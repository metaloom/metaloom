package io.metaloom.cortex.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.core.node.AbstractPipelineNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.metaloom.loom.pipeline.model.SegmentNode;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.metaloom.loom.pipeline.model.SegmentTaskResult;

/**
 * Running several nodes on one worker.
 *
 * <p>The property that must not break: moving a node into an affinity group changes
 * <em>where</em> it runs, never <em>what the pipeline does</em>. So the local skip
 * rules are checked against the same cases {@code PipelineRunEngineTest} pins on
 * the Loom side.</p>
 *
 * <p>A segment hands each node a port-keyed {@link NodeInputs} view rather than the
 * upstream {@code NodeResult}s. That is deliberate and it removes one thing a node
 * used to be able to do: inspect a dependency's <em>state</em>. A non-blocking node
 * downstream of a failure now simply finds nothing on the port that failure would
 * have filled, which is what the wire model can actually express.</p>
 */
public class SegmentTaskRunnerTest {

	/** Stand-in for whatever a node emits; the port ids are what the next node matches on. */
	private static final OutputPort<String> OUT_HASH =
		OutputPort.one("hash", ContentTypeRegistry.HASH_SHA512, String.class);
	private static final OutputPort<Long> OUT_COUNT =
		OutputPort.one("count", ContentTypeRegistry.SCALAR_INTEGER, Long.class);

	/**
	 * A node that records that it ran and returns a fixed set of port outputs.
	 *
	 * <p>Extends {@code AbstractPipelineNode} rather than implementing the interface
	 * directly, so graph-wiring methods a segment never uses stay out of the test.</p>
	 */
	private static class RecordingNode extends AbstractPipelineNode {

		private final Map<String, PortOutput> output;
		private final RuntimeException failure;
		final List<NodeInputs> seenInputs = new ArrayList<>();

		RecordingNode(String id, Map<String, PortOutput> output, RuntimeException failure) {
			super(id, id, NodeMode.PARALLEL, true, 1);
			this.output = output;
			this.failure = failure;
		}

		@Override
		public NodeResult process(LoomMedia media, NodeInputs inputs) {
			seenInputs.add(inputs);
			if (failure != null) {
				throw failure;
			}
			return NodeResult.success(id(), 1, output);
		}
	}

	private final Map<String, RecordingNode> nodes = new LinkedHashMap<>();
	private final AtomicInteger mediaResolutions = new AtomicInteger();

	private SegmentTaskRunner runner() {
		return new SegmentTaskRunner(
			def -> {
				String id = def.getString("id");
				RecordingNode node = nodes.get(id);
				if (node == null) {
					throw new IllegalArgumentException("No node registered for '" + id + "'");
				}
				return node;
			},
			mediaRef -> {
				mediaResolutions.incrementAndGet();
				return new StubLoomMedia(mediaRef.getPath());
			});
	}

	private RecordingNode register(String id, Map<String, PortOutput> output) {
		RecordingNode node = new RecordingNode(id, output, null);
		nodes.put(id, node);
		return node;
	}

	private RecordingNode registerFailing(String id) {
		RecordingNode node = new RecordingNode(id, Map.of(), new IllegalStateException("boom"));
		nodes.put(id, node);
		return node;
	}

	private static <T> Map<String, PortOutput> emits(OutputPort<T> port, T value) {
		return Map.of(port.id(), PortOutput.one(port, value));
	}

	private static SegmentNode segNode(String id, boolean blocking, String... deps) {
		return new SegmentNode(id, "kind-" + id, blocking, Map.of(), List.of(deps));
	}

	private SegmentTask task(List<SegmentNode> segNodes, Map<String, PortPayload> inputs) {
		return new SegmentTask(UUID.randomUUID(), UUID.randomUUID(), "item-1", "seg-1", "video",
			MediaRef.of("/media/a.mp4"), segNodes, inputs);
	}

	@Test
	void testEveryNodeRunsAndIsReported() {
		register("a", emits(OUT_COUNT, 1L));
		register("b", emits(OUT_HASH, "beef"));

		SegmentTaskResult result = runner().run(task(List.of(segNode("a", true), segNode("b", true, "a")), Map.of()));

		assertEquals(2, result.getResults().size());
		assertEquals(List.of("a", "b"), result.getResults().stream().map(NodeTaskResult::getNodeId).toList());
		assertTrue(result.getResults().stream().allMatch(r -> r.getState() == NodeState.COMPLETED));
	}

	@Test
	void testTheMediaIsResolvedOnceForTheWholeSegment() {
		register("a", Map.of());
		register("b", Map.of());
		register("c", Map.of());

		runner().run(task(List.of(segNode("a", true), segNode("b", true, "a"), segNode("c", true, "b")), Map.of()));

		// Resolving the handle once is correct, but note what this does NOT prove: the
		// nodes still read the file themselves, so this is not a decode-once saving.
		// Measured at 1.01x against per-node dispatch over 155 MiB of real video.
		assertEquals(1, mediaResolutions.get(), "A segment must open the media once, not once per node");
	}

	@Test
	void testAnIntermediateResultReachesTheNextNodeWithoutLeavingTheProcess() {
		register("a", emits(OUT_HASH, "deadbeef"));
		RecordingNode b = register("b", Map.of());

		runner().run(task(List.of(segNode("a", true), segNode("b", true, "a")), Map.of()));

		// Matched by port id, not by the producing node's id: 'b' reads the port called
		// "hash" and never names 'a'.
		NodeInputs inputs = b.seenInputs.get(0);
		PortPayload hash = inputs.get(OUT_HASH.id());
		assertNotNull(hash, "The second node must see the first node's port output");
		assertEquals("deadbeef", hash.single());
		assertEquals(ContentTypeRegistry.HASH_SHA512, hash.getContentType());
	}

