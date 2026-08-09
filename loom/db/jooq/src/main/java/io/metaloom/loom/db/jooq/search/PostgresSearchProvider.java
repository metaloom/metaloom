package io.metaloom.loom.db.jooq.search;

import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.nullsLast;
import static java.util.Comparator.reverseOrder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.FacetBucket;
import io.metaloom.loom.api.search.RankFusion;
import io.metaloom.loom.api.search.SearchCapability;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchHit;
import io.metaloom.loom.api.search.SearchMode;
import io.metaloom.loom.api.search.SearchProvider;
import io.metaloom.loom.api.search.SearchProviderInfo;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchResult;
import io.metaloom.loom.api.search.SearchSuggestion;
import io.metaloom.loom.api.search.TextEmbedder;
import io.metaloom.loom.api.search.VectorHit;
import io.metaloom.loom.api.search.VectorIndex;
import io.metaloom.loom.api.search.VectorQuery;

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

	private static final Set<SearchCapability> LEXICAL_CAPABILITIES = EnumSet.of(
		SearchCapability.LEXICAL,
		SearchCapability.PHRASE,
		SearchCapability.FUZZY,
		SearchCapability.HIGHLIGHT,
		SearchCapability.FACETS,
		SearchCapability.EXACT_TOTAL,
		SearchCapability.SUGGEST);

	/** Value of {@code matchedIn} on a hit only the vector ranker found, so "why is this here?" has an answer. */
	public static final String MATCHED_IN_SEMANTIC = "semantic";

	/**
	 * The lexical relevance expression, shared by the ranking query and the hybrid candidate query so the two cannot drift into ranking differently.
	 *
	 * <p>
	 * {@code ts_rank_cd} normalization flag 32 is {@code rank/(rank+1)}, which bounds the score into {@code [0,1)} and so makes it commensurable with
	 * {@code similarity()}. Without it the blend is meaningless. Its five placeholders must be bound in the order {@link #scoreBinds(String)} returns.
	 * </p>
	 */
	private static final String SCORE_EXPRESSION = "greatest("
		+ "ts_rank_cd(text_search, websearch_to_tsquery('simple', ?), 32),"
		+ "ts_rank_cd(text_search_en, websearch_to_tsquery(?::regconfig, ?), 32)"
		+ ") + ? * similarity(trgm_text, ?)";

	private final DSLContext ctx;

	private final SearchOptions options;

	private final TextEmbedder embedder;

	private final VectorIndex vectorIndex;

	@Inject
	public PostgresSearchProvider(DSLContext ctx, SearchOptions options, TextEmbedder embedder, VectorIndex vectorIndex) {
		this.ctx = ctx;
		this.options = options;
		this.embedder = embedder;
		this.vectorIndex = vectorIndex;
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

	/**
	 * What this deployment can do, computed rather than constant.
	 *
	 * <p>
	 * The vector half depends on two things outside this class - an embedding host that answers, and a vector index that is open - and either can be
	 * absent or fail at runtime. Recomputing means {@code /search/status} tells the truth after the embedding host goes down, so the UI's mode toggle
	 * disappears instead of offering a mode that now 400s.
	 * </p>
	 */
	@Override
	public Set<SearchCapability> capabilities() {
		Set<SearchCapability> capabilities = EnumSet.copyOf(LEXICAL_CAPABILITIES);
		if (semanticReady()) {
			capabilities.add(SearchCapability.SEMANTIC);
			capabilities.add(SearchCapability.HYBRID);
		}
		return capabilities;
	}

	/** True when a query can actually be embedded and matched against an index right now. Never throws. */
	private boolean semanticReady() {
		try {
			return options.isSemanticEnabled() && embedder.isAvailable() && vectorIndex.isAvailable();
		} catch (Exception e) {
			log.debug("Semantic readiness check failed", e);
			return false;
		}
	}

	@Override
	public SearchResult search(SearchRequest request) {
		validate(request);
		if (request.getMode() != SearchMode.LEXICAL) {
			return fusedSearch(request);
		}
		return lexicalSearch(request);
	}

	private SearchResult lexicalSearch(SearchRequest request) {
		long started = System.currentTimeMillis();
		String term = request.getQuery().trim();
		List<Object> binds = new ArrayList<>();
		StringBuilder where = new StringBuilder();

		appendMatch(where, binds, term);
		appendFilters(request, where, binds);

		List<Object> selectBinds = new ArrayList<>(scoreBinds(term));
		selectBinds.addAll(binds);

		String sql = "SELECT entity_type, entity_uuid, asset_uuid, title, subtitle, mime_type, size,"
			+ " time_from, sort_date, " + SCORE_EXPRESSION + " AS score, count(*) OVER () AS total_hits"
			+ " FROM search_document WHERE " + where
			+ " ORDER BY " + orderBy(request.getSort())
			+ " LIMIT ? OFFSET ?";

		selectBinds.add(effectiveLimit(request));
		selectBinds.add(request.getOffset());

		Result<Record> records = runSearch(sql, selectBinds.toArray(), term);

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

	// --- Semantic and hybrid ----------------------------------------------------------------------

	/** One row of {@code search_document}, identified the way its primary key identifies it. */
	private record EntityKey(String type, UUID uuid) {
	}

	/**
	 * Rank by meaning ({@code SEMANTIC}) or by both rankers fused ({@code HYBRID}).
	 *
	 * <p>
	 * <b>Why fusion happens in Java rather than in one SQL statement.</b> The design sketch in the spec assumed pgvector, which would put the vectors in
	 * a table and let a single {@code FULL OUTER JOIN} do the work. These vectors live in the {@code VectorIndex} instead - outside Postgres, behind the
	 * same SPI the face vectors use - which costs one extra round trip and buys the thing that decision was made for: no {@code CREATE EXTENSION}, so
	 * nothing here can break {@code generate.sh}, {@code setup-pool.sh} or a stock Postgres image. Both rankers are capped at
	 * {@code LOOM_SEARCH_VECTOR_TOPK}, so the fused set is bounded and small enough that sorting it in memory is not the cost that matters.
	 * </p>
	 *
	 * <p>
	 * <b>Totals are honest about being capped.</b> Neither ranker is exhaustive here, so {@code totalExact} is false and the count describes the
	 * candidate pool, not the corpus. Reporting an exact total would require running the lexical query twice for a number the ranking cannot honour.
	 * </p>
	 */
	private SearchResult fusedSearch(SearchRequest request) {
		long started = System.currentTimeMillis();
		String term = request.getQuery().trim();
		boolean hybrid = request.getMode() == SearchMode.HYBRID;
		int topK = options.getVectorTopK();

		List<EntityKey> vectorRanked = vectorRanking(term, topK);
		List<EntityKey> lexicalRanked = hybrid ? lexicalRanking(request, term, topK) : List.of();

		List<RankFusion.Fused<EntityKey>> fused = RankFusion.rrf(options.getRrfK(), List.of(
			new RankFusion.WeightedRanking<>(lexicalRanked, hybrid ? options.getRrfWeightLexical() : 0),
			new RankFusion.WeightedRanking<>(vectorRanked, options.getRrfWeightVector())));

		// Filters are applied while hydrating rather than inside either ranker: the vector index knows
		// nothing about mime types or tags, so the only place both rankers' candidates can be filtered by
		// the same predicate is against search_document, once they are back together.
		Map<EntityKey, Record> rows = hydrate(request, fused.stream().map(RankFusion.Fused::key).toList());

		SearchResult result = new SearchResult().setProviderName(NAME).setTotalExact(false);
		List<Record> surviving = new ArrayList<>();
		List<Double> scores = new ArrayList<>();
		for (RankFusion.Fused<EntityKey> entry : fused) {
			Record row = rows.get(entry.key());
			if (row != null) {
				surviving.add(row);
				scores.add(entry.score());
			}
		}
		result.setTotalHits(surviving.size());
		reorder(surviving, scores, request.getSort());

		Set<EntityKey> vectorOnly = new LinkedHashSet<>(vectorRanked);
		lexicalRanked.forEach(vectorOnly::remove);

		int from = Math.min(request.getOffset(), surviving.size());
		int to = Math.min(from + effectiveLimit(request), surviving.size());
		for (int i = from; i < to; i++) {
			SearchHit hit = toHit(surviving.get(i)).setScore(scores.get(i));
			if (vectorOnly.contains(new EntityKey(hit.getType().id(), hit.getUuid()))) {
				// Nothing in the text matched the words the user typed, so a lexical "matched in body" would
				// be a lie and ts_headline has nothing to highlight. Say what actually found it.
				hit.setMatchedIn(MATCHED_IN_SEMANTIC);
			}
			result.addHit(hit);
		}

		if (request.isHighlight() && options.isHighlightEnabled() && !result.getHits().isEmpty()) {
			enrichFused(result.getHits(), term);
		}
		if (!request.getFacets().isEmpty()) {
			// Counted over the candidate pool, which is what the result set is. A corpus-wide count would
			// disagree with a capped ranking and invite the user to filter to something with no hits in it.
			result.setFacets(candidateFacets(request, surviving));
			result.addWarning("Facet counts in " + request.getMode() + " mode describe the ranked candidates, not the whole catalog.");
		}

		result.setTookMs(System.currentTimeMillis() - started);
		return result;
	}

	/**
	 * Apply a non-relevance sort to the fused candidates, keeping each row with its score.
	 *
	 * <p>
	 * The lexical path sorts in SQL, which fusion cannot: the order it produces is the fusion's own. Without this the UI's sort control would be
	 * silently ignored in {@code SEMANTIC} and {@code HYBRID} - a visible widget that does nothing, which reads as a bug in the ranking rather than a
	 * missing feature. Note what it sorts: the top {@code topK} candidates, not the catalog, so "newest" means the newest of the best matches.
	 * </p>
	 */
	private void reorder(List<Record> rows, List<Double> scores, io.metaloom.loom.api.search.SearchSortMode sort) {
		if (sort == io.metaloom.loom.api.search.SearchSortMode.RELEVANCE || rows.size() < 2) {
			return;
		}
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			order.add(i);
		}
		Comparator<Integer> comparator = switch (sort) {
			case NEWEST -> Comparator.comparing(i -> rows.get(i).get("sort_date", Timestamp.class), nullsLast(reverseOrder()));
			case OLDEST -> Comparator.comparing(i -> rows.get(i).get("sort_date", Timestamp.class), nullsLast(naturalOrder()));
			case NAME -> Comparator.comparing(i -> rows.get(i).get("title", String.class), nullsLast(naturalOrder()));
			case SIZE -> Comparator.comparing(i -> rows.get(i).get("size", Long.class), nullsLast(reverseOrder()));
			// Unreachable: RELEVANCE returned above. Kept exhaustive so a new sort mode fails to compile.
			case RELEVANCE -> Comparator.comparingInt(i -> i);
		};
		// Ties fall back to the fused order, so paging stays stable for rows sharing a date, name or size.
		order.sort(comparator.thenComparingInt(i -> i));

		List<Record> sortedRows = new ArrayList<>(rows.size());
		List<Double> sortedScores = new ArrayList<>(scores.size());
		for (int index : order) {
			sortedRows.add(rows.get(index));
			sortedScores.add(scores.get(index));
		}
		rows.clear();
		rows.addAll(sortedRows);
		scores.clear();
		scores.addAll(sortedScores);
	}

	/**
	 * Embed the query and ask the vector index for its nearest documents.
	 *
	 * <p>
	 * Every vector belongs to an asset, so hits resolve to {@code entity_type = 'asset'}. That is a real limit rather than an oversight: the corpus is
	 * the per-asset document, and a tag or collection has no asset to embed. Lexical search still finds those by name, which is why {@code HYBRID} is
	 * the mode worth defaulting to.
	 * </p>
	 */
	private List<EntityKey> vectorRanking(String term, int topK) {
		float[] queryVector;
		try {
			queryVector = embedder.embed(term);
		} catch (Exception e) {
			// The capability said this would work, so a failure here is an outage, not a bad request, and
			// must not be answered with an empty list - "no matches" and "the ranker is down" are opposites.
			log.error("Failed to embed the search term", e);
			throw new LoomRestException(503, LoomRestErrorCode.SEARCH_UNAVAILABLE,
				"The embedding host could not be reached, so semantic search is unavailable right now.");
		}
		List<VectorHit> hits;
		try {
			hits = vectorIndex.query(new VectorQuery(embedder.space(), queryVector, topK, (float) options.getVectorMinScore(), null));
		} catch (Exception e) {
			log.error("The vector index query failed", e);
			throw new LoomRestException(503, LoomRestErrorCode.SEARCH_UNAVAILABLE,
				"The vector index could not be queried, so semantic search is unavailable right now.");
		}
		List<EntityKey> ranked = new ArrayList<>(hits.size());
		for (VectorHit hit : hits) {
			if (hit.assetUuid() != null) {
				// Duplicates are left in: RankFusion collapses an asset's repeated appearances to its best
				// rank, which is exactly the wanted behaviour if an asset ever carries several vectors.
				ranked.add(new EntityKey(SearchEntityType.ASSET.id(), hit.assetUuid()));
			}
		}
		return ranked;
	}

	/** The lexical ranking as positions rather than scores - RRF reads only the order. */
	private List<EntityKey> lexicalRanking(SearchRequest request, String term, int topK) {
		List<Object> binds = new ArrayList<>();
		StringBuilder where = new StringBuilder();
		appendMatch(where, binds, term);
		appendFilters(request, where, binds);

		List<Object> selectBinds = new ArrayList<>(scoreBinds(term));
		selectBinds.addAll(binds);
		selectBinds.add(topK);

		Result<Record> records = runSearch("SELECT entity_type, entity_uuid, " + SCORE_EXPRESSION + " AS score"
			+ " FROM search_document WHERE " + where + " ORDER BY score DESC, entity_uuid LIMIT ?", selectBinds.toArray(), term);

		List<EntityKey> ranked = new ArrayList<>(records.size());
		for (Record record : records) {
			ranked.add(new EntityKey(record.get("entity_type", String.class), record.get("entity_uuid", UUID.class)));
		}
		return ranked;
	}

	/**
	 * Fetch the display columns for the fused candidates, dropping any the request's filters exclude.
	 *
	 * <p>
	 * The pair list travels as two parallel arrays through {@code unnest}, which keeps the whole thing to two bind parameters no matter how many
	 * candidates there are - an {@code IN} list of tuples would build SQL that grows with {@code topK}.
	 * </p>
	 */
	private Map<EntityKey, Record> hydrate(SearchRequest request, List<EntityKey> keys) {
		Map<EntityKey, Record> out = new LinkedHashMap<>();
		if (keys.isEmpty()) {
			return out;
		}
		String[] types = new String[keys.size()];
		UUID[] uuids = new UUID[keys.size()];
		for (int i = 0; i < keys.size(); i++) {
			types[i] = keys.get(i).type();
			uuids[i] = keys.get(i).uuid();
		}
		List<Object> binds = new ArrayList<>();
		binds.add(types);
		binds.add(uuids);
		StringBuilder where = new StringBuilder("(entity_type, entity_uuid) IN"
			+ " (SELECT t, u FROM unnest(?::varchar[], ?::uuid[]) AS candidate(t, u))");
		appendFilters(request, where, binds);

		Result<Record> records = runSearch("SELECT entity_type, entity_uuid, asset_uuid, title, subtitle, mime_type, size,"
			+ " time_from, sort_date, lang, 0::float8 AS score FROM search_document WHERE " + where, binds.toArray(), request.getQuery());
		for (Record record : records) {
			out.put(new EntityKey(record.get("entity_type", String.class), record.get("entity_uuid", UUID.class)), record);
		}
		return out;
	}

	/** Facets over the candidate rows already in hand - no second query, and no count the ranking cannot honour. */
	private Map<String, List<FacetBucket>> candidateFacets(SearchRequest request, List<Record> rows) {
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
			Map<String, Long> counts = new LinkedHashMap<>();
			for (Record row : rows) {
				String value = row.get(column, String.class);
				if (value != null && !value.isBlank()) {
					counts.merge(value, 1L, (a, b) -> a + b);
				}
			}
			out.put(facet, counts.entrySet().stream()
				.sorted(Map.Entry.<String, Long> comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(50)
				.map(entry -> new FacetBucket(entry.getKey(), entry.getValue()))
				.toList());
		}
		return out;
	}

	/**
	 * Highlight the hits that have something to highlight.
	 *
	 * <p>
	 * A hit found only by the vector ranker contains none of the typed words by definition, so {@code ts_headline} would return an empty fragment and
	 * the {@code CASE} in {@link #enrich} would relabel it {@code fuzzy}, overwriting the accurate {@code semantic}. Skipping those keeps the label.
	 * </p>
	 */
	private void enrichFused(List<SearchHit> hits, String term) {
		List<SearchHit> lexical = hits.stream().filter(hit -> !MATCHED_IN_SEMANTIC.equals(hit.getMatchedIn())).toList();
		if (!lexical.isEmpty()) {
			enrich(lexical, term);
		}
	}

	/** Why semantic search is off, in the terms of the thing an operator has to change. */
	private String semanticUnavailableReason() {
		if (!options.isSemanticEnabled()) {
			return "it is disabled (LOOM_SEARCH_SEMANTIC_ENABLED=false).";
		}
		try {
			if (!embedder.isAvailable()) {
				return "the embedding host (LOOM_SEARCH_EMBED_URL) is not reachable.";
			}
			if (!vectorIndex.isAvailable()) {
				return "the vector index (LOOM_VECTOR_INDEX_PROVIDER) is not available.";
			}
		} catch (Exception e) {
			return "the embedding host or vector index could not be checked.";
		}
		return "it is not available.";
	}

	@Override
	public List<SearchSuggestion> suggest(String prefix, Set<SearchEntityType> types, int limit) {
		if (prefix == null || prefix.isBlank()) {
			return List.of();
		}
		String term = prefix.trim();
		StringBuilder where = new StringBuilder("(trgm_text ILIKE ? OR trgm_text % ?::text)");

		// Bind order follows the order the placeholders appear in the SQL text, and the similarity()
		// call in the SELECT list precedes every predicate in the WHERE clause.
		List<Object> binds = new ArrayList<>();
		binds.add(term);
		binds.add(term + "%");
		binds.add(term);
		if (types != null && !types.isEmpty()) {
			where.append(" AND entity_type = ANY(?)");
			binds.add(idsOf(types));
		}
		binds.add(Math.max(1, Math.min(limit, options.getMaxLimit())));

		try {
			Result<Record> records = runSearch("SELECT entity_type, entity_uuid, title,"
				+ " similarity(trgm_text, ?) AS score FROM search_document WHERE " + where
				+ " ORDER BY score DESC, title ASC LIMIT ?", binds.toArray(), term);
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
			.setCapabilities(new LinkedHashSet<>(capabilities()));
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

	/**
	 * Run a query with the configured trigram threshold applied.
	 *
	 * <p>
	 * The threshold is a session GUC that the {@code %} operator reads. {@code SET LOCAL} only survives inside a transaction, and a pooled connection
	 * must not be left mutated, so the SET and the query are wrapped in one transaction - that also guarantees they run on the same connection.
	 * </p>
	 */
	private Result<Record> runSearch(String sql, Object[] binds, String term) {
		try {
			return ctx.transactionResult(cfg -> {
				DSLContext tx = DSL.using(cfg);
				tx.execute("SET LOCAL pg_trgm.similarity_threshold = " + threshold());
				return tx.fetch(sql, binds);
			});
		} catch (LoomRestException e) {
			throw e;
		} catch (Exception e) {
			log.error("Search query failed for term '{}'", term, e);
			throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "The search query could not be executed.");
		}
	}

	private void validate(SearchRequest request) {
		if (request.getQuery() == null || request.getQuery().isBlank()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "A search term (q) is required.");
		}
		if (request.getQuery().length() > SearchRequest.MAX_QUERY_LENGTH) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS,
				"The search term must not exceed " + SearchRequest.MAX_QUERY_LENGTH + " characters.");
		}
		if (request.getMode() != SearchMode.LEXICAL && !semanticReady()) {
			// Still a 400 naming the reason, never a silent lexical answer wearing a semantic label. The
			// reason is specific because the three causes need three different fixes.
			throw new LoomRestException(400, LoomRestErrorCode.SEARCH_UNSUPPORTED,
				"The " + NAME + " search provider cannot serve " + request.getMode() + " mode: " + semanticUnavailableReason()
					+ " Only " + SearchMode.LEXICAL + " mode is available.");
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

	/**
	 * The match predicate: exact tokens, stemmed tokens, or a close-enough trigram match on title/subtitle.
	 *
	 * <p>
	 * The trigram branch uses the {@code %} operator rather than {@code similarity() >= x} so that the GIN trigram index can be used; its threshold
	 * comes from {@code pg_trgm.similarity_threshold}, which is why every query built on this runs inside the transaction that sets it (see
	 * {@link #runSearch}).
	 * </p>
	 */
	private void appendMatch(StringBuilder where, List<Object> binds, String term) {
		where.append("(text_search @@ websearch_to_tsquery('simple', ?)")
			.append(" OR text_search_en @@ websearch_to_tsquery(?::regconfig, ?)")
			.append(" OR (trgm_text % ?::text))");
		binds.add(term);
		binds.add(options.getTsConfig());
		binds.add(term);
		binds.add(term);
	}

	/** Binds for {@link #SCORE_EXPRESSION}, in the order its placeholders appear. */
	private List<Object> scoreBinds(String term) {
		return List.of(term, options.getTsConfig(), term, options.getTrigramWeight(), term);
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
