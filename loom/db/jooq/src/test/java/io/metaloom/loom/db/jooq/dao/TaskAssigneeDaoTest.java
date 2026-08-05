package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqTaskAssignee.TASK_ASSIGNEE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.task.TaskAssignee;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

/**
 * Assignment of a task to users and groups, and the delete cascades of {@code task_assignee} (V2.69).
 *
 * <p>
 * Every cascade test creates a second, untouched task with an identical row set and asserts that it survives — a cascade that deletes too much looks
 * exactly like a correct one when only the targeted rows are inspected.
 * </p>
 */
public class TaskAssigneeDaoTest extends AbstractJooqTest {

	private static Stream<Task> stream(Page<Task> page) {
		return StreamSupport.stream(page.spliterator(), false);
	}

	private Task storeTask(User user, String title) {
		Task task = taskDao().createTask(user, title);
		taskDao().store(task);
		return task;
	}

	private Group storeGroup(String name) {
		Group group = groupDao().create(adminUser(), name);
		groupDao().store(group);
		return group;
	}

	private User storeUser(String username) {
		User user = userDao().createUser(adminUser().getUuid(), username);
		userDao().store(user);
		return user;
	}

	private int rowCount(Task task) {
		return context.ctx().fetchCount(TASK_ASSIGNEE, TASK_ASSIGNEE.TASK_UUID.eq(task.getUuid()));
	}

	@Test
	public void testAssignUser() {
		User actor = adminUser();
		User assignee = storeUser("assignee_user");
		Task task = storeTask(actor, "user_assigned_task");

		taskDao().assignUser(task.getUuid(), assignee.getUuid(), actor.getUuid());
		// Assigning twice must be a no-op, not a unique-violation. The conflict target is a
		// PARTIAL unique index, so this also proves the ON CONFLICT ... WHERE clause matches it.
		taskDao().assignUser(task.getUuid(), assignee.getUuid(), actor.getUuid());

		List<TaskAssignee> assignees = taskDao().loadAssignees(task.getUuid());
		assertEquals(1, assignees.size(), "Assigning the same user twice must produce one row");
		TaskAssignee row = assignees.get(0);
		assertEquals(assignee.getUuid(), row.getUserUuid());
		assertNull(row.getGroupUuid(), "A user assignment must not carry a group");
		assertFalse(row.isGroupAssignment());
		assertEquals(actor.getUuid(), row.getAssignerUuid(), "The assigner should be recorded");
		assertNotNull(row.getAssigned(), "The assignment timestamp is defaulted by the database");

		taskDao().unassignUser(task.getUuid(), assignee.getUuid());
		assertTrue(taskDao().loadAssignees(task.getUuid()).isEmpty(), "The assignment should be gone");
	}

	@Test
	public void testAssignGroup() {
		User actor = adminUser();
		Group group = storeGroup("assignee_group");
		Task task = storeTask(actor, "group_assigned_task");

		taskDao().assignGroup(task.getUuid(), group.getUuid(), actor.getUuid());
		taskDao().assignGroup(task.getUuid(), group.getUuid(), actor.getUuid());

		List<TaskAssignee> assignees = taskDao().loadAssignees(task.getUuid());
		assertEquals(1, assignees.size(), "Assigning the same group twice must produce one row");
		assertTrue(assignees.get(0).isGroupAssignment());
		assertEquals(group.getUuid(), assignees.get(0).getGroupUuid());
		assertNull(assignees.get(0).getUserUuid(), "A group assignment must not carry a user");

		taskDao().unassignGroup(task.getUuid(), group.getUuid());
		assertTrue(taskDao().loadAssignees(task.getUuid()).isEmpty());
	}

