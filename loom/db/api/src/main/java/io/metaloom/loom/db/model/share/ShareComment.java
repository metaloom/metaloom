package io.metaloom.loom.db.model.share;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.Element;

/**
 * A comment left through a share link by somebody with no Loom account.
 *
 * <p>
 * Kept apart from {@code comment} for three reasons, in order of how blocking they are: that table's {@code creator_uuid} is a NOT NULL foreign key
 * to {@code user}; an outside party's opinion is a different kind of statement from a colleague's note and must stay visibly separate wherever it
 * surfaces; and this text is attacker-controllable by anyone holding the link, so it must never be fed to the chat agent as trusted input.
 * </p>
 */
public interface ShareComment extends Element<ShareComment> {

	UUID getShareUuid();

	ShareComment setShareUuid(UUID shareUuid);

	/** Null means the comment is about the shared collection as a whole rather than one of its members. */
	UUID getAssetUuid();

	ShareComment setAssetUuid(UUID assetUuid);

	/** The comment this one replies to. One level deep; see {@code commentThread.ts}. */
	UUID getParentUuid();

	ShareComment setParentUuid(UUID parentUuid);

	/** The mark on the media this comment belongs to, or null when it stands alone. */
	UUID getShareAnnotationUuid();

	ShareComment setShareAnnotationUuid(UUID shareAnnotationUuid);

	String getText();

	ShareComment setText(String text);

	/** The visitor name as it stood when this was written. See {@link ShareAnnotation#getAuthorName()}. */
	String getAuthorName();

	ShareComment setAuthorName(String authorName);

	Instant getCreated();

	ShareComment setCreated(Instant created);

	Instant getEdited();

	ShareComment setEdited(Instant edited);
}
