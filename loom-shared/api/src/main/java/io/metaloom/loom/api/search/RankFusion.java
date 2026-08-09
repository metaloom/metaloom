package io.metaloom.loom.api.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: combine several rankings of the same items into one.
 *
 * <pre>
 * score(d) = Σ_r  w_r / (k + rank_r(d))
 * </pre>
 *
 * <p>
 * <b>Why not a weighted sum of the rankers' own scores.</b> {@code ts_rank_cd} is unnormalized and corpus-dependent while cosine similarity is bounded;
 * the two are not commensurable, and any blend weight that looks right on today's catalog drifts as the catalog grows. RRF reads only <i>positions</i>,
 * so it needs no calibration, cannot be skewed by one ranker's score distribution, and degrades gracefully when a ranker returns nothing at all - that
 * ranker simply contributes no terms instead of dragging every score toward zero.
 * </p>
 *
 * <p>
 * {@code k} damps the top of each list: with {@code k = 60} the gap between rank 1 and rank 2 is small enough that agreement between rankers outweighs
 * one ranker's confidence, which is the property that makes fusion better than either input.
 * </p>
 *
 * <p>
 * Ties are broken by first appearance across the rankings in the order they were supplied, so the same inputs always fuse to the same order. An
 * arbitrary tie-break would make paging inconsistent: a document could appear on both page one and page two.
 * </p>
 */
public final class RankFusion {

	private RankFusion() {
	}

	/**
	 * One ranker's output.
	 *
	 * @param ordered
	 *            the ranked items, best first. Duplicates are ignored after the first occurrence, which is what lets a caller pass raw hits - several
	 *            vectors belonging to one asset collapse to that asset's best rank rather than stacking its score.
	 * @param weight
	 *            this ranker's weight. Zero disables the ranker without the caller having to build the list conditionally.
	 */
	public record WeightedRanking<T>(List<T> ordered, double weight) {
	}

	/** One fused item and its score. Scores are comparable only within one fusion. */
	public record Fused<T>(T key, double score) {
	}

	/**
	 * Fuse the rankings.
	 *
	 * @param k
	 *            the fusion constant, conventionally 60. Must be positive: {@code k = 0} makes rank 1 contribute an unbounded score.
	 * @return every item appearing in any ranking, best first
	 */
	public static <T> List<Fused<T>> rrf(int k, List<WeightedRanking<T>> rankings) {
		if (k <= 0) {
			throw new IllegalArgumentException("The RRF constant k must be positive, got " + k);
		}
		if (rankings == null || rankings.isEmpty()) {
			return List.of();
		}
		Map<T, Double> scores = new HashMap<>();
		Map<T, Integer> firstSeen = new HashMap<>();
		int order = 0;

		for (WeightedRanking<T> ranking : rankings) {
			if (ranking == null || ranking.ordered() == null || ranking.weight() == 0) {
				continue;
			}
			java.util.Set<T> seenHere = new java.util.HashSet<>();
			int rank = 0;
			for (T item : ranking.ordered()) {
				// Positions are per ranker, and a duplicate must not consume a rank - otherwise every item
				// after a repeated one is pushed down by an item that contributed nothing.
				if (item == null || !seenHere.add(item)) {
					continue;
				}
				rank++;
				scores.merge(item, ranking.weight() / (k + rank), (a, b) -> a + b);
				firstSeen.putIfAbsent(item, order++);
			}
		}

		List<Fused<T>> fused = new ArrayList<>(scores.size());
		for (Map.Entry<T, Double> entry : scores.entrySet()) {
			fused.add(new Fused<>(entry.getKey(), entry.getValue()));
		}
		fused.sort(Comparator.<Fused<T>> comparingDouble(f -> -f.score())
			.thenComparingInt(f -> firstSeen.getOrDefault(f.key(), Integer.MAX_VALUE)));
		return fused;
	}
}
