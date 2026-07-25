package io.metaloom.cortex.common.metrics;

import java.util.function.Supplier;

import io.metaloom.cortex.api.node.ResultState;

/**
 * No-op {@link CortexMetrics} for tests and any path without a metrics backend. Every method is a
 * safe no-op so instrumentation calls never need null checks.
 */
public class NoopCortexMetrics implements CortexMetrics {

	public static final NoopCortexMetrics INSTANCE = new NoopCortexMetrics();

	@Override
	public void recordLoomMessage(String type) {
	}

	@Override
	public void recordReconnect() {
	}

	@Override
	public void recordTaskReceived(String type) {
	}

	@Override
	public void recordTaskCompleted(String type, String state) {
	}

	@Override
	public void recordTaskDuration(String type, long durationMs) {
	}

	@Override
	public void recordNodeOperation(String nodeKind, ResultState state, long durationMs) {
	}

	@Override
	public void recordFileMissing() {
	}

	@Override
	public void recordResultsBatchSent(int resultCount) {
	}

	@Override
	public void recordBulkSync(String outcome, int assetCount) {
	}

	@Override
	public void recordSourceItemsEnumerated(long count) {
	}

	@Override
	public void recordSourceAckTimeout() {
	}

	@Override
	public void recordAiCall(String provider, boolean success, long durationMs) {
	}

	@Override
	public void recordAiCacheHit(String provider) {
	}

	@Override
	public void bindGauge(String name, Supplier<Number> supplier) {
	}
}
