package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.READ_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PIPELINE;
import static io.metaloom.loom.db.model.perm.Permission.READ_PIPELINE_RUN;
import static io.metaloom.loom.db.model.perm.Permission.READ_PIPELINE_VERSION;
import static io.metaloom.loom.db.model.perm.Permission.RESTORE_PIPELINE_VERSION;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PIPELINE_RUN;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.pipeline.Pipeline;
import io.metaloom.loom.db.model.pipeline.PipelineDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineRunRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineRunResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineVersionRestoreRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineBreakpointRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeReExecuteRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineNodeReExecuteResponse;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.engine.RunStateStore;
import io.metaloom.loom.pipeline.graph.GraphValidationException;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphNode;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.SourceTaskMessage;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.service.impl.PipelineAuthoringService.PipelineWithVersion;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry.ConnectedProcessor;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.ValidationException;
import io.metaloom.loom.rest.validation.PipelineValidationService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

@Singleton
public class PipelineEndpointService extends AbstractCRUDEndpointService<PipelineDao, Pipeline> {

	private static final Logger log = LoggerFactory.getLogger(PipelineEndpointService.class);


	private final ProcessorRegistry processorRegistry;
	private final PipelineValidationService pipelineValidationService;
	/** The single write path for definitions — see {@link PipelineAuthoringService}; the MCP tools use the same one. */
	private final PipelineAuthoringService pipelineAuthoringService;
	private final io.metaloom.loom.common.metrics.LoomMetrics metrics;
	private final PipelineRunDao pipelineRunDao;
	private final PipelineVersionDao pipelineVersionDao;
	private final PipelineRunTracker pipelineRunTracker;

	private final PipelineRunRegistry pipelineRunRegistry;

	private final WebSocketNodeDispatcher nodeDispatcher;

	private final PipelineGraphParser graphParser;
	private final PipelineEventBroadcaster pipelineEventBroadcaster;
	private final io.metaloom.loom.pipeline.engine.NodeKindCircuitBreaker circuitBreaker;
	private final io.vertx.core.Vertx vertx;

	/** How often aggregated node counters are pushed to subscribers. */
	private static final long STATS_INTERVAL_MS = 1000;

	/** Default window (in days) for the cross-pipeline run stats endpoint. */
	private static final int RUN_STATS_DEFAULT_DAYS = 14;

	/** Upper bound for the run stats {@code days} query parameter. */
	private static final int RUN_STATS_MAX_DAYS = 90;
	private final PipelineRunItemDao pipelineRunItemDao;
	private final PipelineNodeTaskDao pipelineNodeTaskDao;

