package io.metaloom.cortex.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Tests for source enumeration, batching and ack-driven backpressure.
 *
 * <p>Two properties are load-bearing. First, a large scan must be batched - a
 * 100 000 file scan cannot become 100 000 frames. Second, the runner must not race
 * ahead of the engine: it waits for each batch to be acknowledged, and a missing ack
 * must fail the source rather than hang it forever.</p>
 */
public class SourceTaskRunnerTest {

	/** Records what the runner emits and can auto-acknowledge. */
	private static class RecordingSink implements SourceTaskRunner.BatchSink {

		private final SourceTaskRunner runner;
		private final UUID runUuid;
		private final boolean autoAck;
		final List<List<MediaRef>> batches = new CopyOnWriteArrayList<>();
		final AtomicLong completedTotal = new AtomicLong(-1);
		final AtomicReference<String> completedError = new AtomicReference<>();
		final CountDownLatch completed = new CountDownLatch(1);

		RecordingSink(SourceTaskRunner runner, UUID runUuid, boolean autoAck) {
			this.runner = runner;
			this.runUuid = runUuid;
			this.autoAck = autoAck;
		}

		@Override
		public void sendBatch(long seq, List<MediaRef> items) {
			batches.add(new ArrayList<>(items));
			if (autoAck) {
				// Ack from another thread, as Loom would.
				new Thread(() -> runner.onAck(runUuid, seq)).start();
			}
		}

		@Override
		public void sendComplete(long totalCount, String error) {
			completedTotal.set(totalCount);
			completedError.set(error);
			completed.countDown();
		}
	}

	private static Flowable<LoomMedia> mediaStream(int count) {
		return Flowable.range(1, count).map(i -> new StubLoomMedia("/media/file-" + i + ".mp4"));
	}

	@Test
	@Timeout(20)
	void testItemsAreBatchedAndCompleted() throws Exception {
		SourceTaskRunner runner = new SourceTaskRunner();
		UUID runUuid = UUID.randomUUID();
		RecordingSink sink = new RecordingSink(runner, runUuid, true);

		runner.run(runUuid, mediaStream(10), 4, sink);

		assertTrue(sink.completed.await(5, TimeUnit.SECONDS));
		assertEquals(3, sink.batches.size(), "10 items at batch size 4 must arrive as 4+4+2");
		assertEquals(4, sink.batches.get(0).size());
		assertEquals(4, sink.batches.get(1).size());
		assertEquals(2, sink.batches.get(2).size());
		assertEquals(10, sink.completedTotal.get());
		assertNull(sink.completedError.get(), "A clean scan reports no error");
	}

	@Test
	@Timeout(20)
	void testMediaPathsAreCarriedAsReferences() throws Exception {
		SourceTaskRunner runner = new SourceTaskRunner();
		UUID runUuid = UUID.randomUUID();
		RecordingSink sink = new RecordingSink(runner, runUuid, true);

		runner.run(runUuid, mediaStream(2), 10, sink);

		assertTrue(sink.completed.await(5, TimeUnit.SECONDS));
		MediaRef first = sink.batches.get(0).get(0);
		assertEquals("/media/file-1.mp4", first.getPath());
		assertNull(first.getSha512(), "The source must not hash - that is a node's job");
	}

	@Test
	@Timeout(20)
	void testRunnerWaitsForAnAckBeforeSendingTheNextBatch() throws Exception {
		SourceTaskRunner runner = new SourceTaskRunner();
		UUID runUuid = UUID.randomUUID();
		RecordingSink sink = new RecordingSink(runner, runUuid, false);

		Thread scan = new Thread(() -> runner.run(runUuid, mediaStream(10), 2, sink));
		scan.start();

		// Without an ack the runner must stall after exactly one batch. If it raced
		// ahead, a fast scan would bury a slower engine.
		Thread.sleep(300);
		assertEquals(1, sink.batches.size(), "The runner must not send batch 2 before batch 1 is acked");

		// Release it one batch at a time.
		for (long seq = 0; seq < 5; seq++) {
			runner.onAck(runUuid, seq);
			Thread.sleep(50);
		}

		assertTrue(sink.completed.await(5, TimeUnit.SECONDS));
		assertEquals(5, sink.batches.size());
		assertEquals(10, sink.completedTotal.get());
		scan.join(5000);
	}

	@Test
	@Timeout(20)
	void testMissingAckFailsTheSourceRatherThanHanging() throws Exception {
		// A short timeout so the test does not wait a minute.
		SourceTaskRunner runner = new SourceTaskRunner(300);
		UUID runUuid = UUID.randomUUID();
		RecordingSink sink = new RecordingSink(runner, runUuid, false);

		runner.run(runUuid, mediaStream(10), 2, sink);

		assertTrue(sink.completed.await(5, TimeUnit.SECONDS),
			"A source that is never acked must still terminate");
		assertNotNull(sink.completedError.get(), "The failure must be reported, not swallowed");
		assertTrue(sink.completedError.get().contains("acknowledgement"),
			"Expected an ack-timeout message, got: " + sink.completedError.get());
	}

	@Test
	@Timeout(20)
	void testFailingStreamStillReportsCompletion() throws Exception {
		SourceTaskRunner runner = new SourceTaskRunner();
		UUID runUuid = UUID.randomUUID();
		RecordingSink sink = new RecordingSink(runner, runUuid, true);

		Flowable<LoomMedia> broken = Flowable.<LoomMedia>error(new IllegalStateException("permission denied"));
		runner.run(runUuid, broken, 4, sink);

		assertTrue(sink.completed.await(5, TimeUnit.SECONDS));
		assertEquals("permission denied", sink.completedError.get(),
			"A run cannot close without a completion signal, even on failure");
	}

	@Test
	@Timeout(20)
	void testEmptySourceCompletesWithZeroItems() throws Exception {
		SourceTaskRunner runner = new SourceTaskRunner();
		UUID runUuid = UUID.randomUUID();
		RecordingSink sink = new RecordingSink(runner, runUuid, true);

		runner.run(runUuid, Flowable.empty(), 4, sink);

		assertTrue(sink.completed.await(5, TimeUnit.SECONDS));
		assertTrue(sink.batches.isEmpty());
		assertEquals(0, sink.completedTotal.get());
		assertNull(sink.completedError.get(),
			"An empty selection is a legitimate outcome, not a failure");
	}

	@Test
	@Timeout(20)
	void testCancelReleasesAWaitingScan() throws Exception {
		SourceTaskRunner runner = new SourceTaskRunner(30_000);
		UUID runUuid = UUID.randomUUID();
		RecordingSink sink = new RecordingSink(runner, runUuid, false);

		Thread scan = new Thread(() -> runner.run(runUuid, mediaStream(10), 2, sink));
		scan.start();
		Thread.sleep(200);

		runner.cancel(runUuid);

		assertTrue(sink.completed.await(5, TimeUnit.SECONDS),
			"Cancelling must unblock the scan rather than leave the thread parked");
		scan.join(5000);
	}

	@Test
	@Timeout(20)
	void testBatchSizeBelowOneIsTreatedAsOne() throws Exception {
		SourceTaskRunner runner = new SourceTaskRunner();
		UUID runUuid = UUID.randomUUID();
		RecordingSink sink = new RecordingSink(runner, runUuid, true);

		runner.run(runUuid, mediaStream(3), 0, sink);

		assertTrue(sink.completed.await(5, TimeUnit.SECONDS));
		assertEquals(3, sink.batches.size(), "A zero batch size must not produce empty or infinite batches");
	}
}
