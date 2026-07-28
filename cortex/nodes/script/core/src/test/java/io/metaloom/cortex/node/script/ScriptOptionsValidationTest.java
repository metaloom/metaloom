package io.metaloom.cortex.node.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Validation of {@link ScriptNodeOptions} and the declared-output parser.
 *
 * <p>
 * These messages are read by a pipeline author staring at a node that will not save, so each
 * assertion checks that the message names the offending value rather than merely that validation
 * failed.
 * </p>
 */
class ScriptOptionsValidationTest {

	private static JsonArray outputs(String key, String type) {
		return new JsonArray().add(new JsonObject().put("key", key).put("type", type));
	}

	private static ScriptNodeOptions valid() {
		return new ScriptNodeOptions()
			.setScript("out.text('caption', 'x');")
			.setOutputs(outputs("caption", "TEXT"));
	}

	private static List<String> errorsOf(ScriptNodeOptions options) {
		ValidationResult result = options.validate();
		return result.getErrors();
	}

	@Test
	void shouldAcceptAMinimalConfiguration() {
		assertTrue(valid().validate().isValid(), () -> String.valueOf(errorsOf(valid())));
	}

	@Test
	void shouldDefaultToARealTimeoutRatherThanNoTimeout() {
		// AbstractNodeOptions defaults timeoutMs to 0 ("no timeout"); a script node must not inherit
		// that, because an unbounded script holds a worker slot forever.
		assertEquals(ScriptNodeOptions.DEFAULT_TIMEOUT_MS, new ScriptNodeOptions().getTimeoutMs());
	}

	@Test
	void shouldRejectABlankScript() {
		assertTrue(errorsOf(valid().setScript("   ")).stream().anyMatch(e -> e.contains("script")));
	}

	@Test
	void shouldRejectABlankEngine() {
		assertTrue(errorsOf(valid().setEngine("")).stream().anyMatch(e -> e.contains("engine")));
	}

	@Test
	void shouldRejectAnEmptyOutputDeclaration() {
		assertTrue(errorsOf(valid().setOutputs(new JsonArray())).stream().anyMatch(e -> e.contains("outputs")));
	}

	@Test
	void shouldRejectAnUnknownOutputType() {
		List<String> errors = errorsOf(valid().setOutputs(outputs("caption", "MOVIE")));
		assertTrue(errors.stream().anyMatch(e -> e.contains("MOVIE")), errors::toString);
	}

	@Test
	void shouldRejectAMalformedOutputKey() {
		List<String> errors = errorsOf(valid().setOutputs(outputs("Not A Key", "TEXT")));
		assertTrue(errors.stream().anyMatch(e -> e.contains("Not A Key")), errors::toString);
	}

	@Test
	void shouldRejectDuplicateOutputKeys() {
		JsonArray duplicated = outputs("caption", "TEXT").add(new JsonObject().put("key", "caption").put("type", "STRING"));
		List<String> errors = errorsOf(valid().setOutputs(duplicated));
		assertTrue(errors.stream().anyMatch(e -> e.contains("duplicate")), errors::toString);
	}

	@Test
	void shouldRejectAMalformedRequiredInput() {
		List<String> errors = errorsOf(valid().setRequiredInputs(List.of("whisper")));
		assertTrue(errors.stream().anyMatch(e -> e.contains("nodeId:outputKey")), errors::toString);
	}

	@Test
	void shouldRejectNonPositiveLimits() {
		// setTimeoutMs is inherited from CortexNodeOptions and returns void, so it cannot chain.
		ScriptNodeOptions noTimeout = valid();
		noTimeout.setTimeoutMs(0);
		assertFalse(noTimeout.validate().isValid());

		assertFalse(valid().setStatementLimit(0).validate().isValid());
		assertFalse(valid().setMaxOutputBytes(0).validate().isValid());
		assertFalse(valid().setMaxLogLines(-1).validate().isValid());
	}

	@Test
	void shouldRejectAnOutputEntryThatIsNotAnObject() {
		JsonArray bad = new JsonArray().add("caption");
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ScriptOutputSpec.parse(bad));
		assertTrue(e.getMessage().contains("key"), e.getMessage());
	}

	@Test
	void shouldDefaultATimeframeOutputToTheChapterSegmentType() {
		ScriptOutputSpec spec = ScriptOutputSpec.parse(outputs("frames", "TIMEFRAMES")).get(0);
		assertEquals(ScriptOutputSpec.DEFAULT_SEGMENT_TYPE, spec.segmentType());
	}

	@Test
	void shouldRejectASegmentTypeTheDatabaseWouldNotAccept() {
		// asset_segment_comp CHECK-constrains segment_type; catching it here beats a 500 from the
		// REST layer halfway through a run.
		JsonArray bad = new JsonArray().add(new JsonObject()
			.put("key", "frames").put("type", "TIMEFRAMES").put("segmentType", "chapter_frames"));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ScriptOutputSpec.parse(bad));
		assertTrue(e.getMessage().contains("SCENE"), e.getMessage());
	}

	@Test
	void shouldRejectTwoTimeframeOutputsSharingASegmentType() {
		JsonArray clashing = new JsonArray()
			.add(new JsonObject().put("key", "a").put("type", "TIMEFRAMES").put("segmentType", "CHAPTER"))
			.add(new JsonObject().put("key", "b").put("type", "TIMEFRAMES").put("segmentType", "chapter"));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ScriptOutputSpec.parse(clashing));
		assertTrue(e.getMessage().contains("segmentType"), e.getMessage());
	}

	@Test
	void shouldRejectASegmentTypeOnANonTimeframeOutput() {
		JsonArray bad = new JsonArray().add(new JsonObject()
			.put("key", "caption").put("type", "TEXT").put("segmentType", "CHAPTER"));
		// Parsed via the record directly - the array parser only reads segmentType for TIMEFRAMES.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> new ScriptOutputSpec("caption", ScriptValueType.TEXT, "CHAPTER"));
		assertTrue(e.getMessage().contains("segmentType"), e.getMessage());
		assertTrue(ScriptOutputSpec.parse(bad).get(0).segmentType() == null,
			"the array parser ignores segmentType on a non-timeframe output");
	}

	@Test
	void shouldParseEveryDeclaredValueType() {
		// A guard against adding a ScriptValueType and forgetting the parser or the content type.
		for (ScriptValueType type : ScriptValueType.values()) {
			ScriptOutputSpec spec = ScriptOutputSpec.parse(outputs("k", type.name().toLowerCase())).get(0);
			assertEquals(type, spec.type());
			assertTrue(spec.type().contentType().startsWith("data/"), type + " has no content type");
		}
	}
}
