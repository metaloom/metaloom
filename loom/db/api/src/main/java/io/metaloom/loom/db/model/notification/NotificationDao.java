package io.metaloom.loom.db.model.notification;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.page.Page;

public interface NotificationDao extends CRUDDao<Notification> {

	/**
	 * Build (but do not store) a notification for one recipient.
	 *
	 * @param actorUuid whoever caused the event, or null for a machine-generated one
	 */
	Notification createNotification(UUID recipientUuid, UUID actorUuid, NotificationType type, String title);

	/**
	 * Page over one user's inbox, newest first.
	 *
	 * <p>
	 * Deliberately bespoke rather than reusing the generic {@code loadPage}: {@code AbstractJooqDao.getField(SortKey)} casts every sort column to
	 * {@code Field<UUID>}, so ordering by {@code created} through the generic path would throw a {@link ClassCastException}. Ordering is
	 * {@code (created DESC, uuid DESC)} — the uuid tie-breaks so a burst of notifications written in the same millisecond pages deterministically.
	 * </p>
	 *
	 * @param unreadOnly restrict to unread entries, which is what the bell's badge list wants
	 */
	Page<Notification> loadPageForRecipient(UUID recipientUuid, boolean unreadOnly, int pageSize);

	/** How many unread entries the recipient has. Backed by a partial index, so this stays cheap as the archive grows. */
	long countUnread(UUID recipientUuid);

	/**
	 * Mark every unread entry of one recipient as read.
	 *
	 * @return how many rows changed
	 */
	int markAllRead(UUID recipientUuid);

	/**
	 * Delete every entry of one recipient.
	 *
	 * @return how many rows were removed
	 */
	int deleteAllForRecipient(UUID recipientUuid);

	/**
	 * Store several notifications in one statement.
	 *
	 * <p>
	 * Group fan-out writes one row per member; doing that as N round trips would make assigning a task to a large group visibly slow.
	 * </p>
	 */
	void storeBatch(List<Notification> notifications);

}
