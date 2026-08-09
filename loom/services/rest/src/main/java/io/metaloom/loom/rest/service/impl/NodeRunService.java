package io.metaloom.loom.rest.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.NodeExecOptions;
import io.metaloom.loom.api.pipeline.PipelineRunKind;
import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.pipeline.engine.ItemState;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.engine.PortPayloads;
import io.metaloom.loom.pipeline.engine.RunStateStore;
import io.metaloom.loom.pipeline.graph.GraphValidationException;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.rest.model.noderun.NodeProbeRequest;
import io.metaloom.loom.rest.model.noderun.NodeProbeResponse;
import io.metaloom.loom.rest.model.noderun.NodeRunItemResult;
import io.metaloom.loom.rest.model.noderun.NodeRunRequest;
import io.metaloom.loom.rest.model.noderun.NodeRunResponse;
import io.metaloom.loom.rest.model.noderun.NodeRunStatusResponse;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventType;
import io.metaloom.loom.rest.validation.PipelineValidationService;
import io.metaloom.loom.rest.validation.ValidationException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

/**
 * Runs nodes on chosen assets without a stored pipeline.
 *
 * <h2>One execution path, two shapes</h2>
 *
 * <p>
 * Both entry points build a {@code PipelineGraph} and run it through a {@link PipelineRunEngine}, the
 * same engine a catalog run uses. There is deliberately no second dispatch path: node tasks leave over
 * {@code WebSocketNodeDispatcher} and results come back through {@code ProcessorEndpoint} into
 * {@link PipelineRunRegistry}, none of which knows or cares that the run has no pipeline behind it.
 * </p>
 *
 * <p>
 * What differs is only how the caller waits. {@link #probe} is one node over one asset and is awaited
 * inside the request under a hard timeout; {@link #startRun} is a graph over many assets and answers
 * with a handle the caller polls. The first exists because "what does {@code vlm} say about this
 * image" is a question, not a job; the second because a pass over two hundred images cannot be
 * answered inside anybody's request timeout.
 * </p>
 *
 * <h2>Where the media come from</h2>
 *
 * <p>
 * Nowhere near a worker. The graph's source is {@code loom-fetch}, which Loom executes itself by
 * feeding {@link PipelineRunEngine#onItemDiscovered(MediaRef)} straight from {@code asset_binary}; no
 * {@code SOURCE_TASK} is sent and no worker has to advertise a source kind. See
 * {@link AdHocGraphBuilder} and {@code spec/chat/AGENTIC_NODE_EXECUTION.md}.
 * </p>
 */
@Singleton
public class NodeRunService {

	private static final Logger log = LoggerFactory.getLogger(NodeRunService.class);

	/** State reported when a probe never reached a worker. Not a node state - nothing ran. */
	public static final String STATE_REJECTED = "REJECTED";

	/** Assumed per-node settle time when nothing better is known, used only for the ETA estimate. */
	private static final long ETA_PER_NODE_MS = 5_000;

	private final DaoCollection daos;
	private final PipelineRunDao pipelineRunDao;
	private final PipelineRunItemDao pipelineRunItemDao;
	private final PipelineNodeTaskDao pipelineNodeTaskDao;
	private final PipelineValidationService validationService;
	private final PipelineGraphParser graphParser;
	private final ProcessorRegistry processorRegistry;
	private final PipelineRunRegistry runRegistry;
	private final PipelineRunEngineFactory engineFactory;
	private final PipelineRunTracker runTracker;
	private final NotificationDispatcher notifications;
	private final AdhocNodeResultWriter resultWriter;
	private final ProbeEligibility eligibility;
	private final NodeExecOptions options;
	private final Vertx vertx;

