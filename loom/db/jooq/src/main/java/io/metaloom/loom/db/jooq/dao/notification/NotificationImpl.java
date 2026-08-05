package io.metaloom.loom.db.jooq.dao.notification;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.notification.Notification;
import io.metaloom.loom.api.notification.NotificationType;

public class NotificationImpl extends AbstractEditableElement<Notification> implements Notification {

	private UUID recipientUuid;

	private NotificationType type;

	private boolean read;

	private Instant readAt;

	private String title;

	private String body;

	private UUID taskUuid;

	private UUID commentUuid;

	private UUID pipelineRunUuid;

	private UUID assetUuid;

	private UUID viaGroupUuid;

	@Override
	public UUID getRecipientUuid() {
		return recipientUuid;
	}

	@Override
	public Notification setRecipientUuid(UUID recipientUuid) {
		this.recipientUuid = recipientUuid;
		return this;
	}

	@Override
	public NotificationType getType() {
		return type;
	}

	@Override
	public Notification setType(NotificationType type) {
		this.type = type;
		return this;
	}

	@Override
	public boolean isRead() {
		return read;
	}

	@Override
	public Notification setRead(boolean read) {
		this.read = read;
		return this;
	}

	@Override
	public Instant getReadAt() {
		return readAt;
	}

	@Override
	public Notification setReadAt(Instant readAt) {
		this.readAt = readAt;
		return this;
	}

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public Notification setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getBody() {
		return body;
	}

	@Override
	public Notification setBody(String body) {
		this.body = body;
		return this;
	}

	@Override
	public UUID getTaskUuid() {
		return taskUuid;
	}

	@Override
	public Notification setTaskUuid(UUID taskUuid) {
		this.taskUuid = taskUuid;
		return this;
	}

	@Override
	public UUID getCommentUuid() {
		return commentUuid;
	}

	@Override
	public Notification setCommentUuid(UUID commentUuid) {
		this.commentUuid = commentUuid;
		return this;
	}

	@Override
	public UUID getPipelineRunUuid() {
		return pipelineRunUuid;
	}

	@Override
	public Notification setPipelineRunUuid(UUID pipelineRunUuid) {
		this.pipelineRunUuid = pipelineRunUuid;
		return this;
	}

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public Notification setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public UUID getViaGroupUuid() {
		return viaGroupUuid;
	}

	@Override
	public Notification setViaGroupUuid(UUID viaGroupUuid) {
		this.viaGroupUuid = viaGroupUuid;
		return this;
	}

}
