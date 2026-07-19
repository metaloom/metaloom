package io.metaloom.cortex.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.NodeTaskResultBatch;

/**
 * Accumulating results before sending them.
 *
 * <p>The saving is obvious; the hazard is not. A run's tail will never reach the
 * batch size — a 500-item run batched at 200 ends with 100 results in the buffer —
 * so anything that only flushes on size strands the end of every run and the engine
 * waits forever for results it has been told to expect.</p>
 */
public class ResultBatcherTest {

	private final List<NodeTaskResultBatch> sent = new ArrayList<>();
	private final ResultBatcher.BatchSink sink = sent::add;

	private static NodeTaskResult result(String nodeId) {
		return NodeTaskResult.completed(UUID.randomUUID(), nodeId, 5, Map.of());
	}

	private int totalEntries() {
		return sent.stream().mapToInt(NodeTaskResultBatch::size).sum();
	}

	@Test
	void testABatchSizeOfOneSendsImmediately() {
		ResultBatcher batcher = new ResultBatcher();
		UUID run = UUID.randomUUID();

		batcher.add(run, "item-1", result("hash"), 1, sink);

		// The default, and the previous behaviour: batching is opt-in.
		assertEquals(1, sent.size());
		assertEquals(1, sent.get(0).size());
	}

	@Test
	void testResultsAccumulateUntilTheBatchIsFull() {
		ResultBatcher batcher = new ResultBatcher();
		UUID run = UUID.randomUUID();

		for (int i = 0; i < 4; i++) {
			batcher.add(run, "item-" + i, result("hash"), 5, sink);
		}
		assertTrue(sent.isEmpty(), "Nothing goes out until the batch is full");
		assertEquals(4, batcher.pendingFor(run));

		batcher.add(run, "item-4", result("hash"), 5, sink);

		assertEquals(1, sent.size());
		assertEquals(5, sent.get(0).size());
		assertEquals(0, batcher.pendingFor(run));
	}

	@Test
	void testATailShorterThanTheBatchIsFlushedOnTime() {
		ResultBatcher batcher = new ResultBatcher(500);
		UUID run = UUID.randomUUID();

		for (int i = 0; i < 3; i++) {
			batcher.add(run, "item-" + i, result("hash"), 200, sink);
		}
		assertTrue(sent.isEmpty());

		// Not yet due.
		assertEquals(0, batcher.flushExpired(System.currentTimeMillis()));

		// This is what stops the end of every run being stranded.
		assertEquals(1, batcher.flushExpired(System.currentTimeMillis() + 1000));
		assertEquals(3, sent.get(0).size());
	}

	@Test
	void testFlushingARunSendsWhateverIsHeld() {
		ResultBatcher batcher = new ResultBatcher();
		UUID run = UUID.randomUUID();

		batcher.add(run, "item-1", result("hash"), 100, sink);
		assertTrue(batcher.flushRun(run));

		assertEquals(1, sent.size());
		assertEquals(1, sent.get(0).size());
		// A second flush has nothing to do and must not send an empty batch.
		assertTrue(!batcher.flushRun(run));
		assertEquals(1, sent.size());
	}

	@Test
	void testRunsAreBatchedIndependently() {
		ResultBatcher batcher = new ResultBatcher();
		UUID runA = UUID.randomUUID();
		UUID runB = UUID.randomUUID();

		batcher.add(runA, "a1", result("hash"), 2, sink);
		batcher.add(runB, "b1", result("hash"), 2, sink);
		assertTrue(sent.isEmpty(), "One run's results must not fill another's batch");

		batcher.add(runA, "a2", result("hash"), 2, sink);

		assertEquals(1, sent.size());
		assertEquals(runA, sent.get(0).getRunUuid());
		assertEquals(1, batcher.pendingFor(runB));
	}

	@Test
	void testEveryResultIsAccountedFor() {
		ResultBatcher batcher = new ResultBatcher();
		UUID run = UUID.randomUUID();

		for (int i = 0; i < 17; i++) {
			batcher.add(run, "item-" + i, result("hash"), 5, sink);
		}
		batcher.flushRun(run);

		// Losing a result is worse than any saving batching provides: the engine would
		// wait on it forever.
		assertEquals(17, totalEntries());
	}

	@Test
	void testTheItemAndResultStayPaired() {
		ResultBatcher batcher = new ResultBatcher();
		UUID run = UUID.randomUUID();

		batcher.add(run, "item-a", result("hash"), 2, sink);
		batcher.add(run, "item-b", result("thumb"), 2, sink);

		List<NodeTaskResultBatch.Entry> entries = sent.get(0).getEntries();
		// A batch that mixed these up would attribute work to the wrong file.
		assertEquals("item-a", entries.get(0).getItemId());
		assertEquals("hash", entries.get(0).getResult().getNodeId());
		assertEquals("item-b", entries.get(1).getItemId());
		assertEquals("thumb", entries.get(1).getResult().getNodeId());
	}

	@Test
	void testAResultWithNoRunIsSentImmediately() {
		ResultBatcher batcher = new ResultBatcher();

		// Nothing to group it with, so holding it would strand it.
		batcher.add(null, "item-1", result("hash"), 100, sink);

		assertEquals(1, sent.size());
	}

	@Test
	void testAnEmptyBatcherFlushesNothing() {
		ResultBatcher batcher = new ResultBatcher();

		assertEquals(0, batcher.flushExpired(System.currentTimeMillis() + 100_000));
		assertTrue(sent.isEmpty(), "An idle worker must not send empty batches forever");
	}

}
