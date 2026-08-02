package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What the filter emits, and — just as important — what it stays silent about.
 *
 * <p>
 * The silence is the routing: the engine skips every consumer wired to a port that carried nothing.
 * So "the German branch was not written" is an assertion with teeth, not a formality.
 * </p>
 */
class FilterNodeTest {

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		// The file has to exist: AbstractMediaNode.process fails a missing one before compute runs.
		File file = new File(tempDir, "asset.txt");
		java.nio.file.Files.writeString(file.toPath(), "irrelevant - the node classifies the wired text, not the bytes");
		media = new StubLoomMedia(file.getAbsolutePath(), false, false, false, true);
	}

	private static JsonArray buckets() {
		return new JsonArray()
			.add(new JsonObject().put("id", "de").put("label", "German").put("match", "german, deutsch"))
			.add(new JsonObject().put("id", "en").put("label", "English"));
	}

	private FilterNode node(StubLLMProvider provider, JsonObject nodeDef) {
		Provider<FilterStrategy> strategy = () -> new LanguageFilterStrategy(provider);
		FilterNode node = new FilterNode(null, cortexOptions, new FilterNodeOptions(), Map.of(FilterBy.LANGUAGE, strategy));
		node.configure(nodeDef);
		return node;
	}

	private static JsonObject nodeDef(String id) {
		return new JsonObject().put("id", id).put("filterBy", "LANGUAGE").put("buckets", buckets());
	}

	private NodeResult run(FilterNode node, String text) {
		NodeInputs inputs = text == null
			? NodeInputs.empty()
			: NodeInputs.builder().input(FilterNode.IN_TEXT, text).build();
		return node.process(NodeContext.create(media, inputs));
	}

	@Test
	void testWritesOnlyTheMatchedBucketPort() {
		NodeResult result = run(node(StubLLMProvider.answering("de"), nodeDef("lang")), "Guten Tag, wie geht es Ihnen?");

		assertThat(result)
			.isSuccess()
			.hasOutput(FilterNode.bucketPort("de"), media.absolutePath())
			// The silence is the routing: a port that carried nothing skips its consumer.
			.hasNoOutput(FilterNode.bucketPort("en"))
			.hasNoOutput(FilterNode.OUT_OTHER)
			.hasOutput(FilterNode.OUT_PASSED, Boolean.TRUE)
			.hasOutput(FilterNode.OUT_BUCKET, "de");
	}

	@Test
	void testAnUnmatchedItemGoesToOther() {
		NodeResult result = run(node(StubLLMProvider.answering("fr"), nodeDef("lang")), "Bonjour tout le monde");

		assertThat(result)
			.isSuccess()
			.hasOutput(FilterNode.OUT_OTHER, media.absolutePath())
			.hasNoOutput(FilterNode.bucketPort("de"))
			.hasNoOutput(FilterNode.bucketPort("en"))
			.hasOutput(FilterNode.OUT_PASSED, Boolean.FALSE)
			.hasOutput(FilterNode.OUT_BUCKET, "other");
	}

	/**
	 * A model answering "German" rather than "de" must still land on the {@code de} port. Throwing
	 * that away because it is not literally the id would send most items to {@code other} and look
	 * like the model was simply bad at its job.
	 */
	@Test
	void testAnAnswerMatchingALabelOrAliasStillRoutes() {
		assertEquals("de", run(node(StubLLMProvider.answering("German"), nodeDef("l")), "text").get(FilterNode.OUT_BUCKET));
		assertEquals("de", run(node(StubLLMProvider.answering("deutsch"), nodeDef("l")), "text").get(FilterNode.OUT_BUCKET));
		assertEquals("de", run(node(StubLLMProvider.answering("  DE  "), nodeDef("l")), "text").get(FilterNode.OUT_BUCKET));
	}

	/**
	 * No text wired in is a configuration, not a failure — and emitting nothing would be worse than
	 * useless, because under port routing it would close the {@code other} branch as well.
	 */
	@Test
	void testAnUnwiredTextPortRoutesToOtherWithoutCallingTheModel() {
		StubLLMProvider provider = StubLLMProvider.answering("de");
		NodeResult result = run(node(provider, nodeDef("lang")), null);

		assertThat(result).isSuccess().hasOutput(FilterNode.OUT_OTHER, media.absolutePath());
		assertEquals(0, provider.callCount(), "there is nothing to classify, so the model must not be asked");
	}

	/**
	 * A model that answers with nonsense is a routing outcome, not a task failure. The run continues
	 * and the item takes the catch-all branch.
	 */
	@Test
	void testAnUnparseableAnswerRoutesToOtherAndStillSucceeds() {
		NodeResult noBucket = run(node(StubLLMProvider.returning(new JsonObject().put("thoughts", "hmm")), nodeDef("l")), "text");
		assertThat(noBucket).isSuccess();
		assertEquals("other", noBucket.get(FilterNode.OUT_BUCKET));

		NodeResult blank = run(node(StubLLMProvider.returning(new JsonObject().put("bucket", "  ")), nodeDef("l")), "text");
		assertThat(blank).isSuccess();
		assertEquals("other", blank.get(FilterNode.OUT_BUCKET));
	}

	/** Below the configured confidence the answer is not trusted, so the item takes the catch-all. */
	@Test
	void testALowConfidenceAnswerRoutesToOther() {
		JsonObject def = nodeDef("lang").put("minConfidence", 0.8);
		assertEquals("other", run(node(StubLLMProvider.answering("de", 0.4), def), "text").get(FilterNode.OUT_BUCKET));
		assertEquals("de", run(node(StubLLMProvider.answering("de", 0.9), def), "text").get(FilterNode.OUT_BUCKET));
	}

	/**
	 * An unreachable model is the worker failing to do the job it was given, and must be FAILED — not
	 * SUCCESS. {@code ctx.failure(...).next()} silently reports success, which is the trap several
	 * older nodes still fall into.
	 */
	@Test
	void testAnUnreachableModelFailsTheTask() {
		NodeResult result = run(node(StubLLMProvider.failing("connection refused"), nodeDef("lang")), "text");

		// .abort() and not .next(): NodeContextImpl.next() ignores the failure cause and reports SUCCESS.
		assertThat(result).isFailed();
		assertEquals(ResultState.FAILED, result.getState(), "a filter that cannot classify must fail, not skip and not succeed");
	}

	@Test
	void testASecondRunIsServedFromTheCache() {
		StubLLMProvider provider = StubLLMProvider.answering("de");
		FilterNode node = node(provider, nodeDef("lang"));

		assertThat(run(node, "Guten Tag")).isSuccess();
		NodeResult second = run(node, "Guten Tag");

		assertEquals(1, provider.callCount(), "the model must be asked once per media path");
		// A cache hit is SUCCESS that re-emits, not SKIPPED - a skip would emit nothing and, under
		// port routing, close every branch below it.
		assertThat(second).isSuccess().hasOutput(FilterNode.bucketPort("de"), media.absolutePath());
	}

	/**
	 * Two filter instances in one graph must not share a verdict. Without the configuration in the
	 * cache key, the second node would re-emit the first's answer on a port it does not even have.
	 */
	@Test
	void testTwoInstancesWithDifferentBucketsDoNotShareACacheEntry() {
		JsonObject languages = nodeDef("lang");
		JsonObject other = new JsonObject().put("id", "genre").put("filterBy", "LANGUAGE")
			.put("buckets", new JsonArray().add(new JsonObject().put("id", "fr").put("label", "French")));

		FilterNode first = node(StubLLMProvider.answering("de"), languages);
		StubLLMProvider secondProvider = StubLLMProvider.answering("fr");
		FilterNode second = node(secondProvider, other);

		run(first, "text");
		NodeResult result = run(second, "text");

		assertEquals(1, secondProvider.callCount(), "the second node must ask its own model, not reuse a neighbour's answer");
		assertEquals("fr", result.get(FilterNode.OUT_BUCKET));
	}

	/**
	 * The producer version has to move when the answer's meaning does, or Loom cannot tell a stale
	 * verdict from a current one.
	 */
	@Test
	void testTheProducerVersionTracksTheConfiguration() {
		String languages = node(StubLLMProvider.answering("de"), nodeDef("lang")).producerVersion();
		String narrower = node(StubLLMProvider.answering("de"),
			nodeDef("lang").put("buckets", new JsonArray().add(new JsonObject().put("id", "de")))).producerVersion();

		assertTrue(languages.startsWith("filter/1:LANGUAGE:"), languages);
		assertNotEquals(languages, narrower, "a changed bucket set is a different producer");
	}

	/**
	 * {@code configure()} mutates the node, so a shared instance would let two concurrent filter
	 * tasks overwrite each other's buckets — and an item would be routed by whichever configuration
	 * won the race.
	 */
	@Test
	void testTheNodeIsNotASingleton() {
		org.junit.jupiter.api.Assertions.assertNull(FilterNode.class.getAnnotation(javax.inject.Singleton.class),
			"FilterNode must not be @Singleton - configure() mutates it per task");
	}

	@Test
	void testAnUnknownFilterByIsRejectedAtConfigureTime() {
		IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
			() -> node(StubLLMProvider.answering("de"), nodeDef("lang").put("filterBy", "MOOD")));

		assertTrue(e.getMessage().contains("MOOD"), e.getMessage());
	}

	@Test
	void testAReservedOrMalformedBucketIdIsRejectedAtConfigureTime() {
		IllegalStateException reserved = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
			() -> node(StubLLMProvider.answering("de"),
				nodeDef("lang").put("buckets", new JsonArray().add(new JsonObject().put("id", "other")))));
		assertTrue(reserved.getMessage().contains("reserved"), reserved.getMessage());

		IllegalStateException malformed = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
			() -> node(StubLLMProvider.answering("de"),
				nodeDef("lang").put("buckets", new JsonArray().add(new JsonObject().put("id", "Not A Port")))));
		assertTrue(malformed.getMessage().contains("not a valid port id"), malformed.getMessage());
	}
}
