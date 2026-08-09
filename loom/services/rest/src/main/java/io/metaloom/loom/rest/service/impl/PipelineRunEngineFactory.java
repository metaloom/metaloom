package io.metaloom.loom.rest.service.impl;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.common.metrics.LoomMetrics;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.pipeline.engine.NodeKindCircuitBreaker;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.engine.RunStateStore;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventType;
import io.vertx.core.Vertx;

/**
 * Builds a configured {@link PipelineRunEngine}.
 *
 * <p>
 * Assembling an engine is a dozen wiring decisions - metrics, the asset sink, the completion hooks
 * that close the run row and the UI socket, the stats timer, the circuit breaker, the retry
 * scheduler - and every one of them is a silent failure if it is forgotten: the run still executes,
 * it just stops reporting, stops retrying or stops writing hashes onto assets. Ad-hoc runs
 * ({@link NodeRunService}) need the same wiring as catalog runs ({@link PipelineEndpointService}),
 * so it lives here once rather than being copied a third time.
 * </p>
 *
 * <p>
 * What this deliberately does <em>not</em> do is start the engine, feed it items or register it.
 * Those are ordering decisions the caller owns: a recovered run must restore its items before it
 * becomes reachable, and an ad-hoc run must be registered before its first task can come back.
 * </p>
 */
@Singleton
public class PipelineRunEngineFactory {

	private static final Logger log = LoggerFactory.getLogger(PipelineRunEngineFactory.class);

	/** How often aggregated node counters are pushed to subscribers. */
	private static final long STATS_INTERVAL_MS = 1000;

	private final WebSocketNodeDispatcher dispatcher;
	private final LoomMetrics metrics;
	private final DaoCollection daos;
	private final PipelineRunTracker tracker;
	private final PipelineEventBroadcaster broadcaster;
	private final NodeKindCircuitBreaker circuitBreaker;
	private final Vertx vertx;

	@Inject
	public PipelineRunEngineFactory(WebSocketNodeDispatcher dispatcher, LoomMetrics metrics, DaoCollection daos,
		PipelineRunTracker tracker, PipelineEventBroadcaster broadcaster, Vertx vertx) {
		this.dispatcher = dispatcher;
		this.metrics = metrics;
		this.daos = daos;
		this.tracker = tracker;
		this.broadcaster = broadcaster;
		this.vertx = vertx;
		// One breaker for the whole process, so its per-kind trip counter and state gauge describe the
		// fleet rather than whichever run happened to notice first.
		this.circuitBreaker = new NodeKindCircuitBreaker(metrics);
	}

	/**
	 * How an engine should report on itself.
	 *
	 * @param label           the name used in run lifecycle frames; the pipeline name for a catalog
	 *                        run, a synthetic label for an ad-hoc one
	 * @param capturePreviews whether workers should attach rendered previews to their results
	 * @param broadcastEvents whether run lifecycle, node stats and breakpoint frames go to the UI
	 *                        socket. Off for a probe, which finishes inside a single request and has
	 *                        no run row for a subscriber to look at
	 * @param trackRun        whether completion closes a {@code pipeline_run} row. Off for a probe,
	 *                        which has no row - calling the tracker for a uuid that was never
	 *                        persisted logs a miss on every probe
	 * @param breakpoints     node ids to hold execution at; already validated against the graph
	 */
	public record EngineConfig(String label, boolean capturePreviews, boolean broadcastEvents, boolean trackRun,
		Collection<String> breakpoints) {

		/** A run that owns a persisted row and reports to the UI. */
		public static EngineConfig forRun(String label, boolean capturePreviews, Collection<String> breakpoints) {
			return new EngineConfig(label, capturePreviews, true, true, breakpoints == null ? List.of() : breakpoints);
		}

		/** A synchronous probe: no row, no subscribers, no previews. */
		public static EngineConfig forProbe(String label) {
			return new EngineConfig(label, false, false, false, List.of());
		}
	}

	/**
	 * Assemble an engine. The caller still owns {@code start()}, item feeding and registration.
	 *
	 * @param graph    the executable graph
	 * @param runUuid  the run identity; results are routed back by it, so it must be the uuid the
	 *                 engine is registered under
	 * @param userUuid the user the run is attributed to; assets written by the sink are created as
	 *                 them
	 * @param store    where run state is persisted, or {@link RunStateStore#NOOP} for a probe
	 */
	public PipelineRunEngine assemble(PipelineGraph graph, UUID runUuid, UUID userUuid, RunStateStore store,
		EngineConfig config) {
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, runUuid, store);

