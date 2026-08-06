package io.metaloom.loom.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Each catalog helper, against a real registry.
 *
 * <p>
 * The gap this closes is the one §5 of {@code METRICS.md} documents: three helpers shipped that
 * were registered, correct, and called by nothing, so the series never appeared in a scrape and a
 * dashboard built on them read a permanent zero. Asserting the <em>scrape output</em> rather than
 * the registry's internal meter list is deliberate — the scraped name is what a query is written
 * against, and Micrometer rewrites the registered name on the way out.
 * </p>
 */
public class MicrometerLoomMetricsTest {

	private PrometheusMeterRegistry registry;
	private MicrometerLoomMetrics metrics;

	@BeforeEach
	void setUp() {
		registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		metrics = new MicrometerLoomMetrics(registry);
	}

	@Test
	void testDispatchLatencyIsExportedAsASecondsTimerPerKindAndState() {
		metrics.recordNodeTaskLatency("whisper", "completed", 2_500);

		String scrape = registry.scrape();
		// The counterpart of loom_node_tasks_dispatched_total, which says only that work left.
		assertThat(scrape).contains("loom_node_task_latency_seconds_count");
		assertThat(scrape).contains("kind=\"whisper\"");
		assertThat(scrape).contains("state=\"completed\"");
		assertThat(registry.get("loom_node_task_latency").tags("kind", "whisper", "state", "completed")
			.timer().totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(2.5d);
	}

	@Test
	void testDispatchLatencySeparatesCompletionFromFailure() {
		metrics.recordNodeTaskLatency("whisper", "completed", 5_000);
		metrics.recordNodeTaskLatency("whisper", "failed", 10);

		// One kind, two very different distributions. Folded together, a kind that fails in
		// milliseconds would flatter the p99 of the work that actually runs.
		assertThat(registry.get("loom_node_task_latency").tags("kind", "whisper", "state", "completed")
			.timer().count()).isEqualTo(1);
		assertThat(registry.get("loom_node_task_latency").tags("kind", "whisper", "state", "failed")
			.timer().count()).isEqualTo(1);
	}

	@Test
	void testRetriesAndDeadLettersAreSeparateCountersPerKind() {
		metrics.recordNodeTaskRetried("whisper");
		metrics.recordNodeTaskRetried("whisper");
		metrics.recordNodeTaskDeadlettered("whisper");

		String scrape = registry.scrape();
		assertThat(scrape).contains("loom_node_tasks_retried_total");
		assertThat(scrape).contains("loom_node_tasks_deadlettered_total");
		// Retries that never dead-letter are a flaky fleet; dead-letters without retries are a
		// broken one. One counter cannot say which.
		assertThat(registry.get("loom_node_tasks_retried").tag("kind", "whisper").counter().count()).isEqualTo(2d);
		assertThat(registry.get("loom_node_tasks_deadlettered").tag("kind", "whisper").counter().count()).isEqualTo(1d);
	}

	@Test
	void testCircuitBreakerTripsAreCountedPerKind() {
		metrics.recordCircuitBreakerTrip("whisper");

		assertThat(registry.scrape()).contains("loom_node_circuit_breaker_trips_total");
		assertThat(registry.get("loom_node_circuit_breaker_trips").tag("kind", "whisper").counter().count())
			.isEqualTo(1d);
	}

	@Test
	void testAnUntaggedGaugeFollowsItsSupplier() {
		AtomicInteger depth = new AtomicInteger(3);
		metrics.bindGauge("loom_node_tasks_inflight", depth::get);

		assertThat(registry.get("loom_node_tasks_inflight").gauge().value()).isEqualTo(3d);
		depth.set(11);
		// Polled at scrape time, not sampled at bind time - the whole reason a gauge is a gauge.
		assertThat(registry.get("loom_node_tasks_inflight").gauge().value()).isEqualTo(11d);
	}

	@Test
	void testATaggedGaugeCreatesOneSeriesPerValue() {
		metrics.bindGauge("loom_processors_by_state", "state", "online", () -> 4);
		metrics.bindGauge("loom_processors_by_state", "state", "terminating", () -> 2);

		assertThat(registry.get("loom_processors_by_state").tag("state", "online").gauge().value()).isEqualTo(4d);
		assertThat(registry.get("loom_processors_by_state").tag("state", "terminating").gauge().value()).isEqualTo(2d);
		assertThat(registry.scrape()).contains("loom_processors_by_state");
	}

	@Test
	void testRebindingATaggedGaugeDoesNotDuplicateTheSeries() {
		metrics.bindGauge("loom_node_circuit_breaker_state", "kind", "whisper", () -> 0);
		metrics.bindGauge("loom_node_circuit_breaker_state", "kind", "whisper", () -> 2);

		// The breaker binds lazily from a path that runs on every observation, so re-binding has to
		// be free. Micrometer keeps the first registration; a second series for the same kind would
		// make the gauge ambiguous rather than merely redundant.
		assertThat(registry.find("loom_node_circuit_breaker_state").gauges()).hasSize(1);
		assertThat(registry.get("loom_node_circuit_breaker_state").tag("kind", "whisper").gauge().value())
			.isEqualTo(0d);
	}

	@Test
	void testTheSameNameCanCarryDifferentTagValues() {
		metrics.bindGauge("loom_node_circuit_breaker_state", "kind", "whisper", () -> 2);
		metrics.bindGauge("loom_node_circuit_breaker_state", "kind", "sha512", () -> 0);

		assertThat(registry.find("loom_node_circuit_breaker_state").gauges()).hasSize(2);
	}
}
