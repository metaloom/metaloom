package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.api.task.TaskStatus;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.notification.Notification;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.comment.CommentCreateRequest;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.task.TaskAssignRequest;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.model.task.TaskUpdateRequest;

/**
 * End-to-end dispatch: a REST action produces the right inbox rows for the right people.
 *
 * <p>
 * Three properties are load-bearing and each gets its own test:
 * </p>
 * <ul>
 * <li><b>Self-suppression.</b> The actor is never notified about their own action — including when they are reached through a group they belong to,
 * which is the path a per-call-site check would miss.</li>
 * <li><b>Deduplication.</b> Somebody named directly and also sitting in an assigned group gets one row.</li>
 * <li><b>Nothing fails the caller.</b> The originating request succeeds regardless.</li>
 * </ul>
 */
public class NotificationDispatchTest extends AbstractEndpointTest {

	@Override
	protected DaoCollection daos() {
		return loom.internal().daos();
	}

	private List<Notification> inboxOf(UUID userUuid) {
		return StreamSupport
			.stream(daos().notificationDao().loadPageForRecipient(userUuid, false, 100).spliterator(), false)
			.toList();
	}

	private List<Notification> inboxOf(UUID userUuid, NotificationType type) {
		return inboxOf(userUuid).stream().filter(n -> n.getType() == type).toList();
	}

	private User storeUser(String username) {
		User user = daos().userDao().createUser(ADMIN_UUID, username);
		daos().userDao().store(user);
		return user;
	}

	private Group storeGroup(String name, User... members) {
		Group group = daos().groupDao().create(daos().userDao().load(ADMIN_UUID), name);
		daos().groupDao().store(group);
		for (User member : members) {
			daos().groupDao().addUserToGroup(group, member);
		}
		return group;
	}

	private TaskResponse createTask(LoomHttpClient client, String title) throws LoomClientException {
		TaskCreateRequest request = new TaskCreateRequest();
		request.setTitle(title);
		return client.createTask(request).sync().body();
	}

	/**
	 * The completion signal for an ad-hoc node run.
	 *
	 * <p>
	 * {@code notification.type} is a varchar with a CHECK constraint rather than an enum, so a value
	 * the Java side knows about and the database does not is rejected at insert time - after the run
	 * has already finished, which is the worst moment to lose the only durable signal it produces.
	 * This asserts the two are in step.
	 * </p>
	 */
	@Test
	public void testNodeRunCompletedIsAcceptedByTheDatabase() {
		User recipient = storeUser("noderun-recipient");

		Notification notification = daos().notificationDao().createNotification(recipient.getUuid(), null,
			NotificationType.NODE_RUN_COMPLETED, "Node run finished: describe images");
		notification.setBody("12 succeeded, 1 failed");
		daos().notificationDao().store(notification);

		List<Notification> inbox = inboxOf(recipient.getUuid(), NotificationType.NODE_RUN_COMPLETED);
		assertEquals(1, inbox.size(), "The new notification type must be storable and readable");
		assertTrue(inbox.get(0).getTitle().contains("describe images"));
		// Machine-generated: there is no actor, so nobody is suppressed and the person who started the
		// run is told even though they "caused" it.
		assertEquals(null, inbox.get(0).getCreatorUuid(), "A machine-generated notification has no actor");
	}

	@Test
	public void testAssigningNotifiesTheAssigneeButNotTheActor() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			User assignee = storeUser("dispatch_assignee");
			TaskResponse task = createTask(client, "Notify on assign");

			client.assignTaskToUser(task.getUuid(), assignee.getUuid()).sync().body();

			List<Notification> theirs = inboxOf(assignee.getUuid(), NotificationType.TASK_ASSIGNED);
			assertEquals(1, theirs.size(), "The assignee is told");
			assertTrue(theirs.get(0).getTitle().contains("Notify on assign"));
			assertEquals(task.getUuid(), theirs.get(0).getTaskUuid(), "The notification deep-links to the task");

