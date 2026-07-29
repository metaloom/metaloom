package io.metaloom.loom.pipeline.graph;

import java.util.Objects;

import io.metaloom.loom.pipeline.model.FilterBranch;

/**
 * One wired edge, seen from the consuming node: "my input port <em>X</em> is fed by node
 * <em>N</em>'s output port <em>Y</em>".
 *
 * <p>
 * This is the replacement for the old arrangement where a node looked its data up by <em>upstream
 * node id</em>. That id is chosen by whoever drew the graph, so renaming a node in the editor
 * silently broke the lookup — and because every consumer treated a missing value as "absent", the
 * break was invisible. A binding inverts the direction: the engine knows which upstream port feeds
 * which local port, and the node only ever names its own ports.
 * </p>
 *
 * @param targetPortId
 *            the consuming node's input port
 * @param sourceNodeId
 *            the producing node
 * @param sourcePortId
 *            the producing node's output port
 * @param branch
 *            which filter branch this edge follows, {@link FilterBranch#ANY} for a normal edge
 * @param targetIsMany
 *            whether the consuming port accepts a sequence. Resolved once by
 *            {@link PortGraphAnalyzer} and carried here so the engine can build a task's inputs
 *            without re-consulting the descriptor registry on every dispatch
 */
public record InputBinding(String targetPortId, String sourceNodeId, String sourcePortId, FilterBranch branch,
	boolean targetIsMany) {

	public InputBinding {
		Objects.requireNonNull(targetPortId, "A target port id must be set");
		Objects.requireNonNull(sourceNodeId, "A source node id must be set");
		Objects.requireNonNull(sourcePortId, "A source port id must be set");
		branch = branch == null ? FilterBranch.ANY : branch;
	}

	public InputBinding(String targetPortId, String sourceNodeId, String sourcePortId, FilterBranch branch) {
		this(targetPortId, sourceNodeId, sourcePortId, branch, false);
	}

	public static InputBinding of(String targetPortId, String sourceNodeId, String sourcePortId) {
		return new InputBinding(targetPortId, sourceNodeId, sourcePortId, FilterBranch.ANY, false);
	}

	/**
	 * A copy that knows whether its target port gathers a sequence.
	 */
	public InputBinding withTargetCardinality(boolean many) {
		return new InputBinding(targetPortId, sourceNodeId, sourcePortId, branch, many);
	}

	@Override
	public String toString() {
		return sourceNodeId + "." + sourcePortId + " -> ." + targetPortId
			+ (branch == FilterBranch.ANY ? "" : " [" + branch + "]");
	}
}
