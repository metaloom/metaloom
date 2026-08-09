package io.metaloom.loom.rest.model.searchindex;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Body of {@code POST /api/v1/search-indices/:id/jobs}.
 *
 * <p>
 * The action travels in the body rather than as three separate routes so that a client polls one job collection regardless of what it started, and so
 * that an index which does not support an action rejects it with a reason rather than 404-ing a path that exists for its neighbour.
 * </p>
 */
public class IndexJobCreateRequest implements RestRequestModel {

	@JsonProperty(required = true)
	@JsonPropertyDescription("REINDEX rebuilds from the system of record, DELTA_SYNC writes what is missing and removes orphans, DROP empties the index without refilling it. Must be one of the index's supportedActions.")
	private String action;

	public String getAction() {
		return action;
	}

	public IndexJobCreateRequest setAction(String action) {
		this.action = action;
		return this;
	}
}
