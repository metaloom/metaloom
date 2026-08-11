package io.metaloom.loom.rest.service.impl;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.auth.AuthenticationService;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.share.Share;
import io.metaloom.loom.db.model.share.ShareAnnotation;
import io.metaloom.loom.db.model.share.ShareAnnotationKind;
import io.metaloom.loom.db.model.share.ShareComment;
import io.metaloom.loom.db.model.share.ShareReaction;
import io.metaloom.loom.db.model.share.ShareReactionType;
import io.metaloom.loom.db.model.share.ShareTargetType;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.share.ShareAnnotationRequest;
import io.metaloom.loom.rest.model.share.ShareChallengeResponse;
import io.metaloom.loom.rest.model.share.ShareCommentRequest;
import io.metaloom.loom.rest.model.share.ShareReactionRequest;
import io.metaloom.loom.rest.model.share.ShareSessionRequest;
import io.metaloom.loom.rest.model.share.ShareSessionResponse;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.service.AbstractEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;

/**
 * Everything an unauthenticated share visitor can do.
 *
 * <p>
 * <b>No method in this class calls {@code checkPerm}, and none of them can.</b> The routes are not behind {@code secure()}, so there is no
 * authenticated user to check permissions against - the caller is a member of the public holding a URL. Authorization is instead
 * {@link ShareAccessService}, which every method here calls first, and which decides from the share row alone.
 * </p>
 *
 * <p>
 * If you add a method to this class, its first statement is a call into {@code ShareAccessService}. There is no exception; the challenge route uses
 * {@code requireShare} because it runs before a session exists, and everything else uses {@code requireSession} or {@code requireAssetAccess}.
 * </p>
 */
@Singleton
public class PublicShareEndpointService extends AbstractEndpointService {

	/** What a visitor is called when they decline to give a name. Localised by the client, which sends the text. */
	private static final String DEFAULT_VISITOR_NAME = "Anonymous";

	private final DaoCollection daos;
	private final ShareAccessService access;
	private final ShareThrottle throttle;
	private final AuthenticationService authService;
	private final AssetBinaryEndpointService binaryService;
	private final NotificationDispatcher dispatcher;

	@Inject
	public PublicShareEndpointService(DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		ShareAccessService access, ShareThrottle throttle, AuthenticationService authService, AssetBinaryEndpointService binaryService,
		NotificationDispatcher dispatcher) {
		super(modelBuilder, validator);
		this.daos = daos;
		this.access = access;
		this.throttle = throttle;
		this.authService = authService;
		this.binaryService = binaryService;
		this.dispatcher = dispatcher;
	}

	// -------------------------------------------------------------------------------------------
	// Opening a link
	// -------------------------------------------------------------------------------------------

	/**
	 * What the front door needs to render itself, before the visitor has proved anything.
	 */
	public void challenge(LoomRoutingContext lrc, String slug) {
		Share share = access.requireShare(slug);
		ShareChallengeResponse response = new ShareChallengeResponse()
			.setTargetType(share.getTargetType())
			.setPasswordRequired(share.isPasswordProtected())
			.setVisitorNameKnown(share.getVisitorName() != null)
			.setVisitorName(share.getVisitorName());
		lrc.send(response);
	}

