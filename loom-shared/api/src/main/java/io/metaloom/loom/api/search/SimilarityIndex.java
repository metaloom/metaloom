package io.metaloom.loom.api.search;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Perceptual video-fingerprint similarity index.
 *
 * <p>
 * A k-nearest-neighbour index over the 256-dim float vectors behind {@code MultiSectorFingerprint}. Unlike {@link SearchProvider} (lexical search),
 * this index answers "which assets have a fingerprint close to this one?" — the near-duplicate query that drives the deduplication nodes.
 * </p>
 *
 * <p>
 * The index is a <b>derived, rebuildable cache</b> of {@code asset_fingerprint_comp}: it can be dropped and reconstructed in full from the comp table
 * at any time via {@link #rebuild(Stream)}. It is never a system-of-record. Every write path updates the comp table first and the index second; a
 * failed index write is logged, never fatal.
 * </p>
 *
 * <p>
 * Exactly one implementation is bound at runtime, selected by {@code LOOM_SIMILARITY_ENABLED}. Implementations must never throw from
 * {@link #isAvailable()} — the status/endpoint code calls it precisely when something is broken.
 * </p>
 *
 * @see io.metaloom.loom.api.search.SearchProvider
 */
public interface SimilarityIndex {

	/**
	 * Upsert one asset's fingerprint vector. Idempotent on {@code (assetUuid, algorithm)}: re-indexing the same asset replaces the previous vector.
	 *
	 * @param assetUuid
	 *            owning asset
	 * @param sha512
	 *            asset content hash (stored for convenience so callers need not re-load the asset)
	 * @param algorithm
	 *            fingerprint algorithm (e.g. {@code metaloom-multisector-v1}); a filter field so multiple algorithms can coexist
	 * @param vector
	 *            the fingerprint vector (length must match the index dimension)
	 */
	void index(UUID assetUuid, String sha512, String algorithm, float[] vector);

	/**
	 * Upsert one asset's fingerprint from its <b>stored hex form</b>.
	 *
	 * <p>
	 * This is the overload Loom itself uses: {@code asset_fingerprint_comp} stores the fingerprint as hex, and decoding it to a vector requires knowing
	 * the fingerprint format. That knowledge lives with the implementation, so callers (the REST layer, the comp write hook) never need the codec.
	 * Malformed hex is logged and ignored - a bad fingerprint must not fail the comp write.
	 * </p>
	 */
	void index(UUID assetUuid, String sha512, String algorithm, String fingerprintHex);

	/** Remove all vectors for an asset (called on asset/comp delete). No-op if the asset is not indexed. */
	void remove(UUID assetUuid);

	/**
	 * k-nearest-neighbour query. Hits below {@code scoreThreshold} are dropped. Never returns {@code null}.
	 *
	 * @param algorithm
	 *            restrict to vectors indexed under this algorithm
	 * @param vector
	 *            the query vector
	 * @param limit
	 *            maximum neighbours to return
	 * @param scoreThreshold
	 *            minimum k-NN score to keep a hit
	 * @return hits ordered by score descending, never {@code null}
	 */
	List<SimilarityHit> query(String algorithm, float[] vector, int limit, float scoreThreshold);

	/**
	 * k-nearest-neighbour query from a <b>stored hex</b> fingerprint - the overload Loom uses. Returns an empty list when the hex cannot be decoded.
	 */
	List<SimilarityHit> query(String algorithm, String fingerprintHex, int limit, float scoreThreshold);

	/** Rebuild the whole index from the supplied vectors (boot / admin). Replaces any existing index content. */
	void rebuild(Stream<IndexedFingerprint> all);

	/**
	 * Rebuild the whole index from stored hex fingerprints - the overload the admin rebuild route uses, streaming {@code asset_fingerprint_comp} rows.
	 * Entries whose hex cannot be decoded are logged and skipped.
	 */
	void rebuildFromHex(Stream<HexFingerprint> all);

	/** Flush pending writes to disk. */
	void commit();

	/** False when disabled or the index directory is unavailable — callers must degrade gracefully (never silently). */
	boolean isAvailable();
}
