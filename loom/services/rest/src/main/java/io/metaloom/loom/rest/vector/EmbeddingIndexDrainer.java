package io.metaloom.loom.rest.vector;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.VectorIndexOptions;
import io.vertx.core.Vertx;

/**
 * Periodically drains embeddings still marked {@code dirty} into the vector index.
 *
 * <p>
 * The write hook in {@code EmbeddingEndpointService} already indexes rows as they are created, so in normal operation this finds nothing to do. It
 * exists for the cases the hook cannot cover: a crash between the database commit and the index write, a period when the index was unavailable or
 * switched off, or an index write that failed for its own reasons. In each case the rows stayed dirty, and this is what eventually picks them up -
 * without it, "best-effort index write" would quietly mean "sometimes never indexed".
 * </p>
 *
 * <p>
 * Runs off the event loop via {@code executeBlocking}: the drain does database and disk I/O. Started at boot from the bootstrap initializer and
 * stopped on shutdown, mirroring {@code SandboxReaper}.
 * </p>
 */
@Singleton
public class EmbeddingIndexDrainer {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexDrainer.class);

	private final Vertx vertx;
	private final EmbeddingIndexSyncService sync;
	private final VectorIndexOptions options;

	private volatile long timerId = -1;

	@Inject
	public EmbeddingIndexDrainer(Vertx vertx, EmbeddingIndexSyncService sync, VectorIndexOptions options) {
		this.vertx = vertx;
		this.sync = sync;
		this.options = options;
	}

	/** Start the periodic drain. No-op when no index backend is bound, when the interval is 0, or when already running. */
	public synchronized void start() {
		if (!options.isEnabled() || !sync.index().isAvailable()) {
			log.debug("Vector index unavailable - drain not started");
			return;
		}
		if (options.getSyncIntervalMs() <= 0) {
			log.info("Vector index drain disabled (LOOM_VECTOR_INDEX_SYNC_INTERVAL_MS=0); relying on the write hook and manual rebuilds");
			return;
		}
		if (timerId != -1) {
			return;
		}
		timerId = vertx.setPeriodic(options.getSyncIntervalMs(), id -> vertx.executeBlocking(() -> {
			try {
				sync.drain(options.getSyncBatchSize());
			} catch (RuntimeException e) {
				log.warn("Vector index drain failed", e);
			}
			return null;
		}, false));
		log.info("Vector index drain started (interval={}ms, batch={})", options.getSyncIntervalMs(), options.getSyncBatchSize());
	}

	/** Stop the drain and flush whatever the index is holding. */
	public synchronized void stop() {
		if (timerId != -1) {
			vertx.cancelTimer(timerId);
			timerId = -1;
		}
		sync.index().commit();
	}
}
