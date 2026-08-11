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
import io.metaloom.loom.rest.service.impl.PublicShareEndpointService;

/**
 * The customer-facing area, at {@code /api/v1/shares/:slug}.
 *
 * <p>
 * <b>This endpoint never calls {@code secure()}</b>, which makes it the fifth unauthenticated prefix in the API after {@code /login},
 * {@code /auth/oauth2}, {@code /health} and the spec routes. That is the entire point of the feature: a customer with no Loom account opens a URL and
 * sees the material.
 * </p>
 *
 * <p>
 * Because there is no authentication handler in front of these routes, there is no user, and {@code requirePerm} is not merely skipped but
 * inapplicable. Every handler below delegates to {@code PublicShareEndpointService}, and every method there begins by calling
 * {@code ShareAccessService} - which resolves the slug, validates the session token, and checks that the addressed asset really is part of what was
 * shared. Adding a route here without that call would publish whatever it reads to the open internet.
 * </p>
 *
 * <p>
 * The slug is a path parameter rather than a uuid, so these routes use {@code lrc.pathParam} rather than {@code pathParamUUID}.
 * </p>
 */
public class PublicShareEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(PublicShareEndpoint.class);

	private final PublicShareEndpointService service;
	private final ModelExamples examples;

	@Inject
	public PublicShareEndpoint(PublicShareEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "share";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/shares";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint - PUBLIC, no secure() by design", name());

		// Deliberately no secure(). See the class comment.

		// Sub-resources before /:slug, so a literal segment is never swallowed by the parameter route.
		addRoute(basePath() + "/:slug/sessions", POST,
			"Open a share link: check its password, record the visitor's name and issue a session",
			examples.shareSessionRequestExample(),
			examples.shareSessionResponseExample(),
			lrc -> {
				service.createSession(lrc, lrc.pathParam("slug"));
			});

		addListRoute(basePath() + "/:slug/assets", GET,
			"The material behind a share link",
			examples.sharedAssetListResponseExample(),
			lrc -> {
				service.listAssets(lrc, lrc.pathParam("slug"));
			});

		addDownloadRoute(basePath() + "/:slug/assets/:assetUuid/binary/data",
			"Stream the bytes of a shared asset. Inline by default; add ?download=1 to receive it as a file",
			lrc -> {
				service.downloadAsset(lrc, lrc.pathParam("slug"), lrc.pathParamUUID("assetUuid"));
			});

		addRoute(basePath() + "/:slug/assets/:assetUuid", GET,
			"One shared asset",
			null,
			examples.sharedAssetResponseExample(),
			lrc -> {
				service.loadAsset(lrc, lrc.pathParam("slug"), lrc.pathParamUUID("assetUuid"));
			});

		// --- Feedback ---

		addRoute(basePath() + "/:slug/comments", GET,
			"The comments left through this link. Narrow to one asset with ?asset=<uuid>",
			null,
			examples.shareCommentListResponseExample(),
			lrc -> {
				service.listComments(lrc, lrc.pathParam("slug"));
			});

		addRoute(basePath() + "/:slug/comments", POST,
			"Leave a comment",
			examples.shareCommentRequestExample(),
			examples.shareCommentResponseExample(),
			lrc -> {
				service.createComment(lrc, lrc.pathParam("slug"));
			});

		addRoute(basePath() + "/:slug/comments/:commentUuid", POST,
			"Edit a comment left through this link",
			examples.shareCommentRequestExample(),
			examples.shareCommentResponseExample(),
			lrc -> {
				service.updateComment(lrc, lrc.pathParam("slug"), lrc.pathParamUUID("commentUuid"));
			});

		addRoute(basePath() + "/:slug/comments/:commentUuid", DELETE,
			"Remove a comment left through this link",
			lrc -> {
				service.deleteComment(lrc, lrc.pathParam("slug"), lrc.pathParamUUID("commentUuid"));
			});

		addRoute(basePath() + "/:slug/annotations", GET,
			"The marks drawn through this link. Narrow to one asset with ?asset=<uuid>",
			null,
			examples.shareAnnotationListResponseExample(),
			lrc -> {
				service.listAnnotations(lrc, lrc.pathParam("slug"));
			});

		addRoute(basePath() + "/:slug/annotations", POST,
			"Mark a moment, a region, or a region over a stretch of time",
			examples.shareAnnotationRequestExample(),
			examples.shareAnnotationResponseExample(),
			lrc -> {
				service.createAnnotation(lrc, lrc.pathParam("slug"));
			});

		addRoute(basePath() + "/:slug/annotations/:annotationUuid", POST,
			"Move or retitle a mark drawn through this link",
			examples.shareAnnotationRequestExample(),
			examples.shareAnnotationResponseExample(),
			lrc -> {
				service.updateAnnotation(lrc, lrc.pathParam("slug"), lrc.pathParamUUID("annotationUuid"));
			});

		addRoute(basePath() + "/:slug/annotations/:annotationUuid", DELETE,
			"Remove a mark drawn through this link",
			lrc -> {
				service.deleteAnnotation(lrc, lrc.pathParam("slug"), lrc.pathParamUUID("annotationUuid"));
			});

		addRoute(basePath() + "/:slug/reactions", GET,
			"The reactions left through this link. Narrow to one asset with ?asset=<uuid>",
			null,
			examples.shareReactionListResponseExample(),
			lrc -> {
				service.listReactions(lrc, lrc.pathParam("slug"));
			});

		addRoute(basePath() + "/:slug/reactions", POST,
			"React to an asset, a comment or a mark",
			examples.shareReactionRequestExample(),
			examples.shareReactionResponseExample(),
			lrc -> {
				service.createReaction(lrc, lrc.pathParam("slug"));
			});

		addRoute(basePath() + "/:slug/reactions/:reactionUuid", DELETE,
			"Take back a reaction",
			lrc -> {
				service.deleteReaction(lrc, lrc.pathParam("slug"), lrc.pathParamUUID("reactionUuid"));
			});

		// Last: the bare slug. Registered after every sub-resource above so none of them is shadowed.
		addRoute(basePath() + "/:slug", GET,
			"What a share link asks for before it will open - whether it needs a password, and what kind of material is behind it",
			null,
			examples.shareChallengeResponseExample(),
			lrc -> {
				service.challenge(lrc, lrc.pathParam("slug"));
			});
	}
}
