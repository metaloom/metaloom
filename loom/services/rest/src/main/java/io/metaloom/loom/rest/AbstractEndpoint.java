package io.metaloom.loom.rest;

import static io.metaloom.loom.rest.HTTPConstants.APPLICATION_JSON;
import static io.metaloom.loom.rest.HTTPConstants.APPLICATION_OCTET_STREAM;
import static io.metaloom.loom.rest.HTTPConstants.MULTIPART_FORM_DATA;
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
import io.metaloom.loom.rest.parameter.SearchQueryParameterKey;
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
			route.queryParameter(param.key(), param.description(), param.example());
		}
		return route;
	}

	/**
	 * Register a search route, documenting the search query parameters on it.
	 *
	 * <p>
	 * Separate from {@link #addListRoute} because the two parameter sets are disjoint: list routes take {@code limit}/{@code from}/{@code sort} and page
	 * by keyset seek, search routes take {@code q}/{@code offset}/{@code mode} and page by offset or cursor. Documenting both sets on both kinds of route
	 * would put parameters into the OpenAPI spec that the handler ignores.
	 * </p>
	 */
	public <REQ extends RestRequestModel> ApiRoute addSearchRoute(String path, HttpMethod method, String description, Example responseExample,
		Handler<LoomRoutingContext> handler) {
		ApiRoute route = addRoute(path, method, description, null, responseExample, handler);
		for (SearchQueryParameterKey param : SearchQueryParameterKey.values()) {
			route.queryParameter(param.key(), param.description(), param.example());
		}
		return route;
	}

	public <REQ extends RestRequestModel> ApiRoute addRoute(String path, HttpMethod method, String description, Handler<LoomRoutingContext> handler) {
		return addRoute(path, method, description, null, null, handler);
	}

	/**
	 * Register a {@code multipart/form-data} upload route.
	 *
	 * <p>
	 * The byte-carrying routes used the bare {@link #addRoute} overload, which sets neither {@code consumes} nor {@code produces}. They therefore
	 * appeared in {@code openapi.json} as paths with no request body at all — a generated client could see the endpoint and had no way to call it, and
	 * the API explorer on the website rendered an upload form with nothing to upload.
	 * </p>
	 *
	 * @param path
	 *            route path
	 * @param description
	 *            operation description, which must state the expected form fields since the schema cannot
	 * @param responseExample
	 *            example of the JSON response, may be null
	 * @param handler
	 *            request handler
	 * @return the route
	 */
	public ApiRoute addUploadRoute(String path, String description, Example responseExample, Handler<LoomRoutingContext> handler) {
		ApiRoute route = addRoute(path, HttpMethod.POST, description, null, responseExample, handler);
		route.consumes(MULTIPART_FORM_DATA);
		return route;
	}

	/**
	 * Register a route that answers with raw bytes rather than JSON.
	 *
	 * @param path
	 *            route path
	 * @param description
	 *            operation description
	 * @param handler
	 *            request handler
	 * @return the route
	 */
	public ApiRoute addDownloadRoute(String path, String description, Handler<LoomRoutingContext> handler) {
		ApiRoute route = addRoute(path, HttpMethod.GET, description, null, null, handler);
		route.produces(APPLICATION_OCTET_STREAM);
		return route;
	}

	public <REQ extends RestRequestModel> ApiRoute addRoute(String path, HttpMethod method, String description, Example requestExample,
		Example responseExample, Handler<LoomRoutingContext> handler) {
		// The description has to be set on the route - not on the router - otherwise the
		// OpenAPI generator has nothing to put on the operation and every route in the
		// spec ends up undocumented.
		ApiRoute route = apiRouter()
			.route(path)
			.method(method)
			.description(description);

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

	/**
	 * Wrap an update handler so that it first rejects (400) any request body which does not carry all replaceable fields of the given request model.
	 *
	 * Use this for full replace (PUT) routes. Partial update (PATCH) routes use the bare handler.
	 *
	 * @param requestClass
	 * @param handler
	 * @return
	 */
	public Handler<LoomRoutingContext> replaceHandler(Class<? extends RestRequestModel> requestClass, Handler<LoomRoutingContext> handler) {
		return lrc -> {
			lrc.requireFullBody(requestClass);
			handler.handle(lrc);
		};
	}

	public void secure(String path) {
		apiRouter().getDelegate().route(path).handler(deps.authHandler);
	}

}
