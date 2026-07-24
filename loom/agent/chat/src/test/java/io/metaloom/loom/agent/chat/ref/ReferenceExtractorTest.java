package io.metaloom.loom.agent.chat.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class ReferenceExtractorTest {

	private static JsonObject resultWithRefs(JsonArray refs) {
		return new JsonObject()
			.put("content", new JsonArray().add(new JsonObject().put("type", "text").put("text", "text")))
			.put("references", refs);
	}

	private static JsonObject ref(String type, String uuid) {
		return new JsonObject().put("type", type).put("uuid", uuid).put("label", "label-" + uuid);
	}

	@Test
	public void testExtractAndDedupe() {
		ReferenceExtractor extractor = new ReferenceExtractor();

		JsonArray first = extractor.extract(resultWithRefs(new JsonArray().add(ref("asset", "a1")).add(ref("asset", "a2"))));
		assertEquals(2, first.size());

		// A second result repeating a1 must only yield the new reference
		JsonArray second = extractor.extract(resultWithRefs(new JsonArray().add(ref("asset", "a1")).add(ref("collection", "c1"))));
		assertEquals(1, second.size());
		assertEquals("c1", second.getJsonObject(0).getString("uuid"));

		// Same uuid with a different type is a distinct reference
		JsonArray third = extractor.extract(resultWithRefs(new JsonArray().add(ref("task", "a1"))));
		assertEquals(1, third.size());

		assertEquals(4, extractor.references().size());
	}

	@Test
	public void testMissingOrMalformedReferences() {
		ReferenceExtractor extractor = new ReferenceExtractor();

		assertTrue(extractor.extract(null).isEmpty());
		assertTrue(extractor.extract(new JsonObject()).isEmpty(), "Results without references yield nothing");
		// Entries without type or uuid are skipped
		JsonArray refs = new JsonArray()
			.add(new JsonObject().put("uuid", "u1"))
			.add(new JsonObject().put("type", "asset"))
			.add(ref("asset", "ok"));
		assertEquals(1, extractor.extract(resultWithRefs(refs)).size());
	}

	@Test
	public void testCap() {
		ReferenceExtractor extractor = new ReferenceExtractor();
		JsonArray refs = new JsonArray();
		for (int i = 0; i < ReferenceExtractor.MAX_REFERENCES + 10; i++) {
			refs.add(ref("asset", "a" + i));
		}
		extractor.extract(resultWithRefs(refs));
		assertEquals(ReferenceExtractor.MAX_REFERENCES, extractor.references().size());

		// Further results are ignored once the cap is reached
		assertTrue(extractor.extract(resultWithRefs(new JsonArray().add(ref("asset", "late")))).isEmpty());
	}

}
