package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventType;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.metaloom.loom.rest.service.impl.RunStatsAggregator;

/**
 * Keeping a million node settles from becoming a million UI frames.
 *
 * <p>The balance under test: volume is counted and pushed periodically, while
 * failures — rare and individually actionable — still arrive promptly and name a
 * file rather than an opaque id.</p>
 */
public class RunStatsAggregatorTest {

	/** Captures what would have gone to subscribers. */
	private static class CapturingBroadcaster extends PipelineEventBroadcaster {

		final List<PipelineEventMessage> events = new ArrayList<>();

		@Override
		public void broadcast(PipelineEventMessage event) {
			events.add(event);
		}

		List<PipelineEventMessage> ofType(PipelineEventType type) {
			return events.stream().filter(e -> e.getType() == type).toList();
		}
	}

	private final UUID runUuid = UUID.randomUUID();

	private RunStatsAggregator aggregator(CapturingBroadcaster broadcaster) {
		return new RunStatsAggregator(runUuid, "test-pipeline", broadcaster);
	}

	@Test
	void testSuccessesAreCountedNotBroadcast() {
		CapturingBroadcaster broadcaster = new CapturingBroadcaster();
		RunStatsAggregator aggregator = aggregator(broadcaster);

		for (int i = 0; i < 1000; i++) {
			aggregator.onNodeSettled("item-" + i, "/media/" + i + ".mp4", "hash", NodeState.COMPLETED, null);
		}

		// The whole point: a thousand settles produce no traffic at all until a flush.
		assertTrue(broadcaster.events.isEmpty(), "Volume must not be forwarded per item");
		assertEquals(1000, aggregator.snapshot().get("hash").getCompleted());
	}

	@Test
	void testAFlushPushesOneSnapshotPerNode() {
		CapturingBroadcaster broadcaster = new CapturingBroadcaster();
		RunStatsAggregator aggregator = aggregator(broadcaster);

		for (int i = 0; i < 500; i++) {
			aggregator.onNodeSettled("item-" + i, "/media/x.mp4", "hash", NodeState.COMPLETED, null);
			aggregator.onNodeSettled("item-" + i, "/media/x.mp4", "thumb", NodeState.SKIPPED, "filtered");
		}

		assertEquals(2, aggregator.flush(), "One snapshot per node, not per item");

		List<PipelineEventMessage> stats = broadcaster.ofType(PipelineEventType.NODE_STATS);
		assertEquals(2, stats.size());
		PipelineEventMessage hash = stats.stream().filter(e -> "hash".equals(e.getNodeId())).findFirst().orElseThrow();
		assertEquals(500L, hash.getProcessedCount());
		assertEquals(0L, hash.getFailedCount());
	}

	@Test
	void testSkipsAreCountedSeparatelyFromSuccesses() {
		CapturingBroadcaster broadcaster = new CapturingBroadcaster();
		RunStatsAggregator aggregator = aggregator(broadcaster);

		aggregator.onNodeSettled("i1", "/a.mp4", "thumb", NodeState.COMPLETED, null);
		aggregator.onNodeSettled("i2", "/b.mp4", "thumb", NodeState.SKIPPED, "filtered out");
		aggregator.flush();

		PipelineEventMessage stats = broadcaster.ofType(PipelineEventType.NODE_STATS).get(0);
		// Reporting a skip as processed would overstate what the run actually did.
		assertEquals(1L, stats.getProcessedCount());
		assertEquals(1L, stats.getSkippedCount());
	}

	@Test
	void testFailuresGoOutImmediatelyAndNameTheFile() {
		CapturingBroadcaster broadcaster = new CapturingBroadcaster();
		RunStatsAggregator aggregator = aggregator(broadcaster);

		aggregator.onNodeSettled("item-7", "/media/broken.mp4", "hash", NodeState.FAILED, "checksum error");

		// Aggregating failures would let the UI say "300 failed" but not which files.
		List<PipelineEventMessage> failures = broadcaster.ofType(PipelineEventType.NODE_FAILED);
		assertEquals(1, failures.size());
		assertEquals("/media/broken.mp4", failures.get(0).getMediaPath(),
			"An operator needs the path, not an opaque item id");
		assertEquals("checksum error", failures.get(0).getMessage());
		assertEquals("hash", failures.get(0).getNodeId());
	}

