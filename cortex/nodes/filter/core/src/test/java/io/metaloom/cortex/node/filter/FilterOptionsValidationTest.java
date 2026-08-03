package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.node.filter.assertj.FilterOptionsAssert.assertThat;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The {@code validate()} contract.
 *
 * <p>
 * Bucket rows are <em>reported</em> here rather than silently dropped, which is the opposite of what
 * {@code FilterPortResolver} does with the same data. That asymmetry is deliberate: the resolver runs
 * while an author is still typing, where dropping an unfinished row is the only sane behaviour, and
 * this runs once they have saved, where dropping one would mean a branch that quietly does not exist.
 * </p>
 */
class FilterOptionsValidationTest {

	private static FilterNodeOptions options() {
		return new FilterNodeOptions().setBuckets(new JsonArray()
			.add(new JsonObject().put("id", "de"))
			.add(new JsonObject().put("id", "en")));
	}

	@Test
	void testDefaultsAreValid() {
		assertThat(new FilterNodeOptions()).isValid();
		assertThat(options()).isValid();
	}

	@Test
	void testFilterByMustBeSet() {
		assertThat(options().setFilterBy(null)).isInvalid().hasError("filterBy must be set");
	}

	@Test
	void testModelAndUrlMustNotBeBlank() {
		assertThat(options().setModel(" ")).isInvalid().hasError("model must not be empty");
		assertThat(options().setOpenaiUrl("")).isInvalid().hasError("openaiUrl must not be empty");
	}

	@Test
	void testMaxTextCharsMustBePositive() {
		assertThat(options().setMaxTextChars(0)).isInvalid().hasError("maxTextChars must be greater than 0");
	}

	@Test
	void testMinConfidenceMustBeAProbability() {
		assertThat(options().setMinConfidence(-0.1)).isInvalid().hasError("minConfidence must be between 0 and 1");
		assertThat(options().setMinConfidence(1.1)).isInvalid().hasError("minConfidence must be between 0 and 1");
		assertThat(options().setMinConfidence(1.0)).isValid();
	}

	@Test
	void testEveryBucketNeedsAnId() {
		assertThat(options().setBuckets(new JsonArray().add(new JsonObject().put("label", "German"))))
			.isInvalid().hasError("every bucket must have an id");
	}

	@Test
	void testABucketIdMustBeAValidPortId() {
		assertThat(options().setBuckets(new JsonArray().add(new JsonObject().put("id", "Not A Port"))))
			.isInvalid().hasError("bucket id 'Not A Port' is not a valid port id");
	}

	/**
	 * A bucket named after one of the fixed ports would produce two ports with the same id, and the
	 * author's edge would attach to whichever the resolver emitted last.
	 */
	@Test
	void testAReservedBucketIdIsRejected() {
		for (String reserved : FilterBucket.RESERVED) {
			assertThat(options().setBuckets(new JsonArray().add(new JsonObject().put("id", reserved))))
				.isInvalid().hasError("bucket id '" + reserved + "' is reserved; it is one of the node's fixed ports");
		}
	}

	@Test
	void testDuplicateBucketIdsAreRejected() {
		assertThat(options().setBuckets(new JsonArray()
			.add(new JsonObject().put("id", "de"))
			.add(new JsonObject().put("id", "de"))))
				.isInvalid().hasError("duplicate bucket id 'de'");
	}

	@Test
	void testABucketMustBeAnObject() {
		assertThat(options().setBuckets(new JsonArray().add("de")))
			.isInvalid().hasError("every bucket must be an object");
	}
}
