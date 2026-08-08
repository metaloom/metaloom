package io.metaloom.cortex.node.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.vertx.core.json.JsonObject;

/**
 * Routes an item by the star rating people gave it in the review screen.
 *
 * <p>
 * <strong>No model.</strong> One Loom call per item, because a rating is stored as an asset reaction
 * of type {@link ReactionType#RATING} carrying an integer, not as a field on the asset.
 * {@link FilterNode} makes that call and hands the answer down; this class never sees a client.
 * </p>
 *
 * <p>
 * A bucket's {@code match} is a comma-separated list of conditions, and the bucket wins if
 * <em>any</em> of them holds:
 * </p>
 * <ul>
 * <li>{@code >=8}, {@code >7}, {@code <=2}, {@code <3} — a comparison</li>
 * <li>{@code 4..7} — a range, <strong>inclusive at both ends</strong></li>
 * <li>{@code 8} — a bare number is <strong>exactly</strong> that rating</li>
 * <li>{@code unrated} — nobody has rated the asset</li>
 * </ul>
 *
 * <p>
 * Both of those last rules differ from {@link SizeFilterStrategy}, deliberately, and the reason is
 * the same in each case: <strong>a bare value is exact on a discrete domain and a ceiling on a
 * continuous one.</strong> Sizes are continuous and nobody writes an exact byte count, so
 * {@code small: 1MB} reads naturally as "up to 1MB" and ranges tile better half-open. Ratings are
 * ten integers: {@code hero: 10} means ten, {@code 1..3} and {@code 4..7} already tile, and an
 * exclusive upper end would quietly drop every 7 from a bucket that says it holds them.
 * {@link DateFilterStrategy} already reads a bare {@code 2024-03-17} as that one day, so the rule is
 * consistent across all three.
 * </p>
 *
 * <h2>Which rating, when several people rated</h2>
 *
 * <p>
 * The <strong>mean</strong>, rounded half-up to an integer for bucketing. The raw mean and the
 * number of ratings are both written to the persisted component, so a routing decision can be
 * explained after the fact. Note the consequence: a mean moves as reviewers are added, so an asset
 * can change branch without anybody changing their mind. Within one run the node's result cache
 * holds the verdict steady; across runs it does not.
 * </p>
 *
 * <h2>Unknown, unrated, and unavailable are three different things</h2>
 *
 * <ul>
 * <li>the asset is not known to Loom (offline, never ingested) — {@code other}</li>
 * <li>the asset is known and nobody rated it — <em>unrated</em>, which an {@code unrated} bucket
 * catches</li>
 * <li>the reaction fetch failed — {@code other}, and pointedly <strong>not</strong> unrated:
 * collapsing the two would route unrated-branch work, which is typically trash, over a Loom
 * outage</li>
 * </ul>
 */
public class RatingFilterStrategy implements FilterStrategy {

	private static final Logger log = LoggerFactory.getLogger(RatingFilterStrategy.class);

	/** The hint that matches an asset nobody has rated. */
	static final String UNRATED = "unrated";

	/** The rating scale the review screen offers. Outside it, a hint is a typo rather than a filter. */
	private static final int MIN_RATING = 0;

	private static final int MAX_RATING = 10;

	private static final String GRAMMAR = "'>=8', '<=2', '4..7', a bare '8' or 'unrated'";

	@Inject
	public RatingFilterStrategy() {
	}

	@Override
	public FilterBy filterBy() {
		return FilterBy.RATING;
	}

	@Override
	public boolean needsReactions() {
		return true;
	}

	@Override
	public List<String> validateBuckets(List<FilterBucket> buckets) {
		List<String> errors = new ArrayList<>();
		for (FilterBucket bucket : buckets) {
			// No fall-back to the bucket id here, unlike MIME and TAG: a bucket called 'hero' is a
			// perfectly good tag hint but is not a rating condition, and silently treating it as one
			// would give a node that routes nothing and looks like data that did not match.
			if (bucket.match() == null) {
				errors.add("bucket '" + bucket.id() + "' needs a rating condition, for example " + GRAMMAR);
				continue;
			}
			for (String hint : hints(bucket)) {
				if (parse(hint) == null) {
					errors.add("bucket '" + bucket.id() + "': '" + hint + "' is not a rating condition; expected " + GRAMMAR);
				}
			}
		}
		return errors;
	}

