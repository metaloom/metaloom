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
 * @param sourceSelective
 *            whether the <em>producing port itself</em> declares
 *            {@link io.metaloom.loom.nodes.spec.PortSpec#isSelective() selective}. This is the edge
 *            at which the branch is actually decided, which is what
 *            {@link PipelineSegmenter} needs: a segment must not span it, because the worker runs a
 *            segment as a unit and cannot know which branch an item took
 * @param routed
 *            whether this edge is on a routed path at all — the source port is selective, <em>or</em>
 *            the producing node is itself downstream of a routed edge. Selectivity has to be
 *            inherited: if the German branch does not fire, the node wired to it is skipped, so its
 *            own outputs are empty and <em>its</em> consumers must skip in turn. A one-hop rule would
 *            leave the grandchild running with empty inputs, which is exactly the non-transitivity
 *            defect {@link PipelineGraphNode} documents for {@link FilterBranch}
 */
public record InputBinding(String targetPortId, String sourceNodeId, String sourcePortId, FilterBranch branch,
	boolean targetIsMany, boolean sourceSelective, boolean routed) {

	public InputBinding {
		Objects.requireNonNull(targetPortId, "A target port id must be set");
		Objects.requireNonNull(sourceNodeId, "A source node id must be set");
		Objects.requireNonNull(sourcePortId, "A source port id must be set");
		branch = branch == null ? FilterBranch.ANY : branch;
	}

	public InputBinding(String targetPortId, String sourceNodeId, String sourcePortId, FilterBranch branch,
		boolean targetIsMany) {
		this(targetPortId, sourceNodeId, sourcePortId, branch, targetIsMany, false, false);
	}

	public InputBinding(String targetPortId, String sourceNodeId, String sourcePortId, FilterBranch branch) {
		this(targetPortId, sourceNodeId, sourcePortId, branch, false, false, false);
	}

	public static InputBinding of(String targetPortId, String sourceNodeId, String sourcePortId) {
		return new InputBinding(targetPortId, sourceNodeId, sourcePortId, FilterBranch.ANY, false, false, false);
	}

	/**
	 * A copy that knows whether its target port gathers a sequence.
	 */
	public InputBinding withTargetCardinality(boolean many) {
		return new InputBinding(targetPortId, sourceNodeId, sourcePortId, branch, many, sourceSelective, routed);
	}

	/**
	 * A copy that knows whether it carries branch routing. See {@link #sourceSelective} and
	 * {@link #routed}.
	 */
	public InputBinding withRouting(boolean sourceSelective, boolean routed) {
		return new InputBinding(targetPortId, sourceNodeId, sourcePortId, branch, targetIsMany, sourceSelective, routed);
	}

	@Override
	public String toString() {
		return sourceNodeId + "." + sourcePortId + " -> ." + targetPortId
			+ (branch == FilterBranch.ANY ? "" : " [" + branch + "]")
			+ (routed ? " [routed]" : "");
	}
}
