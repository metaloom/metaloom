package io.metaloom.loom.rest.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.options.SimilarityOptions;
import io.metaloom.loom.api.options.VectorIndexOptions;
import io.metaloom.loom.api.search.HexFingerprint;
import io.metaloom.loom.api.search.SearchIndexer;
import io.metaloom.loom.api.search.SimilarityIndex;
import io.metaloom.loom.api.search.VectorIndex;
import io.metaloom.loom.api.search.VectorRecord;
import io.metaloom.loom.api.search.VectorSpace;
import io.metaloom.loom.db.jooq.search.SearchEmbeddingService;
import io.metaloom.loom.db.model.asset.AssetComponentDao;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;
import io.metaloom.loom.rest.vector.EmbeddingIndexSyncService;

/**
 * Performs the work behind an {@link IndexJobAction}.
 *
 * <p>
 * Runs on the index-job executor, never on an event loop or a request thread. Separate from {@link SearchIndexRegistry} because that answers "what
 * exists and how is it doing" for every page load, while this changes things and runs for minutes.
 * </p>
 *
 * <h2>Why a reindex is a drop plus a fill, not {@code rebuild()}</h2>
 *
 * <p>
 * {@link VectorIndex#rebuild(Stream)} clears the entire index. One Lucene directory holds every vector space at once - face vectors beside
 * search-text vectors beside a model's replacement - so reindexing the faces through it would silently empty the others. Every per-index operation
 * here is therefore scoped: {@link VectorIndex#drop(VectorSpace)} followed by batched writes of that space alone.
 * </p>
 */
@Singleton
public class SearchIndexJobRunner {

	private static final Logger log = LoggerFactory.getLogger(SearchIndexJobRunner.class);

	/** Vectors written to the index per commit during a reindex. Large enough to amortise the commit, small enough to keep progress moving. */
	private static final int INDEX_BATCH = 500;

	/**
	 * Indexed identifiers compared against the database per round during an orphan sweep.
	 *
	 * <p>
	 * Bounds both the {@code IN (...)} list and the memory the sweep holds. Postgres handles far larger lists, but a sweep is a background chore and
	 * has no business building a hundred-thousand-element bind array.
	 * </p>
	 */
	private static final int SWEEP_BATCH = 10_000;

	/** Upper bound on re-embedding passes, so a document that fails to embed every time cannot spin forever. */
	private static final int EMBED_MAX_PASSES = 1000;

	private final SearchIndexer searchIndexer;
	private final SearchOptions searchOptions;
	private final SearchEmbeddingService searchEmbeddingService;
	private final VectorIndex vectorIndex;
	private final VectorIndexOptions vectorOptions;
	private final EmbeddingDao embeddingDao;
	private final EmbeddingIndexSyncService vectorSync;
	private final SimilarityIndex similarityIndex;
	private final SimilarityOptions similarityOptions;
	private final AssetComponentDao compDao;

	@Inject
	public SearchIndexJobRunner(SearchIndexer searchIndexer, SearchOptions searchOptions, SearchEmbeddingService searchEmbeddingService,
		VectorIndex vectorIndex, VectorIndexOptions vectorOptions, EmbeddingDao embeddingDao, EmbeddingIndexSyncService vectorSync,
		SimilarityIndex similarityIndex, SimilarityOptions similarityOptions, AssetComponentDao compDao) {
		this.searchIndexer = searchIndexer;
		this.searchOptions = searchOptions;
		this.searchEmbeddingService = searchEmbeddingService;
		this.vectorIndex = vectorIndex;
		this.vectorOptions = vectorOptions;
		this.embeddingDao = embeddingDao;
		this.vectorSync = vectorSync;
		this.similarityIndex = similarityIndex;
		this.similarityOptions = similarityOptions;
		this.compDao = compDao;
	}

	/** Dispatch one job. Exceptions propagate to {@link IndexJobRegistry}, which records them on the job. */
	public void run(SearchIndexDescriptor index, IndexJob job) {
		switch (index.kind()) {
			case LEXICAL -> runLexical(job);
			case VECTOR -> runVector(index, job);
			case FINGERPRINT -> runFingerprint(index, job);
		}
	}

	// ---- lexical ---------------------------------------------------------------------------------

	/**
	 * The lexical rebuild is one SQL call to {@code search_document_rebuild()}, which re-runs the very refresh functions the triggers call.
	 *
	 * <p>
	 * It reports no intermediate progress and cannot be cancelled once started - {@link IndexJob#getTotal()} therefore stays null and the client draws
	 * an indeterminate bar. Faking a total by counting assets first would be worse than admitting the gap: the count and the work are not proportional
	 * (one asset with a two-hour transcript costs more than a thousand photographs).
	 * </p>
	 */
	private void runLexical(IndexJob job) {
		long count = searchIndexer.rebuild();
		job.setProcessed(count);
		log.info("Rebuilt the lexical index into {} document(s)", count);
	}

