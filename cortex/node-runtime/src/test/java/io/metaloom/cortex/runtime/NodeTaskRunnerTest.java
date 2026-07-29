package io.metaloom.cortex.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.vertx.core.json.JsonObject;

/**
 * Tests for the Cortex-side task runner.
 *
 * <p>The contract that matters is total: <em>every dispatched task gets exactly one
 * answer</em>. The Loom engine holds an item's progress until a result arrives, so a
 * task that throws, returns null, or names an unknown node kind must still come back
 * as a definite {@code FAILED} rather than silently vanishing.</p>
 */
public class NodeTaskRunnerTest {

	/** The hash port every hash node declares. */
	private static final OutputPort<String> OUT_HASH =
		OutputPort.one("hash", ContentTypeRegistry.HASH_SHA512, String.class);

	/** A port on the receiving side, filled by whatever edge the engine resolved. */
	private static final io.metaloom.cortex.api.node.InputPort<String> IN_HASH =
		io.metaloom.cortex.api.node.InputPort.one("hash", ContentTypeRegistry.HASH_SHA512, String.class);

	/** Node that records what it saw and returns a canned result. */
	private static class StubNode extends AbstractPipelineNode {

		private final NodeResult result;
		private final RuntimeException failure;
		LoomMedia seenMedia;
		NodeInputs seenInputs;

		StubNode(String id, NodeResult result, RuntimeException failure) {
			super(id, id, NodeMode.PARALLEL, true, 1, false);
			this.result = result;
			this.failure = failure;
		}

		@Override
		public NodeResult process(LoomMedia media, NodeInputs inputs) {
			this.seenMedia = media;
			this.seenInputs = inputs;
			if (failure != null) {
				throw failure;
			}
			return result;
		}
	}

	private static NodeTaskRunner runnerFor(StubNode node) {
		return new NodeTaskRunner(def -> node, mediaRef -> new StubLoomMedia(mediaRef.getPath()));
	}

	private static NodeTask task(String nodeId, String kind, Map<String, Object> options,
		Map<String, PortPayload> inputs) {
		return new NodeTask(UUID.randomUUID(), UUID.randomUUID(), "item-1", nodeId, kind,
			MediaRef.of("/media/a.mp4"), options, inputs);
	}

	@Test
	void testSuccessfulNodeIsMappedToTheWireResult() {
		StubNode node = new StubNode("hash",
			NodeResult.success("hash", 42, Map.of(OUT_HASH.id(), PortOutput.one(OUT_HASH, "abc"))), null);

		NodeTaskResult result = runnerFor(node).run(task("hash", "sha512", Map.of(), Map.of()));

		assertEquals(NodeState.COMPLETED, result.getState());
		assertEquals("hash", result.getNodeId());
		assertEquals(42, result.getDurationMs());
		// Outputs cross the wire as port payloads keyed by port id, carrying the declared
		// content type and an origin - not as a bare value keyed by an output name.
		PortPayload payload = result.getOutputs().get(OUT_HASH.id());
		assertNotNull(payload, "Expected a payload on port '" + OUT_HASH.id() + "'");
		assertEquals(ContentTypeRegistry.HASH_SHA512, payload.getContentType());
		assertEquals("abc", payload.single());
		assertEquals("item-1", payload.getElements().get(0).getOrigin().getItemId());
	}

	@Test
	void testNodeOptionsAreFlattenedIntoTheDefinition() {
		// The existing node producers read options off the top level of the definition
		// (filesystem-source reads "path" directly), so the runner must preserve that
		// shape or every producer would need rewriting.
		AtomicReference<JsonObject> seen = new AtomicReference<>();
		StubNode node = new StubNode("src", NodeResult.success("src", 0), null);
		NodeTaskRunner runner = new NodeTaskRunner(def -> {
			seen.set(def);
			return node;
		}, mediaRef -> new StubLoomMedia(mediaRef.getPath()));

		runner.run(task("src", "filesystem-source", Map.of("path", "/media", "depth", 3), Map.of()));

		assertNotNull(seen.get());
		assertEquals("src", seen.get().getString("id"));
		assertEquals("filesystem-source", seen.get().getString("type"));
		assertEquals("/media", seen.get().getString("path"));
		assertEquals(3, seen.get().getInteger("depth"));
	}

	@Test
	void testInputPortsAreHandedToTheNode() {
		StubNode node = new StubNode("thumb", NodeResult.success("thumb", 1), null);
		Map<String, PortPayload> inputs = Map.of(IN_HASH.id(),
			PortPayload.one(ContentTypeRegistry.HASH_SHA512, Origin.single("item-1"), "deadbeef"));

		runnerFor(node).run(task("thumb", "thumbnail", Map.of(), inputs));

		assertNotNull(node.seenInputs, "The node must see its inputs");
		// Keyed by *this* node's port id — the engine already resolved which upstream
		// (node, port) fills it, so no upstream node id appears anywhere here.
		assertEquals("deadbeef", node.seenInputs.get(IN_HASH.id()).single());
	}

