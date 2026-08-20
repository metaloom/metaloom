package io.metaloom.cortex.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.impl.NodeContextImpl;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;

/**
 * The provenance halves of the mapper: the origin a node recorded must survive
 * node → {@link NodeResult} → wire, and the task's execution identity must reach the
 * {@link NodeInputs} the node reads — that is how a ledger row learns which run produced it.
 */
public class NodeResultMapperTest {

	private static NodeTask task(UUID taskUuid, UUID runUuid) {
		return new NodeTask(taskUuid, runUuid, "item-1", "node-1", "dummy",
			MediaRef.of("/media/a.mp4"), Map.of(), Map.of());
	}

	@Test
	public void testOriginSurvivesToTheWire() {
		NodeContextImpl<LoomMedia> ctx = new NodeContextImpl<>((LoomMedia) null, NodeInputs.empty());
		ctx.origin(ResultOrigin.LOCAL);
		NodeResult result = ctx.next().withNode("node-1", 5);

		NodeTaskResult wire = NodeResultMapper.toWire(task(UUID.randomUUID(), UUID.randomUUID()), result);

		assertEquals("LOCAL", wire.getOrigin(), "ctx.origin(LOCAL) must survive node -> result -> wire");
	}

	@Test
	public void testUnreportedOriginStaysNullOnTheWire() {
		NodeContextImpl<LoomMedia> ctx = new NodeContextImpl<>((LoomMedia) null, NodeInputs.empty());
		NodeResult result = ctx.next().withNode("node-1", 5);

		NodeTaskResult wire = NodeResultMapper.toWire(task(UUID.randomUUID(), UUID.randomUUID()), result);

		assertNull(wire.getOrigin(), "No report travels as null; the read side treats null as COMPUTED");
	}

	@Test
	public void testExecutionIdentityReachesTheInputs() {
		UUID taskUuid = UUID.randomUUID();
		UUID runUuid = UUID.randomUUID();

		NodeInputs inputs = NodeResultMapper.toInputs(task(taskUuid, runUuid));

		assertEquals(runUuid, inputs.runUuid());
		assertEquals(taskUuid, inputs.taskUuid());
	}
}
