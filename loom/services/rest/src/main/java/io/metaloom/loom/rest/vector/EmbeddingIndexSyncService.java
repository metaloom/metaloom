package io.metaloom.loom.rest.vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.VectorIndexOptions;
import io.metaloom.loom.api.search.VectorIndex;
import io.metaloom.loom.api.search.VectorRecord;
import io.metaloom.loom.api.search.VectorSpace;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;

/**
 * Keeps the {@link VectorIndex} in step with the {@code embedding} table.
 *
 * <p>
 * The index is a derived, rebuildable cache and {@code embedding} is the system of record, so every path here is best-effort: a failed index write is
 * logged and never fails the database write. What makes that safe rather than merely forgiving is the {@code dirty} flag - a row that could not be
 * indexed stays marked, and {@link #drain(int)} picks it up on the next pass. A crash between the two writes therefore costs a few seconds of index
 * lag, not a missing vector.
 * </p>
 *
 * <p>
 * There are three ways in, in increasing order of cost: the write hook ({@link #index(Collection)}), called inline when an embedding is created; the
 * periodic drain, which catches whatever the hook missed; and {@link #rebuild()}, which replaces the index wholesale and is the recovery path for a
 * lost index, a changed backend or a changed embedding model.
 * </p>
 */
@Singleton
public class EmbeddingIndexSyncService {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexSyncService.class);

	private final VectorIndex index;
	private final EmbeddingDao embeddingDao;
	private final VectorIndexOptions options;

	@Inject
	public EmbeddingIndexSyncService(VectorIndex index, EmbeddingDao embeddingDao, VectorIndexOptions options) {
		this.index = index;
		this.embeddingDao = embeddingDao;
		this.options = options;
	}

	public VectorIndex index() {
		return index;
	}

	public VectorIndexOptions options() {
		return options;
	}

	/**
	 * Write hook: index the embeddings just written and clear their {@code dirty} flag.
	 *
	 * <p>
	 * Rows that cannot be turned into a vector record - no vector, no type, a length that disagrees with {@code dimensions} - are skipped and left
	 * dirty, so they show up as a persistent backlog rather than disappearing silently.
	 * </p>
	 */
	public void index(Collection<Embedding> embeddings) {
		if (!index.isAvailable() || embeddings == null || embeddings.isEmpty()) {
			return;
		}
		try {
			List<VectorRecord> records = new ArrayList<>();
			List<UUID> synced = new ArrayList<>();
			for (Embedding embedding : embeddings) {
				VectorRecord record = toRecord(embedding);
				if (record != null) {
					records.add(record);
					synced.add(embedding.getUuid());
				}
			}
			if (records.isEmpty()) {
				return;
			}
			index.indexAll(records);
			embeddingDao.markSynced(synced);
		} catch (Exception e) {
			log.warn("Failed to update the vector index for {} embedding(s): {}", embeddings.size(), e.getMessage());
		}
	}

	public void remove(UUID embeddingUuid) {
		if (!index.isAvailable() || embeddingUuid == null) {
			return;
		}
		try {
			index.removeByEmbedding(embeddingUuid);
			index.commit();
		} catch (Exception e) {
			log.warn("Failed to remove embedding {} from the vector index: {}", embeddingUuid, e.getMessage());
		}
	}

	/**
	 * Drop every vector belonging to an asset.
	 *
	 * <p>
	 * The {@code embedding} rows are already gone by the time this runs - they cascade from {@code asset} in SQL - so a rebuild would not remove them
	 * either. Without this call the index would keep returning hits for assets that no longer exist.
	 * </p>
	 */
	public void removeAsset(UUID assetUuid) {
		if (!index.isAvailable() || assetUuid == null) {
			return;
		}
		try {
			index.removeByAsset(assetUuid);
			index.commit();
		} catch (Exception e) {
			log.warn("Failed to remove the vectors of asset {} from the vector index: {}", assetUuid, e.getMessage());
		}
	}

	/**
	 * Index one batch of rows still marked dirty.
	 *
	 * @param limit
	 *            maximum rows to take this pass
	 * @return how many rows were indexed
	 */
	public int drain(int limit) {
		if (!index.isAvailable()) {
			return 0;
		}
		try {
			List<Embedding> dirty = embeddingDao.findDirty(limit);
			if (dirty.isEmpty()) {
				return 0;
			}
			int before = dirty.size();
			index(dirty);
			log.debug("Drained {} dirty embedding(s) into the vector index", before);
			return before;
		} catch (Exception e) {
			log.warn("The vector index drain failed: {}", e.getMessage());
			return 0;
		}
	}

	/**
	 * Rebuild the index from every stored embedding.
	 *
	 * <p>
	 * This is what makes a backend swap or an embedding-model change an operation rather than a migration. Rows that cannot be converted are skipped
	 * and reported, so a corrupt or half-written row cannot abort the whole rebuild.
	 * </p>
	 *
	 * @return how many vectors were indexed
	 */
	public long rebuild() {
		long[] indexed = { 0 };
		try (Stream<Embedding> all = embeddingDao.streamAll()) {
			index.rebuild(all.map(embedding -> {
				VectorRecord record = toRecord(embedding);
				if (record != null) {
					indexed[0]++;
				}
				return record;
			}).filter(record -> record != null));
		}
		log.info("Rebuilt the vector index from {} embedding(s)", indexed[0]);
		return indexed[0];
	}

	/**
	 * Convert a stored embedding into an index record, or return null when it cannot be indexed.
	 *
	 * <p>
	 * The conversions that can fail are all cases where the row does not describe a comparable vector: a missing vector or type, or a
	 * {@code dimensions} that disagrees with the actual length. The V2.75 CHECK makes the last one unreachable through the DAO, but the guard stays -
	 * this runs over whatever is in the table, including rows written before that constraint existed.
	 * </p>
	 */
	public static VectorRecord toRecord(Embedding embedding) {
		if (embedding == null || embedding.getUuid() == null) {
			return null;
		}
		Float[] boxed = embedding.getVector();
		String type = embedding.getType();
		if (boxed == null || boxed.length == 0 || type == null || type.isBlank()) {
			return null;
		}
		float[] vector = new float[boxed.length];
		for (int i = 0; i < boxed.length; i++) {
			if (boxed[i] == null) {
				log.warn("Skipping embedding {}: the vector contains a null at position {}", embedding.getUuid(), i);
				return null;
			}
			vector[i] = boxed[i];
		}
		Integer dimensions = embedding.getDimensions();
		if (dimensions != null && dimensions != vector.length) {
			log.warn("Skipping embedding {}: dimensions {} disagrees with the stored vector length {}",
				embedding.getUuid(), dimensions, vector.length);
			return null;
		}
		VectorSpace space = new VectorSpace(type, embedding.getModel(), vector.length);
		return new VectorRecord(embedding.getUuid(), embedding.getAssetUuid(), embedding.getDetectionUuid(), space, vector);
	}
}
