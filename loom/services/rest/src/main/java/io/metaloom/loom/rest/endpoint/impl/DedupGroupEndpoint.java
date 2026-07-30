package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.PATCH;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.service.impl.DedupGroupEndpointService;

/**
 * Deduplication review routes (spec/features/pipeline-nodes/NODE_DEDUP_PLAN.md §2.1).
 *
 * <p>
 * The queue a human works through: the discovery node posts candidate duplicate groups, a reviewer confirms or rejects them, and only then does the
 * apply node move any file.
 * </p>
 */
@Singleton
public class DedupGroupEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(DedupGroupEndpoint.class);

	private final DedupGroupEndpointService service;

	@Inject
	public DedupGroupEndpoint(DedupGroupEndpointService service, EndpointDependencies deps) {
		super(deps);
		this.service = service;
	}

	@Override
	public String name() {
		return "dedup-groups";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/dedup-groups";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		addRoute(basePath(), POST,
			"Create or update a candidate duplicate group. Used by the fingerprint-dedup discovery node; upserts the pending group for the same keep asset and algorithm.",
			lrc -> service.createDedupGroup(lrc));

		addRoute(basePath(), GET,
			"List duplicate review groups, optionally filtered by status (PENDING, CONFIRMED, REJECTED)",
			lrc -> service.listDedupGroups(lrc));

		addRoute(basePath() + "/:uuid", GET,
			"Load one duplicate review group and its members",
			lrc -> service.loadDedupGroup(lrc, lrc.pathParamUUID("uuid")));

		addRoute(basePath() + "/:uuid", PATCH,
			"Confirm or reject a duplicate review group. Only a confirmed group is ever acted on by the apply node.",
			lrc -> service.updateDedupGroup(lrc, lrc.pathParamUUID("uuid")));

		addRoute(basePath() + "/:uuid", DELETE,
			"Delete a duplicate review group. Its members are removed with it.",
			lrc -> service.deleteDedupGroup(lrc, lrc.pathParamUUID("uuid")));
	}
}
