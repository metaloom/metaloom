package io.metaloom.loom.rest.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTaskDao;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;

/**
 * Reclaims node tasks whose lease has lapsed.
 *
 * <p>Dispatch is fire-and-forget over a WebSocket: a worker that accepts a task and
 * then dies - crash, kill, network partition - never reports back. Nothing else in
 * the system notices, so without this the task stays {@code RUNNING}, its item never
 * settles, and the run hangs forever one node short.</p>
 *
 * <p>The reaper is the only component that turns a dead worker into progress. It
 * hands each lapsed task back to its engine, which decides between another attempt
 * and a dead-letter.</p>
 *
 * <h2>Duplicate work is possible, and preferred</h2>
 *
 * <p>A worker that is merely slow can have its task reclaimed and re-dispatched
 * while it is still running, so a node may execute twice. That is why leases are
 * generous and why {@code (item_uuid, node_id)} is unique - the second result is
 * recognised as a duplicate rather than recorded twice. Duplicate work is an
 * acceptable cost; a permanently stalled run is not.</p>
 */
@Singleton
public class LeaseReaper {

	private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

	/** How often to sweep. */
	public static final long DEFAULT_INTERVAL_MS = 60_000;

	/** Upper bound on one sweep, so the reaper cannot itself become an outage. */
	public static final int DEFAULT_SWEEP_LIMIT = 500;

	private final PipelineNodeTaskDao taskDao;
	private final PipelineRunRegistry runRegistry;
	private final io.metaloom.loom.common.metrics.LoomMetrics metrics;

	private ScheduledExecutorService scheduler;

	@Inject
	public LeaseReaper(PipelineNodeTaskDao taskDao, PipelineRunRegistry runRegistry, io.metaloom.loom.common.metrics.LoomMetrics metrics) {
		this.taskDao = taskDao;
		this.runRegistry = runRegistry;
		this.metrics = metrics;
	}

	/** Test convenience: a reaper without a metrics backend. */
	public LeaseReaper(PipelineNodeTaskDao taskDao, PipelineRunRegistry runRegistry) {
		this(taskDao, runRegistry, io.metaloom.loom.common.metrics.NoopLoomMetrics.INSTANCE);
	}

	/**
	 * Begin sweeping on a background thread.
	 */
	public synchronized void start() {
		start(DEFAULT_INTERVAL_MS);
	}

	public synchronized void start(long intervalMs) {
		if (scheduler != null) {
			return;
		}
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "lease-reaper");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleWithFixedDelay(this::sweepQuietly, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
		log.info("Lease reaper started, sweeping every {}ms", intervalMs);
	}

	public synchronized void stop() {
		if (scheduler != null) {
			scheduler.shutdownNow();
			scheduler = null;
		}
	}

	/**
	 * Sweep without ever throwing.
	 *
	 * <p>{@code scheduleWithFixedDelay} cancels the schedule permanently if the task
	 * throws, so an exception escaping here would silently stop all lease recovery for
	 * the life of the process - the failure mode this class exists to prevent.</p>
	 */
	private void sweepQuietly() {
		try {
			sweep();
		} catch (Exception e) {
			log.error("Lease sweep failed; the reaper will try again next interval", e);
		}
	}

	/**
	 * Reclaim every task whose lease has lapsed.
	 *
	 * @return how many tasks were handed back to an engine
	 */
	public int sweep() {
		return sweep(Instant.now(), DEFAULT_SWEEP_LIMIT);
	}

	/**
	 * @param now   the cutoff; leases expiring before this are considered lost
	 * @param limit maximum tasks to reclaim in this sweep
	 * @return how many tasks were handed back to an engine
	 */
	public int sweep(Instant now, int limit) {
		return reclaimAll(taskDao.loadExpiredLeases(now, limit), limit, "with lapsed leases", "Lease sweep");
	}

