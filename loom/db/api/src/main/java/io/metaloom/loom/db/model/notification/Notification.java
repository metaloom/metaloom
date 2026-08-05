package io.metaloom.loom.db.model.notification;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

/**
 * One entry in one user's inbox.
 *
 * <p>
 * A notification addressed to a group does not exist: dispatch resolves the group's members and writes one row each, so
 * {@link #getRecipientUuid()} is always a concrete user. {@link #getViaGroupUuid()} records how they were reached and is explanatory only.
 * </p>
 *
 * <p>
 * The inherited {@code creatorUuid} is the <b>actor</b> — whoever did the thing being reported — not the recipient. It is nullable, because a
 * machine-generated event such as {@link NotificationType#PIPELINE_RUN_FAILED} has no acting user.
 * </p>
 */
public interface Notification extends CUDElement<Notification>, MetaElement<Notification> {

	UUID getRecipientUuid();

	Notification setRecipientUuid(UUID recipientUuid);

	NotificationType getType();

	Notification setType(NotificationType type);

	boolean isRead();

	Notification setRead(boolean read);

	Instant getReadAt();

	Notification setReadAt(Instant readAt);

	/** Pre-rendered summary line, composed at dispatch so the inbox need not re-resolve a deleted subject at read time. */
	String getTitle();

	Notification setTitle(String title);

	String getBody();

	Notification setBody(String body);

	UUID getTaskUuid();

	Notification setTaskUuid(UUID taskUuid);

	UUID getCommentUuid();

	Notification setCommentUuid(UUID commentUuid);

	UUID getPipelineRunUuid();

	Notification setPipelineRunUuid(UUID pipelineRunUuid);

	UUID getAssetUuid();

	Notification setAssetUuid(UUID assetUuid);

	/** The group the recipient was reached through, or null when they were named directly. */
	UUID getViaGroupUuid();

	Notification setViaGroupUuid(UUID viaGroupUuid);

}
