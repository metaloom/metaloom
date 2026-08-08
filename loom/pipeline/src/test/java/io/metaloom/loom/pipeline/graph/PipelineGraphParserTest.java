package io.metaloom.loom.pipeline.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

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
				.add(new JsonObject().put("id", "pn2").put("type", "filter"))
				.add(new JsonObject().put("id", "pn3").put("type", "sha256")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("sourcePort", "media").put("target", "pn2").put("targetPort", "media"))
				.add(new JsonObject().put("id", "pe2").put("source", "pn2").put("sourcePort", "passed").put("target", "pn3").put("targetPort", "media")));
	}

	// ── Definition format version ─────────────────────────────────────────

	@Test
	void testUnversionedDefinitionIsReadAsVersionOne() {
		JsonObject definition = loomFormatDefinition();
		assertEquals(1, PipelineGraphParser.readVersion("demo", definition),
			"Every definition stored before the version field existed is a version 1 definition;"
				+ " rejecting them would strand pipelines that run correctly");
		assertEquals(3, parser.parse("demo", definition, true, false, 100).size());
	}

	@Test
	void testCurrentVersionParses() {
		JsonObject definition = loomFormatDefinition()
			.put("version", PipelineGraphParser.CURRENT_DEFINITION_VERSION);
		assertEquals(PipelineGraphParser.CURRENT_DEFINITION_VERSION,
			PipelineGraphParser.readVersion("demo", definition));
		assertEquals(3, parser.parse("demo", definition, true, false, 100).size());
	}

	/**
	 * A definition from a newer Loom is refused by name rather than half-read: it may use
	 * fields whose absence here would parse into a valid but different graph.
	 */
	@Test
	void testNewerVersionIsRejectedByName() {
		JsonObject definition = loomFormatDefinition()
			.put("version", PipelineGraphParser.CURRENT_DEFINITION_VERSION + 1);
		GraphValidationException e = assertThrows(GraphValidationException.class,
			() -> parser.parse("demo", definition, true, false, 100));
		assertTrue(e.getMessage().contains(String.valueOf(PipelineGraphParser.CURRENT_DEFINITION_VERSION + 1)),
			"The message must name the version found: " + e.getMessage());
		assertTrue(e.getMessage().contains(String.valueOf(PipelineGraphParser.CURRENT_DEFINITION_VERSION)),
			"...and the version this Loom reads: " + e.getMessage());
	}

	@Test
	void testMalformedVersionIsRejected() {
		assertThrows(GraphValidationException.class,
			() -> parser.parse("demo", loomFormatDefinition().put("version", "one"), true, false, 100));
		assertThrows(GraphValidationException.class,
			() -> parser.parse("demo", loomFormatDefinition().put("version", 1.5), true, false, 100));
		assertThrows(GraphValidationException.class,
			() -> parser.parse("demo", loomFormatDefinition().put("version", 0), true, false, 100));
	}

	@Test
	void testStampVersionSetsCurrentButDoesNotRelabel() {
		JsonObject fresh = PipelineGraphParser.stampVersion(loomFormatDefinition());
		assertEquals(PipelineGraphParser.CURRENT_DEFINITION_VERSION, fresh.getInteger("version"));

		// Re-stamping would silently relabel a definition an older Loom round-tripped.
		JsonObject alreadyTagged = loomFormatDefinition().put("version", 1);
		assertEquals(1, PipelineGraphParser.stampVersion(alreadyTagged).getInteger("version"));

		assertNull(PipelineGraphParser.stampVersion(null));
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
				.add(new JsonObject().put("id", "flt").put("type", "filter"))
				.add(new JsonObject().put("id", "keep").put("type", "sha256"))
				.add(new JsonObject().put("id", "drop").put("type", "md5")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "flt").put("targetPort", "media"))
				.add(new JsonObject().put("source", "flt").put("sourcePort", "passed").put("target", "keep").put("targetPort", "media").put("branch", "PASS"))
				.add(new JsonObject().put("source", "flt").put("sourcePort", "passed").put("target", "drop").put("targetPort", "media").put("branch", "REJECT")));

		PipelineGraph graph = parser.parse("branching", definition, true, false, 0);

		assertEquals(FilterBranch.PASS, graph.getNode("keep").branchFor("flt"));
		assertEquals(FilterBranch.REJECT, graph.getNode("drop").branchFor("flt"));
		assertEquals(FilterBranch.ANY, graph.getNode("flt").branchFor("src"),
			"An edge without a branch must default to ANY");
	}

	@Test
	void testTheLegacyInlineDependencyShapeIsRejected() {
		// nodes[].dependencies[] cannot say which ports an edge joins, so a definition in that shape
		// can never be type-checked. Accepting it would mean a graph that saves unvalidated and
		// fails at run time; the error has to name the real problem instead.
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")
					.put("dependencies", new JsonArray().add("src"))));

		GraphValidationException e = assertThrows(GraphValidationException.class,
			() -> parser.parse("legacy", definition, true, false, 0));
		assertTrue(e.getMessage().contains("port-to-port"),
			"The error should explain that edges now carry ports, not merely that something is missing: " + e.getMessage());
	}

	@Test
	void testAStaleInlineDependencyIsRejectedEvenAlongsideEdges() {
		// Silently preferring edges[] and ignoring the leftover key is exactly the failure mode this
		// parser exists to prevent: two shapes describing the graph, one of them quietly unread.
		// A definition carrying both was written against the old model, so say so.
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "a").put("type", "sha256"))
				.add(new JsonObject().put("id", "b").put("type", "md5")
					.put("dependencies", new JsonArray().add("src"))))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "a").put("targetPort", "media"))
				.add(new JsonObject().put("source", "a").put("sourcePort", "hash").put("target", "b").put("targetPort", "media")));

		GraphValidationException e = assertThrows(GraphValidationException.class,
			() -> parser.parse("mixed", definition, true, false, 0));
		assertTrue(e.getMessage().contains("no longer read"),
			"The error should name the stale dependencies[] key: " + e.getMessage());
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
				.add(new JsonObject().put("source", "a").put("sourcePort", "hash").put("target", "b").put("targetPort", "media"))
				.add(new JsonObject().put("source", "b").put("sourcePort", "hash").put("target", "a").put("targetPort", "media")));

		GraphValidationException e = assertThrows(GraphValidationException.class,
			() -> parser.parse("cyclic", definition, true, false, 0));
		assertTrue(e.getMessage().contains("cycle"), "Message should name the problem: " + e.getMessage());
	}

	@Test
	void testAnEdgeWithoutPortsIsRejected() {
		// There is no positional fallback: an edge that does not name its ports cannot be
		// resolved to an input, and guessing is how a graph silently feeds a node nothing.
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha256")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("target", "hash")));

		GraphValidationException e = assertThrows(GraphValidationException.class,
			() -> parser.parse("portless", definition, true, false, 0));
		assertTrue(e.getMessage().contains("sourcePort"), e.getMessage());
	}

	@Test
	void testTwoEdgesBetweenTheSameNodesOnDifferentPortsBothSurvive() {
		// The dedupe key used to be the node pair alone, which made these two
		// indistinguishable and silently dropped one of them.
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "face").put("type", "facedetect"))
				.add(new JsonObject().put("id", "layout").put("type", "scene-layout")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media")
					.put("target", "face").put("targetPort", "image"))
				.add(new JsonObject().put("source", "face").put("sourcePort", "detections")
					.put("target", "layout").put("targetPort", "detections"))
				.add(new JsonObject().put("source", "face").put("sourcePort", "face_count")
					.put("target", "layout").put("targetPort", "depth")));

		PipelineGraph graph = parser.parse("ports", definition, true, false, 0);

		assertEquals(2, graph.getNode("layout").getInputBindings().size());
		assertEquals(List.of("face"), graph.getNode("layout").getDependencies(),
			"Two ports fed by one node are still a single scheduling dependency");
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
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "ghost").put("targetPort", "media")));

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

	/**
	 * A definition built in code must parse to the same options as the identical definition parsed
	 * from a string.
	 *
	 * <p>
	 * It used not to. {@code JsonObject.getMap()} hands back the backing map as stored: a definition
	 * decoded from a string holds plain {@code Map}s and {@code List}s, while one assembled with
	 * {@code new JsonObject().put(...)} keeps nested {@code JsonObject}s and {@code JsonArray}s — and
	 * a Vert.x {@code JsonArray} is not a {@code java.util.List}. {@code FilterPortResolver} lives in
	 * {@code node-model}, which has no Vert.x on its classpath and can only read the plain shape, so
	 * a programmatically built filter node resolved <em>no bucket ports</em> and any edge drawn to
	 * one was refused at boot. That is why {@code DemoDatabaseInitializer} could only ever seed
	 * bucket-less filters.
	 * </p>
	 */
	@Test
	void testOptionsFromABuiltDefinitionMatchTheSameDefinitionParsedFromAString() {
		JsonObject built = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "f").put("type", "filter")
					.put("options", new JsonObject()
						.put("filterBy", "RATING")
						.put("buckets", new JsonArray()
							.add(new JsonObject().put("id", "keep").put("match", ">=8"))
							.add(new JsonObject().put("id", "trash").put("match", "<=2"))))))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "f").put("targetPort", "media")));

		Map<String, Object> fromBuilt = parser.parse("built", built, true, false, 0).getNode("f").getOptions();
		Map<String, Object> fromString = parser.parse("encoded", new JsonObject(built.encode()), true, false, 0)
			.getNode("f").getOptions();

		assertEquals(fromString, fromBuilt, "How a definition was assembled must not change what it means");

		// The concrete property every NodePortResolver depends on: buckets is a plain List of plain
		// Maps, whichever way the definition arrived.
		Object buckets = fromBuilt.get("buckets");
		assertTrue(buckets instanceof List, "buckets must be a java.util.List, not a Vert.x JsonArray: " + buckets.getClass());
		assertTrue(((List<?>) buckets).get(0) instanceof Map, "and each bucket a java.util.Map");
		assertEquals(2, ((List<?>) buckets).size());
	}

	@Test
	void testDiamondGraphOrdersDependenciesFirst() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "left").put("type", "depthmap"))
				.add(new JsonObject().put("id", "right").put("type", "facedetect"))
				.add(new JsonObject().put("id", "join").put("type", "scene-layout")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "left").put("targetPort", "media"))
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "right").put("targetPort", "image"))
				.add(new JsonObject().put("source", "left").put("sourcePort", "meta").put("target", "join").put("targetPort", "depth"))
				.add(new JsonObject().put("source", "right").put("sourcePort", "detections").put("target", "join").put("targetPort", "detections")));

		List<String> order = parser.parse("diamond", definition, true, false, 0).getTopologicalOrder();

		assertEquals(4, order.size());
		assertEquals("src", order.get(0));
		assertEquals("join", order.get(3), "A join node must come after both of its dependencies");
		assertTrue(order.indexOf("left") < order.indexOf("join"));
		assertTrue(order.indexOf("right") < order.indexOf("join"));
	}
}
