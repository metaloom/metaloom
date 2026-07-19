package io.metaloom.cortex.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.api.NodeState;
import io.metaloom.loom.pipeline.model.NodeTaskResult;

/**
 * Translates between Cortex's internal {@link NodeResult} and the wire-level
 * {@link NodeTaskResult}.
 *
 * <p>The two types are kept deliberately separate. The internal one is free to grow
 * implementation concerns; the wire one is a contract that both sides compile
 * against and must stay stable. This class is the single place they meet - mapping
 * in more than one place is how such pairs drift.</p>
 *
 * <p>The two {@code NodeState} enums are mapped by <strong>name</strong>. They are
 * intentionally name-aligned, so adding a value to one without the other breaks
 * this at runtime rather than at compile time. Change both together.</p>
 */
public final class NodeResultMapper {

	private NodeResultMapper() {
	}

	/**
	 * @param taskUuid the task being answered
	 * @param result   the internal result
	 * @return the wire result
	 */
	public static NodeTaskResult toWire(UUID taskUuid, NodeResult result) {
		return new NodeTaskResult(taskUuid, result.getNodeId(), toWireState(result.getState()),
			result.getDurationMs(), result.getMessage(), result.getOutput());
	}

	/**
	 * Rebuild upstream results from the outputs carried in a task.
	 *
	 * <p>Only the outputs travel over the wire, so upstream nodes are reconstructed as
	 * {@code COMPLETED} carriers of those values. That is faithful for the purpose:
	 * the engine on the Loom side has already decided this node should run, and a
	 * node only ever reads its upstreams' output values.</p>
	 *
	 * @param upstreamOutputs node id to output map, as received
	 * @return upstream results keyed by node id
	 */
	public static Map<String, NodeResult> toUpstreamResults(Map<String, Map<String, Object>> upstreamOutputs) {
		Map<String, NodeResult> upstream = new LinkedHashMap<>();
		if (upstreamOutputs == null) {
			return upstream;
		}
		upstreamOutputs.forEach((nodeId, outputs) -> upstream.put(nodeId, NodeResult.success(nodeId, 0, outputs)));
		return upstream;
	}

	/**
	 * Convert a wire result back into the local shape.
	 *
	 * <p>Needed only within a segment, where a node's result becomes the next node's
	 * input without ever leaving the process. Unlike
	 * {@link #toUpstreamResults(Map)} this preserves the real state, because a
	 * downstream node in the same segment genuinely may need to see that its
	 * dependency failed - the Loom engine is not there to decide for it.</p>
	 *
	 * @param result the wire result
	 * @return the local result
	 */
	public static NodeResult toLocal(NodeTaskResult result) {
		switch (result.getState()) {
			case FAILED:
				return NodeResult.failed(result.getNodeId(), result.getDurationMs(), result.getMessage());
			case SKIPPED:
				return NodeResult.skipped(result.getNodeId(), result.getMessage());
			default:
				return NodeResult.success(result.getNodeId(), result.getDurationMs(), result.getOutputs());
		}
	}

	private static io.metaloom.loom.pipeline.model.NodeState toWireState(NodeState state) {
		if (state == null) {
			return io.metaloom.loom.pipeline.model.NodeState.FAILED;
		}
		return io.metaloom.loom.pipeline.model.NodeState.valueOf(state.name());
	}
}
