package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.notification.Notification;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.notification.NotificationListResponse;
import io.metaloom.loom.rest.model.notification.NotificationResponse;

public interface NotificationModelBuilder extends ModelBuilder, UserModelBuilder {

	default NotificationResponse toResponse(Notification notification) {
		NotificationResponse response = new NotificationResponse();
		response.setUuid(notification.getUuid());
		response.setType(notification.getType());
		response.setRead(notification.isRead());
		response.setReadAt(notification.getReadAt());
		response.setTitle(notification.getTitle());
		response.setBody(notification.getBody());
		response.setTaskUuid(notification.getTaskUuid());
		response.setCommentUuid(notification.getCommentUuid());
		response.setPipelineRunUuid(notification.getPipelineRunUuid());
		response.setAssetUuid(notification.getAssetUuid());
		response.setViaGroupUuid(notification.getViaGroupUuid());
		response.setMeta(notification.getMeta());
		// status.creator carries the ACTOR. A machine-generated notification has none, and
		// setStatus tolerates that - the UI renders an absent actor as "someone".
		setStatus(notification, response);
		return response;
	}

	/**
	 * @param unreadCount the caller's total unread count, which is not derivable from the page
	 */
	default NotificationListResponse toNotificationList(Page<Notification> page, long unreadCount) {
		NotificationListResponse response = setPage(new NotificationListResponse(), page, this::toResponse);
		response.setUnreadCount(unreadCount);
		return response;
	}
}
