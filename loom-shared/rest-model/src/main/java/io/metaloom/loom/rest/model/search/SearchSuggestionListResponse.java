package io.metaloom.loom.rest.model.search;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

/**
 * Response of {@code GET /api/v1/search/suggestions}.
 */
public class SearchSuggestionListResponse extends AbstractListResponse<SearchSuggestionListResponse, SearchSuggestionResponse> {

	@Override
	public SearchSuggestionListResponse self() {
		return this;
	}
}
