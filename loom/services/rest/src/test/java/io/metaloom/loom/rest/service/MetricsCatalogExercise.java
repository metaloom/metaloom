package io.metaloom.loom.rest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.metaloom.loom.api.options.StorageOptions;
import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.db.storage.StorageCategory;
import io.metaloom.loom.db.storage.StorageCategoryStat;
import io.metaloom.loom.pipeline.engine.NodeDispatcher;
import io.metaloom.loom.pipeline.engine.NodeKindCircuitBreaker;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.rest.service.impl.BinaryStorageResolver.BackendInfo;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.metaloom.loom.rest.service.impl.PipelineRunRegistry;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.metaloom.loom.rest.service.impl.StorageCapacityGuard.Watermark;
import io.metaloom.loom.rest.storage.StorageMetricsBinder;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Fires every {@code loom_*} meter once, against a registry the caller supplies.
 *
 * <p>
 * Shared by {@link MetricsCatalogScrapeTest} and {@link MetricsSnapshotCatalogTest} because the two are only meaningful if they agree about what
 * "live" means: one holds the Prometheus scrape to {@code METRICS.md}, the other holds {@code GET /api/v1/metrics} to the same file, and a meter
 * exercised in one but not the other produces a contradiction rather than a finding. Both classes said so in prose and then kept their own copy of
 * this, so a new meter had to be remembered twice — and the second copy is exactly the one that gets forgotten.
 * </p>
 *
 * <p>
 * Nothing here needs a database, a socket or a worker: {@code ProcessorRegistry} accepts a null DAO collection and a null broadcaster, the engine
 * dispatches through the {@link NodeDispatcher} SPI, and the storage gauges read suppliers rather than a live backend.
 * </p>
 */
final class MetricsCatalogExercise {

	private MetricsCatalogExercise() {
	}

	/**
	 * Bind every gauge and record every counter and timer at least once.
	 *
	 * @param metrics the facade over the registry under test
	 */
	static void exerciseEverything(LoomMetrics metrics) {
		bindProductionGauges(metrics);
		runAPipeline(metrics);
		recordTheRemainingCounters(metrics);
	}

	/**
	 * Construct the real instrumentation sites, which is what publishes the gauges.
	 */
	private static void bindProductionGauges(LoomMetrics metrics) {
		// null daos + null broadcaster: the in-memory selection registry, as its own unit tests use it.
		new ProcessorRegistry(null, null, metrics);
		new PipelineRunRegistry(metrics);
		new PipelineEventBroadcaster(metrics);
		bindStorageGauges(metrics);
	}

	/**
	 * The storage gauges, with canned suppliers.
	 *
	 * <p>
	 * {@code StorageMetricsBinder} deliberately takes suppliers rather than a {@code DaoCollection} or a {@code BinaryStorageResolver}: a gauge must
	 * never do a {@code statvfs} or a table scan on the scrape thread, and the indirection that buys is also what lets the real binder be exercised
	 * here with no Postgres behind it. One backend and one category is enough to publish every series in the family.
	 * </p>
	 */
	private static void bindStorageGauges(LoomMetrics metrics) {
		BackendInfo backend = new BackendInfo(null, "Default storage", "filesystem", "filesystem:/uploads",
			48318382080L, 214748364800L, null);
		StorageCategoryStat category = new StorageCategoryStat(StorageCategory.ASSET_BINARY, 12, 4096, 11, 3584);
		StorageOptions storage = new StorageOptions();
		new StorageMetricsBinder(metrics, () -> List.of(backend), () -> List.of(category),
			info -> info.freeBytes() == null ? Watermark.UNKNOWN
				: info.freeBytes() < storage.getMinFreeSpace() ? Watermark.CRITICAL : Watermark.OK)
			.bind();
	}

	/**
	 * Drive one run end to end so the engine's own meters fire from their real call sites.
	 */
	private static void runAPipeline(LoomMetrics metrics) {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")
					.put("options", new JsonObject().put("retryFailed", true))))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash")
					.put("targetPort", "media")));
		PipelineGraph graph = new PipelineGraphParser().parse("metrics", definition, true, false, 0);

		List<NodeTask> dispatched = new ArrayList<>();
		NodeDispatcher dispatcher = task -> {
			dispatched.add(task);
			return "worker-1";
		};

		// Shared breaker, exactly as PipelineEndpointService installs it. Observing a kind is what
		// binds that kind's state gauge.
		NodeKindCircuitBreaker breaker = new NodeKindCircuitBreaker(metrics);

		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());
		engine.setMetrics(metrics);
		engine.setCircuitBreaker(breaker);
		engine.start();

		// A failure that retries, then a completion: retried + latency, both states.
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);
		engine.onNodeTaskResult(itemId,
			NodeTaskResult.failed(dispatched.get(dispatched.size() - 1).getTaskUuid(), "hash", 1, "transient"));
		engine.onNodeTaskResult(itemId,
			NodeTaskResult.completed(dispatched.get(dispatched.size() - 1).getTaskUuid(), "hash", 1, Map.of()));

		// A second item, lost twice: retried again, then dead-lettered.
		PipelineRunEngine losing = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());
		losing.setMetrics(metrics);
		losing.start();
		String lostItem = losing.onItemDiscovered(MediaRef.of("/media/b.mp4"));
		losing.onSourceComplete(1);
		losing.onNodeTaskLost(lostItem, "hash", "lease expired");
		losing.onNodeTaskLost(lostItem, "hash", "lease expired again");

		// Trip the breaker so its trip counter exists as well as its state gauge.
		for (int i = 0; i < NodeKindCircuitBreaker.DEFAULT_MIN_SAMPLES; i++) {
			breaker.record("sha512", false);
		}
	}

	/**
	 * The counters whose real call sites need a database, a WebSocket or a worker.
	 *
	 * <p>
	 * Those sites are covered by their own tests; what is being checked here is that the catalog can produce every documented name at all — the
	 * precise gap that let three helpers ship with no caller and a fourth be documented without a helper.
	 * </p>
	 */
	private static void recordTheRemainingCounters(LoomMetrics metrics) {
		metrics.recordRunStarted();
		metrics.recordRunCompleted("success", 1_234);
		metrics.recordRunRejected("no_processor");
		metrics.recordRunRecovered(1);
		metrics.recordNodeTaskDispatched("sha512");
		metrics.recordNodeTaskDispatchFailed("no_processor");
		metrics.recordNodeResultReceived("sha512", "success");
		metrics.recordSourceItemsReceived(3);
		metrics.recordAssetNodeResultWritten("sha512", "success");
		metrics.recordLeasesReclaimed(1);
		metrics.recordOrphansDeadlettered(1);
		metrics.recordTaskReturned("worker-1");
		metrics.recordPipelineEventBroadcast();
		metrics.recordPipelineEventDropped();
		metrics.recordProcessorRegistered();
		metrics.recordProcessorDisconnected();
		metrics.recordProcessorHeartbeat();
		metrics.recordAuthFailure("ws");
		metrics.recordUploadRejected("no_space");
	}
}