	@Inject
	public NodeRunService(DaoCollection daos, PipelineRunDao pipelineRunDao, PipelineRunItemDao pipelineRunItemDao,
		PipelineNodeTaskDao pipelineNodeTaskDao, PipelineValidationService validationService,
		io.metaloom.loom.nodes.spec.NodeDescriptorRegistry nodeDescriptorRegistry, ProcessorRegistry processorRegistry,
		PipelineRunRegistry runRegistry, PipelineRunEngineFactory engineFactory, PipelineRunTracker runTracker,
		NotificationDispatcher notifications, AdhocNodeResultWriter resultWriter, ProbeEligibility eligibility,
		LoomOptions loomOptions, Vertx vertx) {
		this.daos = daos;
		this.pipelineRunDao = pipelineRunDao;
		this.pipelineRunItemDao = pipelineRunItemDao;
		this.pipelineNodeTaskDao = pipelineNodeTaskDao;
		this.validationService = validationService;
		this.processorRegistry = processorRegistry;
		this.runRegistry = runRegistry;
		this.engineFactory = engineFactory;
		this.runTracker = runTracker;
		this.notifications = notifications;
		this.resultWriter = resultWriter;
		this.eligibility = eligibility;
		this.options = loomOptions.getNodeExec();
		this.vertx = vertx;
		// Registry-backed: without it the parser skips port checking and calls every node SINGLE, so an
		// ad-hoc graph would fan out differently from the same graph saved as a pipeline.
		this.graphParser = new PipelineGraphParser(nodeDescriptorRegistry);
	}

	// ── EXE2: the synchronous probe ───────────────────────────────────────

	/**
	 * Run one node against one asset and wait for the result.
	 *
	 * <p>
	 * Every way this can go wrong before dispatch - an unknown or ineligible kind, invalid options, an
	 * asset with no stored binary, no worker that will take the kind - resolves to a
	 * {@link NodeProbeResponse} carrying the reason, not to a failed future. The caller is usually a
	 * language model, and "the call failed" is something it retries whereas "vlm is not available"
	 * is something it can work around.
	 * </p>
	 */
	public Future<NodeProbeResponse> probe(UUID userUuid, NodeProbeRequest request) {
		requireEnabled();
		if (request == null || request.getKind() == null || request.getAssetUuid() == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "A node kind and an asset uuid are required.");
		}
		String kind = request.getKind();

		String ineligible = eligibility.rejectionReason(kind);
		if (ineligible != null) {
			return Future.succeededFuture(rejected(kind, request.getAssetUuid(), ineligible));
		}
		try {
			validationService.validateNodeOptions(kind, request.getOptions());
		} catch (ValidationException e) {
			return Future.succeededFuture(rejected(kind, request.getAssetUuid(), e.getMessage()));
		}

		ResolvedAsset resolved = resolveAsset(request.getAssetUuid());
		if (resolved == null) {
			return Future.succeededFuture(rejected(kind, request.getAssetUuid(),
				"Asset " + request.getAssetUuid() + " has no stored binary path, so there is nothing to run a node against."));
		}

		PipelineGraph graph;
		try {
			JsonObject definition = AdHocGraphBuilder.singleNodeDefinition(kind, request.getOptions());
			validationService.validateDefinition(definition);
			graph = graphParser.parse("probe " + kind, definition, true, false, 0);
		} catch (ValidationException | GraphValidationException e) {
			return Future.succeededFuture(rejected(kind, request.getAssetUuid(), e.getMessage()));
		}

		Set<String> unsupported = PipelineEndpointService.unsupportedNodeKinds(graph, processorRegistry);
		// loom-fetch is executed by Loom, so no worker advertises it and the precheck would always
		// reject it. Every other kind in the graph really does need a worker.
		unsupported.remove(AdHocGraphBuilder.SOURCE_KIND);
		if (!unsupported.isEmpty()) {
			return Future.succeededFuture(rejected(kind, request.getAssetUuid(),
				"No worker currently advertises '" + String.join(", ", unsupported) + "'."));
		}

