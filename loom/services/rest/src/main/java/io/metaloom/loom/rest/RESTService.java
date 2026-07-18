package io.metaloom.loom.rest;

import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.common.service.AbstractService;
import io.metaloom.loom.rest.dagger.RESTEndpoints;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;
import io.metaloom.loom.rest.service.impl.LeaseReaper;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.vertx.router.ApiRouter;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;

@Singleton
public class RESTService extends AbstractService {

	private static final Logger log = LoggerFactory.getLogger(RESTService.class);

	private final HttpServer server;

	private final ApiRouter router;
	private final Set<RESTEndpoint> endpoints;
	private final ServerFailureHandler failureHandler;
	private final LeaseReaper leaseReaper;

	@Inject
	public RESTService(Vertx vertx, LoomOptions options, HttpServer server, @Named("restApiRouter") ApiRouter router,
		@RESTEndpoints Set<RESTEndpoint> endpoints, ServerFailureHandler failureHandler, LeaseReaper leaseReaper) {
		super(vertx, options);
		this.server = server;
		this.router = router;
		this.endpoints = endpoints;
		this.failureHandler = failureHandler;
		this.leaseReaper = leaseReaper;
	}

	public void start() {
		log.info("Setting up REST router");

		setupRouter();

		server.requestHandler(router);

		// Tied to the REST service because that is what owns the run registry the
		// reaper hands reclaimed tasks back to. Without this running, a worker that
		// dies mid-task stalls its run permanently.
		leaseReaper.start();
	}

	public void setupRouter() {
		router.getDelegate().route().handler(CorsHandler.create()
			.addOriginWithRegex(".*")
			.allowedMethod(HttpMethod.GET)
			.allowedMethod(HttpMethod.POST)
			.allowedMethod(HttpMethod.PUT)
			.allowedMethod(HttpMethod.DELETE)
			.allowedMethod(HttpMethod.PATCH)
			.allowedMethod(HttpMethod.OPTIONS)
			.allowedHeader("Content-Type")
			.allowedHeader("Authorization")
			.allowedHeader("Accept")
			.allowCredentials(true));
		router.getDelegate().route().handler(BodyHandler.create().setBodyLimit(-1));

		// Register all injected endpoint routes
		for (RESTEndpoint endpoint : endpoints) {
			endpoint.register();
		}
		router.getDelegate().errorHandler(404, rc -> {
			log.error("Request failed {}", rc.normalizedPath(), rc.failure());
			GenericMessageResponse error = new GenericMessageResponse();
			error.setMessage("Path not Found: " + rc.normalizedPath());
			rc.response().setStatusCode(404).end(Json.encodeToBuffer(error));
		});
		router.getDelegate().route().failureHandler(failureHandler);

	}

	public void stop() {
		if (server != null) {
			log.info("Shuting down REST server");
			server.close();
		}
	}

	public HttpServer getServer() {
		return server;
	}
}
