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
import io.metaloom.loom.rest.service.impl.TagEndpointService;

public class TagEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(RoleEndpoint.class);

	private final ModelExamples examples;
	private final TagEndpointService service;

	@Inject
	public TagEndpoint(TagEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.examples = examples;
		this.service = service;
	}

	@Override
	public String name() {
		return "tag";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/tags";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create new tag",
			examples.tagCreateRequestExample(),
			examples.tagResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a tag",
			examples.tagUpdateRequestExample(),
			examples.tagResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a tag",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of tags",
			examples.tagListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a tag",
			null,
			examples.tagResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});
	}

}
