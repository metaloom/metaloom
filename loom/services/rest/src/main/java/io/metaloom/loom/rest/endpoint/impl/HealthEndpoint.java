package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.LoomVersion;
import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.health.HealthCheckResponse;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

@Singleton
public class HealthEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(HealthEndpoint.class);

	private final DSLContext dslContext;
	private final Vertx vertx;

	@Inject
	public HealthEndpoint(EndpointDependencies deps, DSLContext dslContext) {
		super(deps);
		this.dslContext = dslContext;
		this.vertx = deps.vertx;
	}

	@Override
	public String name() {
		return "health";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/health";
	}

	@Override
	public void register() {
		log.info("Registering health check endpoint");
		addRoute(basePath(), GET, "Health check endpoint", lrc -> {
			HealthCheckResponse response = new HealthCheckResponse();
			response.setStatus("UP");
			response.setVersion(LoomVersion.VERSION);
			response.setTimestamp(java.time.Instant.now().toString());

			// Check database connectivity
			checkDatabase().onComplete(ar -> {
				if (ar.succeeded()) {
					response.setDatabase("UP");
				} else {
					response.setDatabase("DOWN");
					response.setStatus("DEGRADED");
					log.warn("Database health check failed", ar.cause());
				}
				lrc.send(response);
			});
		});
	}

	private Future<Void> checkDatabase() {
		return vertx.executeBlocking(() -> {
			// Simple query to verify database connectivity using jOOQ
			dslContext.fetch("SELECT 1");
			return null;
		});
	}
}