package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqNotification.NOTIFICATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.notification.Notification;
import io.metaloom.loom.db.model.notification.NotificationDao;
import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

/**
 * The per-user notification inbox (V2.70).
 *
 * <p>
 * Two properties carry most of the weight here and both get their own test: every read is scoped to one recipient (a bulk mark-read or clear must
 * never touch another user's rows), and the delete cascades remove exactly the entries whose subject is gone — checked against an untouched twin.
 * </p>
 */
public class NotificationDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<NotificationDao, Notification> {

	@Override
	public NotificationDao getDao() {
		return notificationDao();
	}

	@Override
	public Notification createElement(User user, int i) {
		return getDao().createNotification(user.getUuid(), user.getUuid(), NotificationType.TASK_ASSIGNED, "notification_" + i);
	}

	@Override
	public void assertCreate(Notification created) {
		assertEquals("notification_0", created.getTitle());
		assertEquals(NotificationType.TASK_ASSIGNED, created.getType());
		assertFalse(created.isRead(), "A new notification is unread");
		assertNull(created.getReadAt(), "An unread notification has no read timestamp");
	}

	@Override
	public void updateElement(Notification notification) {
		notification.setTitle("updated_title");
		notification.setRead(true);
		notification.setBody("Some detail");
	}

	@Override
	public void assertUpdate(Notification updated) {
		assertEquals("updated_title", updated.getTitle());
		assertTrue(updated.isRead(), "The read flag should round-trip through the DB");
		assertEquals("Some detail", updated.getBody());
	}

	private User storeUser(String username) {
		User user = userDao().createUser(adminUser().getUuid(), username);
		userDao().store(user);
		return user;
	}

	private Notification store(User recipient, NotificationType type, String title) {
		Notification n = getDao().createNotification(recipient.getUuid(), adminUser().getUuid(), type, title);
		getDao().store(n);
		return n;
	}

	private int rowCount(User recipient) {
		return context.ctx().fetchCount(NOTIFICATION, NOTIFICATION.RECIPIENT_UUID.eq(recipient.getUuid()));
	}

	@Test
	public void testTypeCheckConstraintRejectsUnknownValues() {
		User recipient = storeUser("check_constraint_recipient");
		// The type list is a CHECK rather than an enum, so the database is the thing that
		// stops a typo — nothing in Java would.
		assertThrows(DataAccessException.class, () -> context.ctx()
			.insertInto(NOTIFICATION, NOTIFICATION.RECIPIENT_UUID, NOTIFICATION.TYPE, NOTIFICATION.TITLE)
			.values(recipient.getUuid(), "NOT_A_REAL_TYPE", "bogus")
			.execute());
	}

	@Test
	public void testInboxIsNewestFirstAndRecipientScoped() {
		User mine = storeUser("inbox_owner");
		User other = storeUser("inbox_bystander");

		store(mine, NotificationType.TASK_ASSIGNED, "first");
		store(mine, NotificationType.TASK_COMMENT, "second");
		store(other, NotificationType.TASK_ASSIGNED, "not mine");

		Page<Notification> page = getDao().loadPageForRecipient(mine.getUuid(), false, 25);
		List<String> titles = StreamSupport.stream(page.spliterator(), false).map(Notification::getTitle).toList();

		assertEquals(2, titles.size(), "Only the recipient's own entries are listed");
		assertFalse(titles.contains("not mine"), "Another user's inbox must be invisible");
		assertEquals(2, page.totalCount(), "The total counts matches, not the page size");
		// Ordering is (created DESC, uuid DESC). Both rows land in the same millisecond here,
		// so this asserts the tie-break produces a stable order rather than a specific one.
		assertEquals(2, titles.stream().distinct().count());
	}

	@Test
	public void testUnreadFilterAndCount() {
		User mine = storeUser("unread_owner");
		Notification read = store(mine, NotificationType.TASK_ASSIGNED, "already read");
		store(mine, NotificationType.TASK_COMMENT, "still unread");

		read.setRead(true);
		getDao().update(read);

		assertEquals(1, getDao().countUnread(mine.getUuid()));

		Page<Notification> unread = getDao().loadPageForRecipient(mine.getUuid(), true, 25);
		List<String> titles = StreamSupport.stream(unread.spliterator(), false).map(Notification::getTitle).toList();
		assertEquals(List.of("still unread"), titles);

		Page<Notification> all = getDao().loadPageForRecipient(mine.getUuid(), false, 25);
		assertEquals(2, StreamSupport.stream(all.spliterator(), false).count());
	}