		// No run row: a probe is answered and forgotten. The uuid exists only so results can be routed
		// back through the registry, which is an in-memory map and needs no persistence at all.
		UUID runUuid = UUID.randomUUID();
		boolean persist = persistFlag(request.getPersist());
		Promise<NodeProbeResponse> promise = Promise.promise();

		PipelineRunEngine engine = engineFactory.assemble(graph, runUuid, userUuid, RunStateStore.NOOP,
			PipelineRunEngineFactory.EngineConfig.forProbe("probe " + kind));

		// Both the natural finish and the timeout resolve the same promise, and they genuinely race:
		// tryComplete is what makes the loser a no-op rather than an IllegalStateException.
		long timer = vertx.setTimer(options.getProbeTimeoutMs(), id -> {
			engine.cancel();
			runRegistry.unregister(runUuid);
			promise.tryComplete(rejected(kind, request.getAssetUuid(),
				"Node '" + kind + "' did not finish within " + options.getProbeTimeoutMs()
					+ "ms. Work of this size belongs in a node run, which returns a handle instead of waiting."));
		});

		engine.onCompletion(summary -> {
			vertx.cancelTimer(timer);
			runRegistry.unregister(runUuid);
			try {
				promise.tryComplete(renderProbe(runUuid, userUuid, kind, request.getAssetUuid(), resolved, engine, persist));
			} catch (Exception e) {
				log.error("Failed to render probe result for kind {} on asset {}", kind, request.getAssetUuid(), e);
				promise.tryComplete(rejected(kind, request.getAssetUuid(), "The node finished but its result could not be read: " + e.getMessage()));
			}
		});

		runRegistry.register(runUuid, engine);
		engine.start();
		engine.onItemDiscovered(resolved.media());
		engine.onSourceComplete(1);