	/**
	 * Redeem a link: check the password, stamp the visitor name if this is the first visit, and issue a session.
	 */
	public void createSession(LoomRoutingContext lrc, String slug) {
		Share share = access.requireShare(slug);
		ShareSessionRequest request = lrc.requestBody(ShareSessionRequest.class);
		validator.validate(request);

		if (share.isPasswordProtected()) {
			// Checked before the password so that a locked slug does not keep paying for a bcrypt comparison per
			// guess - the throttle is there to stop the work, not only to refuse the answer.
			if (throttle.isThrottled(slug)) {
				throw new LoomRestException(429, LoomRestErrorCode.BAD_REQUEST,
					"Too many failed attempts for this link. Try again in a few minutes.");
			}
			if (!authService.matchesPassword(request.getPassword(), share.getPasswordHash())) {
				throttle.recordFailure(slug);
				throw new LoomRestException(401, LoomRestErrorCode.BAD_REQUEST, "The password is not correct.");
			}
			throttle.recordSuccess(slug);
		}

		String offeredName = normaliseName(request.getVisitorName());
		daos.shareDao().recordVisit(share.getUuid(), offeredName);

		// Re-read rather than trusting the name just sent: the stored name wins when one is already set, and the
		// UPDATE is where that rule lives. Echoing back what the visitor typed would show the second visitor a name
		// that was never stored.
		Share refreshed = daos.shareDao().load(share.getUuid());

		String token = access.tokens().issue(slug);
		setSessionCookie(lrc, token);

		ShareSessionResponse response = new ShareSessionResponse()
			.setSessionToken(token)
			.setSessionExpiresAt(access.tokens().expiryOfNewToken())
			.setVisitorName(refreshed.getVisitorName())
			.setTargetType(refreshed.getTargetType())
			.setAllowDownload(refreshed.getAllowDownload())
			.setShowMetadata(refreshed.getShowMetadata())
			.setAllowComments(refreshed.getAllowComments())
			.setAllowReactions(refreshed.getAllowReactions())
			.setAllowAnnotations(refreshed.getAllowAnnotations());
		applyTargetInfo(refreshed, response);
		lrc.send(response);
	}

	// -------------------------------------------------------------------------------------------
	// Looking at the material
	// -------------------------------------------------------------------------------------------

	/**
	 * The assets behind the link: the members of a shared collection, or the single shared asset.
	 */
	public void listAssets(LoomRoutingContext lrc, String slug) {
		Share share = access.requireSession(lrc, slug);
		boolean showMetadata = Boolean.TRUE.equals(share.getShowMetadata());

		if (share.targetType() == ShareTargetType.ASSET) {
			Asset asset = daos.assetDao().load(share.getAssetUuid());
			// A one-element list rather than a different response shape for the single-asset case: the viewer then
			// has one code path, and a share can be switched from an asset to a collection without a client change.
			List<Asset> single = asset == null ? List.of() : List.of(asset);
			Page<Asset> page = new Page<>(single.size(), single.size(), single);
			lrc.send(modelBuilder.toSharedAssetList(page, showMetadata));
			return;
		}

		PagingParameters paging = lrc.pagingParams();
		Page<Asset> page = daos.assetDao().loadPageByCollection(share.getCollectionUuid(), paging.from(), paging.limit());
		lrc.send(modelBuilder.toSharedAssetList(page, showMetadata));
	}

	/**
	 * One asset, in the narrow projection a visitor is allowed to see.
	 */
	public void loadAsset(LoomRoutingContext lrc, String slug, UUID assetUuid) {
		Share share = access.requireAssetAccess(lrc, slug, assetUuid);
		Asset asset = access.requireAsset(share, assetUuid);
		lrc.send(modelBuilder.toSharedAssetResponse(asset, Boolean.TRUE.equals(share.getShowMetadata())));
	}

	/**
	 * The bytes.
	 *
	 * <p>
	 * Inline by default so the browser plays or renders the file in place; {@code ?download=1} switches to an attachment and is refused when the link
	 * does not allow downloading. Range handling comes from {@code AssetBinaryEndpointService}, which is what makes seeking in a video work.
	 * </p>
	 */
	public void downloadAsset(LoomRoutingContext lrc, String slug, UUID assetUuid) {
		Share share = access.requireAssetAccess(lrc, slug, assetUuid);
		boolean asAttachment = isDownloadRequested(lrc);
		if (asAttachment) {
			access.requireCapability(share.getAllowDownload(), "downloading");
		}
		binaryService.streamPrimaryBinary(lrc, assetUuid, asAttachment);
	}

	// -------------------------------------------------------------------------------------------
	// Saying something back
	// -------------------------------------------------------------------------------------------

	public void listComments(LoomRoutingContext lrc, String slug) {
		Share share = access.requireSession(lrc, slug);
		UUID assetUuid = optionalAssetFilter(lrc, share);
		lrc.send(modelBuilder.toShareCommentList(daos.shareFeedbackDao().listComments(share.getUuid(), assetUuid)));
	}

