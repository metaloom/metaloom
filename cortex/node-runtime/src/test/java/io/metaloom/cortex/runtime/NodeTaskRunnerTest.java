package io.metaloom.cortex.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.NodeResult;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.pipeline.model.MediaRef;
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

	/** Node that records what it saw and returns a canned result. */
	private static class StubNode extends AbstractPipelineNode {

		private final NodeResult result;
		private final RuntimeException failure;
		LoomMedia seenMedia;
		Map<String, NodeResult> seenUpstream;

		StubNode(String id, NodeResult result, RuntimeException failure) {
			super(id, id, NodeMode.PARALLEL, true, 1, false);
			this.result = result;
			this.failure = failure;
		}

		@Override
		public NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults) {
			this.seenMedia = media;
			this.seenUpstream = upstreamResults;
			if (failure != null) {
				throw failure;
			}
			return result;
		}
	}

	private static NodeTaskRunner runnerFor(StubNode node) {
		return new NodeTaskRunner(def -> node, path -> new StubLoomMedia(path.toString()));
	}

	private static NodeTask task(String nodeId, String kind, Map<String, Object> options,
		Map<String, Map<String, Object>> upstream) {
		return new NodeTask(UUID.randomUUID(), UUID.randomUUID(), "item-1", nodeId, kind,
			MediaRef.of("/media/a.mp4"), options, upstream);
	}

	@Test
	void testSuccessfulNodeIsMappedToTheWireResult() {
		StubNode node = new StubNode("hash", NodeResult.success("hash", 42, Map.of("sha512", "abc")), null);

		NodeTaskResult result = runnerFor(node).run(task("hash", "sha512", Map.of(), Map.of()));

		assertEquals(NodeState.COMPLETED, result.getState());
		assertEquals("hash", result.getNodeId());
		assertEquals(42, result.getDurationMs());
		assertEquals("abc", result.getOutputs().get("sha512"));
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
		}, path -> new StubLoomMedia(path.toString()));

		runner.run(task("src", "filesystem-source", Map.of("path", "/media", "depth", 3), Map.of()));

		assertNotNull(seen.get());
		assertEquals("src", seen.get().getString("id"));
		assertEquals("filesystem-source", seen.get().getString("type"));
		assertEquals("/media", seen.get().getString("path"));
		assertEquals(3, seen.get().getInteger("depth"));
	}

	@Test
	void testUpstreamOutputsAreRebuiltForTheNode() {
		StubNode node = new StubNode("thumb", NodeResult.success("thumb", 1), null);
		Map<String, Map<String, Object>> upstream = new HashMap<>();
		upstream.put("hash", Map.of("sha512", "deadbeef"));

		runnerFor(node).run(task("thumb", "thumbnail", Map.of(), upstream));

		assertNotNull(node.seenUpstream, "The node must see its upstream results");
		NodeResult hashResult = node.seenUpstream.get("hash");
		assertNotNull(hashResult);
		assertEquals("deadbeef", hashResult.getOutput().get("sha512"));
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
		}, path -> new StubLoomMedia(path.toString()));

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
