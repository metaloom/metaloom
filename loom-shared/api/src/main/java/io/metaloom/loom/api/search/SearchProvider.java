package io.metaloom.loom.api.search;

import java.util.List;
import java.util.Set;

/**
 * Read side of search. Exactly one implementation is bound at runtime, selected by {@code LOOM_SEARCH_PROVIDER}.
 *
 * <p>
 * Implementations must never throw from {@link #name()}, {@link #isAvailable()}, {@link #capabilities()} or {@link #info()} - the status route calls
 * them precisely when something is broken.
 * </p>
 *
 * @see io.metaloom.loom.api.search.SearchCapability for how a provider declines work it cannot do
 */
public interface SearchProvider {

	/** Stable identifier: {@code postgres}, {@code elasticsearch} or {@code none}. Appears in responses and in error messages. */
	String name();

	/** Whether queries can be served right now. */
	boolean isAvailable();

	/** What this provider can do. Callers check before asking for deep paging, semantic mode, facets or highlighting. */
	Set<SearchCapability> capabilities();

	/**
	 * Run a query.
	 *
	 * @param request
	 *            the query; the term is in {@link SearchRequest#getQuery()}
	 * @return the result, never null
	 * @throws io.metaloom.loom.api.error.LoomRestException
	 *             503 when unavailable, 400 when the request asks for a capability this provider lacks
	 */
	SearchResult search(SearchRequest request);

	/**
	 * Prefix typeahead. Providers without {@link SearchCapability#SUGGEST} return an empty list rather than throwing, because a suggestion box that
	 * errors is worse than one that stays quiet.
	 */
	List<SearchSuggestion> suggest(String prefix, Set<SearchEntityType> types, int limit);

	/** Status for the operator and the UI. */
	SearchProviderInfo info();
}
