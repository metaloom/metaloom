package io.metaloom.loom.rest.service.impl;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.api.pipeline.PipelineRunKind;
import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine.RestoredTask;
import io.metaloom.loom.pipeline.engine.RunStateStore;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.metaloom.loom.pipeline.engine.PortPayloads;
import io.vertx.core.json.JsonObject;

/**
 * Puts runs left mid-flight by a restart back on their feet.
 *
 * <p>A process that dies never gets to move its runs out of {@code RUNNING}, so on
 * the next start that status means "was in progress when we stopped". For each such
 * run this rebuilds the graph, replays the settled node results, and lets the engine
 * carry on from where it was.</p>
 *
 * <h2>What is and is not recoverable</h2>
 *
 * <p>Node results are recoverable because they were persisted as they settled. The
 * <strong>source enumeration is not</strong>: the Cortex worker that was scanning
 * died with the connection, and files it had not yet reached were never recorded
 * anywhere. A run whose source had not completed is therefore finished with only the
 * media that were already known, and marked so - claiming otherwise would report a
 * partial scan as a complete one.</p>
 */
@Singleton
public class PipelineRunRecovery {

	private static final Logger log = LoggerFactory.getLogger(PipelineRunRecovery.class);

	private final PipelineRunDao runDao;
	private final PipelineRunItemDao itemDao;
	private final PipelineNodeTaskDao taskDao;
	private final PipelineVersionDao versionDao;
	private final PipelineRunRegistry registry;
	private final WebSocketNodeDispatcher dispatcher;
	private final PipelineRunTracker tracker;
	private final io.metaloom.loom.common.metrics.LoomMetrics metrics;
	private final PipelineGraphParser parser;

	@Inject
	public PipelineRunRecovery(PipelineRunDao runDao, PipelineRunItemDao itemDao, PipelineNodeTaskDao taskDao,
		PipelineVersionDao versionDao, PipelineRunRegistry registry, WebSocketNodeDispatcher dispatcher,
		PipelineRunTracker tracker, NodeDescriptorRegistry nodeDescriptorRegistry,
		io.metaloom.loom.common.metrics.LoomMetrics metrics) {
		this.runDao = runDao;
		this.itemDao = itemDao;
		this.taskDao = taskDao;
		this.versionDao = versionDao;
		this.registry = registry;
		this.dispatcher = dispatcher;
		this.tracker = tracker;
		this.metrics = metrics;
		// Registry-backed on purpose: a parser without one skips port checking and classifies every
		// node as ExecutionMode.SINGLE, so a recovered run would silently lose the fan-out the same
		// graph had before the restart.
		this.parser = new PipelineGraphParser(nodeDescriptorRegistry);
	}

	/**
	 * Recover every run that was in progress.
	 *
	 * @return how many runs were resumed
	 */
	public int recoverAll() {
		List<PipelineRun> running;
		try {
			// PAUSED runs are recovered too. They are non-terminal and still owned by an
			// operator, so leaving them behind would strand them: nothing would ever advance
			// them and a later resume would find no engine.
			running = new java.util.ArrayList<>(runDao.loadByStatus(PipelineRunStatus.RUNNING));
			running.addAll(runDao.loadByStatus(PipelineRunStatus.PAUSED));
		} catch (Exception e) {
			log.error("Could not read in-flight runs; recovery skipped", e);
			return 0;
		}
		if (running.isEmpty()) {
			return 0;
		}

		log.info("Recovering {} run(s) that were in progress at shutdown", running.size());
		int recovered = 0;
		for (PipelineRun run : running) {
			// One unrecoverable run must not prevent the others from resuming.
			try {
				if (recover(run)) {
					recovered++;
				}
			} catch (Exception e) {
				log.error("Failed to recover run {}", run.getUuid(), e);
				failRun(run, "Recovery failed: " + e.getMessage());
			}
		}
		if (recovered > 0) {
			metrics.recordRunRecovered(recovered);
		}
		return recovered;
	}

	/**
	 * @param run the run record
	 * @return true when an engine was rebuilt and registered
	 */
	private boolean recover(PipelineRun run) {
		UUID runUuid = run.getUuid();

		PipelineGraph graph;
		if (run.getKind() == PipelineRunKind.ADHOC) {
			// An ad-hoc run belongs to no pipeline, so there is no version row to look up. Its
			// definition travelled with it in meta.definition precisely so that a restart can rebuild
			// the graph; without this branch the version lookup below would come back null and every
			// ad-hoc run would be failed by the next Loom restart.
			JsonObject definition = run.getMeta() == null ? null : run.getMeta().getJsonObject(PipelineRun.META_DEFINITION);
			if (definition == null) {
				failRun(run, "Ad-hoc run carries no definition and cannot be resumed");
				return false;
			}
			graph = parser.parse(AdhocRuns.label(run), definition, true, run.isDryRun(), 0);
		} else {
			PipelineVersion version = versionDao.loadByPipelineAndVersion(run.getPipelineUuid(), run.getPipelineVersion());
			if (version == null) {
				// The definition this run was started from is gone, so there is no graph to
				// resume against. Failing it is honest; leaving it RUNNING forever is not.
				failRun(run, "Pipeline version " + run.getPipelineVersion() + " no longer exists");
				return false;
			}
			graph = parser.parse(version.getName(), version.getDefinition(), version.isEnabled(),
				run.isDryRun(), version.getPriority());
		}

		RunStateStore store = new DaoRunStateStore(runDao, itemDao, taskDao, runUuid, run.getCreatorUuid());
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, runUuid, store);
		// A resumed run reports exactly as a fresh one does; a restart must not create a blind spot
		// in the very numbers an operator is watching while the fleet catches up.
		engine.setMetrics(metrics);
		engine.onCompletion(summary -> tracker.complete(runUuid, summary.getDurationMs(),
			(int) summary.getMediaCount(), (int) summary.getSuccessCount(),
			(int) summary.getFailureCount(), (int) summary.getSkippedCount()));
		engine.start();