	@Test
	void testInputsFromOutsideTheSegmentAreVisible() {
		RecordingNode a = register("a", Map.of());

		runner().run(task(List.of(segNode("a", true, "external")),
			Map.of("media", PortPayload.one(ContentTypeRegistry.MEDIA_ANY, Origin.single("item-1"), "/media/a.mp4"))));

		assertEquals("/media/a.mp4", a.seenInputs.get(0).get("media").single());
	}

	@Test
	void testABlockingNodeIsSkippedWhenItsDependencyFails() {
		registerFailing("a");
		RecordingNode b = register("b", Map.of());

		SegmentTaskResult result = runner().run(task(List.of(segNode("a", true), segNode("b", true, "a")), Map.of()));

		assertEquals(NodeState.FAILED, result.getResults().get(0).getState());
		// Same rule the Loom engine applies between segments. If they disagreed,
		// grouping two nodes would silently change what the pipeline does.
		assertEquals(NodeState.SKIPPED, result.getResults().get(1).getState());
		assertTrue(b.seenInputs.isEmpty(), "A skipped node must not run");
	}

	@Test
	void testANonBlockingNodeRunsAnywayAndSeesNothingOnTheFailedPort() {
		registerFailing("a");
		RecordingNode b = register("b", Map.of());

		SegmentTaskResult result = runner().run(task(List.of(segNode("a", true), segNode("b", false, "a")), Map.of()));

		assertEquals(NodeState.COMPLETED, result.getResults().get(1).getState());
		assertEquals(1, b.seenInputs.size(), "A non-blocking node runs despite the failure");
		// Inputs carry port payloads, not upstream states, so the observable consequence
		// of the failure is an unfilled port - which is exactly what isWired() reports.
		assertNull(b.seenInputs.get(0).get(OUT_HASH.id()),
			"A failed dependency leaves its port unfilled");
		assertTrue(b.seenInputs.get(0).ports().isEmpty(),
			"Nothing upstream succeeded, so nothing is on any port");
	}

	@Test
	void testASkippedNodeIsReportedNotOmitted() {
		registerFailing("a");
		register("b", Map.of());

		SegmentTaskResult result = runner().run(task(List.of(segNode("a", true), segNode("b", true, "a")), Map.of()));

		// An absent result would leave the engine waiting forever for a node nobody is
		// going to run.
		assertEquals(2, result.getResults().size());
		assertEquals("b", result.getResults().get(1).getNodeId());
	}

	@Test
	void testAFailureDoesNotAbandonIndependentNodesAfterIt() {
		registerFailing("a");
		RecordingNode b = register("b", Map.of());

		// 'b' does not depend on 'a', so a failure in 'a' has nothing to do with it.
		SegmentTaskResult result = runner().run(task(List.of(segNode("a", true), segNode("b", true)), Map.of()));

		assertEquals(NodeState.FAILED, result.getResults().get(0).getState());
		assertEquals(NodeState.COMPLETED, result.getResults().get(1).getState());
		assertEquals(1, b.seenInputs.size());
	}

	@Test
	void testAnUnknownNodeKindFailsOnlyThatNode() {
		register("a", Map.of());
		// 'b' is never registered, so instantiation throws.

		SegmentTaskResult result = runner().run(task(List.of(segNode("a", true), segNode("b", true)), Map.of()));

		assertEquals(NodeState.COMPLETED, result.getResults().get(0).getState());
		assertEquals(NodeState.FAILED, result.getResults().get(1).getState());
		assertEquals(2, result.getResults().size(), "Every node still gets an answer");
	}

	@Test
	void testUnresolvableMediaFailsTheSegmentRatherThanEachNode() {
		register("a", Map.of());
		SegmentTaskRunner failing = new SegmentTaskRunner(def -> nodes.get(def.getString("id")),
			mediaRef -> {
				throw new IllegalStateException("file vanished");
			});

		SegmentTaskResult result = failing.run(task(List.of(segNode("a", true)), Map.of()));

		// Nothing could run, so reporting once is honest; inventing an identical
		// failure per node would just be noise.
		assertEquals("file vanished", result.getError());
		assertTrue(result.getResults().isEmpty());
	}

	@Test
	void testASingleNodeSegmentBehavesLikeAPlainNodeTask() {
		register("a", emits(OUT_COUNT, 1L));

		SegmentTaskResult result = runner().run(task(List.of(segNode("a", true)), Map.of()));

		// Per-node dispatch is just a segment of one, so there is no separate path to
		// keep working.
		assertEquals(1, result.getResults().size());
		assertEquals(NodeState.COMPLETED, result.getResults().get(0).getState());
		PortPayload count = result.getResults().get(0).getOutputs().get(OUT_COUNT.id());
		assertNotNull(count);
		// scalar/integer is always widened to Long at the boundary.
		assertEquals(1L, count.single());
		assertFalse(count.isMany());
	}

	@Test
	void testASkipCascadesThroughTheSegment() {
		registerFailing("a");
		register("b", Map.of());
		register("c", Map.of());

		SegmentTaskResult result = runner().run(
			task(List.of(segNode("a", true), segNode("b", true, "a"), segNode("c", true, "b")), Map.of()));

		// 'c' depends on 'b', which was skipped rather than failed. A skip must not
		// cascade as a failure - that is the engine's rule too.
		assertEquals(NodeState.SKIPPED, result.getResults().get(1).getState());
		assertEquals(NodeState.COMPLETED, result.getResults().get(2).getState(),
			"A skipped dependency does not block; only a failed one does");
	}

}
