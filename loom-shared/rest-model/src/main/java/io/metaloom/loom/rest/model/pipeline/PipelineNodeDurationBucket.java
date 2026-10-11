package io.metaloom.loom.rest.model.pipeline;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * How many computed executions of a node took between {@code fromMs} (inclusive) and {@code toMs} (exclusive). Buckets double in width.
 */
public class PipelineNodeDurationBucket implements RestResponseModel<PipelineNodeDurationBucket> {

	@JsonPropertyDescription("Lower bound in milliseconds, inclusive.")
	private long fromMs;

	@JsonPropertyDescription("Upper bound in milliseconds, exclusive.")
	private long toMs;

	@JsonPropertyDescription("Computed executions in this range.")
	private long count;

	public long getFromMs() {
		return fromMs;
	}

	public PipelineNodeDurationBucket setFromMs(long fromMs) {
		this.fromMs = fromMs;
		return this;
	}

	public long getToMs() {
		return toMs;
	}

	public PipelineNodeDurationBucket setToMs(long toMs) {
		this.toMs = toMs;
		return this;
	}

	public long getCount() {
		return count;
	}

	public PipelineNodeDurationBucket setCount(long count) {
		this.count = count;
		return this;
	}

	@Override
	public PipelineNodeDurationBucket self() {
		return this;
	}
}
