package io.metaloom.loom.rest.model.notification;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

/**
 * One entry in the caller's inbox.
 *
 * <p>
 * The inherited {@code status.creator} is the <b>actor</b> — whoever did the thing being reported — not the recipient. The recipient is always the
 * caller, so it is deliberately absent from the wire: a client has no use for it and echoing it back invites trusting it.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationResponse extends AbstractCreatorEditorRestResponse<NotificationResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("What happened.")
	private NotificationType type;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Whether the recipient has marked this entry as read.")
	private boolean read;

	@JsonProperty(required = false)
	@JsonPropertyDescription("ISO8601 timestamp of when the entry was marked read. Absent while unread.")
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssX")
	private Instant readAt;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Pre-rendered summary line.")
	private String title;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Longer detail text.")
	private String body;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuid of the task this entry is about, when it is about one.")
	private UUID taskUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuid of the comment this entry is about, when it is about one.")
	private UUID commentUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuid of the pipeline run this entry is about, when it is about one.")
	private UUID pipelineRunUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuid of the asset this entry is about, when it is about one.")
	private UUID assetUuid;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuid of the group the recipient was reached through, when it was not a direct mention.")
	private UUID viaGroupUuid;

	public NotificationType getType() {
		return type;
	}

	public NotificationResponse setType(NotificationType type) {
		this.type = type;
		return this;
	}

	public boolean isRead() {
		return read;
	}

	public NotificationResponse setRead(boolean read) {
		this.read = read;
		return this;
	}

	public Instant getReadAt() {
		return readAt;
	}

	public NotificationResponse setReadAt(Instant readAt) {
		this.readAt = readAt;
		return this;
	}

	public String getTitle() {
		return title;
	}

	public NotificationResponse setTitle(String title) {
		this.title = title;
		return this;
	}

	public String getBody() {
		return body;
	}

	public NotificationResponse setBody(String body) {
		this.body = body;
		return this;
	}

	public UUID getTaskUuid() {
		return taskUuid;
	}

	public NotificationResponse setTaskUuid(UUID taskUuid) {
		this.taskUuid = taskUuid;
		return this;
	}

	public UUID getCommentUuid() {
		return commentUuid;
	}

	public NotificationResponse setCommentUuid(UUID commentUuid) {
		this.commentUuid = commentUuid;
		return this;
	}

	public UUID getPipelineRunUuid() {
		return pipelineRunUuid;
	}

	public NotificationResponse setPipelineRunUuid(UUID pipelineRunUuid) {
		this.pipelineRunUuid = pipelineRunUuid;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public NotificationResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public UUID getViaGroupUuid() {
		return viaGroupUuid;
	}

	public NotificationResponse setViaGroupUuid(UUID viaGroupUuid) {
		this.viaGroupUuid = viaGroupUuid;
		return this;
	}

	@Override
	public NotificationResponse self() {
		return this;
	}

}
