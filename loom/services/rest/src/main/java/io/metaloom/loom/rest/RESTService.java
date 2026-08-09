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
import io.metaloom.loom.rest.service.impl.NodeRegistrationService;
import io.metaloom.loom.rest.service.impl.PipelineRunRecovery;
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
	private final PipelineRunRecovery runRecovery;
	private final NodeRegistrationService nodeRegistrations;

	@Inject
	public RESTService(Vertx vertx, LoomOptions options, HttpServer server, @Named("restApiRouter") ApiRouter router,
		@RESTEndpoints Set<RESTEndpoint> endpoints, ServerFailureHandler failureHandler, LeaseReaper leaseReaper,
		PipelineRunRecovery runRecovery, NodeRegistrationService nodeRegistrations) {
		super(vertx, options);
		this.server = server;
		this.router = router;
		this.endpoints = endpoints;
		this.failureHandler = failureHandler;
		this.leaseReaper = leaseReaper;
		this.runRecovery = runRecovery;
		this.nodeRegistrations = nodeRegistrations;
	}

	public void start() {
		log.info("Setting up REST router");

		// Before the router, and well before recovery: a saved pipeline using an announced-only node
		// has to parse on a freshly booted Loom with no worker connected. Restoring these after
		// anything validates a graph would make a restart during an outage turn every such pipeline
		// unopenable, and recovery would then dead-letter runs it could have resumed.
		nodeRegistrations.rehydrate();

		setupRouter();

		server.requestHandler(router);

		// Tied to the REST service because that is what owns the run registry the
		// reaper hands reclaimed tasks back to. Without this running, a worker that
		// dies mid-task stalls its run permanently.
		leaseReaper.start();

		// Before the reaper starts sweeping: runs left mid-flight by a restart need
		// their engines back, or the reaper would find their tasks with no engine to
		// hand them to and dead-letter perfectly recoverable work.
		int recovered = runRecovery.recoverAll();
		if (recovered > 0) {
			log.info("Resumed {} pipeline run(s) after restart", recovered);
		}
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

	/**
	 * The restart recovery pass.
	 *
	 * <p>
	 * Exposed so a test can drive recovery deliberately rather than by restarting the whole service:
	 * what a run does after a restart - whether it resumes or is written off - is only observable by
	 * running this against rows that were left mid-flight.
	 * </p>
	 */
	public PipelineRunRecovery getRunRecovery() {
		return runRecovery;
	}

	public HttpServer getServer() {
		return server;
	}
}
