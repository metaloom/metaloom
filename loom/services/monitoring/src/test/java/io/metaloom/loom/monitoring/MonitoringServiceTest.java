package io.metaloom.loom.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.LoomOptions;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Vertx;

/**
 * Verifies the Loom monitoring server binds an (ephemeral) port and serves the Prometheus scrape
 * endpoint with both a custom {@code loom_*} meter and a JVM built-in family.
 */
public class MonitoringServiceTest {

	private Vertx vertx;
	private MonitoringService service;

	@BeforeEach
	void setUp() throws Exception {
		vertx = Vertx.vertx();
		PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		new io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics().bindTo(registry);

		// Exercise the catalog so a custom counter exists in the scrape output.
		MicrometerLoomMetrics metrics = new MicrometerLoomMetrics(registry);
		metrics.recordRunCompleted("success", 1234);

		// restPort 0 puts the monitoring server on an OS-assigned port (test mode).
		LoomOptions options = new LoomOptions();
		options.getServer().setRestPort(0);

		service = new MonitoringService(vertx, options, registry);
		service.start();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (service != null) {
			service.stop();
		}
		if (vertx != null) {
			vertx.close().toCompletionStage().toCompletableFuture().get();
		}
	}

	@Test
	void shouldServePrometheusMetrics() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + service.actualPort() + "/metrics")).GET().build(),
			HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
			.contains("loom_pipeline_runs_completed_total")
			.contains("jvm_memory_used_bytes");
	}

	@Test
	void shouldNotServeRestPaths() throws Exception {
		// The monitoring server only exposes /metrics — a REST-style path must 404, proving metrics
		// are isolated from the application surface.
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + service.actualPort() + "/api/v1/health")).GET().build(),
			HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(404);
	}
}
