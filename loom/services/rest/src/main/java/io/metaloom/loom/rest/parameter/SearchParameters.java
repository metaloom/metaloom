package io.metaloom.loom.rest.parameter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.api.search.SearchMode;
import io.metaloom.loom.api.search.SearchRequest;
import io.metaloom.loom.api.search.SearchSortMode;
import io.metaloom.loom.rest.LoomRoutingContext;

/**
 * Reads the {@code /api/v1/search/*} query parameters and assembles a {@link SearchRequest}.
 *
 * <p>
 * Mirrors {@link PagingParameters} and friends, but reads {@link SearchQueryParameterKey} rather than {@link QueryParameterKey} - see that enum for
 * why the two are kept apart.
 * </p>
 */
public class SearchParameters {

	private final LoomRoutingContext lrc;

	public SearchParameters(LoomRoutingContext lrc) {
		this.lrc = lrc;
	}

	public static SearchParameters create(LoomRoutingContext lrc) {
		return new SearchParameters(lrc);
	}

	/**
	 * Build the provider-facing request.
	 *
	 * <p>
	 * Validation of the term itself (presence, length) is left to the provider so that every entry point - REST, MCP, GraphQL - enforces the same rules
	 * in the same place.
	 * </p>
	 */
	public SearchRequest toRequest() {
		SearchRequest request = new SearchRequest()
			.setQuery(raw(SearchQueryParameterKey.QUERY))
			.setTypes(types())
			.setMode(mode())
			.setSort(sort())
			.setLimit(intParam(SearchQueryParameterKey.LIMIT))
			.setOffset(intParam(SearchQueryParameterKey.OFFSET))
			.setCursor(raw(SearchQueryParameterKey.CURSOR))
			.setHighlight(boolParam(SearchQueryParameterKey.HIGHLIGHT))
			.setMimeTypePrefix(raw(SearchQueryParameterKey.MIME))
			.setLibraryUuid(uuidParam(SearchQueryParameterKey.LIBRARY))
			.setSpaceUuid(uuidParam(SearchQueryParameterKey.SPACE))
			.setCollectionUuid(uuidParam(SearchQueryParameterKey.COLLECTION))
			.setTags(csv(SearchQueryParameterKey.TAG))
			.setLang(raw(SearchQueryParameterKey.LANG))
			.setProfile(raw(SearchQueryParameterKey.PROFILE))
			.setFacets(new LinkedHashSet<>(csv(SearchQueryParameterKey.FACETS)))
			.setCreatedFrom(instantParam(SearchQueryParameterKey.FROM))
			.setCreatedTo(instantParam(SearchQueryParameterKey.TO));
		request.setFilters(lrc.filterParams().filters());
		return request;
	}

	public Set<SearchEntityType> types() {
		Set<SearchEntityType> types = new LinkedHashSet<>();
		for (String value : csv(SearchQueryParameterKey.TYPES)) {
			SearchEntityType type = SearchEntityType.fromString(value);
			if (type == null) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "Unknown search entity type '" + value + "'.");
			}
			types.add(type);
		}
		return types;
	}

	public int limit() {
		return intParam(SearchQueryParameterKey.LIMIT);
	}

	private SearchMode mode() {
		String value = raw(SearchQueryParameterKey.MODE);
		if (value == null) {
			return SearchMode.LEXICAL;
		}
		SearchMode mode = SearchMode.fromString(value);
		if (mode == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "Unknown search mode '" + value + "'.");
		}
		return mode;
	}

	private SearchSortMode sort() {
		String value = raw(SearchQueryParameterKey.SORT);
		if (value == null) {
			return SearchSortMode.RELEVANCE;
		}
		SearchSortMode sort = SearchSortMode.fromString(value);
		if (sort == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "Unknown search sort mode '" + value + "'.");
		}
		return sort;
	}

	private List<String> csv(SearchQueryParameterKey key) {
		List<String> out = new ArrayList<>();
		// Repeatable and comma separated are both accepted; ?tag=a&tag=b reads the same as ?tag=a,b.
		for (String value : lrc.queryParam(key.key())) {
			for (String part : value.split(",")) {
				String trimmed = part.trim();
				if (!trimmed.isEmpty()) {
					out.add(trimmed);
				}
			}
		}
		return out;
	}

	/**
	 * Return the raw, unconverted value of a parameter, or null when it was not supplied.
	 *
	 * <p>
	 * Deliberately does <b>not</b> fall back to {@link SearchQueryParameterKey#defaultValue()}: those defaults are already typed (LIMIT and OFFSET default
	 * to Integer), so returning one from a String-typed accessor would be a ClassCastException waiting for the first request that omits the parameter.
	 * Defaults are applied by the typed accessors below.
	 * </p>
	 */
	private String raw(SearchQueryParameterKey key) {
		List<String> values = lrc.queryParam(key.key());
		if (values.isEmpty()) {
			return null;
		}
		if (values.size() > 1) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "Parameter " + key.key() + " was found multiple times");
		}
		String value = values.get(0);
		return value == null || value.isBlank() ? null : value;
	}

	private int intParam(SearchQueryParameterKey key) {
		String value = raw(key);
		if (value == null) {
			Integer fallback = key.defaultValue();
			return fallback == null ? 0 : fallback;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "Parameter " + key.key() + " must be an integer.");
		}
	}

	private boolean boolParam(SearchQueryParameterKey key) {
		String value = raw(key);
		if (value == null) {
			Boolean fallback = key.defaultValue();
			return fallback != null && fallback;
		}
		return Boolean.parseBoolean(value);
	}

	private UUID uuidParam(SearchQueryParameterKey key) {
		String value = raw(key);
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "Parameter " + key.key() + " must be a UUID.");
		}
	}

	private Instant instantParam(SearchQueryParameterKey key) {
		String value = raw(key);
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (Exception e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "Parameter " + key.key() + " must be an ISO-8601 instant.");
		}
	}
}
