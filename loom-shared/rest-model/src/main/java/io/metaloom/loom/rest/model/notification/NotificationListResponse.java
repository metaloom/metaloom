package io.metaloom.loom.rest.model.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class NotificationListResponse extends AbstractListResponse<NotificationListResponse, NotificationResponse> {

	@JsonProperty(required = true)
	@JsonPropertyDescription("How many unread entries the caller has in total, regardless of paging or the unread filter.")
	private long unreadCount;

	/**
	 * The authoritative unread count for the bell badge.
	 *
	 * <p>
	 * Carried on the list rather than derived client-side because the badge must count the whole inbox, not the page — and because an
	 * {@code ?unread=true} list would otherwise make the badge equal to the page size.
	 * </p>
	 */
	public long getUnreadCount() {
		return unreadCount;
	}

	public NotificationListResponse setUnreadCount(long unreadCount) {
		this.unreadCount = unreadCount;
		return this;
	}

	@Override
	public NotificationListResponse self() {
		return this;
	}

}
