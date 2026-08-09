package io.metaloom.loom.db.model.embedding;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.user.User;

public interface EmbeddingDao extends CRUDDao<Embedding> {

	default Embedding createEmbedding(User user, Asset asset, Float[] data, String type) {
		return createEmbedding(user.getUuid(), asset.getUuid(), data, type);
	}

	Embedding createEmbedding(UUID userUuid, UUID assetUuid, Float[] data, String type);

	/**
	 * Insert the embedding, or replace the conflicting row keyed by {@code (asset_uuid, node_kind, type, frame_number, subject_index)}. A node that runs
	 * again rewrites its own rows instead of appending duplicates.
	 *
	 * @param embedding the embedding to persist; its uuid is populated on return
	 * @return the persisted embedding
	 */
	Embedding upsertEmbedding(Embedding embedding);

	/**
	 * Stream the embeddings that have not yet been written to the vector index, oldest first.
	 *
	 * <p>
	 * This is the drain the index sync runs on. It is deliberately a stream rather than a list: a full rebuild walks every embedding in the system and
	 * must not need them all in memory at once.
	 * </p>
	 *
	 * @param limit maximum rows to return
	 */
	List<Embedding> findDirty(int limit);

	/**
	 * Stream every embedding, for a full index rebuild. Ordered by uuid so a rebuild is reproducible.
	 */
	Stream<Embedding> streamAll();

	/**
	 * Mark the given embeddings as drained into the index, clearing {@code dirty} and stamping {@code synced_at}. A no-op for an empty collection.
	 */
	void markSynced(Collection<UUID> uuids);

	/**
	 * One row per distinct {@code (type, model, dimensions)} triple present in the table, with its total and its unindexed backlog.
	 *
	 * <p>
	 * This is how the admin surface discovers which indices exist at all. There is no registry of vector spaces anywhere - a space comes into being the
	 * first time a node writes a vector of a new kind, which is deliberate ({@code type} and {@code model} are free text so a new model needs no code
	 * change), and the only place that knowledge lives is the table itself.
	 * </p>
	 */
	List<EmbeddingSpaceStats> listSpaces();

	/**
	 * Every embedding in one space, ordered by uuid so a rebuild is reproducible.
	 *
	 * <p>
	 * The scoped counterpart to {@link #streamAll()}. Reindexing one space must not read - and must not cause its index backend to discard - the
	 * vectors of another model that happens to share the backend.
	 * </p>
	 */
	Stream<Embedding> streamAll(String type, String model, int dimensions);

	/** Rows in one space still marked dirty, oldest {@code synced_at} first. */
	List<Embedding> findDirty(String type, String model, int dimensions, int limit);

	/** Which of the given uuids still exist. The batched membership test behind the index orphan sweep. */
	Set<UUID> filterExisting(Collection<UUID> uuids);

	/**
	 * Total and unindexed counts for one {@code (type, model, dimensions)} triple.
	 *
	 * @param total
	 *            rows in the {@code embedding} table for this space - the system of record's count, against which an index's own count is compared
	 * @param dirty
	 *            rows not yet written to the index
	 */
	record EmbeddingSpaceStats(String type, String model, int dimensions, long total, long dirty) {
	}

}
