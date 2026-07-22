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
		secure(basePath() + "/runs/stats");
		secure(basePath() + "/:uuid");
		secure(basePath() + "/:uuid/run");
		secure(basePath() + "/:uuid/runs");
		secure(basePath() + "/:uuid/runs/:runUuid");
		secure(basePath() + "/:uuid/runs/:runUuid/items");
		secure(basePath() + "/:uuid/runs/:runUuid/cancel");
		secure(basePath() + "/:uuid/versions");
		secure(basePath() + "/:uuid/versions/:version");
		secure(basePath() + "/:uuid/versions/:version/restore");

		// Cross-pipeline run stats (literal prefix — registered before :uuid wildcard)
		addRoute(basePath() + "/runs/stats", GET,
			"Load aggregated daily pipeline run statistics across all pipelines",
			null,
			examples.pipelineRunStatsResponseExample(),
			lrc -> {
				service.loadRunStats(lrc);
			});

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
			"Delete a pipeline and all its versions",
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

		// Run — dispatch a pipeline execution to a registered processor
		addRoute(basePath() + "/:uuid/run", POST,
			"Trigger execution of a pipeline",
			examples.pipelineRunRequestExample(),
			examples.pipelineRunResponseExample(),
			lrc -> {
				service.run(lrc, lrc.pathParamUUID("uuid"));
			});

		// Run history — list pipeline runs
		addListRoute(basePath() + "/:uuid/runs", GET,
			"Load a paged list of pipeline runs",
			examples.pipelineRunListResponseExample(),
			lrc -> {
				service.listRuns(lrc, lrc.pathParamUUID("uuid"));
			});

		// Load a single pipeline run
		addRoute(basePath() + "/:uuid/runs/:runUuid", GET,
			"Load a single pipeline run",
			null,
			examples.pipelineRunRecordExample(),
			lrc -> {
				service.loadRun(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("runUuid"));
			});

		// List the items of a single pipeline run
		addListRoute(basePath() + "/:uuid/runs/:runUuid/items", GET,
			"Load a paged list of items for a pipeline run",
			examples.pipelineRunItemListResponseExample(),
			lrc -> {
				service.listRunItems(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("runUuid"));
			});

		// Cancel an in-flight pipeline run
		addRoute(basePath() + "/:uuid/runs/:runUuid/cancel", POST,
			"Cancel an in-flight pipeline run",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.cancelRun(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamUUID("runUuid"));
			});

		// Pipeline Versions
		// List all versions of a pipeline
		addListRoute(basePath() + "/:uuid/versions", GET,
			"Load a paged list of pipeline versions",
			examples.pipelineVersionListResponseExample(),
			lrc -> {
				service.listVersions(lrc, lrc.pathParamUUID("uuid"));
			});

		// Load a specific version of a pipeline
		addRoute(basePath() + "/:uuid/versions/:version", GET,
			"Load a specific pipeline version",
			null,
			examples.pipelineVersionResponseExample(),
			lrc -> {
				service.loadVersion(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamInt("version"));
			});

		// Restore a pipeline version (creates a new version with restored content)
		addRoute(basePath() + "/:uuid/versions/:version/restore", POST,
			"Restore a pipeline version",
			examples.pipelineVersionRestoreRequestExample(),
			examples.pipelineVersionRestoreResponseExample(),
			lrc -> {
				service.restoreVersion(lrc, lrc.pathParamUUID("uuid"), lrc.pathParamInt("version"));
			});
	}
}
