package io.metaloom.cortex.node.tag;

import static io.metaloom.cortex.node.tag.assertj.TagOptionsAssert.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.tag.TagNodeOptions.Normalize;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The {@code validate()} contract.
 *
 * <p>
 * Rule rows are <em>reported</em> here rather than silently dropped, which is the opposite of what
 * {@link TagNodeOptions#rules()} does with the same data. The asymmetry is deliberate: dropping an
 * unfinished row is right while an author is still typing, and wrong once they have saved — a dropped
 * rule is a rule that never fires and never says why.
 * </p>
 */
class TagOptionsValidationTest {

	private static JsonObject rule(String id, String tag) {
		return new JsonObject()
			.put("id", id)
			.put("tag", tag)
			.put("when", new JsonArray().add(new JsonObject().put("input", "text").put("op", "NOT_BLANK")));
	}

	private static TagNodeOptions options() {
		return new TagNodeOptions().setRules(new JsonArray().add(rule("blurry", "blurry")));
	}

	@Test
	void testDefaultsAreValid() {
		// ...except that RULES needs rules, which is the whole configuration of this node.
		assertThat(new TagNodeOptions()).isInvalid().hasError("at least one rule is required when tagBy is RULES");
		assertThat(options()).isValid().hasTagBy(TagBy.RULES).hasCollection("auto").hasMaxTags(20).hasRuleIds("blurry");
		assertThat(new TagNodeOptions().setTagBy(TagBy.LABELS)).isValid();
	}

	@Test
	void testTagByAndNormalizeMustBeSet() {
		assertThat(options().setTagBy(null)).isInvalid().hasError("tagBy must be set");
		assertThat(options().setNormalize(null)).isInvalid().hasError("normalize must be set");
	}

	@Test
	void testCollectionMustNotBeBlank() {
		assertThat(options().setCollection(" ")).isInvalid().hasError("collection must not be empty");
	}

	@Test
	void testMaxTagsMustBePositive() {
		assertThat(options().setMaxTags(0)).isInvalid().hasError("maxTags must be greater than 0");
	}

	@Test
	void testMinConfidenceMustBeAProbability() {
		assertThat(options().setMinConfidence(-0.1)).isInvalid().hasError("minConfidence must be between 0 and 1");
		assertThat(options().setMinConfidence(1.1)).isInvalid().hasError("minConfidence must be between 0 and 1");
		assertThat(options().setMinConfidence(1.0)).isValid();
	}

	@Test
	void testEveryRuleNeedsAnIdAndAName() {
		assertThat(options().setRules(new JsonArray().add(new JsonObject().put("tag", "x"))))
			.isInvalid().hasError("every rule needs an id");
		assertThat(options().setRules(new JsonArray().add(new JsonObject().put("id", "r")
			.put("when", new JsonArray().add(new JsonObject().put("op", "EXISTS").put("input", "text"))))))
			.isInvalid().hasError("rule 'r' needs a tag or a tagTemplate");
	}

	@Test
	void testARuleNeedsAtLeastOneCondition() {
		assertThat(options().setRules(new JsonArray().add(new JsonObject().put("id", "r").put("tag", "x"))))
			.isInvalid().hasError("rule 'r' has no conditions, so it can never fire");
	}

	@Test
	void testDuplicateRuleIdsAreReported() {
		assertThat(options().setRules(new JsonArray().add(rule("dup", "a")).add(rule("dup", "b"))))
			.isInvalid().hasError("duplicate rule id 'dup'");
	}

	@Test
	void testAnUnknownOperatorOrInputIsReported() {
		JsonObject badOp = new JsonObject().put("id", "r").put("tag", "x")
			.put("when", new JsonArray().add(new JsonObject().put("input", "text").put("op", "BIGGER")));
		assertThat(options().setRules(new JsonArray().add(badOp)))
			.isInvalid().hasErrorMatching(error -> error.contains("unknown or missing op"));

		JsonObject badInput = new JsonObject().put("id", "r").put("tag", "x")
			.put("when", new JsonArray().add(new JsonObject().put("input", "nowhere").put("op", "EXISTS")));
		assertThat(options().setRules(new JsonArray().add(badInput)))
			.isInvalid().hasErrorMatching(error -> error.contains("unknown input 'nowhere'"));
	}

	/** A malformed regex would otherwise throw once per item, mid-run, on a worker. */
	@Test
	void testABadRegexIsReportedAtValidationTime() {
		JsonObject rule = new JsonObject().put("id", "r").put("tag", "x")
			.put("when", new JsonArray().add(new JsonObject().put("input", "text").put("op", "MATCHES").put("value", "([")));
		assertThat(options().setRules(new JsonArray().add(rule)))
			.isInvalid().hasErrorMatching(error -> error.contains("not a valid regular expression"));
	}

	@Test
	void testAnOperatorThatNeedsAValueSaysSo() {
		JsonObject rule = new JsonObject().put("id", "r").put("tag", "x")
			.put("when", new JsonArray().add(new JsonObject().put("input", "number").put("op", "GT")));
		assertThat(options().setRules(new JsonArray().add(rule))).isInvalid().hasError("rule 'r': GT needs a value");
	}

	@Test
	void testInNeedsAnArray() {
		JsonObject rule = new JsonObject().put("id", "r").put("tag", "x")
			.put("when", new JsonArray().add(new JsonObject().put("input", "text").put("op", "IN").put("value", "de")));
		assertThat(options().setRules(new JsonArray().add(rule))).isInvalid().hasError("rule 'r': IN needs an array value");
	}

	@Test
	void testATemplateWithoutAForEachIsReported() {
		JsonObject rule = new JsonObject().put("id", "r").put("tagTemplate", "lang:${value}")
			.put("when", new JsonArray().add(new JsonObject().put("op", "NOT_BLANK")));
		assertThat(options().setRules(new JsonArray().add(rule)))
			.isInvalid().hasError("rule 'r' uses a tagTemplate but no forEach, so there is no value to build a name from");
	}

	@Test
	void testForEachMustNameAListPort() {
		JsonObject rule = new JsonObject().put("id", "r").put("tagTemplate", "x:${value}").put("forEach", "text")
			.put("when", new JsonArray().add(new JsonObject().put("op", "NOT_BLANK")));
		assertThat(options().setRules(new JsonArray().add(rule)))
			.isInvalid().hasErrorMatching(error -> error.contains("iterates 'text'"));
	}

	/**
	 * Normalisation is the first guard against littering a namespace every person on the instance
	 * shares: an untrimmed name and a trimmed one are two permanent rows.
	 */
	@Test
	void testNormalisation() {
		assertThat(options()).normalizesTo("  Blurry ", "blurry");
		assertThat(options().setNormalize(Normalize.TRIM)).normalizesTo("  Blurry ", "Blurry");
		assertThat(options().setNormalize(Normalize.NONE)).normalizesTo("  Blurry ", "  Blurry ");
		assertThat(options()).normalizesTo("   ", null);
	}
}