			assertEquals(0, inboxOf(ADMIN_UUID, NotificationType.TASK_ASSIGNED).size(),
				"The actor must not be notified about their own action");
		}
	}

	@Test
	public void testGroupAssignmentFansOutButStillSuppressesTheActor() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			User member = storeUser("dispatch_group_member");
			User admin = daos().userDao().load(ADMIN_UUID);
			// The admin is IN the assigned group. This is the case a per-call-site suppression
			// check misses: they arrive through the fan-out, not by name.
			Group group = storeGroup("dispatch_group", member, admin);
			TaskResponse task = createTask(client, "Notify the team");

			client.assignTaskToGroup(task.getUuid(), group.getUuid()).sync().body();

			assertEquals(1, inboxOf(member.getUuid(), NotificationType.TASK_ASSIGNED).size(),
				"Every member of the assigned group is told");
			assertEquals(0, inboxOf(ADMIN_UUID, NotificationType.TASK_ASSIGNED).size(),
				"...except the actor, even when they are reached through the group");
		}
	}

	@Test
	public void testOverlappingTargetsProduceOneRow() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			User both = storeUser("dispatch_both");
			Group group = storeGroup("dispatch_overlap_group", both);
			TaskResponse task = createTask(client, "Named twice");

			// Named directly AND a member of the assigned group.
			client.assignTask(task.getUuid(), new TaskAssignRequest()
				.setUserUuids(List.of(both.getUuid()))
				.setGroupUuids(List.of(group.getUuid()))).sync().body();

			assertEquals(1, inboxOf(both.getUuid(), NotificationType.TASK_ASSIGNED).size(),
				"A user reached twice gets one notification, not two");
		}
	}

	@Test
	public void testUnassigningNotifies() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			User assignee = storeUser("dispatch_unassignee");
			TaskResponse task = createTask(client, "Notify on unassign");

			client.assignTaskToUser(task.getUuid(), assignee.getUuid()).sync().body();
			client.unassignTaskFromUser(task.getUuid(), assignee.getUuid()).sync().body();

			assertEquals(1, inboxOf(assignee.getUuid(), NotificationType.TASK_UNASSIGNED).size(),
				"Being taken off a task is worth knowing about");
		}
	}

	@Test
	public void testStatusChangeNotifiesAssignees() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			User assignee = storeUser("dispatch_status_assignee");
			TaskResponse task = createTask(client, "Notify on status change");
			client.assignTaskToUser(task.getUuid(), assignee.getUuid()).sync().body();

			TaskUpdateRequest update = new TaskUpdateRequest();
			update.setTaskStatus(TaskStatus.ACCEPTED);
			client.updateTask(task.getUuid(), update).sync().body();

			assertEquals(1, inboxOf(assignee.getUuid(), NotificationType.TASK_STATUS_CHANGED).size());
		}
	}

	@Test
	public void testAnUnrelatedEditDoesNotNotify() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			User assignee = storeUser("dispatch_quiet_assignee");
			TaskResponse task = createTask(client, "Rename me");
			client.assignTaskToUser(task.getUuid(), assignee.getUuid()).sync().body();

			// Only the title changes. A status-change notification here would mean every edit
			// spams every assignee.
			TaskUpdateRequest update = new TaskUpdateRequest();
			update.setTitle("Renamed");
			client.updateTask(task.getUuid(), update).sync().body();

			assertEquals(0, inboxOf(assignee.getUuid(), NotificationType.TASK_STATUS_CHANGED).size(),
				"Renaming a task is not a status change");
		}
	}

	@Test
	public void testCommentOnTaskNotifiesTheAssignees() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			User assignee = storeUser("dispatch_comment_assignee");
			TaskResponse task = createTask(client, "Comment target");
			client.assignTaskToUser(task.getUuid(), assignee.getUuid()).sync().body();

			CommentCreateRequest comment = new CommentCreateRequest();
			comment.setTitle("Review request");
			comment.setText("Have a look at this");
			client.createTaskComment(task.getUuid(), comment).sync().body();

			List<Notification> theirs = inboxOf(assignee.getUuid(), NotificationType.TASK_COMMENT);
			assertEquals(1, theirs.size());
			assertEquals(task.getUuid(), theirs.get(0).getTaskUuid());
		}
	}

	@Test
	public void testReplyNotifiesTheParentAuthor() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			TaskResponse task = createTask(client, "Threaded discussion");

			// joedoe writes the root comment; the admin replies to it.
			User joedoe = daos().userDao().load(USER_UUID);
			io.metaloom.loom.db.model.comment.Comment root = daos().commentDao()
				.createCommentForTask(joedoe.getUuid(), task.getUuid(), "Original", "the original point");
			daos().commentDao().store(root);

			CommentCreateRequest reply = new CommentCreateRequest();
			reply.setTitle("Counterpoint");
			reply.setText("I disagree");
			reply.setParentUuid(root.getUuid());
			CommentResponse created = client.createTaskComment(task.getUuid(), reply).sync().body();

			List<Notification> theirs = inboxOf(joedoe.getUuid(), NotificationType.COMMENT_REPLY);
			assertEquals(1, theirs.size(), "The author of the parent comment is told");
			assertEquals(created.getUuid(), theirs.get(0).getCommentUuid(), "The notification deep-links to the reply");
		}
	}

	@Test
	public void testAssigningToYourselfNotifiesNobody() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			TaskResponse task = createTask(client, "Mine alone");

			client.assignTaskToUser(task.getUuid(), ADMIN_UUID).sync().body();

			assertEquals(0, inboxOf(ADMIN_UUID, NotificationType.TASK_ASSIGNED).size(),
				"Assigning something to yourself is not news");
		}
	}

}