	@Test
	void testDemandedOutputsAndOriginReachTheNode() {
		StubNode node = new StubNode("thumb", NodeResult.success("thumb", 1), null);
		NodeTask task = new NodeTask(UUID.randomUUID(), UUID.randomUUID(), "item-1", "thumb", "thumbnail", 3,
			MediaRef.of("/media/a.mp4"), Map.of(), Map.of(), Set.of("thumbnail"), 1);

		runnerFor(node).run(task);

		assertEquals(Set.of("thumbnail"), node.seenInputs.demandedOutputs(),
			"isDemanded() is how a node skips work nobody asked for");
		assertEquals(3, node.seenInputs.origin().getSeq(),
			"A per-element task must tell the node which element it is processing");
	}

	@Test
	void testManyPortElementsAreNumberedOnTheWire() {
		OutputPort<String> outTexts = OutputPort.many("texts", ContentTypeRegistry.TEXT_PLAIN, String.class);
		StubNode node = new StubNode("split",
			NodeResult.success("split", 1, Map.of(outTexts.id(), PortOutput.many(outTexts, List.of("a", "b", "c")))),
			null);

		NodeTaskResult result = runnerFor(node).run(task("split", "script", Map.of(), Map.of()));

		PortPayload payload = result.getOutputs().get(outTexts.id());
		assertNotNull(payload);
		assertTrue(payload.isMany());
		assertEquals(List.of("a", "b", "c"), payload.values());
		// The element count is what the engine reads to decide how many downstream
		// per-element tasks to spawn, so the numbering has to be exact.
		assertEquals(0, payload.getElements().get(0).getOrigin().getSeq());
		assertEquals(2, payload.getElements().get(2).getOrigin().getSeq());
		assertEquals(Integer.valueOf(3), payload.getElements().get(2).getOrigin().getTotal());
	}

	@Test
	void testAValueThatCannotSatisfyItsPortFailsOnlyThatTask() {
		OutputPort<Long> outCount = OutputPort.one("count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);
		// A node that put prose on a numeric port. The coercion at the emit boundary is what
		// turns this into a failure naming the port, instead of a cast blowing up downstream.
		StubNode node = new StubNode("count",
			NodeResult.success("count", 1, Map.of(outCount.id(), new PortOutput(outCount, List.of("not a number")))),
			null);

		NodeTaskResult result = runnerFor(node).run(task("count", "script", Map.of(), Map.of()));

		assertEquals(NodeState.FAILED, result.getState());
		assertTrue(result.getMessage().contains("count"),
			"The failure must name the offending port, got: " + result.getMessage());
	}

	@Test
	void testMediaPathReachesTheNode() {
		StubNode node = new StubNode("hash", NodeResult.success("hash", 0), null);

		runnerFor(node).run(task("hash", "sha512", Map.of(), Map.of()));

		assertTrue(node.seenMedia.absolutePath().endsWith("a.mp4"),
			"Expected the task's media path, got " + node.seenMedia.absolutePath());
	}

	@Test
	void testThrowingNodeBecomesAFailedResult() {
		StubNode node = new StubNode("hash", null, new IllegalStateException("disk on fire"));

		NodeTaskResult result = runnerFor(node).run(task("hash", "sha512", Map.of(), Map.of()));

		assertEquals(NodeState.FAILED, result.getState(),
			"A throwing node must not propagate - one bad file cannot take down the worker");
		assertTrue(result.getMessage().contains("disk on fire"));
	}

	@Test
	void testUnknownNodeKindBecomesAFailedResult() {
		NodeTaskRunner runner = new NodeTaskRunner(def -> {
			throw new IllegalArgumentException("No producer registered for 'whisper'");
		}, mediaRef -> new StubLoomMedia(mediaRef.getPath()));

		NodeTaskResult result = runner.run(task("asr", "whisper", Map.of(), Map.of()));

		assertEquals(NodeState.FAILED, result.getState(),
			"An unexecutable kind must fail loudly rather than report success");
		assertTrue(result.getMessage().contains("whisper"));
	}

	@Test
	void testNodeReturningNullBecomesAFailedResult() {
		StubNode node = new StubNode("hash", null, null);

		NodeTaskResult result = runnerFor(node).run(task("hash", "sha512", Map.of(), Map.of()));

		assertEquals(NodeState.FAILED, result.getState(),
			"The engine needs a definite answer for every task it dispatched");
	}

	@Test
	void testSkippedStateSurvivesTheMapping() {
		StubNode node = new StubNode("hash", NodeResult.skipped("hash", "not processable"), null);

		NodeTaskResult result = runnerFor(node).run(task("hash", "sha512", Map.of(), Map.of()));

		assertEquals(NodeState.SKIPPED, result.getState());
		assertEquals("not processable", result.getMessage());
	}

	@Test
	void testResultCarriesTheOriginatingTaskUuid() {
		StubNode node = new StubNode("hash", NodeResult.success("hash", 0), null);
		NodeTask task = task("hash", "sha512", Map.of(), Map.of());

		assertEquals(task.getTaskUuid(), runnerFor(node).run(task).getTaskUuid(),
			"Correlation matters once more than one task is in flight");
	}
}
