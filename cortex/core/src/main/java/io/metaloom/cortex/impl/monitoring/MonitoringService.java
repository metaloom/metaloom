package io.metaloom.cortex.impl.monitoring;

import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.s3.event.WebhookS3EventSource;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;

@Singleton
public class MonitoringService {

	private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

	private final Vertx vertx;
	private final HealthEndpoint healthEndpoint;
	private final MetricsEndpoint metricsEndpoint;
	private final WebhookS3EventSource s3EventSource;

	private HttpServer httpServer;

	@Inject
	public MonitoringService(Vertx vertx, HealthEndpoint healthEndpoint, MetricsEndpoint metricsEndpoint,
		WebhookS3EventSource s3EventSource) {
		this.vertx = vertx;
		this.healthEndpoint = healthEndpoint;
		this.metricsEndpoint = metricsEndpoint;
		this.s3EventSource = s3EventSource;
	}

	public void init() {
		init(8093);
	}

	public void init(int port) {
		try {
			log.info("Starting monitoring HTTP server on port {}", port);
			Router router = Router.router(vertx);
			healthEndpoint.register(router);
			metricsEndpoint.register(router);
			// Registers nothing unless webhook bucket notifications are enabled and a shared
			// secret is configured, so a default deployment exposes no new surface.
			s3EventSource.register(router);

			httpServer = vertx.createHttpServer().requestHandler(router);
			httpServer.listen(port).toCompletionStage().toCompletableFuture().get();
			log.info("Monitoring HTTP server listening on port {}", httpServer.actualPort());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while starting monitoring HTTP server", e);
		} catch (ExecutionException e) {
			throw new RuntimeException("Error while starting monitoring HTTP server", e);
		}
	}

	public Integer actualMonitoringPort() {
		if (httpServer == null) {
			return null;
		}
		return httpServer.actualPort();
	}

	public void deinit() {
		if (httpServer != null) {
			try {
				log.info("Stopping monitoring HTTP server");
				httpServer.close().toCompletionStage().toCompletableFuture().get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.error("Interrupted while stopping monitoring HTTP server", e);
			} catch (ExecutionException e) {
				log.error("Error while stopping monitoring HTTP server", e);
			}
		}
	}

}