	@Test
	public void testExactlyOneTargetIsEnforced() {
		Task task = storeTask(adminUser(), "check_constraint_task");

		// Neither target set.
		assertThrows(DataAccessException.class, () -> context.ctx()
			.insertInto(TASK_ASSIGNEE, TASK_ASSIGNEE.TASK_UUID)
			.values(task.getUuid())
			.execute(), "A row naming neither a user nor a group must be rejected");

		// Both targets set.
		Group group = storeGroup("both_targets_group");
		User user = storeUser("both_targets_user");
		assertThrows(DataAccessException.class, () -> context.ctx()
			.insertInto(TASK_ASSIGNEE, TASK_ASSIGNEE.TASK_UUID, TASK_ASSIGNEE.USER_UUID, TASK_ASSIGNEE.GROUP_UUID)
			.values(task.getUuid(), user.getUuid(), group.getUuid())
			.execute(), "A row naming both a user and a group must be rejected");
	}

	@Test
	public void testLoadAssignedUserUuidsResolvesGroupMembership() {
		User actor = adminUser();
		User direct = storeUser("direct_assignee");
		User member = storeUser("group_member");
		User both = storeUser("direct_and_member");

		Group group = storeGroup("resolution_group");
		groupDao().addUserToGroup(group, member);
		groupDao().addUserToGroup(group, both);

		Task task = storeTask(actor, "resolution_task");
		taskDao().assignUser(task.getUuid(), direct.getUuid(), actor.getUuid());
		taskDao().assignUser(task.getUuid(), both.getUuid(), actor.getUuid());
		taskDao().assignGroup(task.getUuid(), group.getUuid(), actor.getUuid());

		List<UUID> resolved = taskDao().loadAssignedUserUuids(task.getUuid());

		assertTrue(resolved.contains(direct.getUuid()), "A directly assigned user is responsible");
		assertTrue(resolved.contains(member.getUuid()), "A member of an assigned group is responsible");
		assertEquals(1, resolved.stream().filter(both.getUuid()::equals).count(),
			"A user who is both named directly and in an assigned group must appear exactly once");
		assertEquals(3, resolved.size(), "Only the three responsible users should be resolved");
	}

	@Test
	public void testLoadPageAssignedToCoversDirectAndGroup() {
		User actor = adminUser();
		User member = storeUser("paged_member");
		Group group = storeGroup("paged_group");
		groupDao().addUserToGroup(group, member);

		Task directTask = storeTask(actor, "paged_direct_task");
		Task groupTask = storeTask(actor, "paged_group_task");
		Task unrelatedTask = storeTask(actor, "paged_unrelated_task");

		taskDao().assignUser(directTask.getUuid(), member.getUuid(), actor.getUuid());
		taskDao().assignGroup(groupTask.getUuid(), group.getUuid(), actor.getUuid());

		Page<Task> page = taskDao().loadPageAssignedTo(member.getUuid(), null, 25, null, null, null);
		List<UUID> uuids = stream(page).map(Task::getUuid).toList();

		// The LEFT JOIN matters here: an inner join on user_group would drop the direct row,
		// whose group_uuid is null.
		assertTrue(uuids.contains(directTask.getUuid()), "A directly assigned task must be listed");
		assertTrue(uuids.contains(groupTask.getUuid()), "A task assigned to the user's group must be listed");
		assertFalse(uuids.contains(unrelatedTask.getUuid()), "An unassigned task must not be listed");
	}

	@Test
	public void testDeleteTaskCascadesAssignees() {
		User actor = adminUser();
		User assignee = storeUser("cascade_task_user");
		Group group = storeGroup("cascade_task_group");

		Task task = storeTask(actor, "cascade_task");
		taskDao().assignUser(task.getUuid(), assignee.getUuid(), actor.getUuid());
		taskDao().assignGroup(task.getUuid(), group.getUuid(), actor.getUuid());

		Task survivor = storeTask(actor, "cascade_task_survivor");
		taskDao().assignUser(survivor.getUuid(), assignee.getUuid(), actor.getUuid());
		taskDao().assignGroup(survivor.getUuid(), group.getUuid(), actor.getUuid());

		assertEquals(2, rowCount(task));
		assertEquals(2, rowCount(survivor));

		taskDao().delete(task);

		assertNull(taskDao().load(task.getUuid()), "The task is gone");
		assertEquals(0, rowCount(task), "The task_assignee rows must have cascaded");
		assertEquals(2, rowCount(survivor), "The other task's assignees must be untouched");
		assertNotNull(userDao().load(assignee.getUuid()), "The assigned user must survive deletion of the task");
		assertNotNull(groupDao().load(group.getUuid()), "The assigned group must survive deletion of the task");
	}

