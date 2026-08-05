package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.model.notification.NotificationListResponse;
import io.metaloom.loom.rest.model.notification.NotificationResponse;
import io.metaloom.loom.rest.model.notification.NotificationUpdateRequest;

/**
 * The caller's notification inbox.
 *
 * <p>
 * Every method operates on the authenticated user's own entries. There is no create method: notifications are dispatched server-side, and a client
 * that could post one could forge a message from anybody.
 * </p>
 */
public interface NotificationMethods {

	LoomClientRequest<NotificationListResponse> listNotifications();

	/**
	 * List only the unread entries. The response still carries the caller's full unread count.
	 */
	LoomClientRequest<NotificationListResponse> listUnreadNotifications();

	LoomClientRequest<NotificationResponse> loadNotification(UUID notificationUuid);

	LoomClientRequest<NotificationResponse> updateNotification(UUID notificationUuid, NotificationUpdateRequest request);

	default LoomClientRequest<NotificationResponse> markNotificationRead(UUID notificationUuid) {
		return updateNotification(notificationUuid, new NotificationUpdateRequest().setRead(true));
	}

	LoomClientRequest<GenericMessageResponse> markAllNotificationsRead();

	LoomClientRequest<NoResponse> deleteNotification(UUID notificationUuid);

	/** Clear the whole inbox. The retention escape hatch — nothing prunes it automatically. */
	LoomClientRequest<NoResponse> clearNotifications();

}
