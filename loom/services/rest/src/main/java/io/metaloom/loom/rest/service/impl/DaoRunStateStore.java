package io.metaloom.loom.rest.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunDao;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.pipeline.PipelineRunItemDao;
import io.metaloom.loom.pipeline.engine.ItemState.ItemOutcome;
import io.metaloom.loom.pipeline.engine.RunStateStore;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.vertx.core.json.JsonObject;

/**
 * Writes run state to Postgres, in batches.
 *
 * <p>One store instance serves one run - it holds that run's buffer, so it is not
 * shared and needs no per-run keying.</p>
 *
 * <h2>Why batching is not optional</h2>
 *
 * <p>A 100 000 item run over a 10 node graph produces over a million task rows. At
 * one statement per call the database, not the workers, becomes the bottleneck, and
 * the engine would be blocking on a round trip while holding its monitor. Rows are
 * therefore accumulated and written in bulk.</p>
 *
 * <h2>Flush ordering is a correctness requirement</h2>
 *
 * <p>Task rows carry a foreign key to their item, so buffered items are always
 * written before buffered tasks. Flushing in the other order fails the whole batch
 * on a constraint violation.</p>
 */
public class DaoRunStateStore implements RunStateStore {

	private static final Logger log = LoggerFactory.getLogger(DaoRunStateStore.class);

	/** How many buffered rows trigger an automatic flush. */
	public static final int DEFAULT_BATCH_SIZE = 500;

	/**
	 * How long a worker may hold a task before it is presumed dead.
	 *
	 * <p>Generous on purpose: reclaiming a task from a worker that is merely slow
	 * causes duplicate work, which is worse than waiting a while longer to notice a
	 * genuine death.</p>
	 */
	public static final long DEFAULT_LEASE_MS = 10 * 60 * 1000L;

	/** Marks in {@code pipeline_run.meta} that the source finished enumerating. */
	public static final String META_SOURCE_COMPLETE = "sourceComplete";

	private final PipelineRunDao runDao;
	private final PipelineRunItemDao itemDao;
	private final PipelineNodeTaskDao taskDao;
	private final UUID runUuid;
	private final UUID userUuid;
	private final int batchSize;

	private final List<PipelineRunItem> pendingItems = new ArrayList<>();
	/** Keyed by item + node so a settle updates the dispatch row rather than adding one. */
	private final Map<String, PipelineNodeTask> pendingTasks = new LinkedHashMap<>();

	public DaoRunStateStore(PipelineRunDao runDao, PipelineRunItemDao itemDao, PipelineNodeTaskDao taskDao,
		UUID runUuid, UUID userUuid) {
		this(runDao, itemDao, taskDao, runUuid, userUuid, DEFAULT_BATCH_SIZE);
	}

	public DaoRunStateStore(PipelineRunDao runDao, PipelineRunItemDao itemDao, PipelineNodeTaskDao taskDao,
		UUID runUuid, UUID userUuid, int batchSize) {
		this.runDao = runDao;
		this.itemDao = itemDao;
		this.taskDao = taskDao;
		this.runUuid = runUuid;
		this.userUuid = userUuid;
		this.batchSize = Math.max(1, batchSize);
	}

	@Override
	public synchronized UUID itemDiscovered(UUID runUuid, long itemSeq, MediaRef media) {
		PipelineRunItem item = itemDao.createRunItem(userUuid, runUuid, itemSeq, media.getPath());
		// The id is assigned here rather than by the database default, because the
		// engine needs it immediately - long before this row is flushed.
		UUID itemUuid = UUID.randomUUID();
		item.setUuid(itemUuid);
		item.setSha512(media.getSha512());
		item.setSizeBytes(media.getSize());
		item.setState("PENDING");

		pendingItems.add(item);
		flushIfFull();
		return itemUuid;
	}

	@Override
	public synchronized void taskDispatched(UUID itemUuid, NodeTask task) {
		if (itemUuid == null) {
			return;
		}
		PipelineNodeTask row = taskDao.createNodeTask(userUuid, itemUuid, task.getRunUuid(), task.getNodeId(),
			task.getNodeKind());
		row.setUuid(task.getTaskUuid());
		row.setState("RUNNING");
		row.setAttempt(1);
		Instant now = Instant.now();
		row.setStarted(now);
		// The lease is what makes a dead worker recoverable: without an expiry this row
		// stays RUNNING forever and its item never settles.
		row.setLeaseExpiresAt(now.plusMillis(DEFAULT_LEASE_MS));

		pendingTasks.put(key(itemUuid, task.getNodeId()), row);
		flushIfFull();
	}

