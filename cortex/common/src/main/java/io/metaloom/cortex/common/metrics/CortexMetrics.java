package io.metaloom.cortex.common.metrics;

import java.util.function.Supplier;

import io.metaloom.cortex.api.node.ResultState;

/**
 * Central catalog of all Cortex domain metrics. This is the single place where Cortex meters are
 * described; instrumentation sites depend on this interface and call the typed helpers rather than
 * touching a metrics library directly.
 *
 * <p>
 * The interface lives in {@code cortex-common} (upstream of the nodes, node-runtime and core) so
 * every layer can record metrics, while the Micrometer/Prometheus implementation and its dependency
 * stay isolated in {@code cortex-core} ({@code MicrometerCortexMetrics}). Tests and offline paths can
 * use {@link NoopCortexMetrics}.
 *
 * <p>
 * The scraped Prometheus names (with {@code _total} / {@code _seconds} suffixes) are documented in
 * {@code spec/features/ops/METRICS.md}.
 */
public interface CortexMetrics {

	// ---- Loom control channel ---------------------------------------------------------------

	/** A message of the given type was received from Loom (registered, node_task, error, ...). */
	void recordLoomMessage(String type);

	/** A reconnection attempt to Loom was scheduled. */
	void recordReconnect();

	// ---- Task handling ----------------------------------------------------------------------

	/** A work order of the given type (node|segment|source) was received from Loom. */
	void recordTaskReceived(String type);

	/** A task of the given type settled with the given state (success|failed|skipped). */
	void recordTaskCompleted(String type, String state);

	/** Wall-clock duration of a task of the given type. */
	void recordTaskDuration(String type, long durationMs);

	/**
	 * A task was handed back to Loom unexecuted while draining.
	 *
	 * <p>{@code refused} counts work that arrived after this worker announced its
	 * shutdown; {@code unfinished} counts work still running when the drain deadline
	 * passed. A deployment that shows many of the latter has a drain timeout shorter
	 * than its slowest node.</p>
	 */
	void recordTaskReturned(String reason);

	// ---- Node operations --------------------------------------------------------------------

	/** A single node applied to a single media item settled with the given state. */
	void recordNodeOperation(String nodeKind, ResultState state, long durationMs);

	/** A media file referenced by a task did not exist on disk. */
	void recordFileMissing();

	// ---- Result egress ----------------------------------------------------------------------

	/** A batch of {@code resultCount} node results was flushed toward Loom. */
	void recordResultsBatchSent(int resultCount);

	/** {@code assetCount} assets were bulk-synced with the given outcome (synced|failed|dropped_offline|skipped_no_hash). */
	void recordBulkSync(String outcome, int assetCount);

	/** {@code count} source items were enumerated and streamed back to Loom. */
	void recordSourceItemsEnumerated(long count);

	/** A source item batch acknowledgement timed out. */
	void recordSourceAckTimeout();

	// ---- Upstream AI calls ------------------------------------------------------------------

	/** An upstream AI provider call (llm|smolvlm|whisper|tesseract) completed. */
	void recordAiCall(String provider, boolean success, long durationMs);

	/** A node served a result from its in-heap skip cache instead of calling the AI provider. */
	void recordAiCacheHit(String provider);

	// ---- Gauges -----------------------------------------------------------------------------

	/** Bind a gauge to live state; the supplier is polled at scrape time. Idempotent per name. */
	void bindGauge(String name, Supplier<Number> supplier);
}
