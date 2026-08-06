package io.metaloom.loom.db.model.embedding;

import java.util.Collection;
import java.util.List;
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

}
