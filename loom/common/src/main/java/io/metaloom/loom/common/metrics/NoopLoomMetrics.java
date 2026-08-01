package io.metaloom.loom.common.metrics;

import java.util.function.Supplier;

/**
 * No-op {@link LoomMetrics} for tests and any path without a metrics backend. Every method is a safe
 * no-op so instrumentation calls never need null checks.
 */
public class NoopLoomMetrics implements LoomMetrics {

	public static final NoopLoomMetrics INSTANCE = new NoopLoomMetrics();

	@Override
	public void recordRunStarted() {
	}

	@Override
	public void recordRunCompleted(String status, long durationMs) {
	}

	@Override
	public void recordRunRejected(String reason) {
	}

	@Override
	public void recordRunRecovered(int count) {
	}

	@Override
	public void recordNodeTaskDispatched(String kind) {
	}

	@Override
	public void recordNodeTaskDispatchFailed(String reason) {
	}

	@Override
	public void recordNodeResultReceived(String kind, String state) {
	}

	@Override
	public void recordSourceItemsReceived(long count) {
	}

	@Override
	public void recordAssetNodeResultWritten(String kind, String state) {
	}

	@Override
	public void recordLeasesReclaimed(int count) {
	}

	@Override
	public void recordOrphansDeadlettered(int count) {
	}

	@Override
	public void recordTaskReturned(String nodeId) {
	}

	@Override
	public void recordPipelineEventBroadcast() {
	}

	@Override
	public void recordPipelineEventDropped() {
	}

	@Override
	public void recordProcessorRegistered() {
	}

	@Override
	public void recordProcessorDisconnected() {
	}

	@Override
	public void recordProcessorHeartbeat() {
	}

	@Override
	public void recordAuthFailure(String type) {
	}

	@Override
	public void bindGauge(String name, Supplier<Number> supplier) {
	}
}
