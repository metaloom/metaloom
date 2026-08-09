package io.metaloom.loom.api.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.search.RankFusion.Fused;
import io.metaloom.loom.api.search.RankFusion.WeightedRanking;

/**
 * The fusion arithmetic behind {@code SearchMode.HYBRID}.
 *
 * <p>
 * Worth testing on its own because every property that makes RRF the right choice is a property of this function rather than of the search provider:
 * that agreement beats one ranker's confidence, that an empty ranker costs nothing, and that the order is reproducible.
 * </p>
 */
public class RankFusionTest {

	private List<String> keys(List<Fused<String>> fused) {
		return fused.stream().map(Fused::key).toList();
	}

	private List<Fused<String>> fuse(List<String> lexical, List<String> vector) {
		return RankFusion.rrf(60, List.of(new WeightedRanking<>(lexical, 1.0), new WeightedRanking<>(vector, 1.0)));
	}

	@Test
	public void testASingleRankingIsReturnedInItsOwnOrder() {
		List<Fused<String>> fused = fuse(List.of("a", "b", "c"), List.of());
		assertEquals(List.of("a", "b", "c"), keys(fused));
	}

	@Test
	public void testAgreementBeatsBeingFirstInOneRanking() {
		// "b" is second in both; "a" is first in one and absent from the other. Two mid-ranks outweigh one
		// top rank, which is the entire reason fusion is better than either input.
		List<Fused<String>> fused = fuse(List.of("a", "b"), List.of("c", "b"));
		assertEquals("b", keys(fused).get(0));
	}

	@Test
	public void testScoresAreTheReciprocalRankSum() {
		List<Fused<String>> fused = fuse(List.of("a"), List.of("a"));
		assertEquals(2.0 / 61, fused.get(0).score(), 1e-9);
	}

	@Test
	public void testAnEmptyRankerContributesNothingRatherThanPenalising() {
		// Degrading gracefully when one ranker finds nothing is why RRF survives the vector index being
		// empty: the lexical order must come through untouched.
		assertEquals(keys(fuse(List.of("a", "b"), List.of())), keys(fuse(List.of("a", "b"), List.of())));
		assertEquals(List.of("a", "b"), keys(fuse(List.of("a", "b"), List.of())));
	}

	@Test
	public void testAZeroWeightDisablesARankerEntirely() {
		List<Fused<String>> fused = RankFusion.rrf(60, List.of(
			new WeightedRanking<>(List.of("lex"), 0.0),
			new WeightedRanking<>(List.of("vec"), 1.0)));
		assertEquals(List.of("vec"), keys(fused));
	}

	@Test
	public void testWeightsDecideWhichRankerBreaksADisagreement() {
		// Both rankers return the same two documents in opposite orders, so the weights are the only thing
		// that can decide. Whichever ranker is weighted higher gets its preference.
		List<String> lexical = List.of("a", "b");
		List<String> vector = List.of("b", "a");

		List<Fused<String>> lexicalHeavy = RankFusion.rrf(60, List.of(
			new WeightedRanking<>(lexical, 4.0), new WeightedRanking<>(vector, 1.0)));
		assertEquals("a", keys(lexicalHeavy).get(0));

		List<Fused<String>> vectorHeavy = RankFusion.rrf(60, List.of(
			new WeightedRanking<>(lexical, 1.0), new WeightedRanking<>(vector, 4.0)));
		assertEquals("b", keys(vectorHeavy).get(0));
	}

	@Test
	public void testADuplicateDoesNotConsumeARank() {
		// A repeated item must not push everything after it down: several vectors can belong to one asset,
		// and the asset's later neighbours would otherwise be penalised for its own duplicates.
		List<Fused<String>> withDuplicate = fuse(List.of("a", "a", "b"), List.of());
		List<Fused<String>> without = fuse(List.of("a", "b"), List.of());
		assertEquals(keys(without), keys(withDuplicate));
		assertEquals(without.get(1).score(), withDuplicate.get(1).score(), 1e-9);
	}

	@Test
	public void testADuplicateIsScoredOnceAtItsBestRank() {
		List<Fused<String>> fused = fuse(List.of("a", "a"), List.of());
		assertEquals(1, fused.size());
		assertEquals(1.0 / 61, fused.get(0).score(), 1e-9);
	}

	@Test
	public void testTiesBreakOnFirstAppearanceSoPagingIsStable() {
		// Two items with identical scores must always come back in the same order; an arbitrary tie-break
		// would let one document appear on both page one and page two.
		List<Fused<String>> first = fuse(List.of("a"), List.of("b"));
		List<Fused<String>> second = fuse(List.of("a"), List.of("b"));
		assertEquals(first.get(0).score(), first.get(1).score(), 1e-9);
		assertEquals(List.of("a", "b"), keys(first));
		assertEquals(keys(first), keys(second));
	}

	@Test
	public void testKDampsTheAdvantageOfTheTopRank() {
		double small = RankFusion.rrf(1, List.of(new WeightedRanking<>(List.of("a", "b"), 1.0))).get(0).score();
		double large = RankFusion.rrf(1000, List.of(new WeightedRanking<>(List.of("a", "b"), 1.0))).get(0).score();
		assertTrue(large < small, "A larger k must flatten the score of rank 1");
	}

	@Test
	public void testNullsAreSkippedRatherThanRanked() {
		List<String> withNull = new java.util.ArrayList<>();
		withNull.add("a");
		withNull.add(null);
		withNull.add("b");
		assertEquals(List.of("a", "b"), keys(RankFusion.rrf(60, List.of(new WeightedRanking<>(withNull, 1.0)))));
	}

	@Test
	public void testNoRankingsFuseToNothing() {
		assertTrue(RankFusion.rrf(60, List.of()).isEmpty());
		assertTrue(RankFusion.rrf(60, null).isEmpty());
	}

	@Test
	public void testANonPositiveKIsRejected() {
		// k = 0 makes rank 1 divide by zero, which would surface as an infinite score rather than an error.
		assertThrows(IllegalArgumentException.class, () -> RankFusion.rrf(0, List.of(new WeightedRanking<>(List.of("a"), 1.0))));
	}
}
