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
}
