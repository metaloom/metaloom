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
import io.metaloom.loom.rest.service.impl.ReactionEndpointService;

public class ReactionEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(ReactionEndpoint.class);

	private final ReactionEndpointService service;
	private final ModelExamples examples;

	@Inject
	public ReactionEndpoint(ReactionEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "reaction";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/reactions";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// List all reactions
		addListRoute(basePath(), GET,
			"Load a paged list of reactions",
			null,
			lrc -> {
				service.listReactions(lrc);
			});

		// Load a single reaction
		addRoute(basePath() + "/:uuid", GET,
			"Load a reaction",
			lrc -> {
				service.loadReaction(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete a reaction
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a reaction",
			lrc -> {
				service.deleteReaction(lrc, lrc.pathParamUUID("uuid"));
			});

		// --- Asset-scoped reactions ---

		addRoute(basePath() + "/assets/:assetUuid", POST,
			"Create a new reaction on an asset",
			lrc -> {
				service.createAssetReaction(lrc, lrc.pathParamAssetId("assetUuid"));
			});

		addRoute(basePath() + "/assets/:assetUuid", GET,
			"List reactions on an asset",
			lrc -> {
				service.listAssetReactions(lrc, lrc.pathParamAssetId("assetUuid"));
			});
	}

}
