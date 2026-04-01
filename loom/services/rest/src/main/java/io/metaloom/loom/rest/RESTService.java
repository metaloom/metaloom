package io.metaloom.loom.rest;

import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.common.service.AbstractService;
import io.metaloom.loom.rest.dagger.RESTEndpoints;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.vertx.router.ApiRouter;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.Json;
import io.vertx.ext.web.handler.BodyHandler;

@Singleton
public class RESTService extends AbstractService {

	private static final Logger log = LoggerFactory.getLogger(RESTService.class);

	private HttpServer server;

	private final ApiRouter router;
	private final Set<RESTEndpoint> endpoints;
	private final ServerFailureHandler failureHandler;

	@Inject
	public RESTService(Vertx vertx, LoomOptions options, @Named("restApiRouter") ApiRouter router, @RESTEndpoints Set<RESTEndpoint> endpoints,
		ServerFailureHandler failureHandler) {
		super(vertx, options);
		this.router = router;
		this.endpoints = endpoints;
		this.failureHandler = failureHandler;
	}

	public HttpServer start() throws InterruptedException, ExecutionException {
		int port = options().getServer().getRestPort();
		String host = options().getServer().getBindAddress();

		log.info("Starting REST server on {}:{}", host, port);
		HttpServerOptions httpOptions = new HttpServerOptions();
		httpOptions.setHost(host);
		httpOptions.setPort(port);

		setupRouter();

		this.server = vertx().createHttpServer(httpOptions);
		server.requestHandler(router);
		server.listen().toCompletionStage().toCompletableFuture().get();
		return server;
	}

	public void setupRouter() {
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
