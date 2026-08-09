package io.metaloom.loom.db.jooq.search;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.api.search.TextEmbedder;
import io.metaloom.loom.db.model.embedding.Embedding;
import io.metaloom.loom.db.model.embedding.EmbeddingDao;

/**
 * Keeps one text embedding per asset in step with that asset's {@code search_document}.
 *
 * <p>
 * <b>The corpus is the document lexical search already assembles.</b> Nothing new is extracted, no pipeline is re-run and no node has to exist: the
 * triggers behind {@code search_document} already gather an asset's filename, transcripts, OCR, captions, detections and tags into one text, and that
 * text is what gets embedded. A transcript arriving later refreshes the document, which makes the embedding stale, which brings it back through here -
 * the freshness signal is the one the lexical index already maintains.
 * </p>
 *
 * <p>
 * <b>Vectors land in {@code embedding} like any other, and are indexed by the machinery that already exists.</b> Rows are written with
 * {@code node_kind='search'} and {@code type=LOOM_SEARCH_VECTOR_TYPE}, start {@code dirty}, and are drained into the {@code VectorIndex} by
 * {@code EmbeddingIndexSyncService} exactly as face vectors are. So this class writes no index, owns no schema and adds no migration - the only new
 * thing in the write path is the call to the embedding model.
 * </p>
 *
 * <p>
 * <b>Only asset documents participate.</b> {@code embedding.asset_uuid} is {@code NOT NULL} and a tag, collection or library document has no asset, so
 * those are not embedded and semantic hits are always assets. That is a limit worth stating rather than hiding: lexical search still finds a tag by
 * name, which is the argument for {@code HYBRID} being the interesting mode rather than {@code SEMANTIC} alone.
 * </p>
 */
@Singleton
public class SearchEmbeddingService {

	private static final Logger log = LoggerFactory.getLogger(SearchEmbeddingService.class);

	/**
	 * Asset documents whose embedding is missing or older than the document.
	 *
	 * <p>
	 * {@code edited} is refreshed by the upsert, so a re-embedded row stops matching this immediately - without that the same documents would be
	 * embedded on every pass forever, which is a cost that would only show up as an inference bill.
	 * </p>
	 */
	private static final String STALE_SQL = """
		SELECT sd.entity_uuid AS asset_uuid, sd.title, sd.keywords, sd.body
		  FROM search_document sd
		  LEFT JOIN embedding e
		         ON e.asset_uuid = sd.entity_uuid
		        AND e.node_kind = ?
		        AND e.type = ?
		        AND e.model = ?
		        AND e.frame_number = 0
		        AND e.subject_index = 0
		 WHERE sd.entity_type = 'asset'
		   AND (e.uuid IS NULL OR e.edited < sd.synced_at)
		 ORDER BY sd.synced_at
		 LIMIT ?
		""";

	private static final String PENDING_SQL = """
		SELECT count(*) AS c
		  FROM search_document sd
		  LEFT JOIN embedding e
		         ON e.asset_uuid = sd.entity_uuid
		        AND e.node_kind = ?
		        AND e.type = ?
		        AND e.model = ?
		        AND e.frame_number = 0
		        AND e.subject_index = 0
		 WHERE sd.entity_type = 'asset'
		   AND (e.uuid IS NULL OR e.edited < sd.synced_at)
		""";

	private final DSLContext ctx;
	private final EmbeddingDao embeddingDao;
	private final TextEmbedder embedder;
	private final SearchOptions options;

	@Inject
	public SearchEmbeddingService(DSLContext ctx, EmbeddingDao embeddingDao, TextEmbedder embedder, SearchOptions options) {
		this.ctx = ctx;
		this.embeddingDao = embeddingDao;
		this.embedder = embedder;
		this.options = options;
	}

	/** Whether there is anything to embed with. Never throws. */
	public boolean isReady() {
		try {
			return options.isSemanticEnabled() && embedder.isAvailable();
		} catch (Exception e) {
			return false;
		}
	}

	/** How many asset documents are waiting to be embedded. Reported by the status route so a backlog is visible. */
	public long pendingCount() {
		try {
			Record record = ctx.fetchOne(PENDING_SQL, SearchOptions.VECTOR_NODE_KIND, options.getVectorType(), model());
			return record == null ? 0 : record.get("c", Long.class);
		} catch (Exception e) {
			log.warn("Could not count pending search embeddings: {}", e.getMessage());
			return 0;
		}
	}