		int restored = restoreItems(runUuid, engine);
		registry.register(runUuid, engine);

		boolean wasPaused = run.getStatus() == PipelineRunStatus.PAUSED;
		if (wasPaused) {
			// Re-apply the operator's suspension *before* resume(), which would otherwise
			// dispatch every ready node the moment the engine is rebuilt - a restart must not
			// silently un-pause a run by firing off a burst of work.
			engine.pause();
		}

		boolean sourceComplete = wasSourceComplete(run);
		if (!sourceComplete) {
			log.warn("Run {} was still enumerating when it stopped; {} known item(s) will be finished but the "
				+ "remaining media were never recorded and are lost", runUuid, restored);
		}
		engine.resume(sourceComplete);

		log.info("Recovered run {} with {} item(s){}", runUuid, restored, wasPaused ? ", still suspended" : "");
		return true;
	}

	/**
	 * Replay persisted item and node state into the engine.
	 *
	 * @return how many items were restored
	 */
	private int restoreItems(UUID runUuid, PipelineRunEngine engine) {
		List<PipelineRunItem> items = itemDao.loadByRun(runUuid);
		if (items.isEmpty()) {
			return 0;
		}

		// One query for the whole run rather than one per item: a large run would
		// otherwise issue a query per item just to start up.
		Map<UUID, List<PipelineNodeTask>> tasksByItem = new LinkedHashMap<>();
		for (PipelineNodeTask task : taskDao.loadByRun(runUuid)) {
			tasksByItem.computeIfAbsent(task.getItemUuid(), k -> new java.util.ArrayList<>()).add(task);
		}

		for (PipelineRunItem item : items) {
			// A list, not a map keyed by node: a run that died mid fan-out has one row per element,
			// and keying on the node id alone would keep only the last of them - the item would come
			// back narrower than it is and the dropped executions would never run.
			List<RestoredTask> restored = new ArrayList<>();

			for (PipelineNodeTask task : tasksByItem.getOrDefault(item.getUuid(), List.of())) {
				NodeState state = terminalStateOf(task.getState());
				// RUNNING or PENDING never finished, so it carries no result and the engine
				// dispatches it again - but its attempt count still has to survive, or a restart
				// silently refills the retry budget of a poison element.
				NodeTaskResult result = state == null ? null
					: new NodeTaskResult(task.getUuid(), task.getNodeId(), task.getElementSeq(), state,
						task.getDurationMs() == null ? 0 : task.getDurationMs(), task.getErrorMessage(),
						outputsOf(task));
				restored.add(new RestoredTask(task.getNodeId(), task.getElementSeq(), result, task.getAttempt()));
			}

			engine.restoreItem(item.getUuid().toString(), toMediaRef(item), restored);
		}
		return items.size();
	}

	/**
	 * @param state the persisted state
	 * @return the engine state, or null when the task had not finished
	 */
	private static NodeState terminalStateOf(NodeTaskState state) {
		if (state == null) {
			return null;
		}
		return switch (state) {
			case COMPLETED -> NodeState.COMPLETED;
			case SKIPPED -> NodeState.SKIPPED;
			// A dead-lettered task is out of attempts, so the engine has to see it as a
			// settled failure rather than as work still to do.
			case FAILED, DEAD_LETTER -> NodeState.FAILED;
			case PENDING, RUNNING -> null;
		};
	}

	private static Map<String, PortPayload> outputsOf(PipelineNodeTask task) {
		return PortPayloads.decode(task.getOutputs());
	}

	private static MediaRef toMediaRef(PipelineRunItem item) {
		return new MediaRef(item.getMediaPath(), item.getSha512(),
			item.getSizeBytes() == null ? -1 : item.getSizeBytes());
	}

	private static boolean wasSourceComplete(PipelineRun run) {
		JsonObject meta = run.getMeta();
		return meta != null && Boolean.TRUE.equals(meta.getBoolean(DaoRunStateStore.META_SOURCE_COMPLETE));
	}

	private void failRun(PipelineRun run, String reason) {
		try {
			run.setStatus(PipelineRunStatus.FAILED);
			run.setErrorMessage(reason);
			runDao.update(run);
			log.warn("Run {} marked FAILED: {}", run.getUuid(), reason);
		} catch (Exception e) {
			log.error("Could not mark run {} as failed", run.getUuid(), e);
		}
	}

}
