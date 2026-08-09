package io.metaloom.loom.rest.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;

/**
 * Runs index maintenance operations in the background and keeps their progress readable.
 *
 * <p>
 * <b>In memory, and deliberately so.</b> A job is a request to redo work that is derivable from the database - if a restart loses one, the operator
 * presses the button again, and nothing is inconsistent in the meantime. Persisting it would buy resumability for an operation whose whole cost is
 * that it is cheap to restart.
 * </p>
 *
 * <h2>Why a dedicated executor</h2>
 *
 * <p>
 * These jobs run for minutes over a large corpus. On the shared Vert.x worker pool that occupies a general-purpose worker for the duration and trips
 * the blocked-thread checker, which logs a stack trace every second for the whole run - a healthy rebuild would look like an incident. A named
 * executor with a pool size of <b>one</b> also serialises jobs across every index for free, which matters because the Lucene backends have a single
 * writer each and two concurrent rebuilds would simply queue on that lock anyway, just less visibly.
 * </p>
 *
 * <h2>One job per index</h2>
 *
 * <p>
 * A second job for an index that is already busy is rejected with 409 rather than queued. Queueing would let an impatient operator stack five
 * rebuilds, each of which redoes the previous one's work; the conflict is the more useful answer because it says what is already happening.
 * </p>
 */
@Singleton
public class IndexJobRegistry {

	private static final Logger log = LoggerFactory.getLogger(IndexJobRegistry.class);

	/**
	 * How many finished jobs are kept.
	 *
	 * <p>
	 * A client polls a job it started, so history has to outlive the job long enough for the last poll to land, and an operator comparing two runs
	 * wants the previous one. Beyond that it is a debugging convenience, not a record - the audit trail of "who rebuilt what" is the request log.
	 * </p>
	 */
	private static final int HISTORY_LIMIT = 32;

	private final Map<String, IndexJob> active = new ConcurrentHashMap<>();

	private final Map<UUID, IndexJob> history = new LinkedHashMap<>();

	private final WorkerExecutor executor;

	@Inject
	public IndexJobRegistry(Vertx vertx) {
		// 24h is not a real deadline; it is Vert.x's blocked-thread threshold for this pool, and a
		// rebuild that takes longer than a day is a broken instance rather than a slow one.
		this.executor = vertx.createSharedWorkerExecutor("loom-index-jobs", 1, 24, TimeUnit.HOURS);
	}

	/**
	 * Accept a job and start it.
	 *
	 * @param work
	 *            the operation, run on the index-job executor. It receives the job so it can advance the counters and honour a cancel request.
	 * @throws LoomRestException
	 *             409 when this index already has a job in flight
	 */
	public IndexJob submit(String indexId, IndexJobAction action, Consumer<IndexJob> work) {
		IndexJob job = new IndexJob(indexId, action);
		IndexJob running = active.putIfAbsent(indexId, job);
		if (running != null) {
			throw new LoomRestException(409, LoomRestErrorCode.CONFLICT,
				"The index " + indexId + " is already running a " + running.getAction() + " job (" + running.getUuid() + ").");
		}
		remember(job);
		executor.executeBlocking(() -> {
			run(job, work);
			return null;
			// Ordered false: the pool has one thread, so ordering is already guaranteed, and asking
			// for it again would serialise the completion handlers on the event loop for no gain.
		}, false);
		return job;
	}

	private void run(IndexJob job, Consumer<IndexJob> work) {
		job.setState(IndexJobState.RUNNING).setStartedAt(Instant.now());
		log.info("Index job {} started: {} on {}", job.getUuid(), job.getAction(), job.getIndexId());
		try {
			work.accept(job);
			job.setState(job.isCancelRequested() ? IndexJobState.CANCELLED : IndexJobState.SUCCEEDED);
		} catch (Exception e) {
			// The job's failure is reported through the job, never thrown at the executor: the
			// caller has long since received its 202 and the only place left to tell is the record.
			log.error("Index job {} ({} on {}) failed", job.getUuid(), job.getAction(), job.getIndexId(), e);
			job.setState(IndexJobState.FAILED).setError(e.getMessage() == null ? e.toString() : e.getMessage());
		} finally {
			job.setFinishedAt(Instant.now());
			active.remove(job.getIndexId(), job);
			log.info("Index job {} finished as {} after {} item(s), {} removed",
				job.getUuid(), job.getState(), job.getProcessed(), job.getRemoved());
		}
	}

	/** The job currently running for an index, or null. */
	public IndexJob active(String indexId) {
		return active.get(indexId);
	}

	public IndexJob find(UUID jobUuid) {
		synchronized (history) {
			return history.get(jobUuid);
		}
	}

	/** Jobs recorded for one index, newest first. Includes the running one. */
	public List<IndexJob> list(String indexId) {
		List<IndexJob> jobs;
		synchronized (history) {
			jobs = new ArrayList<>(history.values());
		}
		jobs.removeIf(job -> !job.getIndexId().equals(indexId));
		// Nulls last so a queued job that has not started sorts above finished ones.
		jobs.sort(Comparator.comparing(IndexJob::getStartedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
		return jobs;
	}

	private void remember(IndexJob job) {
		synchronized (history) {
			history.put(job.getUuid(), job);
			// Evict the oldest *finished* job. An eviction pass that could drop a running one would
			// make its own progress unpollable, which is the one thing history exists to prevent.
			if (history.size() > HISTORY_LIMIT) {
				history.values().stream()
					.filter(candidate -> candidate.getState().isTerminal())
					.findFirst()
					.ifPresent(oldest -> history.remove(oldest.getUuid()));
			}
		}
	}

	public void close() {
		executor.close();
	}
}
