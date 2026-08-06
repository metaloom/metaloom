package io.metaloom.loom.common.metrics;

import java.util.function.Supplier;

/**
 * Central catalog of all Loom domain metrics. This is the single place where Loom meters are
 * described; instrumentation sites depend on this interface and call the typed helpers rather than
 * touching a metrics library directly.
 *
 * <p>
 * The interface lives in {@code loom-common} (upstream of the service layer) so any service can
 * record metrics, while the Micrometer/Prometheus implementation and its dependency stay isolated in
 * {@code loom-service-monitoring} ({@code MicrometerLoomMetrics}). Tests can use
 * {@link NoopLoomMetrics}.
 *
 * <p>
 * The scraped Prometheus names (with {@code _total} / {@code _seconds} suffixes) are documented in
 * {@code spec/features/ops/METRICS.md}.
 */
public interface LoomMetrics {

	// ---- Pipeline runs ----------------------------------------------------------------------

	/** A pipeline run was dispatched. */
	void recordRunStarted();

	/** A pipeline run reached a terminal state (success|partial|failed|cancelled) with a duration. */
	void recordRunCompleted(String status, long durationMs);

	/** A pipeline run request was rejected before starting (no_processor|invalid_graph|...). */
	void recordRunRejected(String reason);

	/** {@code count} interrupted runs were resumed after a restart. */
	void recordRunRecovered(int count);

	// ---- Node task dispatch -----------------------------------------------------------------

	/** A node task of the given kind was dispatched to a worker. */
	void recordNodeTaskDispatched(String kind);

	/** A node task could not be dispatched (no_processor|socket_gone). */
	void recordNodeTaskDispatchFailed(String reason);

	// ---- Node task lifecycle (pipeline engine) ----------------------------------------------

	/**
	 * A dispatched node task came back and settled, after {@code durationMs} on the wire and on the
	 * worker.
	 *
	 * <p>The counterpart of {@code recordNodeTaskDispatched}, which says only that work left. This is
	 * the one signal that separates "the fleet is busy" from "the fleet is stuck": dispatch counts
	 * keep rising in both cases, and only the latency shows which.</p>
	 *
	 * <p>Recorded for executions that were actually placed on a worker. A dispatch no worker would
	 * take never starts a clock — {@code loom_node_tasks_dispatch_failed_total} already counts those,
	 * and folding their near-zero settle time into the timer would flatter the distribution exactly
	 * when the fleet is at its worst.</p>
	 *
	 * @param kind       the node kind
	 * @param state      how it settled (completed|failed|skipped)
	 * @param durationMs wall time from dispatch to result
	 */
	void recordNodeTaskLatency(String kind, String state, long durationMs);

	/** A node task of the given kind was handed back for another attempt. */
	void recordNodeTaskRetried(String kind);

	/** A node task of the given kind exhausted its attempts and was dead-lettered. */
	void recordNodeTaskDeadlettered(String kind);

	/** A node kind's circuit breaker opened, parking dispatch of that kind. */
	void recordCircuitBreakerTrip(String kind);

	// ---- Results ingestion ------------------------------------------------------------------

	/** A node result of the given kind settled with the given state (success|failed|skipped). */
	void recordNodeResultReceived(String kind, String state);

	/** {@code count} source items were received from a worker. */
	void recordSourceItemsReceived(long count);

	/** A row was written to the asset_node_result ledger for the given kind and state. */
	void recordAssetNodeResultWritten(String kind, String state);

	// ---- Watchdog / recovery ----------------------------------------------------------------

	/** {@code count} lapsed task leases were reclaimed by the reaper. */
	void recordLeasesReclaimed(int count);

	/** {@code count} orphaned tasks of dead runs were dead-lettered. */
	void recordOrphansDeadlettered(int count);

	/**
	 * A draining worker handed a task back unexecuted and it was re-placed.
	 *
	 * <p>Read against {@code loom_leases_reclaimed}: the same recovery, but paid for at
	 * announcement rather than after a lease interval. A fleet that scales down often
	 * and shows reclaims instead of returns has workers dying rather than draining.</p>
	 */
	void recordTaskReturned(String nodeId);

	// ---- WebSocket fan-out ------------------------------------------------------------------

	/** A pipeline event was broadcast to UI subscribers. */
	void recordPipelineEventBroadcast();

	/** A pipeline event was dropped for a subscriber due to backpressure. */
	void recordPipelineEventDropped();

	// ---- Workers ----------------------------------------------------------------------------

	/** A Cortex worker registered. */
	void recordProcessorRegistered();

	/** A Cortex worker disconnected. */
	void recordProcessorDisconnected();

	/** A worker heartbeat was received. */
	void recordProcessorHeartbeat();

	// ---- Auth -------------------------------------------------------------------------------

	/** An authentication attempt failed (jwt|ws|permission). */
	void recordAuthFailure(String type);

	// ---- Gauges -----------------------------------------------------------------------------

	/** Bind a gauge to live state; the supplier is polled at scrape time. Idempotent per name. */
	void bindGauge(String name, Supplier<Number> supplier);

	/**
	 * Bind one series of a labelled gauge family. Idempotent per {@code (name, tag)} pair.
	 *
	 * <p>The label is what makes a fleet gauge readable: {@code loom_processors_connected} says how
	 * many workers are attached, but not that eleven of them are TERMINATING. Bind one series per
	 * value of a <strong>bounded</strong> set — an enum, a node kind — never per run, asset or user.</p>
	 *
	 * @param name     meter name, shared by every series of the family
	 * @param tagKey   label name
	 * @param tagValue label value; one series is created per distinct value
	 * @param supplier polled at scrape time
	 */
	void bindGauge(String name, String tagKey, String tagValue, Supplier<Number> supplier);
}
