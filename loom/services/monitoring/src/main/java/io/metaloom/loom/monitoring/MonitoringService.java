package io.metaloom.loom.monitoring;

import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.common.service.AbstractService;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.micrometer.PrometheusScrapingHandler;

/**
 * Loom monitoring server. Binds the dedicated monitoring port
 * ({@code server.monitoringPort} / {@code LOOM_SERVER_MON_PORT}, default 8989) and serves the
 * Prometheus scrape endpoint at {@code GET /metrics}. This is intentionally a separate HTTP server
 * from the REST API — metrics must never be exposed on the application-facing REST port.
 *
 * <p>
 * Mirrors {@code MCPService}: it creates and owns its own {@link HttpServer} rather than sharing the
 * REST server. When the REST port is {@code 0} (test mode) the monitoring server also uses an
 * OS-assigned port.
 */
@Singleton
public class MonitoringService extends AbstractService {

	private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

	private final PrometheusMeterRegistry registry;

	private HttpServer server;

	@Inject
	public MonitoringService(Vertx vertx, LoomOptions options, PrometheusMeterRegistry registry) {
		super(vertx, options);
		this.registry = registry;
	}

	/**
	 * Start the monitoring HTTP server on the configured monitoring port. When the REST port is 0
	 * (test mode) an OS-assigned port is used.
	 */
	public HttpServer start() throws InterruptedException, ExecutionException {
		int port = options().getServer().getRestPort() == 0 ? 0 : options().getServer().getMonitoringPort();
		String host = options().getServer().getBindAddress();

		log.info("Starting monitoring server on {}:{}", host, port);

		Router router = Router.router(vertx());
		// Scrape directly from our registry so the route is independent of Vert.x's global backend
		// registry lookup. Emits both the custom loom_* meters and the Vert.x/JVM built-ins.
		router.get("/metrics").handler(PrometheusScrapingHandler.create(registry));

		HttpServerOptions httpOptions = new HttpServerOptions().setHost(host).setPort(port);
		this.server = vertx().createHttpServer(httpOptions);
		server.requestHandler(router);
		server.listen().toCompletionStage().toCompletableFuture().get();
		log.info("Monitoring server listening on {}:{}", host, server.actualPort());
		return server;
	}

	public void stop() {
		if (server != null) {
			try {
				server.close().toCompletionStage().toCompletableFuture().get();
				log.info("Monitoring server closed");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Interrupted while closing the monitoring server");
			} catch (ExecutionException e) {
				log.error("Failed to close the monitoring server", e);
			}
		}
	}

	/**
	 * The bound port; useful in tests when the port is OS-assigned (0).
	 */
	public int actualPort() {
		return server == null ? -1 : server.actualPort();
	}
}
