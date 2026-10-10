package io.metaloom.cortex.node.script.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.script.ScriptOutputSpec;
import io.metaloom.cortex.node.script.ScriptValueType;
import io.vertx.core.json.JsonObject;

/**
 * The {@code out} binding's declaration check and coercion, exercised directly.
 *
 * <p>
 * {@code ScriptNodeTest} drives this class through the real GraalJS engine, which is the right test
 * for the contract a pipeline author sees but a poor one for the coercion branches: every rejection
 * arrives there as a node failure whose message is the only evidence of which branch ran. These
 * tests call the collector so each branch is pinned on its own, and so the image path can be
 * observed through a sink that records what it was handed.
 * </p>
 */
class ScriptOutputCollectorTest {

	/** Everything the sink was asked to write, so the image path is observable without a filesystem. */
	private final List<byte[]> written = new ArrayList<>();

	private ScriptOutputCollector collector(ScriptOutputSpec... specs) {
		return new ScriptOutputCollector(List.of(specs), (key, index, bytes) -> {
			written.add(bytes);
			return "/meta/script_bin/" + key + "-" + index + ".png";
		});
	}

	private static ScriptOutputSpec spec(String key, ScriptValueType type) {
		return new ScriptOutputSpec(key, type);
	}

	/** A collected list value, typed so AssertJ's varargs matchers bind. */
	@SuppressWarnings("unchecked")
	private static List<Object> listValue(ScriptOutputCollector collector, String key) {
		return (List<Object>) collector.values().get(key);
	}

	// ----------------------------------------------------------------------------------------
	// The declaration check
	// ----------------------------------------------------------------------------------------

	@Test
	void testAnUndeclaredKeyIsRejectedAndNamesWhatIsDeclared() {
		ScriptOutputCollector collector = collector(spec("caption", ScriptValueType.TEXT));

		ScriptOutputException error = assertThrows(ScriptOutputException.class, () -> collector.set("captoin", "typo"));
		// A silently dropped typo would leave the drawn graph claiming an edge carries data that
		// never flows, so the message has to name both the typo and the real keys.
		assertThat(error).hasMessageContaining("captoin").hasMessageContaining("caption");
		assertThat(collector.values()).isEmpty();
	}

