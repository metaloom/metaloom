package io.metaloom.loom.pipeline.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Several connected nodes applied to one media item, executed together on one
 * worker.
 *
 * <p>A {@link NodeTask} is the degenerate case of this with one node. The reason
 * both exist is cost: under per-node dispatch a five-node video pipeline pays five
 * round trips <em>and</em> re-reads and re-decodes the file five times, because
 * nothing survives between tasks. As one segment it opens the file once and keeps
 * intermediate results in the worker's memory.</p>
 *
 * <p>{@code inputs} carries only what comes from <em>outside</em> the
 * segment. Dependencies between nodes inside it are satisfied locally and never
 * cross the network — which is the saving.</p>
 */
public class SegmentTask {

	private final UUID taskUuid;
	private final UUID runUuid;
	private final String itemId;
	private final String segmentId;
	private final String affinity;
	private final MediaRef media;
	private final List<SegmentNode> nodes;
	private final Map<String, PortPayload> inputs;

	@JsonCreator
	public SegmentTask(@JsonProperty("taskUuid") UUID taskUuid, @JsonProperty("runUuid") UUID runUuid,
		@JsonProperty("itemId") String itemId, @JsonProperty("segmentId") String segmentId,
		@JsonProperty("affinity") String affinity, @JsonProperty("media") MediaRef media,
		@JsonProperty("nodes") List<SegmentNode> nodes,
		@JsonProperty("inputs") Map<String, PortPayload> inputs) {
		this.taskUuid = Objects.requireNonNull(taskUuid, "A task uuid must be set");
		this.runUuid = runUuid;
		this.itemId = Objects.requireNonNull(itemId, "An item id must be set");
		this.segmentId = segmentId;
		this.affinity = affinity;
		this.media = Objects.requireNonNull(media, "A media reference must be set");
		this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
		this.inputs = inputs == null
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
	}

	public UUID getTaskUuid() {
		return taskUuid;
	}

	public UUID getRunUuid() {
		return runUuid;
	}

	public String getItemId() {
		return itemId;
	}

	public String getSegmentId() {
		return segmentId;
	}

	public String getAffinity() {
		return affinity;
	}

	public MediaRef getMedia() {
		return media;
	}

	/** @return the nodes, in execution order */
	public List<SegmentNode> getNodes() {
		return nodes;
	}

	/** @return outputs of dependencies outside this segment */
	public Map<String, PortPayload> getInputs() {
		return inputs;
	}

	/**
	 * @return the distinct kinds this segment needs; a worker must be permitted to run
	 *         all of them
	 */
	@JsonIgnore
	public List<String> getNodeKinds() {
		return nodes.stream().map(SegmentNode::getNodeKind).distinct().toList();
	}

	@Override
	public String toString() {
		return "SegmentTask[" + segmentId + " item=" + itemId + " nodes=" + nodes.size() + "]";
	}

}
