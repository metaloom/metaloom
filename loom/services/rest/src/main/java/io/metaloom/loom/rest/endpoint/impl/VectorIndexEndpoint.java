package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.service.impl.VectorIndexEndpointService;

/**
 * Administrative routes for the embedding vector index.
 *
 * <p>
 * Index-wide operations only. Because the index is a derived cache of {@code embedding.vector}, a rebuild is always safe, and it is the recovery path
 * for any drift as well as the migration path when the embedding model or the index backend changes.
 * </p>
 */
@Singleton
public class VectorIndexEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(VectorIndexEndpoint.class);

	private final VectorIndexEndpointService service;

	@Inject
	public VectorIndexEndpoint(VectorIndexEndpointService service, EndpointDependencies deps) {
		super(deps);
		this.service = service;
	}

	@Override
	public String name() {
		return "vector-index";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/vector-index";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		addRoute(basePath() + "/rebuild", POST,
			"Rebuild the embedding vector index from the stored embeddings",
			lrc -> service.rebuild(lrc));

		addRoute(basePath() + "/sync", POST,
			"Index the embeddings that have not yet been written to the vector index",
			lrc -> service.sync(lrc));

		addRoute(basePath() + "/status", GET,
			"Report which vector index backend is bound and whether it is usable",
			lrc -> service.status(lrc));
	}
}
