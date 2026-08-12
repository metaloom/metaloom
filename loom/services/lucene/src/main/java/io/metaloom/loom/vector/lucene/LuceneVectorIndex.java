package io.metaloom.loom.vector.lucene;

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
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldExistsQuery;
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

import io.metaloom.loom.api.search.IndexStatus;
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
 *
 * <h2>Shutdown</h2>
 *
 * <p>
 * {@link #close()} is called from the server shutdown sequence and is idempotent. Every method re-reads {@link #available} <i>inside</i> the write lock
 * (or captures the {@link SearcherManager} before using it) so a request still in flight when the index closes degrades to a no-op or an empty result
 * instead of an {@link AlreadyClosedException} on the way out.
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
			// Re-checked under the lock: a write that passed the check above may have been waiting here while close() ran.
			if (!available) {
				return;
			}
			writer.updateDocument(new Term(EMBEDDING_FIELD, record.embeddingUuid().toString()), toDocument(record));
		} catch (IOException | AlreadyClosedException e) {
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
			if (!available) {
				return;
			}
			for (VectorRecord record : records) {
				index(record);
			}
			writer.commit();
			searcherManager.maybeRefresh();
		} catch (IOException | AlreadyClosedException e) {
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
			if (!available) {
				return;
			}
			writer.deleteDocuments(term);
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Failed to remove vectors for {}: {}", what, e.getMessage());
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public List<VectorHit> query(VectorQuery query) {
		List<VectorHit> hits = new ArrayList<>();
		SearcherManager manager = reader();
		if (manager == null || query == null) {
			return hits;
		}
		IndexSearcher searcher = null;
		try {
			manager.maybeRefresh();
			searcher = manager.acquire();
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
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Vector query failed: {}", e.getMessage());
		} finally {
			release(manager, searcher);
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
			if (!available) {
				return;
			}
			writer.deleteAll();
			if (stream != null) {
				stream.forEach(this::addQuietly);
			}
			writer.commit();
			searcherManager.maybeRefresh();
			log.info("Rebuilt vector index at {}", indexPath);
		} catch (IOException | AlreadyClosedException e) {
			log.error("Failed to rebuild vector index: {}", e.getMessage(), e);
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public void drop(VectorSpace space) {
		if (!available || space == null) {
			return;
		}
		writeLock.lock();
		try {
			if (!available) {
				return;
			}
			writer.deleteDocuments(spaceQuery(space));
			writer.commit();
			searcherManager.maybeRefresh();
			log.info("Dropped every vector in space {} from the index at {}", space.key(), indexPath);
		} catch (IOException | AlreadyClosedException e) {
			log.error("Failed to drop vector space {}: {}", space.key(), e.getMessage(), e);
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * A filter matching exactly the documents of one space.
	 *
	 * <p>
	 * The dimension needs its own clause. It is carried structurally by the k-NN field name rather than as a filter term, so a type + model match alone
	 * would also select a same-named model of a different length - which is precisely the pair {@link VectorSpace} exists to keep apart. A
	 * {@link FieldExistsQuery} on the dimension-suffixed field restores that distinction without adding a field to every document.
	 * </p>
	 */
	private static Query spaceQuery(VectorSpace space) {
		return new BooleanQuery.Builder()
			.add(new TermQuery(new Term(TYPE_FIELD, space.type())), Occur.FILTER)
			.add(new TermQuery(new Term(MODEL_FIELD, space.model())), Occur.FILTER)
			.add(new FieldExistsQuery(vectorField(space.dimensions())), Occur.FILTER)
			.build();
	}

	@Override
	public IndexStatus status() {
		IndexStatus status = new IndexStatus().setHealthy(available);
		if (!available) {
			return status.setDetail("The vector index directory could not be opened at " + indexPath);
		}
		try {
			IndexWriter.DocStats stats = writer.getDocStats();
			status.setDocumentCount(stats.numDocs);
			status.setDeletedCount((long) stats.maxDoc - stats.numDocs);
			status.setSizeBytes(directorySize());
		} catch (Exception e) {
			// Status is read precisely when something is wrong, so a failure here reports rather than throws.
			log.warn("Could not read vector index status: {}", e.getMessage());
			status.setHealthy(false).setDetail(e.getMessage());
		}
		return status;
	}

	@Override
	public IndexStatus status(VectorSpace space) {
		IndexStatus status = new IndexStatus().setHealthy(available);
		SearcherManager manager = reader();
		if (manager == null || space == null) {
			return status;
		}
		IndexSearcher searcher = null;
		try {
			manager.maybeRefresh();
			searcher = manager.acquire();
			status.setDocumentCount(searcher.count(spaceQuery(space)));
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Could not count vectors in space {}: {}", space.key(), e.getMessage());
			status.setHealthy(false).setDetail(e.getMessage());
		} finally {
			release(manager, searcher);
		}
		return status;
	}

	/**
	 * Sum of every file in the index directory.
	 *
	 * <p>
	 * A concurrent merge can unlink a file between listing it and measuring it, so a missing file is skipped rather than failing the whole read - the
	 * number is an operator-facing estimate, not an accounting figure.
	 * </p>
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
	 * Walk the {@code embedding_uuid} term dictionary.
	 *
	 * <p>
	 * The terms are read rather than the stored fields because the alternative - loading every document to read one string back out - decompresses the
	 * whole stored-field block per document, which on a multi-million-vector index is the difference between a sweep an operator can run and one they
	 * cannot. The consequence is that terms of deleted-but-unmerged documents are still visible; the caller is told to expect a superset, and deleting
	 * an already-deleted document is a no-op, so filtering them here would cost a per-document liveDocs lookup to prevent nothing.
	 * </p>
	 */
	@Override
	public Stream<UUID> streamIndexedEmbeddingUuids() {
		SearcherManager manager = reader();
		if (manager == null) {
			return Stream.empty();
		}
		IndexSearcher searcher;
		try {
			manager.maybeRefresh();
			searcher = manager.acquire();
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Could not open a reader to enumerate indexed embeddings: {}", e.getMessage());
			return Stream.empty();
		}
		IndexSearcher acquired = searcher;
		try {
			List<Stream<UUID>> perLeaf = new ArrayList<>();
			for (LeafReaderContext leaf : acquired.getIndexReader().leaves()) {
				Terms terms = leaf.reader().terms(EMBEDDING_FIELD);
				if (terms != null) {
					perLeaf.add(termStream(terms));
				}
			}
			return perLeaf.stream().flatMap(s -> s).onClose(() -> release(manager, acquired));
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Could not enumerate indexed embeddings: {}", e.getMessage());
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
							// A term that is not a uuid cannot correspond to an embedding row; skip it rather than abort the sweep.
						}
					}
				} catch (IOException e) {
					log.warn("Failed while walking the embedding term dictionary: {}", e.getMessage());
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
			if (!available) {
				return;
			}
			writer.commit();
			searcherManager.maybeRefresh();
		} catch (IOException | AlreadyClosedException e) {
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
			log.info("Vector index closed at {}", indexPath);
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Close whatever is open, in dependency order, without letting one failure skip the rest - a directory left open keeps the {@code write.lock} file,
	 * which is the whole reason this runs.
	 */
	private void closeQuietly() {
		try {
			if (searcherManager != null) {
				searcherManager.close();
			}
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Failed to close searcher manager: {}", e.getMessage());
		}
		try {
			// IndexWriter.close() commits pending changes on the way out; an abrupt exit is what leaves segments behind.
			if (writer != null && writer.isOpen()) {
				writer.close();
			}
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Failed to close vector index writer: {}", e.getMessage());
		}
		try {
			if (directory != null) {
				directory.close();
			}
		} catch (IOException | AlreadyClosedException e) {
			log.warn("Failed to close vector index directory: {}", e.getMessage());
		}
		searcherManager = null;
		writer = null;
		directory = null;
	}
}
