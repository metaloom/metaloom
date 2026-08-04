package io.metaloom.cortex.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.artifact.ArtifactCache;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.metaloom.loom.pipeline.model.SegmentTask;

/**
 * Translates between Cortex's internal {@link NodeResult} and the wire-level
 * {@link NodeTaskResult}.
 *
 * <p>The two types are kept deliberately separate. The internal one is free to grow
 * implementation concerns; the wire one is a contract that both sides compile
 * against and must stay stable. This class is the single place they meet - mapping
 * in more than one place is how such pairs drift.</p>
 *
 * <p>It is also the <strong>emit-side type boundary</strong>. Every value a node
 * produced is coerced against the content type its port declares before it is allowed
 * onto the wire, and every element is stamped with an {@link Origin} naming the run
 * item it descends from. A value that cannot satisfy its declared type fails
 * <em>that one task</em>, with the port and the two types in the message, rather than
 * surviving as far as a downstream cast or a persist batch.</p>
 *
 * <p>Cortex's terminal {@link ResultState} is mapped <strong>explicitly</strong> to the
 * wire {@code NodeState} in {@code toWireState} ({@code SUCCESS→COMPLETED},
 * {@code SKIPPED→SKIPPED}, {@code FAILED→FAILED}). The wire enum additionally has
 * {@code PENDING}/{@code RUNNING}, which a terminal result never produces.</p>
 */
public final class NodeResultMapper {

	private NodeResultMapper() {
	}

	/**
	 * Map a result produced for a single-node task.
	 *
	 * @param task   the task being answered - the source of the origin item id and element index
	 * @param result the internal result
	 * @return the wire result
	 * @throws io.metaloom.loom.nodes.spec.ValueCoercionException when an emitted value does not
	 *         satisfy its port's declared content type
	 */
	public static NodeTaskResult toWire(NodeTask task, NodeResult result) {
		NodeTaskResult wire = toWire(task.getTaskUuid(), task.getItemId(), task.getElementSeq(), result);
		if (!task.isCapturePreviews()) {
			// The overwhelmingly common path: one boolean check and nothing else.
			return wire;
		}
		// Previews are attached from the already-coerced payloads rather than from the node's raw
		// outputs, so a value that failed its declared type never reaches the image reader.
		return wire.withPreviews(NodePreviews.merge(NodePreviews.build(wire.getOutputs()), result.getPreviews()));
	}

	/**
	 * Map a result produced for one node of a segment. A segment is dispatched per item rather
	 * than per element, so every node in it answers for element 0.
	 */
	public static NodeTaskResult toWire(SegmentTask task, NodeResult result) {
		return toWire(task.getTaskUuid(), task.getItemId(), 0, result);
	}

	public static NodeTaskResult toWire(UUID taskUuid, String itemId, int elementSeq, NodeResult result) {
		Map<String, PortPayload> outputs = toPayloads(itemId, elementSeq, result.getOutputs());
		return new NodeTaskResult(taskUuid, result.getNodeId(), elementSeq, toWireState(result.getState()),
			result.getDurationMs(), result.getMessage(), outputs);
	}

	/**
	 * Turn the context's per-port accumulators into wire payloads.
	 *
	 * <p>A {@code ONE} port yields a single element carrying this execution's origin - which for
	 * a per-element node is its own {@code elementSeq}, so that a downstream zip can line the
	 * branches up again. A {@code MANY} port numbers its elements {@code 0..n-1} with
	 * {@code total = n}: that count is what the engine reads to decide how many per-element
	 * tasks to spawn downstream.</p>
	 */
	public static Map<String, PortPayload> toPayloads(String itemId, int elementSeq, Map<String, PortOutput> outputs) {
		if (outputs == null || outputs.isEmpty()) {
			return Map.of();
		}
		Map<String, PortPayload> payloads = new LinkedHashMap<>(outputs.size());
		outputs.forEach((portId, output) -> {
			PortPayload payload = output.toPayload(itemId, elementSeq);
			if (payload != null) {
				payloads.put(portId, payload);
			}
		});
		return payloads;
	}

	/**
	 * Build the port-keyed view a node reads its inputs through.
	 *
	 * <p>Only the ports travel over the wire - the engine resolved which upstream node and port
	 * fills each one before dispatching, so nothing here has to know the graph.</p>
	 */
	public static NodeInputs toInputs(NodeTask task) {
		return toInputs(task, ArtifactCache.noop());
	}

	/**
	 * As above, bound to the artifact scope the runner opened for this execution.
	 */
	public static NodeInputs toInputs(NodeTask task, ArtifactCache artifacts) {
		return new NodeInputs(task.getInputs(), task.getDemandedOutputs(),
			new Origin(task.getItemId(), task.getElementSeq(), null), artifacts, task.isCapturePreviews());
	}

	/**
	 * Build the view for one node inside a segment.
	 *
	 * <p>{@code available} starts as whatever came from outside the segment and grows as the
	 * nodes in it run, so a dependency satisfied locally never crosses the network - which is
	 * the entire saving a segment exists for.</p>
	 *
	 * <p>⚠️ {@code SegmentNode} carries no input bindings, so a segment-internal edge can only be
	 * matched by port id: a node reading {@code text} picks up whatever earlier node in the
	 * segment emitted a port called {@code text}. Engine-level dispatch has the real bindings and
	 * does not rely on this; until the segment wire model carries them too, do not group nodes
	 * whose port ids collide with a different meaning.</p>
	 */
	public static NodeInputs toInputs(SegmentTask task, Map<String, PortPayload> available) {
		return toInputs(task, available, ArtifactCache.noop());
	}

	/**
	 * As above, bound to the artifact scope the segment opened.
	 *
	 * <p>
	 * The port view is rebuilt per node — it grows as the segment runs — while the scope is one object shared by every node in the segment. That
	 * asymmetry is the whole mechanism: outputs travel, artifacts stay.
	 * </p>
	 */
	public static NodeInputs toInputs(SegmentTask task, Map<String, PortPayload> available, ArtifactCache artifacts) {
		return new NodeInputs(available, Set.of(), Origin.single(task.getItemId()), artifacts);
	}

	private static io.metaloom.loom.pipeline.model.NodeState toWireState(ResultState state) {
		if (state == null) {
			return io.metaloom.loom.pipeline.model.NodeState.FAILED;
		}
		switch (state) {
			case SUCCESS:
				return io.metaloom.loom.pipeline.model.NodeState.COMPLETED;
			case SKIPPED:
				return io.metaloom.loom.pipeline.model.NodeState.SKIPPED;
			case FAILED:
			default:
				return io.metaloom.loom.pipeline.model.NodeState.FAILED;
		}
	}
}
