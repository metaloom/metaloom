package io.metaloom.loom.client.common.method;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.search.SearchResultResponse;
import io.metaloom.loom.rest.model.search.SearchStatusResponse;
import io.metaloom.loom.rest.model.search.SearchSuggestionListResponse;

/**
 * Client access to the search routes.
 *
 * <p>
 * Not optional: the endpoint tests drive everything through the client, so a route without a client method cannot be tested.
 * </p>
 */
public interface SearchMethods {

	/**
	 * Cross-entity search.
	 *
	 * @param query
	 *            the search term
	 * @return the ranked result
	 */
	LoomClientRequest<SearchResultResponse> search(String query);

	/**
	 * Cross-entity search with extra query parameters.
	 *
	 * @param query
	 *            the search term
	 * @param parameters
	 *            additional parameters as alternating key/value pairs, e.g. {@code "types", "asset", "limit", "10"}
	 * @return the ranked result
	 */
	LoomClientRequest<SearchResultResponse> search(String query, String... parameters);

	/**
	 * Asset-only search.
	 *
	 * @param query
	 *            the search term
	 * @param parameters
	 *            additional parameters as alternating key/value pairs
	 * @return the ranked result, restricted to assets
	 */
	LoomClientRequest<SearchResultResponse> searchAssets(String query, String... parameters);

	/**
	 * Typeahead suggestions.
	 *
	 * @param prefix
	 *            partial term
	 * @param parameters
	 *            additional parameters as alternating key/value pairs
	 * @return the suggestions
	 */
	LoomClientRequest<SearchSuggestionListResponse> searchSuggestions(String prefix, String... parameters);

	/**
	 * Which backend is bound, whether it is available, and what it can do.
	 *
	 * @return the status, which answers 200 even when search is unavailable
	 */
	LoomClientRequest<SearchStatusResponse> searchStatus();
}
