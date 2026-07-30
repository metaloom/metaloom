package io.metaloom.loom.similarity.lucene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

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
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
			writer.updateDocument(new Term(ASSET_FIELD, assetUuid.toString()), toDocument(assetUuid, sha512, algorithm, vector));
		} catch (IOException e) {
			log.warn("Failed to index fingerprint for asset {}: {}", assetUuid, e.getMessage());
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public void remove(UUID assetUuid) {
		if (!available) {
			return;
		}
		writeLock.lock();
		try {
			writer.deleteDocuments(new Term(ASSET_FIELD, assetUuid.toString()));
		} catch (IOException e) {
			log.warn("Failed to remove fingerprint for asset {}: {}", assetUuid, e.getMessage());
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public List<SimilarityHit> query(String algorithm, float[] vector, int limit, float scoreThreshold) {
		List<SimilarityHit> hits = new ArrayList<>();
		if (!available || vector == null || limit <= 0) {
			return hits;
		}
		IndexSearcher searcher = null;
		try {
			searcherManager.maybeRefresh();
			searcher = searcherManager.acquire();
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
		} catch (IOException e) {
			log.warn("Similarity query failed: {}", e.getMessage());
		} finally {
			if (searcher != null) {
				try {
					searcherManager.release(searcher);
				} catch (IOException e) {
					log.warn("Failed to release searcher: {}", e.getMessage());
				}
			}
		}
		return hits;
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
		} catch (IOException e) {
			log.error("Failed to rebuild fingerprint similarity index: {}", e.getMessage(), e);
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public void commit() {
		if (!available) {
			return;
		}
		writeLock.lock();
		try {
			writer.commit();
			searcherManager.maybeRefresh();
		} catch (IOException e) {
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

	@Override
	public void close() {
		writeLock.lock();
		try {
			closeQuietly();
			available = false;
		} finally {
			writeLock.unlock();
		}
	}

	private void closeQuietly() {
		if (searcherManager != null) {
			try {
				searcherManager.close();
			} catch (IOException e) {
				log.warn("Failed to close searcher manager: {}", e.getMessage());
			}
			searcherManager = null;
		}
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException e) {
				log.warn("Failed to close index writer: {}", e.getMessage());
			}
			writer = null;
		}
		if (directory != null) {
			try {
				directory.close();
			} catch (IOException e) {
				log.warn("Failed to close directory: {}", e.getMessage());
			}
			directory = null;
		}
	}
}
