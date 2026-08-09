package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.NodeRunEndpointService;

/**
 * Running nodes on chosen assets without a stored pipeline.
 *
 * <p>
 * Two shapes, because the work has two shapes. {@code POST /node-runs/probes} runs one node against
 * one asset and answers with the result, which is what "what does this node say about this file"
 * needs. {@code POST /node-runs} takes a graph and a set of assets and answers with a handle, because
 * a pass over two hundred images does not fit in a request.
 * </p>
 *
 * <p>
 * Every route is scoped to the caller: an ad-hoc run belongs to whoever started it and is not visible
 * under {@code /pipelines/:uuid/runs}. See {@code spec/chat/AGENTIC_NODE_EXECUTION.md}.
 * </p>
 */
public class NodeRunEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(NodeRunEndpoint.class);

	private final NodeRunEndpointService service;
	private final ModelExamples examples;

	@Inject
	public NodeRunEndpoint(NodeRunEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "nodeRun";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/node-runs";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// LITERAL PREFIX - registered before the /:uuid wildcard below, or Vert.x matches "probes"
		// as a uuid path parameter and this route is unreachable.
		addRoute(basePath() + "/probes", POST,
			"Run a single node against a single asset and wait for the result. A node that cannot be run reports why in the response rather than failing the request.",
			examples.nodeProbeRequestExample(),
			examples.nodeProbeResponseExample(),
			lrc -> {
				service.probe(lrc);
			});

		addRoute(basePath(), POST,
			"Start an ad-hoc node run from a definition supplied with the request, and return a handle to poll.",
			examples.nodeRunRequestExample(),
			examples.nodeRunResponseExample(),
			lrc -> {
				service.start(lrc);
			});

		addListRoute(basePath(), GET,
			"Load a paged list of the caller's ad-hoc node runs, newest first.",
			examples.nodeRunListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		addRoute(basePath() + "/:uuid", GET,
			"Load the status and results of one of the caller's ad-hoc node runs. Pass ?results=false for the status alone.",
			null,
			examples.nodeRunStatusResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid/cancel", POST,
			"Cancel one of the caller's ad-hoc node runs.",
			null,
			examples.nodeRunCancelResponseExample(),
			lrc -> {
				service.cancel(lrc, lrc.pathParamUUID("uuid"));
			});
	}
}
