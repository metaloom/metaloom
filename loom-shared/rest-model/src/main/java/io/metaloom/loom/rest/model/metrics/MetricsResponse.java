package io.metaloom.loom.rest.model.metrics;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * An instantaneous read of the Loom metric catalog ({@code loom_*}), served over the app REST port.
 *
 * <p>
 * This is <b>not</b> a second Prometheus scrape endpoint. {@code /metrics} on the monitoring port
 * (8989) stays the scrape surface and stays unauthenticated; this route is authenticated, permission
 * gated, and returns JSON a browser can consume without a Prometheus in between — which is what the
 * monitoring screen needs, since the UI has no access to the internal monitoring port.
 * </p>
 *
 * <p>
 * There is no history here, and deliberately no attempt to fake one: Loom holds no time-series
 * store. A caller wanting a trend samples this endpoint and differences the counters itself.
 * </p>
 */
public class MetricsResponse implements RestResponseModel<MetricsResponse> {

	@JsonPropertyDescription("Server time the snapshot was taken (ISO 8601 instant). Counter deltas between two calls are measured against this.")
	private String timestamp;

	@JsonPropertyDescription("One entry per name+tag series, sorted by name then tags.")
	private List<MetricRecord> metrics = new ArrayList<>();

	public MetricsResponse() {
	}

	public String getTimestamp() {
		return timestamp;
	}

	public MetricsResponse setTimestamp(String timestamp) {
		this.timestamp = timestamp;
		return this;
	}

	public List<MetricRecord> getMetrics() {
		return metrics;
	}

	public MetricsResponse setMetrics(List<MetricRecord> metrics) {
		this.metrics = metrics;
		return this;
	}

	public MetricsResponse add(MetricRecord record) {
		this.metrics.add(record);
		return this;
	}

	@Override
	public MetricsResponse self() {
		return this;
	}

}
