package io.metaloom.loom.rest.model.processor.message;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * Reports that a source node has finished enumerating.
 *
 * <p>Body of {@link ProcessorMessageType#SOURCE_COMPLETE}. A run cannot reach a
 * terminal state before this arrives - otherwise the engine could not tell "no
 * more items" from "no items yet".</p>
 */
public class SourceCompleteMessage implements RestModel {

	@JsonProperty(required = true)
	private UUID runUuid;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Total number of items emitted, for reconciliation against what was received")
	private long totalCount;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Failure detail when enumeration aborted; null on success")
	private String error;

	public UUID getRunUuid() {
		return runUuid;
	}

	public SourceCompleteMessage setRunUuid(UUID runUuid) {
		this.runUuid = runUuid;
		return this;
	}

	public long getTotalCount() {
		return totalCount;
	}

	public SourceCompleteMessage setTotalCount(long totalCount) {
		this.totalCount = totalCount;
		return this;
	}

	public String getError() {
		return error;
	}

	public SourceCompleteMessage setError(String error) {
		this.error = error;
		return this;
	}
}
