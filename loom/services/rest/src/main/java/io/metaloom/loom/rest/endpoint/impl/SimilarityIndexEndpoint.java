package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.service.impl.SimilarityEndpointService;

/**
 * Administrative routes for the fingerprint similarity index (spec/loom/SEARCH_LUCENE.md §5).
 *
 * <p>
 * The per-asset query lives on {@code /assets/:uuid/similar-assets} ({@link AssetEndpoint}); this endpoint carries only the index-wide operations.
 * Because the index is a derived cache of {@code asset_fingerprint_comp}, a rebuild is always safe and is the recovery path for any drift.
 * </p>
 */
@Singleton
public class SimilarityIndexEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(SimilarityIndexEndpoint.class);

	private final SimilarityEndpointService service;

	@Inject
	public SimilarityIndexEndpoint(SimilarityEndpointService service, EndpointDependencies deps) {
		super(deps);
		this.service = service;
	}

	@Override
	public String name() {
		return "similarity-index";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/similarity-index";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		addRoute(basePath() + "/rebuild", POST,
			"Rebuild the fingerprint similarity index from the stored fingerprint components",
			lrc -> service.rebuild(lrc));
	}
}
