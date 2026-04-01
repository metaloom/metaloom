package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.LoomVersion;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.HTTPConstants;
import io.metaloom.vertx.openapi.OpenAPIGenerator;
import io.metaloom.vertx.openapi.OpenAPIGenerator.Builder;

@Singleton
public class RESTInfoEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(RESTInfoEndpoint.class);

	@Inject
	public RESTInfoEndpoint(EndpointDependencies deps) {
		super(deps);
	}

	@Override
	public String name() {
		return "info";
	}

	@Override
	public void register() {
		addRoute(basePath(), GET, "Load REST API info", lrc -> {
			// TODO implement handler
			lrc.error("not yet implemented");
		});

		addRoute(basePath() + "/openapi", GET, "Load REST API OpenAPI spec", lrc -> {
			try {
				Builder builder = OpenAPIGenerator.builder();
				builder.title("MetaLoom // Loom REST API");
				builder.baseUrl("https://server.tld");
				builder.version(LoomVersion.VERSION);
				builder.description("The API for our example server");
				builder.apiRouter(apiRouter());
				// TODO check accept header for types
				String yaml = builder.generateYaml();
				lrc.sendText(yaml, HTTPConstants.TEXT_YAML, 200);
			} catch (Exception e) {
				log.error("Error while invoking API spec generator", e);
				throw new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Error while generating spec");
			}
		});
	}

	@Override
	public String basePath() {
		return API_V1_PATH;
	}

}
