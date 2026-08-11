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
import io.metaloom.loom.rest.service.impl.ShareLinkEndpointService;

/**
 * The owner-facing half of sharing, at {@code /api/v1/share-links}.
 *
 * <p>
 * <b>A separate endpoint from {@link PublicShareEndpoint}, and on a separate base path, on purpose.</b> Authentication is applied by path wildcard
 * ({@code secure(basePath() + "*")}), so hanging the visitor routes off the same base would either secure them - breaking the whole feature - or force
 * this endpoint to enumerate each of its own paths, which is the kind of list that silently loses an entry when somebody adds a route. Two base paths
 * make the split structural: everything under {@code /share-links} is authenticated, everything under {@code /shares} is not, and neither can drift
 * into the other.
 * </p>
 */
public class ShareLinkEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(ShareLinkEndpoint.class);

	private final ShareLinkEndpointService service;
	private final ModelExamples examples;

	@Inject
	public ShareLinkEndpoint(ShareLinkEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "share-link";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/share-links";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		addRoute(basePath(), POST,
			"Create a share link over an asset or a collection",
			examples.shareCreateRequestExample(),
			examples.shareResponseExample(),
			lrc -> {
				service.create(lrc);
			});

		addRoute(basePath() + "/:uuid", POST,
			"Update a share link - its expiry, its password, or what the visitor may do",
			examples.shareUpdateRequestExample(),
			examples.shareResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid", DELETE,
			"Revoke a share link. The URL stops working immediately and any feedback left through it is removed",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});

		addListRoute(basePath(), GET,
			"Load a paged list of share links",
			examples.shareListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// Registered before /:uuid so the literal segment is not swallowed by the wildcard - see RESTAPI.md section 9.
		addRoute(basePath() + "/:uuid/feedback", GET,
			"Load the comments, marks and reactions a visitor left through this link",
			null,
			examples.shareFeedbackResponseExample(),
			lrc -> {
				service.loadFeedback(lrc, lrc.pathParamUUID("uuid"));
			});

		addRoute(basePath() + "/:uuid", GET,
			"Load a share link",
			null,
			examples.shareResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});
	}
}
