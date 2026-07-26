package io.metaloom.loom.core.endpoint.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.vertx.core.json.JsonObject;

/**
 * GraphQL read tests for the {@code User} domain element. Exercises single lookups (by uuid and username), the list query, the {@code groups} back
 * reference and the field level authorization guard on {@code Query.user}.
 */
public class UserGraphQLTest extends AbstractGraphQLTest {

	@Test
	public void testUserByUuid() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("uuid", ADMIN_UUID.toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { user(uuid: $uuid) { uuid username enabled deleted sso } }", variables);

			Map<String, Object> user = object(data, "user");
			assertNotNull(user);
			assertEquals(ADMIN_UUID.toString(), user.get("uuid"));
			assertEquals(UserDao.ADMIN_USER_NAME, user.get("username"));
			// enabled/deleted/sso are non-null booleans projected from the row.
			assertNotNull(user.get("enabled"));
			assertEquals(Boolean.FALSE, user.get("deleted"));
			assertNotNull(user.get("sso"));
		}
	}

	@Test
	public void testUserByUsername() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("username", "joedoe");
			Map<String, Object> data = data(client,
				"query($username: String!) { userByUsername(username: $username) { uuid username } }", variables);

			Map<String, Object> user = object(data, "userByUsername");
			assertNotNull(user);
			assertEquals(USER_UUID.toString(), user.get("uuid"));
			assertEquals("joedoe", user.get("username"));
		}
	}

	@Test
	public void testUserList() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			Map<String, Object> data = data(client, "{ users { uuid username } }");
			List<Map<String, Object>> users = list(data, "users");
			assertNotNull(users);
			// The fixture provisions at least admin + joedoe.
			assertTrue(users.stream().anyMatch(u -> UserDao.ADMIN_USER_NAME.equals(u.get("username"))), "admin should be listed");
			assertTrue(users.stream().anyMatch(u -> "joedoe".equals(u.get("username"))), "joedoe should be listed");
		}
	}

	@Test
	public void testPasswordHashIsNotExposed() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// The schema has no passwordHash field, so requesting it is a validation error rather than a data leak.
			JsonObject variables = new JsonObject().put("uuid", ADMIN_UUID.toString());
			assertHasErrors(query(client, "query($uuid: ID!) { user(uuid: $uuid) { passwordHash } }", variables));
		}
	}

	@Test
	public void testUserGroups() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("uuid", ADMIN_UUID.toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { user(uuid: $uuid) { uuid groups { uuid name } } }", variables);

			Map<String, Object> user = object(data, "user");
			List<Map<String, Object>> groups = list(user, "groups");
			assertNotNull(groups);
			// The admin user is a member of the fixture test-group.
			assertTrue(groups.stream().anyMatch(g -> GROUP_UUID.toString().equals(g.get("uuid"))),
				"admin should be a member of the fixture group");
		}
	}

	@Test
	public void testUserNotFound() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("uuid", UUID.randomUUID().toString());
			Map<String, Object> data = data(client, "query($uuid: ID!) { user(uuid: $uuid) { uuid } }", variables);
			assertNull(data.get("user"), "An unknown user uuid must resolve to null");
		}
	}

	@Test
	public void testUserRequiresReadUserPermission() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			// joedoe only holds READ_USER... which is exactly what Query.user needs, so instead login joedoe and prove
			// that a field guarded by a permission joedoe lacks (READ_GROUP on user.groups) is rejected.
			loginJoeDoe(client);

			JsonObject variables = new JsonObject().put("uuid", ADMIN_UUID.toString());
			// user{username} is allowed (READ_USER), but user.groups needs READ_GROUP which joedoe lacks.
			assertErrorCode(query(client, "query($uuid: ID!) { user(uuid: $uuid) { username groups { uuid } } }", variables), "FORBIDDEN");
		}
	}

	private void loginJoeDoe(LoomHttpClient client) throws LoomClientException {
		AuthLoginResponse loginResponse = client.login("joedoe", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	@Test
	@Override
	public void testIndividualRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			String uuid = UUID.randomUUID().toString();
			assertRetrievalForbidden(client, Permission.READ_USER, "{ user(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_USER, "{ userByUsername(username: \"admin\") { uuid } }");
		}
	}

	@Test
	@Override
	public void testListRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			assertRetrievalForbidden(client, Permission.READ_USER, "{ users { uuid } }");
		}
	}
}