	public void createComment(LoomRoutingContext lrc, String slug) {
		Share share = access.requireSession(lrc, slug);
		access.requireCapability(share.getAllowComments(), "comments");

		ShareCommentRequest request = lrc.requestBody(ShareCommentRequest.class);
		validator.validate(request);
		UUID assetUuid = requireMemberOrNull(share, request.getAssetUuid());

		ShareComment comment = daos.shareFeedbackDao().createComment(share.getUuid(), assetUuid, authorOf(share), request.getText());
		if (request.getParentUuid() != null) {
			ShareComment parent = requireComment(share, request.getParentUuid());
			// Replies are one level deep. Attaching to the parent's parent rather than rejecting the request keeps a
			// client that renders a reply button on every comment from producing a thread nobody can follow.
			comment.setParentUuid(parent.getParentUuid() != null ? parent.getParentUuid() : parent.getUuid());
		}
		if (request.getAnnotationUuid() != null) {
			comment.setShareAnnotationUuid(requireAnnotation(share, request.getAnnotationUuid()).getUuid());
		}
		daos.shareFeedbackDao().storeComment(comment);
		notifyOwner(share, assetUuid, comment.getText());
		lrc.send(modelBuilder.toResponse(comment), 201);
	}

	public void updateComment(LoomRoutingContext lrc, String slug, UUID commentUuid) {
		Share share = access.requireSession(lrc, slug);
		access.requireCapability(share.getAllowComments(), "comments");

		ShareCommentRequest request = lrc.requestBody(ShareCommentRequest.class);
		validator.validate(request);

		ShareComment comment = requireComment(share, commentUuid);
		comment.setText(request.getText());
		daos.shareFeedbackDao().updateComment(comment);
		lrc.send(modelBuilder.toResponse(comment));
	}

	public void deleteComment(LoomRoutingContext lrc, String slug, UUID commentUuid) {
		Share share = access.requireSession(lrc, slug);
		access.requireCapability(share.getAllowComments(), "comments");
		ShareComment comment = requireComment(share, commentUuid);
		daos.shareFeedbackDao().deleteComment(comment.getUuid());
		lrc.sendNoContent();
	}

	public void listAnnotations(LoomRoutingContext lrc, String slug) {
		Share share = access.requireSession(lrc, slug);
		UUID assetUuid = optionalAssetFilter(lrc, share);
		lrc.send(modelBuilder.toShareAnnotationList(daos.shareFeedbackDao().listAnnotations(share.getUuid(), assetUuid)));
	}

	public void createAnnotation(LoomRoutingContext lrc, String slug) {
		Share share = access.requireSession(lrc, slug);
		access.requireCapability(share.getAllowAnnotations(), "annotations");

		ShareAnnotationRequest request = lrc.requestBody(ShareAnnotationRequest.class);
		validator.validate(request);
		access.requireMembership(share, request.getAssetUuid());

		ShareAnnotation annotation = daos.shareFeedbackDao().createAnnotation(share.getUuid(), request.getAssetUuid(),
			ShareAnnotationKind.parse(request.getKind()), authorOf(share));
		applyGeometry(annotation, request);
		daos.shareFeedbackDao().storeAnnotation(annotation);
		notifyOwner(share, annotation.getAssetUuid(), annotation.getText());
		lrc.send(modelBuilder.toResponse(annotation), 201);
	}

