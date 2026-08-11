package io.metaloom.loom.db.model.share;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.Element;

/**
 * A share visitor reacting to an asset, a guest comment or a guest annotation.
 *
 * <p>
 * Exactly one subject is set, as in {@code reaction}. Uniqueness is {@code (share_uuid, type, subject)} - the share stands in for the creator,
 * because identity here is the link rather than a person.
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

	Instant getCreated();

	ShareReaction setCreated(Instant created);
}
