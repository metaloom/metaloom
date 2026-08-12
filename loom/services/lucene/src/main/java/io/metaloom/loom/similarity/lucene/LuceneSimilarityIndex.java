package io.metaloom.loom.similarity.lucene;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene103.Lucene103Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.search.HexFingerprint;
import io.metaloom.loom.api.search.IndexStatus;
import io.metaloom.loom.api.search.IndexedFingerprint;
import io.metaloom.loom.api.search.SimilarityHit;
import io.metaloom.loom.api.search.SimilarityIndex;
import io.metaloom.video4j.fingerprint.index.HighDimensionKnnVectorsFormat;
import io.metaloom.video4j.fingerprint.v2.MultiSectorFingerprint;

/**
 * Lucene HNSW k-NN implementation of {@link SimilarityIndex} for perceptual video fingerprints.
 *
 * <p>
 * Reuses the <b>exact codec / vector format</b> that the {@code video4j} {@code fingerprint-indexer} uses (a {@link Lucene103Codec} whose k-NN format
 * is a {@link HighDimensionKnnVectorsFormat} wrapping a {@link Lucene99HnswVectorsFormat}, sized for the 256-dim
 * {@link MultiSectorFingerprint#FINGERPRINT_VECTOR_SIZE} vector). It does <b>not</b> reuse {@code HashFingerprintIndexer} directly because that class
 * stores only the sha512sum and supports neither an {@code asset_uuid} key, an {@code algorithm} filter, nor deletes — all of which this SPI needs.
 * </p>
 *
 * <p>
 * <b>Concurrency.</b> Lucene {@link IndexWriter} is single-writer; all mutations are serialized through {@link #writeLock}. Reads go through a
 * {@link SearcherManager} for near-real-time visibility. The index is a derived, rebuildable cache of {@code asset_fingerprint_comp} — losing it costs
 * a {@link #rebuild(Stream)}, never data.
 * </p>
 *
 * <p>
 * <b>Shutdown.</b> {@link #close()} is called from the server shutdown sequence and is idempotent. Every method re-reads {@link #available} <i>inside</i>
 * the write lock (or captures the {@link SearcherManager} before using it) so a request still in flight when the index closes degrades to a no-op or an
 * empty result instead of an {@link AlreadyClosedException} stack trace on the way out.
 * </p>
 */
public class LuceneSimilarityIndex implements SimilarityIndex, AutoCloseable {

	public static final Logger log = LoggerFactory.getLogger(LuceneSimilarityIndex.class);

	/** k-NN vector field; must match the field the vectors are written to. */
	public static final String VECTOR_FIELD = "fingerprint";
	/** Stored content hash (mirrors video4j's {@code sha512sum} stored field). */
	public static final String HASH_FIELD = "sha512sum";
	/** Indexed + stored asset uuid; the upsert / delete key. */
	public static final String ASSET_FIELD = "asset_uuid";
	/** Indexed + stored algorithm; the query filter field. */
	public static final String ALGORITHM_FIELD = "algorithm";