	public void updateAnnotation(LoomRoutingContext lrc, String slug, UUID annotationUuid) {
		Share share = access.requireSession(lrc, slug);
		access.requireCapability(share.getAllowAnnotations(), "annotations");

		ShareAnnotationRequest request = lrc.requestBody(ShareAnnotationRequest.class);
		validator.validate(request);

		ShareAnnotation annotation = requireAnnotation(share, annotationUuid);
		if (!annotation.getKind().equals(request.getKind())) {
			// Turning a region into a timecode would leave the stored geometry contradicting the new kind, and the
			// database CHECK would then reject the write with a constraint name. Draw a new mark instead.
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST,
				"The kind of a mark cannot be changed. Delete it and draw a new one.");
		}
		applyGeometry(annotation, request);
		daos.shareFeedbackDao().updateAnnotation(annotation);
		lrc.send(modelBuilder.toResponse(annotation));
	}

	public void deleteAnnotation(LoomRoutingContext lrc, String slug, UUID annotationUuid) {
		Share share = access.requireSession(lrc, slug);
		access.requireCapability(share.getAllowAnnotations(), "annotations");
		ShareAnnotation annotation = requireAnnotation(share, annotationUuid);
		daos.shareFeedbackDao().deleteAnnotation(annotation.getUuid());
		lrc.sendNoContent();
	}

	public void listReactions(LoomRoutingContext lrc, String slug) {
		Share share = access.requireSession(lrc, slug);
		UUID assetUuid = optionalAssetFilter(lrc, share);
		lrc.send(modelBuilder.toShareReactionList(daos.shareFeedbackDao().listReactions(share.getUuid(), assetUuid)));
	}

	public void createReaction(LoomRoutingContext lrc, String slug) {
		Share share = access.requireSession(lrc, slug);
		access.requireCapability(share.getAllowReactions(), "reactions");

		ShareReactionRequest request = lrc.requestBody(ShareReactionRequest.class);
		validator.validate(request);

		ShareReactionType type = ShareReactionType.parse(request.getType());
		if (type == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Unknown reaction type " + request.getType());
		}

		ShareReaction reaction = daos.shareFeedbackDao().createReaction(share.getUuid(), type, authorOf(share));
		if (request.getAssetUuid() != null) {
			access.requireMembership(share, request.getAssetUuid());
			reaction.setAssetUuid(request.getAssetUuid());
		} else if (request.getCommentUuid() != null) {
			reaction.setShareCommentUuid(requireComment(share, request.getCommentUuid()).getUuid());
		} else {
			reaction.setShareAnnotationUuid(requireAnnotation(share, request.getAnnotationUuid()).getUuid());
		}
		daos.shareFeedbackDao().storeReaction(reaction);
		lrc.send(modelBuilder.toResponse(reaction), 201);
	}

	public void deleteReaction(LoomRoutingContext lrc, String slug, UUID reactionUuid) {
		Share share = access.requireSession(lrc, slug);
		access.requireCapability(share.getAllowReactions(), "reactions");
		ShareReaction reaction = daos.shareFeedbackDao().loadReaction(share.getUuid(), reactionUuid);
		if (reaction == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Reaction not found");
		}
		daos.shareFeedbackDao().deleteReaction(reaction.getUuid());
		lrc.sendNoContent();
	}

	// -------------------------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------------------------

	/**
	 * The author name written onto a piece of feedback.
	 *
	 * <p>
	 * Taken from the share row, never from the request. A name a visitor could set per comment is a name a visitor could set to somebody else's.
	 * </p>
	 */
	private String authorOf(Share share) {
		return share.getVisitorName() == null ? DEFAULT_VISITOR_NAME : share.getVisitorName();
	}

	private String normaliseName(String offered) {
		if (offered == null || offered.isBlank()) {
			return DEFAULT_VISITOR_NAME;
		}
		return offered.trim();
	}

	/**
	 * The optional {@code ?asset=} narrowing on the listing routes.
	 */
	private UUID optionalAssetFilter(LoomRoutingContext lrc, Share share) {
		List<String> values = lrc.queryParam("asset");
		if (values == null || values.isEmpty()) {
			return null;
		}
		UUID assetUuid;
		try {
			assetUuid = UUID.fromString(values.get(0));
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_QUERY_PARAMS, "The asset filter is not a valid uuid");
		}
		access.requireMembership(share, assetUuid);
		return assetUuid;
	}

	/**
	 * A comment's asset, checked for membership. Null is allowed and means the shared collection as a whole.
	 */
	private UUID requireMemberOrNull(Share share, UUID assetUuid) {
		if (assetUuid == null) {
			if (share.targetType() == ShareTargetType.ASSET) {
				// On a single-asset share there is no "the collection as a whole" to address, so an omitted asset
				// means the shared asset. Rejecting it would make the client send a uuid it already implied.
				return share.getAssetUuid();
			}
			return null;
		}
		access.requireMembership(share, assetUuid);
		return assetUuid;
	}

	private ShareComment requireComment(Share share, UUID commentUuid) {
		ShareComment comment = commentUuid == null ? null : daos.shareFeedbackDao().loadComment(share.getUuid(), commentUuid);
		if (comment == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Comment not found");
		}
		return comment;
	}

	private ShareAnnotation requireAnnotation(Share share, UUID annotationUuid) {
		ShareAnnotation annotation = annotationUuid == null ? null : daos.shareFeedbackDao().loadAnnotation(share.getUuid(), annotationUuid);
		if (annotation == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Annotation not found");
		}
		return annotation;
	}

	private void applyGeometry(ShareAnnotation annotation, ShareAnnotationRequest request) {
		annotation.setTimeFrom(request.getTimeFrom());
		annotation.setTimeTo(request.getTimeTo());
		annotation.setAreaX(request.getAreaX());
		annotation.setAreaY(request.getAreaY());
		annotation.setAreaWidth(request.getAreaWidth());
		annotation.setAreaHeight(request.getAreaHeight());
		annotation.setText(request.getText());
	}

	private boolean isDownloadRequested(LoomRoutingContext lrc) {
		List<String> values = lrc.queryParam("download");
		if (values == null || values.isEmpty()) {
			return false;
		}
		String value = values.get(0);
		return value == null || value.isEmpty() || "1".equals(value) || "true".equalsIgnoreCase(value);
	}

	private void applyTargetInfo(Share share, ShareSessionResponse response) {
		if (share.targetType() == ShareTargetType.COLLECTION) {
			Collection collection = daos.collectionDao().load(share.getCollectionUuid());
			if (collection != null) {
				response.setTargetName(collection.getName());
			}
			return;
		}
		Asset asset = daos.assetDao().load(share.getAssetUuid());
		if (asset != null) {
			response.setTargetName(asset.getFilename());
		}
	}

	/**
	 * Set the session cookie for media elements.
	 *
	 * <p>
	 * {@code SameSite=Lax} rather than {@code Strict}: the share URL is arrived at by clicking a link in an email or a chat window, and a Strict
	 * cookie is not sent on that first cross-site navigation - so the first request after opening the link would look unauthenticated. Scoped to the
	 * share routes, so it never rides along with anything else.
	 * </p>
	 */
	private void setSessionCookie(LoomRoutingContext lrc, String token) {
		lrc.routingContext().response().addCookie(
			Cookie.cookie(ShareAccessService.SESSION_COOKIE, token)
				.setHttpOnly(true)
				.setSameSite(CookieSameSite.LAX)
				.setMaxAge(ShareSessionTokens.SESSION_TTL_SECONDS)
				.setPath(ShareAccessService.SESSION_COOKIE_PATH));
	}

	/**
	 * Tell the link's owner that their customer said something.
	 *
	 * <p>
	 * A share whose creator has been deleted has nobody to notify, which is the normal end state of a link that outlived its author -
	 * {@code NotificationDispatcher} handles the null and swallows any failure of its own, so a notification problem can never cost the visitor the
	 * comment they just typed.
	 * </p>
	 */
	private void notifyOwner(Share share, UUID assetUuid, String body) {
		String targetName = share.targetType() == ShareTargetType.COLLECTION
			? nameOfCollection(share.getCollectionUuid())
			: nameOfAsset(share.getAssetUuid());
		dispatcher.shareFeedbackLeft(share.getCreatorUuid(), authorOf(share), targetName, assetUuid, body);
	}

	private String nameOfCollection(UUID collectionUuid) {
		Collection collection = collectionUuid == null ? null : daos.collectionDao().load(collectionUuid);
		return collection == null ? "a shared collection" : collection.getName();
	}

	private String nameOfAsset(UUID assetUuid) {
		Asset asset = assetUuid == null ? null : daos.assetDao().load(assetUuid);
		return asset == null ? "shared material" : asset.getFilename();
	}
}