		// Dispatch-to-result latency, retries and dead-letters are only knowable here: the engine is
		// the one party that sees a task leave and its result come back.
		engine.setMetrics(metrics);
		// Outputs of nodes marked syncToLoom land on the asset, not just in the run record. Without
		// this the hash a pipeline computes is invisible everywhere an asset is actually looked at.
		engine.setAssetSink(new DaoAssetSink(daos.assetDao(), userUuid));

		if (config.trackRun()) {
			engine.onCompletion(summary -> tracker.complete(runUuid, summary.getDurationMs(),
				(int) summary.getMediaCount(), (int) summary.getSuccessCount(),
				(int) summary.getFailureCount(), (int) summary.getSkippedCount()));
		}

		if (config.broadcastEvents()) {
			// The run banner and history refresh off these frames. Loom is the only party that knows a
			// run started or settled - a worker holds no pipeline graph - so if they are not emitted
			// here they are not emitted at all.
			engine.onCompletion(summary -> broadcastRunEvent(PipelineEventType.PIPELINE_COMPLETED, config.label(), runUuid));

			// Aggregated progress: per-node counters on a timer, individual events only for failures.
			// Forwarding every settle would be millions of frames to move a progress bar.
			RunStatsAggregator statsAggregator = new RunStatsAggregator(runUuid, graph.getName(), broadcaster);
			engine.onNodeSettled(statsAggregator);
			statsAggregator.setProgressSupplier(engine::nodeProgressSnapshot);
			long statsTimer = vertx.setPeriodic(STATS_INTERVAL_MS, timerId -> statsAggregator.flush());
			engine.onCompletion(summary -> {
				vertx.cancelTimer(statsTimer);
				// One last push so the final counts are not left a timer-tick stale.
				statsAggregator.flush();
			});

			attachBreakpointBroadcast(engine, config.label(), runUuid);
		}

		// Debug mode is a property of this run, not of the pipeline: the same definition is run both
		// ways, and nothing about the graph changes either way.
		engine.setCapturePreviews(config.capturePreviews());
		// Armed before the caller starts the engine, so the first item cannot slip past a breakpoint
		// that was asked for in the same request that started the run.
		engine.setBreakpoints(config.breakpoints());
		engine.setCircuitBreaker(circuitBreaker);
		engine.setRetryScheduler((delayMs, action) -> vertx.setTimer(Math.max(1, delayMs), t -> action.run()));

		return engine;
	}

	/**
	 * Announce a run-level lifecycle change on the UI events socket.
	 *
	 * <p>
	 * Never allowed to fail the operation that triggered it - a run really did start even if nobody
	 * could be told about it.
	 * </p>
	 */
	public void broadcastRunEvent(PipelineEventType type, String label, UUID runUuid) {
		try {
			broadcaster.broadcast(new PipelineEventMessage()
				.setType(type)
				.setPipelineName(label)
				.setPipelineRunUuid(runUuid.toString())
				.setTimestamp(System.currentTimeMillis()));
		} catch (Exception e) {
			log.error("Failed to broadcast {} for run {}", type, runUuid, e);
		}
	}

	/**
	 * Forward the engine's holds and releases to the UI socket.
	 *
	 * <p>
	 * Sent immediately rather than folded into the {@code NODE_STATS} tick, for the same reason a
	 * failure is: a hold happens because a person asked for it and is worthless a second late.
	 * </p>
	 */
	private void attachBreakpointBroadcast(PipelineRunEngine engine, String label, UUID runUuid) {
		engine.onBreakpoint((itemId, mediaPath, nodeId, elementSeq, held) -> {
			try {
				broadcaster.broadcast(new PipelineEventMessage()
					.setType(held ? PipelineEventType.NODE_BREAKPOINT_HELD : PipelineEventType.NODE_BREAKPOINT_RELEASED)
					.setPipelineName(label)
					.setPipelineRunUuid(runUuid.toString())
					.setNodeId(nodeId)
					.setItemUuid(itemId)
					.setElementSeq(elementSeq)
					.setMediaPath(mediaPath)
					.setTimestamp(System.currentTimeMillis()));
			} catch (Exception e) {
				// The run really did stop even if nobody could be told about it.
				log.error("Failed to broadcast a breakpoint frame for run {}", runUuid, e);
			}
		});
	}

}