		return promise.future();
	}

	private NodeProbeResponse renderProbe(UUID runUuid, UUID userUuid, String kind, UUID assetUuid, ResolvedAsset resolved,
		PipelineRunEngine engine, boolean persist) {
		Map<String, ItemState> items = engine.getItems();
		ItemState item = items.isEmpty() ? null : items.values().iterator().next();
		Map<String, NodeTaskResult> results = item == null ? Map.of() : item.getResults();

		NodeTaskResult nodeResult = results.get(AdHocGraphBuilder.nodeIdFor(kind));
		NodeProbeResponse response = new NodeProbeResponse()
			.setNodeKind(kind)
			.setAssetUuid(assetUuid)
			.setText(NodeResultRenderer.renderText(resolved.media(), results, options.getResultMaxChars()));

		if (nodeResult == null) {
			return response.setState(STATE_REJECTED)
				.setMessage("The node produced no result. It may have been skipped because its inputs were not satisfied.");
		}

		response.setState(String.valueOf(nodeResult.getState()))
			.setDurationMs(nodeResult.getDurationMs())
			.setOutputs(NodeResultRenderer.renderOutputs(nodeResult.getOutputs()))
			.setMessage(nodeResult.getMessage());

		if (persist && nodeResult.getState() == NodeState.COMPLETED) {
			resultWriter.writeProbe(runUuid, userUuid, assetUuid, kind, nodeResult);
		}
		return response;
	}

	// ── EXE3/EXE5: the asynchronous run ───────────────────────────────────

	/**
	 * Start an ad-hoc run over many assets and return a handle immediately.
	 *
	 * <p>
	 * The response is written after the engine has been started and the items fed in, both of which are
	 * non-blocking - dispatch is a websocket write - so the caller gets its uuid in milliseconds no
	 * matter how much work was accepted.
	 * </p>
	 */
	public NodeRunResponse startRun(UUID userUuid, NodeRunRequest request) {
		requireEnabled();
		if (request == null || request.getDefinition() == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "A definition is required.");
		}
		List<UUID> assetUuids = request.getAssetUuids();
		if (assetUuids == null || assetUuids.isEmpty()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "At least one asset uuid is required.");
		}
		if (assetUuids.size() > options.getMaxAssets()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"An ad-hoc run may cover at most " + options.getMaxAssets() + " assets; " + assetUuids.size() + " were given.");
		}

		JsonObject definition;
		PipelineGraph graph;
		try {
			definition = AdHocGraphBuilder.withLoomFetchSource(request.getDefinition());
			validationService.validateDefinition(definition);
			graph = graphParser.parse(definitionName(definition), definition, true,
				Boolean.TRUE.equals(request.getDryRun()), 0);
		} catch (ValidationException | GraphValidationException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, e.getMessage());
		}
		if (graph.size() > options.getMaxNodes()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"An ad-hoc definition may contain at most " + options.getMaxNodes() + " nodes; this one has " + graph.size() + ".");
		}
		// Checked against the database rather than an in-memory counter: a counter resets on restart
		// while the runs it was counting are recovered and still occupying workers.
		int active = pipelineRunDao.countActiveAdhocByCreator(userUuid);
		if (active >= options.getMaxActiveJobsPerUser()) {
			throw new LoomRestException(429, LoomRestErrorCode.BAD_REQUEST,
				"You already have " + active + " ad-hoc run(s) in flight, which is the limit. Wait for one to finish or cancel it.");
		}

		Set<String> unsupported = PipelineEndpointService.unsupportedNodeKinds(graph, processorRegistry);
		unsupported.remove(AdHocGraphBuilder.SOURCE_KIND);
		if (!unsupported.isEmpty()) {
			throw new LoomRestException(503, LoomRestErrorCode.INTERNAL_ERROR,
				"No processor available for node kind(s): " + String.join(", ", unsupported));
		}

		// Resolve before the row is created, so a request that resolves nothing costs no run at all.
		List<UUID> rejectedAssets = new ArrayList<>();
		Map<UUID, ResolvedAsset> resolvedAssets = new LinkedHashMap<>();
		for (UUID assetUuid : assetUuids) {
			ResolvedAsset resolved = resolveAsset(assetUuid);
			if (resolved == null) {
				rejectedAssets.add(assetUuid);
			} else {
				resolvedAssets.put(assetUuid, resolved);
			}
		}
		if (resolvedAssets.isEmpty()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"None of the " + assetUuids.size() + " asset(s) has a stored binary path, so there is nothing to run against.");
		}

		PipelineRun run = pipelineRunDao.createAdhocRun(userUuid, definition);
		run.setStatus(PipelineRunStatus.RUNNING);
		run.setDryRun(Boolean.TRUE.equals(request.getDryRun()));
		pipelineRunDao.store(run);
		UUID runUuid = run.getUuid();
		String label = AdhocRuns.label(run);

		// The same durable store a catalog run uses. That is what makes the status route answer after a
		// restart rather than only while the engine happens to be in memory.
		RunStateStore store = new DaoRunStateStore(pipelineRunDao, pipelineRunItemDao, pipelineNodeTaskDao, runUuid, userUuid);
		PipelineRunEngine engine = engineFactory.assemble(graph, runUuid, userUuid, store,
			PipelineRunEngineFactory.EngineConfig.forRun(label, Boolean.TRUE.equals(request.getDebug()), List.of()));

		if (persistFlag(request.getPersist())) {
			// Written at the end from the persisted task rows rather than as each node settles: the
			// settle callback carries only a state and a message, and it fires on the engine thread
			// with the monitor held, which is the last place to start issuing database writes.
			engine.onCompletion(summary -> persistLedger(runUuid, userUuid));
		}
		// The durable, out-of-turn completion signal. The caller may be a chat turn that ended minutes
		// ago, so there has to be something to come back to.
		engine.onCompletion(summary -> notifications.nodeRunCompleted(userUuid, runUuid, label,
			(int) summary.getSuccessCount(), (int) summary.getFailureCount(), (int) summary.getSkippedCount()));

		runRegistry.register(runUuid, engine);
		engine.start();
		engineFactory.broadcastRunEvent(PipelineEventType.PIPELINE_STARTED, label, runUuid);

		for (ResolvedAsset resolved : resolvedAssets.values()) {
			engine.onItemDiscovered(resolved.media());
		}
		engine.onSourceComplete(resolvedAssets.size());

		log.info("Ad-hoc run {} started ({} node(s), {} item(s), {} rejected)", runUuid, graph.size(),
			resolvedAssets.size(), rejectedAssets.size());

		return new NodeRunResponse()
			.setUuid(runUuid)
			.setStatus(String.valueOf(PipelineRunStatus.RUNNING))
			.setAccepted(resolvedAssets.size())
			.setRejected(rejectedAssets.size())
			.setRejectedAssetUuids(rejectedAssets)
			.setEtaMs((long) resolvedAssets.size() * graph.size() * ETA_PER_NODE_MS)
			.setMessage(resolvedAssets.size() + " item(s) accepted");
	}

	/**
	 * Status and, on request, results of one of the caller's ad-hoc runs.
	 *
	 * <p>
	 * A run belonging to somebody else answers <b>404, not 403</b>: a 403 would confirm the uuid exists
	 * and let a caller enumerate other people's jobs.
	 * </p>
	 */
	public NodeRunStatusResponse status(UUID userUuid, UUID runUuid, boolean includeResults) {
		requireEnabled();
		PipelineRun run = loadOwnAdhocOr404(userUuid, runUuid);
		NodeRunStatusResponse response = toStatus(run);
		if (includeResults) {
			response.setResults(loadResults(runUuid));
		}
		return response;
	}

	/** The caller's own ad-hoc runs, newest first. */
	public Page<PipelineRun> list(UUID userUuid, UUID fromId, int pageSize) {
		requireEnabled();
		return pipelineRunDao.loadAdhocPageByCreator(userUuid, fromId, pageSize, List.of(), null, null);
	}

	/**
	 * Cancel one of the caller's ad-hoc runs.
	 *
	 * <p>
	 * The row is marked terminal <em>before</em> the engine is stopped, mirroring
	 * {@code PipelineEndpointService.cancelRun}: a run that completes naturally in the same instant must
	 * not be able to overwrite the cancellation.
	 * </p>
	 */
	public void cancel(UUID userUuid, UUID runUuid) {
		requireEnabled();
		PipelineRun run = loadOwnAdhocOr404(userUuid, runUuid);
		if (PipelineRunStatusResolver.isTerminal(run.getStatus())) {
			throw new LoomRestException(409, LoomRestErrorCode.CONFLICT, "Node run is already " + run.getStatus() + ".");
		}

		boolean applied = runTracker.cancel(runUuid);

		PipelineRunEngine engine = runRegistry.get(runUuid);
		if (engine != null) {
			engine.cancel();
			runRegistry.unregister(runUuid);
		}

		if (!applied) {
			throw new LoomRestException(409, LoomRestErrorCode.CONFLICT, "Node run completed before it could be cancelled.");
		}

		// cancel() sets the engine complete without invoking its completion callbacks, so the closing
		// frame has to be emitted here or a subscriber would wait forever on a run that already stopped.
		engineFactory.broadcastRunEvent(PipelineEventType.PIPELINE_COMPLETED, AdhocRuns.label(run), runUuid);
	}

	// ── Internals ─────────────────────────────────────────────────────────

	private PipelineRun loadOwnAdhocOr404(UUID userUuid, UUID runUuid) {
		PipelineRun run = pipelineRunDao.load(runUuid);
		if (run == null || run.getKind() != PipelineRunKind.ADHOC || !userUuid.equals(run.getCreatorUuid())) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Node run not found.");
		}
		return run;
	}

	/** Build the status view of a run row. Public so the endpoint can render a listing page with it. */
	public NodeRunStatusResponse toStatus(PipelineRun run) {
		JsonObject meta = run.getMeta();
		return new NodeRunStatusResponse()
			.setUuid(run.getUuid())
			.setStatus(String.valueOf(run.getStatus()))
			.setMediaCount(run.getMediaCount())
			.setSuccessCount(run.getSuccessCount())
			.setFailureCount(run.getFailureCount())
			.setSkippedCount(run.getSkippedCount())
			.setStarted(run.getStarted() == null ? null : run.getStarted().toString())
			.setFinished(run.getFinished() == null ? null : run.getFinished().toString())
			.setDurationMs(run.getDurationMs())
			.setErrorMessage(run.getErrorMessage())
			.setDefinition(meta == null ? null : meta.getJsonObject(PipelineRun.META_DEFINITION));
	}

	/**
	 * Record every completed node of a finished run in the per-asset ledger.
	 *
	 * <p>
	 * Reads the rows the run already persisted, so this is the same view {@link #status} serves and
	 * needs no state carried through the engine. Failures are logged per row by the writer; one asset
	 * that cannot be resolved must not stop the rest being recorded.
	 * </p>
	 */
	private void persistLedger(UUID runUuid, UUID userUuid) {
		try {
			Map<UUID, PipelineRunItem> itemsByUuid = new LinkedHashMap<>();
			for (PipelineRunItem item : pipelineRunItemDao.loadByRun(runUuid)) {
				itemsByUuid.put(item.getUuid(), item);
			}
			for (PipelineNodeTask task : pipelineNodeTaskDao.loadByRun(runUuid)) {
				if (AdHocGraphBuilder.SOURCE_NODE_ID.equals(task.getNodeId())
					|| task.getState() != io.metaloom.loom.api.pipeline.NodeTaskState.COMPLETED) {
					continue;
				}
				UUID assetUuid = assetUuidOf(itemsByUuid.get(task.getItemUuid()));
				if (assetUuid == null) {
					continue;
				}
				resultWriter.writeTask(runUuid, userUuid, assetUuid, task,
					NodeResultRenderer.renderOutputs(PortPayloads.decode(task.getOutputs())));
			}
		} catch (Exception e) {
			log.error("Failed to record ad-hoc ledger entries for run {}", runUuid, e);
		}
	}

	/**
	 * Per-item node results, read from the persisted task rows.
	 *
	 * <p>
	 * Two queries for the whole run rather than one per item: a run over two hundred assets would
	 * otherwise issue two hundred queries to render one status page.
	 * </p>
	 */
	private List<NodeRunItemResult> loadResults(UUID runUuid) {
		Map<UUID, PipelineRunItem> itemsByUuid = new LinkedHashMap<>();
		for (PipelineRunItem item : pipelineRunItemDao.loadByRun(runUuid)) {
			itemsByUuid.put(item.getUuid(), item);
		}

		List<NodeRunItemResult> results = new ArrayList<>();
		for (PipelineNodeTask task : pipelineNodeTaskDao.loadByRun(runUuid)) {
			if (AdHocGraphBuilder.SOURCE_NODE_ID.equals(task.getNodeId())) {
				// Synthesised by the engine, never dispatched; reporting it would make every run look
				// as if it had one more node than the caller asked for.
				continue;
			}
			PipelineRunItem item = itemsByUuid.get(task.getItemUuid());
			results.add(new NodeRunItemResult()
				.setAssetUuid(assetUuidOf(item))
				.setMediaPath(item == null ? null : item.getMediaPath())
				.setNodeId(task.getNodeId())
				.setNodeKind(task.getNodeKind())
				.setState(String.valueOf(task.getState()))
				.setOutputs(NodeResultRenderer.renderOutputs(PortPayloads.decode(task.getOutputs())))
				.setMessage(task.getErrorMessage())
				.setDurationMs(task.getDurationMs()));
		}
		return results;
	}

	/**
	 * The asset an item belongs to, resolved through its sha512.
	 *
	 * <p>
	 * The run item records the media, not the asset: the pipeline machinery is built around paths and
	 * hashes because a worker may see media Loom has never catalogued. For an ad-hoc run the asset is
	 * always known, and the hash is what connects the two back up.
	 * </p>
	 */
	private UUID assetUuidOf(PipelineRunItem item) {
		if (item == null || item.getSha512() == null) {
			return null;
		}
		try {
			Asset asset = daos.assetDao().loadBySHA512(SHA512.fromString(item.getSha512()));
			return asset == null ? null : asset.getUuid();
		} catch (Exception e) {
			log.debug("Could not resolve the asset for run item {}", item.getUuid(), e);
			return null;
		}
	}

	/**
	 * The media reference for an asset, or null when it has no stored binary.
	 *
	 * <p>
	 * The path comes from the primary binary, the hash and size from the asset itself. A node runs
	 * against bytes on a worker's filesystem, so an asset Loom knows of but cannot point at is not
	 * something a node can be run on - and saying so is better than dispatching a task that will fail
	 * on the worker with a file-not-found.
	 * </p>
	 */
	private ResolvedAsset resolveAsset(UUID assetUuid) {
		Asset asset = daos.assetDao().load(assetUuid);
		if (asset == null) {
			return null;
		}
		AssetBinary binary = daos.assetBinaryDao().loadPrimaryByAssetUuid(assetUuid);
		if (binary == null || binary.getPath() == null) {
			return null;
		}
		MediaRef media = new MediaRef(binary.getPath(),
			asset.getSHA512() == null ? null : asset.getSHA512().toString(),
			asset.getSize(),
			mediaTypeOf(binary.getMimeType() != null ? binary.getMimeType() : asset.getMimeType()));
		return new ResolvedAsset(assetUuid, media);
	}

	/**
	 * The coarse media family a mime type belongs to.
	 *
	 * <p>
	 * Best-effort by design, exactly as a source node's guess is: {@link MediaRef#UNKNOWN} is a normal
	 * answer and the real check happens on the worker, where the file itself can be inspected.
	 * </p>
	 */
	private static String mediaTypeOf(String mimeType) {
		if (mimeType == null) {
			return MediaRef.UNKNOWN;
		}
		String lower = mimeType.toLowerCase(java.util.Locale.ROOT);
		if (lower.startsWith("image/")) {
			return MediaRef.IMAGE;
		}
		if (lower.startsWith("video/")) {
			return MediaRef.VIDEO;
		}
		if (lower.startsWith("audio/")) {
			return MediaRef.AUDIO;
		}
		if (lower.startsWith("text/") || lower.startsWith("application/pdf")) {
			return MediaRef.DOCUMENT;
		}
		return MediaRef.UNKNOWN;
	}

	private static String definitionName(JsonObject definition) {
		String name = definition.getString("name");
		return name == null || name.isBlank() ? "ad-hoc" : name;
	}

	private boolean persistFlag(Boolean requested) {
		return requested != null ? requested : options.isPersistDefault();
	}

	private void requireEnabled() {
		if (!options.isEnabled()) {
			throw new LoomRestException(503, LoomRestErrorCode.INTERNAL_ERROR,
				"Ad-hoc node execution is disabled (LOOM_AGENT_EXEC_ENABLED).");
		}
	}

	private static NodeProbeResponse rejected(String kind, UUID assetUuid, String reason) {
		return new NodeProbeResponse()
			.setState(STATE_REJECTED)
			.setNodeKind(kind)
			.setAssetUuid(assetUuid)
			.setMessage(reason)
			.setText(reason);
	}

	/** An asset that can actually be run against, paired with the reference a node will receive. */
	private record ResolvedAsset(UUID assetUuid, MediaRef media) {
	}

}
