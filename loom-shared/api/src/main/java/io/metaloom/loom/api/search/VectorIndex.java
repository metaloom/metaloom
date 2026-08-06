package io.metaloom.loom.api.search;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Approximate nearest-neighbour index over the vectors in the {@code embedding} table.
 *
 * <p>
 * Answers "which embeddings are closest to this one?" - the query behind face matching and person clustering, and later behind semantic search over
 * whole-image and text embeddings. The first producer is {@code FacedetectNode}; the SPI is deliberately not face-specific, because the vectors that
 * arrive next will not be faces.
 * </p>
 *
 * <p>
 * <b>The index is a derived, rebuildable cache of {@code embedding.vector}, never a system of record.</b> It can be dropped and reconstructed in full
 * at any time via {@link #rebuild(Stream)}. Every write path writes Postgres first and the index second; a failed index write is logged and never
 * fatal, and the row stays {@code dirty} so the sync picks it up later. This is what makes swapping the backend safe: the data was never only in the
 * index.
 * </p>
 *
 * <p>
 * Exactly one implementation is bound at runtime, selected by {@code LOOM_VECTOR_INDEX_PROVIDER}. Implementations must never throw from
 * {@link #isAvailable()} - the status and rebuild routes call it precisely when something is broken. A backend that is unavailable must cause the
 * query routes to <b>reject</b> with a named reason, never to return an empty list: "no index" and "no matches" are opposite answers and must not look
 * the same to a caller.
 * </p>
 *
 * <p>
 * Every operation is scoped by {@link VectorSpace}, so vectors from different models never mix. See that record for why.
 * </p>
 *
 * @see io.metaloom.loom.api.search.SimilarityIndex the sibling index for perceptual video fingerprints, whose shape this follows
 */
public interface VectorIndex {

	/**
	 * Upsert one vector. Idempotent on {@code embeddingUuid}: re-indexing the same embedding replaces the previous vector rather than adding a second
	 * copy, so a node re-run and a sync drain of the same row converge instead of accumulating.
	 */
	void index(VectorRecord record);

	/**
	 * Upsert a batch. Equivalent to calling {@link #index(VectorRecord)} per record, but implementations may commit once for the batch.
	 */
	void indexAll(List<VectorRecord> records);

	/** Remove one embedding's vector. No-op if it is not indexed. */
	void removeByEmbedding(UUID embeddingUuid);

	/**
	 * Remove every vector belonging to an asset.
	 *
	 * <p>
	 * {@code embedding} cascades from {@code asset} in SQL, so the rows are already gone by the time this is called; without it the index would keep
	 * answering with vectors whose assets no longer exist.
	 * </p>
	 */
	void removeByAsset(UUID assetUuid);

	/**
	 * Nearest-neighbour query. Hits below the query's threshold are dropped. Never returns {@code null}; an empty list means no neighbours, which is a
	 * different thing from an unavailable index - check {@link #isAvailable()} for that.
	 *
	 * @return hits ordered by score descending
	 */
	List<VectorHit> query(VectorQuery query);

	/**
	 * Rebuild the whole index from the supplied vectors, replacing any existing content.
	 *
	 * <p>
	 * This is the operation behind both "we changed the embedding model" and "we changed the index backend", and the reason neither needs a data
	 * migration. Takes a stream because a full rebuild walks every embedding in the system and must not need them all in memory.
	 * </p>
	 */
	void rebuild(Stream<VectorRecord> all);

	/** Flush pending writes to disk. */
	void commit();

	/** False when disabled or the backend is unusable - callers must degrade loudly, never silently. */
	boolean isAvailable();

	/** A short name for the bound backend, e.g. {@code lucene} or {@code none}, for status reporting. */
	String providerName();
}