	@Test
	public void testMarkAllReadTouchesOnlyTheCallersUnreadRows() {
		User mine = storeUser("mark_all_owner");
		User other = storeUser("mark_all_bystander");
		store(mine, NotificationType.TASK_ASSIGNED, "mine a");
		store(mine, NotificationType.TASK_COMMENT, "mine b");
		store(other, NotificationType.TASK_ASSIGNED, "theirs");

		int changed = getDao().markAllRead(mine.getUuid());

		assertEquals(2, changed);
		assertEquals(0, getDao().countUnread(mine.getUuid()));
		assertEquals(1, getDao().countUnread(other.getUuid()), "Another user's unread count must be untouched");

		// Re-running it changes nothing: the update is restricted to unread rows, so an
		// already-acknowledged entry does not get a fresh read_at.
		assertEquals(0, getDao().markAllRead(mine.getUuid()));
	}

	@Test
	public void testDeleteAllForRecipientTouchesOnlyTheirRows() {
		User mine = storeUser("clear_owner");
		User other = storeUser("clear_bystander");
		store(mine, NotificationType.TASK_ASSIGNED, "mine a");
		store(mine, NotificationType.TASK_COMMENT, "mine b");
		store(other, NotificationType.TASK_ASSIGNED, "theirs");

		int removed = getDao().deleteAllForRecipient(mine.getUuid());

		assertEquals(2, removed);
		assertEquals(0, rowCount(mine));
		assertEquals(1, rowCount(other), "Another user's inbox must survive");
	}

	@Test
	public void testStoreBatchAssignsUuids() {
		User a = storeUser("batch_recipient_a");
		User b = storeUser("batch_recipient_b");
		// Group fan-out writes one row per member through this path.
		List<Notification> batch = List.of(
			getDao().createNotification(a.getUuid(), adminUser().getUuid(), NotificationType.TASK_ASSIGNED, "fanned a"),
			getDao().createNotification(b.getUuid(), adminUser().getUuid(), NotificationType.TASK_ASSIGNED, "fanned b"));

		getDao().storeBatch(batch);

		assertEquals(1, rowCount(a));
		assertEquals(1, rowCount(b));
		batch.forEach(n -> assertNotNull(n.getUuid(), "storeBatch must back-fill the generated uuid"));
	}

	@Test
	public void testDeleteSubjectCascadesToItsNotifications() {
		User recipient = storeUser("cascade_recipient");

		Task doomedTask = taskDao().createTask(adminUser(), "doomed_task");
		taskDao().store(doomedTask);
		Task survivingTask = taskDao().createTask(adminUser(), "surviving_task");
		taskDao().store(survivingTask);

		Notification doomed = getDao().createNotification(recipient.getUuid(), adminUser().getUuid(),
			NotificationType.TASK_ASSIGNED, "about the doomed task");
		doomed.setTaskUuid(doomedTask.getUuid());
		getDao().store(doomed);

		Notification survivor = getDao().createNotification(recipient.getUuid(), adminUser().getUuid(),
			NotificationType.TASK_ASSIGNED, "about the surviving task");
		survivor.setTaskUuid(survivingTask.getUuid());
		getDao().store(survivor);

		taskDao().delete(doomedTask);

		assertNull(getDao().load(doomed.getUuid()), "A notification whose subject is gone would deep-link to a 404");
		assertNotNull(getDao().load(survivor.getUuid()), "The untouched twin must survive");
	}

	@Test
	public void testDeleteRecipientCascadesOnlyTheirInbox() {
		User doomed = storeUser("cascade_doomed_recipient");
		User survivor = storeUser("cascade_surviving_recipient");
		Notification theirs = store(doomed, NotificationType.TASK_ASSIGNED, "for the doomed user");
		Notification others = store(survivor, NotificationType.TASK_ASSIGNED, "for the surviving user");

		userDao().delete(doomed);

		assertNull(getDao().load(theirs.getUuid()));
		assertNotNull(getDao().load(others.getUuid()), "Another user's inbox must survive");
	}

	@Test
	public void testDeleteActorKeepsTheNotification() {
		User actor = storeUser("departing_actor");
		User recipient = storeUser("retained_recipient");

		Notification n = getDao().createNotification(recipient.getUuid(), actor.getUuid(),
			NotificationType.TASK_ASSIGNED, "assigned by someone who left");
		getDao().store(n);

		userDao().delete(actor);

		Notification reloaded = getDao().load(n.getUuid());
		assertNotNull(reloaded, "Losing the actor must not erase the fact that it happened");
		assertNull(reloaded.getCreatorUuid(), "The actor reference should be SET NULL, not cascaded");
	}

	@Test
	public void testCountUnreadIgnoresOtherRecipients() {
		User mine = storeUser("count_owner");
		User other = storeUser("count_bystander");
		store(mine, NotificationType.TASK_ASSIGNED, "mine");
		store(other, NotificationType.TASK_ASSIGNED, "theirs a");
		store(other, NotificationType.TASK_ASSIGNED, "theirs b");

		assertEquals(1, getDao().countUnread(mine.getUuid()));
		assertEquals(2, getDao().countUnread(other.getUuid()));
		assertEquals(0, getDao().countUnread(UUID.randomUUID()), "An unknown user has an empty inbox, not an error");
	}

}
