package io.metaloom.loom.rest.model.searchindex;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Response of {@code GET /api/v1/search-indices}.
 *
 * <p>
 * Not a paged {@code ListResponse}: the set is small, bounded by how many embedding models an instance runs, and it is assembled per request from
 * live backend state rather than read from a table - so there is no cursor to page by and no total to report beyond the list itself.
 * </p>
 *
 * <p>
 * Answered with 200 even when every index is disabled, for the same reason {@code /search/status} is: reporting that is the whole job.
 * </p>
 */
public class SearchIndexListResponse implements RestResponseModel<SearchIndexListResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The indices, ordered lexical first, then the vector spaces, then fingerprints.")
	private List<SearchIndexResponse> data = new ArrayList<>();

	@JsonProperty(required = true)
	@JsonPropertyDescription("The storage backends the indices live in. Size on disk is reported here because it has no per-index meaning.")
	private List<SearchIndexBackendResponse> backends = new ArrayList<>();

	public List<SearchIndexResponse> getData() {
		return data;
	}

	public SearchIndexListResponse setData(List<SearchIndexResponse> data) {
		this.data = data;
		return this;
	}

	public List<SearchIndexBackendResponse> getBackends() {
		return backends;
	}

	public SearchIndexListResponse setBackends(List<SearchIndexBackendResponse> backends) {
		this.backends = backends;
		return this;
	}

	@Override
	public SearchIndexListResponse self() {
		return this;
	}
}
