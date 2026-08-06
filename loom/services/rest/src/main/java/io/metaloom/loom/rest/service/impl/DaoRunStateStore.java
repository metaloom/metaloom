package io.metaloom.loom.rest.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.api.pipeline.RunItemState;
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
import io.metaloom.loom.pipeline.engine.NodePreviews;
import io.metaloom.loom.pipeline.engine.PortPayloads;
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
	/**
	 * Keyed by item + node + element + generation so a settle updates the dispatch row rather than
	 * adding one.
	 *
	 * <p>
	 * The generation belongs in the key. A re-executed node dispatches a second time for the same
	 * {@code (item, node, element)}, and if that shared a buffer slot with the attempt before it the
	 * earlier row would be dropped before it was ever flushed — destroying exactly the comparison the
	 * operator re-executed in order to make.
	 * </p>
	 */
	private final Map<String, PipelineNodeTask> pendingTasks = new LinkedHashMap<>();
	/**
	 * The generation each execution was last dispatched under, so a settle can find its own row.
	 *
	 * <p>
	 * {@code taskSettled} is handed a {@link NodeTaskResult}, which carries no generation — and does
	 * not need to, because a re-execution is only possible on an execution that is <em>held</em>, and
	 * a held execution has already settled. There is therefore never an older generation of the same
	 * execution still in flight to be confused with the current one.
	 * </p>
	 */
	private final Map<String, Integer> generations = new LinkedHashMap<>();
	/**
	 * Tasks already written to the database.
	 *
	 * <p>Once a dispatch row has been flushed, the buffer no longer holds it, and a
	 * later settle must <em>update</em> that row. Without this it looks like a task
	 * that was never dispatched, and re-inserting it collides on the primary key -
	 * the worker echoes the task uuid back, so the fabricated row carries the same
	 * one.</p>
	 */
	private final java.util.Set<String> flushedTasks = new java.util.LinkedHashSet<>();

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
		item.setState(RunItemState.PENDING);

		pendingItems.add(item);
		flushIfFull();
		return itemUuid;
	}

	@Override
	public synchronized void taskDispatched(UUID itemUuid, NodeTask task, String workerId) {
		dispatched(itemUuid, task, workerId, null);
	}

	@Override
	public synchronized void segmentTaskDispatched(UUID itemUuid, NodeTask task, String workerId, UUID dispatchUuid) {
		dispatched(itemUuid, task, workerId, dispatchUuid);
	}

	/**
	 * @param dispatchUuid the segment request that carried this node, or null for an ordinary
	 *                     per-node dispatch, where the row's own uuid already is the dispatch
	 */
	private void dispatched(UUID itemUuid, NodeTask task, String workerId, UUID dispatchUuid) {
		if (itemUuid == null) {
			return;
		}
		PipelineNodeTask row = taskDao.createNodeTask(userUuid, itemUuid, task.getRunUuid(), task.getNodeId(),
			task.getNodeKind());
		if (dispatchUuid != null) {
			// Every member of a segment carries the uuid of the one request they arrived in, which
			// is the only thing distinguishing "three nodes, one round trip" from three dispatches
			// once the rows are on disk.
			row.setMeta(new JsonObject().put("dispatchUuid", dispatchUuid.toString()));
		}
		row.setUuid(task.getTaskUuid());
		// A refused dispatch is recorded as PENDING with no lease: it was never handed
		// to anyone, so it must not look reclaimable.
		row.setState(workerId == null ? NodeTaskState.PENDING : NodeTaskState.RUNNING);
		row.setLeasedBy(workerId);
		row.setAttempt(1);
		row.setElementSeq(task.getElementSeq());
		row.setGeneration(task.getGeneration());
		Instant now = Instant.now();
		row.setStarted(now);
		if (workerId != null) {
			// The lease is what makes a dead worker recoverable: without an expiry this
			// row stays RUNNING forever and its item never settles.
			row.setLeaseExpiresAt(now.plusMillis(DEFAULT_LEASE_MS));
		}

		generations.put(execKey(itemUuid, task.getNodeId(), task.getElementSeq()), task.getGeneration());
		pendingTasks.put(key(itemUuid, task.getNodeId(), task.getElementSeq(), task.getGeneration()), row);
		flushIfFull();
	}

	@Override
	public synchronized void taskSettled(UUID itemUuid, NodeTaskResult result) {
		if (itemUuid == null) {
			return;
		}
		int generation = generations.getOrDefault(
			execKey(itemUuid, result.getNodeId(), result.getElementSeq()), 0);
		String key = key(itemUuid, result.getNodeId(), result.getElementSeq(), generation);
		PipelineNodeTask row = pendingTasks.get(key);

		if (row == null) {
			// Not buffered. It may still be on disk from its dispatch - a flush can
			// happen between dispatching a node and its result arriving. Ask the
			// database rather than assuming, because fabricating a second row collides
			// on the primary key: the worker echoes the task uuid back.
			if (updateSettledTask(itemUuid, result)) {
				return;
			}
		}

		if (row == null) {
			// Settled without a dispatch: a skip, or the synthesised source node. Both
			// are real decisions and have to be recorded, or recovery would re-run them.
			// run_uuid is NOT NULL, and a skip carries no task to read it from - which is
			// why the run is a property of the store rather than of the call.
			row = taskDao.createNodeTask(userUuid, itemUuid, runUuid, result.getNodeId(), "skipped");
			row.setUuid(result.getTaskUuid() != null ? result.getTaskUuid() : UUID.randomUUID());
			row.setAttempt(0);
			row.setElementSeq(result.getElementSeq());
			row.setGeneration(generation);
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
			row.setOutputs(PortPayloads.encode(result.getOutputs()));
		}
		// Only written for a run started in debug mode, so this is null on every ordinary run.
		// Encoded here rather than in the engine because the column is a persistence concern -
		// the engine passes the previews through untouched.
		if (!result.getPreviews().isEmpty()) {
			row.setPreviews(NodePreviews.encode(result.getPreviews()));
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
				item.setState(stateOf(outcome));
				return;
			}
		}
		// Already flushed, so this is an update rather than part of the batch.
		PipelineRunItem stored = itemDao.load(itemUuid);
		if (stored != null) {
			stored.setState(stateOf(outcome));
			itemDao.update(stored);
		}
	}

	@Override
	public java.util.Optional<NodeTaskResult> previousResult(MediaRef media, String nodeId) {
		try {
			PipelineRunItem previous = itemDao.loadLatestSettledByPath(media.getPath());
			if (previous == null) {
				return java.util.Optional.empty();
			}
			// The file may have been replaced since. Size is the only cheap signal the
			// source gives us, so a mismatch means recompute rather than trust.
			Long knownSize = previous.getSizeBytes();
			if (knownSize == null || media.getSize() < 0 || knownSize != media.getSize()) {
				log.debug("Not reusing results for {}: size changed or unknown", media.getPath());
				return java.util.Optional.empty();
			}

			PipelineNodeTask task = taskDao.loadByItemAndNode(previous.getUuid(), nodeId, 0);
			if (task == null || task.getState() != NodeTaskState.COMPLETED) {
				// Only a success is worth adopting. Reusing a failure would make an
				// outage permanent, and reusing a skip would carry no outputs anyway.
				return java.util.Optional.empty();
			}
			JsonObject outputs = task.getOutputs();
			if (outputs == null || outputs.isEmpty()) {
				return java.util.Optional.empty();
			}
			return java.util.Optional.of(NodeTaskResult.completed(null, nodeId,
				task.getDurationMs() == null ? 0 : task.getDurationMs(), PortPayloads.decode(outputs)));
		} catch (Exception e) {
			// Reuse is an optimisation; never let it break a run.
			log.error("Failed to look up a previous result for '{}' on {}", nodeId, media.getPath(), e);
			return java.util.Optional.empty();
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
				flushedTasks.addAll(pendingTasks.keySet());
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

	/**
	 * Apply a settle to a task row that has already been written.
	 */
	private boolean updateSettledTask(UUID itemUuid, NodeTaskResult result) {
		try {
			PipelineNodeTask stored = taskDao.loadByItemAndNode(itemUuid, result.getNodeId(), result.getElementSeq());
			if (stored == null) {
				return false;
			}
			stored.setState(stateOf(result.getState()));
			stored.setLeaseExpiresAt(null);
			stored.setDurationMs(result.getDurationMs());
			stored.setErrorMessage(result.getMessage());
			stored.setFinished(Instant.now());
			if (!result.getOutputs().isEmpty()) {
				stored.setOutputs(PortPayloads.encode(result.getOutputs()));
			}
			// Written here as well as on the buffered path. Whether a dispatch row has been flushed
			// before its result arrives depends only on how full the batch happened to be, so leaving
			// this out made previews vanish for some executions of a debug run and not others.
			if (!result.getPreviews().isEmpty()) {
				stored.setPreviews(NodePreviews.encode(result.getPreviews()));
			}
			taskDao.update(stored);
			return true;
		} catch (Exception e) {
			log.error("Failed to update settled task '{}' for item {}", result.getNodeId(), itemUuid, e);
			return false;
		}
	}

	private void flushIfFull() {
		if (pendingItems.size() + pendingTasks.size() >= batchSize) {
			flush();
		}
	}

	/** Identifies one execution, across however many times it has been run. */
	private static String execKey(UUID itemUuid, String nodeId, int elementSeq) {
		return itemUuid + "/" + nodeId + "#" + elementSeq;
	}

	/** Identifies one attempt at one execution — the row's idempotency key. */
	private static String key(UUID itemUuid, String nodeId, int elementSeq, int generation) {
		return execKey(itemUuid, nodeId, elementSeq) + "@" + generation;
	}

	/**
	 * Map the engine's node state onto the column vocabulary.
	 *
	 * <p>The two enums overlap but are not the same: {@link NodeTaskState} additionally has
	 * DEAD_LETTER, which the engine cannot report because giving up on a task is a decision
	 * the reaper makes. Mapped explicitly rather than by name so neither side can be extended
	 * into a value the other silently mistranslates.</p>
	 */
	private static NodeTaskState stateOf(NodeState state) {
		return switch (state) {
			case COMPLETED -> NodeTaskState.COMPLETED;
			case FAILED -> NodeTaskState.FAILED;
			case SKIPPED -> NodeTaskState.SKIPPED;
			case PENDING, RUNNING -> NodeTaskState.PENDING;
		};
	}

	/**
	 * Map an item outcome onto the column vocabulary.
	 *
	 * <p>The engine spells the failure case FAILURE and the column spells it FAILED. That
	 * difference used to reach the database verbatim via {@code outcome.name()}, so a failed
	 * item matched neither the terminal-state set nor any UI filter - it simply looked
	 * unfinished forever. {@code V2.77} rewrites the rows that were written that way.</p>
	 */
	private static RunItemState stateOf(ItemOutcome outcome) {
		return switch (outcome) {
			case SUCCESS -> RunItemState.SUCCESS;
			case FAILURE -> RunItemState.FAILED;
			case SKIPPED -> RunItemState.SKIPPED;
		};
	}

}
