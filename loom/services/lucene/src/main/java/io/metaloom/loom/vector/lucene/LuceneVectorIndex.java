package io.metaloom.loom.vector.lucene;

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
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
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

import io.metaloom.loom.api.search.VectorHit;
import io.metaloom.loom.api.search.VectorIndex;
import io.metaloom.loom.api.search.VectorQuery;
import io.metaloom.loom.api.search.VectorRecord;
import io.metaloom.loom.api.search.VectorSpace;

/**
 * Lucene HNSW implementation of {@link VectorIndex}.
 *
 * <p>
 * <b>Its own index directory, separate from {@code LuceneSimilarityIndex}.</b> They share a module and a codec approach but nothing else: the
 * fingerprint index holds one 256-dim vector per asset and answers "is this the same recording?", while this one holds many vectors per asset - one
 * per face, per frame - and answers "is this the same subject?". Merging them would mean one k-NN field carrying two incompatible vector populations,
 * and one rebuild that has to walk two unrelated tables.
 * </p>
 *
 * <h2>How a model change is absorbed</h2>
 *
 * <p>
 * Vectors are written to a <b>dimension-suffixed field</b>, {@code vec_512} for a 512-d model. Lucene's {@link KnnFloatVectorField} is fixed-dimension
 * per field name - the same constraint that forces pgvector to use one table per dimension - so a shared field name would make a 128-d model
 * unindexable the moment a 512-d one existed. With the suffix, a new model of a different size simply lands in a new field in the same index, and one
 * of a matching size is separated by the {@code type}/{@code model} filter terms carried on every document and applied to every query. Two models
 * coexist, and no query can be answered across them.
 * </p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>
 * Lucene's {@link IndexWriter} is single-writer, so all mutations serialize through {@link #writeLock}. Reads go through a {@link SearcherManager} for
 * near-real-time visibility. The index is a derived cache of {@code embedding.vector} - losing it costs a {@link #rebuild(Stream)}, never data.
 * </p>
 */
public class LuceneVectorIndex implements VectorIndex, AutoCloseable {

	public static final Logger log = LoggerFactory.getLogger(LuceneVectorIndex.class);

	/** Indexed + stored embedding uuid; the upsert / delete key. */
	public static final String EMBEDDING_FIELD = "embedding_uuid";
	/** Indexed + stored asset uuid; the asset-delete key and the query exclusion filter. */
	public static final String ASSET_FIELD = "asset_uuid";
	/** Stored detection uuid, so a hit resolves to a region rather than only to an asset. */
	public static final String DETECTION_FIELD = "detection_uuid";
	/** Indexed type filter term, e.g. "face". */
	public static final String TYPE_FIELD = "type";
	/** Indexed model filter term, e.g. "inspireface-r18". */
	public static final String MODEL_FIELD = "model";

	/**
	 * Lucene's own default ceiling for {@link Lucene99HnswVectorsFormat}. Above it a wrapping format is required; the face vectors in use today are well
	 * below it, and exceeding it is reported rather than silently failing at write time.
	 */
	private static final int MAX_DIMENSIONS = 1024;

