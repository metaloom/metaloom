package io.metaloom.cortex.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.PortOutput;
import io.metaloom.cortex.api.node.artifact.Artifact;
import io.metaloom.cortex.api.node.artifact.ArtifactCache;
import io.metaloom.cortex.api.node.artifact.ArtifactKey;
import io.metaloom.cortex.api.node.artifact.impl.ScopedArtifactCache;
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

		/**
		 * What the node does with its inputs before returning. Runs <em>before</em> the failure so
		 * a test can have a node publish an artifact and then die, which is the interesting case.
		 */
		private Consumer<NodeInputs> action;

		RecordingNode(String id, Map<String, PortOutput> output, RuntimeException failure) {
			super(id, id, NodeMode.PARALLEL, true, 1);
			this.output = output;
			this.failure = failure;
		}

		RecordingNode doing(Consumer<NodeInputs> action) {
			this.action = action;
			return this;
		}

		@Override
		public NodeResult process(LoomMedia media, NodeInputs inputs) {
			seenInputs.add(inputs);
			if (action != null) {
				action.accept(inputs);
			}
			if (failure != null) {
				throw failure;
			}
			return NodeResult.success(id(), 1, output);
		}
	}

	private final Map<String, RecordingNode> nodes = new LinkedHashMap<>();
	private final AtomicInteger mediaResolutions = new AtomicInteger();

	private SegmentTaskRunner runner() {
		return runner(ScopedArtifactCache.DEFAULT_MAX_BYTES);
	}

	private SegmentTaskRunner runner(long maxArtifactBytes) {
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
			},
			maxArtifactBytes);
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
		return task("item-1", "/media/a.mp4", segNodes, inputs);
	}

	private SegmentTask task(String itemId, String path, List<SegmentNode> segNodes, Map<String, PortPayload> inputs) {
		return new SegmentTask(UUID.randomUUID(), UUID.randomUUID(), itemId, "seg-1", "video",
			MediaRef.of(path), segNodes, inputs);
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

	/**
	 * The converse of the test above, and the rule that lets independent analysers share a
	 * segment at all. Affinity groups fuse nodes that read the same media, and such nodes
	 * routinely emit ports their neighbours also declare — {@code consistency} emits
	 * {@code is_complete} and {@code thumbnail} has one. If being in a segment were enough to
	 * see a value, adding an affinity label would change what the pipeline computes, which is
	 * the one thing an optimisation must never do.
	 */
	@Test
	void testANodeDoesNotSeeTheOutputOfAMemberItDoesNotDependOn() {
		register("a", emits(OUT_HASH, "deadbeef"));
		RecordingNode b = register("b", Map.of());

		// 'b' names no dependencies: it is a sibling of 'a', not its consumer.
		runner().run(task(List.of(segNode("a", true), segNode("b", true)), Map.of()));

		assertNull(b.seenInputs.get(0).get(OUT_HASH.id()),
			"A segment must not be a source of data: 'b' has no edge to 'a'");
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

	// ── decode once ──────────────────────────────────────────────────────
	//
	// The saving a segment was supposed to deliver and did not: nodes receive upstream
	// outputs, those outputs are serialised back to Loom, and a 200 MB frame buffer has no
	// business travelling. The artifact scope is where it lives instead.

	/** Stands in for decoded frames: expensive to build, holds memory a collector will not reclaim promptly. */
	private static class DecodedFrames implements AutoCloseable {

		boolean closed;

		@Override
		public void close() {
			closed = true;
		}
	}

	private static final ArtifactKey<DecodedFrames> FRAMES = ArtifactKey.of("video/frames@2fps", DecodedFrames.class);

	private static final long FRAMES_BYTES = 200L * 1024 * 1024;

	/** One entry per time the expensive work actually ran. */
	private final List<DecodedFrames> decodes = new ArrayList<>();

	/** One entry per node that asked for frames, in order. */
	private final List<DecodedFrames> handedOut = new ArrayList<>();

	/** What a node needing decoded frames does: ask the scope, and decode only if nobody has yet. */
	private Consumer<NodeInputs> needsFrames() {
		return needsArtifact(FRAMES, FRAMES_BYTES);
	}

	private Consumer<NodeInputs> needsArtifact(ArtifactKey<DecodedFrames> key, long weightBytes) {
		return inputs -> handedOut.add(inputs.artifacts().get(key, () -> {
			DecodedFrames frames = new DecodedFrames();
			decodes.add(frames);
			return Artifact.of(frames, weightBytes);
		}));
	}

	@Test
	void testTwoNodesNeedingTheSameArtifactProduceItOnce() {
		register("a", Map.of()).doing(needsFrames());
		register("b", Map.of()).doing(needsFrames());

		SegmentTaskResult result = runner().run(task(List.of(segNode("a", true), segNode("b", true, "a")), Map.of()));

		assertTrue(result.getResults().stream().allMatch(r -> r.getState() == NodeState.COMPLETED));
		// This is the whole point of the task. Before the artifact scope existed both nodes
		// opened the same file and decoded it themselves; the media handle being resolved once
		// never helped, because LoomMedia is a file reference and not a decoded artifact.
		assertEquals(1, decodes.size(), "The expensive artifact must be produced once for the whole segment");
		assertEquals(2, handedOut.size(), "...and both nodes must have received it");
		assertSame(handedOut.get(0), handedOut.get(1), "The second node gets the artifact itself, not a rebuild of it");
	}

	@Test
	void testAnArtifactDoesNotLeakFromOneMediaItemToTheNext() {
		register("a", Map.of()).doing(needsFrames());
		register("b", Map.of()).doing(needsFrames());
		List<SegmentNode> segNodes = List.of(segNode("a", true), segNode("b", true, "a"));

		runner().run(task("item-A", "/media/a.mp4", segNodes, Map.of()));
		runner().run(task("item-B", "/media/b.mp4", segNodes, Map.of()));

		// Item B's nodes are handed a different scope object, so there is no key by which
		// they could reach item A's frames even if they tried. The isolation is structural,
		// not a rule someone has to remember to apply.
		assertEquals(2, decodes.size(), "Each item must decode its own media");
		assertEquals(4, handedOut.size());
		assertSame(handedOut.get(0), handedOut.get(1), "Within item A, produced once");
		assertSame(handedOut.get(2), handedOut.get(3), "Within item B, produced once");
		assertNotSame(handedOut.get(1), handedOut.get(2), "Item B must not be handed item A's artifact");
	}

	@Test
	void testAnArtifactPublishedByANodeThatThenFailsIsNotServedToTheNodesAfterIt() {
		// 'a' gets as far as publishing and then dies. Nothing in the type system can tell a
		// frame buffer it had finished filling from one it had not.
		nodes.put("a", new RecordingNode("a", Map.of(), new IllegalStateException("boom")).doing(needsFrames()));
		register("b", Map.of()).doing(needsFrames());

		// 'b' is non-blocking, so the engine's rule says it runs despite the failure upstream.
		SegmentTaskResult result = runner().run(task(List.of(segNode("a", true), segNode("b", false, "a")), Map.of()));

		assertEquals(NodeState.FAILED, result.getResults().get(0).getState());
		assertEquals(NodeState.COMPLETED, result.getResults().get(1).getState());
		assertEquals(2, decodes.size(), "The failed node's artifact must not be reused - 'b' decodes for itself");
		assertNotSame(handedOut.get(0), handedOut.get(1));
		assertTrue(decodes.get(0).closed, "The discarded artifact is released rather than left to the collector");
	}

	@Test
	void testAnArtifactFromASucceedingNodeSurvivesAFailureInADifferentNode() {
		register("a", Map.of()).doing(needsFrames());
		registerFailing("b");
		register("c", Map.of()).doing(needsFrames());

		SegmentTaskResult result = runner().run(
			task(List.of(segNode("a", true), segNode("b", true), segNode("c", true)), Map.of()));

		assertEquals(NodeState.FAILED, result.getResults().get(1).getState());
		// The rollback is per node, not per segment. 'b' failing says nothing about frames
		// 'a' finished decoding, and throwing them away would turn one failure into three.
		assertEquals(1, decodes.size(), "An unrelated failure must not invalidate an artifact that was completed");
		assertSame(handedOut.get(0), handedOut.get(1));
	}

	@Test
	void testTheScopeIsClosedWhenTheSegmentEnds() {
		register("a", Map.of()).doing(needsFrames());

		runner().run(task(List.of(segNode("a", true)), Map.of()));

		// Deterministic release is what makes a native-backed artifact safe to cache. Waiting
		// for a collector that sees a small Java object wrapping 200 MB of off-heap memory is
		// how a worker gets OOM-killed while the heap looks fine.
		assertEquals(1, decodes.size());
		assertTrue(decodes.get(0).closed, "The segment ending must release what the scope still holds");
	}

	@Test
	void testAScopeKeptPastItsSegmentFailsTheNodeRatherThanServingAStaleArtifact() {
		AtomicReference<ArtifactCache> stashed = new AtomicReference<>();
		register("a", Map.of()).doing(inputs -> {
			ArtifactCache previous = stashed.getAndSet(inputs.artifacts());
			if (previous == null) {
				inputs.artifacts().get(FRAMES, () -> {
					DecodedFrames frames = new DecodedFrames();
					decodes.add(frames);
					return Artifact.of(frames, FRAMES_BYTES);
				});
			} else {
				// The bug this guards: a node stashing the scope in a field, which node
				// instances invite because the registry reuses them across items.
				previous.peek(FRAMES);
			}
		});

		runner().run(task("item-A", "/media/a.mp4", List.of(segNode("a", true)), Map.of()));
		SegmentTaskResult second = runner().run(task("item-B", "/media/b.mp4", List.of(segNode("a", true)), Map.of()));

		assertEquals(NodeState.FAILED, second.getResults().get(0).getState(),
			"Reaching into a finished scope must fail that node, not quietly return item A's artifact");
		assertTrue(second.getResults().get(0).getMessage().contains("closed"),
			"The message must name the cause: " + second.getResults().get(0).getMessage());
	}

	@Test
	void testOneSegmentCannotPushTheScopeBeyondItsMemoryCeiling() {
		long ceiling = 2 * FRAMES_BYTES;
		List<Long> retainedAfterEachNode = new ArrayList<>();
		for (String id : List.of("a", "b", "c", "d")) {
			// Distinct keys, so each node genuinely adds to what is held rather than sharing.
			register(id, Map.of()).doing(needsArtifact(ArtifactKey.of("video/frames-" + id, DecodedFrames.class), FRAMES_BYTES)
				.andThen(inputs -> retainedAfterEachNode.add(inputs.artifacts().retainedBytes())));
		}

		runner(ceiling).run(task(List.of(segNode("a", true), segNode("b", true), segNode("c", true), segNode("d", true)),
			Map.of()));

		assertEquals(4, decodes.size(), "Every node did its own work - these are four different artifacts");
		// Within one segment the scope's lifetime bounds nothing, so the ceiling has to.
		assertTrue(retainedAfterEachNode.stream().allMatch(retained -> retained <= ceiling),
			"The scope exceeded its ceiling: " + retainedAfterEachNode);
		assertEquals(ceiling, retainedAfterEachNode.get(3), "The ceiling is used, not merely respected");
	}

	@Test
	void testALongRunOfSegmentsReleasesEveryArtifactItProduced() {
		register("a", Map.of()).doing(needsFrames());
		register("b", Map.of()).doing(needsFrames());
		List<SegmentNode> segNodes = List.of(segNode("a", true), segNode("b", true, "a"));

		for (int item = 0; item < 300; item++) {
			runner().run(task("item-" + item, "/media/" + item + ".mp4", segNodes, Map.of()));
		}

		// The bound across a run is the scope's lifetime, not a size limit: a worker that has
		// processed 300 items holds exactly what a worker that has processed one holds.
		assertEquals(300, decodes.size(), "One decode per item, not one per node");
		assertTrue(decodes.stream().allMatch(frames -> frames.closed),
			"Every segment released its artifact; nothing accumulates from one item to the next");
	}

}
