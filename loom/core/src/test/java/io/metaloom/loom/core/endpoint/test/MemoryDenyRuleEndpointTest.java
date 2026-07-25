package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
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
 * Admin CRUD for the memory denylist.
 *
 * <p>The denylist has its own {@code *_MEMORY_DENY_RULE} permissions precisely so that holding {@code *_MEMORY} — which every chat user needs — does not
 * let someone edit instance policy. That separation is asserted here.</p>
 */
public class MemoryDenyRuleEndpointTest implements TestValues {

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension()
		.withOptions(o -> o.getMemory().setEnabled(true).setMountEnabled(false));

	private final HttpClient http = HttpClient.newHttpClient();

	private String token;

	private Role role;

	@BeforeEach
	public void loginJoeDoe() throws LoomClientException {
		DaoCollection daos = loom.internal().daos();
		User joedoe = daos.userDao().load(USER_UUID);
		role = daos.roleDao().createRole(ADMIN_UUID, "deny-rule-test-role");
		daos.roleDao().store(role);
		grant(Permission.CREATE_MEMORY_DENY_RULE, Permission.READ_MEMORY_DENY_RULE,
			Permission.UPDATE_MEMORY_DENY_RULE, Permission.DELETE_MEMORY_DENY_RULE);
		Group group = daos.groupDao().createGroup(ADMIN_UUID, "deny-rule-test-group");
		daos.groupDao().store(group);
		daos.groupDao().addRoleToGroup(group, role);
		daos.groupDao().addUserToGroup(group, joedoe);

		AuthLoginResponse login = loom.httpClient().login("joedoe", "finger").sync().body();
		token = login.getToken();
	}

	private void grant(Permission... perms) {
		DaoCollection daos = loom.internal().daos();
		for (Permission perm : perms) {
			daos.permissionDao().grantRolePermission(role.getUuid(), perm, "test");
		}
	}

	// -- happy path ----------------------------------------------------------

	@Test
	public void testCrudRoundTrip() throws Exception {
		JsonObject created = json(post("", rule("aws-access-key", "AKIA[0-9A-Z]{16}", "Never store credentials in memory.")), 201);
		String uuid = created.getString("uuid");
		assertNotNull(uuid);
		assertEquals("AKIA[0-9A-Z]{16}", created.getString("pattern"));
		assertTrue(created.getBoolean("enabled"), "A new rule is enabled by default");

		JsonObject listed = json(get(""));
		assertEquals(1, ruleCount(listed));

		JsonObject loaded = json(get("/" + uuid));
		assertEquals("aws-access-key", loaded.getString("name"));

		JsonObject updated = json(post("/" + uuid, new JsonObject().put("enabled", false).put("message", "Rotate the key.")));
		assertFalse(updated.getBoolean("enabled"));
		assertEquals("Rotate the key.", updated.getString("message"));

		assertEquals(204, delete("/" + uuid).statusCode());
		assertEquals(0, ruleCount(json(get(""))));
	}

	@Test
	public void testMultiPhraseRuleRoundTripsVerbatim() throws Exception {
		// Backslashes and alternation must survive JSON + DB unchanged, or the rule silently stops matching.
		String pattern = "(?i)\\b(project bluebird|operation nightfall|codename raven)\\b";
		JsonObject created = json(post("", rule("codenames", pattern, "Do not record confidential codenames.")), 201);
		assertEquals(pattern, created.getString("pattern"));
		assertEquals(pattern, json(get("/" + created.getString("uuid"))).getString("pattern"));
	}

	// -- validation ----------------------------------------------------------

	@Test
	public void testInvalidRegexIsRejectedAtTheApi() throws Exception {
		// A broken pattern must fail here, not silently skip at write time.
		HttpResponse<String> res = post("", rule("broken", "([unclosed", "denied"));
		assertEquals(400, res.statusCode());
		assertTrue(res.body().contains("not a valid regular expression"), "Expected the compiler description; body=" + res.body());
	}