	@Inject
	public PipelineEndpointService(PipelineDao pipelineDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator, ProcessorRegistry processorRegistry,
		PipelineValidationService pipelineValidationService, PipelineAuthoringService pipelineAuthoringService,
		PipelineRunDao pipelineRunDao,
		PipelineVersionDao pipelineVersionDao, PipelineRunTracker pipelineRunTracker, PipelineRunRegistry pipelineRunRegistry,
		WebSocketNodeDispatcher nodeDispatcher, PipelineRunItemDao pipelineRunItemDao,
		PipelineNodeTaskDao pipelineNodeTaskDao, PipelineEventBroadcaster pipelineEventBroadcaster,
		io.vertx.core.Vertx vertx, io.metaloom.loom.common.metrics.LoomMetrics metrics) {
		super(pipelineDao, daos, modelBuilder, validator);
		this.metrics = metrics;
		// One breaker for the whole process, so its per-kind trip counter and state gauge describe
		// the fleet rather than whichever run happened to notice first.
		this.circuitBreaker = new io.metaloom.loom.pipeline.engine.NodeKindCircuitBreaker(metrics);
		this.processorRegistry = processorRegistry;
		this.pipelineValidationService = pipelineValidationService;
		this.pipelineAuthoringService = pipelineAuthoringService;
		this.pipelineRunDao = pipelineRunDao;
		this.pipelineVersionDao = pipelineVersionDao;
		this.pipelineRunTracker = pipelineRunTracker;
		this.pipelineRunRegistry = pipelineRunRegistry;
		this.nodeDispatcher = nodeDispatcher;
		this.pipelineRunItemDao = pipelineRunItemDao;
		this.pipelineNodeTaskDao = pipelineNodeTaskDao;
		this.pipelineEventBroadcaster = pipelineEventBroadcaster;
		this.vertx = vertx;
		this.graphParser = new PipelineGraphParser();
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		checkPerm(lrc, DELETE_PIPELINE, () -> {
			// Delete all versions for this pipeline first
			pipelineVersionDao.loadByPipeline(id).forEach(v -> pipelineVersionDao.delete(v.getUuid()));
			// Then delete the pipeline
			dao().delete(id);
			lrc.send(new io.metaloom.loom.rest.model.message.GenericMessageResponse().setMessage("Pipeline and all versions deleted"));
		});
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_PIPELINE, page -> modelBuilder.toPipelineList(page, latestVersions(page)));
	}

	/**
	 * Resolve the latest version of every pipeline on the page in a single query, so that the flattened response can be built without an N+1 lookup.
	 *
	 * <p>
	 * This follows the {@code latestVersionUuid} pointer rather than re-deriving the highest version number per pipeline, which is what makes the batch
	 * possible. Create, update and restore all keep the pointer in step, and the V2.30 migration backfilled it, so the two agree.
	 * </p>
	 */
	private Map<UUID, PipelineVersion> latestVersions(io.metaloom.loom.db.page.Page<Pipeline> page) {
		Map<UUID, UUID> versionToPipeline = new HashMap<>();
		for (Pipeline pipeline : page) {
			if (pipeline.getLatestVersionUuid() != null) {
				versionToPipeline.put(pipeline.getLatestVersionUuid(), pipeline.getUuid());
			}
		}
		Map<UUID, PipelineVersion> byPipeline = new HashMap<>();
		for (PipelineVersion version : pipelineVersionDao.loadByUuids(versionToPipeline.keySet())) {
			byPipeline.put(versionToPipeline.get(version.getUuid()), version);
		}
		return byPipeline;
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_PIPELINE, () -> {
			return dao().loadWithLatestVersion(id);
		}, pipeline -> modelBuilder.toResponse(pipeline, pipelineVersionDao.loadLatestByPipeline(pipeline.getUuid())));
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		AtomicReference<PipelineVersion> created = new AtomicReference<>();
		create(lrc, CREATE_PIPELINE, () -> {
			PipelineCreateRequest request = lrc.requestBody(PipelineCreateRequest.class);
			PipelineWithVersion result = pipelineAuthoringService.create(lrc.userUuid(), request);
			created.set(result.version());
			return result.pipeline();
		}, pipeline -> modelBuilder.toResponse(pipeline, created.get()));
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		AtomicReference<PipelineVersion> updated = new AtomicReference<>();
		update(lrc, UPDATE_PIPELINE, () -> {
			PipelineUpdateRequest request = lrc.requestBody(PipelineUpdateRequest.class);
			PipelineWithVersion result = pipelineAuthoringService.update(lrc.userUuid(), id, request);
			if (result == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline not found.");
			}
			updated.set(result.version());
			return result.pipeline();
		}, pipeline -> modelBuilder.toResponse(pipeline, updated.get()));
	}

	/**
	 * Trigger a pipeline run: select a processor and hand it the pipeline's source
	 * node as a {@code SOURCE_TASK}. From there the {@link PipelineRunEngine} owns the
	 * graph and dispatches individual {@code NODE_TASK}s as items stream back.
	 *
	 * <p>Callers only need {@link io.metaloom.loom.db.model.perm.Permission#READ_PIPELINE}
	 * — this endpoint does not mutate the pipeline definition itself; it merely
	 * asks a processor to execute the already-persisted definition.</p>
	 */
	public void run(LoomRoutingContext lrc, UUID id) {
		checkPerm(lrc, READ_PIPELINE, () -> {
			Pipeline pipeline = dao().loadWithLatestVersion(id);
			if (pipeline == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline not found.");
			}

			PipelineRunRequest request;
			try {
				request = lrc.requestBody(PipelineRunRequest.class);
			} catch (Exception e) {
				request = new PipelineRunRequest();
			}
			if (request == null) {
				request = new PipelineRunRequest();
			}

			RunDispatch result = dispatchRun(pipeline, lrc.userUuid(), request);
			lrc.send(result.response, result.status);
		});
	}

	/**
	 * Trigger a pipeline run scoped to a single asset. Used by the asset-created auto-trigger, which has no request context of its own. The asset is
	 * resolved to its stored binary path and handed to the pipeline source node (see {@link #sourceOptions}).
	 *
	 * @param pipelineUuid
	 *            the pipeline to run
	 * @param assetUuid
	 *            the asset to process
	 * @param userUuid
	 *            the user the run is attributed to (typically the asset creator)
	 * @return the dispatch outcome
	 */
	public PipelineRunResponse runForAsset(UUID pipelineUuid, UUID assetUuid, UUID userUuid) {
		Pipeline pipeline = dao().loadWithLatestVersion(pipelineUuid);
		if (pipeline == null) {
			return new PipelineRunResponse().setDispatched(false).setMessage("Pipeline not found: " + pipelineUuid);
		}
		PipelineRunRequest request = new PipelineRunRequest().setMediaUuids(List.of(assetUuid));
		return dispatchRun(pipeline, userUuid, request).response;
	}

	/**
	 * The node kinds in {@code graph} that no online worker will run, preserving graph
	 * order and de-duplicated. Empty means the whole graph is schedulable against the
	 * current pool. A kind is unsupported when {@link ProcessorRegistry#selectProcessorForKinds}
	 * finds no CPU-capable worker whose whitelist/blacklist accepts it.
	 *
	 * <p>Package-external and static so it can be unit-tested against a real
	 * {@link ProcessorRegistry} without standing up the DAO/DB layer that the full
	 * {@link #dispatchRun} path needs.</p>
	 */
	public static Set<String> unsupportedNodeKinds(PipelineGraph graph, ProcessorRegistry processorRegistry) {
		Set<String> unsupported = new LinkedHashSet<>();
		for (PipelineGraphNode node : graph.getNodes()) {
			String kind = node.getKind();
			if (kind == null || kind.isBlank()) {
				continue;
			}
			if (processorRegistry.selectProcessorForKinds(ProcessorCapability.CPU, List.of(kind)) == null) {
				unsupported.add(kind);
			}
		}
		return unsupported;
	}

	/**
	 * Shared run-dispatch core used by both the REST {@link #run} endpoint and the asset auto-trigger. Returns the response together with the HTTP
	 * status the REST endpoint should send, rather than writing to a routing context, so it can be called without one.
	 */
	private RunDispatch dispatchRun(Pipeline pipeline, UUID userUuid, PipelineRunRequest request) {
		PipelineRunResponse response = new PipelineRunResponse();

		// Get the latest version for the run
		PipelineVersion latestVersion = pipelineVersionDao.loadLatestByPipeline(pipeline.getUuid());
		int pipelineVersion = latestVersion != null ? latestVersion.getVersionNumber() : 1;
		String pipelineName = latestVersion != null ? latestVersion.getName() : String.valueOf(pipeline.getUuid());
		boolean dryRun = request.isDryRun() != null
			? request.isDryRun()
			: (latestVersion != null && latestVersion.isDryRun());

		// Build the executable graph up front. A definition that cannot run as
		// drawn is an error the caller should see now, not a green run that
		// quietly did nothing.
		PipelineGraph graph;
		try {
			graph = graphParser.parse(pipelineName,
				latestVersion != null ? latestVersion.getDefinition() : null,
				latestVersion == null || latestVersion.isEnabled(), dryRun, pipelineVersion);
		} catch (GraphValidationException e) {
			metrics.recordRunRejected("invalid_graph");
			log.warn("Refusing to run pipeline '{}': {}", pipelineName, e.getMessage());
			return new RunDispatch(response.setDispatched(false).setMessage(e.getMessage()), 400);
		}

		// Every node kind in the graph must have some online worker that will run it,
		// not just the source. A run whose downstream kind (say 'whisper') no worker
		// accepts would otherwise start green and stall the moment that node became
		// ready and could not be dispatched. Reject up front, naming the kinds, so the
		// caller sees an actionable 503 now rather than a run that quietly never
		// finishes. This is checked after parsing because the kinds come from the graph.
		Set<String> unsupportedKinds = unsupportedNodeKinds(graph, processorRegistry);
		if (!unsupportedKinds.isEmpty()) {
			metrics.recordRunRejected("no_processor");
			log.warn("Rejected pipeline run for pipeline {}: no processor accepts node kind(s) {}",
				pipeline.getUuid(), unsupportedKinds);
			return new RunDispatch(response.setDispatched(false)
				.setMessage("No processor available for node kind(s): " + String.join(", ", unsupportedKinds)), 503);
		}

		// The source is dispatched explicitly below (SOURCE_TASK), so resolve its
		// worker now; the precheck above guarantees one exists.
		String sourceKind = graph.getSourceNode().getKind();
		ConnectedProcessor processor = processorRegistry.selectProcessorForKinds(ProcessorCapability.CPU,
			List.of(sourceKind));

		// Create a pipeline run record to track this execution
		PipelineRun runRecord = pipelineRunDao.createPipelineRun(userUuid, pipeline.getUuid(), pipelineVersion);
		runRecord.setStatus(PipelineRunStatus.RUNNING);
		runRecord.setDryRun(dryRun);
		pipelineRunDao.store(runRecord);
		metrics.recordRunStarted();
		UUID runUuid = runRecord.getUuid();
		response.setRunUuid(runUuid);

		// The engine owns the graph and decides what runs next; Cortex only ever
		// sees one node at a time. State goes to Postgres through the store, so the
		// run is not lost with the process that started it.
		RunStateStore stateStore = new DaoRunStateStore(pipelineRunDao, pipelineRunItemDao, pipelineNodeTaskDao,
			runUuid, userUuid);
		PipelineRunEngine engine = new PipelineRunEngine(graph, nodeDispatcher, runUuid, stateStore);
		// Dispatch-to-result latency, retries and dead-letters are only knowable here: the engine is
		// the one party that sees a task leave and its result come back.
		engine.setMetrics(metrics);
		// Outputs of nodes marked syncToLoom land on the asset, not just in the run
		// record. Without this the hash a pipeline computes is invisible everywhere
		// an asset is actually looked at.
		engine.setAssetSink(new DaoAssetSink(daos().assetDao(), userUuid));
		engine.onCompletion(summary -> pipelineRunTracker.complete(runUuid, summary.getDurationMs(),
			(int) summary.getMediaCount(), (int) summary.getSuccessCount(),
			(int) summary.getFailureCount(), (int) summary.getSkippedCount()));
		// The run banner and history in the editor refresh off these two frames. Loom is the
		// only party that knows a run started or settled - a worker holds no pipeline graph -
		// so if they are not emitted here they are not emitted at all.
		engine.onCompletion(summary -> broadcastRunEvent(
			io.metaloom.loom.rest.model.pipeline.event.PipelineEventType.PIPELINE_COMPLETED, pipelineName, runUuid));
		// Aggregated progress: per-node counters on a timer, individual events only
		// for failures. Forwarding every settle would be millions of frames to move
		// a progress bar.
		RunStatsAggregator statsAggregator = new RunStatsAggregator(runUuid, graph.getName(),
			pipelineEventBroadcaster);
		engine.onNodeSettled(statsAggregator);
		statsAggregator.setProgressSupplier(engine::nodeProgressSnapshot);
		long statsTimer = vertx.setPeriodic(STATS_INTERVAL_MS, timerId -> statsAggregator.flush());
		engine.onCompletion(summary -> {
			vertx.cancelTimer(statsTimer);
			// One last push so the final counts are not left a timer-tick stale.
			statsAggregator.flush();
		});

		// Shared across runs on purpose: a kind broken by a missing model file or an
		// expired key is broken for everyone, and per-run breakers would each have to
		// rediscover that.
		// Debug mode is a property of this run, not of the pipeline: the same definition is run
		// both ways, and nothing about the graph changes either way.
		engine.setCapturePreviews(Boolean.TRUE.equals(request.isDebug()));
		// Armed before start(), so the first item cannot slip past a breakpoint the caller asked
		// for in the same request that started the run.
		attachBreakpointBroadcast(engine, pipelineName, runUuid);
		engine.setBreakpoints(validateBreakpoints(graph, request.getBreakpoints()));
		engine.setCircuitBreaker(circuitBreaker);
		engine.setRetryScheduler((delayMs, action) -> vertx.setTimer(Math.max(1, delayMs), t -> action.run()));

		pipelineRunRegistry.register(runUuid, engine);
		engine.start();
		broadcastRunEvent(io.metaloom.loom.rest.model.pipeline.event.PipelineEventType.PIPELINE_STARTED, pipelineName, runUuid);

		// Hand the source node to a worker. Everything else follows from the
		// items it streams back.
		PipelineGraphNode sourceNode = graph.getSourceNode();
		SourceTaskMessage sourceTask = new SourceTaskMessage()
			.setRunUuid(runUuid)
			.setNodeId(sourceNode.getId())
			.setNodeKind(sourceNode.getKind())
			.setOptions(sourceOptions(sourceNode, request));

		boolean dispatched = processorRegistry.send(processor.nodeId, ProcessorMessageType.SOURCE_TASK, sourceTask);
		if (!dispatched) {
			// The socket was gone by the time we wrote to it. Nothing will ever
			// enumerate, so close the run out immediately rather than leaving it
			// RUNNING forever.
			pipelineRunRegistry.unregister(runUuid);
			pipelineRunTracker.fail(runUuid, "Processor was not reachable");
			// The engine was unregistered without ever completing, so its onCompletion
			// callbacks will not fire. Emit the closing frame here or this run would be the
			// one case where a PIPELINE_STARTED is never answered.
			broadcastRunEvent(io.metaloom.loom.rest.model.pipeline.event.PipelineEventType.PIPELINE_COMPLETED, pipelineName, runUuid);
		}

		response
			.setProcessorNodeId(processor.nodeId)
			.setDispatched(dispatched)
			.setMessage(dispatched ? "Source task dispatched" : "Processor was not reachable");

		log.info("Pipeline '{}' run started (pipelineRunUuid={}, nodes={}, processor={}, ok={})",
			pipelineName, runUuid, graph.size(), processor.nodeId, dispatched);
		return new RunDispatch(response, dispatched ? 202 : 503);
	}

	/** Result of {@link #dispatchRun}: the response plus the HTTP status the REST endpoint should send. */
	private static final class RunDispatch {
		final PipelineRunResponse response;
		final int status;

		RunDispatch(PipelineRunResponse response, int status) {
			this.response = response;
			this.status = status;
		}
	}

	/**
	 * Resolve the options the source node should run with.
	 *
	 * <p>The definition supplies the defaults; a run request may override the
	 * selection with {@code pathGlobs}, a single {@code path}, or a set of asset UUIDs.
	 * When asset UUIDs are given they are resolved to their stored binary paths: a single
	 * asset is passed as {@code path} (and {@code assetUuid}) — what the asset and
	 * filesystem source nodes read directly — while multiple assets fall back to
	 * {@code pathGlobs}. Note the paths are resolved on the <em>worker</em>, so a
	 * path the chosen processor cannot see yields an empty run rather than an error.</p>
	 *
	 * <p>Precedence, most specific first: {@code mediaUuids} &gt; {@code pathGlobs} &gt;
	 * {@code path}. {@code path} is only applied when no globs were supplied, so a glob
	 * request is never silently downgraded to a single root — the two mean different
	 * things to the source node ({@code pathGlobs} forces a full re-walk, a bare
	 * {@code path} runs the differential scan against the persisted index).</p>
	 */
	private java.util.Map<String, Object> sourceOptions(PipelineGraphNode sourceNode, PipelineRunRequest request) {
		return SourceOptionsResolver.resolve(sourceNode.getOptions(), request, assetUuid -> {
			AssetBinary binary = daos().assetBinaryDao().loadPrimaryByAssetUuid(assetUuid);
			if (binary == null || binary.getPath() == null) {
				log.warn("No stored binary path for asset {}; it cannot be included in the run", assetUuid);
				return null;
			}
			return binary.getPath();
		});
	}


	/**
	 * List pipeline runs for a specific pipeline.
	 */
	public void listRuns(LoomRoutingContext lrc, UUID pipelineUuid) {
		checkPerm(lrc, READ_PIPELINE, () -> {
			io.metaloom.loom.rest.parameter.PagingParameters pagingParameters = lrc.pagingParams();
			io.metaloom.loom.rest.parameter.FilterParameters filterParameters = lrc.filterParams();
			io.metaloom.loom.rest.parameter.SortParameters sortParameters = lrc.sortParams();
			UUID from = pagingParameters.from();
			int limit = pagingParameters.limit();
			if (log.isDebugEnabled()) {
				log.debug("Loading page from {} limit: {}", from, limit);
			}
			io.metaloom.loom.db.page.Page<PipelineRun> page = pipelineRunDao.loadPageByPipeline(pipelineUuid, from, limit, filterParameters.filters(), sortParameters.sortBy(), sortParameters.sortOrder());
			io.metaloom.loom.rest.model.RestResponseModel<?> response = modelBuilder.toPipelineRunList(page);
			lrc.send(response);
		});
	}

	/**
	 * Load aggregated daily run statistics across all pipelines.
	 *
	 * <p>Returns a zero-filled window of daily buckets (default {@value #RUN_STATS_DEFAULT_DAYS} days,
	 * clamped to 1..{@value #RUN_STATS_MAX_DAYS} via the {@code days} query parameter) ending today.</p>
	 */
	public void loadRunStats(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_PIPELINE_RUN, () -> {
			int days = RUN_STATS_DEFAULT_DAYS;
			List<String> daysParam = lrc.queryParam("days");
			if (daysParam != null && !daysParam.isEmpty()) {
				try {
					days = Integer.parseInt(daysParam.get(0));
				} catch (NumberFormatException e) {
					throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "Invalid days parameter.");
				}
				days = Math.max(1, Math.min(RUN_STATS_MAX_DAYS, days));
			}
			java.time.LocalDate today = java.time.LocalDate.now();
			java.time.LocalDateTime since = today.minusDays(days - 1L).atStartOfDay();
			List<io.metaloom.loom.db.model.pipeline.PipelineRunDayStats> stats = pipelineRunDao.loadDailyStats(since);
			lrc.send(modelBuilder.toPipelineRunStats(stats, today, days));
		});
	}

	/**
	 * Load a single pipeline run.
	 */
	public void loadRun(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid) {
		checkPerm(lrc, READ_PIPELINE_RUN, () -> {
			PipelineRun run = pipelineRunDao.load(runUuid);
			if (run == null || !pipelineUuid.equals(run.getPipelineUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline run not found.");
			}
			lrc.send(modelBuilder.toPipelineRunRecord(run));
		});
	}

	/**
	 * List the items of a single pipeline run.
	 */
	public void listRunItems(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid) {
		checkPerm(lrc, READ_PIPELINE_RUN, () -> {
			PipelineRun run = pipelineRunDao.load(runUuid);
			if (run == null || !pipelineUuid.equals(run.getPipelineUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline run not found.");
			}
			io.metaloom.loom.rest.parameter.PagingParameters pagingParameters = lrc.pagingParams();
			io.metaloom.loom.rest.parameter.FilterParameters filterParameters = lrc.filterParams();
			io.metaloom.loom.rest.parameter.SortParameters sortParameters = lrc.sortParams();
			UUID from = pagingParameters.from();
			int limit = pagingParameters.limit();
			if (log.isDebugEnabled()) {
				log.debug("Loading run item page from {} limit: {}", from, limit);
			}
			io.metaloom.loom.db.page.Page<PipelineRunItem> page = pipelineRunItemDao.loadPageByRun(runUuid, from, limit, filterParameters.filters(), sortParameters.sortBy(), sortParameters.sortOrder());
			io.metaloom.loom.rest.model.RestResponseModel<?> response = modelBuilder.toPipelineRunItemList(page);
			lrc.send(response);
		});
	}

	/**
	 * List the node executions of a single run item.
	 *
	 * <p>This is the only route that exposes {@code pipeline_node_task}, and with it the
	 * {@code outputs} a node actually emitted. Without it a partially-failed run can only
	 * say <em>that</em> an item failed, never which node failed or what it produced - which
	 * is the first question anyone debugging a pipeline asks.</p>
	 *
	 * <p>Unpaged: see {@code PipelineModelBuilder#toPipelineNodeTaskList}. The item is
	 * resolved through its run and the run through its pipeline, so a task cannot be read by
	 * addressing it under a pipeline it does not belong to.</p>
	 */
	public void listRunItemTasks(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid, UUID itemUuid) {
		checkPerm(lrc, READ_PIPELINE_RUN, () -> {
			loadRunOr404(pipelineUuid, runUuid);
			PipelineRunItem item = pipelineRunItemDao.load(itemUuid);
			if (item == null || !runUuid.equals(item.getRunUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline run item not found.");
			}
			lrc.send(modelBuilder.toPipelineNodeTaskList(pipelineUuid, pipelineNodeTaskDao.loadByItem(itemUuid)));
		});
	}

	/**
	 * Serve the bytes of one debugging preview.
	 *
	 * <p>A preview is the only way to look at media a node <em>produced</em>: an
	 * {@code artifact/image} port carries a path on the worker that made it, which Loom
	 * cannot reach. The bytes are served from their own route rather than inlined into the
	 * task list so the browser caches them per image and revalidates with an ETag.</p>
	 *
	 * <p>Addressed through the full ownership chain (pipeline → run → item → task), so the
	 * bytes are exactly as reachable as the record that pointed at them and no more.</p>
	 */
	public void loadTaskPreview(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid, UUID itemUuid,
		UUID taskUuid, String portId) {
		checkPerm(lrc, READ_PIPELINE_RUN, () -> {
			loadRunOr404(pipelineUuid, runUuid);
			PipelineRunItem item = pipelineRunItemDao.load(itemUuid);
			if (item == null || !runUuid.equals(item.getRunUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline run item not found.");
			}
			io.metaloom.loom.db.model.pipeline.PipelineNodeTask task = pipelineNodeTaskDao.load(taskUuid);
			if (task == null || !itemUuid.equals(task.getItemUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Node task not found.");
			}

			JsonObject previews = task.getPreviews();
			JsonObject preview = previews == null ? null : previews.getJsonObject(portId);
			String encoded = preview == null ? null : preview.getString("data");
			if (encoded == null) {
				// Covers three different absences that are all a 404 to a byte fetch: the run had
				// no previews, this port had none, or the preview was skipped and carries only a
				// reason. The reason is already visible on the task record.
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No preview for this port.");
			}

			byte[] data;
			try {
				data = java.util.Base64.getDecoder().decode(encoded);
			} catch (IllegalArgumentException e) {
				log.warn("Preview for task {} port '{}' is not valid base64", taskUuid, portId);
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No preview for this port.");
			}

			String mimeType = preview.getString("mimeType", "application/octet-stream");
			// The bytes are immutable once written - a settled task is never re-run into the same
			// row - so the task uuid and port identify this content for good.
			String etag = "\"" + taskUuid + "-" + portId.hashCode() + "\"";
			io.vertx.core.http.HttpServerResponse response = lrc.routingContext().response();
			response.putHeader("Content-Type", mimeType);
			response.putHeader("ETag", etag);
			response.putHeader("Cache-Control", "private, max-age=3600");
			if (etag.equals(lrc.routingContext().request().getHeader("If-None-Match"))) {
				response.setStatusCode(304).end();
				return;
			}
			response.end(io.vertx.core.buffer.Buffer.buffer(data));
		});
	}

	/**
	 * Cancel an in-flight pipeline run.
	 *
	 * <p>Marks the run {@code CANCELLED} and stops its engine dispatching further node
	 * tasks. In-flight worker tasks cannot be recalled - the dispatcher has no reverse
	 * signal - so they settle naturally and their late results are ignored by the
	 * tracker's terminal guard.</p>
	 *
	 * <p>The run is marked terminal <em>before</em> the engine is stopped, so a run that
	 * completes naturally in the same instant cannot overwrite the cancellation.</p>
	 */
	public void cancelRun(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid) {
		checkPerm(lrc, UPDATE_PIPELINE_RUN, () -> {
			PipelineRun run = pipelineRunDao.load(runUuid);
			if (run == null || !pipelineUuid.equals(run.getPipelineUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline run not found.");
			}
			if (PipelineRunStatusResolver.isTerminal(run.getStatus())) {
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT,
					"Pipeline run is already " + run.getStatus() + ".");
			}

			// Mark terminal first so a natural completion racing this request cannot win.
			boolean applied = pipelineRunTracker.cancel(runUuid);

			// Stop the live engine if one is present. It may be absent - an offline run, or
			// one lost to a Loom restart - in which case the cancel is still recorded above.
			PipelineRunEngine engine = pipelineRunRegistry.get(runUuid);
			if (engine != null) {
				engine.cancel();
				pipelineRunRegistry.unregister(runUuid);
			}

			if (!applied) {
				// The run reached a terminal state between the check above and the cancel.
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT,
					"Pipeline run completed before it could be cancelled.");
			}

			// engine.cancel() sets runComplete without invoking the completion callbacks, so
			// the closing frame has to come from here - as it does for an undispatchable run.
			broadcastRunEvent(io.metaloom.loom.rest.model.pipeline.event.PipelineEventType.PIPELINE_COMPLETED, runPipelineName(run), runUuid);

			lrc.send(new io.metaloom.loom.rest.model.message.GenericMessageResponse().setMessage("Pipeline run cancelled"));
		});
	}

	/**
	 * Suspend an in-flight pipeline run.
	 *
	 * <p>Stops the engine dispatching further node tasks and withholds the source
	 * acknowledgement, which halts the scan itself. In-flight worker tasks are not recalled -
	 * the dispatcher has no reverse signal - so they settle normally and simply spawn nothing
	 * downstream until the run is resumed.</p>
	 *
	 * <p>The row is moved to {@code PAUSED} <em>before</em> the engine is gated, mirroring
	 * {@link #cancelRun}: a run completing naturally in the same instant must not be able to
	 * win against a half-applied pause.</p>
	 */
	public void pauseRun(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid) {
		checkPerm(lrc, UPDATE_PIPELINE_RUN, () -> {
			PipelineRun run = loadRunOr404(pipelineUuid, runUuid);
			if (PipelineRunStatusResolver.isTerminal(run.getStatus())) {
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT,
					"Pipeline run is already " + run.getStatus() + ".");
			}
			if (run.getStatus() == PipelineRunStatus.PAUSED) {
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT, "Pipeline run is already paused.");
			}

			if (!pipelineRunTracker.pause(runUuid)) {
				// The run reached a terminal state between the check above and the update.
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT,
					"Pipeline run could not be paused; it is no longer running.");
			}

			PipelineRunEngine engine = pipelineRunRegistry.get(runUuid);
			if (engine != null) {
				engine.pause();
			}

			// Announced after the gate is applied, so a client acting on the frame cannot
			// observe a run that calls itself paused while dispatch is still running.
			broadcastRunEvent(io.metaloom.loom.rest.model.pipeline.event.PipelineEventType.RUN_PAUSED, runPipelineName(run), runUuid);

			lrc.send(new io.metaloom.loom.rest.model.message.GenericMessageResponse().setMessage("Pipeline run paused"));
		});
	}

	/**
	 * Resume a suspended pipeline run.
	 *
	 * <p>Requires a live engine. A run whose engine was lost - to a Loom restart, or because
	 * it was an offline run - is refused with 409 rather than silently flipped back to
	 * {@code RUNNING}, which would produce a run that nothing will ever advance.</p>
	 *
	 * <p>The engine is ungated <em>before</em> the row moves back to {@code RUNNING}, so the
	 * run is never advertised as running while dispatch is still suspended.</p>
	 */
	public void resumeRun(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid) {
		checkPerm(lrc, UPDATE_PIPELINE_RUN, () -> {
			PipelineRun run = loadRunOr404(pipelineUuid, runUuid);
			if (run.getStatus() != PipelineRunStatus.PAUSED) {
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT,
					"Pipeline run is " + run.getStatus() + " and cannot be resumed.");
			}

			PipelineRunEngine engine = pipelineRunRegistry.get(runUuid);
			if (engine == null) {
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT,
					"Pipeline run is not live and cannot be resumed. Trigger a new run instead.");
			}

			if (!pipelineRunTracker.resume(runUuid)) {
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT,
					"Pipeline run could not be resumed; it is no longer paused.");
			}
			// Ungate last: unpause() dispatches immediately, and those tasks must not run
			// against a row that still claims to be paused.
			engine.unpause();

			broadcastRunEvent(io.metaloom.loom.rest.model.pipeline.event.PipelineEventType.RUN_RESUMED, runPipelineName(run), runUuid);

			lrc.send(new io.metaloom.loom.rest.model.message.GenericMessageResponse().setMessage("Pipeline run resumed"));
		});
	}

	/**
	 * What a run is halting at, and what it is currently holding.
	 *
	 * <p>
	 * Answered from the live engine, which is the only thing that knows: a breakpoint is run state,
	 * held in memory alongside the run it belongs to, and deliberately not written into the pipeline
	 * definition. A run whose engine is gone therefore reports nothing armed rather than 409 - there
	 * is nothing to hold anything back, which is the honest answer and not an error.
	 * </p>
	 */
	public void listBreakpoints(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid) {
		checkPerm(lrc, READ_PIPELINE_RUN, () -> {
			loadRunOr404(pipelineUuid, runUuid);
			PipelineRunEngine engine = pipelineRunRegistry.get(runUuid);
			lrc.send(modelBuilder.toBreakpointResponse(engine));
		});
	}

	/**
	 * Replace the set of nodes this run halts at.
	 *
	 * <p>
	 * Whole-set replacement rather than add/remove: the editor shows the armed set and sends what it
	 * should become, so the two cannot drift. Disarming a node releases whatever it was holding, so
	 * clearing a breakpoint always lets the run continue rather than stranding it.
	 * </p>
	 */
	public void setBreakpoints(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid) {
		checkPerm(lrc, UPDATE_PIPELINE_RUN, () -> {
			PipelineRun run = loadRunOr404(pipelineUuid, runUuid);
			PipelineRunEngine engine = requireLiveEngine(runUuid);

			PipelineBreakpointRequest request = lrc.requestBody(PipelineBreakpointRequest.class);
			List<String> nodeIds = validateBreakpoints(engine.getGraph(),
				request == null ? null : request.getNodeIds());

			engine.setBreakpoints(nodeIds);
			// No broadcast of the armed set itself: a second editor learns what is armed by
			// re-reading it, and the frames that matter - a node actually holding or being let
			// through - are emitted by the engine's own listener.
			log.info("Run {} is now halting at {}", runUuid, nodeIds);
			lrc.send(modelBuilder.toBreakpointResponse(engine));
		});
	}

	/**
	 * Let one node's held executions through, leaving the breakpoint armed.
	 */
	public void continueBreakpoint(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid, String nodeId) {
		checkPerm(lrc, UPDATE_PIPELINE_RUN, () -> {
			loadRunOr404(pipelineUuid, runUuid);
			PipelineRunEngine engine = requireLiveEngine(runUuid);
			int released = engine.releaseNode(nodeId);
			lrc.send(new io.metaloom.loom.rest.model.message.GenericMessageResponse()
				.setMessage("Released " + released + " held execution" + (released == 1 ? "" : "s") + " of node " + nodeId));
		});
	}

	/**
	 * Let exactly one held execution through - the step.
	 *
	 * <p>
	 * A step with nothing held is a 409 rather than a silent success. "Step" that quietly did
	 * nothing would look identical to "step" that advanced the run, which is the one ambiguity a
	 * debugger cannot afford.
	 * </p>
	 */
	public void stepRun(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid) {
		checkPerm(lrc, UPDATE_PIPELINE_RUN, () -> {
			loadRunOr404(pipelineUuid, runUuid);
			PipelineRunEngine engine = requireLiveEngine(runUuid);
			if (!engine.stepOne()) {
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT,
					"Pipeline run is not holding at a breakpoint, so there is nothing to step.");
			}
			lrc.send(modelBuilder.toBreakpointResponse(engine));
		});
	}

	/**
	 * Run one held execution again, optionally with different settings.
	 *
	 * <p>
	 * The counterpart to a breakpoint. Stopping at a node lets you see what it produced; this lets
	 * you change the setting you suspect and see the same input answered differently, without
	 * re-running the pipeline from the top and without discarding the attempt you are comparing
	 * against.
	 * </p>
	 *
	 * <p>
	 * ⚠️ <strong>The pipeline is not written.</strong> Options given here live in the engine for the
	 * rest of this run and nowhere else, exactly like the armed breakpoint set. Keeping a setting is
	 * a separate act through {@link #update}, which creates a new pipeline version — so
	 * experimenting on a live run can never quietly change what everyone else runs.
	 * </p>
	 */
	public void reExecuteNode(LoomRoutingContext lrc, UUID pipelineUuid, UUID runUuid, String nodeId) {
		checkPerm(lrc, UPDATE_PIPELINE_RUN, () -> {
			loadRunOr404(pipelineUuid, runUuid);
			PipelineRunEngine engine = requireLiveEngine(runUuid);

			PipelineNodeReExecuteRequest request = lrc.requestBody(PipelineNodeReExecuteRequest.class);
			if (request == null || request.getItemUuid() == null || request.getItemUuid().isBlank()) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
					"An itemUuid must be given: a re-execution runs one node over one item's input.");
			}
			// Reuses the breakpoint check, so a typo in a node id is reported the same way whether it
			// was armed or re-executed.
			validateBreakpoints(engine.getGraph(), List.of(nodeId));

			io.metaloom.loom.pipeline.graph.PipelineGraphNode node = engine.getGraph().getNode(nodeId);
			// Validated against the node's declared parameters before anything is changed, so a bad
			// value is a 400 rather than a worker failure the operator has to go and read logs for.
			pipelineValidationService.validateNodeOptions(node.getKind(), request.getOptions());

			int generation;
			try {
				generation = engine.reExecute(request.getItemUuid(), nodeId, request.getElementSeq(),
					request.getOptions());
			} catch (IllegalStateException e) {
				// Not held. A conflict rather than a bad request: the call is well-formed and would
				// have been accepted a moment earlier, or will be once the item reaches this node.
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT, e.getMessage());
			} catch (IllegalArgumentException e) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, e.getMessage());
			}

			log.info("Re-executing node '{}' of run {} as generation {}", nodeId, runUuid, generation);
			lrc.send(new PipelineNodeReExecuteResponse()
				.setGeneration(generation)
				.setNodeId(nodeId)
				.setOptions(engine.getOptionOverrides().getOrDefault(nodeId, node.getOptions())));
		});
	}

	/**
	 * The live engine for a run, or a 409 explaining why there is not one.
	 *
	 * <p>
	 * Mirrors {@link #resumeRun}: a run lost to a Loom restart, or one that was never live, cannot
	 * be debugged. Refusing is better than accepting a breakpoint nothing will ever honour.
	 * </p>
	 */
	private PipelineRunEngine requireLiveEngine(UUID runUuid) {
		PipelineRunEngine engine = pipelineRunRegistry.get(runUuid);
		if (engine == null) {
			throw new LoomRestException(409, LoomRestErrorCode.CONFLICT,
				"Pipeline run is not live and cannot be debugged. Trigger a new run instead.");
		}
		return engine;
	}

	/**
	 * Check every requested breakpoint against the graph it is meant to halt.
	 *
	 * <p>
	 * A breakpoint on a node id that does not exist would arm silently and never fire, and the
	 * operator would conclude the feature is broken rather than that they made a typo. Naming the
	 * offending id is the whole value of the check.
	 * </p>
	 *
	 * @return the validated ids, never null
	 */
	private List<String> validateBreakpoints(io.metaloom.loom.pipeline.graph.PipelineGraph graph, List<String> nodeIds) {
		if (nodeIds == null || nodeIds.isEmpty()) {
			return List.of();
		}
		List<String> unknown = nodeIds.stream().filter(id -> graph.getNode(id) == null).toList();
		if (!unknown.isEmpty()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"No such node in this pipeline: " + String.join(", ", unknown));
		}
		return nodeIds;
	}

	/**
	 * Forward the engine's holds and releases to the UI socket.
	 *
	 * <p>
	 * Sent immediately rather than folded into the {@code NODE_STATS} tick, for the same reason a
	 * failure is: a hold happens because a person asked for it and is worthless a second late.
	 * </p>
	 */
	private void attachBreakpointBroadcast(PipelineRunEngine engine, String pipelineName, UUID runUuid) {
		engine.onBreakpoint((itemId, mediaPath, nodeId, elementSeq, held) -> {
			try {
				pipelineEventBroadcaster.broadcast(new io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage()
					.setType(held
						? io.metaloom.loom.rest.model.pipeline.event.PipelineEventType.NODE_BREAKPOINT_HELD
						: io.metaloom.loom.rest.model.pipeline.event.PipelineEventType.NODE_BREAKPOINT_RELEASED)
					.setPipelineName(pipelineName)
					.setPipelineRunUuid(runUuid.toString())
					.setNodeId(nodeId)
					.setItemUuid(itemId)
					.setElementSeq(elementSeq)
					.setMediaPath(mediaPath)
					.setTimestamp(System.currentTimeMillis()));
			} catch (Exception e) {
				// The run really did stop even if nobody could be told about it.
				log.error("Failed to broadcast a breakpoint frame for run {}", runUuid, e);
			}
		});
	}

	/**
	 * Load a run, rejecting an unknown run or one belonging to a different pipeline.
	 */
	private PipelineRun loadRunOr404(UUID pipelineUuid, UUID runUuid) {
		PipelineRun run = pipelineRunDao.load(runUuid);
		if (run == null || !pipelineUuid.equals(run.getPipelineUuid())) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline run not found.");
		}
		return run;
	}

	/**
	 * The pipeline name a run was started under.
	 *
	 * <p>
	 * Resolved from the run's own version number rather than from the latest version, because a run that started before a rename must keep announcing
	 * itself under the name its subscribers filtered on. Falls back to the latest version, and finally to the uuid, so an event is never dropped merely
	 * because a version row is missing.
	 * </p>
	 */
	private String runPipelineName(PipelineRun run) {
		PipelineVersion version = pipelineVersionDao.loadByPipelineAndVersion(run.getPipelineUuid(), run.getPipelineVersion());
		if (version == null) {
			version = pipelineVersionDao.loadLatestByPipeline(run.getPipelineUuid());
		}
		return version != null ? version.getName() : String.valueOf(run.getPipelineUuid());
	}

	/**
	 * Announce a run-level lifecycle change on the UI events socket.
	 *
	 * <p>
	 * Run lifecycle is broadcast from here and not from {@code RunStatsAggregator}, which only ever sees node settles. These four types carry no counters:
	 * a client that wants numbers reads them off the following {@code NODE_STATS} tick or refetches the run.
	 * </p>
	 *
	 * <p>
	 * Never allowed to fail the operation that triggered it - a run really did pause even if nobody could be told about it.
	 * </p>
	 */
	private void broadcastRunEvent(io.metaloom.loom.rest.model.pipeline.event.PipelineEventType type, String pipelineName, UUID runUuid) {
		try {
			pipelineEventBroadcaster.broadcast(new io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage()
				.setType(type)
				.setPipelineName(pipelineName)
				.setPipelineRunUuid(runUuid.toString())
				.setTimestamp(System.currentTimeMillis()));
		} catch (Exception e) {
			log.error("Failed to broadcast {} for run {}", type, runUuid, e);
		}
	}

	/**
	 * List all versions of a pipeline.
	 */
	public void listVersions(LoomRoutingContext lrc, UUID pipelineUuid) {
		checkPerm(lrc, READ_PIPELINE_VERSION, () -> {
			io.metaloom.loom.rest.parameter.PagingParameters pagingParameters = lrc.pagingParams();
			io.metaloom.loom.rest.parameter.FilterParameters filterParameters = lrc.filterParams();
			io.metaloom.loom.rest.parameter.SortParameters sortParameters = lrc.sortParams();
			UUID from = pagingParameters.from();
			int limit = pagingParameters.limit();
			if (log.isDebugEnabled()) {
				log.debug("Loading versions page from {} limit: {}", from, limit);
			}
			io.metaloom.loom.db.page.Page<PipelineVersion> page = pipelineVersionDao.loadPageByPipeline(pipelineUuid, from, limit, filterParameters.filters(), sortParameters.sortBy(), sortParameters.sortOrder());
			io.metaloom.loom.rest.model.RestResponseModel<?> response = modelBuilder.toPipelineVersionList(pipelineUuid, page);
			lrc.send(response);
		});
	}

	/**
	 * Load a specific version of a pipeline.
	 */
	public void loadVersion(LoomRoutingContext lrc, UUID pipelineUuid, int versionNumber) {
		checkPerm(lrc, READ_PIPELINE_VERSION, () -> {
			PipelineVersion version = pipelineVersionDao.loadByPipelineAndVersion(pipelineUuid, versionNumber);
			if (version == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline version not found.");
			}
			PipelineResponse response = modelBuilder.toVersionResponse(pipelineUuid, version);
			lrc.send(response);
		});
	}

	/**
	 * Restore a pipeline version (creates a new version with the restored content).
	 */
	public void restoreVersion(LoomRoutingContext lrc, UUID pipelineUuid, int versionNumber) {
		checkPerm(lrc, RESTORE_PIPELINE_VERSION, () -> {
			PipelineVersion versionToRestore = pipelineVersionDao.loadByPipelineAndVersion(pipelineUuid, versionNumber);
			if (versionToRestore == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline version not found.");
			}

			PipelineVersionRestoreRequest request;
			try {
				request = lrc.requestBody(PipelineVersionRestoreRequest.class);
			} catch (Exception e) {
				request = new PipelineVersionRestoreRequest();
			}
			if (request == null) {
				request = new PipelineVersionRestoreRequest();
			}

			UUID userUuid = lrc.userUuid();
			Pipeline pipeline = dao().loadWithLatestVersion(pipelineUuid);
			if (pipeline == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Pipeline not found.");
			}

			// Get the latest version to determine the next version number
			PipelineVersion latestVersion = pipelineVersionDao.loadLatestByPipeline(pipeline.getUuid());
			int nextVersion = latestVersion != null ? latestVersion.getVersionNumber() + 1 : 1;

			// Create new version with restored content
			PipelineVersion restoredVersion = pipelineVersionDao.createVersion(
				userUuid,
				pipeline.getUuid(),
				nextVersion,
				request.getName() != null ? request.getName() : versionToRestore.getName(),
				request.getDescription() != null ? request.getDescription() : versionToRestore.getDescription(),
				versionToRestore.getDefinition(),
				versionToRestore.isEnabled(),
				versionToRestore.getPriority(),
				versionToRestore.isDryRun(),
				versionToRestore.getMeta()
			);
			pipelineVersionDao.store(restoredVersion);

			// Update pipeline with latest version reference
			pipeline.setLatestVersionUuid(restoredVersion.getUuid());
			setEditor(pipeline, userUuid);
			dao().update(pipeline);

			// The restore created a new latest version, so the pipeline renders from it.
			PipelineResponse response = modelBuilder.toResponse(pipeline, restoredVersion);
			lrc.send(response, 201);
		});
	}

}
