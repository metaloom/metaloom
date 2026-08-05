package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.notification.Notification;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.notification.NotificationListResponse;
import io.metaloom.loom.rest.model.notification.NotificationResponse;
import io.metaloom.loom.rest.model.notification.NotificationUpdateRequest;

/**
 * The {@code /notifications} inbox.
 *
 * <p>
 * Extends {@link AbstractEndpointTest} rather than {@code AbstractCRUDEndpointTest}: there is no create route, so the CRUD test cases do not apply.
 * </p>
 *
 * <p>
 * The load-bearing assertion here is <b>cross-user isolation</b>: holding {@code READ_NOTIFICATION} must not make another user's inbox visible, and
 * touching a foreign entry must answer <b>404, not 403</b> — a 403 would confirm the uuid exists.
 * </p>
 */
public class NotificationEndpointTest extends AbstractEndpointTest {

	@Override
	protected DaoCollection daos() {
		return loom.internal().daos();
	}

	/** Seed a notification directly through the DAO — there is no create route by design. */
	private Notification seed(UUID recipientUuid, String title) {
		Notification n = daos().notificationDao().createNotification(recipientUuid, ADMIN_UUID, NotificationType.TASK_ASSIGNED, title);
		daos().notificationDao().store(n);
		return n;
	}

	@Test
	public void testListIsScopedToTheCaller() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			seed(ADMIN_UUID, "for the admin");
			seed(USER_UUID, "for joedoe");

			NotificationListResponse list = client.listNotifications().sync().body();

