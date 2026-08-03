package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.AssetComponentEndpointService;

public class AssetComponentEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(AssetComponentEndpoint.class);

	private final AssetComponentEndpointService service;
	private final ModelExamples examples;

	@Inject
	public AssetComponentEndpoint(AssetComponentEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "assetComponent";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/assets/:assetUuid/components";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath());
		secure(basePath() + "/:compUuid");

		// List all components for an asset
		addRoute(basePath(), GET,
			"List all components for an asset",
			null,
			examples.assetComponentListResponseExample(),
			lrc -> {
				service.list(lrc, lrc.pathParamUUID("assetUuid"));
			});

		// Create or replace a component. This is an upsert on the component's identity
		// (asset, node kind, discriminators), so a node re-run replaces its own row.
		addRoute(basePath(), POST,
			"Create or replace a component for an asset (upsert on asset, node kind and discriminators)",
			examples.assetComponentCreateRequestExample(),
			examples.assetComponentResponseExample(),
			lrc -> {
				service.create(lrc, lrc.pathParamUUID("assetUuid"));
			});

		// Load a single component
		addRoute(basePath() + "/:compUuid", GET,
			"Load a component by UUID",
			null,
			examples.assetComponentResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("assetUuid"), lrc.pathParamUUID("compUuid"));
			});

		// Update a component
		addRoute(basePath() + "/:compUuid", POST,
			"Update a component",
			examples.assetComponentUpdateRequestExample(),
			examples.assetComponentResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("assetUuid"), lrc.pathParamUUID("compUuid"));
			});

		// Delete a component
		addRoute(basePath() + "/:compUuid", DELETE,
			"Delete a component",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("assetUuid"), lrc.pathParamUUID("compUuid"));
			});
	}
}
