package io.metaloom.loom.core.endpoint.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillVersion;
import io.vertx.core.json.JsonObject;

/**
 * GraphQL read tests for the {@code Skill} and {@code SkillVersion} domain elements. The fixture does not provision skills, so each test seeds a skill with
 * two versions (the second wired as the active one) via the DAO layer.
 */
public class SkillGraphQLTest extends AbstractGraphQLTest {

	/**
	 * Seed a skill with two versions and mark version 2 as the active one. Returns the skill uuid.
	 */
	private UUID seedSkill() {
		UUID adminUuid = adminUuid();

		Skill skill = daos().skillDao().createSkill(adminUuid, "graphql-test-skill", "A test skill", "# v1 content");
		daos().skillDao().store(skill);

		SkillVersion v1 = daos().skillVersionDao().createVersion(adminUuid, skill.getUuid(), 1, "A test skill", "# v1 content", null);
		daos().skillVersionDao().store(v1);
		SkillVersion v2 = daos().skillVersionDao().createVersion(adminUuid, skill.getUuid(), 2, "A test skill v2", "# v2 content", null);
		daos().skillVersionDao().store(v2);

		skill.setActiveVersionUuid(v2.getUuid());
		skill.setActiveVersionNumber(2);
		daos().skillDao().update(skill);

		return skill.getUuid();
	}

	@Test
	public void testSkillByUuid() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID skillUuid = seedSkill();

			JsonObject variables = new JsonObject().put("uuid", skillUuid.toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { skill(uuid: $uuid) { uuid name description enabled published activeVersionNumber } }", variables);

			Map<String, Object> skill = object(data, "skill");
			assertNotNull(skill);
			assertEquals(skillUuid.toString(), skill.get("uuid"));
			assertEquals("graphql-test-skill", skill.get("name"));
			assertEquals(Boolean.TRUE, skill.get("enabled"));
			assertEquals(2, skill.get("activeVersionNumber"));
		}
	}

	@Test
	public void testSkillActiveAndLatestVersion() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID skillUuid = seedSkill();

			JsonObject variables = new JsonObject().put("uuid", skillUuid.toString());
			Map<String, Object> data = data(client,
				"query($uuid: ID!) { skill(uuid: $uuid) { activeVersion { versionNumber content } latestVersion { versionNumber } } }", variables);

			Map<String, Object> skill = object(data, "skill");
			Map<String, Object> active = object(skill, "activeVersion");
			assertNotNull(active, "The active version back reference should resolve");
			assertEquals(2, active.get("versionNumber"));
			assertEquals("# v2 content", active.get("content"));

			Map<String, Object> latest = object(skill, "latestVersion");
			assertEquals(2, latest.get("versionNumber"), "Version 2 is the highest numbered version");
		}
	}

	@Test
	public void testSkillVersions() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID skillUuid = seedSkill();

			JsonObject variables = new JsonObject().put("skillUuid", skillUuid.toString());
			Map<String, Object> data = data(client,
				"query($skillUuid: ID!) { skillVersions(skillUuid: $skillUuid) { versionNumber skill { uuid } } }", variables);

			List<Map<String, Object>> versions = list(data, "skillVersions");
			assertEquals(2, versions.size(), "The skill has two versions");
			// Versions are ordered by version number ascending.
			assertEquals(1, versions.get(0).get("versionNumber"));
			assertEquals(2, versions.get(1).get("versionNumber"));
			assertEquals(skillUuid.toString(), object(versions.get(0), "skill").get("uuid"));
		}
	}

	@Test
	public void testSkillVersionByNumber() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID skillUuid = seedSkill();

			JsonObject variables = new JsonObject().put("skillUuid", skillUuid.toString()).put("versionNumber", 1);
			Map<String, Object> data = data(client,
				"query($skillUuid: ID!, $versionNumber: Int!) { skillVersionByNumber(skillUuid: $skillUuid, versionNumber: $versionNumber) { versionNumber content } }",
				variables);

			Map<String, Object> version = object(data, "skillVersionByNumber");
			assertNotNull(version);
			assertEquals(1, version.get("versionNumber"));
			assertEquals("# v1 content", version.get("content"));
		}
	}

	@Test
	public void testLatestSkillVersionQuery() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID skillUuid = seedSkill();

			JsonObject variables = new JsonObject().put("skillUuid", skillUuid.toString());
			Map<String, Object> data = data(client,
				"query($skillUuid: ID!) { latestSkillVersion(skillUuid: $skillUuid) { versionNumber } }", variables);

			Map<String, Object> version = object(data, "latestSkillVersion");
			assertEquals(2, version.get("versionNumber"));
		}
	}

	@Test
	public void testSkillList() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UUID skillUuid = seedSkill();

			Map<String, Object> data = data(client, "{ skills { uuid name } }");
			List<Map<String, Object>> skills = list(data, "skills");
			assertTrue(skills.stream().anyMatch(s -> skillUuid.toString().equals(s.get("uuid"))), "The seeded skill should be listed");
		}
	}

	@Test
	@Override
	public void testIndividualRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			String uuid = UUID.randomUUID().toString();
			assertRetrievalForbidden(client, Permission.READ_SKILL, "{ skill(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_SKILL_VERSION, "{ skillVersion(uuid: \"" + uuid + "\") { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_SKILL_VERSION,
				"{ skillVersionByNumber(skillUuid: \"" + uuid + "\", versionNumber: 1) { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_SKILL_VERSION, "{ latestSkillVersion(skillUuid: \"" + uuid + "\") { uuid } }");
		}
	}

	@Test
	@Override
	public void testListRetrievalRequiresPermission() throws Exception {
		try (LoomHttpClient client = loginPermissionlessClient()) {
			String uuid = UUID.randomUUID().toString();
			assertRetrievalForbidden(client, Permission.READ_SKILL, "{ skills { uuid } }");
			assertRetrievalForbidden(client, Permission.READ_SKILL_VERSION, "{ skillVersions(skillUuid: \"" + uuid + "\") { uuid } }");
		}
	}
}
