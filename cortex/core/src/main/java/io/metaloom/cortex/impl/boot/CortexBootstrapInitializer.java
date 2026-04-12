package io.metaloom.cortex.impl.boot;

import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

@Singleton
public class CortexBootstrapInitializer {

	public static final Logger log = LoggerFactory.getLogger(CortexBootstrapInitializer.class);

	private final Vertx vertx;
	private HttpServer httpServer;
	private int monitoringPort;

	@Inject
	public CortexBootstrapInitializer(Vertx vertx) {
		this.vertx = vertx;
	}

	public void init() {
		init(8093);
	}

	public void init(int port) {
		this.monitoringPort = port;

		try {
			log.info("Starting monitoring HTTP server on port {}", port);
			Router router = Router.router(vertx);

			router.get("/health").handler(ctx -> {
				ctx.response()
					.putHeader("Content-Type", "application/json")
					.end(new JsonObject().put("status", "up").encode());
			});

			router.get("/ready").handler(ctx -> {
				ctx.response()
					.putHeader("Content-Type", "application/json")
					.end(new JsonObject().put("status", "ready").encode());
			});

			httpServer = vertx.createHttpServer()
				.requestHandler(router);

			httpServer.listen(port).toCompletionStage().toCompletableFuture().get();
			log.info("Monitoring HTTP server listening on port {}", httpServer.actualPort());
		} catch (InterruptedException | ExecutionException e) {
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
			} catch (InterruptedException | ExecutionException e) {
				log.error("Error while stopping monitoring HTTP server", e);
			}
		}
	}

}