	private static final Codec CODEC = new Lucene103Codec() {
		@Override
		public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
			KnnVectorsFormat hnsw = new Lucene99HnswVectorsFormat(200, 100);
			return new HighDimensionKnnVectorsFormat(hnsw, MultiSectorFingerprint.FINGERPRINT_VECTOR_SIZE);
		}
	};

	private final Path indexPath;
	private final ReentrantLock writeLock = new ReentrantLock();

	private Directory directory;
	private IndexWriter writer;
	private SearcherManager searcherManager;
	private volatile boolean available;

	public LuceneSimilarityIndex(Path indexPath) {
		this.indexPath = indexPath;
		init();
	}

	private void init() {
		try {
			Files.createDirectories(indexPath);
			this.directory = new MMapDirectory(indexPath);
			IndexWriterConfig iwc = new IndexWriterConfig()
				.setCodec(CODEC)
				.setOpenMode(OpenMode.CREATE_OR_APPEND);
			this.writer = new IndexWriter(directory, iwc);
			// Ensure a commit point exists so the SearcherManager can open a reader on a fresh index.
			this.writer.commit();
			this.searcherManager = new SearcherManager(writer, null);
			this.available = true;
			log.info("Fingerprint similarity index opened at {}", indexPath);
		} catch (IOException e) {
			log.error("Could not open fingerprint similarity index at {} - similarity queries will be unavailable", indexPath, e);
			closeQuietly();
			this.available = false;
		}
	}

	@Override
	public boolean isAvailable() {
		return available;
	}

	@Override
	public void index(UUID assetUuid, String sha512, String algorithm, float[] vector) {
		if (!available || vector == null) {
			return;
		}
		writeLock.lock();
		try {
			// Re-checked under the lock: a write that passed the check above may have been waiting here while close() ran.
			if (!available) {
				return;
			}
			writer.updateDocument(new Term(ASSET_FIELD, assetUuid.toString()), toDocument(assetUuid, sha512, algorithm, vector));
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Failed to index fingerprint for asset {}: {}", assetUuid, e.getMessage());
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public void index(UUID assetUuid, String sha512, String algorithm, String fingerprintHex) {
		float[] vector = toVector(fingerprintHex);
		if (vector == null) {
			return;
		}
		index(assetUuid, sha512, algorithm, vector);
	}

	@Override
	public void remove(UUID assetUuid) {
		if (!available) {
			return;
		}
		writeLock.lock();
		try {
			if (!available) {
				return;
			}
			writer.deleteDocuments(new Term(ASSET_FIELD, assetUuid.toString()));
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Failed to remove fingerprint for asset {}: {}", assetUuid, e.getMessage());
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public List<SimilarityHit> query(String algorithm, float[] vector, int limit, float scoreThreshold) {
		List<SimilarityHit> hits = new ArrayList<>();
		SearcherManager manager = reader();
		if (manager == null || vector == null || limit <= 0) {
			return hits;
		}
		IndexSearcher searcher = null;
		try {
			manager.maybeRefresh();
			searcher = manager.acquire();
			Query filter = algorithm == null ? null : new TermQuery(new Term(ALGORITHM_FIELD, algorithm));
			Query knn = new KnnFloatVectorQuery(VECTOR_FIELD, vector, limit, filter);
			TopDocs top = searcher.search(knn, limit);
			StoredFields storedFields = searcher.getIndexReader().storedFields();
			for (ScoreDoc sd : top.scoreDocs) {
				if (sd.score < scoreThreshold) {
					continue;
				}
				Document doc = storedFields.document(sd.doc);
				String uuidStr = doc.get(ASSET_FIELD);
				if (uuidStr == null) {
					continue;
				}
				hits.add(new SimilarityHit(UUID.fromString(uuidStr), doc.get(HASH_FIELD), sd.score));
			}
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Similarity query failed: {}", e.getMessage());
		} finally {
			release(manager, searcher);
		}
		return hits;
	}

	@Override
	public List<SimilarityHit> query(String algorithm, String fingerprintHex, int limit, float scoreThreshold) {
		float[] vector = toVector(fingerprintHex);
		return vector == null ? List.of() : query(algorithm, vector, limit, scoreThreshold);
	}

	@Override
	public void rebuildFromHex(Stream<HexFingerprint> all) {
		if (all == null) {
			rebuild(null);
			return;
		}
		// Decode lazily so a large corpus never materialises as vectors in memory all at once.
		rebuild(all.map(fp -> {
			float[] vector = toVector(fp.fingerprint());
			return vector == null ? null : new IndexedFingerprint(fp.assetUuid(), fp.sha512(), fp.algorithm(), vector);
		}).filter(fp -> fp != null));
	}

	/**
	 * Decode the stored hex fingerprint into its k-NN vector. Returns {@code null} (with a warning) when the hex is malformed or has the wrong
	 * dimension - a bad fingerprint must never fail the caller's write.
	 */
	private float[] toVector(String fingerprintHex) {
		if (fingerprintHex == null || fingerprintHex.isBlank() || "NULL".equalsIgnoreCase(fingerprintHex)) {
			return null;
		}
		try {
			float[] vector = MultiSectorFingerprint.of(fingerprintHex).vector();
			if (vector == null || vector.length != MultiSectorFingerprint.FINGERPRINT_VECTOR_SIZE) {
				log.warn("Ignoring fingerprint with unexpected vector length {}", vector == null ? "null" : vector.length);
				return null;
			}
			return vector;
		} catch (Exception e) {
			log.warn("Ignoring malformed fingerprint: {}", e.getMessage());
			return null;
		}
	}

	@Override
	public void rebuild(Stream<IndexedFingerprint> all) {
		if (!available) {
			if (all != null) {
				all.close();
			}
			return;
		}
		writeLock.lock();
		try (Stream<IndexedFingerprint> stream = all) {
			if (!available) {
				return;
			}
			writer.deleteAll();
			if (stream != null) {
				stream.forEach(fp -> {
					try {
						writer.addDocument(toDocument(fp.assetUuid(), fp.sha512(), fp.algorithm(), fp.vector()));
					} catch (IOException e) {
						log.warn("Failed to (re)index fingerprint for asset {}: {}", fp.assetUuid(), e.getMessage());
					}
				});
			}
			writer.commit();
			searcherManager.maybeRefresh();
			log.info("Rebuilt fingerprint similarity index at {}", indexPath);
		} catch (IOException | AlreadyClosedException e) {
			log.error("Failed to rebuild fingerprint similarity index: {}", e.getMessage(), e);
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public void drop(String algorithm) {
		if (!available) {
			return;
		}
		writeLock.lock();
		try {
			if (!available) {
				return;
			}
			// A null algorithm means "everything"; documents written without one carry no ALGORITHM_FIELD and a term query would miss them.
			if (algorithm == null || algorithm.isBlank()) {
				writer.deleteAll();
			} else {
				writer.deleteDocuments(new Term(ALGORITHM_FIELD, algorithm));
			}
			writer.commit();
			searcherManager.maybeRefresh();
			log.info("Dropped fingerprints for algorithm {} from the index at {}", algorithm, indexPath);
		} catch (IOException | AlreadyClosedException e) {
			log.error("Failed to drop fingerprints for algorithm {}: {}", algorithm, e.getMessage(), e);
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public IndexStatus status() {
		IndexStatus status = new IndexStatus().setHealthy(available);
		if (!available) {
			return status.setDetail("The fingerprint index directory could not be opened at " + indexPath);
		}
		try {
			IndexWriter.DocStats stats = writer.getDocStats();
			status.setDocumentCount(stats.numDocs);
			status.setDeletedCount((long) stats.maxDoc - stats.numDocs);
			status.setSizeBytes(directorySize());
		} catch (Exception e) {
			log.warn("Could not read fingerprint index status: {}", e.getMessage());
			status.setHealthy(false).setDetail(e.getMessage());
		}
		return status;
	}

	@Override
	public IndexStatus status(String algorithm) {
		IndexStatus status = new IndexStatus().setHealthy(available);
		SearcherManager manager = reader();
		if (manager == null) {
			return status;
		}
		IndexSearcher searcher = null;
		try {
			manager.maybeRefresh();
			searcher = manager.acquire();
			status.setDocumentCount(algorithm == null || algorithm.isBlank()
				? searcher.getIndexReader().numDocs()
				: searcher.count(new TermQuery(new Term(ALGORITHM_FIELD, algorithm))));
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Could not count fingerprints for algorithm {}: {}", algorithm, e.getMessage());
			status.setHealthy(false).setDetail(e.getMessage());
		} finally {
			release(manager, searcher);
		}
		return status;
	}

	/**
	 * Sum of every file in the index directory. A concurrent merge can unlink a file between listing and measuring it, so a missing file is skipped
	 * rather than failing the read - this is an operator-facing estimate.
	 */
	private long directorySize() throws IOException {
		long bytes = 0;
		for (String file : directory.listAll()) {
			try {
				bytes += directory.fileLength(file);
			} catch (FileNotFoundException | NoSuchFileException e) {
				// Merged away while we were looking at it.
			}
		}
		return bytes;
	}

	/**
	 * Walk the {@code asset_uuid} term dictionary rather than loading stored fields - see
	 * {@link io.metaloom.loom.api.search.VectorIndex#streamIndexedEmbeddingUuids()} for why. The result is a superset that may include
	 * deleted-but-unmerged documents.
	 */
	@Override
	public Stream<UUID> streamIndexedAssetUuids() {
		SearcherManager manager = reader();
		if (manager == null) {
			return Stream.empty();
		}
		IndexSearcher searcher;
		try {
			manager.maybeRefresh();
			searcher = manager.acquire();
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Could not open a reader to enumerate indexed fingerprints: {}", e.getMessage());
			return Stream.empty();
		}
		IndexSearcher acquired = searcher;
		try {
			List<Stream<UUID>> perLeaf = new ArrayList<>();
			for (LeafReaderContext leaf : acquired.getIndexReader().leaves()) {
				Terms terms = leaf.reader().terms(ASSET_FIELD);
				if (terms != null) {
					perLeaf.add(termStream(terms));
				}
			}
			return perLeaf.stream().flatMap(s -> s).onClose(() -> release(manager, acquired));
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Could not enumerate indexed fingerprints: {}", e.getMessage());
			release(manager, acquired);
			return Stream.empty();
		}
	}

	private static Stream<UUID> termStream(Terms terms) throws IOException {
		TermsEnum iterator = terms.iterator();
		Iterator<UUID> uuids = new Iterator<>() {

			private UUID next = advance();

			private UUID advance() {
				try {
					BytesRef term;
					while ((term = iterator.next()) != null) {
						try {
							return UUID.fromString(term.utf8ToString());
						} catch (IllegalArgumentException e) {
							// Not a uuid, so not an asset key; skip rather than abort the sweep.
						}
					}
				} catch (IOException e) {
					log.warn("Failed while walking the asset term dictionary: {}", e.getMessage());
				}
				return null;
			}

			@Override
			public boolean hasNext() {
				return next != null;
			}

			@Override
			public UUID next() {
				if (next == null) {
					throw new NoSuchElementException();
				}
				UUID current = next;
				next = advance();
				return current;
			}
		};
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(uuids, Spliterator.ORDERED | Spliterator.NONNULL), false);
	}

	/**
	 * The searcher manager to read through, or {@code null} once the index is closed or was never opened. Callers capture the returned reference for the
	 * whole read: {@link #close()} nulls the field, so re-reading it mid-query would be a NullPointerException waiting for a shutdown to happen.
	 */
	private SearcherManager reader() {
		SearcherManager manager = searcherManager;
		return available ? manager : null;
	}

	private void release(SearcherManager manager, IndexSearcher searcher) {
		if (manager == null || searcher == null) {
			return;
		}
		try {
			manager.release(searcher);
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Failed to release searcher: {}", e.getMessage());
		}
	}

	@Override
	public String providerName() {
		return "lucene";
	}

	@Override
	public void commit() {
		if (!available) {
			return;
		}
		writeLock.lock();
		try {
			if (!available) {
				return;
			}
			writer.commit();
			searcherManager.maybeRefresh();
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Failed to commit fingerprint similarity index: {}", e.getMessage());
		} finally {
			writeLock.unlock();
		}
	}

	private Document toDocument(UUID assetUuid, String sha512, String algorithm, float[] vector) {
		Document doc = new Document();
		doc.add(new StringField(ASSET_FIELD, assetUuid.toString(), Field.Store.YES));
		if (algorithm != null) {
			doc.add(new StringField(ALGORITHM_FIELD, algorithm, Field.Store.YES));
		}
		if (sha512 != null) {
			doc.add(new StoredField(HASH_FIELD, sha512));
		}
		doc.add(new KnnFloatVectorField(VECTOR_FIELD, vector));
		return doc;
	}

	/**
	 * Release the searcher manager, writer and directory, dropping the {@code write.lock} the {@link IndexWriter} holds on the index directory.
	 *
	 * <p>
	 * Idempotent: a second call, or a call on an index that never opened, does nothing. {@code available} is cleared <b>before</b> anything is closed, so
	 * a writer waiting on {@link #writeLock} sees the flag and no-ops instead of touching a closed {@link IndexWriter}.
	 * </p>
	 */
	@Override
	public void close() {
		writeLock.lock();
		try {
			if (!available) {
				return;
			}
			available = false;
			closeQuietly();
			log.info("Fingerprint similarity index closed at {}", indexPath);
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Close whatever is open, in dependency order, without letting one failure skip the rest - a directory left open keeps the {@code write.lock} file,
	 * which is the whole reason this runs.
	 */
	private void closeQuietly() {
		if (searcherManager != null) {
			try {
				searcherManager.close();
			} catch (IOException | AlreadyClosedException e) {
				log.warn("Failed to close searcher manager: {}", e.getMessage());
			}
			searcherManager = null;
		}
		if (writer != null) {
			try {
				// IndexWriter.close() commits pending changes on the way out; an abrupt exit is what leaves segments behind.
				if (writer.isOpen()) {
					writer.close();
				}
			} catch (IOException | AlreadyClosedException e) {
				log.warn("Failed to close index writer: {}", e.getMessage());
			}
			writer = null;
		}
		if (directory != null) {
			try {
				directory.close();
			} catch (IOException | AlreadyClosedException e) {
				log.warn("Failed to close directory: {}", e.getMessage());
			}
			directory = null;
		}
	}
}