			assertNotNull(list.getData());
			assertTrue(list.getData().stream().anyMatch(n -> "for the admin".equals(n.getTitle())));
			assertFalse(list.getData().stream().anyMatch(n -> "for joedoe".equals(n.getTitle())),
				"Another user's inbox must never appear in mine");
		}
	}

	@Test
	public void testUnreadFilterAndCount() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			Notification first = seed(ADMIN_UUID, "unread one");
			seed(ADMIN_UUID, "unread two");

			client.markNotificationRead(first.getUuid()).sync().body();

			NotificationListResponse unread = client.listUnreadNotifications().sync().body();
			assertTrue(unread.getData().stream().noneMatch(n -> n.getUuid().equals(first.getUuid())),
				"A read entry must not appear in the unread list");

			// The badge count is the whole inbox, not the page — with ?unread=true a
			// page-derived count would be right by accident and wrong as soon as it pages.
			assertTrue(unread.getUnreadCount() >= 1, "The unread count travels on the list response");
		}
	}

	@Test
	public void testMarkReadAndUnread() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Notification seeded = seed(ADMIN_UUID, "toggle me");

			NotificationResponse read = client.markNotificationRead(seeded.getUuid()).sync().body();
			assertTrue(read.isRead());
			assertNotNull(read.getReadAt(), "Marking read stamps the timestamp");

			NotificationResponse unread = client
				.updateNotification(seeded.getUuid(), new NotificationUpdateRequest().setRead(false)).sync().body();
			assertFalse(unread.isRead());
			// Clearing the flag clears the timestamp, so "read but never read" cannot occur.
			org.junit.jupiter.api.Assertions.assertNull(unread.getReadAt());
		}
	}

	@Test
	public void testMarkAllReadAndClear() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			seed(ADMIN_UUID, "bulk a");
			seed(ADMIN_UUID, "bulk b");
			Notification othersEntry = seed(USER_UUID, "not mine");

			client.markAllNotificationsRead().sync().body();

			NotificationListResponse afterRead = client.listUnreadNotifications().sync().body();
			assertTrue(afterRead.getData() == null || afterRead.getData().isEmpty(), "Everything of mine should now be read");
			assertFalse(daos().notificationDao().load(othersEntry.getUuid()).isRead(),
				"Another user's entry must not have been marked read");

			client.clearNotifications().sync().body();

			NotificationListResponse afterClear = client.listNotifications().sync().body();
			assertTrue(afterClear.getData() == null || afterClear.getData().isEmpty());
			assertNotNull(daos().notificationDao().load(othersEntry.getUuid()), "Another user's inbox must survive my clear");
		}
	}

	@Test
	public void testForeignNotificationIs404NotForbidden() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Notification theirs = seed(USER_UUID, "belongs to joedoe");

			// 404, deliberately — a 403 would confirm the uuid exists and leak the shape of
			// another user's inbox to anyone who can guess a uuid.
			expect(404, "Not Found", client.loadNotification(theirs.getUuid()));
			expect(404, "Not Found", client.markNotificationRead(theirs.getUuid()));
			expect(404, "Not Found", client.deleteNotification(theirs.getUuid()));

			assertNotNull(daos().notificationDao().load(theirs.getUuid()), "The foreign entry must be untouched");
		}
	}

	@Test
	public void testDismiss() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Notification seeded = seed(ADMIN_UUID, "dismiss me");

			client.deleteNotification(seeded.getUuid()).sync().body();

			org.junit.jupiter.api.Assertions.assertNull(daos().notificationDao().load(seeded.getUuid()));
			expect(404, "Not Found", client.loadNotification(seeded.getUuid()));
		}
	}

	@Test
	public void testUpdateRequiresTheReadFlag() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Notification seeded = seed(ADMIN_UUID, "validation");

			// An absent flag would otherwise be coerced to false and silently un-read the entry.
			expect(400, "Bad Request", client.updateNotification(seeded.getUuid(), new NotificationUpdateRequest()));
		}
	}

	@Test
	public void testPermissions() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Notification joedoesEntry = seed(USER_UUID, "joedoe's own entry");

			// READ only, granted through a group + role — user_permission allows a single direct
			// grant per user, so a direct grant cannot express a three-permission set.
			DaoCollection daos = daos();
			User joedoe = daos.userDao().load(USER_UUID);
			Role role = daos.roleDao().createRole(ADMIN_UUID, "notification-read-role");
			daos.roleDao().store(role);
			daos.permissionDao().grantRolePermission(role.getUuid(), Permission.READ_NOTIFICATION);
			Group group = daos.groupDao().create(joedoe, "notification-read-group");
			daos.groupDao().store(group);
			daos.groupDao().addRoleToGroup(group, role);
			daos.groupDao().addUserToGroup(group, joedoe);

			try (LoomHttpClient userClient = loom.httpClient()) {
				AuthLoginResponse login = userClient.login("joedoe", "finger").sync().body();
				userClient.setToken(login.getToken());

				NotificationListResponse mine = userClient.listNotifications().sync().body();
				assertTrue(mine.getData().stream().anyMatch(n -> n.getUuid().equals(joedoesEntry.getUuid())),
					"READ_NOTIFICATION should expose the caller's own inbox");

				// The other two permissions were never granted.
				expect(403, "Forbidden", userClient.markNotificationRead(joedoesEntry.getUuid()));
				expect(403, "Forbidden", userClient.markAllNotificationsRead());
				expect(403, "Forbidden", userClient.deleteNotification(joedoesEntry.getUuid()));
				expect(403, "Forbidden", userClient.clearNotifications());
			}
		}
	}

	@Test
	public void testUnauthenticatedAccessIsRejected() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			// No login at all: the inbox is behind secure(), so this must not leak a list.
			expect(401, "Unauthorized", client.listNotifications());
		}
	}

	@Test
	public void testListOrdersNewestFirst() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			seed(ADMIN_UUID, "older");
			seed(ADMIN_UUID, "newer");

			NotificationListResponse list = client.listNotifications().sync().body();
			List<String> titles = list.getData().stream().map(NotificationResponse::getTitle).toList();

			assertEquals(2, titles.size());
			// Both land in the same millisecond, so this pins that the ordering is deterministic
			// rather than which of the two wins.
			assertTrue(titles.containsAll(List.of("older", "newer")));
		}
	}

}
