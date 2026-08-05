package io.metaloom.loom.pipeline.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Grouping nodes into units of work that run together on one worker.
 *
 * <p>Two failure modes are worth more than the rest. Merging nodes that merely
 * share a label but are not connected forces unrelated work onto one machine for
 * no benefit. Merging nodes whose combined dependencies point back at each other
 * produces a graph that can never start — strictly worse than being chatty.</p>
 */
public class PipelineSegmenterTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();
	private final PipelineSegmenter segmenter = new PipelineSegmenter();

	private JsonObject node(String id, String kind, String affinity) {
		JsonObject node = new JsonObject().put("id", id).put("type", kind);
		if (affinity != null) {
			node.put("affinity", affinity);
		}
		return node;
	}

	private JsonObject edge(String from, String to) {
		return new JsonObject().put("source", from).put("sourcePort", "media")
			.put("target", to).put("targetPort", "media");
	}

	private PipelineGraph graph(JsonArray nodes, JsonArray edges) {
		nodes.add(0, new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true));
		return parser.parse("seg", new JsonObject().put("nodes", nodes).put("edges", edges), true, false, 0);
	}

	private PipelineSegment segmentContaining(List<PipelineSegment> segments, String nodeId) {
		return segments.stream().filter(s -> s.getNodeIds().contains(nodeId)).findFirst()
			.orElseThrow(() -> new AssertionError("No segment contains '" + nodeId + "': " + segments));
	}

	@Test
	void testEverythingDefaultsToOneSegment() {
		PipelineGraph g = graph(
			new JsonArray().add(node("a", "sha512", null)).add(node("b", "thumbnail", null)),
			new JsonArray().add(edge("src", "a")).add(edge("a", "b")));

		List<PipelineSegment> segments = segmenter.segment(g);

		// The default must be "one group per pipeline". Defaulting to one group per
		// node would silently make every pipeline maximally chatty, which is exactly
		// the cost this whole mechanism exists to avoid.
		assertEquals(1, segments.size());
		assertEquals(List.of("a", "b"), segments.get(0).getNodeIds());
	}

	@Test
	void testTheSourceIsNeverPartOfASegment() {
		PipelineGraph g = graph(
			new JsonArray().add(node("a", "sha512", null)),
			new JsonArray().add(edge("src", "a")));

		List<PipelineSegment> segments = segmenter.segment(g);

		// The engine synthesises the source result rather than dispatching it, so a
		// segment containing it would hold a node no worker will ever run.
		assertTrue(segments.stream().noneMatch(s -> s.getNodeIds().contains("src")));
		assertEquals(List.of("src"), segments.get(0).getDependencies());
	}

	@Test
	void testDifferentGroupsBecomeDifferentSegments() {
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("decode", "video-decode", "video"))
				.add(node("frames", "keyframe", "video"))
				.add(node("hash", "sha512", "cheap")),
			new JsonArray().add(edge("src", "decode")).add(edge("decode", "frames")).add(edge("src", "hash")));

		List<PipelineSegment> segments = segmenter.segment(g);

		assertEquals(2, segments.size());
		assertEquals(List.of("decode", "frames"), segmentContaining(segments, "decode").getNodeIds());
		assertEquals(List.of("hash"), segmentContaining(segments, "hash").getNodeIds());
	}

	@Test
	void testSiblingsOfOneProducerBecomeOneSegment() {
		// Two branches off the source, both labelled "video", never joined to each other.
		PipelineGraph g = graph(
			new JsonArray().add(node("a", "video-decode", "video")).add(node("b", "video-decode", "video")),
			new JsonArray().add(edge("src", "a")).add(edge("src", "b")));

		List<PipelineSegment> segments = segmenter.segment(g);

		// No intermediate result passes between them, but they read the same input, and
		// reading it once is the saving affinity exists for. Requiring an edge between
		// members would lose it here for good: typed ports make analysers of one medium
		// siblings rather than a chain, so the edge this used to demand cannot exist.
		assertEquals(1, segments.size());
		assertEquals(List.of("a", "b"), segments.get(0).getNodeIds());
	}

	@Test
	void testSameGroupWithNoSharedProducerStaysSeparate() {
		// 'a' reads the source; 'b' reads 'mid', which is in another group. Same label,
		// nothing in common - neither an edge nor an input.
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("a", "video-decode", "video"))
				.add(node("mid", "sha512", "other"))
				.add(node("b", "video-decode", "video")),
			new JsonArray().add(edge("src", "a")).add(edge("src", "mid")).add(edge("mid", "b")));

		List<PipelineSegment> segments = segmenter.segment(g);

		// A shared label is still not a shared unit of work. Merging these would pin
		// unrelated work to one worker for nothing.
		assertEquals(2, segmentsWithAffinity(segments, "video"));
		assertFalse(segmentContaining(segments, "a").getNodeIds().contains("b"));
	}

	/**
	 * Two consumers of one <em>filter</em> are not siblings in the sense that matters: the item
	 * goes down exactly one branch, so one of them must not run at all. Fusing them would hand a
	 * worker a unit of work containing a node this item was routed away from, and the worker has
	 * no verdict to tell which.
	 */
	@Test
	void testSiblingsOnDifferentBranchesOfAFilterStaySeparate() {
		PipelineGraphParser typed = new PipelineGraphParser(io.metaloom.loom.pipeline.TestDescriptors.registry());
		PipelineGraph g = typed.parse("routed", new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "test-source").put("source", true))
				.add(new JsonObject().put("id", "r").put("type", "router").put("affinity", "other"))
				.add(new JsonObject().put("id", "left").put("type", "describe").put("affinity", "video"))
				.add(new JsonObject().put("id", "right").put("type", "describe").put("affinity", "video")))
			.put("edges", new JsonArray()
				.add(edge("src", "r"))
				.add(new JsonObject().put("source", "r").put("sourcePort", "a")
					.put("target", "left").put("targetPort", "media"))
				.add(new JsonObject().put("source", "r").put("sourcePort", "b")
					.put("target", "right").put("targetPort", "media"))),
			true, false, 0);

		List<PipelineSegment> segments = segmenter.segment(g);

		assertFalse(segmentContaining(segments, "left").getNodeIds().contains("right"),
			"consumers of two different branches must not travel together: " + segments);
	}

	/**
	 * A segment carries one map of inputs keyed by port id, shared by every member, so a group
	 * needing two different values for the same port id cannot be dispatched as one unit — the
	 * second member would silently receive the first's data. Being chatty is the right answer.
	 */
	@Test
	void testAGroupNeedingTwoValuesForOnePortIsSplit() {
		PipelineGraphParser typed = new PipelineGraphParser(io.metaloom.loom.pipeline.TestDescriptors.registry());
		PipelineGraph g = typed.parse("ambiguous", new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "test-source").put("source", true))
				.add(new JsonObject().put("id", "p1").put("type", "describe").put("affinity", "other"))
				.add(new JsonObject().put("id", "p2").put("type", "describe").put("affinity", "other"))
				.add(new JsonObject().put("id", "shared").put("type", "describe").put("affinity", "other"))
				// Both read "left", but from different producers; both read "right" from 'shared',
				// which is what puts them in one group in the first place.
				.add(new JsonObject().put("id", "z1").put("type", "zipper").put("affinity", "video"))
				.add(new JsonObject().put("id", "z2").put("type", "zipper").put("affinity", "video")))
			.put("edges", new JsonArray()
				.add(edge("src", "p1")).add(edge("src", "p2")).add(edge("src", "shared"))
				.add(textEdge("p1", "z1", "left")).add(textEdge("shared", "z1", "right"))
				.add(textEdge("p2", "z2", "left")).add(textEdge("shared", "z2", "right"))),
			true, false, 0);

		List<PipelineSegment> segments = segmenter.segment(g);

		assertFalse(segmentContaining(segments, "z1").getNodeIds().contains("z2"),
			"'left' would have to be p1.text and p2.text at once: " + segments);
	}

	private JsonObject textEdge(String from, String to, String targetPort) {
		return new JsonObject().put("source", from).put("sourcePort", "text")
			.put("target", to).put("targetPort", targetPort);
	}

	private long segmentsWithAffinity(List<PipelineSegment> segments, String affinity) {
		return segments.stream().filter(s -> affinity.equals(s.getAffinity())).count();
	}

	@Test
	void testASegmentReportsEveryKindItNeeds() {
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("decode", "video-decode", "video"))
				.add(node("face", "facedetect", "video")),
			new JsonArray().add(edge("src", "decode")).add(edge("decode", "face")));

		PipelineSegment segment = segmenter.segment(g).get(0);

		// A worker must be permitted to run all of them; reporting only the first
		// would place segments on workers that cannot finish them.
		assertEquals(List.of("video-decode", "facedetect"), segment.getNodeKinds());
	}

	@Test
	void testExternalDependenciesAreReportedAndInternalOnesAreNot() {
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("hash", "sha512", "cheap"))
				.add(node("decode", "video-decode", "video"))
				.add(node("face", "facedetect", "video")),
			new JsonArray().add(edge("src", "hash")).add(edge("hash", "decode")).add(edge("decode", "face")));

		PipelineSegment video = segmentContaining(segmenter.segment(g), "decode");

		// 'decode → face' is internal and must not be waited on externally; 'hash' is
		// the real gate.
		assertEquals(List.of("hash"), video.getDependencies());
	}

	@Test
	void testAGroupThatWouldDeadlockIsSplit() {
		// a → b → c with a and c grouped. Merging them makes the ac segment depend on
		// b while b depends on ac: neither can ever start.
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("a", "sha512", "x"))
				.add(node("b", "thumbnail", "y"))
				.add(node("c", "md5", "x")),
			new JsonArray().add(edge("src", "a")).add(edge("a", "b")).add(edge("b", "c")));

		List<PipelineSegment> segments = segmenter.segment(g);

		// Falling back to per-node dispatch is chatty but runnable. Accepting the merge
		// would produce a pipeline that hangs on its first item.
		assertEquals(3, segments.size());
		assertTrue(segments.stream().allMatch(PipelineSegment::isSingleNode),
			"A group that cannot be scheduled must be broken up, got: " + segments);
	}

	@Test
	void testALegitimateDiamondStaysMerged() {
		// a → b, a → c, b → d, c → d, all one group. The merged dependencies point
		// only outward, so this is safe to run as one unit.
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("a", "video-decode", "video"))
				.add(node("b", "keyframe", "video"))
				.add(node("c", "audio", "video"))
				.add(node("d", "merge", "video")),
			new JsonArray().add(edge("src", "a")).add(edge("a", "b")).add(edge("a", "c"))
				.add(edge("b", "d")).add(edge("c", "d")));

		List<PipelineSegment> segments = segmenter.segment(g);

		assertEquals(1, segments.size());
		assertEquals(4, segments.get(0).getNodeIds().size());
	}

	@Test
	void testNodesInASegmentAreInExecutionOrder() {
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("c", "md5", "video"))
				.add(node("a", "video-decode", "video"))
				.add(node("b", "keyframe", "video")),
			new JsonArray().add(edge("src", "a")).add(edge("a", "b")).add(edge("b", "c")));

		PipelineSegment segment = segmenter.segment(g).get(0);

		// Declaration order in the JSON is arbitrary. A worker runs the list as given,
		// so the order has to come from the graph, not the document.
		assertEquals(List.of("a", "b", "c"), segment.getNodeIds());
	}

	@Test
	void testSegmentsAreEmittedInDependencyOrder() {
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("late", "thumbnail", "b"))
				.add(node("early", "sha512", "a")),
			new JsonArray().add(edge("src", "early")).add(edge("early", "late")));

		List<PipelineSegment> segments = segmenter.segment(g);

		assertEquals(List.of("early"), segments.get(0).getNodeIds());
		assertEquals(List.of("late"), segments.get(1).getNodeIds());
	}

	@Test
	void testASingleNodeSegmentIsRecognisable() {
		PipelineGraph g = graph(
			new JsonArray().add(node("a", "sha512", "solo")).add(node("b", "md5", "other")),
			new JsonArray().add(edge("src", "a")).add(edge("a", "b")));

		List<PipelineSegment> segments = segmenter.segment(g);

		// Per-node dispatch is just a segment of one, so the engine needs no separate
		// path for it.
		assertTrue(segments.stream().allMatch(PipelineSegment::isSingleNode));
		assertFalse(segments.isEmpty());
	}

	@Test
	void testAGraphOfOnlyASourceHasNoSegments() {
		PipelineGraph g = graph(new JsonArray(), new JsonArray());

		assertTrue(segmenter.segment(g).isEmpty(), "There is nothing to dispatch");
	}

	/**
	 * A segment must never span an edge leaving a selective port.
	 *
	 * <p>
	 * A worker runs a segment as one unit and applies only local blocking rules — it has no idea
	 * which branch an item took. Packing a router and its branch consumer together would therefore
	 * run the branch unconditionally, which is the same defect the older {@code PASS}/{@code REJECT}
	 * edges are already segmented apart for. It needs a descriptor registry to reproduce: without one
	 * the analyzer never runs and nothing is marked selective.
	 * </p>
	 */
	@Test
	void testASelectiveEdgeEndsASegment() {
		PipelineGraphParser typed = new PipelineGraphParser(io.metaloom.loom.pipeline.TestDescriptors.registry());
		PipelineGraph g = typed.parse("routed", new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "test-source").put("source", true))
				.add(new JsonObject().put("id", "r").put("type", "router"))
				.add(new JsonObject().put("id", "d").put("type", "describe")))
			.put("edges", new JsonArray()
				.add(edge("src", "r"))
				.add(new JsonObject().put("source", "r").put("sourcePort", "a")
					.put("target", "d").put("targetPort", "media"))),
			true, false, 0);

		List<PipelineSegment> segments = segmenter.segment(g);

		assertFalse(segmentContaining(segments, "r").getNodeIds().contains("d"),
			"the router and its branch consumer must not travel to a worker together: " + segments);
	}

	/**
	 * The converse, so the rule stays as narrow as it claims to be: an edge from a router's
	 * <em>non</em>-selective port decides nothing, so it must not cost the pipeline its batching.
	 */
	@Test
	void testANonSelectiveEdgeFromARouterDoesNotEndASegment() {
		PipelineGraphParser typed = new PipelineGraphParser(io.metaloom.loom.pipeline.TestDescriptors.registry());
		PipelineGraph g = typed.parse("routed", new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "test-source").put("source", true))
				.add(new JsonObject().put("id", "r").put("type", "router"))
				.add(new JsonObject().put("id", "n").put("type", "noticer")))
			.put("edges", new JsonArray()
				.add(edge("src", "r"))
				.add(new JsonObject().put("source", "r").put("sourcePort", "label")
					.put("target", "n").put("targetPort", "label"))),
			true, false, 0);

		List<PipelineSegment> segments = segmenter.segment(g);

		assertTrue(segmentContaining(segments, "r").getNodeIds().contains("n"),
			"only the edge that actually decides a branch ends a segment: " + segments);
	}

}
