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
import io.metaloom.loom.rest.service.impl.SkillEndpointService;

public class SkillEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(SkillEndpoint.class);

	private final SkillEndpointService service;
	private final ModelExamples examples;

	@Inject
	public SkillEndpoint(SkillEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "skill";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/skills";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Library (must be registered before /:uuid so "library" is not consumed as a uuid)
		addListRoute(basePath() + "/library", GET,
			"Load a paged list of published skills from all users (the shared skill library)",
			examples.skillListResponseExample(),
			lrc -> {
				service.listLibrary(lrc);
			});

		// Create
		addRoute(basePath(), POST,
			"Create a new skill",
			examples.skillCreateRequestExample(),
			examples.skillResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Install
		addRoute(basePath() + "/:uuid/install", POST,
			"Install a published skill by copying it into the callers own skill set",
			null,
			examples.skillResponseExample(),
			lrc -> {
				service.install(lrc, lrc.pathParamUUID("uuid"));
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a skill",
			examples.skillUpdateRequestExample(),
			examples.skillResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a skill",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List (own skills only)
		addListRoute(basePath(), GET,
			"Load a paged list of the callers own skills",
			examples.skillListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a skill",
			null,
			examples.skillResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});
	}

}
