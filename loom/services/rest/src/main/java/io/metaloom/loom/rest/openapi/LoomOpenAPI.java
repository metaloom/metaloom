package io.metaloom.loom.rest.openapi;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.metaloom.loom.api.LoomVersion;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.RESTService;
import io.metaloom.loom.rest.ServerFailureHandler;
import io.metaloom.loom.rest.endpoint.RESTEndpoint;
import io.metaloom.loom.rest.endpoint.impl.AssetEndpoint;
import io.metaloom.loom.rest.endpoint.impl.GroupEndpoint;
import io.metaloom.loom.rest.endpoint.impl.MeEndpoint;
import io.metaloom.loom.rest.endpoint.impl.UserEndpoint;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.vertx.openapi.OpenAPIGenerator;
import io.metaloom.vertx.openapi.OpenAPIGenerator.Builder;
import io.metaloom.vertx.router.ApiRouter;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

public class LoomOpenAPI {

	public String generateJson() throws JsonProcessingException {
		Vertx vertx = Vertx.vertx();
		HttpServer server = vertx.createHttpServer();
		LoomOptions options = new LoomOptions();
		ApiRouter router = ApiRouter.create(vertx);
		Set<RESTEndpoint> endpoints = new HashSet<>();
		EndpointDependencies deps = new EndpointDependencies(vertx, router, null, null);
		ModelExamples examples = new ModelExamples();
		endpoints.add(new UserEndpoint(null, deps, examples));
		endpoints.add(new GroupEndpoint(null, deps, examples));
		endpoints.add(new AssetEndpoint(null, null, null, null, null, null, null, null, null, null, null, null, null, deps, examples));
		endpoints.add(new MeEndpoint(null, deps, examples));
		ServerFailureHandler failureHandler = null;
		// Only the router is needed to describe the API; start() - which is what would
		// use the reaper - is never called here.
		RESTService rest = new RESTService(vertx, options, server, router, endpoints, failureHandler, null, null);
		rest.setupRouter();

		Builder builder = OpenAPIGenerator.builder();
		builder.title("MetaLoom // Loom REST API");
		builder.baseUrl("https://server.tld");
		builder.version(LoomVersion.VERSION);
		builder.description("The API for our example server");
		builder.apiRouter(router);

		return builder.generateJson();
	}
}
