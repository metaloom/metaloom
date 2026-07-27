package io.metaloom.loom.db.jooq.search;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.FacetBucket;
import io.metaloom.loom.api.search.SearchCapability;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchMode;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.SearchProviderInfo;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.api.search.SearchSuggestion;

/**
 * Search backed by PostgreSQL full-text search plus {@code pg_trgm}, over the {@code search_document} table.
 *
 * <p>
 * <b>Why raw SQL.</b> The three columns that do the work - {@code text_search}, {@code text_search_en} and {@code trgm_text} - are
 * {@code GENERATED ALWAYS AS ... STORED} and are deliberately excluded from jOOQ code generation, both because jOOQ has no {@code tsvector} binding and
 * because a generated column must never reach an INSERT/UPDATE. They are therefore addressed by name, the same technique
 * {@code AssetComponentDaoImpl} already uses for its raw lookups.
 * </p>
 *
 * <p>
 * <b>Why {@code websearch_to_tsquery}.</b> It is the only query parser that accepts what a user actually types - quoted phrases, {@code or},
 * {@code -negation} - <i>without throwing on malformed input</i>. {@code to_tsquery} raises a syntax error on a stray {@code &}, which would turn every
 * clumsy query into a 500.
 * </p>
 *
 * <p>
 * All user input travels as a bind parameter. The only interpolated SQL is built from enums this class controls.
 * </p>
 */
@Singleton
public class PostgresSearchProvider implements SearchProvider {

	private static final Logger log = LoggerFactory.getLogger(PostgresSearchProvider.class);

	public static final String NAME = "postgres";

	private static final Set<SearchCapability> CAPABILITIES = EnumSet.of(
		SearchCapability.LEXICAL,
		SearchCapability.PHRASE,
		SearchCapability.FUZZY,
		SearchCapability.HIGHLIGHT,
		SearchCapability.FACETS,
		SearchCapability.EXACT_TOTAL,
		SearchCapability.SUGGEST);

	private final DSLContext ctx;

	private final SearchOptions options;

	@Inject
	public PostgresSearchProvider(DSLContext ctx, SearchOptions options) {
		this.ctx = ctx;
		this.options = options;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public boolean isAvailable() {
		try {
			ctx.fetchOne("SELECT 1 FROM search_document LIMIT 1");
			return true;
		} catch (Exception e) {
			log.warn("Search document table is not queryable", e);
			return false;
		}
	}

	@Override
	public Set<SearchCapability> capabilities() {
		return CAPABILITIES;
	}

	@Override
	public SearchResult search(SearchRequest request) {
		long started = System.currentTimeMillis();
		validate(request);

		String term = request.getQuery().trim();
		List<Object> binds = new ArrayList<>();
		StringBuilder where = new StringBuilder();

		// Matching: exact tokens, stemmed tokens, or a close-enough trigram match on title/subtitle.
		where.append("(text_search @@ websearch_to_tsquery('simple', ?)")
			.append(" OR text_search_en @@ websearch_to_tsquery(?::regconfig, ?)")
			.append(" OR (trgm_text %% ?))");
		binds.add(term);
		binds.add(options.getTsConfig());
		binds.add(term);
		binds.add(term);

		appendFilters(request, where, binds);

		// ts_rank_cd normalization flag 32 is rank/(rank+1), which bounds the score into [0,1) and so
		// makes it commensurable with similarity(). Without it the blend below is meaningless.
		String score = "greatest("
			+ "ts_rank_cd(text_search, websearch_to_tsquery('simple', ?), 32),"
			+ "ts_rank_cd(text_search_en, websearch_to_tsquery(?::regconfig, ?), 32)"
			+ ") + ? * similarity(trgm_text, ?)";
		List<Object> scoreBinds = List.of(term, options.getTsConfig(), term, options.getTrigramWeight(), term);

		List<Object> selectBinds = new ArrayList<>(scoreBinds);
		selectBinds.addAll(binds);

		String sql = "SELECT entity_type, entity_uuid, asset_uuid, title, subtitle, mime_type, size,"
			+ " time_from, sort_date, " + score + " AS score, count(*) OVER () AS total_hits"
			+ " FROM search_document WHERE " + where
			+ " ORDER BY " + orderBy(request.getSort())
			+ " LIMIT ? OFFSET ?";

		selectBinds.add(effectiveLimit(request));
		selectBinds.add(request.getOffset());

		Result<Record> records;
		try {
			ctx.execute("SET LOCAL pg_trgm.similarity_threshold = " + threshold());
			records = ctx.fetch(sql, selectBinds.toArray());
		} catch (Exception e) {
			log.error("Search query failed for term '{}'", term, e);
			throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "The search query could not be executed.");
		}

		SearchResult result = new SearchResult().setProviderName(NAME).setTotalExact(true);
		for (Record record : records) {
			result.addHit(toHit(record));
			if (result.getTotalHits() == 0) {
				result.setTotalHits(((Number) record.get("total_hits")).longValue());
			}
		}

		if (request.isHighlight() && options.isHighlightEnabled() && !result.getHits().isEmpty()) {
			enrich(result.getHits(), term);
		}
		if (!request.getFacets().isEmpty()) {
			result.setFacets(facets(request, where.toString(), binds));
		}

		result.setTookMs(System.currentTimeMillis() - started);
		return result;
	}