	@Override
	public synchronized void taskSettled(UUID itemUuid, NodeTaskResult result) {
		if (itemUuid == null) {
			return;
		}
		String key = key(itemUuid, result.getNodeId());
		PipelineNodeTask row = pendingTasks.get(key);
		if (row == null) {
			// Settled without a dispatch: a skip, or the synthesised source node. Both
			// are real decisions and have to be recorded, or recovery would re-run them.
			// run_uuid is NOT NULL, and a skip carries no task to read it from - which is
			// why the run is a property of the store rather than of the call.
			row = taskDao.createNodeTask(userUuid, itemUuid, runUuid, result.getNodeId(), "skipped");
			row.setUuid(result.getTaskUuid() != null ? result.getTaskUuid() : UUID.randomUUID());
			row.setAttempt(0);
			pendingTasks.put(key, row);
		}

		row.setState(stateOf(result.getState()));
		// A settled task is no longer leased. Leaving the expiry set would make the
		// reaper consider finished work reclaimable.
		row.setLeaseExpiresAt(null);
		row.setDurationMs(result.getDurationMs());
		row.setErrorMessage(result.getMessage());
		row.setFinished(Instant.now());
		if (!result.getOutputs().isEmpty()) {
			row.setOutputs(new JsonObject(result.getOutputs()));
		}
		flushIfFull();
	}

	@Override
	public synchronized void itemSettled(UUID itemUuid, ItemOutcome outcome) {
		if (itemUuid == null) {
			return;
		}
		for (PipelineRunItem item : pendingItems) {
			if (itemUuid.equals(item.getUuid())) {
				item.setState(outcome.name());
				return;
			}
		}
		// Already flushed, so this is an update rather than part of the batch.
		PipelineRunItem stored = itemDao.load(itemUuid);
		if (stored != null) {
			stored.setState(outcome.name());
			itemDao.update(stored);
		}
	}

	@Override
	public synchronized void sourceCompleted(UUID runUuid, long totalCount) {
		// Items must be on disk before the run is marked fully enumerated, or a crash
		// in between leaves a run that claims to know all its media but does not.
		flush();
		try {
			PipelineRun run = runDao.load(runUuid);
			if (run == null) {
				return;
			}
			JsonObject meta = run.getMeta() == null ? new JsonObject() : run.getMeta();
			meta.put(META_SOURCE_COMPLETE, true);
			meta.put("sourceItemCount", totalCount);
			run.setMeta(meta);
			runDao.update(run);
		} catch (Exception e) {
			log.error("Failed to mark run {} as fully enumerated", runUuid, e);
		}
	}

	@Override
	public synchronized void flush() {
		if (pendingItems.isEmpty() && pendingTasks.isEmpty()) {
			return;
		}
		int items = pendingItems.size();
		int tasks = pendingTasks.size();
		try {
			// Items first: the task rows point at them.
			if (!pendingItems.isEmpty()) {
				itemDao.storeBatch(new ArrayList<>(pendingItems));
				pendingItems.clear();
			}
			if (!pendingTasks.isEmpty()) {
				taskDao.storeBatch(new ArrayList<>(pendingTasks.values()));
				pendingTasks.clear();
			}
			log.debug("Flushed {} item(s) and {} task(s)", items, tasks);
		} catch (Exception e) {
			// Losing state is bad, but taking the run down with it is worse - the
			// in-memory engine can still finish the work.
			log.error("Failed to flush {} item(s) and {} task(s) of run state", items, tasks, e);
			pendingItems.clear();
			pendingTasks.clear();
		}
	}

	private void flushIfFull() {
		if (pendingItems.size() + pendingTasks.size() >= batchSize) {
			flush();
		}
	}

	private static String key(UUID itemUuid, String nodeId) {
		return itemUuid + "/" + nodeId;
	}

	/**
	 * Map the engine's node state onto the column vocabulary.
	 *
	 * <p>They agree today; this exists so that a new engine state cannot silently
	 * become an unrecognised string in the database.</p>
	 */
	private static String stateOf(NodeState state) {
		switch (state) {
			case COMPLETED:
				return "COMPLETED";
			case FAILED:
				return "FAILED";
			case SKIPPED:
				return "SKIPPED";
			default:
				return "PENDING";
		}
	}

}
