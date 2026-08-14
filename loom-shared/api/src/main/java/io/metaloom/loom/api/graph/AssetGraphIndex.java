package io.metaloom.loom.api.graph;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.loom.api.search.IndexStatus;

/**
 * Traversal index over the asset relationship graph - the tags, collections, remixes and people that connect one asset to another.
 *
 * <p>
 * Answers "what else is like this asset, and why". Postgres can answer it too, with a self-join through each link table and a union; this index exists
 * because that query gets slower with every relation added to the schema, while a graph traversal does not. Whether it is actually worth having is a
 * measured question, and {@code spec/reports/PHASE_STATUS.md} in the graph-storage project records the measurement.
 * </p>
 *
 * <p>
 * <b>The index is a derived, rebuildable projection of the link tables, never a system of record.</b> Every fact in it exists in
 * {@code tag_asset}, {@code collection_asset}, {@code remix_member} or {@code detection} first. It can be dropped and reconstructed in full at any
 * time via {@link #rebuild(Stream)}. Every write path writes Postgres first and the index second; a failed index write is logged and never fatal. This
 * is the same contract as {@link io.metaloom.loom.api.search.VectorIndex} and for the same reason: it is what makes the backend swappable, and it is
 * what makes the engine's single-writer limitation survivable, because an index can be rebuilt and can be written in batches.
 * </p>
 *
 * <p>
 * Exactly one implementation is bound at runtime, selected by {@code LOOM_ASSET_GRAPH_PROVIDER}. Implementations must never throw from
 * {@link #isAvailable()} - the status and rebuild routes call it precisely when something is broken. A backend that is unavailable must cause the
 * query routes to <b>reject</b> with a named reason, never to return an empty list: "no index" and "nothing is related" are opposite answers and must
 * not look the same to a caller.
 * </p>
 *
 * @see io.metaloom.loom.api.search.VectorIndex the sibling index whose contract this follows exactly
 */
public interface AssetGraphIndex {

	/**
	 * Record one relationship. Idempotent on {@code (from, type, to)}: re-projecting the same row replaces the edge rather than adding a second copy,
	 * so a write hook and a full rebuild of the same relation converge instead of accumulating.
	 */
	void link(GraphEdge edge);

	/** Record a batch. Equivalent to {@link #link(GraphEdge)} per edge, but implementations may commit once for the batch. */
	void linkAll(List<GraphEdge> edges);

	/** Remove one relationship. No-op if it was never recorded. */
	void unlink(GraphEdge edge);

	/**
	 * Remove a node and every edge touching it.
	 *
	 * <p>
	 * The link rows cascade from their owners in SQL, so they are already gone by the time this is called; without it the index would keep answering
	 * with relationships whose rows no longer exist.
	 * </p>
	 */
	void remove(GraphNodeRef node);

	/**
	 * The assets related to one asset, best first.
	 *
	 * <p>
	 * Never returns {@code null}; an empty list means nothing is related, which is a different thing from an unavailable index - check
	 * {@link #isAvailable()} for that. An asset is never its own neighbour.
	 * </p>
	 */
	List<RelatedAsset> relatedAssets(RelatedAssetsQuery query);

	/**
	 * The immediate neighbours of a node: the tags, collections, remixes and people on an asset, or the assets on one of those.
	 *
	 * @param types
	 *            relation types to include, or {@code null} for all
	 */
	List<GraphNodeRef> neighbours(GraphNodeRef node, Set<String> types);

	/** True when the index knows about the node at all. Distinguishes "not indexed" from "indexed with no edges". */
	boolean contains(GraphNodeRef node);

	/**
	 * Rebuild the whole index from the supplied edges, replacing any existing content.
	 *
	 * <p>
	 * This is the operation behind both "we added a relation to the schema" and "we changed the index backend", and the reason neither needs a data
	 * migration. Takes a stream because a full rebuild walks every link row in the system and must not need them all in memory.
	 * </p>
	 */
	void rebuild(Stream<GraphEdge> all);

	/** Every asset uuid the index currently knows about. The input to the orphan sweep; the caller must close the stream. */
	Stream<UUID> streamIndexedAssetUuids();

	/**
	 * State of the backend: how many nodes and edges it holds and how much disk it occupies. Never throws - like {@link #isAvailable()} this is called
	 * precisely when something is broken.
	 */
	IndexStatus status();

	/** Flush pending writes to disk. */
	void commit();

	/**
	 * Give space back after heavy churn.
	 *
	 * <p>
	 * Separate from {@link #commit()} because it is expensive and because in at least one backend it invalidates internal identifiers, which is safe
	 * only because nothing outside the index holds them - the index is addressed by uuid throughout. Callers schedule it; it never runs by itself.
	 * </p>
	 */
	void compact();

	/** False when disabled or the backend is unusable - callers must degrade loudly, never silently. */
	boolean isAvailable();

	/** A short name for the bound backend, e.g. {@code graphstore} or {@code none}, for status reporting. */
	String providerName();
}