	// ---- vector ----------------------------------------------------------------------------------

	private void runVector(SearchIndexDescriptor index, IndexJob job) {
		VectorSpace space = index.space();
		switch (job.getAction()) {
			case DROP -> {
				long before = vectorIndex.status(space).getDocumentCount();
				vectorIndex.drop(space);
				job.setRemoved(before);
			}
			case REINDEX -> reindexVectorSpace(space, job);
			case DELTA_SYNC -> deltaSyncVectorSpace(index, space, job);
		}
	}

	private void reindexVectorSpace(VectorSpace space, IndexJob job) {
		job.setTotal(countVectors(space));
		vectorIndex.drop(space);

		List<VectorRecord> batch = new ArrayList<>(INDEX_BATCH);
		List<UUID> synced = new ArrayList<>(INDEX_BATCH);
		try (Stream<Embedding> all = embeddingDao.streamAll(space.type(), space.model(), space.dimensions())) {
			for (Embedding embedding : (Iterable<Embedding>) all::iterator) {
				if (job.isCancelRequested()) {
					break;
				}
				VectorRecord record = EmbeddingIndexSyncService.toRecord(embedding);
				if (record == null) {
					// Unconvertible rows are skipped and left dirty rather than silently dropped, so a
					// corrupt row shows up as a permanent backlog instead of a vector that quietly vanished.
					continue;
				}
				batch.add(record);
				synced.add(embedding.getUuid());
				job.incrementProcessed();
				if (batch.size() >= INDEX_BATCH) {
					flush(batch, synced);
				}
			}
		}
		flush(batch, synced);
		vectorIndex.commit();
	}

	private void flush(List<VectorRecord> batch, List<UUID> synced) {
		if (batch.isEmpty()) {
			return;
		}
		vectorIndex.indexAll(batch);
		embeddingDao.markSynced(synced);
		batch.clear();
		synced.clear();
	}

	/**
	 * Bring one space up to date without rebuilding: embed what is missing, index what is dirty, remove what is orphaned.
	 *
	 * <p>
	 * The re-embed step runs only for the semantic space and only because it is a deliberate button press - it calls the inference host once per stale
	 * document and therefore costs money, which is exactly why the background drainer is rate-limited and this is not on any polling path.
	 * </p>
	 */
	private void deltaSyncVectorSpace(SearchIndexDescriptor index, VectorSpace space, IndexJob job) {
		if (isSemantic(space)) {
			job.setProcessed(job.getProcessed() + searchEmbeddingService.embedAllStale(EMBED_MAX_PASSES));
		}

		int batchSize = Math.max(1, vectorOptions.getSyncBatchSize());
		while (!job.isCancelRequested()) {
			List<Embedding> dirty = embeddingDao.findDirty(space.type(), space.model(), space.dimensions(), batchSize);
			if (dirty.isEmpty()) {
				break;
			}
			vectorSync.index(dirty);
			job.setProcessed(job.getProcessed() + dirty.size());
			if (dirty.size() < batchSize) {
				break;
			}
		}

		sweepVectorOrphans(job);
		vectorIndex.commit();
		log.info("Delta-synced {}: {} written, {} orphan(s) removed", index.id(), job.getProcessed(), job.getRemoved());
	}

	/**
	 * Remove index entries whose embedding row is gone.
	 *
	 * <p>
	 * Necessary because {@code embedding} cascades away with its asset and leaves no tombstone: if the index was disabled, or the process died between
	 * the database commit and the index write, nothing ever told the index. Without this the index keeps answering with vectors belonging to assets
	 * that no longer exist, and a rebuild - which starts from empty - is the only other cure.
	 * </p>
	 *
	 * <p>
	 * Deliberately index-wide rather than per-space. The identifier dictionary the sweep walks is not partitioned by space, and an orphan is an orphan
	 * regardless of which space it sat in.
	 * </p>
	 */
	private void sweepVectorOrphans(IndexJob job) {
		Set<UUID> candidates = new LinkedHashSet<>(SWEEP_BATCH);
		try (Stream<UUID> indexed = vectorIndex.streamIndexedEmbeddingUuids()) {
			for (UUID uuid : (Iterable<UUID>) indexed::iterator) {
				if (job.isCancelRequested()) {
					return;
				}
				candidates.add(uuid);
				if (candidates.size() >= SWEEP_BATCH) {
					job.addRemoved(removeMissing(candidates));
					candidates.clear();
				}
			}
		}
		job.addRemoved(removeMissing(candidates));
	}