	/**
	 * Hand back everything a departed worker still holds, without waiting out its leases.
	 *
	 * <p>Waiting is what the timed sweep is for, and it is the wrong answer once the worker
	 * is <em>known</em> to be gone — evicted for silence, or its socket closed. Each task
	 * would then cost a full, deliberately generous lease before anything moved, and the
	 * lease exists to tolerate a worker that is merely slow, not one that has been struck
	 * off the fleet.</p>
	 *
	 * <p>This shares the timed sweep's reclaim path, so the "duplicate work is possible, and
	 * preferred" contract above holds here unchanged: a task this reclaims that the timed
	 * sweep is concurrently reclaiming reaches the same engine, and an engine ignores a loss
	 * reported against an execution that has already settled.</p>
	 *
	 * @param nodeId the departed worker
	 * @param limit  maximum tasks to reclaim for it
	 * @return how many tasks were handed back to an engine
	 */
	public int reclaimWorker(String nodeId, int limit) {
		if (nodeId == null || nodeId.isBlank()) {
			return 0;
		}
		return reclaimAll(taskDao.loadLeasedBy(nodeId, limit), limit, "held by departed worker '" + nodeId + "'",
			"Reclaim of '" + nodeId + "'");
	}

	/**
	 * @param tasks the batch to hand back
	 * @param limit the bound the batch was read under, so a truncated read can be reported
	 * @param what  how to describe the batch in the reclaim log line
	 * @param who   how to describe the caller in the truncation warning
	 * @return how many tasks were handed back to an engine
	 */
	private int reclaimAll(List<PipelineNodeTask> tasks, int limit, String what, String who) {
		if (tasks.isEmpty()) {
			return 0;
		}
		int reclaimed = 0;
		for (PipelineNodeTask task : tasks) {
			if (reclaim(task)) {
				reclaimed++;
			}
		}
		if (reclaimed > 0) {
			metrics.recordLeasesReclaimed(reclaimed);
			log.warn("Reclaimed {} task(s) {}", reclaimed, what);
		}
		if (tasks.size() == limit) {
			// Say so rather than let a truncated sweep read as "nothing more to do".
			log.warn("{} hit its limit of {} - more tasks remain", who, limit);
		}
		return reclaimed;
	}

	/**
	 * @param task the lapsed task
	 * @return true when an engine took it back
	 */
	private boolean reclaim(PipelineNodeTask task) {
		UUID runUuid = task.getRunUuid();
		PipelineRunEngine engine = runRegistry.get(runUuid);
		if (engine == null) {
			// The run is gone - finished, cancelled, or lost to a restart. Clear the
			// lease so the row stops being swept every minute forever.
			releaseOrphan(task);
			return false;
		}

		try {
			// The item id the engine knows is the item's uuid, which is exactly what
			// makes this lookup possible across a restart. The element matters too: a node
			// downstream of a fan-out has several executions in flight, and reclaiming the
			// node rather than the element would strand the one that was actually lost.
			engine.onNodeTaskLost(task.getItemUuid().toString(), task.getNodeId(), task.getElementSeq(),
				"lease expired at " + task.getLeaseExpiresAt());
			return true;
		} catch (Exception e) {
			log.error("Failed to reclaim task {} of run {}", task.getUuid(), runUuid, e);
			return false;
		}
	}

	/**
	 * Mark a task whose run no longer exists, so it is not swept again.
	 */
	private void releaseOrphan(PipelineNodeTask task) {
		try {
			task.setState(NodeTaskState.DEAD_LETTER);
			task.setLeaseExpiresAt(null);
			task.setErrorMessage("Lease expired but run " + task.getRunUuid() + " is no longer active");
			taskDao.update(task);
			metrics.recordOrphansDeadlettered(1);
			log.warn("Task {} belongs to inactive run {} - dead-lettered", task.getUuid(), task.getRunUuid());
		} catch (Exception e) {
			log.error("Failed to release orphaned task {}", task.getUuid(), e);
		}
	}

}