	@Test
	public void testDeleteUserCascadesOnlyTheirAssignments() {
		User actor = adminUser();
		User doomed = storeUser("cascade_doomed_user");
		User survivor = storeUser("cascade_surviving_user");
		Task task = storeTask(actor, "cascade_user_task");

		taskDao().assignUser(task.getUuid(), doomed.getUuid(), actor.getUuid());
		taskDao().assignUser(task.getUuid(), survivor.getUuid(), actor.getUuid());
		assertEquals(2, rowCount(task));

		userDao().delete(doomed);

		assertEquals(1, rowCount(task), "Only the deleted user's assignment must disappear");
		assertEquals(survivor.getUuid(), taskDao().loadAssignees(task.getUuid()).get(0).getUserUuid());
		assertNotNull(taskDao().load(task.getUuid()), "The task must survive deletion of one of its assignees");
	}

	@Test
	public void testDeleteGroupCascadesOnlyItsAssignments() {
		User actor = adminUser();
		Group doomed = storeGroup("cascade_doomed_group");
		Group survivor = storeGroup("cascade_surviving_group");
		Task task = storeTask(actor, "cascade_group_task");

		taskDao().assignGroup(task.getUuid(), doomed.getUuid(), actor.getUuid());
		taskDao().assignGroup(task.getUuid(), survivor.getUuid(), actor.getUuid());
		assertEquals(2, rowCount(task));

		groupDao().delete(doomed.getUuid());

		assertEquals(1, rowCount(task), "Only the deleted group's assignment must disappear");
		assertEquals(survivor.getUuid(), taskDao().loadAssignees(task.getUuid()).get(0).getGroupUuid());
		assertNotNull(taskDao().load(task.getUuid()), "The task must survive deletion of one of its assigned groups");
	}

	@Test
	public void testDeleteAssignerKeepsTheAssignment() {
		User assigner = storeUser("departing_assigner");
		User assignee = storeUser("retained_assignee");
		Task task = storeTask(adminUser(), "assigner_set_null_task");

		taskDao().assignUser(task.getUuid(), assignee.getUuid(), assigner.getUuid());

		userDao().delete(assigner);

		List<TaskAssignee> assignees = taskDao().loadAssignees(task.getUuid());
		assertEquals(1, assignees.size(), "Losing the assigner must not unassign live work");
		assertEquals(assignee.getUuid(), assignees.get(0).getUserUuid());
		assertNull(assignees.get(0).getAssignerUuid(), "The assigner reference should be SET NULL, not cascaded");
	}

	@Test
	public void testLoadAssigneesForSeveralTasks() {
		User actor = adminUser();
		User assignee = storeUser("batch_assignee");
		Task taskA = storeTask(actor, "batch_task_a");
		Task taskB = storeTask(actor, "batch_task_b");
		Task taskC = storeTask(actor, "batch_task_c");

		taskDao().assignUser(taskA.getUuid(), assignee.getUuid(), actor.getUuid());
		taskDao().assignUser(taskB.getUuid(), assignee.getUuid(), actor.getUuid());

		List<TaskAssignee> batch = taskDao().loadAssignees(List.of(taskA.getUuid(), taskB.getUuid(), taskC.getUuid()));
		assertEquals(2, batch.size(), "Only assigned tasks contribute rows");
		assertTrue(batch.stream().anyMatch(a -> a.getTaskUuid().equals(taskA.getUuid())));
		assertTrue(batch.stream().anyMatch(a -> a.getTaskUuid().equals(taskB.getUuid())));

		assertTrue(taskDao().loadAssignees(List.of()).isEmpty(), "An empty request must not hit the database");
	}

}