	private long removeMissing(Set<UUID> candidates) {
		if (candidates.isEmpty()) {
			return 0;
		}
		Set<UUID> alive = embeddingDao.filterExisting(candidates);
		long removed = 0;
		for (UUID uuid : candidates) {
			if (!alive.contains(uuid)) {
				// Deleting an already-deleted document is a no-op, which is what lets the sweep read the
				// term dictionary (a superset that still lists unmerged deletes) instead of live documents.
				vectorIndex.removeByEmbedding(uuid);
				removed++;
			}
		}
		return removed;
	}

	/**
	 * How many rows the reindex expects to walk.
	 *
	 * <p>
	 * Read from {@code listSpaces()} rather than a dedicated count query: it is one grouped scan over an existing index, and it is the same number the
	 * list screen just displayed, so the total a progress bar counts against agrees with the total the operator was looking at when they pressed the
	 * button.
	 * </p>
	 */
	private long countVectors(VectorSpace space) {
		return embeddingDao.listSpaces().stream()
			.filter(stats -> new VectorSpace(stats.type(), stats.model(), stats.dimensions()).key().equals(space.key()))
			.mapToLong(EmbeddingDao.EmbeddingSpaceStats::total)
			.findFirst()
			.orElse(0);
	}

	private boolean isSemantic(VectorSpace space) {
		if (!searchOptions.isSemanticEnabled()) {
			return false;
		}
		String model = searchOptions.getEmbedModel();
		return model != null && !model.isBlank()
			&& new VectorSpace(searchOptions.getVectorType(), model, searchOptions.getEmbedDimensions()).key().equals(space.key());
	}

	// ---- fingerprint -----------------------------------------------------------------------------

	private void runFingerprint(SearchIndexDescriptor index, IndexJob job) {
		String algorithm = index.algorithm();
		switch (job.getAction()) {
			case DROP -> {
				long before = similarityIndex.status(algorithm).getDocumentCount();
				similarityIndex.drop(algorithm);
				job.setRemoved(before);
			}
			case REINDEX -> reindexFingerprints(algorithm, job);
			case DELTA_SYNC -> sweepFingerprintOrphans(algorithm, job);
		}
	}

	private void reindexFingerprints(String algorithm, IndexJob job) {
		job.setTotal(compDao.countByAlgorithm(algorithm));
		similarityIndex.drop(algorithm);
		// The projection joins the owning asset, so a reindexed hit carries the content hash the dedup consumer identifies duplicates by.
		try (Stream<HexFingerprint> fingerprints = compDao.streamHexFingerprintsByAlgorithm(algorithm)) {
			for (HexFingerprint fingerprint : (Iterable<HexFingerprint>) fingerprints::iterator) {
				if (job.isCancelRequested()) {
					break;
				}
				similarityIndex.index(fingerprint.assetUuid(), fingerprint.sha512(), fingerprint.algorithm(), fingerprint.fingerprint());
				job.incrementProcessed();
			}
		}
		similarityIndex.commit();
	}

	/**
	 * The fingerprint index has no dirty flag, so a delta sync here can only remove what should not be there. Anything missing is added by a reindex -
	 * which is honest rather than limiting, because there is nothing recording that a fingerprint was never indexed.
	 */
	private void sweepFingerprintOrphans(String algorithm, IndexJob job) {
		Set<UUID> candidates = new LinkedHashSet<>(SWEEP_BATCH);
		try (Stream<UUID> indexed = similarityIndex.streamIndexedAssetUuids()) {
			for (UUID uuid : (Iterable<UUID>) indexed::iterator) {
				if (job.isCancelRequested()) {
					return;
				}
				candidates.add(uuid);
				if (candidates.size() >= SWEEP_BATCH) {
					job.addRemoved(removeMissingAssets(algorithm, candidates));
					candidates.clear();
				}
			}
		}
		job.addRemoved(removeMissingAssets(algorithm, candidates));
		similarityIndex.commit();
	}

	private long removeMissingAssets(String algorithm, Set<UUID> candidates) {
		if (candidates.isEmpty()) {
			return 0;
		}
		Set<UUID> alive = compDao.filterExistingFingerprintAssets(algorithm, candidates);
		long removed = 0;
		for (UUID uuid : candidates) {
			if (!alive.contains(uuid)) {
				similarityIndex.remove(uuid);
				removed++;
			}
		}
		return removed;
	}

	/** Exposed so the endpoint service can report the configured algorithm without reaching for the options itself. */
	public String defaultAlgorithm() {
		return similarityOptions.getAlgorithm();
	}
}
