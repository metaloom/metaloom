package io.metaloom.loom.pipeline.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The outcome of a {@link SegmentTask}: one result per node.
 *
 * <p><strong>Per-node outcomes, never a single status for the segment.</strong> A
 * segment that reported one verdict would turn one bad node into a wholly failed
 * item, losing the results of nodes that ran perfectly well before it. The engine
 * assimilates these exactly as it would individual {@code NODE_TASK_RESULT}s.</p>
 *
 * <p>A segment may report fewer results than it was given nodes: once a blocking
 * node fails, the ones after it are skipped, and those skips are reported
 * explicitly rather than omitted — an absent result would leave the engine waiting
 * for a node nobody is going to run.</p>
 */
public class SegmentTaskResult {

	private final UUID taskUuid;
	private final String itemId;
	private final String segmentId;
	private final List<NodeTaskResult> results;
	private final String error;

	@JsonCreator
	public SegmentTaskResult(@JsonProperty("taskUuid") UUID taskUuid, @JsonProperty("itemId") String itemId,
		@JsonProperty("segmentId") String segmentId, @JsonProperty("results") List<NodeTaskResult> results,
		@JsonProperty("error") String error) {
		this.taskUuid = taskUuid;
		this.itemId = Objects.requireNonNull(itemId, "An item id must be set");
		this.segmentId = segmentId;
		this.results = results == null ? List.of() : List.copyOf(results);
		this.error = error;
	}

	public UUID getTaskUuid() {
		return taskUuid;
	}

	public String getItemId() {
		return itemId;
	}

	public String getSegmentId() {
		return segmentId;
	}

	/** @return one result per node the worker reached, in execution order */
	public List<NodeTaskResult> getResults() {
		return results;
	}

	/**
	 * @return a segment-level failure - the media could not be opened at all, say -
	 *         or null when the per-node results tell the whole story
	 */
	public String getError() {
		return error;
	}

	@Override
	public String toString() {
		return "SegmentTaskResult[" + segmentId + " item=" + itemId + " results=" + results.size() + "]";
	}

}
