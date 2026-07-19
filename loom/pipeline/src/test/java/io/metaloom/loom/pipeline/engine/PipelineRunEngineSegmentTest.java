package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.SegmentTask;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Dispatching whole segments rather than single nodes.
 *
 * <p>This is where affinity stops being a label and starts changing what crosses
 * the network. The properties that matter: a grouped run makes one dispatch instead
 * of several, an ungrouped one is unchanged, and a fleet that cannot take a segment
 * whole still makes progress rather than stalling.</p>
 */
public class PipelineRunEngineSegmentTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	/** Records both dispatch kinds so the choice between them is observable. */
	private static class SegmentAwareDispatcher implements NodeDispatcher {

		final List<NodeTask> nodeTasks = new ArrayList<>();
		final List<SegmentTask> segmentTasks = new ArrayList<>();
		boolean acceptSegments = true;

		@Override
		public String dispatch(NodeTask task) {
			nodeTasks.add(task);
			return "worker";
		}

		@Override
		public String dispatch(SegmentTask task) {
			if (!acceptSegments) {
				return null;
			}
			segmentTasks.add(task);
			return "worker";
		}
	}

	private JsonObject node(String id, String kind, String affinity) {
		JsonObject node = new JsonObject().put("id", id).put("type", kind);
		if (affinity != null) {
			node.put("affinity", affinity);
		}
		return node;
	}

	private JsonObject edge(String from, String to) {
		return new JsonObject().put("source", from).put("target", to);
	}

	/** src -> a -> b, with a and b sharing the given group. */
	private PipelineGraph chainGraph(String affinity) {
		JsonArray nodes = new JsonArray()
			.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
			.add(node("a", "video-decode", affinity))
			.add(node("b", "keyframe", affinity));
		JsonArray edges = new JsonArray().add(edge("src", "a")).add(edge("a", "b"));
		return parser.parse("seg", new JsonObject().put("nodes", nodes).put("edges", edges), true, false, 0);
	}

	@Test
	void testAGroupedChainIsDispatchedAsOneSegment() {
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(chainGraph("video"), dispatcher, UUID.randomUUID());

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// One request instead of two, and the file is opened once rather than twice.
		assertEquals(1, dispatcher.segmentTasks.size());
		assertTrue(dispatcher.nodeTasks.isEmpty(), "A grouped chain must not also be dispatched per node");
		assertEquals(List.of("a", "b"),
			dispatcher.segmentTasks.get(0).getNodes().stream().map(n -> n.getNodeId()).toList());
	}

	@Test
	void testTheSegmentCarriesWhatItNeedsFromOutside() {
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(chainGraph("video"), dispatcher, UUID.randomUUID());

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		SegmentTask task = dispatcher.segmentTasks.get(0);
		// The source result is external to the segment, so it travels; 'a' feeding 'b'
		// is internal and must not.
		assertNotNull(task.getUpstreamOutputs().get("src"));
		assertTrue(!task.getUpstreamOutputs().containsKey("a"),
			"An intermediate result must stay on the worker, not travel");
	}

	@Test
	void testSegmentResultsSettleEveryNode() {
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(chainGraph("video"), dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		SegmentTask task = dispatcher.segmentTasks.get(0);
		engine.onSegmentTaskResult(itemId, task.getSegmentId(), List.of(
			NodeTaskResult.completed(task.getTaskUuid(), "a", 10, Map.of("frames", 5)),
			NodeTaskResult.completed(task.getTaskUuid(), "b", 10, Map.of())), null);

		assertEquals(NodeState.COMPLETED, engine.getItem(itemId).getResults().get("a").getState());
		assertEquals(NodeState.COMPLETED, engine.getItem(itemId).getResults().get("b").getState());
		assertTrue(engine.isComplete());
	}

	@Test
	void testUngroupedNodesStillDispatchIndividually() {
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		// Distinct groups, so each node is its own segment.
		JsonArray nodes = new JsonArray()
			.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
			.add(node("a", "sha512", "one"))
			.add(node("b", "md5", "two"));
		JsonArray edges = new JsonArray().add(edge("src", "a")).add(edge("a", "b"));
		PipelineGraph graph = parser.parse("seg", new JsonObject().put("nodes", nodes).put("edges", edges),
			true, false, 0);
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// The Phase 1 path is untouched for anything not deliberately grouped.
		assertEquals(1, dispatcher.nodeTasks.size());
		assertTrue(dispatcher.segmentTasks.isEmpty());
	}

	@Test
	void testAFleetThatCannotTakeTheSegmentFallsBackToPerNode() {
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		dispatcher.acceptSegments = false;
		PipelineRunEngine engine = new PipelineRunEngine(chainGraph("video"), dispatcher, UUID.randomUUID());

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// No single worker covers both kinds. Chatty beats stalled: the run proceeds
		// node by node rather than parking on a segment nobody can run.
		assertTrue(dispatcher.segmentTasks.isEmpty());
		assertEquals(1, dispatcher.nodeTasks.size());
		assertEquals("a", dispatcher.nodeTasks.get(0).getNodeId());
	}

	@Test
	void testASegmentLevelFailureSettlesEveryNodeInIt() {
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(chainGraph("video"), dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		SegmentTask task = dispatcher.segmentTasks.get(0);
		// The worker could not open the file, so no per-node results exist. Every node
		// must still be settled or they stay in flight forever.
		engine.onSegmentTaskResult(itemId, task.getSegmentId(), List.of(), "file vanished");

		assertEquals(NodeState.FAILED, engine.getItem(itemId).getResults().get("a").getState());
		assertEquals(NodeState.FAILED, engine.getItem(itemId).getResults().get("b").getState());
		assertTrue(engine.isComplete(), "The run must still be able to finish");
	}

	@Test
	void testAFilterEdgeIsNotSegmentedAcross() {
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		// A filter and its PASS branch, deliberately in the same affinity group.
		JsonArray nodes = new JsonArray()
			.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
			.add(node("filter", "size-filter", "video"))
			.add(node("thumb", "thumbnail", "video"));
		JsonArray edges = new JsonArray()
			.add(edge("src", "filter"))
			.add(edge("filter", "thumb").put("branch", "PASS"));
		PipelineGraph graph = parser.parse("seg", new JsonObject().put("nodes", nodes).put("edges", edges),
			true, false, 0);
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// The worker applies blocking-skip rules but knows nothing about branch
		// verdicts. Segmenting across a filter would run the branch node regardless of
		// the verdict - silently changing what the pipeline does.
		assertTrue(dispatcher.segmentTasks.isEmpty(),
			"A filter edge must end the segment, so routing stays with the engine");
		assertEquals(1, dispatcher.nodeTasks.size());
		assertEquals("filter", dispatcher.nodeTasks.get(0).getNodeId());
	}

	@Test
	void testADryRunDispatchesNoSegments() {
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		JsonArray nodes = new JsonArray()
			.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
			.add(node("a", "video-decode", "video"))
			.add(node("b", "keyframe", "video"));
		JsonArray edges = new JsonArray().add(edge("src", "a")).add(edge("a", "b"));
		PipelineGraph graph = parser.parse("seg", new JsonObject().put("nodes", nodes).put("edges", edges),
			true, true, 0);
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		assertTrue(dispatcher.segmentTasks.isEmpty());
		assertTrue(dispatcher.nodeTasks.isEmpty());
		assertEquals(NodeState.SKIPPED, engine.getItem(itemId).getResults().get("a").getState());
	}

	@Test
	void testASegmentCountsAsOneInFlightSlot() {
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(chainGraph("video"), dispatcher, UUID.randomUUID());
		engine.setMaxInFlight(1);

		engine.start();
		engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));

		// One request, one slot - not one per node. Counting per node would let a
		// two-node segment saturate a cap of one and stall the second item forever.
		assertEquals(1, engine.getInFlightCount());
		assertEquals(1, dispatcher.segmentTasks.size());
	}

	@Test
	void testSegmentsAreCachedRatherThanRecomputedPerItem() {
		PipelineGraph graph = chainGraph("video");

		// Topology belongs to the pipeline version, not the item. Recomputing would put
		// graph analysis on the hot path of every dispatch.
		assertTrue(graph.getSegments() == graph.getSegments());
	}

	@Test
	void testDuplicateSegmentResultsAreIgnored() {
		SegmentAwareDispatcher dispatcher = new SegmentAwareDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(chainGraph("video"), dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		SegmentTask task = dispatcher.segmentTasks.get(0);

		List<NodeTaskResult> results = List.of(
			NodeTaskResult.completed(task.getTaskUuid(), "a", 10, Map.of()),
			NodeTaskResult.completed(task.getTaskUuid(), "b", 10, Map.of()));
		engine.onSegmentTaskResult(itemId, task.getSegmentId(), results, null);
		// Redelivery is expected once retries exist and must not corrupt settled state.
		engine.onSegmentTaskResult(itemId, task.getSegmentId(), results, null);

		assertEquals(NodeState.COMPLETED, engine.getItem(itemId).getResults().get("a").getState());
		assertEquals(2, engine.getItem(itemId).getResults().size() - 1, "Only src, a and b exist");
	}

}
