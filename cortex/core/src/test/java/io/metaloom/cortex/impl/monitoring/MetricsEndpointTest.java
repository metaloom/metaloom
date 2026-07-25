package io.metaloom.cortex.impl.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.cli.dagger.CortexBindModule;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;

/**
 * Verifies the Cortex {@code /metrics} scrape endpoint serves the shared registry — both a custom
 * {@code cortex_*} meter and a JVM built-in family — on the monitoring server.
 */
public class MetricsEndpointTest {

	private Vertx vertx;
	private HttpServer server;
	private int port;

	@BeforeEach
	void setUp() throws Exception {
		vertx = Vertx.vertx();
		PrometheusMeterRegistry registry = CortexBindModule.provideMeterRegistry();

		// Exercise the catalog so a custom counter exists in the scrape output.
		MicrometerCortexMetrics metrics = new MicrometerCortexMetrics(registry);
		metrics.recordNodeOperation("hash", ResultState.SUCCESS, 5);
		metrics.recordFileMissing();

		Router router = Router.router(vertx);
		new MetricsEndpoint(registry).register(router);
		server = vertx.createHttpServer().requestHandler(router).listen(0).toCompletionStage().toCompletableFuture().get();
		port = server.actualPort();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (server != null) {
			server.close().toCompletionStage().toCompletableFuture().get();
		}
		if (vertx != null) {
			vertx.close().toCompletionStage().toCompletableFuture().get();
		}
	}

	@Test
	void shouldServePrometheusMetrics() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/metrics")).GET().build(),
			HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
			.contains("cortex_node_operations_total")
			.contains("cortex_files_missing_total")
			.contains("jvm_memory_used_bytes");
	}
}
