package io.metaloom.loom.core.boot;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.mcp.MCPService;
import io.metaloom.loom.rest.RESTService;

@Singleton
public class BootstrapInitializer {

	public static final Logger log = LoggerFactory.getLogger(BootstrapInitializer.class);

	// private final GrpcService grpcService;

	private final RESTService restService;

	private final MCPService mcpService;

	private final AuthenticationService authService;

	private final Flyway flyway;

	private final DatabaseInitializer initializer;

	@Inject
	public BootstrapInitializer(RESTService restService, MCPService mcpService, AuthenticationService authService, Flyway flyway, DatabaseInitializer initializer) {
		this.restService = restService;
		this.mcpService = mcpService;
		this.authService = authService;
		this.flyway = flyway;
		this.initializer = initializer;
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
			log.info("Starting MCP service");
			mcpService.start();
		} catch (Exception e) {
			throw new RuntimeException("Error while starting MCP service", e);
		}
	}

	public RESTService getRestService() {
		return restService;
	}

	public MCPService getMcpService() {
		return mcpService;
	}

	public void deinit() {
		mcpService.stop();
		restService.stop();
	}
}
