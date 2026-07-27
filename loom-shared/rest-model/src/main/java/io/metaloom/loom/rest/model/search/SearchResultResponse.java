package io.metaloom.loom.rest.model.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * Response of {@code GET /api/v1/search/results}.
 */
public class SearchResultResponse implements RestResponseModel<SearchResultResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Array which contains the found elements, most relevant first.")
	private List<SearchHitResponse> data = new ArrayList<>();

	@JsonProperty(value = "_metainfo", required = true)
	@JsonPropertyDescription("Paging, timing and provider information for the result.")
	private SearchMetaInfo metainfo;

	@JsonPropertyDescription("Facet counts, keyed by the requested facet name.")
	private Map<String, List<SearchFacetResponse>> facets = new LinkedHashMap<>();

	public List<SearchHitResponse> getData() {
		return data;
	}

	public SearchResultResponse setData(List<SearchHitResponse> data) {
		this.data = data;
		return this;
	}

	public SearchResultResponse add(SearchHitResponse hit) {
		this.data.add(hit);
		return this;
	}

	public SearchMetaInfo getMetainfo() {
		return metainfo;
	}

	public SearchResultResponse setMetainfo(SearchMetaInfo metainfo) {
		this.metainfo = metainfo;
		return this;
	}

	public Map<String, List<SearchFacetResponse>> getFacets() {
		return facets;
	}

	public SearchResultResponse setFacets(Map<String, List<SearchFacetResponse>> facets) {
		this.facets = facets;
		return this;
	}

	@Override
	public SearchResultResponse self() {
		return this;
	}
}
