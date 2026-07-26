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
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.vertx.core.json.JsonObject;

/**
 * GraphQL read tests for the {@code Role} domain element. The fixture provisions a single {@code test-role}. Also asserts the {@code READ_ROLE} field
 * guard by querying as a user that lacks the permission.
 */
public class RoleGraphQLTest extends AbstractGraphQLTest {

	@Test
	public void testRoleByUuid() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("uuid", ROLE_UUID.toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { role(uuid: $uuid) { uuid name } }", variables);

			Map<String, Object> role = object(data, "role");
			assertNotNull(role);
			assertEquals(ROLE_UUID.toString(), role.get("uuid"));
			assertEquals("test-role", role.get("name"));
		}
	}

	@Test
	public void testRoleByName() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("name", "test-role");
			Map<String, Object> data = data(client,
				"query($name: String!) { roleByName(name: $name) { uuid name } }", variables);

			Map<String, Object> role = object(data, "roleByName");
			assertNotNull(role);
			assertEquals(ROLE_UUID.toString(), role.get("uuid"));
		}
	}

	@Test
	public void testRoleList() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			Map<String, Object> data = data(client, "{ roles { uuid name } }");
			List<Map<String, Object>> roles = list(data, "roles");
			assertNotNull(roles);
			assertTrue(roles.stream().anyMatch(r -> "test-role".equals(r.get("name"))), "The fixture role should be listed");
		}
	}

	@Test
	public void testRoleNotFound() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			JsonObject variables = new JsonObject().put("uuid", UUID.randomUUID().toString());
			Map<String, Object> data = data(client, "query($uuid: ID!) { role(uuid: $uuid) { uuid } }", variables);
			assertNull(data.get("role"), "An unknown role uuid must resolve to null");
		}
	}

	@Test
	public void testRoleRequiresReadRolePermission() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			// joedoe only carries READ_USER, so a roles query must be forbidden.
			AuthLoginResponse loginResponse = client.login("joedoe", "finger").sync().body();
			client.setToken(loginResponse.getToken());

			assertErrorCode(query(client, "{ roles { uuid name } }"), "FORBIDDEN");
		}
	}

	@Test
	public void testUnauthenticatedIsRejected() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			// No login -> the endpoint auth handler rejects the request before it reaches GraphQL execution.
			try {
				query(client, "{ roles { uuid } }");
			} catch (LoomClientException e) {
				assertEquals(401, e.getStatusCode(), "An unauthenticated GraphQL request must be rejected with 401");
				return;
			}
			throw new AssertionError("Expected the unauthenticated request to be rejected");
		}
	}

	@Test
	@Override
	public void testIndividualRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			String uuid = UUID.randomUUID().toString();
			assertRetrievalForbidden(client, Permission.READ_ROLE, "{ role(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_ROLE, "{ roleByName(name: \"test-role\") { uuid } }");
		}
	}

	@Test
	@Override
	public void testListRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			assertRetrievalForbidden(client, Permission.READ_ROLE, "{ roles { uuid } }");
		}
	}
}