	@Override
	public List<SearchSuggestion> suggest(String prefix, Set<SearchEntityType> types, int limit) {
		if (prefix == null || prefix.isBlank()) {
			return List.of();
		}
		String term = prefix.trim();
		List<Object> binds = new ArrayList<>();
		StringBuilder where = new StringBuilder("(trgm_text ILIKE ? OR trgm_text %% ?)");
		binds.add(term + "%");
		binds.add(term);
		if (types != null && !types.isEmpty()) {
			where.append(" AND entity_type = ANY(?)");
			binds.add(idsOf(types));
		}
		binds.add(term);
		binds.add(Math.max(1, Math.min(limit, options.getMaxLimit())));

		try {
			ctx.execute("SET LOCAL pg_trgm.similarity_threshold = " + threshold());
			Result<Record> records = ctx.fetch("SELECT entity_type, entity_uuid, title,"
				+ " similarity(trgm_text, ?) AS score FROM search_document WHERE " + where
				+ " ORDER BY score DESC, title ASC LIMIT ?", binds.toArray());
			List<SearchSuggestion> out = new ArrayList<>();
			for (Record record : records) {
				out.add(new SearchSuggestion(
					record.get("title", String.class),
					SearchEntityType.fromString(record.get("entity_type", String.class)),
					record.get("entity_uuid", UUID.class),
					record.get("score", Double.class)));
			}
			return out;
		} catch (Exception e) {
			// A suggestion box that errors is worse than one that stays quiet.
			log.warn("Suggest query failed for prefix '{}'", term, e);
			return List.of();
		}
	}

	@Override
	public SearchProviderInfo info() {
		SearchProviderInfo info = new SearchProviderInfo()
			.setProvider(NAME)
			.setCapabilities(new LinkedHashSet<>(CAPABILITIES));
		try {
			info.setDocumentCount(ctx.fetchOne("SELECT count(*) AS c FROM search_document").get("c", Long.class));
			info.setAvailable(true);
			// The Postgres provider queries the index directly, so it has no sync lag by construction.
			info.setDirtyCount(0);
			info.setLastSyncedAt(Instant.now());
		} catch (Exception e) {
			info.setAvailable(false).setReason("search_document is not queryable: " + e.getMessage());
		}
		return info;
	}

	// ---------------------------------------------------------------------------------------------

