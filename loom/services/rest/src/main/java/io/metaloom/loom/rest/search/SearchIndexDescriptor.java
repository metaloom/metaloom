package io.metaloom.loom.rest.search;

import java.time.Instant;
import java.util.List;

import io.metaloom.loom.api.search.VectorSpace;

/**
 * One index as the admin surface sees it.
 *
 * <p>
 * Assembled per request by {@link SearchIndexRegistry} rather than stored: nothing in Loom keeps a list of indices, because a vector space comes into
 * existence the first time a node writes a vector of a new kind. Deriving the list from the data is what lets a new embedding model appear on the
 * screen without a code change - which is the same reason {@code embedding.type} and {@code embedding.model} are free text.
 * </p>
 *
 * @param documentCount
 *            how many items the <b>system of record</b> holds. Reported beside {@link #indexedCount} rather than instead of it, because the whole
 *            question an operator brings to this screen is whether those two agree.
 * @param indexedCount
 *            how many the index itself holds. Higher than {@code documentCount} means orphans a delta sync would remove; lower means a backlog.
 * @param pendingCount
 *            items known to be waiting. Tracked for vector spaces (the {@code dirty} flag) and for the semantic space (documents whose text changed
 *            since they were embedded); derived by comparison for fingerprints, which have no freshness flag; always 0 for the lexical index, which
 *            triggers maintain inside the writing transaction and which therefore cannot lag.
 * @param space
 *            the vector space this describes, or null for a non-vector index
 * @param algorithm
 *            the fingerprint algorithm this describes, or null for a non-fingerprint index
 */
public record SearchIndexDescriptor(
	String id,
	SearchIndexKind kind,
	String backendId,
	String label,
	VectorSpace space,
	String algorithm,
	boolean enabled,
	boolean available,
	String reason,
	long documentCount,
	long indexedCount,
	long pendingCount,
	Instant lastSyncedAt,
	List<IndexJobAction> supportedActions) {

	public boolean supports(IndexJobAction action) {
		return supportedActions.contains(action);
	}

	/** The producing model, or the empty string when none was recorded. Only vector spaces have one. */
	public String model() {
		return space == null ? null : space.model();
	}

	public Integer dimensions() {
		return space == null ? null : space.dimensions();
	}

	/** The {@code embedding.type} this space holds, e.g. {@code face}. Null for the lexical and fingerprint indices. */
	public String type() {
		return space == null ? null : space.type();
	}
}
