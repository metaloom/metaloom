package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.test.data.TestValues;
import io.vertx.core.json.JsonObject;

/**
 * The memory REST surface.
 *
 * <p>Declares its own extension because the endpoint is only registered when the memory bank is enabled, which is off by default.</p>
 */
public class MemoryEndpointTest implements TestValues {

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension()
		.withOptions(o -> o.getMemory().setEnabled(true).setMountEnabled(false));

	private final HttpClient http = HttpClient.newHttpClient();

	private String token;

	@BeforeEach
	public void loginJoeDoe() throws LoomClientException {
		// The joedoe fixture user only carries READ_USER by default. Direct user grants are limited to a single
		// permission per user (user_permission PK), so the memory permissions are granted via a group + role.
		DaoCollection daos = loom.internal().daos();
		User joedoe = daos.userDao().load(USER_UUID);
		Role role = daos.roleDao().createRole(ADMIN_UUID, "memory-test-role");
		daos.roleDao().store(role);
		for (Permission perm : List.of(Permission.CREATE_MEMORY, Permission.READ_MEMORY, Permission.UPDATE_MEMORY, Permission.DELETE_MEMORY)) {
			daos.permissionDao().grantRolePermission(role.getUuid(), perm, "test");
		}
		Group group = daos.groupDao().createGroup(ADMIN_UUID, "memory-test-group");
		daos.groupDao().store(group);
		daos.groupDao().addRoleToGroup(group, role);
		daos.groupDao().addUserToGroup(group, joedoe);

		LoomHttpClient client = loom.httpClient();
		AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
		token = login.getToken();
	}

	// -- happy path ----------------------------------------------------------

	@Test
	public void testCreateReadListDeleteRoundTrip() throws Exception {
		JsonObject created = json(put("notes.md", new JsonObject().put("body", "# Notes\n\nremember this").put("title", "My notes")));
		assertEquals("notes.md", created.getString("id"));
		assertEquals("user", created.getString("scope"));
		assertEquals("My notes", created.getString("title"));
		assertEquals(1, created.getInteger("version"));

		JsonObject loaded = json(get("/entry?scope=user&id=notes.md"));
		assertEquals("# Notes\n\nremember this", loaded.getString("body"));
		assertEquals("joedoe", loaded.getString("editor"), "The editor is resolved for the provenance header");

		JsonObject listed = json(get("?scope=user"));
		assertEquals(1, listed.getJsonArray("entries").size());
		// The listing carries the header fields the UI renders, but the body is fetched per entry.
		assertEquals("notes.md", listed.getJsonArray("entries").getJsonObject(0).getString("id"));

		assertEquals(200, delete("/entry?scope=user&id=notes.md").statusCode());
		assertEquals(0, json(get("?scope=user")).getJsonArray("entries").size());
	}

	@Test
	public void testUpdateBumpsTheVersion() throws Exception {
		put("notes.md", new JsonObject().put("body", "v1"));
		JsonObject updated = json(put("notes.md", new JsonObject().put("body", "v2")));
		assertEquals(2, updated.getInteger("version"));
		assertEquals("v2", json(get("/entry?scope=user&id=notes.md")).getString("body"));
	}

	@Test
	public void testScopesReportUsageAndQuota() throws Exception {
		put("notes.md", new JsonObject().put("body", "12345"));

		JsonObject scopes = json(get("/scopes"));
		JsonObject userScope = scopes.getJsonArray("scopes").getJsonObject(0);
		assertEquals("user", userScope.getString("scope"));
		assertEquals(1, userScope.getInteger("count"));
		assertEquals(5, userScope.getInteger("bytes"));
		assertNotNull(userScope.getInteger("maxEntries"));
		assertTrue(userScope.getBoolean("writable"));
	}

	// -- errors --------------------------------------------------------------

	@Test
	public void testCreateConflictsWhenTheIdExists() throws Exception {
		assertEquals(200, post("notes.md", new JsonObject().put("body", "first")).statusCode());
		assertEquals(409, post("notes.md", new JsonObject().put("body", "second")).statusCode());
	}

	@Test
	public void testTraversalIdIsRejected() throws Exception {
		assertEquals(400, post("../escape.md", new JsonObject().put("body", "x")).statusCode());
	}

	@Test
	public void testMissingIdIsRejected() throws Exception {
		assertEquals(400, get("/entry?scope=user").statusCode());
	}

	@Test
	public void testOversizedBodyIsRejected() throws Exception {
		// Quota violations surface as 400 with an actionable message rather than a 500.
		String body = "x".repeat(300 * 1024);
		assertEquals(400, post("big.md", new JsonObject().put("body", body)).statusCode());
	}

	@Test
	public void testUnknownEntryIs404() throws Exception {
		assertEquals(404, get("/entry?scope=user&id=nope.md").statusCode());
		assertEquals(404, delete("/entry?scope=user&id=nope.md").statusCode());
	}

	@Test
	public void testForeignScopeIsIndistinguishableFromMissing() throws Exception {
		// joedoe is in no space and only in the test group, so a made-up group must not reveal itself.
		assertEquals(404, get("?scope=group&ref=some-other-team").statusCode());
		assertEquals(404, get("?scope=space").statusCode());
	}

	@Test
	public void testUnauthenticatedIsRejected() throws Exception {
		HttpResponse<String> res = http.send(HttpRequest.newBuilder()
			.uri(URI.create(baseUrl() + "/scopes")).GET().build(), BodyHandlers.ofString());
		assertTrue(res.statusCode() == 401 || res.statusCode() == 403, "Expected an auth failure but got " + res.statusCode());
	}

	// -- helpers -------------------------------------------------------------

	private String baseUrl() {
		int port = loom.internal().boot().getRestService().getServer().actualPort();
		return "http://localhost:" + port + "/api/v1/memory";
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder()
			.uri(URI.create(baseUrl() + path))
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "application/json");
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(request(path).GET().build(), BodyHandlers.ofString());
	}

	private HttpResponse<String> put(String id, JsonObject body) throws Exception {
		return http.send(request("/entry?scope=user&id=" + enc(id))
			.PUT(HttpRequest.BodyPublishers.ofString(body.encode())).build(), BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String id, JsonObject body) throws Exception {
		return http.send(request("/entry?scope=user&id=" + enc(id))
			.POST(HttpRequest.BodyPublishers.ofString(body.encode())).build(), BodyHandlers.ofString());
	}

	private HttpResponse<String> delete(String path) throws Exception {
		return http.send(request(path).DELETE().build(), BodyHandlers.ofString());
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static JsonObject json(HttpResponse<String> response) {
		assertEquals(200, response.statusCode(), "Unexpected status; body=" + response.body());
		return new JsonObject(response.body());
	}

}
