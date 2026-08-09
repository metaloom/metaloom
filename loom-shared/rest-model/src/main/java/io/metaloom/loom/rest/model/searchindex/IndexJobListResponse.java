package io.metaloom.loom.rest.model.searchindex;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Response of {@code GET /api/v1/search-indices/:id/jobs} - the recent jobs for one index, newest first.
 *
 * <p>
 * Bounded and unpaged. The server keeps only the last handful of finished jobs in memory, so there is nothing to page through; the record an operator
 * needs is "what is running and what did the last run do", not an audit history.
 * </p>
 */
public class IndexJobListResponse implements RestResponseModel<IndexJobListResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Recent jobs for this index, newest first. Includes the running one.")
	private List<IndexJobResponse> data = new ArrayList<>();

	public List<IndexJobResponse> getData() {
		return data;
	}

	public IndexJobListResponse setData(List<IndexJobResponse> data) {
		this.data = data;
		return this;
	}

	@Override
	public IndexJobListResponse self() {
		return this;
	}
}
