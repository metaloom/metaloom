package io.metaloom.loom.rest.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.db.model.pipeline.PipelineVersion;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.engine.RunStateStore;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
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

	private static final String STATUS_RUNNING = "RUNNING";

	private final PipelineRunDao runDao;
	private final PipelineRunItemDao itemDao;
	private final PipelineNodeTaskDao taskDao;
	private final PipelineVersionDao versionDao;
	private final PipelineRunRegistry registry;
	private final WebSocketNodeDispatcher dispatcher;
	private final PipelineRunTracker tracker;
	private final PipelineGraphParser parser = new PipelineGraphParser();

	@Inject
	public PipelineRunRecovery(PipelineRunDao runDao, PipelineRunItemDao itemDao, PipelineNodeTaskDao taskDao,
		PipelineVersionDao versionDao, PipelineRunRegistry registry, WebSocketNodeDispatcher dispatcher,
		PipelineRunTracker tracker) {
		this.runDao = runDao;
		this.itemDao = itemDao;
		this.taskDao = taskDao;
		this.versionDao = versionDao;
		this.registry = registry;
		this.dispatcher = dispatcher;
		this.tracker = tracker;
	}

	/**
	 * Recover every run that was in progress.
	 *
	 * @return how many runs were resumed
	 */
	public int recoverAll() {
		List<PipelineRun> running;
		try {
			running = runDao.loadByStatus(STATUS_RUNNING);
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
		return recovered;
	}

	/**
	 * @param run the run record
	 * @return true when an engine was rebuilt and registered
	 */
	private boolean recover(PipelineRun run) {
		UUID runUuid = run.getUuid();
		PipelineVersion version = versionDao.loadByPipelineAndVersion(run.getPipelineUuid(), run.getPipelineVersion());
		if (version == null) {
			// The definition this run was started from is gone, so there is no graph to
			// resume against. Failing it is honest; leaving it RUNNING forever is not.
			failRun(run, "Pipeline version " + run.getPipelineVersion() + " no longer exists");
			return false;
		}

		PipelineGraph graph = parser.parse(version.getName(), version.getDefinition(), version.isEnabled(),
			run.isDryRun(), version.getPriority());

		RunStateStore store = new DaoRunStateStore(runDao, itemDao, taskDao, runUuid, run.getCreatorUuid());
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, runUuid, store);
		engine.onCompletion(summary -> tracker.complete(runUuid, summary.getDurationMs(),
			(int) summary.getMediaCount(), (int) summary.getSuccessCount(),
			(int) summary.getFailureCount(), (int) summary.getSkippedCount()));
		engine.start();

		int restored = restoreItems(runUuid, engine);
		registry.register(runUuid, engine);

		boolean sourceComplete = wasSourceComplete(run);
		if (!sourceComplete) {
			log.warn("Run {} was still enumerating when it stopped; {} known item(s) will be finished but the "
				+ "remaining media were never recorded and are lost", runUuid, restored);
		}
		engine.resume(sourceComplete);

		log.info("Recovered run {} with {} item(s)", runUuid, restored);
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
			Map<String, NodeTaskResult> settled = new LinkedHashMap<>();
			Map<String, Integer> attempts = new LinkedHashMap<>();

			for (PipelineNodeTask task : tasksByItem.getOrDefault(item.getUuid(), List.of())) {
				attempts.put(task.getNodeId(), task.getAttempt());
				NodeState state = terminalStateOf(task.getState());
				if (state == null) {
					// RUNNING or PENDING: it never finished, so leave it unsettled and let
					// the engine dispatch it again.
					continue;
				}
				settled.put(task.getNodeId(), new NodeTaskResult(task.getUuid(), task.getNodeId(), state,
					task.getDurationMs() == null ? 0 : task.getDurationMs(), task.getErrorMessage(),
					outputsOf(task)));
			}

			engine.restoreItem(item.getUuid().toString(), toMediaRef(item), settled, attempts);
		}
		return items.size();
	}

	/**
	 * @param state the persisted state
	 * @return the engine state, or null when the task had not finished
	 */
	private static NodeState terminalStateOf(String state) {
		if (state == null) {
			return null;
		}
		switch (state) {
			case "COMPLETED":
				return NodeState.COMPLETED;
			case "SKIPPED":
				return NodeState.SKIPPED;
			case "FAILED":
			case "DEAD_LETTER":
				return NodeState.FAILED;
			default:
				return null;
		}
	}

	private static Map<String, Object> outputsOf(PipelineNodeTask task) {
		JsonObject outputs = task.getOutputs();
		return outputs == null ? Map.of() : outputs.getMap();
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
			run.setStatus("FAILED");
			run.setErrorMessage(reason);
			runDao.update(run);
			log.warn("Run {} marked FAILED: {}", run.getUuid(), reason);
		} catch (Exception e) {
			log.error("Could not mark run {} as failed", run.getUuid(), e);
		}
	}

}
