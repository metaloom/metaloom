package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.task.TaskAssignRequest;
import io.metaloom.loom.rest.model.task.TaskAssigneeListResponse;
import io.metaloom.loom.rest.model.task.TaskAssigneeResponse;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskResponse;

/**
 * The {@code /tasks/:uuid/assignees} sub-resource.
 *
 * <p>
 * Assignment reuses {@code UPDATE_TASK} / {@code READ_TASK} rather than introducing an {@code ASSIGN_TASK} verb, so the permission cases below probe
 * those two. Permissions are granted through a <b>group + role</b>, never a direct user grant — {@code user_permission} allows only one direct
 * permission per user. {@code SkillEndpointTest} is the reference for that recipe.
 * </p>
 */
public class TaskAssigneeEndpointTest extends AbstractEndpointTest {

	private TaskResponse createTask(LoomHttpClient client, String title) throws LoomClientException {
		TaskCreateRequest request = new TaskCreateRequest();
		request.setTitle(title);
		return client.createTask(request).sync().body();
	}

	private Group storeGroup(String name) {
		DaoCollection daos = loom.internal().daos();
		Group group = daos.groupDao().create(daos.userDao().load(ADMIN_UUID), name);
		daos.groupDao().store(group);
		return group;
	}

	private User storeUser(String username) {
		DaoCollection daos = loom.internal().daos();
		User user = daos.userDao().createUser(ADMIN_UUID, username);
		daos.userDao().store(user);
		return user;
	}

	@Test
	public void testAssignUserAndGroup() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TaskResponse task = createTask(client, "Assignable task");
			User assignee = storeUser("assignee_endpoint_user");
			Group group = storeGroup("assignee_endpoint_group");

			TaskAssigneeListResponse assigned = client.assignTask(task.getUuid(),
				new TaskAssignRequest()
					.setUserUuids(List.of(assignee.getUuid()))
					.setGroupUuids(List.of(group.getUuid())))
				.sync().body();
			assertEquals(2, assigned.getData().size(), "Both targets should be assigned");

			TaskAssigneeListResponse listed = client.listTaskAssignees(task.getUuid()).sync().body();
			assertEquals(2, listed.getData().size());

			TaskAssigneeResponse userEntry = listed.getData().stream()
				.filter(a -> assignee.getUuid().equals(a.getUserUuid())).findFirst().orElse(null);
			assertNotNull(userEntry, "The user assignment should be listed");
			assertEquals("assignee_endpoint_user", userEntry.getName(), "The display name should be resolved onto the response");
			assertNull(userEntry.getGroupUuid(), "A user entry must not carry a group uuid");
			assertNotNull(userEntry.getAssigned());
			assertEquals(ADMIN_UUID, userEntry.getAssignerUuid(), "The acting admin should be recorded as the assigner");

			TaskAssigneeResponse groupEntry = listed.getData().stream()
				.filter(a -> group.getUuid().equals(a.getGroupUuid())).findFirst().orElse(null);
			assertNotNull(groupEntry, "The group assignment should be listed");
			assertEquals("assignee_endpoint_group", groupEntry.getName());
			assertNull(groupEntry.getUserUuid(), "A group entry must not carry a user uuid");

			// The task response itself carries the assignees, so a client rendering a task needs no
			// second request.
			TaskResponse reloaded = client.loadTask(task.getUuid()).sync().body();
			assertEquals(2, reloaded.getAssignees().size(), "The task response should embed its assignees");

			client.unassignTaskFromUser(task.getUuid(), assignee.getUuid()).sync().body();
			client.unassignTaskFromGroup(task.getUuid(), group.getUuid()).sync().body();

			TaskAssigneeListResponse empty = client.listTaskAssignees(task.getUuid()).sync().body();
			assertTrue(empty.getData() == null || empty.getData().isEmpty(), "Both assignments should be gone");
		}
	}

	@Test
	public void testAssignIsIdempotent() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TaskResponse task = createTask(client, "Idempotent task");
			User assignee = storeUser("idempotent_assignee");

			client.assignTaskToUser(task.getUuid(), assignee.getUuid()).sync().body();
			TaskAssigneeListResponse second = client.assignTaskToUser(task.getUuid(), assignee.getUuid()).sync().body();

			assertEquals(1, second.getData().size(), "Assigning the same user twice must not duplicate the assignment");
		}
	}

	@Test
	public void testAssignErrors() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TaskResponse task = createTask(client, "Error case task");
			User assignee = storeUser("error_case_assignee");

			expect(404, "Not Found", client.listTaskAssignees(UUID.randomUUID()));
			expect(404, "Not Found", client.assignTaskToUser(UUID.randomUUID(), assignee.getUuid()));
			expect(404, "Not Found", client.assignTaskToUser(task.getUuid(), UUID.randomUUID()));
			expect(404, "Not Found", client.assignTaskToGroup(task.getUuid(), UUID.randomUUID()));
			expect(404, "Not Found", client.unassignTaskFromUser(UUID.randomUUID(), assignee.getUuid()));

			// An empty body would otherwise answer 201 having done nothing.
			expect(400, "Bad Request", client.assignTask(task.getUuid(), new TaskAssignRequest()));
		}
	}

	@Test
	public void testAssignRejectsPartiallyUnknownTargets() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			TaskResponse task = createTask(client, "Partial failure task");
			User good = storeUser("partial_good_assignee");

			// One resolvable user and one unknown one: the request must fail WHOLE rather than
			// leaving the good half behind.
			expect(404, "Not Found", client.assignTask(task.getUuid(),
				new TaskAssignRequest().setUserUuids(List.of(good.getUuid(), UUID.randomUUID()))));

			TaskAssigneeListResponse listed = client.listTaskAssignees(task.getUuid()).sync().body();
			assertTrue(listed.getData() == null || listed.getData().isEmpty(),
				"A rejected assign request must not have written the resolvable half");
		}
	}

	@Test
	public void testPermissions() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			TaskResponse task = createTask(client, "Permission task");
			User assignee = storeUser("permission_assignee");

			// joedoe gets READ_TASK only — enough to list, not to assign. Granted through a
			// group + role because user_permission allows just one direct grant per user.
			DaoCollection daos = loom.internal().daos();
			User joedoe = daos.userDao().load(USER_UUID);
			Role role = daos.roleDao().createRole(ADMIN_UUID, "task-assignee-read-role");
			daos.roleDao().store(role);
			daos.permissionDao().grantRolePermission(role.getUuid(), Permission.READ_TASK);
			Group group = daos.groupDao().create(joedoe, "task-assignee-read-group");
			daos.groupDao().store(group);
			daos.groupDao().addRoleToGroup(group, role);
			daos.groupDao().addUserToGroup(group, joedoe);

			try (LoomHttpClient userClient = loom.httpClient()) {
				AuthLoginResponse login = userClient.login("joedoe", "finger").sync().body();
				userClient.setToken(login.getToken());

				// READ_TASK is granted
				assertNotNull(userClient.listTaskAssignees(task.getUuid()).sync().body(),
					"READ_TASK should allow listing the assignees");

				// UPDATE_TASK is not
				expect(403, "Forbidden", userClient.assignTaskToUser(task.getUuid(), assignee.getUuid()));
				expect(403, "Forbidden", userClient.unassignTaskFromUser(task.getUuid(), assignee.getUuid()));
				expect(403, "Forbidden", userClient.unassignTaskFromGroup(task.getUuid(), group.getUuid()));
			}
		}
	}

}
