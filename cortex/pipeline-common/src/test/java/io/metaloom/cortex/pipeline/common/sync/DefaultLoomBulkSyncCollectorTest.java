package io.metaloom.cortex.pipeline.common.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.pipeline.common.StubMedia;
import io.metaloom.cortex.pipeline.common.sync.DefaultLoomBulkSyncCollector.SyncEntry;

/**
 * The collector that batches node results on their way to Loom.
 *
 * <p>
 * Its whole reason to exist is that one REST call per node result does not scale, so what matters is
 * <em>when</em> it writes: at the batch size, on an explicit flush, and never on an empty buffer.
 * The failure path is the interesting half - a write that fails puts its entries back rather than
 * dropping them, because these results are the only record that the work was done.
 * </p>
 */
public class DefaultLoomBulkSyncCollectorTest {

	/** A writer that records what it was given and can be told to fail. */
	private static class RecordingWriter implements DefaultLoomBulkSyncCollector.BulkSyncWriter {

		final List<List<String>> batches = new ArrayList<>();
		boolean failing;

		@Override
		public void writeBulk(List<SyncEntry> entries) throws Exception {
			List<String> ids = new ArrayList<>();
			entries.forEach(entry -> ids.add(entry.getNodeId()));
			batches.add(ids);
			if (failing) {
				throw new IllegalStateException("Loom is unreachable");
			}
		}
	}

	private final RecordingWriter writer = new RecordingWriter();

	private void collect(DefaultLoomBulkSyncCollector collector, String nodeId) {
		collector.collect(new StubMedia("/media/" + nodeId + ".mp4"), nodeId, new NodeResult(ResultState.SUCCESS));
	}

	// ── When it writes ────────────────────────────────────────────────────

	@Test
	void testNothingIsWrittenBelowTheBatchSize() {
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(writer, 3);

		collect(collector, "a");
		collect(collector, "b");

		assertEquals(0, writer.batches.size(), "Two of three is not a batch");
		assertEquals(2, collector.pending());
	}

	@Test
	void testReachingTheBatchSizeFlushesAutomatically() {
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(writer, 3);

		collect(collector, "a");
		collect(collector, "b");
		collect(collector, "c");

		assertEquals(List.of(List.of("a", "b", "c")), writer.batches);
		assertEquals(0, collector.pending(), "A flushed batch must leave the buffer empty");
	}

	/**
	 * The tail of a run never reaches the batch size, so an explicit flush is what makes the last
	 * results of every run reach Loom at all.
	 */
	@Test
	void testAnExplicitFlushDrainsWhateverIsPending() {
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(writer, 100);

		collect(collector, "a");
		collect(collector, "b");

		assertEquals(2, collector.flush(), "flush() answers with how many entries it sent");
		assertEquals(List.of(List.of("a", "b")), writer.batches);
		assertEquals(0, collector.pending());
	}

	@Test
	void testFlushingAnEmptyBufferDoesNotCallTheWriter() {
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(writer, 100);

		assertEquals(0, collector.flush());
		assertTrue(writer.batches.isEmpty(), "An empty flush must not cost a REST round trip");
	}

	@Test
	void testTheDefaultBatchSizeIsOneHundred() {
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(writer);

		for (int i = 0; i < 99; i++) {
			collect(collector, "n" + i);
		}
		assertEquals(0, writer.batches.size());

		collect(collector, "n99");
		assertEquals(1, writer.batches.size());
		assertEquals(100, writer.batches.get(0).size());
	}

	// ── What it writes ────────────────────────────────────────────────────

	@Test
	void testEntriesKeepTheirMediaNodeAndResultInCollectionOrder() {
		List<SyncEntry> captured = new ArrayList<>();
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(captured::addAll, 2);

		NodeResult first = new NodeResult(ResultState.SUCCESS);
		NodeResult second = new NodeResult(ResultState.SKIPPED);
		collector.collect(new StubMedia("/media/a.mp4"), "sha512", first);
		collector.collect(new StubMedia("/media/b.mp4"), "md5", second);

		assertEquals(2, captured.size());
		assertEquals("sha512", captured.get(0).getNodeId());
		assertEquals("/media/a.mp4", captured.get(0).getMedia().absolutePath());
		assertEquals(first, captured.get(0).getResult());
		assertEquals("md5", captured.get(1).getNodeId());
		assertEquals(second, captured.get(1).getResult());
	}

	@Test
	void testAnEntryDescribesItselfAsNodeAndPath() {
		// This string is what a failed bulk write is logged with, so it has to name both halves.
		SyncEntry entry = new SyncEntry(new StubMedia("/media/a.mp4"), "sha512", new NodeResult(ResultState.SUCCESS));
		assertEquals("sha512 -> /media/a.mp4", entry.toString());
	}

	// ── When the write fails ──────────────────────────────────────────────

	/**
	 * A failed write must not lose the batch. These results are the only record that the work was
	 * done, and the node that produced them has already moved on - dropping them means the work is
	 * silently redone or silently lost.
	 */
	@Test
	void testAFailedWriteKeepsTheEntriesForTheNextAttempt() {
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(writer, 100);
		writer.failing = true;

		collect(collector, "a");
		collect(collector, "b");

		assertEquals(0, collector.flush(), "A failed flush reports that it sent nothing");
		assertEquals(1, writer.batches.size(), "...but it did attempt the write");
		assertEquals(2, collector.pending(), "and the entries are still pending");
	}

	@Test
	void testTheKeptEntriesGoOutOnTheNextFlush() {
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(writer, 100);
		writer.failing = true;
		collect(collector, "a");
		collector.flush();

		writer.failing = false;
		collect(collector, "b");

		assertEquals(2, collector.flush());
		assertEquals(List.of("a"), writer.batches.get(0), "The first attempt carried only what was buffered then");
		assertEquals(List.of("a", "b"), writer.batches.get(1),
			"The retry carries the kept entry together with whatever arrived since");
		assertEquals(0, collector.pending());
	}

	/**
	 * The consequence of keeping a failed batch, made explicit: the buffer stays at or above the
	 * batch size, so <em>every</em> subsequent {@code collect} re-attempts the write. That is the
	 * behaviour today - there is no backoff here - and a worker collecting during a Loom outage
	 * therefore calls the writer once per result rather than once per batch.
	 */
	@Test
	void testWhileTheWriterFailsEverySubsequentCollectRetriesTheWholeBuffer() {
		DefaultLoomBulkSyncCollector collector = new DefaultLoomBulkSyncCollector(writer, 2);
		writer.failing = true;

		collect(collector, "a");
		collect(collector, "b");
		assertEquals(List.of(List.of("a", "b")), writer.batches);

		collect(collector, "c");

		assertEquals(2, writer.batches.size(), "The buffer is still full, so collecting triggers another attempt");
		assertEquals(List.of("a", "b", "c"), writer.batches.get(1));
		assertEquals(3, collector.pending(), "Nothing was dropped");
	}
}
