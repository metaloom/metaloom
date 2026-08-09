package io.metaloom.loom.client.common.method;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.metrics.MetricsResponse;

public interface MetricsMethods {

	/**
	 * Load a snapshot of the {@code loom_*} metric catalog.
	 *
	 * @return the snapshot request
	 */
	LoomClientRequest<MetricsResponse> loadMetrics();

	/**
	 * Load the catalog series whose name starts with the given prefix.
	 *
	 * @param prefix a name prefix inside the {@code loom_} namespace
	 * @return the snapshot request
	 */
	LoomClientRequest<MetricsResponse> loadMetrics(String prefix);
}