	/**
	 * Embed up to {@code limit} stale documents.
	 *
	 * <p>
	 * A batch that fails is logged and abandoned rather than retried here: the documents stay stale, so the next pass picks them up. Retrying inline
	 * would turn a down inference host into a hot loop against it.
	 * </p>
	 *
	 * @return how many embeddings were written
	 */
	public int embedStale(int limit) {
		if (!isReady() || limit <= 0) {
			return 0;
		}
		Result<Record> stale;
		try {
			stale = ctx.fetch(STALE_SQL, SearchOptions.VECTOR_NODE_KIND, options.getVectorType(), model(), limit);
		} catch (Exception e) {
			log.warn("Could not read stale search documents: {}", e.getMessage());
			return 0;
		}
		if (stale.isEmpty()) {
			return 0;
		}

		int written = 0;
		int batchSize = Math.max(1, options.getEmbedBatchSize());
		for (int start = 0; start < stale.size(); start += batchSize) {
			List<Record> batch = stale.subList(start, Math.min(start + batchSize, stale.size()));
			written += embedBatch(batch);
		}
		if (written > 0) {
			log.debug("Embedded {} search document(s)", written);
		}
		return written;
	}

	/**
	 * Re-embed everything, in passes, until nothing is stale.
	 *
	 * <p>
	 * This is the "the model changed" path. It needs no deletion step: {@code model} is part of the embedding identity key, so a new model writes rows
	 * beside the old ones and both spaces are queryable until the old one is dropped. Bounded by a pass count so a document that fails to embed every
	 * time cannot spin here forever.
	 * </p>
	 *
	 * @return how many embeddings were written
	 */
	public long embedAllStale(int maxPasses) {
		long total = 0;
		for (int pass = 0; pass < maxPasses; pass++) {
			int written = embedStale(Math.max(options.getEmbedBatchSize(), 100));
			if (written == 0) {
				break;
			}
			total += written;
		}
		return total;
	}

	// ---------------------------------------------------------------------------------------------

	private int embedBatch(List<Record> batch) {
		List<String> texts = new ArrayList<>(batch.size());
		for (Record record : batch) {
			texts.add(documentText(record));
		}
		List<float[]> vectors;
		try {
			vectors = embedder.embedAll(texts);
		} catch (Exception e) {
			log.warn("Failed to embed a batch of {} search document(s): {}", batch.size(), e.getMessage());
			return 0;
		}
		int written = 0;
		for (int i = 0; i < batch.size(); i++) {
			UUID assetUuid = batch.get(i).get("asset_uuid", UUID.class);
			try {
				embeddingDao.upsertEmbedding(toEmbedding(assetUuid, vectors.get(i)));
				written++;
			} catch (Exception e) {
				// One asset failing must not lose the rest of the batch's inference work.
				log.warn("Failed to store the search embedding for asset {}: {}", assetUuid, e.getMessage());
			}
		}
		return written;
	}

	/**
	 * The text handed to the model.
	 *
	 * <p>
	 * Title, keywords and body - deliberately <em>not</em> {@code subtitle}, which holds every filesystem path the asset has ever had. Paths are
	 * excellent lexical signal, which is why they stay in the tsvector, and close to pure noise for a sentence model: a hundred tokens of directory
	 * names pull the vector toward whatever the folder structure looks like and away from what the asset is about.
	 * </p>
	 */
	private String documentText(Record record) {
		StringBuilder text = new StringBuilder();
		append(text, record.get("title", String.class));
		append(text, record.get("keywords", String.class));
		append(text, record.get("body", String.class));
		String value = text.toString();
		int max = options.getEmbedMaxChars();
		return value.length() <= max ? value : value.substring(0, max);
	}

	private static void append(StringBuilder target, String value) {
		if (value != null && !value.isBlank()) {
			if (target.length() > 0) {
				target.append('\n');
			}
			target.append(value);
		}
	}

	private Embedding toEmbedding(UUID assetUuid, float[] vector) {
		Float[] boxed = new Float[vector.length];
		for (int i = 0; i < vector.length; i++) {
			boxed[i] = vector[i];
		}
		return embeddingDao.createEmbedding(null, assetUuid, boxed, options.getVectorType())
			.setNodeKind(SearchOptions.VECTOR_NODE_KIND)
			.setModel(model())
			.setDimensions(vector.length)
			.setFrameNumber(0)
			.setSubjectIndex(0)
			// The embedder unit-normalizes, so cosine and inner product rank identically. Recording it is
			// what makes that auditable instead of an assumption the ranking quietly depends on.
			.setNormalized(Boolean.TRUE)
			.setDirty(Boolean.TRUE);
	}

	/** The model discriminator: part of the embedding identity key, so two models coexist rather than overwrite. */
	private String model() {
		return embedder.space().model();
	}
}