	@Test
	void testNullIsRejectedRatherThanStoredAsAnAbsentValue() {
		ScriptOutputCollector collector = collector(spec("caption", ScriptValueType.TEXT));
		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("caption", null)))
			.hasMessageContaining("omit the call");
	}

	@Test
	void testATypedHelperOnTheWrongDeclarationNamesBothTypes() {
		ScriptOutputCollector collector = collector(spec("caption", ScriptValueType.TEXT));

		ScriptOutputException error = assertThrows(ScriptOutputException.class,
			() -> collector.setTyped("caption", List.of(), ScriptValueType.TIMEFRAMES));
		assertThat(error).hasMessageContaining("TEXT").hasMessageContaining("TIMEFRAMES");
	}

	@Test
	void testATypedHelperOnAnUndeclaredKeyStillReportsTheDeclarationError() {
		// setTyped short-circuits on a null spec; the "not declared" message is the useful one.
		ScriptOutputCollector collector = collector(spec("caption", ScriptValueType.TEXT));
		assertThat(assertThrows(ScriptOutputException.class,
			() -> collector.setTyped("nope", "x", ScriptValueType.TEXT))).hasMessageContaining("not declared");
	}

	// ----------------------------------------------------------------------------------------
	// Scalar coercion
	// ----------------------------------------------------------------------------------------

	@Test
	void testNumericCoercionKeepsIntegersWholeAndRejectsNonNumbers() {
		ScriptOutputCollector collector = collector(spec("count", ScriptValueType.INTEGER), spec("score", ScriptValueType.NUMBER));

		collector.set("count", "7");
		collector.set("score", 3);

		assertEquals(7L, collector.values().get("count"));
		assertEquals(3.0d, collector.values().get("score"));
		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("count", "seven")))
			.hasMessageContaining("expects a number");
	}

	@Test
	void testBooleanCoercionAcceptsOnlyTheTwoWords() {
		ScriptOutputCollector collector = collector(spec("ok", ScriptValueType.BOOLEAN));

		collector.set("ok", "TRUE");
		assertEquals(Boolean.TRUE, collector.values().get("ok"));
		collector.set("ok", false);
		assertEquals(Boolean.FALSE, collector.values().get("ok"));

		// Not truthiness: a script emitting 1 or "yes" for a boolean has a bug, and guessing which
		// way it meant would hide it.
		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("ok", "yes"))).hasMessageContaining("expects a boolean");
		assertThrows(ScriptOutputException.class, () -> collector.set("ok", 1));
	}

	@Test
	void testJsonCoercionAcceptsAMapOrAnEncodedObjectAndRejectsAnythingElse() {
		ScriptOutputCollector collector = collector(spec("meta", ScriptValueType.JSON));

		collector.set("meta", Map.of("a", 1));
		assertEquals(1, ((JsonObject) collector.values().get("meta")).getInteger("a"));

		collector.set("meta", "{\"b\":2}");
		assertEquals(2, ((JsonObject) collector.values().get("meta")).getInteger("b"));

		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("meta", "not json")))
			.hasMessageContaining("did not parse");
		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("meta", 42)))
			.hasMessageContaining("expects a JSON object");
	}

	@Test
	void testATextListRejectsANonListAndStringifiesItsElements() {
		ScriptOutputCollector collector = collector(spec("tags", ScriptValueType.TEXT_LIST));

		collector.set("tags", new ArrayList<>(List.of("a", 2)));
		assertThat(listValue(collector, "tags")).containsExactly("a", "2");

		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("tags", "a,b")))
			.hasMessageContaining("expects a list");
	}

	// ----------------------------------------------------------------------------------------
	// Timeframes
	// ----------------------------------------------------------------------------------------

	@Test
	void testTimeframesKeepTheOptionalFieldsAndNormaliseTheBounds() {
		ScriptOutputCollector collector = collector(new ScriptOutputSpec("marks", ScriptValueType.TIMEFRAMES, "CHAPTER"));

		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("startMs", 1_000.0d);
		frame.put("endMs", 2_500L);
		frame.put("label", 7);
		frame.put("data", Map.of("k", "v"));
		collector.set("marks", List.of(frame));

		JsonObject stored = (JsonObject) listValue(collector, "marks").get(0);
		// A JS number arrives as a double; the segment row's bounds are whole milliseconds.
		assertEquals(1_000L, stored.getLong("startMs"));
		assertEquals(2_500L, stored.getLong("endMs"));
		assertEquals("7", stored.getString("label"));
		assertEquals("v", stored.getJsonObject("data").getString("k"));
	}

	@Test
	void testEveryMalformedTimeframeIsRejectedWithItsIndex() {
		ScriptOutputCollector collector = collector(new ScriptOutputSpec("marks", ScriptValueType.TIMEFRAMES, "CHAPTER"));

		// Not an object at all.
		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("marks", List.of("0-5"))))
			.hasMessageContaining("marks'[0]");
		// Missing one bound. Validated here rather than at persistence time so the error points at
		// the script instead of at a REST rejection.
		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("marks", List.of(Map.of("startMs", 0)))))
			.hasMessageContaining("needs both startMs and endMs");
		// end before start, and a negative start.
		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("marks", List.of(Map.of("startMs", 500, "endMs", 100)))))
			.hasMessageContaining("invalid range 500..100");
		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("marks", List.of(Map.of("startMs", -1, "endMs", 100)))))
			.hasMessageContaining("invalid range");
		// A zero-length mark is legal - a point on a timeline.
		collector.set("marks", List.of(Map.of("startMs", 100, "endMs", 100)));
		assertThat(listValue(collector, "marks")).hasSize(1);
	}

	// ----------------------------------------------------------------------------------------
	// Images: bytes vs. base64 vs. path
	// ----------------------------------------------------------------------------------------

	/**
	 * ⚠️ The regression this class exists for.
	 *
	 * <p>
	 * {@code looksBase64} used to require {@code indexOf('/') < 0}, but {@code '/'} is part of the
	 * base64 alphabet - a base64-encoded PNG of any size contains one with near certainty. So
	 * {@code out.image(key, base64)} took the <em>path</em> branch: no bytes were written, nothing
	 * failed, and the emitted value was the base64 blob itself, handed to a downstream consumer as
	 * if it were a file path.
	 * </p>
	 */
	@Test
	void testBase64ImageBytesContainingASlashAreWrittenRatherThanTreatedAsAPath() {
		byte[] bytes = new byte[512];
		for (int i = 0; i < bytes.length; i++) {
			// 0xFF bytes encode as '/' sextets, so the slash the old heuristic tripped over is
			// guaranteed to be there rather than statistically likely.
			bytes[i] = (byte) (i % 4 == 0 ? 0xFF : i * 7);
		}
		String base64 = Base64.getEncoder().encodeToString(bytes);
		assertThat(base64).contains("/");

		ScriptOutputCollector collector = collector(spec("frame", ScriptValueType.IMAGE));
		collector.set("frame", base64);

		assertThat(written).hasSize(1);
		assertThat(written.get(0)).isEqualTo(bytes);
		assertEquals("/meta/script_bin/frame-0.png", collector.values().get("frame"));
	}

	@Test
	void testAPathIsPassedThroughUntouched() {
		ScriptOutputCollector collector = collector(spec("frame", ScriptValueType.IMAGE));
		collector.set("frame", "/var/tmp/already-rendered.png");

		// Re-reading and re-writing a file the script already produced would only copy bytes around.
		assertThat(written).isEmpty();
		assertEquals("/var/tmp/already-rendered.png", collector.values().get("frame"));
	}

	@Test
	void testAnImageListWritesOneFilePerElementInOrder() {
		ScriptOutputCollector collector = collector(spec("frames", ScriptValueType.IMAGE_LIST));

		// A JS byte array arrives as a list of numbers.
		collector.set("frames", List.of(List.of(1, 2, 3), List.of(4, 5)));

		assertThat(written).hasSize(2);
		assertThat(written.get(0)).isEqualTo(new byte[] { 1, 2, 3 });
		assertThat(written.get(1)).isEqualTo(new byte[] { 4, 5 });
		assertThat(listValue(collector, "frames"))
			.containsExactly("/meta/script_bin/frames-0.png", "/meta/script_bin/frames-1.png");
	}

	@Test
	void testAnImageOutputWithoutASinkIsAnErrorRatherThanASilentDrop() {
		ScriptOutputCollector collector = new ScriptOutputCollector(List.of(spec("frame", ScriptValueType.IMAGE)), null);
		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("frame", "AAAA==")))
			.hasMessageContaining("no binary sink");
	}

	@Test
	void testAnImageThatIsNeitherBytesNorBase64NorAPathIsRejected() {
		ScriptOutputCollector collector = collector(spec("frame", ScriptValueType.IMAGE));
		assertThat(assertThrows(ScriptOutputException.class, () -> collector.set("frame", 42)))
			.hasMessageContaining("must be bytes, a base64 string, or a path");
	}

	/** Line-wrapped base64 - what most encoders emit - passes looksBase64 and must then also decode. */
	@Test
	void testLineWrappedBase64IsDecoded() {
		byte[] bytes = new byte[300];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) i;
		}
		String wrapped = Base64.getMimeEncoder().encodeToString(bytes);
		assertThat(wrapped).contains("\r\n");
		ScriptOutputCollector collector = collector(spec("img", ScriptValueType.IMAGE));

		collector.set("img", wrapped);

		assertThat(written).hasSize(1);
		assertThat(written.get(0)).isEqualTo(bytes);
	}

	@Test
	void testNonFiniteNumbersAreRejected() {
		ScriptOutputCollector collector = collector(spec("n", ScriptValueType.NUMBER), spec("i", ScriptValueType.INTEGER));

		assertThrows(ScriptOutputException.class, () -> collector.set("n", Double.NaN));
		assertThrows(ScriptOutputException.class, () -> collector.set("i", Double.POSITIVE_INFINITY));
		assertThrows(ScriptOutputException.class, () -> collector.set("n", "Infinity"));
		collector.set("n", 0.5d);
		assertEquals(0.5d, collector.values().get("n"));
	}
}
