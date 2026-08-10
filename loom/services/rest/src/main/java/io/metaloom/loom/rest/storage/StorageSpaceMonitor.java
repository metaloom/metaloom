package io.metaloom.loom.rest.storage;

import java.time.Instant;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.db.storage.StorageCategoryStat;
import io.metaloom.loom.db.storage.StorageReport;
import io.metaloom.loom.db.storage.StorageStatsService;
import io.metaloom.loom.rest.service.impl.BinaryStorageResolver;
import io.metaloom.loom.rest.service.impl.BinaryStorageResolver.BackendInfo;
import io.metaloom.loom.rest.service.impl.StorageCapacityGuard;
import io.metaloom.loom.rest.service.impl.StorageCapacityGuard.Watermark;
import io.vertx.core.Vertx;

/**
 * Watches every storage backend's free space, logs when one crosses a watermark, and keeps the snapshot the gauges read.
 *
 * <p>
 * An operator should not have to open a screen to find out that a volume is filling. The interval is the debounce: logging on every degraded pass is
 * what makes the line greppable without drowning the log, and nothing is logged at all while every backend is healthy.
 * </p>
 *
 * <p>
 * The cached snapshot is the other half of its job. Gauges are polled on the Prometheus scrape thread, and neither a {@code statvfs} nor the aggregate
 * SQL belongs there - one hangs the scrape on a stalled NFS mount, the other runs several table scans every fifteen seconds. Both happen here, on a
 * timer, off the event loop.
 * </p>
 *
 * <p>
 * Started from {@code BootstrapInitializer} beside the search drainers, whose shape this follows.
 * </p>
 */
@Singleton
public class StorageSpaceMonitor {

	private static final Logger log = LoggerFactory.getLogger(StorageSpaceMonitor.class);

	private final Vertx vertx;

	private final BinaryStorageResolver storageResolver;

	private final StorageStatsService storageStats;

	private final StorageCapacityGuard capacityGuard;

	private final LoomOptions options;

	private final StorageMetricsBinder binder;

	private volatile Snapshot snapshot = Snapshot.empty();

	private volatile long timerId = -1;

	@Inject
	public StorageSpaceMonitor(Vertx vertx, BinaryStorageResolver storageResolver, StorageStatsService storageStats,
		StorageCapacityGuard capacityGuard, LoomOptions options, LoomMetrics metrics) {
		this.vertx = vertx;
		this.storageResolver = storageResolver;
		this.storageStats = storageStats;
		this.capacityGuard = capacityGuard;
		this.options = options;
		this.binder = new StorageMetricsBinder(metrics,
			() -> snapshot.backends(),
			() -> snapshot.categories(),
			backend -> capacityGuard.evaluate(backend.freeBytes()));
	}

	/** Start the periodic pass. No-op when the interval is 0 or it is already running. */
	public synchronized void start() {
		if (options.getStorage().getSpaceCheckIntervalMs() <= 0) {
			log.info("The storage space check is disabled (LOOM_STORAGE_SPACE_CHECK_INTERVAL_MS=0); "
				+ "free space is still reported by GET /api/v1/storage on request");
			return;
		}
		if (timerId != -1) {
			return;
		}
		long interval = options.getStorage().getSpaceCheckIntervalMs();
		// One pass immediately, so the gauges are populated without waiting out the first interval.
		vertx.executeBlocking(() -> {
			refresh();
			return null;
		}, false);
		timerId = vertx.setPeriodic(interval, id -> vertx.executeBlocking(() -> {
			refresh();
			return null;
		}, false));
		log.info("Storage space check started (interval={}ms, warn={} bytes, critical={} bytes)", interval,
			options.getStorage().getWarnFreeSpace(), options.getStorage().getMinFreeSpace());
	}

	public synchronized void stop() {
		if (timerId != -1) {
			vertx.cancelTimer(timerId);
			timerId = -1;
		}
	}

	/**
	 * The most recent snapshot. Never null, and empty until the first pass has run.
	 */
	public Snapshot snapshot() {
		return snapshot;
	}

	/**
	 * Re-read every backend and the category totals, then log anything degraded.
	 *
	 * <p>
	 * Failures are swallowed: this is a best-effort observation pass, and one broken pool must not stop the next one from being checked or leave the
	 * gauges stuck on stale data forever.
	 * </p>
	 */
	public void refresh() {
		try {
			List<BackendInfo> backends = storageResolver.allBackends();
			StorageReport report = storageStats.report();
			snapshot = new Snapshot(Instant.now(), backends, report.categories());
			binder.bind();
			report(backends);
		} catch (RuntimeException e) {
			log.warn("The storage space check failed", e);
		}
	}

	private void report(List<BackendInfo> backends) {
		for (BackendInfo backend : backends) {
			if (backend.error() != null) {
				log.warn("Storage pool '{}' could not be resolved: {}", backend.poolName(), backend.error());
				continue;
			}
			Watermark watermark = capacityGuard.evaluate(backend.freeBytes());
			switch (watermark) {
				case CRITICAL -> log.error(
					"Storage '{}' ({}) is below the critical watermark: {} bytes free, {} required (LOOM_STORAGE_MIN_FREE_SPACE). Uploads to it are being refused.",
					backend.poolName(), backend.description(), backend.freeBytes(), options.getStorage().getMinFreeSpace());
				case WARN -> log.warn("Storage '{}' ({}) is running low: {} bytes free, warning at {} (LOOM_STORAGE_WARN_FREE_SPACE).",
					backend.poolName(), backend.description(), backend.freeBytes(), options.getStorage().getWarnFreeSpace());
				// OK needs no line, and UNKNOWN is the permanent state of every object store - logging it every
				// pass would be a recurring warning about something that is working exactly as designed.
				case OK, UNKNOWN -> {
				}
			}
		}
	}

	/**
	 * What the last pass saw.
	 *
	 * @param takenAt    when the pass ran
	 * @param backends   every storage backend and its capacity
	 * @param categories the per-category figures
	 */
	public record Snapshot(Instant takenAt, List<BackendInfo> backends, List<StorageCategoryStat> categories) {

		public static Snapshot empty() {
			return new Snapshot(null, List.of(), List.of());
		}
	}
}
