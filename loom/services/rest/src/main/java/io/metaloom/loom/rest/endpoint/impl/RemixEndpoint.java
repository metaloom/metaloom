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
import io.metaloom.loom.rest.service.impl.RemixEndpointService;

public class RemixEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(RemixEndpoint.class);
	private final RemixEndpointService service;
	private final ModelExamples examples;

	@Inject
	public RemixEndpoint(RemixEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "remix";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/remixes";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create. The request may carry the members, because "combine these into a remix" is one
		// gesture and splitting it would leave an empty remix behind when the second call failed.
		addRoute(basePath(), POST,
			"Create a new remix, optionally with its members",
			examples.remixCreateRequestExample(),
			examples.remixResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of remixes",
			examples.remixListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Membership routes are registered before /:uuid so the literal segments are matched first;
		// a uuid route registered ahead of them swallows "assets" as a uuid and answers 400.
		addListRoute(basePath() + "/:uuid/assets", GET,
			"Load a paged list of the assets in the remix",
			examples.remixMemberListResponseExample(),
			lrc -> {
				service.listMembers(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/assets", POST,
			"Add one or more assets to the remix",
			examples.remixMemberRequestExample(),
			examples.remixResponseExample(),
			lrc -> {
				service.addAssets(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/assets/:assetUuid", DELETE,
			"Remove an asset from the remix",
			lrc -> {
				service.removeAsset(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("assetUuid"));
			});

		addRoute(basePath() + "/:uuid/source", POST,
			"Set which member asset is the source of the remix",
			examples.remixMemberRequestExample(),
			examples.remixResponseExample(),
			lrc -> {
				service.setSource(lrc, lrc.pathParamUUID("uuid"));
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a remix",
			null,
			examples.remixResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a remix",
			examples.remixUpdateRequestExample(),
			examples.remixResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a remix",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});
	}

}
