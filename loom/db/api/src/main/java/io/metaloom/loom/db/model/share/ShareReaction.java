package io.metaloom.loom.db.model.share;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.Element;

/**
 * A share visitor reacting to an asset, a guest comment or a guest annotation.
 *
 * <p>
 * Exactly one subject is set, as in {@code reaction}. Uniqueness is {@code (share_uuid, visitor_id, type, subject)} (V2.120) - the link and the visitor
 * holding it stand in for the creator, so two people reviewing through one link each get their own "approve".
 * </p>
 */
public interface ShareReaction extends Element<ShareReaction> {

	UUID getShareUuid();

	ShareReaction setShareUuid(UUID shareUuid);

	UUID getAssetUuid();

	ShareReaction setAssetUuid(UUID assetUuid);

	UUID getShareCommentUuid();

	ShareReaction setShareCommentUuid(UUID shareCommentUuid);

	UUID getShareAnnotationUuid();

	ShareReaction setShareAnnotationUuid(UUID shareAnnotationUuid);

	String getType();

	ShareReaction setType(String type);

	default ShareReactionType type() {
		return ShareReactionType.parse(getType());
	}

	default ShareReaction setType(ShareReactionType type) {
		return setType(type == null ? null : type.name());
	}

	/** The visitor name as it stood when this was written. See {@link ShareAnnotation#getAuthorName()}. */
	String getAuthorName();

	ShareReaction setAuthorName(String authorName);

	/**
	 * The share visitor who wrote this, from the signed session token (V2.120). Only that visitor may change or delete it through the link. Null on
	 * rows predating V2.120, which no visitor may change.
	 */
	UUID getVisitorId();

	ShareReaction setVisitorId(UUID visitorId);

	Instant getCreated();

	ShareReaction setCreated(Instant created);
}