	private static final Codec CODEC = new Lucene103Codec() {
		@Override
		public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
			return new Lucene99HnswVectorsFormat(200, 100);
		}
	};

	private final Path indexPath;
	private final ReentrantLock writeLock = new ReentrantLock();

	private Directory directory;
	private IndexWriter writer;
	private SearcherManager searcherManager;
	private volatile boolean available;

	public LuceneVectorIndex(Path indexPath) {
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
			log.info("Vector index opened at {}", indexPath);
		} catch (IOException e) {
			log.error("Could not open vector index at {} - vector queries will be unavailable", indexPath, e);
			closeQuietly();
			this.available = false;
		}
	}

	/**
	 * The k-NN field for a given vector length. Lucene fixes the dimension per field name, so the length has to be part of it.
	 */
	static String vectorField(int dimensions) {
		return "vec_" + dimensions;
	}

	@Override
	public boolean isAvailable() {
		return available;
	}

	@Override
	public String providerName() {
		return "lucene";
	}

	@Override
	public void index(VectorRecord record) {
		if (!available || record == null) {
			return;
		}
		if (record.space().dimensions() > MAX_DIMENSIONS) {
			log.warn("Refusing to index embedding {}: {} dimensions exceeds the {} the HNSW format supports",
				record.embeddingUuid(), record.space().dimensions(), MAX_DIMENSIONS);
			return;
		}
		writeLock.lock();
		try {
			writer.updateDocument(new Term(EMBEDDING_FIELD, record.embeddingUuid().toString()), toDocument(record));
		} catch (IOException e) {
			log.warn("Failed to index embedding {}: {}", record.embeddingUuid(), e.getMessage());
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public void indexAll(List<VectorRecord> records) {
		if (!available || records == null || records.isEmpty()) {
			return;
		}
		writeLock.lock();
		try {
			for (VectorRecord record : records) {
				index(record);
			}
			writer.commit();
			searcherManager.maybeRefresh();
		} catch (IOException e) {
			log.warn("Failed to commit vector index batch: {}", e.getMessage());
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public void removeByEmbedding(UUID embeddingUuid) {
		delete(new Term(EMBEDDING_FIELD, embeddingUuid.toString()), "embedding " + embeddingUuid);
	}

	@Override
	public void removeByAsset(UUID assetUuid) {
		delete(new Term(ASSET_FIELD, assetUuid.toString()), "asset " + assetUuid);
	}

	private void delete(Term term, String what) {
		if (!available) {
			return;
		}
		writeLock.lock();
		try {
			writer.deleteDocuments(term);
		} catch (IOException e) {
			log.warn("Failed to remove vectors for {}: {}", what, e.getMessage());
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public List<VectorHit> query(VectorQuery query) {
		List<VectorHit> hits = new ArrayList<>();
		if (!available || query == null) {
			return hits;
		}
		IndexSearcher searcher = null;
		try {
			searcherManager.maybeRefresh();
			searcher = searcherManager.acquire();
			TopDocs top = searcher.search(knnQuery(query), query.limit());
			StoredFields storedFields = searcher.getIndexReader().storedFields();
			for (ScoreDoc sd : top.scoreDocs) {
				if (sd.score < query.scoreThreshold()) {
					continue;
				}
				Document doc = storedFields.document(sd.doc);
				String embeddingUuid = doc.get(EMBEDDING_FIELD);
				String assetUuid = doc.get(ASSET_FIELD);
				if (embeddingUuid == null || assetUuid == null) {
					continue;
				}
				String detectionUuid = doc.get(DETECTION_FIELD);
				hits.add(new VectorHit(UUID.fromString(embeddingUuid), UUID.fromString(assetUuid),
					detectionUuid == null ? null : UUID.fromString(detectionUuid), sd.score));
			}
		} catch (IOException e) {
			log.warn("Vector query failed: {}", e.getMessage());
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

	/**
	 * Build the k-NN query, scoped to the requested space.
	 *
	 * <p>
	 * The dimension is enforced structurally by the field name and the type and model by filter terms, so a query cannot reach a vector produced by a
	 * different model - the failure this guards against returns a plausible number rather than an error, and would otherwise be invisible.
	 * </p>
	 */
	private Query knnQuery(VectorQuery query) {
		VectorSpace space = query.space();
		BooleanQuery.Builder filter = new BooleanQuery.Builder();
		filter.add(new TermQuery(new Term(TYPE_FIELD, space.type())), Occur.FILTER);
		filter.add(new TermQuery(new Term(MODEL_FIELD, space.model())), Occur.FILTER);
		if (query.excludeAssetUuid() != null) {
			filter.add(new TermQuery(new Term(ASSET_FIELD, query.excludeAssetUuid().toString())), Occur.MUST_NOT);
		}
		return new KnnFloatVectorQuery(vectorField(space.dimensions()), query.vector(), query.limit(), filter.build());
	}

	@Override
	public void rebuild(Stream<VectorRecord> all) {
		if (!available) {
			if (all != null) {
				all.close();
			}
			return;
		}
		writeLock.lock();
		try (Stream<VectorRecord> stream = all) {
			writer.deleteAll();
			if (stream != null) {
				stream.forEach(this::addQuietly);
			}
			writer.commit();
			searcherManager.maybeRefresh();
			log.info("Rebuilt vector index at {}", indexPath);
		} catch (IOException e) {
			log.error("Failed to rebuild vector index: {}", e.getMessage(), e);
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Add during a rebuild. Uses {@code addDocument} rather than {@code updateDocument} because the index was just cleared, so there is nothing to
	 * replace and the term lookup would be wasted work on every one of potentially millions of rows.
	 */
	private void addQuietly(VectorRecord record) {
		if (record.space().dimensions() > MAX_DIMENSIONS) {
			log.warn("Skipping embedding {} during rebuild: {} dimensions exceeds the supported {}",
				record.embeddingUuid(), record.space().dimensions(), MAX_DIMENSIONS);
			return;
		}
		try {
			writer.addDocument(toDocument(record));
		} catch (IOException e) {
			log.warn("Failed to (re)index embedding {}: {}", record.embeddingUuid(), e.getMessage());
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
			log.warn("Failed to commit vector index: {}", e.getMessage());
		} finally {
			writeLock.unlock();
		}
	}

	private Document toDocument(VectorRecord record) {
		VectorSpace space = record.space();
		Document doc = new Document();
		doc.add(new StringField(EMBEDDING_FIELD, record.embeddingUuid().toString(), Field.Store.YES));
		doc.add(new StringField(ASSET_FIELD, record.assetUuid().toString(), Field.Store.YES));
		doc.add(new StringField(TYPE_FIELD, space.type(), Field.Store.YES));
		doc.add(new StringField(MODEL_FIELD, space.model(), Field.Store.YES));
		if (record.detectionUuid() != null) {
			doc.add(new StoredField(DETECTION_FIELD, record.detectionUuid().toString()));
		}
		doc.add(new KnnFloatVectorField(vectorField(space.dimensions()), record.vector()));
		return doc;
	}

	@Override
	public void close() {
		writeLock.lock();
		try {
			closeQuietly();
		} finally {
			available = false;
			writeLock.unlock();
		}
	}

	private void closeQuietly() {
		try {
			if (searcherManager != null) {
				searcherManager.close();
			}
		} catch (IOException e) {
			log.warn("Failed to close searcher manager: {}", e.getMessage());
		}
		try {
			if (writer != null && writer.isOpen()) {
				writer.close();
			}
		} catch (IOException e) {
			log.warn("Failed to close vector index writer: {}", e.getMessage());
		}
		try {
			if (directory != null) {
				directory.close();
			}
		} catch (IOException e) {
			log.warn("Failed to close vector index directory: {}", e.getMessage());
		}
		searcherManager = null;
		writer = null;
		directory = null;
	}
}