	@Test
	void testFailuresAreAlsoCounted() {
		CapturingBroadcaster broadcaster = new CapturingBroadcaster();
		RunStatsAggregator aggregator = aggregator(broadcaster);

		aggregator.onNodeSettled("i1", "/a.mp4", "hash", NodeState.FAILED, "boom");
		aggregator.flush();

		// Being individually reported must not exclude a failure from the totals.
		assertEquals(1L, broadcaster.ofType(PipelineEventType.NODE_STATS).get(0).getFailedCount());
	}

	@Test
	void testAnIdleRunProducesNoTraffic() {
		CapturingBroadcaster broadcaster = new CapturingBroadcaster();
		RunStatsAggregator aggregator = aggregator(broadcaster);

		aggregator.onNodeSettled("i1", "/a.mp4", "hash", NodeState.COMPLETED, null);
		assertEquals(1, aggregator.flush());

		// A finished or stalled run would otherwise re-send identical counters every
		// tick for the life of the process.
		assertEquals(0, aggregator.flush(), "Nothing changed, so nothing is sent");
		assertEquals(0, aggregator.flush());
	}

	@Test
	void testAFlushResumesAfterNewActivity() {
		CapturingBroadcaster broadcaster = new CapturingBroadcaster();
		RunStatsAggregator aggregator = aggregator(broadcaster);

		aggregator.onNodeSettled("i1", "/a.mp4", "hash", NodeState.COMPLETED, null);
		aggregator.flush();
		aggregator.flush();

		aggregator.onNodeSettled("i2", "/b.mp4", "hash", NodeState.COMPLETED, null);
		assertEquals(1, aggregator.flush(), "New work must resume reporting");
		assertEquals(2L, broadcaster.ofType(PipelineEventType.NODE_STATS)
			.get(broadcaster.ofType(PipelineEventType.NODE_STATS).size() - 1).getProcessedCount());
	}

	@Test
	void testCountersAreKeptPerNode() {
		CapturingBroadcaster broadcaster = new CapturingBroadcaster();
		RunStatsAggregator aggregator = aggregator(broadcaster);

		aggregator.onNodeSettled("i1", "/a.mp4", "hash", NodeState.COMPLETED, null);
		aggregator.onNodeSettled("i1", "/a.mp4", "thumb", NodeState.FAILED, "no frames");

		assertEquals(1, aggregator.snapshot().get("hash").getCompleted());
		assertEquals(0, aggregator.snapshot().get("hash").getFailed());
		assertEquals(1, aggregator.snapshot().get("thumb").getFailed());
	}

	@Test
	void testLiveActiveAndPendingCountsAreReported() {
		CapturingBroadcaster broadcaster = new CapturingBroadcaster();
		RunStatsAggregator aggregator = aggregator(broadcaster);
		aggregator.setProgressSupplier(() -> java.util.Map.of("hash", new int[] { 3, 17 }));

		aggregator.onNodeSettled("i1", "/a.mp4", "hash", NodeState.COMPLETED, null);
		aggregator.flush();

		// Reporting pending as a hardcoded zero, as the events previously did, makes a
		// saturated run look idle.
		PipelineEventMessage stats = broadcaster.ofType(PipelineEventType.NODE_STATS).get(0);
		assertEquals(3, stats.getActiveCount());
		assertEquals(17, stats.getPendingCount());
	}

	@Test
	void testAFailingProgressSupplierDoesNotStopTheFlush() {
		CapturingBroadcaster broadcaster = new CapturingBroadcaster();
		RunStatsAggregator aggregator = aggregator(broadcaster);
		aggregator.setProgressSupplier(() -> {
			throw new IllegalStateException("engine gone");
		});

		aggregator.onNodeSettled("i1", "/a.mp4", "hash", NodeState.COMPLETED, null);

		// Counters are still worth sending even when the live snapshot is unavailable.
		assertEquals(1, aggregator.flush());
		assertEquals(1L, broadcaster.ofType(PipelineEventType.NODE_STATS).get(0).getProcessedCount());
	}

	@Test
	void testABrokenSubscriberDoesNotStopTheRun() {
		PipelineEventBroadcaster exploding = new PipelineEventBroadcaster() {
			@Override
			public void broadcast(PipelineEventMessage event) {
				throw new IllegalStateException("socket gone");
			}
		};
		RunStatsAggregator aggregator = new RunStatsAggregator(runUuid, "test-pipeline", exploding);

		// Observation must never be able to break the thing being observed - this runs
		// on the engine thread with the monitor held.
		aggregator.onNodeSettled("i1", "/a.mp4", "hash", NodeState.FAILED, "boom");
		aggregator.flush();

		assertEquals(1, aggregator.snapshot().get("hash").getFailed());
	}

}
