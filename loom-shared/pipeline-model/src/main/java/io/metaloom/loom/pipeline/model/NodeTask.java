package io.metaloom.loom.pipeline.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single unit of work: apply one node to one element of one media item.
 *
 * <p>Built by the Loom-side engine and handed to a Cortex node executor. In
 * Phase 1 the engine pushes these; a later phase may invert this to a pull with
 * leases, at which point only the transport changes - the payload does not.</p>
 *
 * <p><strong>Inputs are keyed by the receiving node's own port ids</strong>, not by upstream node
 * id. The engine resolves which upstream {@code (node, port)} fills each input port from the wired
 * edges, so a node never has to know — or hard-code — what the pipeline author named its
 * neighbours. That indirection is the point: renaming a node in the editor used to silently break
 * every downstream lookup.</p>
 *
 * <p>{@code elementSeq} identifies which element of a fanned-out sequence this task is for. A node
 * that is not per-element always gets {@code 0}.</p>
 */
public class NodeTask {

	private final UUID taskUuid;
	private final UUID runUuid;
	private final String itemId;
	private final String nodeId;
	private final String nodeKind;
	private final int elementSeq;
	private final MediaRef media;
	private final Map<String, Object> options;
	private final Map<String, PortPayload> inputs;
	private final Set<String> demandedOutputs;
	private final int resultBatchSize;

	@JsonCreator
	public NodeTask(@JsonProperty("taskUuid") UUID taskUuid, @JsonProperty("runUuid") UUID runUuid,
		@JsonProperty("itemId") String itemId, @JsonProperty("nodeId") String nodeId,
		@JsonProperty("nodeKind") String nodeKind, @JsonProperty("elementSeq") Integer elementSeq,
		@JsonProperty("media") MediaRef media,
		@JsonProperty("options") Map<String, Object> options,
		@JsonProperty("inputs") Map<String, PortPayload> inputs,
		@JsonProperty("demandedOutputs") Set<String> demandedOutputs,
		@JsonProperty("resultBatchSize") Integer resultBatchSize) {
		this.taskUuid = Objects.requireNonNull(taskUuid, "A task uuid must be set");
		this.runUuid = runUuid;
		this.itemId = Objects.requireNonNull(itemId, "An item id must be set");
		this.nodeId = Objects.requireNonNull(nodeId, "A node id must be set");
		this.nodeKind = Objects.requireNonNull(nodeKind, "A node kind must be set");
		this.elementSeq = elementSeq == null ? 0 : elementSeq;
		this.media = Objects.requireNonNull(media, "A media reference must be set");
		this.options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
		this.inputs = inputs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
		this.demandedOutputs = demandedOutputs == null
			? Set.of()
			: Collections.unmodifiableSet(new LinkedHashSet<>(demandedOutputs));
		// Carried on the task so a worker serving several runs batches each one to its
		// own pipeline's setting, without needing separate run-level bookkeeping.
		this.resultBatchSize = resultBatchSize == null ? 1 : Math.max(1, resultBatchSize);
	}

	/**
	 * Convenience overload for a single-element, unbatched task.
	 */
	public NodeTask(UUID taskUuid, UUID runUuid, String itemId, String nodeId, String nodeKind, MediaRef media,
		Map<String, Object> options, Map<String, PortPayload> inputs) {
		this(taskUuid, runUuid, itemId, nodeId, nodeKind, 0, media, options, inputs, null, 1);
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
	 * @return identifier of the media item within the run — also the origin id of every element
	 *         this task produces
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

	/**
	 * @return which element of a fanned-out sequence this task covers; 0 when the node runs once per
	 *         item
	 */
	public int getElementSeq() {
		return elementSeq;
	}

	public MediaRef getMedia() {
		return media;
	}

	/** @return per-node options from the pipeline definition, never null */
	public Map<String, Object> getOptions() {
		return options;
	}

	/**
	 * @return this node's input ports and what they carry, keyed by <em>this node's</em> port ids,
	 *         never null
	 */
	public Map<String, PortPayload> getInputs() {
		return inputs;
	}

	/**
	 * The output ports the pipeline actually wired up.
	 *
	 * <p>
	 * A node may use this to skip work nobody asked for — not computing a depth map when no edge
	 * leaves the {@code map} port. Emitting an undemanded port stays legal; the set is a hint, not a
	 * restriction. It is also what makes an {@code EXCLUSIVE} output group enforceable.
	 * </p>
	 *
	 * @return the demanded output port ids, never null; empty means "nothing downstream asked"
	 */
	public Set<String> getDemandedOutputs() {
		return demandedOutputs;
	}

	/**
	 * Whether the pipeline wired anything to the given output port.
	 */
	public boolean isDemanded(String portId) {
		return demandedOutputs.contains(portId);
	}

	@Override
	public String toString() {
		return "NodeTask[" + nodeId + " (" + nodeKind + ")"
			+ (elementSeq > 0 ? " #" + elementSeq : "") + " on " + media.getPath() + "]";
	}
}
