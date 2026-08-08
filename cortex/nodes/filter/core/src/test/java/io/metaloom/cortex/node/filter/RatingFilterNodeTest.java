package io.metaloom.cortex.node.filter;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.filter.RatingFilterStrategy.Band;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Rating bucketing, and the hint grammar underneath it.
 *
 * <p>
 * The node is built with a {@code null} {@code LoomClient}, which is the offline contract: an asset
 * Loom has never heard of must land in {@code other} and the <b>task must still succeed</b>. A filter
 * that aborted here would take a fifty-thousand-file run down over one un-ingested file.
 * </p>
 *
 * <p>
 * What happens when Loom <em>is</em> reachable — the mean over several reviewers, and the difference
 * between "unrated" and "we could not find out" — lives in {@link RatingFilterNodePersistenceTest},
 * which has a client to mock.
 * </p>
 */
class RatingFilterNodeTest {

	@TempDir
	File tempDir;

	private FilterNode node(JsonObject nodeDef) {
		Provider<FilterStrategy> strategy = RatingFilterStrategy::new;
		FilterNode node = new FilterNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), new FilterNodeOptions(),
			Map.of(FilterBy.RATING, strategy));
		node.configure(nodeDef);
		return node;
	}

	private static JsonObject nodeDef() {
		return new JsonObject().put("id", "by-rating").put("filterBy", "RATING")
			.put("buckets", new JsonArray()
				.add(new JsonObject().put("id", "keep").put("label", "Keep").put("match", ">=8"))
				.add(new JsonObject().put("id", "trash").put("label", "Trash").put("match", "<=2")));
	}

	private NodeResult run(FilterNode node) throws Exception {
		StubLoomMedia media = StubLoomMedia.ofBytes(tempDir, "asset.txt", "some bytes");
		return node.process(NodeContext.create(media, NodeInputs.empty()));
	}

	/**
	 * The offline contract, and the most important case in this file: no client means no asset, and an
	 * unknown asset is a routing answer rather than a worker failure.
	 */
	@Test
	void testAnAssetLoomDoesNotKnowGoesToOtherAndTheTaskStillSucceeds() throws Exception {
		assertThat(run(node(nodeDef())))
			.isSuccess()
			.hasOutput(FilterNode.OUT_BUCKET, "other")
			.hasNoOutput(FilterNode.bucketPort("keep"))
			.hasNoOutput(FilterNode.bucketPort("trash"))
			.hasOutput(FilterNode.OUT_PASSED, Boolean.FALSE);
	}

	@Test
	void testTheProducerVersionNamesTheStrategy() {
		assertTrue(node(nodeDef()).producerVersion().startsWith("filter/1:RATING:"));
	}

	// ── The hint grammar ──────────────────────────────────────────────────

	@Test
	void testComparisonForms() {
		assertTrue(RatingFilterStrategy.parse(">=8").holds(8));
		assertFalse(RatingFilterStrategy.parse(">=8").holds(7));
		assertTrue(RatingFilterStrategy.parse(">7").holds(8));
		assertFalse(RatingFilterStrategy.parse(">7").holds(7));
		assertTrue(RatingFilterStrategy.parse("<=2").holds(2));
		assertFalse(RatingFilterStrategy.parse("<=2").holds(3));
		assertTrue(RatingFilterStrategy.parse("<3").holds(2));
		assertFalse(RatingFilterStrategy.parse("<3").holds(3));
	}

	/**
	 * Inclusive at <em>both</em> ends, unlike {@code SizeFilterStrategy}. A rating is one of ten
	 * integers: {@code 1..3} and {@code 4..7} already tile, and a half-open upper end would quietly
	 * drop every 7 from a bucket whose configuration says it holds them.
	 */
	@Test
	void testARangeIsInclusiveAtBothEnds() {
		Band band = RatingFilterStrategy.parse("4..7");
		assertTrue(band.holds(4));
		assertTrue(band.holds(7));
		assertFalse(band.holds(3));
		assertFalse(band.holds(8));
	}

	/**
	 * A bare number is <em>that</em> rating, where a bare size is a ceiling. The rule across the
	 * strategies: exact on a discrete domain, a ceiling on a continuous one — {@code DateFilterStrategy}
	 * already reads a bare date as that one day.
	 */
	@Test
	void testABareNumberIsAnExactRating() {
		Band band = RatingFilterStrategy.parse("8");
		assertTrue(band.holds(8));
		assertFalse(band.holds(7));
		assertFalse(band.holds(9));
	}

	@Test
	void testUnratedMatchesOnlyAnAssetNobodyRated() {
		Band band = RatingFilterStrategy.parse("unrated");
		assertTrue(band.holds(null));
		assertFalse(band.holds(1));

		// And no other hint may claim an unrated asset: '<=2' means "rated at most 2", not "at most 2
		// or never rated at all", which would sweep the whole un-reviewed backlog into the trash branch.
		assertFalse(RatingFilterStrategy.parse("<=2").holds(null));
		assertFalse(RatingFilterStrategy.parse("0..10").holds(null));
	}

	@Test
	void testUnparseableHints() {
		assertNull(RatingFilterStrategy.parse("<=two"));
		assertNull(RatingFilterStrategy.parse(""));
		assertNull(RatingFilterStrategy.parse("8.5"));
		// Out of the 0-10 scale: '80' was meant to be '8', and a filter that silently matches nothing
		// is exactly what validateBuckets exists to prevent.
		assertNull(RatingFilterStrategy.parse("80"));
		assertNull(RatingFilterStrategy.parse(">=11"));
	}

	@Test
	void testTheMeanIsRoundedHalfUp() {
		assertEquals(8, RatingFilterStrategy.mean(List.of(8)));
		assertEquals(6, RatingFilterStrategy.mean(List.of(9, 3)));
		assertEquals(8, RatingFilterStrategy.mean(List.of(9, 8, 7)));
		// 7.5 rounds up rather than to even, so a bucket boundary is where an operator would put it.
		assertEquals(8, RatingFilterStrategy.mean(List.of(8, 7)));
		assertNull(RatingFilterStrategy.mean(List.of()), "nobody rated it - that is 'unrated', not zero");
	}

	@Test
	void testAnUnparseableHintIsRejectedAtConfigureTime() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> node(nodeDef().put("buckets", new JsonArray().add(new JsonObject().put("id", "keep").put("match", "<=two")))));

		assertTrue(e.getMessage().contains("<=two"), e.getMessage());
		assertTrue(e.getMessage().contains("keep"), e.getMessage());
	}

	/**
	 * And a bucket with no hint. Unlike the MIME and TAG strategies there is no fall-back to the bucket
	 * id here: {@code keep} is not a rating condition.
	 */
	@Test
	void testABucketWithNoHintIsRejectedAtConfigureTime() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> node(nodeDef().put("buckets", new JsonArray().add(new JsonObject().put("id", "keep")))));

		assertTrue(e.getMessage().contains("needs a rating condition"), e.getMessage());
	}
}