	private void validate(SearchRequest request) {
		if (request.getQuery() == null || request.getQuery().isBlank()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "A search term (q) is required.");
		}
		if (request.getQuery().length() > SearchRequest.MAX_QUERY_LENGTH) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS,
				"The search term must not exceed " + SearchRequest.MAX_QUERY_LENGTH + " characters.");
		}
		if (request.getMode() != SearchMode.LEXICAL) {
			throw new LoomRestException(400, LoomRestErrorCode.SEARCH_UNSUPPORTED,
				"The " + NAME + " search provider supports only " + SearchMode.LEXICAL + " mode. Requested: " + request.getMode() + ".");
		}
		if (request.getOffset() < 0) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "The offset must not be negative.");
		}
		if (request.getOffset() > options.getMaxOffset()) {
			// Deep paging degrades into a table scan here. Say so, rather than time out.
			throw new LoomRestException(400, LoomRestErrorCode.SEARCH_UNSUPPORTED,
				"The " + NAME + " search provider does not support paging past offset " + options.getMaxOffset()
					+ ". Narrow the query instead.");
		}
	}

	private void appendFilters(SearchRequest request, StringBuilder where, List<Object> binds) {
		if (!request.getTypes().isEmpty()) {
			where.append(" AND entity_type = ANY(?)");
			binds.add(idsOf(request.getTypes()));
		}
		if (request.getMimeTypePrefix() != null && !request.getMimeTypePrefix().isBlank()) {
			where.append(" AND mime_type LIKE ?");
			binds.add(request.getMimeTypePrefix() + "%");
		}
		if (request.getLibraryUuid() != null) {
			where.append(" AND library_uuids @> ARRAY[?]::uuid[]");
			binds.add(request.getLibraryUuid());
		}
		if (request.getSpaceUuid() != null) {
			where.append(" AND space_uuids @> ARRAY[?]::uuid[]");
			binds.add(request.getSpaceUuid());
		}
		if (request.getCollectionUuid() != null) {
			where.append(" AND collection_uuids @> ARRAY[?]::uuid[]");
			binds.add(request.getCollectionUuid());
		}
		if (!request.getTags().isEmpty()) {
			where.append(" AND tag_names && ?");
			binds.add(request.getTags().toArray(new String[0]));
		}
		if (request.getLang() != null && !request.getLang().isBlank()) {
			where.append(" AND lang = ?");
			binds.add(request.getLang());
		}
		if (request.getCreatedFrom() != null) {
			where.append(" AND sort_date >= ?");
			binds.add(Timestamp.from(request.getCreatedFrom()));
		}
		if (request.getCreatedTo() != null) {
			where.append(" AND sort_date <= ?");
			binds.add(Timestamp.from(request.getCreatedTo()));
		}
		// Reserved ACL narrowing. Nothing populates these today - enforcement is a global permission
		// gate, matching every other list route - but the index column is already here, so switching
		// on row-level ACL later is exactly this clause and no reindex.
		if (request.getAllowedLibraryUuids() != null) {
			where.append(" AND (library_uuids = '{}' OR library_uuids && ?)");
			binds.add(request.getAllowedLibraryUuids().toArray(new UUID[0]));
		}
		if (request.getAllowedSpaceUuids() != null) {
			where.append(" AND (space_uuids = '{}' OR space_uuids && ?)");
			binds.add(request.getAllowedSpaceUuids().toArray(new UUID[0]));
		}
	}

	/**
	 * Second pass over the returned page only.
	 *
	 * <p>
	 * {@code ts_headline} re-parses the original document text, is O(document size) and cannot use an index. Running it inside the ranking query would
	 * make it run for every match rather than for the ~25 rows actually being returned, which is the difference between a 20ms search and a 20s one.
	 * </p>
	 */
	private void enrich(List<SearchHit> hits, String term) {
		try {
			for (SearchHit hit : hits) {
				Record record = ctx.fetchOne("SELECT"
					+ " ts_headline('simple', coalesce(nullif(body,''), coalesce(nullif(subtitle,''), title)),"
					+ "   websearch_to_tsquery('simple', ?), 'MaxFragments=2,MinWords=4,MaxWords=18') AS snippet,"
					+ " CASE WHEN to_tsvector('simple', title)    @@ websearch_to_tsquery('simple', ?) THEN 'title'"
					+ "      WHEN to_tsvector('simple', subtitle) @@ websearch_to_tsquery('simple', ?) THEN 'subtitle'"
					+ "      WHEN to_tsvector('simple', body)     @@ websearch_to_tsquery('simple', ?) THEN 'body'"
					+ "      WHEN to_tsvector('simple', keywords) @@ websearch_to_tsquery('simple', ?) THEN 'keywords'"
					+ "      ELSE 'fuzzy' END AS matched_in"
					+ " FROM search_document WHERE entity_type = ? AND entity_uuid = ?",
					term, term, term, term, term, hit.getType().id(), hit.getUuid());
				if (record != null) {
					String snippet = record.get("snippet", String.class);
					if (snippet != null && !snippet.isBlank()) {
						hit.getHighlights().add(snippet);
					}
					hit.setMatchedIn(record.get("matched_in", String.class));
				}
			}
		} catch (Exception e) {
			// Highlighting is cosmetic. Losing it must not lose the results.
			log.warn("Failed to compute highlights", e);
		}
	}

	private Map<String, List<FacetBucket>> facets(SearchRequest request, String where, List<Object> binds) {
		Map<String, List<FacetBucket>> out = new LinkedHashMap<>();
		for (String facet : request.getFacets()) {
			String column = switch (facet) {
				case "mime_type", "mime" -> "mime_type";
				case "entity_type", "type" -> "entity_type";
				case "lang" -> "lang";
				default -> null;
			};
			if (column == null) {
				continue;
			}
			try {
				Result<Record> rows = ctx.fetch("SELECT " + column + " AS v, count(*) AS c FROM search_document WHERE "
					+ where + " AND " + column + " IS NOT NULL GROUP BY 1 ORDER BY c DESC, v ASC LIMIT 50", binds.toArray());
				List<FacetBucket> buckets = new ArrayList<>();
				for (Record row : rows) {
					buckets.add(new FacetBucket(row.get("v", String.class), row.get("c", Long.class)));
				}
				out.put(facet, buckets);
			} catch (Exception e) {
				log.warn("Failed to compute facet {}", facet, e);
			}
		}
		return out;
	}

	private SearchHit toHit(Record record) {
		SearchEntityType type = SearchEntityType.fromString(record.get("entity_type", String.class));
		Timestamp sortDate = record.get("sort_date", Timestamp.class);
		return new SearchHit()
			.setType(type)
			.setUuid(record.get("entity_uuid", UUID.class))
			.setAssetUuid(record.get("asset_uuid", UUID.class))
			.setTitle(record.get("title", String.class))
			.setSubtitle(record.get("subtitle", String.class))
			.setMimeType(record.get("mime_type", String.class))
			.setSize(record.get("size", Long.class))
			.setTimeFromMs(record.get("time_from", Long.class))
			.setSortDate(sortDate == null ? null : sortDate.toInstant())
			.setScore(record.get("score", Double.class));
	}

	private String orderBy(io.metaloom.loom.api.search.SearchSortMode sort) {
		// Built from an enum this class owns - never from caller input.
		return switch (sort) {
			case NEWEST -> "sort_date DESC NULLS LAST, entity_uuid";
			case OLDEST -> "sort_date ASC NULLS LAST, entity_uuid";
			case NAME -> "title ASC, entity_uuid";
			case SIZE -> "size DESC NULLS LAST, entity_uuid";
			case RELEVANCE -> "score DESC, entity_uuid";
		};
	}

	private int effectiveLimit(SearchRequest request) {
		int limit = request.getLimit() <= 0 ? options.getDefaultLimit() : request.getLimit();
		return Math.min(limit, options.getMaxLimit());
	}

	private String threshold() {
		// Numeric, from validated options - not caller input.
		return Double.toString(options.getTrigramThreshold());
	}

	private String[] idsOf(Set<SearchEntityType> types) {
		return types.stream().map(SearchEntityType::id).toArray(String[]::new);
	}
}
