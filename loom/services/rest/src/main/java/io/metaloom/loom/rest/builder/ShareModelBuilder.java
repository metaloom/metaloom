package io.metaloom.loom.rest.builder;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetImageComp;
import io.metaloom.loom.db.model.asset.AssetVideoComp;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.share.Share;
import io.metaloom.loom.db.model.share.ShareAnnotation;
import io.metaloom.loom.db.model.share.ShareComment;
import io.metaloom.loom.db.model.share.ShareReaction;
import io.metaloom.loom.db.model.share.ShareTargetType;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.share.ShareAnnotationListResponse;
import io.metaloom.loom.rest.model.share.ShareAnnotationResponse;
import io.metaloom.loom.rest.model.share.ShareCommentListResponse;
import io.metaloom.loom.rest.model.share.ShareCommentResponse;
import io.metaloom.loom.rest.model.share.ShareFeedbackResponse;
import io.metaloom.loom.rest.model.share.ShareListResponse;
import io.metaloom.loom.rest.model.share.ShareReactionListResponse;
import io.metaloom.loom.rest.model.share.ShareReactionResponse;
import io.metaloom.loom.rest.model.share.ShareResponse;
import io.metaloom.loom.rest.model.share.SharedAssetListResponse;
import io.metaloom.loom.rest.model.share.SharedAssetResponse;
import io.vertx.core.json.JsonObject;

public interface ShareModelBuilder extends ModelBuilder, UserModelBuilder {

	/**
	 * Where the customer view lives, relative to the server root. Kept next to the builder rather than in the UI, because a link that is copied out of
	 * this application and pasted into an email has to keep working - which makes it part of the API contract, not a routing detail.
	 */
	String SHARE_UI_PATH = "/ui/share/";

	/**
	 * The {@code schema_type} of the JSON component carrying authored metadata, and the two Dublin Core keys inside it that a customer should see.
	 */
	String METADATA_SCHEMA_TYPE = "metadata";

	default ShareResponse toResponse(Share share) {
		ShareResponse response = new ShareResponse();
		response.setUuid(share.getUuid());
		response.setSlug(share.getSlug());
		// Relative by default. The endpoint service replaces it with an absolute URL derived from the request,
		// because only the request knows which host the caller reached this server on.
		response.setUrl(SHARE_UI_PATH + share.getSlug());
		response.setTargetType(share.getTargetType());
		response.setTargetUuid(share.getTargetUuid());
		response.setTargetName(targetNameOf(share));
		// The hash is never rendered. `password` stays null here and is filled in by the service only on the one
		// response that carries a freshly generated password.
		response.setPasswordProtected(share.isPasswordProtected());
		response.setExpired(share.isExpired());
		response.setExpiresAt(share.getExpiresAt());
		response.setAllowDownload(share.getAllowDownload());
		response.setShowMetadata(share.getShowMetadata());
		response.setAllowComments(share.getAllowComments());
		response.setAllowReactions(share.getAllowReactions());
		response.setAllowAnnotations(share.getAllowAnnotations());
		response.setVisitorName(share.getVisitorName());
		response.setFirstVisitedAt(share.getFirstVisitedAt());
		response.setLastViewedAt(share.getLastViewedAt());
		response.setViewCount(share.getViewCount());
		response.setFeedbackCount(feedbackCountOf(share));
		response.setMeta(share.getMeta());
		setStatus(share, response);
		return response;
	}

	default ShareListResponse toShareList(Page<Share> page) {
		return setPage(new ShareListResponse(), page, this::toResponse);
	}

	/**
	 * The name of whatever the share points at, so a list of links reads without a second request per row.
	 */
	private String targetNameOf(Share share) {
		if (share.targetType() == ShareTargetType.COLLECTION && share.getCollectionUuid() != null) {
			Collection collection = daos().collectionDao().load(share.getCollectionUuid());
			return collection == null ? null : collection.getName();
		}
		if (share.getAssetUuid() != null) {
			Asset asset = daos().assetDao().load(share.getAssetUuid());
			return asset == null ? null : asset.getFilename();
		}
		return null;
	}