	@Override
	public Classification classify(FilterItem item, FilterNodeOptions options, List<FilterBucket> buckets) {
		if (item.asset() == null) {
			return Classification.other("asset is not known to Loom");
		}
		if (!item.reactionsAvailable()) {
			// Not 'unrated'. We do not know whether it is rated, and guessing would send work down a
			// branch the reviewer never chose for it.
			return Classification.other("reactions unavailable");
		}

		List<Integer> ratings = ratings(item.reactions());
		Integer rating = mean(ratings);

		JsonObject detail = new JsonObject()
			.put("ratingCount", ratings.size())
			.put("ratingSource", "mean");
		if (rating != null) {
			detail.put("rating", rating).put("ratingMean", rawMean(ratings));
		}

		for (FilterBucket bucket : buckets) {
			for (String hint : hints(bucket)) {
				Band band = parse(hint);
				// null only when configure() was bypassed; treating it as "no match" beats an NPE
				// mid-run, and validateBuckets already reports it on the normal path.
				if (band != null && band.holds(rating)) {
					return Classification.of(bucket.id(), 1, detail.put("matched", hint));
				}
			}
		}

		log.debug("Rating filter found no bucket for {} (rating {})", item.media().absolutePath(), rating);
		return Classification.of(Classification.OTHER, 1, detail);
	}

	/** The rating values on the asset, ignoring emoji reactions and any row that carries no number. */
	private static List<Integer> ratings(List<ReactionResponse> reactions) {
		return reactions.stream()
			.filter(r -> r.getType() == ReactionType.RATING && r.getRating() != null)
			.map(ReactionResponse::getRating)
			.toList();
	}

	/** The mean rating rounded half-up, or {@code null} when nobody rated the asset. */
	static Integer mean(List<Integer> ratings) {
		if (ratings.isEmpty()) {
			return null;
		}
		return (int) Math.round(rawMean(ratings));
	}

	static double rawMean(List<Integer> ratings) {
		return ratings.stream().mapToInt(Integer::intValue).average().orElse(0d);
	}

	private static List<String> hints(FilterBucket bucket) {
		if (bucket.match() == null) {
			return List.of();
		}
		return Arrays.stream(bucket.match().split(","))
			.map(hint -> hint.trim().toLowerCase(Locale.ROOT))
			.filter(hint -> !hint.isEmpty())
			.toList();
	}

	/**
	 * A parsed hint. Both bounds are <strong>inclusive</strong> — unlike
	 * {@code SizeFilterStrategy.Threshold}, deliberately; see the class javadoc. {@code null} means
	 * unbounded.
	 */
	record Band(Integer min, Integer max, boolean unrated) {

		boolean holds(Integer rating) {
			if (rating == null) {
				return unrated;
			}
			if (unrated) {
				return false;
			}
			return (min == null || rating >= min) && (max == null || rating <= max);
		}
	}

	/**
	 * @param hint
	 *            a hint, already trimmed and lowercased
	 * @return the band, or {@code null} when the hint is not a rating condition
	 */
	static Band parse(String hint) {
		if (UNRATED.equals(hint)) {
			return new Band(null, null, true);
		}
		int range = hint.indexOf("..");
		if (range >= 0) {
			Integer from = rating(hint.substring(0, range).trim());
			Integer to = rating(hint.substring(range + 2).trim());
			return from == null || to == null ? null : new Band(from, to, false);
		}
		if (hint.startsWith(">=")) {
			Integer value = rating(hint.substring(2).trim());
			return value == null ? null : new Band(value, null, false);
		}
		if (hint.startsWith("<=")) {
			Integer value = rating(hint.substring(2).trim());
			return value == null ? null : new Band(null, value, false);
		}
		if (hint.startsWith(">")) {
			Integer value = rating(hint.substring(1).trim());
			return value == null ? null : new Band(value + 1, null, false);
		}
		if (hint.startsWith("<")) {
			Integer value = rating(hint.substring(1).trim());
			return value == null ? null : new Band(null, value - 1, false);
		}
		// A bare value is that exact rating - see the class javadoc for why this differs from size.
		Integer value = rating(hint);
		return value == null ? null : new Band(value, value, false);
	}

	/**
	 * @param value
	 *            a bare integer
	 * @return the rating, or {@code null} when it is not one or falls outside the 0-10 scale
	 */
	static Integer rating(String value) {
		if (value.isEmpty()) {
			return null;
		}
		try {
			int parsed = Integer.parseInt(value);
			// Out of range is a typo, not a filter that matches nothing: '80' was meant to be '8', and
			// reporting it from configure() is far kinder than a run in which everything lands in 'other'.
			return parsed < MIN_RATING || parsed > MAX_RATING ? null : parsed;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
