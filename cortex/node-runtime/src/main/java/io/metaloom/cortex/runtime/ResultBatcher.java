package io.metaloom.cortex.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.NodeTaskResultBatch;

/**
 * Accumulates node results per run and hands them over in batches.
 *
 * <p>A cheap node over many small files produces one result per item per node, each
 * one its own message. Batching trades a little latency for far fewer of them. The
 * size comes from the pipeline definition, so a pipeline that wants results
 * immediately simply leaves it at 1.</p>
 *
 * <h2>Flushing on time is not optional</h2>
 *
 * <p>A run's last few results will not reach the batch size — a 500-item run with a
 * batch of 200 ends with 100 results sitting in the buffer. Without a time-based
 * flush the engine would wait forever for tasks it has already been told to expect,
 * and the run would never close. The size trigger is the optimisation; the timer is
 * what makes it correct.</p>
 *
 * <p>Thread-safe: results arrive on worker threads, the timer flush on another.</p>
 */
public class ResultBatcher {

	private static final Logger log = LoggerFactory.getLogger(ResultBatcher.class);

	/** How long a partial batch may wait before being sent anyway. */
	public static final long DEFAULT_MAX_HOLD_MS = 500;

	/** Receives a batch that is ready to go. */
	@FunctionalInterface
	public interface BatchSink {
		void send(NodeTaskResultBatch batch);
	}

	private static final class RunBuffer {
		final List<NodeTaskResultBatch.Entry> entries = new ArrayList<>();
		int batchSize = 1;
		long oldestAt;
		/** Where this run's results go. Remembered so a timer flush knows where to send. */
		BatchSink sink;
	}

	private final Map<UUID, RunBuffer> buffers = new LinkedHashMap<>();
	private final long maxHoldMs;

	public ResultBatcher() {
		this(DEFAULT_MAX_HOLD_MS);
	}

	public ResultBatcher(long maxHoldMs) {
		this.maxHoldMs = maxHoldMs;
	}

	/**
	 * Record a result, sending the batch if it is now full.
	 *
	 * @param runUuid   the run, or null when the task carried none - such a result is
	 *                  sent immediately, since it cannot be grouped
	 * @param itemId    the item
	 * @param result    the outcome
	 * @param batchSize the pipeline's configured size
	 * @param sink      where to send it; the connection belongs to the task, not to
	 *                  this batcher
	 */
	public void add(UUID runUuid, String itemId, NodeTaskResult result, int batchSize, BatchSink sink) {
		if (runUuid == null || batchSize <= 1) {
			// Nothing to group with, or grouping switched off. Send it as it is.
			sink.send(new NodeTaskResultBatch(runUuid,
				List.of(new NodeTaskResultBatch.Entry(itemId, result))));
			return;
		}

		NodeTaskResultBatch ready = null;
		synchronized (this) {
			RunBuffer buffer = buffers.computeIfAbsent(runUuid, k -> new RunBuffer());
			if (buffer.entries.isEmpty()) {
				buffer.oldestAt = System.currentTimeMillis();
			}
			buffer.batchSize = batchSize;
			buffer.sink = sink;
			buffer.entries.add(new NodeTaskResultBatch.Entry(itemId, result));

			if (buffer.entries.size() >= batchSize) {
				ready = drain(runUuid, buffer);
			}
		}
		// Sent outside the lock: a slow socket must not stall every worker thread
		// producing results for this run.
		if (ready != null) {
			sink.send(ready);
		}
	}

	/**
	 * Send any batch that has been waiting too long.
	 *
	 * <p>Call periodically. Without it the tail of every run is stranded.</p>
	 *
	 * @return how many batches were sent
	 */
	public int flushExpired() {
		return flushExpired(System.currentTimeMillis());
	}

	/**
	 * @param now the current time, injected so tests need no sleeping
	 * @return how many batches were sent
	 */
	public int flushExpired(long now) {
		List<Runnable> ready = new ArrayList<>();
		synchronized (this) {
			for (Map.Entry<UUID, RunBuffer> entry : new ArrayList<>(buffers.entrySet())) {
				RunBuffer buffer = entry.getValue();
				if (!buffer.entries.isEmpty() && now - buffer.oldestAt >= maxHoldMs) {
					BatchSink target = buffer.sink;
					NodeTaskResultBatch batch = drain(entry.getKey(), buffer);
					if (target != null) {
						ready.add(() -> target.send(batch));
					}
				}
			}
		}
		ready.forEach(Runnable::run);
		return ready.size();
	}

	/**
	 * Send everything held for a run, whatever its size.
	 *
	 * <p>Used when a run is known to be finishing, so its tail does not wait out the
	 * hold time.</p>
	 *
	 * @param runUuid the run
	 * @return true when something was sent
	 */
	public boolean flushRun(UUID runUuid) {
		NodeTaskResultBatch ready;
		BatchSink target;
		synchronized (this) {
			RunBuffer buffer = buffers.get(runUuid);
			if (buffer == null || buffer.entries.isEmpty()) {
				buffers.remove(runUuid);
				return false;
			}
			target = buffer.sink;
			ready = drain(runUuid, buffer);
		}
		if (target == null) {
			return false;
		}
		target.send(ready);
		return true;
	}

	/** @return results currently held for a run */
	public synchronized int pendingFor(UUID runUuid) {
		RunBuffer buffer = buffers.get(runUuid);
		return buffer == null ? 0 : buffer.entries.size();
	}

	/** Caller must hold the monitor. */
	private NodeTaskResultBatch drain(UUID runUuid, RunBuffer buffer) {
		NodeTaskResultBatch batch = new NodeTaskResultBatch(runUuid, new ArrayList<>(buffer.entries));
		buffer.entries.clear();
		if (log.isDebugEnabled()) {
			log.debug("Sending {} result(s) for run {}", batch.size(), runUuid);
		}
		return batch;
	}

}
