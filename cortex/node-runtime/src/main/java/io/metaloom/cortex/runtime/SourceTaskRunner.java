package io.metaloom.cortex.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Runs a source node and streams what it enumerates back to Loom in batches.
 *
 * <p><strong>Batching is a requirement, not an optimisation.</strong> A 100 000 file
 * scan must not become 100 000 WebSocket frames.</p>
 *
 * <p><strong>Acks are the backpressure.</strong> After sending a batch the runner
 * waits for Loom to acknowledge it before sending the next. Without that a fast
 * filesystem scan simply buries a slower engine, and neither side has any other
 * flow-control signal in Phase 1. A missing ack fails the source rather than
 * hanging forever.</p>
 *
 * <p>⚠️ {@link #run} blocks the calling thread for the duration of the scan. Call it
 * from a worker thread, never from a Vert.x event loop.</p>
 */
public class SourceTaskRunner {

	private static final Logger log = LoggerFactory.getLogger(SourceTaskRunner.class);

	/** How long to wait for a batch ack before giving up on the source. */
	public static final long DEFAULT_ACK_TIMEOUT_MS = 60_000;

	private final Map<String, CountDownLatch> pendingAcks = new ConcurrentHashMap<>();
	private final java.util.Set<UUID> cancelled = ConcurrentHashMap.newKeySet();
	private final long ackTimeoutMs;

	/** Receives batches and the terminal signal. */
	public interface BatchSink {

		/**
		 * @param seq   monotonic batch number, starting at 0
		 * @param items the discovered references
		 */
		void sendBatch(long seq, List<MediaRef> items);

		/**
		 * @param totalCount how many items were emitted in total
		 * @param error      failure detail, or null on a clean finish
		 */
		void sendComplete(long totalCount, String error);
	}

	public SourceTaskRunner() {
		this(DEFAULT_ACK_TIMEOUT_MS);
	}

	public SourceTaskRunner(long ackTimeoutMs) {
		this.ackTimeoutMs = ackTimeoutMs;
	}

	/**
	 * Enumerate the source and stream the results.
	 *
	 * <p>Always terminates with exactly one {@code sendComplete}, whether the scan
	 * succeeded, failed, or was cut short by a missing ack - the engine cannot close
	 * a run without it.</p>
	 *
	 * @param runUuid   the run these items belong to
	 * @param stream    the cold stream produced by the source node
	 * @param batchSize how many items per batch; values below 1 are treated as 1
	 * @param sink      where batches are sent
	 */
	public void run(UUID runUuid, Flowable<LoomMedia> stream, int batchSize, BatchSink sink) {
		int effectiveBatchSize = Math.max(1, batchSize);
		AtomicLong seq = new AtomicLong();
		AtomicLong total = new AtomicLong();

		try {
			stream
				.map(SourceTaskRunner::toRef)
				.buffer(effectiveBatchSize)
				.blockingForEach(batch -> {
					long currentSeq = seq.getAndIncrement();
					CountDownLatch ack = new CountDownLatch(1);
					pendingAcks.put(ackKey(runUuid, currentSeq), ack);

					sink.sendBatch(currentSeq, batch);
					total.addAndGet(batch.size());

					boolean acked = ack.await(ackTimeoutMs, TimeUnit.MILLISECONDS);

					// Cancellation also releases the latch, so distinguish the two:
					// treating a cancel as an ack would carry on scanning after the
					// connection is already gone.
					if (cancelled.contains(runUuid)) {
						throw new IllegalStateException("Source cancelled");
					}
					if (!acked) {
						pendingAcks.remove(ackKey(runUuid, currentSeq));
						throw new IllegalStateException("No acknowledgement for batch " + currentSeq
							+ " within " + ackTimeoutMs + "ms");
					}
				});

			log.info("Source for run {} enumerated {} item(s) in {} batch(es)", runUuid, total.get(), seq.get());
			sink.sendComplete(total.get(), null);
		} catch (Exception e) {
			log.error("Source enumeration failed for run {} after {} item(s)", runUuid, total.get(), e);
			sink.sendComplete(total.get(), describe(e));
		} finally {
			clearPending(runUuid);
			cancelled.remove(runUuid);
		}
	}

	/**
	 * Release the runner to send the next batch.
	 *
	 * @param runUuid the run
	 * @param seq     the batch being acknowledged
	 */
	public void onAck(UUID runUuid, long seq) {
		CountDownLatch ack = pendingAcks.remove(ackKey(runUuid, seq));
		if (ack == null) {
			// A duplicate or late ack. Harmless, but worth seeing when debugging.
			log.debug("Ignoring unexpected ack for run {} batch {}", runUuid, seq);
			return;
		}
		ack.countDown();
	}

	/**
	 * Abandon any wait in progress, e.g. because the connection dropped.
	 *
	 * @param runUuid the run to release
	 */
	public void cancel(UUID runUuid) {
		cancelled.add(runUuid);
		clearPending(runUuid);
	}

	private void clearPending(UUID runUuid) {
		String prefix = runUuid + "/";
		pendingAcks.keySet().removeIf(key -> {
			if (key.startsWith(prefix)) {
				CountDownLatch latch = pendingAcks.get(key);
				if (latch != null) {
					// Release the waiter so run() can unwind instead of blocking to timeout.
					latch.countDown();
				}
				return true;
			}
			return false;
		});
	}

	private static String ackKey(UUID runUuid, long seq) {
		return runUuid + "/" + seq;
	}

	private static MediaRef toRef(LoomMedia media) {
		return new MediaRef(media.absolutePath(), null, sizeOf(media));
	}

	private static long sizeOf(LoomMedia media) {
		try {
			return media.size();
		} catch (Exception e) {
			// Size is informational; a file that vanished mid-scan should not abort
			// the whole enumeration.
			return -1;
		}
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}
}
