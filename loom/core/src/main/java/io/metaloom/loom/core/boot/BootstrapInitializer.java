package io.metaloom.loom.core.boot;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.mcp.MCPService;
import io.metaloom.loom.rest.RESTService;
import io.metaloom.loom.rest.UIService;
import io.metaloom.loom.server.grpc.GrpcService;
import io.vertx.core.http.HttpServer;

@Singleton
public class BootstrapInitializer {

	public static final Logger log = LoggerFactory.getLogger(BootstrapInitializer.class);

	private final GrpcService grpcService;

	private final RESTService restService;

	private final UIService uiService;

	private final MCPService mcpService;

	private final AuthenticationService authService;

	private final Flyway flyway;

	private final DatabaseInitializer initializer;

	private final DemoDatabaseInitializer demoInitializer;

	private final HttpServer httpServer;

	@Inject
	public BootstrapInitializer(GrpcService grpcService, RESTService restService, UIService uiService, MCPService mcpService, AuthenticationService authService,
		Flyway flyway, DatabaseInitializer initializer, DemoDatabaseInitializer demoInitializer, HttpServer httpServer) {
		this.grpcService = grpcService;
		this.restService = restService;
		this.uiService = uiService;
		this.mcpService = mcpService;
		this.authService = authService;
		this.flyway = flyway;
		this.initializer = initializer;
		this.demoInitializer = demoInitializer;
		this.httpServer = httpServer;
	}

	public void init(boolean migrate) throws IOException {
		if (migrate) {
			try {
				log.info("Invoking database migration check");
				flyway.migrate();
			} catch (Exception e) {
				throw new RuntimeException("Error while invoking database migration", e);
			}
		}

		try {
			log.info("Invoking database initializer");
			initializer.init();
		} catch (Exception e) {
			throw new RuntimeException("Error while initializing database", e);
		}

		try {
			log.info("Invoking demo database initializer");
			demoInitializer.init();
		} catch (Exception e) {
			log.warn("Error while populating demo data — continuing startup", e);
		}

		// try {
		// authService.init();
		// } catch (Exception e) {
		// throw new RuntimeException("Error while initializing the authentication service", e);
		// }

		try {
			log.info("Starting REST service");
			restService.start();
		} catch (Exception e) {
			throw new RuntimeException("Error while starting rest service", e);
		}

		try {
			log.info("Starting UI service");
			uiService.start();
		} catch (Exception e) {
			throw new RuntimeException("Error while starting UI service", e);
		}

		try {
			log.info("Starting HTTP server");
			httpServer.listen().toCompletionStage().toCompletableFuture().get();
			log.info("HTTP server listening on port {}", httpServer.actualPort());
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException("Error while starting HTTP server", e);
		}

		try {
			log.info("Starting MCP service");
			mcpService.start();
		} catch (Exception e) {
			throw new RuntimeException("Error while starting MCP service", e);
		}

		try {
			log.info("Starting gRPC service");
			grpcService.start();
		} catch (Exception e) {
			throw new RuntimeException("Error while starting gRPC service", e);
		}
	}

	public RESTService getRestService() {
		return restService;
	}

	public MCPService getMcpService() {
		return mcpService;
	}

	public GrpcService getGrpcService() {
		return grpcService;
	}

	public void deinit() {
		mcpService.stop();
		restService.stop();
		grpcService.stop();
		// Stopping the services unregisters their handlers but leaves the socket bound.
		// A process that starts a server, shuts it down and starts another - which is
		// what a test suite does - then fails to bind with "Address already in use".
		try {
			httpServer.close().toCompletionStage().toCompletableFuture().get();
			log.info("HTTP server closed");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Interrupted while closing the HTTP server");
		} catch (Exception e) {
			log.error("Failed to close the HTTP server", e);
		}
	}
}
