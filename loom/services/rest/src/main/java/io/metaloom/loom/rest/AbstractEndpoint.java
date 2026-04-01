package io.metaloom.loom.rest;

import static io.metaloom.loom.rest.HTTPConstants.APPLICATION_JSON;
import static io.metaloom.vertx.route.request.impl.RequestImpl.request;
import static io.metaloom.vertx.route.response.impl.ResponseImpl.response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.dagger.RestComponent;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;
import io.metaloom.loom.rest.json.LoomJson;
import io.metaloom.loom.rest.model.RestModel;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.parameter.QueryParameterKey;
import io.metaloom.vertx.route.ApiRoute;
import io.metaloom.vertx.router.ApiRouter;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;

public abstract class AbstractEndpoint implements RESTEndpoint {

	private static final Logger log = LoggerFactory.getLogger(AbstractEndpoint.class);

	private EndpointDependencies deps;

	public AbstractEndpoint(EndpointDependencies deps) {
		this.deps = deps;
	}

	public ApiRouter apiRouter() {
		return deps.router;
	}

	public Vertx vertx() {
		return deps.vertx;
	}

	public <REQ extends RestRequestModel> ApiRoute addListRoute(String path, HttpMethod method, String description, Example responseExample,
		Handler<LoomRoutingContext> handler) {
		ApiRoute route = addRoute(path, method, description, null, responseExample, handler);
		for (QueryParameterKey param : QueryParameterKey.values()) {
			route.queryParameter(param.key(), param.example(), param.description());
		}
		return route;
	}

	public <REQ extends RestRequestModel> ApiRoute addRoute(String path, HttpMethod method, String description, Handler<LoomRoutingContext> handler) {
		return addRoute(path, method, description, null, null, handler);
	}

	public <REQ extends RestRequestModel> ApiRoute addRoute(String path, HttpMethod method, String description, Example requestExample,
		Example responseExample, Handler<LoomRoutingContext> handler) {
		ApiRoute route = apiRouter()
			.description(description)
			.route(path)
			.method(method);

		if (requestExample != null) {
			route.consumes(APPLICATION_JSON);
			route.exampleRequest(APPLICATION_JSON, request()
				.body(LoomJson.encode(requestExample.body()))
				.description(requestExample.description()));
		}

		if (responseExample != null) {
			if (responseExample.body() != null) {
				RestModel body = responseExample.body();
				route.produces(APPLICATION_JSON);
				try {
					String json = LoomJson.encode(body);
					route.exampleResponse(responseExample.code(),
						response(APPLICATION_JSON)
							.body(json)
							.description(responseExample.description()));
				} catch (Exception e) {
					log.error("Failed to construct response example for endpoint {} {} of type {}", path, method,
						body != null ? body.getClass().getName() : "null");
					log.error("Failed to setup example", e);
				}
			} else {
				// NO CONTENT Response
				route.exampleResponse(responseExample.code(),
					response().description(responseExample.description()));
			}
		}

		return route.handler(rc -> {
			// Construct a new subtree so we can use DI in the scope of a request
			RestComponent requestComponent = deps.restComponentProvider.get()
				.context(rc)
				.build();
			LoomRoutingContext loomContext = requestComponent.requestHandler();
			handler.handle(loomContext);
		});
	}

	public void secure(String path) {
		apiRouter().getDelegate().route(path).handler(deps.authHandler);
	}

}
