package io.metaloom.loom.rest.search;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.SearchOptions;
import io.metaloom.loom.db.jooq.search.SearchEmbeddingService;
import io.vertx.core.Vertx;

/**
 * Periodically embeds the search documents whose text has changed since they were last embedded.
 *
 * <p>
 * This is the semantic counterpart to the trigger that keeps {@code search_document} current, and it cannot be a trigger for the obvious reason: it
 * calls a model over the network, which has no business happening inside the transaction that saved an asset. The lag is the price of that, and it is
 * bounded by {@code LOOM_SEARCH_EMBED_SYNC_INTERVAL_MS}.
 * </p>
 *
 * <p>
 * The rows it writes are indexed by the drain that already exists ({@code EmbeddingIndexDrainer}), so a newly ingested asset becomes semantically
 * findable after two short intervals rather than one. Both are best-effort and idempotent: nothing is lost if either misses a pass, because staleness
 * is derived from the data rather than from a queue that could be dropped.
 * </p>
 *
 * <p>
 * Runs off the event loop via {@code executeBlocking} - it does database I/O and a blocking HTTP call. Started at boot from the bootstrap initializer
 * and stopped on shutdown, mirroring {@code EmbeddingIndexDrainer}.
 * </p>
 */
@Singleton
public class SearchEmbeddingDrainer {

	private static final Logger log = LoggerFactory.getLogger(SearchEmbeddingDrainer.class);

	private final Vertx vertx;
	private final SearchEmbeddingService service;
	private final SearchOptions options;

	private volatile long timerId = -1;

	/**
	 * Whether a pass is already running.
	 *
	 * <p>
	 * {@link io.vertx.core.Vertx#setPeriodic} fires on a clock, not on completion, so without this a pass that takes longer than the interval is
	 * simply joined by the next one. That is not a slow drain, it is a pile-up: on metaloom.sky a 5s interval against a CPU embedding host built up
	 * two dozen concurrent 32-document requests, every one of them queued behind the others inside the host, until they all breached the client
	 * timeout in the same millisecond and the whole backlog made no progress at all. Worse, abandoning a queued request does not cancel the work the
	 * host is already committed to, so the overload feeds itself.
	 * </p>
	 *
	 * <p>
	 * Skipping a tick is always safe here: the documents stay stale and the next tick picks them up. That is the same reasoning
	 * {@code SearchEmbeddingService} gives for abandoning a failed batch rather than retrying it inline.
	 * </p>
	 */
	private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

	@Inject
	public SearchEmbeddingDrainer(Vertx vertx, SearchEmbeddingService service, SearchOptions options) {
		this.vertx = vertx;
		this.service = service;
		this.options = options;
	}

	/** Start the periodic pass. No-op when semantic search is off, the interval is 0, or it is already running. */
	public synchronized void start() {
		if (!service.isReady()) {
			log.debug("Semantic search is not configured - the document embedding pass was not started");
			return;
		}
		if (options.getEmbedSyncIntervalMs() <= 0) {
			log.info("The document embedding pass is disabled (LOOM_SEARCH_EMBED_SYNC_INTERVAL_MS=0); embeddings must be built by a rebuild");
			return;
		}
		if (timerId != -1) {
			return;
		}
		timerId = vertx.setPeriodic(options.getEmbedSyncIntervalMs(), id -> {
			if (!running.compareAndSet(false, true)) {
				// The previous pass is still in flight. See the field comment: overlapping passes against a
				// slow embedding host do not drain faster, they deadlock the drain.
				log.debug("Skipping the document embedding pass - the previous one is still running");
				return;
			}
			vertx.executeBlocking(() -> {
				try {
					service.embedStale(options.getEmbedBatchSize());
				} catch (RuntimeException e) {
					log.warn("The document embedding pass failed", e);
				} finally {
					running.set(false);
				}
				return null;
			}, false);
		});
		log.info("Document embedding pass started (interval={}ms, batch={})", options.getEmbedSyncIntervalMs(), options.getEmbedBatchSize());
	}

	public synchronized void stop() {
		if (timerId != -1) {
			vertx.cancelTimer(timerId);
			timerId = -1;
		}
	}
}
