package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.SearchIndexEndpointService;

/**
 * Operator routes for every search-related index: the lexical documents, each embedding vector space, and the duplicate fingerprints.
 *
 * <p>
 * These indices are maintained by completely different machinery - triggers, an asynchronous drain, and a node write hook respectively - but they
 * fail in the same three ways: behind, drifted, or broken. This endpoint is the one place an operator can see all three side by side and act on them,
 * which is why it supersedes the per-backend {@code /vector-index/*} and {@code /similarity-index/*} routes rather than sitting beside them.
 * </p>
 *
 * <p>
 * <b>Work is a job, not a request.</b> Rebuilding an index walks the whole corpus; the older routes did that inside the request and held the
 * connection open for however long it took, reporting nothing until it finished. Here {@code POST .../jobs} answers 202 with a job the client polls.
 * </p>
 */
@Singleton
public class SearchIndexEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(SearchIndexEndpoint.class);

	private final SearchIndexEndpointService service;
	private final ModelExamples examples;

	@Inject
	public SearchIndexEndpoint(SearchIndexEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "search-indices";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/search-indices";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		addRoute(basePath(), GET,
			"List every search index with its state, model, size and backlog, plus the storage backends they live in",
			null, examples.searchIndexListResponseExample(),
			lrc -> service.list(lrc));

		// The job routes come first: ":id" would otherwise swallow nothing here (jobs sits one level
		// deeper), but keeping the more specific paths above the bare ":id" read matches how every
		// other endpoint in this package is ordered and survives a later route being added.
		addRoute(basePath() + "/:id/jobs", GET,
			"List the recent maintenance jobs for one index, newest first",
			null, examples.indexJobListResponseExample(),
			lrc -> service.listJobs(lrc, lrc.pathParam("id")));

		addRoute(basePath() + "/:id/jobs", POST,
			"Start a maintenance job (REINDEX, DELTA_SYNC or DROP) against one index. Answers 202 with a job to poll",
			examples.indexJobCreateRequestExample(), examples.indexJobResponseExample(),
			lrc -> service.createJob(lrc, lrc.pathParam("id")));

		addRoute(basePath() + "/:id/jobs/:jobUuid", GET,
			"Read one maintenance job and its progress",
			null, examples.indexJobResponseExample(),
			lrc -> service.readJob(lrc, lrc.pathParam("id"), lrc.pathParamUUID("jobUuid")));

		addRoute(basePath() + "/:id/jobs/:jobUuid", DELETE,
			"Ask a running maintenance job to stop at its next item boundary",
			null, examples.indexJobResponseExample(),
			lrc -> service.cancelJob(lrc, lrc.pathParam("id"), lrc.pathParamUUID("jobUuid")));

		addRoute(basePath() + "/:id", GET,
			"Read the state of one search index",
			null, examples.searchIndexResponseExample(),
			lrc -> service.read(lrc, lrc.pathParam("id")));
	}
}
