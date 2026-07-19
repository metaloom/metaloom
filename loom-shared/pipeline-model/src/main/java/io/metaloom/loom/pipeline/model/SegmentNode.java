package io.metaloom.loom.pipeline.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One node within a {@link SegmentTask}.
 *
 * <p>Carries its dependencies so the worker can feed each node the outputs of the
 * ones before it. Those dependencies may point inside the segment - satisfied
 * locally, which is the whole point - or outside it, satisfied from the task's
 * upstream outputs.</p>
 */
public class SegmentNode {

	private final String nodeId;
	private final String nodeKind;
	private final boolean blocking;
	private final Map<String, Object> options;
	private final List<String> dependencies;

	@JsonCreator
	public SegmentNode(@JsonProperty("nodeId") String nodeId, @JsonProperty("nodeKind") String nodeKind,
		@JsonProperty("blocking") boolean blocking, @JsonProperty("options") Map<String, Object> options,
		@JsonProperty("dependencies") List<String> dependencies) {
		this.nodeId = Objects.requireNonNull(nodeId, "A node id must be set");
		this.nodeKind = Objects.requireNonNull(nodeKind, "A node kind must be set");
		this.blocking = blocking;
		this.options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
		this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
	}

	public String getNodeId() {
		return nodeId;
	}

	public String getNodeKind() {
		return nodeKind;
	}

	/**
	 * @return true when a failed dependency should skip this node; a property of the
	 *         dependent node, matching the engine's semantics exactly
	 */
	public boolean isBlocking() {
		return blocking;
	}

	public Map<String, Object> getOptions() {
		return options;
	}

	public List<String> getDependencies() {
		return dependencies;
	}

	@Override
	public String toString() {
		return "SegmentNode[" + nodeId + " (" + nodeKind + ")]";
	}

}
