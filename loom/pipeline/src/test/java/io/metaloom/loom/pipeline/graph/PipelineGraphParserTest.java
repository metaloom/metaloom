package io.metaloom.loom.pipeline.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.model.FilterBranch;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for the definition parser.
 *
 * <p>The first test here is the regression guard for the defect that broke the
 * pipeline feature end to end: Loom stored the graph as {@code nodes[]} plus
 * {@code edges[]}, the Cortex loader read {@code nodes[].dependencies[]} and ignored
 * {@code edges}, and every UI-authored pipeline silently collapsed to its source
 * node. There was no loader test, which is why it survived. There is now.</p>
 */
public class PipelineGraphParserTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	/**
	 * The exact shape the UI writes and {@code DemoDatabaseInitializer} seeds.
	 */
	private static JsonObject loomFormatDefinition() {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "pn2").put("type", "filter-mimetype"))
				.add(new JsonObject().put("id", "pn3").put("type", "sha256")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("target", "pn2"))
				.add(new JsonObject().put("id", "pe2").put("source", "pn2").put("target", "pn3")));
	}

	@Test
	void testEdgesArrayBuildsTheWholeGraph() {
		PipelineGraph graph = parser.parse("demo", loomFormatDefinition(), true, false, 100);

		assertEquals(3, graph.size(),
			"All three nodes must survive parsing. A size of 1 means edges[] was ignored and "
				+ "the graph collapsed to its source - the original defect.");
		assertEquals("pn1", graph.getSourceNodeId());
		assertEquals(List.of("pn1", "pn2", "pn3"), graph.getTopologicalOrder());
		assertEquals(List.of("pn1"), graph.getNode("pn2").getDependencies());
		assertEquals(List.of("pn2"), graph.getNode("pn3").getDependencies());
	}

	@Test
	void testEdgeBranchesBecomeConditionalDependencies() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "flt").put("type", "filter-mimetype"))
				.add(new JsonObject().put("id", "keep").put("type", "sha256"))
				.add(new JsonObject().put("id", "drop").put("type", "md5")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "flt"))
				.add(new JsonObject().put("source", "flt").put("target", "keep").put("branch", "PASS"))
				.add(new JsonObject().put("source", "flt").put("target", "drop").put("branch", "REJECT")));

		PipelineGraph graph = parser.parse("branching", definition, true, false, 0);

		assertEquals(FilterBranch.PASS, graph.getNode("keep").branchFor("flt"));
		assertEquals(FilterBranch.REJECT, graph.getNode("drop").branchFor("flt"));
		assertEquals(FilterBranch.ANY, graph.getNode("flt").branchFor("src"),
			"An edge without a branch must default to ANY");
	}

	@Test
	void testInlineDependenciesStillParseAsFallback() {
		// The older Cortex serde shape must keep loading.
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")
					.put("dependencies", new JsonArray().add("src"))));

		PipelineGraph graph = parser.parse("legacy", definition, true, false, 0);

		assertEquals(2, graph.size());
		assertEquals(List.of("src"), graph.getNode("hash").getDependencies());
	}

	@Test
	void testEdgesWinOverInlineDependencies() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "a").put("type", "sha256"))
				.add(new JsonObject().put("id", "b").put("type", "md5")
					.put("dependencies", new JsonArray().add("src"))))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "a"))
				.add(new JsonObject().put("source", "a").put("target", "b")));

		PipelineGraph graph = parser.parse("mixed", definition, true, false, 0);

		assertEquals(List.of("a"), graph.getNode("b").getDependencies(),
			"When edges[] is present it is authoritative; the stale inline dependency must not apply");
	}

	@Test
	void testNodeOptionsAreCarried() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true)
					.put("options", new JsonObject().put("path", "/media").put("depth", 3))));

		PipelineGraph graph = parser.parse("opts", definition, true, false, 0);

		assertEquals("/media", graph.getNode("src").getOptions().get("path"));
		assertEquals(3, graph.getNode("src").getOptions().get("depth"));
	}

	@Test
	void testBlockingDefaultsToTrueAndSyncToLoomToFalse() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true)));

		PipelineGraphNode node = parser.parse("defaults", definition, true, false, 0).getNode("src");

		assertTrue(node.isBlocking(), "Failing open would hide upstream errors");
		assertFalse(node.isSyncToLoom(), "Results must be opted into explicitly");
	}

	@Test
	void testCycleIsRejected() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "a").put("type", "sha256").put("source", true))
				.add(new JsonObject().put("id", "b").put("type", "md5")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "a").put("target", "b"))
				.add(new JsonObject().put("source", "b").put("target", "a")));

		GraphValidationException e = assertThrows(GraphValidationException.class,
			() -> parser.parse("cyclic", definition, true, false, 0));
		assertTrue(e.getMessage().contains("cycle"), "Message should name the problem: " + e.getMessage());
	}

	@Test
	void testAmbiguousSourceIsRejectedRatherThanGuessed() {
		// Two dependency-free nodes and no declared source. The old loader picked the
		// first one, which is how a broken graph became a plausible one-node run.
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "a").put("type", "sha256"))
				.add(new JsonObject().put("id", "b").put("type", "md5")))
			.put("edges", new JsonArray());

		GraphValidationException e = assertThrows(GraphValidationException.class,
			() -> parser.parse("ambiguous", definition, true, false, 0));
		assertTrue(e.getMessage().contains("source"), e.getMessage());
	}

	@Test
	void testTwoDeclaredSourcesAreRejected() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "a").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "b").put("type", "filesystem-source").put("source", true)));

		assertThrows(GraphValidationException.class, () -> parser.parse("two-sources", definition, true, false, 0));
	}

	@Test
	void testEdgeReferencingUnknownNodeIsRejected() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true)))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "ghost")));

		GraphValidationException e = assertThrows(GraphValidationException.class,
			() -> parser.parse("dangling", definition, true, false, 0));
		assertTrue(e.getMessage().contains("ghost"), e.getMessage());
	}

	@Test
	void testDuplicateNodeIdIsRejected() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "dup").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "dup").put("type", "sha256")));

		assertThrows(GraphValidationException.class, () -> parser.parse("dup", definition, true, false, 0));
	}

	@Test
	void testEmptyDefinitionIsRejected() {
		assertThrows(GraphValidationException.class,
			() -> parser.parse("empty", new JsonObject().put("nodes", new JsonArray()), true, false, 0));
		assertThrows(GraphValidationException.class, () -> parser.parse("null", null, true, false, 0));
	}

	@Test
	void testDiamondGraphOrdersDependenciesFirst() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "left").put("type", "sha256"))
				.add(new JsonObject().put("id", "right").put("type", "md5"))
				.add(new JsonObject().put("id", "join").put("type", "thumbnail")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "left"))
				.add(new JsonObject().put("source", "src").put("target", "right"))
				.add(new JsonObject().put("source", "left").put("target", "join"))
				.add(new JsonObject().put("source", "right").put("target", "join")));

		List<String> order = parser.parse("diamond", definition, true, false, 0).getTopologicalOrder();

		assertEquals(4, order.size());
		assertEquals("src", order.get(0));
		assertEquals("join", order.get(3), "A join node must come after both of its dependencies");
		assertTrue(order.indexOf("left") < order.indexOf("join"));
		assertTrue(order.indexOf("right") < order.indexOf("join"));
	}
}
