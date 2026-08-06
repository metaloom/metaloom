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
 * Only {@link #LANGUAGE} costs a round trip. {@link #MIME}, {@link #SIZE} and {@link #DATE} read the
 * item's own metadata and hold no reference to an {@code LLMProvider}, so a filter-only pipeline
 * runs with no model backend reachable at all.
 * </p>
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
	DATE
}
