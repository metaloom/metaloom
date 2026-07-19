package io.metaloom.loom.pipeline.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Telling an author their grouping will not run the way they wrote it.
 *
 * <p>Both problems are otherwise invisible: a split group looks like a working
 * pipeline that is merely slow, and an unplaceable one looks like a run that hangs
 * for no reason.</p>
 */
public class AffinityValidatorTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();
	private final AffinityValidator validator = new AffinityValidator();

	private JsonObject node(String id, String kind, String affinity) {
		JsonObject node = new JsonObject().put("id", id).put("type", kind);
		if (affinity != null) {
			node.put("affinity", affinity);
		}
		return node;
	}

	private JsonObject edge(String from, String to) {
		return new JsonObject().put("source", from).put("target", to);
	}

	private PipelineGraph graph(JsonArray nodes, JsonArray edges) {
		nodes.add(0, new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true));
		return parser.parse("aff", new JsonObject().put("nodes", nodes).put("edges", edges), true, false, 0);
	}

	/** A fleet where one worker runs exactly the given kinds. */
	private static java.util.function.Predicate<List<String>> fleetRunning(String... kinds) {
		Set<String> allowed = Set.of(kinds);
		return required -> allowed.containsAll(required);
	}

	@Test
	void testAWellFormedPipelineWarnsAboutNothing() {
		PipelineGraph g = graph(
			new JsonArray().add(node("a", "sha512", "cheap")).add(node("b", "md5", "cheap")),
			new JsonArray().add(edge("src", "a")).add(edge("a", "b")));

		assertTrue(validator.validate(g, fleetRunning("sha512", "md5")).isEmpty());
	}

	@Test
	void testASplitGroupIsReported() {
		// a → b → c with a and c grouped: the merge would deadlock, so it is broken up
		// and the author's intent is silently not honoured.
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("a", "sha512", "x"))
				.add(node("b", "thumbnail", "y"))
				.add(node("c", "md5", "x")),
			new JsonArray().add(edge("src", "a")).add(edge("a", "b")).add(edge("b", "c")));

		List<AffinityWarning> warnings = validator.validate(g, kinds -> true);

		assertEquals(1, warnings.size());
		AffinityWarning warning = warnings.get(0);
		assertEquals(AffinityWarning.Kind.GROUP_SPLIT, warning.getKind());
		assertEquals("x", warning.getAffinity());
		// The message has to name the group and say what actually happens, or it is
		// just noise the author cannot act on.
		assertTrue(warning.getMessage().contains("'x'"));
		assertTrue(warning.getMessage().contains("2 separate segments"),
			"Expected the real segment count, got: " + warning.getMessage());
	}

	@Test
	void testDisconnectedNodesSharingALabelAreAlsoReported() {
		PipelineGraph g = graph(
			new JsonArray().add(node("a", "sha512", "video")).add(node("b", "md5", "video")),
			new JsonArray().add(edge("src", "a")).add(edge("src", "b")));

		List<AffinityWarning> warnings = validator.validate(g, kinds -> true);

		// Same label, no path between them, so no intermediate result can stay on the
		// worker. Worth saying: the author thinks these are co-located and they are not.
		assertEquals(1, warnings.size());
		assertEquals(AffinityWarning.Kind.GROUP_SPLIT, warnings.get(0).getKind());
	}

	@Test
	void testASegmentNoWorkerCanRunIsReported() {
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("decode", "video-decode", "video"))
				.add(node("face", "facedetect", "video")),
			new JsonArray().add(edge("src", "decode")).add(edge("decode", "face")));

		// A fleet that can decode but cannot detect faces. No single worker covers the
		// group, so the segment would park forever with no visible cause.
		List<AffinityWarning> warnings = validator.validate(g, fleetRunning("video-decode"));

		assertEquals(1, warnings.size());
		AffinityWarning warning = warnings.get(0);
		assertEquals(AffinityWarning.Kind.UNPLACEABLE, warning.getKind());
		assertTrue(warning.getMessage().contains("facedetect"),
			"The message must name the kinds involved, got: " + warning.getMessage());
	}

	@Test
	void testAGroupIsUnplaceableEvenWhenEachKindIsIndividuallyAvailable() {
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("decode", "video-decode", "video"))
				.add(node("face", "facedetect", "video")),
			new JsonArray().add(edge("src", "decode")).add(edge("decode", "face")));

		// Two specialised workers: one decodes, one detects. Both kinds are covered by
		// the fleet, but no *single* worker covers both - which is what a segment
		// needs. This is the case a naive per-kind check would miss entirely.
		java.util.function.Predicate<List<String>> splitFleet = required -> Set.of("video-decode").containsAll(required)
			|| Set.of("facedetect").containsAll(required);

		List<AffinityWarning> warnings = validator.validate(g, splitFleet);

		assertEquals(1, warnings.size());
		assertEquals(AffinityWarning.Kind.UNPLACEABLE, warnings.get(0).getKind());
	}

	@Test
	void testSplittingTheGroupMakesItPlaceableAgain() {
		// The same two kinds, deliberately left in separate groups. Each segment is a
		// single kind, so the specialised fleet can run both.
		PipelineGraph g = graph(
			new JsonArray()
				.add(node("decode", "video-decode", "a"))
				.add(node("face", "facedetect", "b")),
			new JsonArray().add(edge("src", "decode")).add(edge("decode", "face")));

		java.util.function.Predicate<List<String>> splitFleet = required -> Set.of("video-decode").containsAll(required)
			|| Set.of("facedetect").containsAll(required);

		assertTrue(validator.validate(g, splitFleet).isEmpty(),
			"Splitting the group is the fix, and must not itself warn");
	}

	@Test
	void testTheFleetCheckCanBeSkipped() {
		PipelineGraph g = graph(
			new JsonArray().add(node("a", "whisper", "x")),
			new JsonArray().add(edge("src", "a")));

		// Structural-only validation, for when no fleet is connected - a save must not
		// report every node as unplaceable just because Cortex happens to be down.
		assertTrue(validator.validate(g, kinds -> true).isEmpty());
	}

	@Test
	void testASingleNodeGroupIsNeverReportedAsSplit() {
		PipelineGraph g = graph(
			new JsonArray().add(node("a", "sha512", "solo")).add(node("b", "md5", "other")),
			new JsonArray().add(edge("src", "a")).add(edge("a", "b")));

		// Each group has one node, so nothing was split - warning here would fire on
		// every ordinary pipeline.
		assertTrue(validator.validate(g, kinds -> true).isEmpty());
	}

}
