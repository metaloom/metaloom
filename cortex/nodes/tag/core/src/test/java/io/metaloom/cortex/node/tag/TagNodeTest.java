package io.metaloom.cortex.node.tag;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * What the node decides to tag, offline - no Loom, so the assertions are about the verdict rather
 * than the writes ({@link TagNodePersistenceTest} covers those).
 *
 * <p>
 * The recurring theme is that <em>nothing</em> about a missing input is an error. A rule reading a
 * port nobody wired reports itself as skipped and the item gets no tag from it; that is a
 * configuration, and a node that refused to start would only make the missing edge harder to find.
 * </p>
 */
class TagNodeTest {

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;

	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		// The file has to exist: AbstractMediaNode.process fails a missing one before compute runs.
		File file = new File(tempDir, "asset.jpg");
		java.nio.file.Files.writeString(file.toPath(), "irrelevant - the node reads its ports, not the bytes");
		media = new StubLoomMedia(file.getAbsolutePath(), false, false, false, true);
	}

	private TagNode node(JsonObject nodeDef) {
		Provider<TagStrategy> rules = RulesTagStrategy::new;
		Provider<TagStrategy> labels = LabelsTagStrategy::new;
		TagNode node = new TagNode(null, cortexOptions, new TagNodeOptions(),
			Map.of(TagBy.RULES, rules, TagBy.LABELS, labels));
		node.configure(nodeDef);
		return node;
	}

	private static JsonObject rule(String id, String tag, JsonObject... conditions) {
		JsonArray when = new JsonArray();
		for (JsonObject condition : conditions) {
			when.add(condition);
		}
		return new JsonObject().put("id", id).put("tag", tag).put("when", when);
	}

	private static JsonObject when(String input, String path, String op, Object value) {
		JsonObject condition = new JsonObject().put("op", op);
		if (input != null) {
			condition.put("input", input);
		}
		if (path != null) {
			condition.put("path", path);
		}
		if (value != null) {
			condition.put("value", value);
		}
		return condition;
	}

	private static JsonObject nodeDef(String id, JsonObject... rules) {
		JsonArray array = new JsonArray();
		for (JsonObject rule : rules) {
			array.add(rule);
		}
		return new JsonObject().put("id", id).put("collection", "quality").put("rules", array);
	}

	private NodeResult run(TagNode node, NodeInputs inputs) {
		return node.process(NodeContext.create(media, inputs));
	}

	/** The applied tag names, in order. */
	private static List<String> applied(NodeResult result) {
		JsonObject record = new JsonObject((String) result.get(TagNode.OUT_APPLIED));
		return record.getJsonArray("applied").stream()
			.map(entry -> ((JsonObject) entry).getString("tag"))
			.toList();
	}

	private static JsonObject record(NodeResult result) {
		return new JsonObject((String) result.get(TagNode.OUT_APPLIED));
	}

	@Test
	void testAThresholdOnAStructPathTags() {
		TagNode node = node(nodeDef("quality-tags",
			rule("blurry", "blurry", when("struct", "blurriness", "GT", 0.6)),
			rule("lowres", "low-resolution", when("struct", "width", "LT", 800))));

		NodeResult result = run(node, NodeInputs.builder()
			.input(TagNode.IN_STRUCT, new JsonObject().put("blurriness", 0.9).put("width", 4000).encode())
			.build());

		assertThat(result).isSuccess();
		assertEquals(List.of("blurry"), applied(result));
		assertEquals(1L, result.get(TagNode.OUT_COUNT));
	}

	@Test
	void testANestedPathAndAnArrayIndexResolve() {
		TagNode node = node(nodeDef("colors",
			rule("warm", "warm", when("struct", "colors.0.name", "EQ", "amber"))));

		NodeResult result = run(node, NodeInputs.builder()
			.input(TagNode.IN_STRUCT, new JsonObject()
				.put("colors", new JsonArray().add(new JsonObject().put("name", "amber")))
				.encode())
			.build());

		assertEquals(List.of("warm"), applied(result));
	}

	/**
	 * ALL is the default and ANY is opt-in, because a rule that fires when <em>any</em> of its
	 * conditions holds is the one more likely to litter a shared namespace by accident.
	 */
	@Test
	void testAllAndAnyCombineTheConditions() {
		JsonObject both = rule("print", "print-ready",
			when("struct", "width", "GTE", 3000),
			when("struct", "blurriness", "LT", 0.2));

		JsonInputs inputs = new JsonInputs(4000, 0.5);
		assertEquals(List.of(), applied(run(node(nodeDef("all", both)), inputs.build())));

		JsonObject any = both.copy().put("match", "ANY");
		assertEquals(List.of("print-ready"), applied(run(node(nodeDef("any", any)), inputs.build())));
	}

	/** Builds a quality-shaped struct payload. */
	private record JsonInputs(int width, double blurriness) {
		NodeInputs build() {
			return NodeInputs.builder()
				.input(TagNode.IN_STRUCT, new JsonObject().put("width", width).put("blurriness", blurriness).encode())
				.build();
		}
	}

	@Test
	void testEveryOperatorAgainstItsPortType() {
		assertEquals(List.of("t"), applied(run(node(nodeDef("n", rule("r", "t", when("text", null, "CONTAINS", "refund")))),
			NodeInputs.builder().input(TagNode.IN_TEXT, "we want a REFUND now").build())));
		assertEquals(List.of("t"), applied(run(node(nodeDef("n", rule("r", "t", when("text", null, "MATCHES", "(?i)\\brefund\\b")))),
			NodeInputs.builder().input(TagNode.IN_TEXT, "a Refund, please").build())));
		assertEquals(List.of("t"), applied(run(node(nodeDef("n", rule("r", "t", when("text", null, "STARTS_WITH", "dear")))),
			NodeInputs.builder().input(TagNode.IN_TEXT, "Dear Sir").build())));
		assertEquals(List.of("t"), applied(run(node(nodeDef("n", rule("r", "t", when("number", null, "LT", 0)))),
			NodeInputs.builder().input(TagNode.IN_NUMBER, -0.4).build())));
		assertEquals(List.of("t"), applied(run(node(nodeDef("n", rule("r", "t", when("flag", null, "EQ", true)))),
			NodeInputs.builder().input(TagNode.IN_FLAG, true).build())));
		assertEquals(List.of("t"), applied(run(node(nodeDef("n", rule("r", "t", when("text", null, "NOT_BLANK", null)))),
			NodeInputs.builder().input(TagNode.IN_TEXT, "something").build())));
		assertEquals(List.of("t"), applied(run(node(nodeDef("n", rule("r", "t", when("struct", "lang", "IN", new JsonArray().add("de").add("en"))))),
			NodeInputs.builder().input(TagNode.IN_STRUCT, new JsonObject().put("lang", "de").encode()).build())));
		assertEquals(List.of("t"), applied(run(node(nodeDef("n", rule("r", "t", when("struct", "lang", "EXISTS", null)))),
			NodeInputs.builder().input(TagNode.IN_STRUCT, new JsonObject().put("lang", "de").encode()).build())));
	}

	/**
	 * A path that does not resolve is a false condition, not an exception: upstream payloads differ by
	 * media type, and one missing field must not fail the item.
	 */
	@Test
	void testAnUnresolvedPathIsFalse() {
		NodeResult result = run(node(nodeDef("n", rule("r", "t", when("struct", "no.such.path", "GT", 1)))),
			NodeInputs.builder().input(TagNode.IN_STRUCT, new JsonObject().put("width", 10).encode()).build());

		assertThat(result).isSuccess();
		assertEquals(List.of(), applied(result));
	}

	/**
	 * A rule whose port nobody wired says so on the record. Silence would leave an author comparing a
	 * rule that is wrong against an edge that is missing with no way to tell which.
	 */
	@Test
	void testAnUnwiredPortSkipsTheRuleAndSaysSo() {
		NodeResult result = run(node(nodeDef("n", rule("r", "needs-review", when("number", null, "LT", 0)))), NodeInputs.empty());

		assertThat(result).isSuccess();
		assertEquals(List.of(), applied(result));
		assertEquals(1, record(result).getJsonArray("skippedRules").size());
		assertTrue(record(result).getJsonArray("skippedRules").getString(0).contains("number"));
	}

	@Test
	void testForEachOverTheLabelsPortTemplatesAName() {
		JsonObject rule = new JsonObject()
			.put("id", "language")
			.put("tagTemplate", "lang:${value}")
			.put("forEach", "labels")
			.put("when", new JsonArray().add(when(null, null, "NOT_BLANK", null)));

		NodeResult result = run(node(nodeDef("langs", rule)), NodeInputs.builder()
			.inputs(TagNode.IN_LABELS, List.of("de", "en"))
			.build());

		assertEquals(List.of("lang:de", "lang:en"), applied(result));
	}

	@Test
	void testTheLabelsStrategyTagsEveryElement() {
		TagNode node = node(new JsonObject().put("id", "colours").put("tagBy", "LABELS").put("collection", "colour"));

		NodeResult result = run(node, NodeInputs.builder()
			.inputs(TagNode.IN_LABELS, List.of("Amber", "teal"))
			.build());

		// TRIM_LOWER by default, because "Amber" and "amber" would otherwise be two permanent rows.
		assertEquals(List.of("amber", "teal"), applied(result));
	}

	@Test
	void testNormalisationIsAppliedBeforeTheAllowList() {
		JsonObject def = new JsonObject().put("id", "c").put("tagBy", "LABELS").put("collection", "colour")
			.put("allowedTags", new JsonArray().add("Amber"));

		NodeResult result = run(node(def), NodeInputs.builder().inputs(TagNode.IN_LABELS, List.of("  AMBER  ")).build());

		// The allow-list is normalised the same way the computed name is, so an author does not have
		// to know which case the upstream node happens to emit.
		assertEquals(List.of("amber"), applied(result));
	}

	@Test
	void testANameOutsideTheAllowListIsRejectedAndRecorded() {
		JsonObject def = new JsonObject().put("id", "c").put("tagBy", "LABELS").put("collection", "colour")
			.put("allowedTags", new JsonArray().add("amber"));

		NodeResult result = run(node(def), NodeInputs.builder()
			.inputs(TagNode.IN_LABELS, List.of("amber", "chartreuse"))
			.build());

		assertEquals(List.of("amber"), applied(result));
		JsonObject rejected = record(result).getJsonArray("rejected").getJsonObject(0);
		assertEquals("chartreuse", rejected.getString("tag"));
		assertEquals("not in allowedTags", rejected.getString("reason"));
	}

	@Test
	void testMaxTagsCapsTheItem() {
		JsonObject def = new JsonObject().put("id", "c").put("tagBy", "LABELS").put("collection", "colour").put("maxTags", 2);

		NodeResult result = run(node(def), NodeInputs.builder()
			.inputs(TagNode.IN_LABELS, List.of("one", "two", "three"))
			.build());

		assertEquals(List.of("one", "two"), applied(result));
		assertTrue(record(result).getJsonArray("rejected").getJsonObject(0).getString("reason").contains("maxTags"));
	}

	@Test
	void testTheSameNameIsAttachedOnce() {
		JsonObject def = new JsonObject().put("id", "c").put("tagBy", "LABELS").put("collection", "colour");

		NodeResult result = run(node(def), NodeInputs.builder()
			.inputs(TagNode.IN_LABELS, List.of("amber", "Amber"))
			.build());

		assertEquals(List.of("amber"), applied(result));
	}

	/** A rule may name its own collection; the node option is only the fallback. */
	@Test
	void testARuleCollectionOverridesTheNodeCollection() {
		JsonObject rule = rule("lang", "german", when("text", null, "CONTAINS", "guten"))
			.put("collection", "language");

		NodeResult result = run(node(nodeDef("n", rule)), NodeInputs.builder().input(TagNode.IN_TEXT, "Guten Tag").build());

		assertEquals("language", record(result).getJsonArray("applied").getJsonObject(0).getString("collection"));
	}

	/**
	 * An unconfigured node skips rather than tagging: with no rules it could only ever write nothing,
	 * and it would do so for every item in the run.
	 */
	@Test
	void testAnUnconfiguredNodeSkips() {
		TagNode node = new TagNode(null, cortexOptions, new TagNodeOptions(), Map.of(TagBy.RULES, (Provider<TagStrategy>) RulesTagStrategy::new));

		NodeResult result = node.process(NodeContext.create(media, NodeInputs.empty()));

		assertThat(result).isSkipped();
	}

	/** A misconfiguration fails the task by name rather than tagging wrongly for a whole run. */
	@Test
	void testAnUnknownStrategyOrRuleIsRejectedAtConfigureTime() {
		assertThrows(IllegalStateException.class,
			() -> node(new JsonObject().put("id", "n").put("tagBy", "MAGIC")));
		assertThrows(IllegalStateException.class,
			() -> node(nodeDef("n", rule("r", "t", when("struct", "width", "BIGGER_THAN", 3)))));
		assertThrows(IllegalStateException.class,
			() -> node(nodeDef("n", rule("r", "t", when("nowhere", null, "EXISTS", null)))));
	}

	/**
	 * A second run over the same media with the same configuration produces the same verdict; that it
	 * is served from the cache rather than re-derived is asserted in {@link TagNodePersistenceTest},
	 * where a mocked client can count the writes.
	 */
	@Test
	void testASecondRunProducesTheSameVerdict() {
		TagNode node = node(nodeDef("n", rule("r", "t", when("text", null, "NOT_BLANK", null))));
		NodeInputs inputs = NodeInputs.builder().input(TagNode.IN_TEXT, "something").build();

		assertEquals(List.of("t"), applied(run(node, inputs)));
		assertEquals(List.of("t"), applied(run(node, inputs)));
	}

	/**
	 * Two instances in one graph must not share a cached answer even for the same file - they are
	 * configured differently, which is the entire point of one kind with per-instance rules.
	 */
	@Test
	void testTwoInstancesDoNotShareACachedVerdict() {
		TagNode first = node(nodeDef("a", rule("r", "alpha", when("text", null, "NOT_BLANK", null))));
		TagNode second = node(nodeDef("b", rule("r", "beta", when("text", null, "NOT_BLANK", null))));
		NodeInputs inputs = NodeInputs.builder().input(TagNode.IN_TEXT, "x").build();

		assertEquals(List.of("alpha"), applied(run(first, inputs)));
		assertEquals(List.of("beta"), applied(run(second, inputs)));
	}
}
