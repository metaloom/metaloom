package io.metaloom.loom.core.endpoint.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.db.model.perm.Permission;
import io.vertx.core.json.JsonObject;

/**
 * GraphQL read tests for the {@code Group} domain element, including the {@code users} and {@code roles} relations. The fixture provisions a single
 * {@code test-group} that carries the admin user and the {@code test-role}.
 */
public class GroupGraphQLTest extends AbstractGraphQLTest {

	@Test
	public void testGroupByUuid() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("uuid", GROUP_UUID.toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { group(uuid: $uuid) { uuid name } }", variables);

			Map<String, Object> group = object(data, "group");
			assertNotNull(group);
			assertEquals(GROUP_UUID.toString(), group.get("uuid"));
			assertEquals("test-group", group.get("name"));
		}
	}

	@Test
	public void testGroupByName() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("name", "test-group");
			Map<String, Object> data = data(client,
				"query($name: String!) { groupByName(name: $name) { uuid name } }", variables);

			Map<String, Object> group = object(data, "groupByName");
			assertNotNull(group);
			assertEquals(GROUP_UUID.toString(), group.get("uuid"));
		}
	}

	@Test
	public void testGroupList() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			Map<String, Object> data = data(client, "{ groups { uuid name } }");
			List<Map<String, Object>> groups = list(data, "groups");
			assertNotNull(groups);
			assertTrue(groups.stream().anyMatch(g -> "test-group".equals(g.get("name"))), "The fixture group should be listed");
		}
	}

	@Test
	public void testGroupUsersAndRoles() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("uuid", GROUP_UUID.toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { group(uuid: $uuid) { users { uuid username } roles { uuid name } } }", variables);

			Map<String, Object> group = object(data, "group");

			List<Map<String, Object>> users = list(group, "users");
			assertTrue(users.stream().anyMatch(u -> ADMIN_UUID.toString().equals(u.get("uuid"))),
				"The admin user should be a member of the fixture group");

			List<Map<String, Object>> roles = list(group, "roles");
			assertTrue(roles.stream().anyMatch(r -> "test-role".equals(r.get("name"))),
				"The fixture role should be attached to the group");
		}
	}

	@Test
	@Override
	public void testIndividualRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			String uuid = UUID.randomUUID().toString();
			assertRetrievalForbidden(client, Permission.READ_GROUP, "{ group(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_GROUP, "{ groupByName(name: \"test-group\") { uuid } }");
		}
	}

	@Test
	@Override
	public void testListRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			assertRetrievalForbidden(client, Permission.READ_GROUP, "{ groups { uuid } }");
		}
	}
}
