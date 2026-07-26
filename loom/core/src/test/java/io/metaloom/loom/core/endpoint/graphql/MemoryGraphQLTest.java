package io.metaloom.loom.core.endpoint.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.db.model.memory.MemoryDenyRule;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.vertx.core.json.JsonObject;

/**
 * GraphQL read tests for the {@code MemoryEntry}, {@code MemoryScopeStats} and {@code MemoryDenyRule} domain elements.
 *
 * <p>The memory tables always exist (the migration is unconditional) and the DAOs are always wired, so the GraphQL memory queries work regardless of the
 * memory feature toggle that only gates the REST endpoint. Each test seeds its own entries into the {@code USER} scope of the admin user.</p>
 */
public class MemoryGraphQLTest extends AbstractGraphQLTest {

	/**
	 * Store a memory entry in the admin user's private scope.
	 */
	private MemoryEntry seedEntry(String memoryId, String title, String body) {
		UUID adminUuid = adminUuid();
		MemoryEntry entry = daos().memoryEntryDao().createMemoryEntry(adminUuid, MemoryScope.USER, adminUuid, memoryId);
		entry.setTitle(title);
		entry.setBody(body);
		entry.setSize(body.getBytes().length);
		entry.setSha256("0".repeat(64));
		daos().memoryEntryDao().store(entry);
		return entry;
	}

	@Test
	public void testMemoryEntryByUuid() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			MemoryEntry entry = seedEntry("notes/a.md", "Note A", "Body of note A");

