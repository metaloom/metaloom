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
import io.metaloom.loom.rest.service.impl.PipelineEndpointService;

public class PipelineEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(PipelineEndpoint.class);

	private final PipelineEndpointService service;
	private final ModelExamples examples;

	@Inject
	public PipelineEndpoint(PipelineEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "pipeline";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/pipelines";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		// Secure CRUD paths without catching nested technical paths like
		// /api/v1/pipelines/events/ws which is handled by PipelineEventEndpoint.
		secure(basePath());
		secure(basePath() + "/:uuid");

		// Create
		addRoute(basePath(), POST,
			"Create a new pipeline",
			examples.pipelineCreateRequestExample(),
			examples.pipelineResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a pipeline",
			examples.pipelineUpdateRequestExample(),
			examples.pipelineResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a pipeline",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of pipelines",
			examples.pipelineListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a pipeline",
			null,
			examples.pipelineResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});
	}
}
