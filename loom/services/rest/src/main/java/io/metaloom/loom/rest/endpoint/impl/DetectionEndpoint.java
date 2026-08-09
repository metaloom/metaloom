package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.GET;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.DetectionEndpointService;

/**
 * The cross-asset detection collection.
 *
 * <p>
 * Every other detection route is an asset sub-resource on {@link AssetEndpoint}, because a detection belongs to an asset. This endpoint exists for the
 * one question that cannot be asked that way: "what is waiting to be reviewed?" - which spans assets by definition. Same split {@code ClusterEndpoint}
 * has against {@code /assets/:uuid/clusters}.
 * </p>
 */
public class DetectionEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(DetectionEndpoint.class);

	private final DetectionEndpointService service;

	private final ModelExamples examples;

	@Inject
	public DetectionEndpoint(DetectionEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "detection";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/detections";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// addListRoute, not addRoute: the review queue is paged, and the plain variant would leave ?limit= and ?from= undocumented in OpenAPI.
		addListRoute(basePath(), GET,
			"Load a paged list of detections across every asset, optionally filtered by review status and type. This is the review queue.",
			examples.detectionListResponseExample(),
			lrc -> {
				service.listDetections(lrc);
			});
	}
}