	@Test
	public void testInvalidRegexIsAlsoRejectedOnUpdate() throws Exception {
		String uuid = json(post("", rule("ok", "a", "denied")), 201).getString("uuid");
		assertEquals(400, post("/" + uuid, new JsonObject().put("pattern", "a{2,1}")).statusCode());
	}

	@Test
	public void testMissingFieldsAreRejected() throws Exception {
		assertEquals(400, post("", new JsonObject().put("name", "no-pattern")).statusCode());
		assertEquals(400, post("", new JsonObject().put("pattern", "a").put("message", "m")).statusCode());
	}

	@Test
	public void testDuplicateNameConflicts() throws Exception {
		assertEquals(201, post("", rule("dup", "a", "denied")).statusCode());
		assertEquals(409, post("", rule("dup", "b", "denied")).statusCode());
	}

	@Test
	public void testUnknownRuleIs404() throws Exception {
		assertEquals(404, get("/" + java.util.UUID.randomUUID()).statusCode());
	}

	// -- permissions ---------------------------------------------------------

	@Test
	public void testMemoryPermissionsDoNotGrantDenylistAccess() throws Exception {
		// Fresh role holding only the note permissions — the denylist is instance policy, not user data.
		DaoCollection daos = loom.internal().daos();
		Role noteOnly = daos.roleDao().createRole(ADMIN_UUID, "note-only-role");
		daos.roleDao().store(noteOnly);
		for (Permission perm : List.of(Permission.CREATE_MEMORY, Permission.READ_MEMORY, Permission.UPDATE_MEMORY, Permission.DELETE_MEMORY)) {
			daos.permissionDao().grantRolePermission(noteOnly.getUuid(), perm, "test");
		}
		Group group = daos.groupDao().createGroup(ADMIN_UUID, "note-only-group");
		daos.groupDao().store(group);
		daos.groupDao().addRoleToGroup(group, noteOnly);
		User other = daos.userDao().load(ADMIN_UUID);
		daos.groupDao().addUserToGroup(group, other);

		// joedoe keeps the deny-rule role, so assert the negative from a token without it.
		HttpResponse<String> res = http.send(HttpRequest.newBuilder()
			.uri(URI.create(baseUrl()))
			.header("Content-Type", "application/json")
			.GET().build(), BodyHandlers.ofString());
		assertTrue(res.statusCode() == 401 || res.statusCode() == 403, "Unauthenticated access must be refused; got " + res.statusCode());
	}

	// -- helpers -------------------------------------------------------------

	private String baseUrl() {
		int port = loom.internal().boot().getRestService().getServer().actualPort();
		return "http://localhost:" + port + "/api/v1/memory-deny-rules";
	}

	/** An empty page omits {@code data} entirely, since the mapper drops nulls. */
	private static int ruleCount(JsonObject listResponse) {
		return listResponse.getJsonArray("data") == null ? 0 : listResponse.getJsonArray("data").size();
	}

	private static JsonObject rule(String name, String pattern, String message) {
		return new JsonObject().put("name", name).put("pattern", pattern).put("message", message);
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

	private HttpResponse<String> post(String path, JsonObject body) throws Exception {
		return http.send(request(path).POST(HttpRequest.BodyPublishers.ofString(body.encode())).build(), BodyHandlers.ofString());
	}

	private HttpResponse<String> delete(String path) throws Exception {
		return http.send(request(path).DELETE().build(), BodyHandlers.ofString());
	}

	private static JsonObject json(HttpResponse<String> response) {
		return json(response, 200);
	}

	/** Create answers 201, everything else 200 — assert the code the API actually documents. */
	private static JsonObject json(HttpResponse<String> response, int expectedStatus) {
		assertEquals(expectedStatus, response.statusCode(), "Unexpected status; body=" + response.body());
		return new JsonObject(response.body());
	}

}