	private Integer feedbackCountOf(Share share) {
		long total = daos().shareFeedbackDao().countComments(share.getUuid())
			+ daos().shareFeedbackDao().countAnnotations(share.getUuid())
			+ daos().shareFeedbackDao().countReactions(share.getUuid());
		return (int) total;
	}

	/**
	 * One asset as a share visitor sees it.
	 *
	 * <p>
	 * Built field by field from a hand-picked list rather than by reusing {@code toResponse(Asset)}. See {@link SharedAssetResponse} - the point is
	 * that a field added to the internal model must not become public by default.
	 * </p>
	 *
	 * @param asset
	 *            the asset
	 * @param showMetadata
	 *            when false, everything below the mime type is withheld: the visitor can still view and play the file, and that is all
	 */
	default SharedAssetResponse toSharedAssetResponse(Asset asset, boolean showMetadata) {
		SharedAssetResponse response = new SharedAssetResponse();
		response.setUuid(asset.getUuid());
		response.setFilename(asset.getFilename());
		response.setMimeType(asset.getMimeType());
		if (!showMetadata) {
			return response;
		}
		response.setSize(asset.getSize());
		response.setCreated(asset.getCreated());

		// Dimensions and duration come from whichever component describes this kind of media. Video first: a file
		// carrying both (a video with an extracted poster frame) is a video to the person watching it.
		List<AssetVideoComp> videoComps = daos().assetComponentDao().loadVideoComps(asset.getUuid());
		if (!videoComps.isEmpty()) {
			AssetVideoComp comp = videoComps.get(0);
			response.setWidth(comp.getMediaWidth());
			response.setHeight(comp.getMediaHeight());
			response.setDuration(comp.getMediaDuration() == null ? null : comp.getMediaDuration().doubleValue());
		} else {
			List<AssetImageComp> imageComps = daos().assetComponentDao().loadImageComps(asset.getUuid());
			if (!imageComps.isEmpty()) {
				AssetImageComp comp = imageComps.get(0);
				response.setWidth(comp.getMediaWidth());
				response.setHeight(comp.getMediaHeight());
			}
		}

		applyAuthoredMetadata(asset, response);
		return response;
	}

	/**
	 * Title and description, when the metadata node has extracted them.
	 *
	 * <p>
	 * Only the two authored Dublin Core fields are read. The same component also holds camera settings, GPS coordinates, the rights holder and the
	 * creator's name - none of which a client reviewing a cut asked for, and some of which the owner would be startled to find published by a link
	 * they created to show one video.
	 * </p>
	 */
	private void applyAuthoredMetadata(Asset asset, SharedAssetResponse response) {
		var comp = daos().assetComponentDao().loadJsonComp(asset.getUuid(), null, METADATA_SCHEMA_TYPE, null);
		if (comp == null || comp.getData() == null) {
			return;
		}
		JsonObject dc = comp.getData().getJsonObject("dc");
		if (dc == null) {
			return;
		}
		response.setTitle(dc.getString("title"));
		response.setDescription(dc.getString("description"));
	}

	default SharedAssetListResponse toSharedAssetList(Page<Asset> page, boolean showMetadata) {
		SharedAssetListResponse response = setPage(new SharedAssetListResponse(), page, asset -> toSharedAssetResponse(asset, showMetadata));
		if (response.getData() == null) {
			// An emptied collection is an ordinary state for a share, not an edge case - the viewer renders "nothing
			// here yet" rather than failing to find the array.
			response.setData(java.util.List.of());
		}
		return response;
	}

	default ShareCommentResponse toResponse(ShareComment comment) {
		ShareCommentResponse response = new ShareCommentResponse();
		response.setUuid(comment.getUuid());
		response.setAssetUuid(comment.getAssetUuid());
		response.setParentUuid(comment.getParentUuid());
		response.setAnnotationUuid(comment.getShareAnnotationUuid());
		response.setText(comment.getText());
		response.setAuthorName(comment.getAuthorName());
		response.setCreated(comment.getCreated());
		response.setEdited(comment.getEdited());
		return response;
	}

