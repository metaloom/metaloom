package io.metaloom.loom.rest.search;

/**
 * The storage one or more indices share.
 *
 * <p>
 * Backends exist in this model for one reason: <b>size on disk has no per-index meaning</b>. The embedding vector index is a single Lucene directory
 * whose segments interleave every space in it, so "how many bytes do the face vectors take" is a question its own storage cannot answer. Reporting a
 * fabricated split - dividing by document share, say - would look authoritative and be wrong, so bytes are attributed to the directory that actually
 * holds them and the spaces beneath it carry counts alone.
 * </p>
 *
 * @param deletedCount
 *            documents deleted but not yet merged away. This is why a drop does not immediately shrink {@link #sizeBytes()}: Lucene deletes are
 *            logical, and the space returns on the next merge.
 */
public record SearchIndexBackend(
	String id,
	String provider,
	boolean enabled,
	boolean available,
	String reason,
	long documentCount,
	long deletedCount,
	long sizeBytes) {

	/** The Postgres table behind lexical search. */
	public static final String LEXICAL = "lexical";

	/** The Lucene directory holding every embedding vector space. */
	public static final String VECTOR = "vector";

	/** The Lucene directory holding perceptual fingerprints. Separate from {@link #VECTOR} on disk as well as in the model. */
	public static final String FINGERPRINT = "fingerprint";
}
