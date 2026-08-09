package io.metaloom.loom.rest.model.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

/**
 * One series of the Loom metric catalog, as a single instantaneous sample.
 *
 * <p>
 * The {@code name} is the <b>scraped</b> Prometheus name — the one documented in the §3 tables of
 * {@code spec/features/ops/METRICS.md}, suffixes included ({@code _total} for counters,
 * {@code _seconds} for timers). Micrometer's meter ids carry no suffix; the projection applies the
 * same convention the Prometheus exposition does, so a dashboard reading this endpoint and a
 * dashboard reading {@code /metrics} on the monitoring port name the same thing identically.
 * </p>
 *
 * <p>
 * A series is identified by {@code name} <em>and</em> {@code tags} together: one row per tag
 * combination, exactly as in a scrape. This carries no history — Loom keeps no time-series store, so
 * a caller that wants a trend samples this endpoint over time.
 * </p>
 */
public class MetricRecord implements RestModel {

	@JsonPropertyDescription("Scraped Prometheus name of the series, e.g. loom_node_tasks_dispatched_total.")
	private String name;

	@JsonPropertyDescription("Meter type: COUNTER, GAUGE or TIMER.")
	private String type;

	@JsonPropertyDescription("Label set of this series, e.g. {\"kind\":\"sha512\",\"state\":\"completed\"}. Empty when the meter is untagged.")
	private Map<String, String> tags = new LinkedHashMap<>();

	@JsonPropertyDescription("Counter total or gauge reading. Null for a timer, which reports count/sum/max/mean instead.")
	private Double value;

	@JsonPropertyDescription("Timer: number of recorded events. Null for counters and gauges.")
	private Long count;

	@JsonPropertyDescription("Timer: total recorded time in seconds. Null for counters and gauges.")
	private Double sumSeconds;

	@JsonPropertyDescription("Timer: longest recorded event in seconds. Null for counters and gauges.")
	private Double maxSeconds;

	@JsonPropertyDescription("Timer: mean event duration in seconds (sum/count, 0 when count is 0). Null for counters and gauges.")
	private Double meanSeconds;

	public MetricRecord() {
	}

	public String getName() {
		return name;
	}

	public MetricRecord setName(String name) {
		this.name = name;
		return this;
	}

	public String getType() {
		return type;
	}

	public MetricRecord setType(String type) {
		this.type = type;
		return this;
	}

	public Map<String, String> getTags() {
		return tags;
	}

	public MetricRecord setTags(Map<String, String> tags) {
		this.tags = tags;
		return this;
	}

	public Double getValue() {
		return value;
	}

	public MetricRecord setValue(Double value) {
		this.value = value;
		return this;
	}

	public Long getCount() {
		return count;
	}

	public MetricRecord setCount(Long count) {
		this.count = count;
		return this;
	}

	public Double getSumSeconds() {
		return sumSeconds;
	}

	public MetricRecord setSumSeconds(Double sumSeconds) {
		this.sumSeconds = sumSeconds;
		return this;
	}

	public Double getMaxSeconds() {
		return maxSeconds;
	}

	public MetricRecord setMaxSeconds(Double maxSeconds) {
		this.maxSeconds = maxSeconds;
		return this;
	}

	public Double getMeanSeconds() {
		return meanSeconds;
	}

	public MetricRecord setMeanSeconds(Double meanSeconds) {
		this.meanSeconds = meanSeconds;
		return this;
	}

}
