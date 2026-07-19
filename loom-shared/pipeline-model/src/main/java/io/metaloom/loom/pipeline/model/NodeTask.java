package io.metaloom.loom.pipeline.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single unit of work: apply one node to one media item.
 *
 * <p>Built by the Loom-side engine and handed to a Cortex node executor. In
 * Phase 1 the engine pushes these; a later phase may invert this to a pull with
 * leases, at which point only the transport changes - the payload does not.</p>
 *
 * <p>{@code upstreamOutputs} carries the outputs of the dependencies this node
 * declares an input for. It is deliberately <em>not</em> the full result map:
 * shipping every upstream output inline does not survive real data (embeddings,
 * thumbnails, transcripts), so the engine sends only what the node asked for.</p>
 */
public class NodeTask {

	private final UUID taskUuid;
	private final UUID runUuid;
	private final String itemId;
	private final String nodeId;
	private final String nodeKind;
	private final MediaRef media;
	private final Map<String, Object> options;
	private final Map<String, Map<String, Object>> upstreamOutputs;
	private final int resultBatchSize;

	@JsonCreator
	public NodeTask(@JsonProperty("taskUuid") UUID taskUuid, @JsonProperty("runUuid") UUID runUuid,
		@JsonProperty("itemId") String itemId, @JsonProperty("nodeId") String nodeId,
		@JsonProperty("nodeKind") String nodeKind, @JsonProperty("media") MediaRef media,
		@JsonProperty("options") Map<String, Object> options,
		@JsonProperty("upstreamOutputs") Map<String, Map<String, Object>> upstreamOutputs,
		@JsonProperty("resultBatchSize") Integer resultBatchSize) {
		this.taskUuid = Objects.requireNonNull(taskUuid, "A task uuid must be set");
		this.runUuid = runUuid;
		this.itemId = Objects.requireNonNull(itemId, "An item id must be set");
		this.nodeId = Objects.requireNonNull(nodeId, "A node id must be set");
		this.nodeKind = Objects.requireNonNull(nodeKind, "A node kind must be set");
		this.media = Objects.requireNonNull(media, "A media reference must be set");
		this.options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
		this.upstreamOutputs = upstreamOutputs == null
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(upstreamOutputs));
		// Carried on the task so a worker serving several runs batches each one to its
		// own pipeline's setting, without needing separate run-level bookkeeping.
		this.resultBatchSize = resultBatchSize == null ? 1 : Math.max(1, resultBatchSize);
	}

	/**
	 * Convenience overload for the unbatched case.
	 */
	public NodeTask(UUID taskUuid, UUID runUuid, String itemId, String nodeId, String nodeKind, MediaRef media,
		Map<String, Object> options, Map<String, Map<String, Object>> upstreamOutputs) {
		this(taskUuid, runUuid, itemId, nodeId, nodeKind, media, options, upstreamOutputs, 1);
	}

	/**
	 * @return how many results the worker may accumulate before sending; 1 means send
	 *         each one as it happens
	 */
	public int getResultBatchSize() {
		return resultBatchSize;
	}

	public UUID getTaskUuid() {
		return taskUuid;
	}

	/**
	 * @return the pipeline run this task belongs to, or null for an untracked execution
	 */
	public UUID getRunUuid() {
		return runUuid;
	}

	/**
	 * @return identifier of the media item within the run
	 */
	public String getItemId() {
		return itemId;
	}

	public String getNodeId() {
		return nodeId;
	}

	/**
	 * @return the node kind, e.g. {@code sha512} - this is what the worker resolves to an implementation
	 */
	public String getNodeKind() {
		return nodeKind;
	}

	public MediaRef getMedia() {
		return media;
	}

	/** @return per-node options from the pipeline definition, never null */
	public Map<String, Object> getOptions() {
		return options;
	}

	/** @return outputs of the upstream nodes this node declared inputs for, never null */
	public Map<String, Map<String, Object>> getUpstreamOutputs() {
		return upstreamOutputs;
	}

	@Override
	public String toString() {
		return "NodeTask[" + nodeId + " (" + nodeKind + ") on " + media.getPath() + "]";
	}
}
