package io.metaloom.loom.rest.model.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.rest.model.RestModel;

/**
 * A notification frame on the multiplexed UI events socket.
 *
 * <p>
 * Discriminated by {@code channel: "NOTIFICATION"}, exactly like {@code ProcessorEventMessage} and {@code NodeRegistryEventMessage}. Unlike those
 * two, this channel is <b>addressed to a single user</b> — see
 * {@code PipelineEventBroadcaster#broadcastNotification}.
 * </p>
 *
 * <p>
 * The recipient uuid is deliberately <b>not</b> on the wire. It is routing metadata that the server already used to pick this socket; putting it in
 * the frame would invite a client to trust it, and it tells the recipient nothing they do not already know.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationEventMessage implements RestModel {

	public static final String CHANNEL = "NOTIFICATION";

	@JsonProperty(required = true)
	@JsonPropertyDescription("Multiplexing channel discriminator; always 'NOTIFICATION' for notification events")
	private String channel = CHANNEL;

	@JsonProperty(required = true)
	@JsonPropertyDescription("What happened.")
	private NotificationType type;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The full notification, so a client can insert it without a refetch.")
	private NotificationResponse notification;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The recipient's total unread count after this notification, so the badge is authoritative.")
	private long unreadCount;

	public String getChannel() {
		return channel;
	}

	public NotificationEventMessage setChannel(String channel) {
		this.channel = channel;
		return this;
	}

	public NotificationType getType() {
		return type;
	}

	public NotificationEventMessage setType(NotificationType type) {
		this.type = type;
		return this;
	}

	public NotificationResponse getNotification() {
		return notification;
	}

	public NotificationEventMessage setNotification(NotificationResponse notification) {
		this.notification = notification;
		return this;
	}

	public long getUnreadCount() {
		return unreadCount;
	}

	public NotificationEventMessage setUnreadCount(long unreadCount) {
		this.unreadCount = unreadCount;
		return this;
	}

}
