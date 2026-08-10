package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.READ_STORAGE;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.db.storage.StorageCategoryStat;
import io.metaloom.loom.db.storage.StorageReport;
import io.metaloom.loom.db.storage.StorageStatsService;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.storage.StorageBackendListResponse;
import io.metaloom.loom.rest.model.storage.StorageBackendModel;
import io.metaloom.loom.rest.model.storage.StorageCategoryModel;
import io.metaloom.loom.rest.model.storage.StorageReportResponse;
import io.metaloom.loom.rest.model.storage.StorageThresholdsModel;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.service.impl.BinaryStorageResolver.BackendInfo;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.vertx.core.Vertx;

/**
 * Serves {@code GET /api/v1/storage} and {@code /storage/backends}.
 *
 * <p>
 * This is where the two halves of the answer meet. The catalogue half - how many elements of each kind, how many bytes they claim, how much of that is
 * duplicate content - comes from {@link StorageStatsService} and is pure SQL. The capacity half - how much room is left, and how close to full that is
 * - comes from the storage backends themselves and cannot be asked of the database at all. Neither is useful alone: rows without free space cannot
 * say whether the next upload will fail, and free space without rows cannot say what to delete.
 * </p>
 *
 * <p>
 * Computed per request, with no cache. A stored figure would be a number somebody has to remember to refresh, which is precisely what
 * {@code asset_pool.free_space} already is and why nothing reads it.
 * </p>
 *
 * <p>
 * Both routes run on a worker via {@link Vertx#executeBlocking}, for two reasons rather than the usual one: jOOQ blocks, <em>and</em> reading free
 * space is a {@code statvfs} that will hang the event loop outright on a stalled network mount.
 * </p>
 */
@Singleton
public class StorageEndpointService extends AbstractEndpointService {

	private static final Logger log = LoggerFactory.getLogger(StorageEndpointService.class);

	private final StorageStatsService storageStats;

	private final BinaryStorageResolver storageResolver;

	private final StorageCapacityGuard capacityGuard;

	private final LoomOptions options;

	private final Vertx vertx;

	@Inject
	public StorageEndpointService(StorageStatsService storageStats, BinaryStorageResolver storageResolver, StorageCapacityGuard capacityGuard,
		LoomOptions options, Vertx vertx, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(modelBuilder, validator);
		this.storageStats = storageStats;
		this.storageResolver = storageResolver;
		this.capacityGuard = capacityGuard;
		this.options = options;
		this.vertx = vertx;
	}

	/**
	 * {@code GET /api/v1/storage} - what is stored and how much room is left.
	 */
	public void loadReport(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_STORAGE, () -> {
			vertx.<StorageReportResponse>executeBlocking(() -> {
				StorageReport report = storageStats.report();
				return toResponse(report, storageResolver.allBackends());
			}).onComplete(ar -> {
				if (ar.succeeded()) {
					lrc.send(ar.result());
				} else {
					log.error("Error while computing the storage report", ar.cause());
					lrc.error("Failed to compute the storage report");
				}
			});
		});
	}

	/**
	 * {@code GET /api/v1/storage/backends} - capacity only.
	 *
	 * <p>
	 * Exists because it is cheap: one {@code statvfs} per backend and no table scans, which makes it the one a dashboard can poll where the full
	 * report is not.
	 * </p>
	 */
	public void loadBackends(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_STORAGE, () -> {
			vertx.<StorageBackendListResponse>executeBlocking(() -> {
				StorageBackendListResponse response = new StorageBackendListResponse().setThresholds(thresholds());
				// A HashMap rather than Map.of(): the default storage is keyed by a null pool uuid, and an
				// immutable map throws on a null key instead of answering "not present".
				Map<UUID, StorageReport.StoragePoolStat> noTotals = new HashMap<>();
				storageResolver.allBackends().forEach(backend -> response.add(toModel(backend, noTotals)));
				return response;
			}).onComplete(ar -> {
				if (ar.succeeded()) {
					lrc.send(ar.result());
				} else {
					log.error("Error while reading the storage backends", ar.cause());
					lrc.error("Failed to read the storage backends");
				}
			});
		});
	}

	private StorageReportResponse toResponse(StorageReport report, List<BackendInfo> backends) {
		Map<UUID, StorageReport.StoragePoolStat> perPool = new HashMap<>();
		report.perPool().forEach(stat -> perPool.put(stat.poolUuid(), stat));

		StorageReportResponse response = new StorageReportResponse()
			.setTimestamp(Instant.now())
			.setThresholds(thresholds())
			.setObjects(report.objects())
			.setDistinctBytes(report.distinctBytes())
			.setOrphanObjects(report.orphanObjects())
			.setOrphanBytes(report.orphanBytes());
		report.categories().forEach(stat -> response.add(toModel(stat)));
		backends.forEach(backend -> response.add(toModel(backend, perPool)));
		return response;
	}

	private StorageThresholdsModel thresholds() {
		return new StorageThresholdsModel()
			.setMinFreeSpaceBytes(options.getStorage().getMinFreeSpace())
			.setWarnFreeSpaceBytes(options.getStorage().getWarnFreeSpace())
			.setMaxUploadSizeBytes(options.getStorage().getMaxUploadSize());
	}

	private StorageCategoryModel toModel(StorageCategoryStat stat) {
		return new StorageCategoryModel()
			.setCategory(stat.category().name())
			.setElements(stat.elements())
			.setLogicalBytes(stat.logicalBytes())
			.setDistinctObjects(stat.distinctObjects())
			.setDistinctBytes(stat.distinctBytes());
	}

	/**
	 * @param perPool the catalogue's per-pool totals, or an empty map on the capacity-only route where they were not computed. Must tolerate a null
	 *                key: the deployment's default storage is the null pool.
	 */
	private StorageBackendModel toModel(BackendInfo backend, Map<UUID, StorageReport.StoragePoolStat> perPool) {
		StorageReport.StoragePoolStat stat = perPool.get(backend.poolUuid());
		return new StorageBackendModel()
			.setPoolUuid(backend.poolUuid())
			.setPoolName(backend.poolName())
			.setKind(backend.kind())
			.setDescription(backend.description())
			.setFreeBytes(backend.freeBytes())
			.setTotalBytes(backend.totalBytes())
			// UNKNOWN rather than OK when the backend reports no capacity. An object store is not known to be
			// healthy, it is unmeasurable, and a green bucket on a dashboard is a lie of omission.
			.setWatermark(capacityGuard.evaluate(backend.freeBytes()).name())
			.setObjects(stat == null ? 0 : stat.objects())
			.setBytes(stat == null ? 0 : stat.bytes())
			.setError(backend.error());
	}
}
