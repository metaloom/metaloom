package io.metaloom.cortex.node.filter;

/**
 * What a {@link FilterNode} instance matches its buckets against.
 *
 * <p>
 * This is the seam that lets one node kind replace the eight {@code filter-*} kinds that used to be
 * advertised. Each constant is backed by a {@link FilterStrategy}; adding one is a strategy class
 * plus a Dagger binding plus a value in the descriptor's {@code filterBy} parameter, and never an
 * edit to {@link FilterNode}.
 * </p>
 *
 * <p>
 * What each one costs differs, and it is worth knowing before choosing:
 * </p>
 * <ul>
 * <li>{@link #MIME}, {@link #SIZE} and {@link #DATE} read the item's own metadata. No backend of any
 * kind.</li>
 * <li>{@link #TAG} reads the asset Loom already returned for the item, so it needs Loom reachable
 * but costs no call of its own.</li>
 * <li>{@link #RATING} costs one extra Loom call per item — cached per run — because a rating lives
 * on the asset's reactions rather than on the asset.</li>
 * <li>{@link #LANGUAGE} is the only one that needs a model backend.</li>
 * </ul>
 */
public enum FilterBy {

	/**
	 * The language of the wired text, decided by a language model.
	 *
	 * <p>
	 * A model rather than a detector library is a deliberate starting point, not a conclusion: it
	 * costs one round trip per item and needs a reachable LLM backend. See the node's website page for
	 * the alternatives that were considered.
	 * </p>
	 */
	LANGUAGE,

	/**
	 * The item's MIME type, derived from its file name. Bucket hints are patterns —
	 * {@code image/*}, {@code video/mp4}, or a bare family such as {@code image}.
	 */
	MIME,

	/**
	 * The item's size in bytes. Bucket hints are thresholds and ranges — {@code <10MB},
	 * {@code 1MB..100MB}, {@code >1GB}.
	 */
	SIZE,

	/**
	 * The item's last-modified time. Bucket hints are dates, date ranges and ages —
	 * {@code >=2024-01-01}, {@code 2024-01-01..2024-12-31}, {@code age<30d}.
	 */
	DATE,

	/**
	 * The star rating reviewers gave the asset, averaged. Bucket hints are thresholds and ranges —
	 * {@code >=8}, {@code <=2}, {@code 4..7}, a bare {@code 8} meaning exactly that rating, and
	 * {@code unrated}.
	 *
	 * <p>
	 * This is one half of what makes manual review worth doing: without it a person's decision goes
	 * into the database and never comes out again.
	 * </p>
	 */
	RATING,

	/**
	 * The tags on the asset. Bucket hints are names and prefix globs, optionally negated —
	 * {@code hero}, {@code person/*}, {@code !archive}, and {@code untagged}.
	 */
	TAG
}
