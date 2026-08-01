package io.metaloom.loom.monitoring;

import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.common.metrics.LoomMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Micrometer/Prometheus-backed {@link LoomMetrics}. Turns the typed catalog calls into meters on the
 * shared {@link PrometheusMeterRegistry}.
 *
 * <p>
 * Meter names deliberately omit the Prometheus {@code _total} / {@code _seconds} suffixes — the
 * registry appends them at scrape time (a counter named {@code loom_pipeline_runs_started} is
 * exported as {@code loom_pipeline_runs_started_total}).
 */
@Singleton
public class MicrometerLoomMetrics implements LoomMetrics {

	private final PrometheusMeterRegistry registry;

	@Inject
	public MicrometerLoomMetrics(PrometheusMeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void recordRunStarted() {
		registry.counter("loom_pipeline_runs_started").increment();
	}

	@Override
	public void recordRunCompleted(String status, long durationMs) {
		registry.counter("loom_pipeline_runs_completed", "status", status).increment();
		io.micrometer.core.instrument.Timer.builder("loom_pipeline_run_duration").tag("status", status).register(registry)
			.record(java.time.Duration.ofMillis(durationMs));
	}

	@Override
	public void recordRunRejected(String reason) {
		registry.counter("loom_pipeline_runs_rejected", "reason", reason).increment();
	}

	@Override
	public void recordRunRecovered(int count) {
		registry.counter("loom_pipeline_runs_recovered").increment(count);
	}

	@Override
	public void recordNodeTaskDispatched(String kind) {
		registry.counter("loom_node_tasks_dispatched", "kind", kind).increment();
	}

	@Override
	public void recordNodeTaskDispatchFailed(String reason) {
		registry.counter("loom_node_tasks_dispatch_failed", "reason", reason).increment();
	}

	@Override
	public void recordNodeResultReceived(String kind, String state) {
		registry.counter("loom_node_results_received", "kind", kind, "state", state).increment();
	}

	@Override
	public void recordSourceItemsReceived(long count) {
		registry.counter("loom_source_items_received").increment(count);
	}

	@Override
	public void recordAssetNodeResultWritten(String kind, String state) {
		registry.counter("loom_asset_node_results_written", "kind", kind, "state", state).increment();
	}

	@Override
	public void recordLeasesReclaimed(int count) {
		registry.counter("loom_leases_reclaimed").increment(count);
	}

	@Override
	public void recordOrphansDeadlettered(int count) {
		registry.counter("loom_orphans_deadlettered").increment(count);
	}

	@Override
	public void recordTaskReturned(String nodeId) {
		registry.counter("loom_tasks_returned", "node", nodeId == null ? "unknown" : nodeId).increment();
	}

	@Override
	public void recordPipelineEventBroadcast() {
		registry.counter("loom_pipeline_events_broadcast").increment();
	}

	@Override
	public void recordPipelineEventDropped() {
		registry.counter("loom_pipeline_events_dropped").increment();
	}

	@Override
	public void recordProcessorRegistered() {
		registry.counter("loom_processor_registrations").increment();
	}

	@Override
	public void recordProcessorDisconnected() {
		registry.counter("loom_processor_disconnects").increment();
	}

	@Override
	public void recordProcessorHeartbeat() {
		registry.counter("loom_processor_heartbeats").increment();
	}

	@Override
	public void recordAuthFailure(String type) {
		registry.counter("loom_auth_failures", "type", type).increment();
	}

	@Override
	public void bindGauge(String name, Supplier<Number> supplier) {
		Gauge.builder(name, supplier).strongReference(true).register(registry);
	}
}
