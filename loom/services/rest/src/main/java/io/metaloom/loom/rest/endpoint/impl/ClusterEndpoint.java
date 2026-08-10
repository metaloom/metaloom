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
import io.metaloom.loom.rest.service.impl.ClusterEndpointService;

public class ClusterEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(ClusterEndpoint.class);

	private final ClusterEndpointService service;
	private final ModelExamples examples;

	@Inject
	public ClusterEndpoint(ClusterEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "cluster";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/clusters";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Create
		addRoute(basePath(), POST,
			"Create a new cluster",
			examples.clusterCreateExample(),
			examples.clusterResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		// Update
		addRoute(basePath() + "/:uuid", POST,
			"Update a cluster",
			examples.clusterUpdateExample(),
			examples.clusterResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Delete
		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a cluster",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of clusters",
			examples.clusterListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load a cluster",
			null,
			examples.clusterResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// Members
		addRoute(basePath() + "/:uuid/members", GET,
			"List the embeddings belonging to a cluster, each with the detection and bounding box it came from so a face crop can be addressed.",
			null,
			examples.clusterMemberListResponseExample(),
			lrc -> {
				service.listMembers(lrc, lrc.pathParamUUID("uuid"));
			});

		// Confirm
		//
		// An RPC-style sub-resource rather than a field write, because the operation is not one: it creates or links a person and mutates the
		// cluster in a single transaction, and its required permissions depend on which of those it does.
		addRoute(basePath() + "/:uuid/confirm", POST,
			"Confirm that a cluster is a person, linking an existing one or creating a new one. Requires CREATE_PERSON in addition to UPDATE_CLUSTER when it creates.",
			examples.clusterConfirmExample(),
			// The reviewed shape, not the pending one: these two routes are the only writers of reviewedAt/reviewerUuid, and the response is where a
			// client learns that a verdict carries its author.
			examples.clusterReviewedResponseExample(),
			lrc -> {
				service.confirm(lrc, lrc.pathParamUUID("uuid"));
			});

		// Reject
		addRoute(basePath() + "/:uuid/reject", POST,
			"Reject a cluster: it is not a subject worth keeping. The person pointer is left as it was, so an earlier verdict stays readable.",
			null,
			examples.clusterReviewedResponseExample(),
			lrc -> {
				service.reject(lrc, lrc.pathParamUUID("uuid"));
			});
	}
}
