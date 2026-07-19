package io.metaloom.loom.rest.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventType;

/**
 * Turns a run's per-node firehose into something a progress bar can consume.
 *
 * <p>A 100 000 item run over a 10 node graph settles a million nodes. Forwarding one
 * event per settle to every UI subscriber would be millions of frames to render a
 * bar that moves in percent — the flood §7.3 warns about. Counters are accumulated
 * here and pushed on a timer instead.</p>
 *
 * <h2>What still goes out immediately</h2>
 *
 * <p><strong>Failures.</strong> They are rare, individually actionable, and the one
 * thing an operator needs promptly — aggregating them into a number would leave the
 * UI able to say "300 failed" but not which files. Successes and skips are pure
 * volume and are only ever counted.</p>
 */
public class RunStatsAggregator implements io.metaloom.loom.pipeline.engine.PipelineRunEngine.NodeSettleListener {

	private static final Logger log = LoggerFactory.getLogger(RunStatsAggregator.class);

	/** Per-node running totals. */
	public static class NodeCounters {

		final AtomicInteger completed = new AtomicInteger();
		final AtomicInteger failed = new AtomicInteger();
		final AtomicInteger skipped = new AtomicInteger();

		public int getCompleted() {
			return completed.get();
		}

		public int getFailed() {
			return failed.get();
		}

		public int getSkipped() {
			return skipped.get();
		}

		public int getTotal() {
			return completed.get() + failed.get() + skipped.get();
		}
	}

	private final UUID runUuid;
	private final String pipelineName;
	private final PipelineEventBroadcaster broadcaster;
	private final Map<String, NodeCounters> counters = new ConcurrentHashMap<>();

	/** Set when something has changed since the last flush, so idle runs stay quiet. */
	private volatile boolean dirty;

	public RunStatsAggregator(UUID runUuid, String pipelineName, PipelineEventBroadcaster broadcaster) {
		this.runUuid = runUuid;
		this.pipelineName = pipelineName;
		this.broadcaster = broadcaster;
	}

	@Override
	public void onNodeSettled(String itemId, String mediaPath, String nodeId, NodeState state, String message) {
		NodeCounters node = counters.computeIfAbsent(nodeId, k -> new NodeCounters());
		switch (state) {
			case COMPLETED:
				node.completed.incrementAndGet();
				break;
			case FAILED:
				node.failed.incrementAndGet();
				// Rare and individually actionable, so it goes out now rather than being
				// summed into a number nobody can act on.
				emitFailure(mediaPath, nodeId, message);
				break;
			default:
				node.skipped.incrementAndGet();
				break;
		}
		dirty = true;
	}

	/**
	 * Push a snapshot of every node's counters, if anything changed.
	 *
	 * <p>Called on a timer. Skipping the push when nothing has changed keeps an idle
	 * or finished run from generating traffic forever.</p>
	 *
	 * @return how many node snapshots were sent
	 */
	public int flush() {
		if (!dirty) {
			return 0;
		}
		dirty = false;

		int sent = 0;
		for (Map.Entry<String, NodeCounters> entry : snapshot().entrySet()) {
			NodeCounters node = entry.getValue();
			try {
				broadcaster.broadcast(new PipelineEventMessage()
					.setType(PipelineEventType.NODE_STATS)
					.setPipelineName(pipelineName)
					.setPipelineRunUuid(runUuid.toString())
					.setNodeId(entry.getKey())
					.setProcessedCount((long) node.getCompleted())
					.setFailedCount((long) node.getFailed())
					.setSkippedCount((long) node.getSkipped()));
				sent++;
			} catch (Exception e) {
				log.error("Failed to broadcast stats for node '{}' of run {}", entry.getKey(), runUuid, e);
			}
		}
		return sent;
	}

	/** @return a stable copy of the counters, for tests and inspection */
	public Map<String, NodeCounters> snapshot() {
		return new LinkedHashMap<>(counters);
	}

	private void emitFailure(String mediaPath, String nodeId, String message) {
		try {
			broadcaster.broadcast(new PipelineEventMessage()
				.setType(PipelineEventType.NODE_FAILED)
				.setPipelineName(pipelineName)
				.setPipelineRunUuid(runUuid.toString())
				.setNodeId(nodeId)
				.setMediaPath(mediaPath)
				.setMessage(message));
		} catch (Exception e) {
			log.error("Failed to broadcast failure of node '{}' on '{}'", nodeId, mediaPath, e);
		}
	}

}
