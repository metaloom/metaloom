package io.metaloom.loom.rest.model.search;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * One facet value and how many hits carry it.
 */
public class SearchFacetResponse implements RestResponseModel<SearchFacetResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("The facet value.")
	private String value;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Number of matching elements carrying this value.")
	private long count;

	public SearchFacetResponse() {
	}

	public SearchFacetResponse(String value, long count) {
		this.value = value;
		this.count = count;
	}

	public String getValue() {
		return value;
	}

	public SearchFacetResponse setValue(String value) {
		this.value = value;
		return this;
	}

	public long getCount() {
		return count;
	}

	public SearchFacetResponse setCount(long count) {
		this.count = count;
		return this;
	}

	@Override
	public SearchFacetResponse self() {
		return this;
	}
}
