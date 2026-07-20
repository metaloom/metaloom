package io.metaloom.loom.pipeline.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Contract test between the pipeline editor UI and the Loom graph parser for the
 * node <code>affinity</code> field.
 *
 * <p>The UI writes affinity as a top-level string on each node object (see
 * {@code getGraphJson} in {@code PipelineEditor.tsx}); the engine reads it via
 * {@link PipelineGraphParser} and groups nodes into {@link PipelineSegment}s. If
 * the two ever disagree on the field name or its location, a five-node video
 * pipeline that the author grouped to decode once would silently decode five
 * times. These tests pin the shape the UI actually emits.</p>
 */
public class PipelineAffinitySerdeTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();
	private final PipelineSegmenter segmenter = new PipelineSegmenter();

	/**
	 * A node exactly as the editor's getGraphJson serialises it: a top-level
	 * {@code type} (the descriptor kind), {@code label}/{@code position}, an
	 * optional top-level {@code affinity} (omitted for the default group), and the
	 * UI's {@code config} bag (which the parser ignores in favour of {@code options}).
	 */
	private JsonObject uiNode(String id, String kind, String affinity) {
		JsonObject node = new JsonObject()
			.put("id", id)
			.put("type", kind)
			.put("label", kind)
			.put("position", new JsonObject().put("x", 0).put("y", 0))
			.put("config", new JsonObject());
		if (affinity != null) {
			node.put("affinity", affinity);
		}
		return node;
	}

	private JsonObject uiEdge(String id, String from, String to) {
		return new JsonObject().put("id", id).put("source", from).put("target", to);
	}

	private PipelineGraph parse(JsonArray nodes, JsonArray edges) {
		// The UI seeds every pipeline with a filesystem-source node; it emits no
		// explicit "source": true, so the parser must recognise it by its kind.
		nodes.add(0, uiNode("src", "filesystem-source", null));
		return parser.parse("ui", new JsonObject().put("nodes", nodes).put("edges", edges), true, false, 0);
	}

	private PipelineSegment segmentContaining(List<PipelineSegment> segments, String nodeId) {
		return segments.stream().filter(s -> s.getNodeIds().contains(nodeId)).findFirst()
			.orElseThrow(() -> new AssertionError("No segment contains '" + nodeId + "': " + segments));
	}

	@Test
	void testUiWrittenAffinityIsReadAtTheNodeTopLevel() {
		PipelineGraph g = parse(
			new JsonArray()
				.add(uiNode("sha512", "sha512", "groupA"))
				.add(uiNode("thumb", "thumbnail", "groupA")),
			new JsonArray().add(uiEdge("e1", "src", "sha512")).add(uiEdge("e2", "sha512", "thumb")));

		// The exact field the UI wrote must be the field the engine reads.
		assertEquals("groupA", g.getNode("sha512").getAffinity());
		assertEquals("groupA", g.getNode("thumb").getAffinity());
	}

	@Test
	void testTwoConnectedNodesSharingAGroupFormOneSegment() {
		PipelineGraph g = parse(
			new JsonArray()
				.add(uiNode("sha512", "sha512", "groupA"))
				.add(uiNode("thumb", "thumbnail", "groupA"))
				.add(uiNode("md5", "md5", null)),
			new JsonArray()
				.add(uiEdge("e1", "src", "sha512"))
				.add(uiEdge("e2", "sha512", "thumb"))
				.add(uiEdge("e3", "src", "md5")));

		List<PipelineSegment> segments = segmenter.segment(g);

		// The two grouped, connected nodes decode/dispatch as a single unit …
		PipelineSegment grouped = segmentContaining(segments, "sha512");
		assertEquals(List.of("sha512", "thumb"), grouped.getNodeIds());
		assertEquals("groupA", grouped.getAffinity());

		// … while the ungrouped (default) node is dispatched on its own.
		PipelineSegment defaulted = segmentContaining(segments, "md5");
		assertEquals(List.of("md5"), defaulted.getNodeIds());
		assertNotSame(grouped, defaulted);
		assertEquals(2, segments.size());
	}

	@Test
	void testMissingAffinityDefaultsToDefaultGroup() {
		// A definition authored before affinity existed carries no affinity fields.
		PipelineGraph g = parse(
			new JsonArray()
				.add(uiNode("a", "sha512", null))
				.add(uiNode("b", "thumbnail", null)),
			new JsonArray().add(uiEdge("e1", "src", "a")).add(uiEdge("e2", "a", "b")));

		// Every node falls back to the implicit "default" group …
		assertEquals(PipelineGraphNode.DEFAULT_AFFINITY, g.getNode("a").getAffinity());
		assertEquals(PipelineGraphNode.DEFAULT_AFFINITY, g.getNode("b").getAffinity());

		// … and the whole pipeline stays one segment (backward compatible).
		List<PipelineSegment> segments = segmenter.segment(g);
		assertEquals(1, segments.size());
		assertEquals(List.of("a", "b"), segments.get(0).getNodeIds());
	}

	@Test
	void testBlankAffinityCollapsesToDefault() {
		// The UI omits default, but a hand-edited or legacy definition may carry an
		// empty string; it must not become a distinct one-node group.
		PipelineGraph g = parse(
			new JsonArray()
				.add(uiNode("a", "sha512", ""))
				.add(uiNode("b", "thumbnail", "")),
			new JsonArray().add(uiEdge("e1", "src", "a")).add(uiEdge("e2", "a", "b")));

		assertEquals(PipelineGraphNode.DEFAULT_AFFINITY, g.getNode("a").getAffinity());
		assertTrue(segmenter.segment(g).size() == 1, "Blank affinity must not split the pipeline");
	}
}
