package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.vertx.core.json.Json;

/**
 * REST endpoint that serves pipeline node descriptors and content types.
 * The UI uses these to dynamically build the node palette, edit forms,
 * and connector validation.
 */
@Singleton
public class NodeDescriptorEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(NodeDescriptorEndpoint.class);

	private final NodeDescriptorRegistry registry;

	@Inject
	public NodeDescriptorEndpoint(NodeDescriptorRegistry registry, EndpointDependencies deps) {
		super(deps);
		this.registry = registry;
	}

	@Override
	public String name() {
		return "node-descriptors";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/pipeline/node-descriptors";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		// List all node descriptors + content types (combined response for the UI)
		addRoute(basePath(), GET, "Load all pipeline node descriptors", lrc -> {
			String json = "{\"nodeDescriptors\":" + Json.encode(registry.getAll())
				+ ",\"contentTypes\":" + Json.encode(ContentTypeRegistry.all()) + "}";
			lrc.sendText(json, "application/json", 200);
		});

		// Load a single node descriptor by kind
		addRoute(basePath() + "/:kind", GET, "Load a pipeline node descriptor by kind", lrc -> {
			String kind = lrc.pathParam("kind");
			var descriptor = registry.get(kind);
			if (descriptor == null) {
				lrc.sendText("{\"message\":\"Node descriptor not found: " + kind + "\"}", "application/json", 404);
				return;
			}
			lrc.sendText(Json.encode(descriptor), "application/json", 200);
		});

		// List all content types
		addRoute(API_V1_PATH + "/pipeline/content-types", GET, "Load all pipeline content types", lrc -> {
			lrc.sendText(Json.encode(ContentTypeRegistry.all()), "application/json", 200);
		});
	}
}
