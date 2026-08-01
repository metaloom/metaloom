package io.metaloom.cortex.impl.monitoring;

import java.time.Duration;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.common.metrics.CortexMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Micrometer/Prometheus-backed {@link CortexMetrics}. Owns the process-wide
 * {@link PrometheusMeterRegistry} and turns the typed catalog calls into meters.
 *
 * <p>
 * Meter names deliberately omit the Prometheus {@code _total} / {@code _seconds} suffixes — the
 * registry appends them at scrape time (a counter named {@code cortex_node_operations} is exported as
 * {@code cortex_node_operations_total}).
 */
@Singleton
public class MicrometerCortexMetrics implements CortexMetrics {

	private final PrometheusMeterRegistry registry;

	@Inject
	public MicrometerCortexMetrics(PrometheusMeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void recordLoomMessage(String type) {
		registry.counter("cortex_loom_messages_received", "type", type).increment();
	}

	@Override
	public void recordReconnect() {
		registry.counter("cortex_loom_reconnects").increment();
	}

	@Override
	public void recordTaskReceived(String type) {
		registry.counter("cortex_tasks_received", "type", type).increment();
	}

	@Override
	public void recordTaskReturned(String reason) {
		registry.counter("cortex_tasks_returned", "reason", reason).increment();
	}

	@Override
	public void recordTaskCompleted(String type, String state) {
		registry.counter("cortex_tasks_completed", "type", type, "state", state).increment();
	}

	@Override
	public void recordTaskDuration(String type, long durationMs) {
		Timer.builder("cortex_task_duration").tag("type", type).register(registry).record(Duration.ofMillis(durationMs));
	}

	@Override
	public void recordNodeOperation(String nodeKind, ResultState state, long durationMs) {
		registry.counter("cortex_node_operations", "node_kind", nodeKind, "state", state.name().toLowerCase()).increment();
		Timer.builder("cortex_node_operation_duration").tag("node_kind", nodeKind).register(registry).record(Duration.ofMillis(durationMs));
	}

	@Override
	public void recordFileMissing() {
		registry.counter("cortex_files_missing").increment();
	}

	@Override
	public void recordResultsBatchSent(int resultCount) {
		registry.counter("cortex_results_batches_sent").increment();
		registry.counter("cortex_results_sent").increment(resultCount);
	}

	@Override
	public void recordBulkSync(String outcome, int assetCount) {
		registry.counter("cortex_bulk_sync_assets", "outcome", outcome).increment(assetCount);
	}

	@Override
	public void recordSourceItemsEnumerated(long count) {
		registry.counter("cortex_source_items_enumerated").increment(count);
	}

	@Override
	public void recordSourceAckTimeout() {
		registry.counter("cortex_source_ack_timeouts").increment();
	}

	@Override
	public void recordAiCall(String provider, boolean success, long durationMs) {
		registry.counter("cortex_ai_calls", "provider", provider, "outcome", success ? "success" : "failure").increment();
		Timer.builder("cortex_ai_call_duration").tag("provider", provider).register(registry).record(Duration.ofMillis(durationMs));
	}

	@Override
	public void recordAiCacheHit(String provider) {
		registry.counter("cortex_ai_cache_hits", "provider", provider).increment();
	}

	@Override
	public void bindGauge(String name, Supplier<Number> supplier) {
		Gauge.builder(name, supplier).strongReference(true).register(registry);
	}
}