	default ShareCommentListResponse toShareCommentList(List<ShareComment> comments) {
		ShareCommentListResponse response = new ShareCommentListResponse();
		// setData rather than repeated add(): a link nobody has commented on yet is the ordinary starting state, and
		// add() only creates the list on first use - so an empty listing would answer with no `data` array at all and
		// every caller would have to special-case it. See AbstractListResponse#setData.
		response.setData(comments.stream().map(this::toResponse).toList());
		setUnpagedInfo(response, comments.size());
		return response;
	}

	default ShareAnnotationResponse toResponse(ShareAnnotation annotation) {
		ShareAnnotationResponse response = new ShareAnnotationResponse();
		response.setUuid(annotation.getUuid());
		response.setAssetUuid(annotation.getAssetUuid());
		response.setKind(annotation.getKind());
		response.setTimeFrom(annotation.getTimeFrom());
		response.setTimeTo(annotation.getTimeTo());
		response.setAreaX(annotation.getAreaX());
		response.setAreaY(annotation.getAreaY());
		response.setAreaWidth(annotation.getAreaWidth());
		response.setAreaHeight(annotation.getAreaHeight());
		response.setText(annotation.getText());
		response.setAuthorName(annotation.getAuthorName());
		response.setCreated(annotation.getCreated());
		response.setEdited(annotation.getEdited());
		return response;
	}

	default ShareAnnotationListResponse toShareAnnotationList(List<ShareAnnotation> annotations) {
		ShareAnnotationListResponse response = new ShareAnnotationListResponse();
		response.setData(annotations.stream().map(this::toResponse).toList());
		setUnpagedInfo(response, annotations.size());
		return response;
	}

	default ShareReactionResponse toResponse(ShareReaction reaction) {
		ShareReactionResponse response = new ShareReactionResponse();
		response.setUuid(reaction.getUuid());
		response.setType(reaction.getType());
		response.setAssetUuid(reaction.getAssetUuid());
		response.setCommentUuid(reaction.getShareCommentUuid());
		response.setAnnotationUuid(reaction.getShareAnnotationUuid());
		response.setAuthorName(reaction.getAuthorName());
		response.setCreated(reaction.getCreated());
		return response;
	}

	default ShareReactionListResponse toShareReactionList(List<ShareReaction> reactions) {
		ShareReactionListResponse response = new ShareReactionListResponse();
		response.setData(reactions.stream().map(this::toResponse).toList());
		setUnpagedInfo(response, reactions.size());
		return response;
	}

	/**
	 * Everything one visitor said, for the owner's review panel.
	 */
	default ShareFeedbackResponse toShareFeedback(Share share) {
		UUID shareUuid = share.getUuid();
		ShareFeedbackResponse response = new ShareFeedbackResponse();
		response.setUuid(shareUuid);
		response.setVisitorName(share.getVisitorName());
		daos().shareFeedbackDao().listComments(shareUuid, null).forEach(c -> response.getComments().add(toResponse(c)));
		daos().shareFeedbackDao().listAnnotations(shareUuid, null).forEach(a -> response.getAnnotations().add(toResponse(a)));
		daos().shareFeedbackDao().listReactions(shareUuid, null).forEach(r -> response.getReactions().add(toResponse(r)));
		return response;
	}

	/**
	 * Fill the paging envelope for a list that is deliberately not paged.
	 *
	 * <p>
	 * The guest feedback lists are bounded by what one person typed into one link, so they are returned whole. The envelope is still populated - every
	 * list response in this API carries one, and a client that reads {@code totalCount} should not have to know which routes page and which do not.
	 * </p>
	 */
	private void setUnpagedInfo(io.metaloom.loom.rest.model.common.AbstractListResponse<?, ?> response, int size) {
		io.metaloom.loom.rest.model.common.PagingInfo info = new io.metaloom.loom.rest.model.common.PagingInfo();
		info.setPerPage((long) size);
		info.setTotalCount((long) size);
		response.setMetainfo(info);
	}
}
