package io.metaloom.loom.agent.chat.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class VisualExtractorTest {

	private static JsonObject resultWithVisuals(JsonArray visuals) {
		return new JsonObject()
			.put("content", new JsonArray().add(new JsonObject().put("type", "text").put("text", "text")))
			.put("visuals", visuals);
	}

	private static JsonObject visual(String uuid, JsonObject payload) {
		return new JsonObject()
			.put("type", "pipeline-graph")
			.put("uuid", uuid)
			.put("label", "Pipeline " + uuid)
			.put("payload", payload);
	}

	private static JsonObject graph(int nodes) {
		JsonArray nodeArray = new JsonArray();
		for (int i = 0; i < nodes; i++) {
			nodeArray.add(new JsonObject().put("id", "pn" + i).put("kind", "sha256").put("label", "Node " + i));
		}
		return new JsonObject().put("nodes", nodeArray).put("edges", new JsonArray());
	}

	@Test
	public void testExtractAndDedupe() {
		VisualExtractor extractor = new VisualExtractor();

		assertEquals(1, extractor.extract(resultWithVisuals(new JsonArray().add(visual("p1", graph(3))))).size());
		// The same pipeline shown twice in one run is one card, not two
		assertTrue(extractor.extract(resultWithVisuals(new JsonArray().add(visual("p1", graph(3))))).isEmpty());
		assertEquals(1, extractor.extract(resultWithVisuals(new JsonArray().add(visual("p2", graph(2))))).size());
		assertEquals(2, extractor.visuals().size());
	}

	@Test
	public void testMissingOrMalformedVisuals() {
		VisualExtractor extractor = new VisualExtractor();

		assertTrue(extractor.extract(null).isEmpty());
		assertTrue(extractor.extract(new JsonObject()).isEmpty(), "Results without visuals yield nothing");
		// Entries without a type or without a payload cannot be rendered
		JsonArray visuals = new JsonArray()
			.add(new JsonObject().put("uuid", "p1").put("payload", graph(1)))
			.add(new JsonObject().put("type", "pipeline-graph").put("uuid", "p2"))
			.add(visual("p3", graph(1)));
		assertEquals(1, extractor.extract(resultWithVisuals(visuals)).size());
	}

	@Test
	public void testCap() {
		VisualExtractor extractor = new VisualExtractor();
		JsonArray visuals = new JsonArray();
		for (int i = 0; i < VisualExtractor.MAX_VISUALS + 3; i++) {
			visuals.add(visual("p" + i, graph(1)));
		}
		extractor.extract(resultWithVisuals(visuals));
		assertEquals(VisualExtractor.MAX_VISUALS, extractor.visuals().size());

		assertTrue(extractor.extract(resultWithVisuals(new JsonArray().add(visual("late", graph(1))))).isEmpty());
	}

	@Test
	public void testOversizedVisualIsDropped() {
		VisualExtractor extractor = new VisualExtractor();
		JsonObject huge = visual("p1", new JsonObject().put("blob", "x".repeat(VisualExtractor.MAX_VISUAL_BYTES + 1)));

		assertTrue(extractor.extract(resultWithVisuals(new JsonArray().add(huge))).isEmpty(),
			"An oversized payload is dropped rather than persisted onto the chat row");
		// Dropping one must not block the next, well-sized visual
		assertEquals(1, extractor.extract(resultWithVisuals(new JsonArray().add(visual("p2", graph(2))))).size());
	}

}