			JsonObject variables = new JsonObject().put("uuid", entry.getUuid().toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { memoryEntry(uuid: $uuid) { uuid scope memoryId title body size version } }", variables);

			Map<String, Object> loaded = object(data, "memoryEntry");
			assertNotNull(loaded);
			assertEquals(entry.getUuid().toString(), loaded.get("uuid"));
			assertEquals("USER", loaded.get("scope"));
			assertEquals("notes/a.md", loaded.get("memoryId"));
			assertEquals("Note A", loaded.get("title"));
			assertEquals("Body of note A", loaded.get("body"));
		}
	}

	@Test
	public void testMemoryEntryByPath() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			seedEntry("notes/b.md", "Note B", "Body of note B");

			JsonObject variables = new JsonObject()
				.put("scope", "USER")
				.put("scopeUuid", adminUuid().toString())
				.put("memoryId", "notes/b.md");
			Map<String, Object> data = data(client,
				"query($scope: MemoryScope!, $scopeUuid: ID!, $memoryId: String!) { memoryEntryByPath(scope: $scope, scopeUuid: $scopeUuid, memoryId: $memoryId) { title body } }",
				variables);

			Map<String, Object> loaded = object(data, "memoryEntryByPath");
			assertNotNull(loaded);
			assertEquals("Note B", loaded.get("title"));
			assertEquals("Body of note B", loaded.get("body"));
		}
	}

	@Test
	public void testMemoryEntriesListAndPrefix() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			seedEntry("projects/x.md", "X", "body x");
			seedEntry("projects/y.md", "Y", "body y");
			seedEntry("scratch/z.md", "Z", "body z");

			JsonObject variables = new JsonObject()
				.put("scope", "USER")
				.put("scopeUuid", adminUuid().toString())
				.put("prefix", "projects/");
			Map<String, Object> data = data(client,
				"query($scope: MemoryScope!, $scopeUuid: ID!, $prefix: String) { memoryEntries(scope: $scope, scopeUuid: $scopeUuid, prefix: $prefix) { memoryId } }",
				variables);

			List<Map<String, Object>> entries = list(data, "memoryEntries");
			assertEquals(2, entries.size(), "Only the two projects/ entries should match the prefix");
			assertTrue(entries.stream().allMatch(e -> String.valueOf(e.get("memoryId")).startsWith("projects/")));
		}
	}

	@Test
	public void testMemoryStats() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			seedEntry("stats/a.md", "A", "aaaa");
			seedEntry("stats/b.md", "B", "bbbbbb");

			JsonObject variables = new JsonObject()
				.put("scope", "USER")
				.put("scopeUuid", adminUuid().toString());
			Map<String, Object> data = data(client,
				"query($scope: MemoryScope!, $scopeUuid: ID!) { memoryStats(scope: $scope, scopeUuid: $scopeUuid) { count bytes } }",
				variables);

			Map<String, Object> stats = object(data, "memoryStats");
			assertNotNull(stats);
			// count is an Int, bytes is the Long scalar (may deserialize as Integer/Long depending on magnitude).
			assertEquals(2, stats.get("count"));
			assertEquals(10L, ((Number) stats.get("bytes")).longValue(), "4 + 6 body bytes");
		}
	}

	@Test
	public void testMemoryDenyRules() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			UUID adminUuid = adminUuid();
			MemoryDenyRule enabled = daos().memoryDenyRuleDao().createMemoryDenyRule(adminUuid, "block-secret", "(?i)\\bsecret\\b",
				"That note looks like it contains a secret");
			enabled.setEnabled(true);
			daos().memoryDenyRuleDao().store(enabled);

			MemoryDenyRule disabled = daos().memoryDenyRuleDao().createMemoryDenyRule(adminUuid, "block-legacy", "legacy", "Disabled rule");
			disabled.setEnabled(false);
			daos().memoryDenyRuleDao().store(disabled);

			// By uuid
			JsonObject vars = new JsonObject().put("uuid", enabled.getUuid().toString());
			Map<String, Object> byUuid = data(client,
				"query($uuid: ID!) { memoryDenyRule(uuid: $uuid) { name pattern message enabled } }", vars);
			Map<String, Object> rule = object(byUuid, "memoryDenyRule");
			assertEquals("block-secret", rule.get("name"));
			assertEquals(Boolean.TRUE, rule.get("enabled"));

			// enabledOnly=true skips the disabled rule
			Map<String, Object> enabledOnly = data(client, "{ memoryDenyRules(enabledOnly: true) { name } }");
			List<Map<String, Object>> rules = list(enabledOnly, "memoryDenyRules");
			assertTrue(rules.stream().anyMatch(r -> "block-secret".equals(r.get("name"))));
			assertTrue(rules.stream().noneMatch(r -> "block-legacy".equals(r.get("name"))), "Disabled rule must be excluded");

			// Without the flag both rules show up
			Map<String, Object> all = data(client, "{ memoryDenyRules { name } }");
			List<Map<String, Object>> allRules = list(all, "memoryDenyRules");
			assertTrue(allRules.stream().anyMatch(r -> "block-legacy".equals(r.get("name"))), "All rules should be listed");
		}
	}

	@Test
	public void testInvalidScopeIsBadUserInput() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// An unknown enum literal is a GraphQL validation error.
			JsonObject variables = new JsonObject()
				.put("scope", "NONSENSE")
				.put("scopeUuid", adminUuid().toString());
			assertHasErrors(query(client,
				"query($scope: MemoryScope!, $scopeUuid: ID!) { memoryStats(scope: $scope, scopeUuid: $scopeUuid) { count } }", variables));
		}
	}

	@Test
	@Override
	public void testIndividualRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			String uuid = UUID.randomUUID().toString();
			assertRetrievalForbidden(client, Permission.READ_MEMORY, "{ memoryEntry(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_MEMORY,
				"{ memoryEntryByPath(scope: USER, scopeUuid: \"" + uuid + "\", memoryId: \"notes/a.md\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_MEMORY,
				"{ memoryStats(scope: USER, scopeUuid: \"" + uuid + "\") { count } }");
			assertRetrievalForbidden(client, Permission.READ_MEMORY_DENY_RULE, "{ memoryDenyRule(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_MEMORY_DENY_RULE, "{ memoryDenyRuleByName(name: \"block-secret\") { uuid } }");
		}
	}

	@Test
	@Override
	public void testListRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			String uuid = UUID.randomUUID().toString();
			assertRetrievalForbidden(client, Permission.READ_MEMORY,
				"{ memoryEntries(scope: USER, scopeUuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_MEMORY_DENY_RULE, "{ memoryDenyRules { uuid } }");
		}
	}
}
