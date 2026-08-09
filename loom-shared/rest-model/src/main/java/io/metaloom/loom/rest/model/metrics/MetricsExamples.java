package io.metaloom.loom.rest.model.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * OpenAPI examples for the metrics route.
 *
 * <p>
 * The names here are real catalog names from {@code spec/features/ops/METRICS.md} §3 — an example
 * carrying an invented series would be the same documentation fiction that section exists to
 * prevent.
 * </p>
 */
public interface MetricsExamples extends ExampleValues {

	default Example metricsResponseExample() {
		return new ExampleImpl(metricsResponse(), "The metrics snapshot response", HttpResponseStatus.OK);
	}

	default MetricsResponse metricsResponse() {
		return new MetricsResponse()
			.setTimestamp("2026-08-09T11:24:07Z")
			.add(new MetricRecord()
				.setName("loom_node_task_latency_seconds")
				.setType("TIMER")
				.setTags(tags("kind", "sha512", "state", "completed"))
				.setCount(1_284L)
				.setSumSeconds(412.6d)
				.setMaxSeconds(3.91d)
				.setMeanSeconds(0.321d))
			.add(new MetricRecord()
				.setName("loom_node_tasks_dispatched_total")
				.setType("COUNTER")
				.setTags(tags("kind", "sha512"))
				.setValue(1_290d))
			.add(new MetricRecord()
				.setName("loom_node_tasks_inflight")
				.setType("GAUGE")
				.setValue(6d))
			.add(new MetricRecord()
				.setName("loom_node_tasks_inflight_ceiling")
				.setType("GAUGE")
				.setValue(16d))
			.add(new MetricRecord()
				.setName("loom_pipeline_runs_active")
				.setType("GAUGE")
				.setValue(2d))
			.add(new MetricRecord()
				.setName("loom_processors_by_state")
				.setType("GAUGE")
				.setTags(tags("state", "online"))
				.setValue(3d));
	}

	private static Map<String, String> tags(String... keyValues) {
		Map<String, String> tags = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			tags.put(keyValues[i], keyValues[i + 1]);
		}
		return tags;
	}
}
