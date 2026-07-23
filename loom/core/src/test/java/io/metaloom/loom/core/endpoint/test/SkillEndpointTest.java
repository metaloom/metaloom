package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.skill.SkillCreateRequest;
import io.metaloom.loom.rest.model.skill.SkillListResponse;
import io.metaloom.loom.rest.model.skill.SkillResponse;
import io.metaloom.loom.rest.model.skill.SkillUpdateRequest;
import io.metaloom.loom.rest.model.skill.SkillVersionListResponse;

public class SkillEndpointTest extends AbstractCRUDEndpointTest {

	private SkillResponse createSkill(LoomHttpClient client, String name) throws LoomClientException {
		SkillCreateRequest request = new SkillCreateRequest();
		request.setName(name);
		request.setDescription("Description of " + name);
		request.setContent("# " + name + "\nInstructions for the agent.");
		return client.createSkill(request).sync().body();
	}

	private SkillResponse updateContent(LoomHttpClient client, java.util.UUID uuid, String content) throws LoomClientException {
		SkillUpdateRequest update = new SkillUpdateRequest();
		update.setContent(content);
		return client.updateSkill(uuid, update).sync().body();
	}

	private void loginJoeDoe(LoomHttpClient client) throws LoomClientException {
		// The joedoe fixture user only carries READ_USER by default. Direct user grants are limited to a single
		// permission per user (user_permission PK), so the skill permissions are granted via a group + role instead.
		DaoCollection daos = loom.internal().daos();
		User joedoe = daos.userDao().load(USER_UUID);
		Role role = daos.roleDao().createRole(ADMIN_UUID, "skill-test-role");
		daos.roleDao().store(role);
		for (Permission perm : List.of(Permission.CREATE_SKILL, Permission.READ_SKILL, Permission.UPDATE_SKILL, Permission.DELETE_SKILL,
			Permission.READ_SKILL_VERSION, Permission.RESTORE_SKILL_VERSION)) {
			daos.permissionDao().grantRolePermission(role.getUuid(), perm, "test");
		}
		Group group = daos.groupDao().create(joedoe, "skill-test-group");
		daos.groupDao().store(group);
		daos.groupDao().addRoleToGroup(group, role);
		daos.groupDao().addUserToGroup(group, joedoe);

		AuthLoginResponse loginResponse = client.login("joedoe", "finger").sync().body();
		client.setToken(loginResponse.getToken());
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		SkillResponse created = createSkill(client, "read-skill");

		SkillResponse skill = client.loadSkill(created.getUuid()).sync().body();
		assertNotNull(skill);
		assertEquals("read-skill", skill.getName());
		assertEquals("Description of read-skill", skill.getDescription());
		assertNotNull(skill.getContent());
		assertTrue(skill.getEnabled(), "New skills should be enabled by default");
		assertFalse(skill.getPublished(), "New skills should not be published by default");
		assertNull(skill.getOriginSkillUuid());
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		SkillResponse response = createSkill(client, "create-skill");
		assertNotNull(response);
		assertNotNull(response.getUuid());
		assertEquals("create-skill", response.getName());

		SkillResponse loaded = client.loadSkill(response.getUuid()).sync().body();
		assertEquals(response.getUuid(), loaded.getUuid());
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		SkillResponse created = createSkill(client, "delete-skill");
		assertNotNull(created.getUuid());

		client.deleteSkill(created.getUuid()).sync().body();
		expect(404, "Not Found", client.loadSkill(created.getUuid()));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		SkillResponse created = createSkill(client, "update-skill");

		SkillUpdateRequest update = new SkillUpdateRequest();
		update.setName("updated-skill");
		update.setDescription("Updated description");
		update.setContent("Updated content");
		update.setEnabled(false);
		update.setPublished(true);

		SkillResponse response = client.updateSkill(created.getUuid(), update).sync().body();
		assertEquals("updated-skill", response.getName());

		SkillResponse loaded = client.loadSkill(created.getUuid()).sync().body();
		assertEquals("updated-skill", loaded.getName());
		assertEquals("Updated description", loaded.getDescription());
		assertEquals("Updated content", loaded.getContent());
		assertFalse(loaded.getEnabled());
		assertTrue(loaded.getPublished());
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 42; i++) {
			createSkill(client, "page-skill-" + i);
		}
		SkillListResponse list = client.listSkills().sync().body();
		assertNotNull(list);
		assertNotNull(list.getData());
	}

	@Test
	public void testCrossUserIsolation() throws Exception {
		SkillResponse adminSkill;
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			adminSkill = createSkill(client, "admin-owned");
		}

		try (LoomHttpClient client = loom.httpClient()) {
			loginJoeDoe(client);

			// Foreign skills must be indistinguishable from missing ones
			expect(404, "Not Found", client.loadSkill(adminSkill.getUuid()));
			SkillUpdateRequest update = new SkillUpdateRequest();
			update.setName("stolen");
			expect(404, "Not Found", client.updateSkill(adminSkill.getUuid(), update));
			expect(404, "Not Found", client.deleteSkill(adminSkill.getUuid()));

			// The own list must not leak foreign skills
			SkillListResponse list = client.listSkills().sync().body();
			if (list.getData() != null) {
				assertTrue(list.getData().stream().noneMatch(s -> s.getUuid().equals(adminSkill.getUuid())),
					"The list must not contain foreign skills");
			}
		}

		// The skill must remain untouched for its owner
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			SkillResponse loaded = client.loadSkill(adminSkill.getUuid()).sync().body();
			assertEquals("admin-owned", loaded.getName());
		}
	}

	@Test
	public void testVersionIncrementAndList() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			SkillResponse created = createSkill(client, "versioned-skill");
			assertEquals(1, created.getVersionNumber(), "A freshly created skill should be at version 1");

			SkillResponse v2 = updateContent(client, created.getUuid(), "content v2");
			assertEquals(2, v2.getVersionNumber(), "A content edit should append a new version");
			SkillResponse v3 = updateContent(client, created.getUuid(), "content v3");
			assertEquals(3, v3.getVersionNumber());

			SkillVersionListResponse versions = client.listSkillVersions(created.getUuid()).sync().body();
			assertNotNull(versions.getData());
			assertEquals(3, versions.getData().size(), "All three versions should be listed");

			// The historic version still carries its original body
			SkillResponse historic = client.loadSkillVersion(created.getUuid(), 1).sync().body();
			assertEquals(1, historic.getVersionNumber());
			assertEquals("# versioned-skill\nInstructions for the agent.", historic.getContent(), "v1 must retain its original content");

			// The active skill reflects the newest content
			SkillResponse loaded = client.loadSkill(created.getUuid()).sync().body();
			assertEquals("content v3", loaded.getContent());
			assertEquals(3, loaded.getVersionNumber());
		}
	}

	@Test
	public void testToggleDoesNotBumpVersion() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			SkillResponse created = createSkill(client, "toggle-skill");

			// A toggle-only update (no body change) must not append a version
			SkillUpdateRequest publish = new SkillUpdateRequest();
			publish.setPublished(true);
			SkillResponse toggled = client.updateSkill(created.getUuid(), publish).sync().body();
			assertEquals(1, toggled.getVersionNumber(), "Toggling published must not append a new version");
			assertTrue(toggled.getPublished());

			SkillVersionListResponse versions = client.listSkillVersions(created.getUuid()).sync().body();
			assertEquals(1, versions.getData().size(), "There should still be a single version after a toggle-only edit");
		}
	}

	@Test
	public void testRevertDeletesNewerVersions() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			SkillResponse created = createSkill(client, "revert-skill");
			String originalContent = created.getContent();
			updateContent(client, created.getUuid(), "content v2");
			updateContent(client, created.getUuid(), "content v3");

			// Revert to v1 → newer versions are deleted and the active version is re-pointed
			SkillResponse reverted = client.restoreSkillVersion(created.getUuid(), 1).sync().body();
			assertEquals(1, reverted.getVersionNumber(), "After reverting, the active version should be v1");
			assertEquals(originalContent, reverted.getContent(), "The reverted skill should carry v1's content");

			SkillVersionListResponse versions = client.listSkillVersions(created.getUuid()).sync().body();
			assertEquals(1, versions.getData().size(), "Reverting to v1 must delete v2 and v3");

			// The deleted versions are gone
			expect(404, "Not Found", client.loadSkillVersion(created.getUuid(), 2));
			expect(404, "Not Found", client.loadSkillVersion(created.getUuid(), 3));

			// A subsequent edit continues numbering from the reverted version
			SkillResponse next = updateContent(client, created.getUuid(), "content v2-again");
			assertEquals(2, next.getVersionNumber(), "Editing after a revert should produce a fresh v2");
		}
	}

	@Test
	public void testVersionCrossUserIsolation() throws Exception {
		SkillResponse adminSkill;
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			adminSkill = createSkill(client, "admin-versioned");
			updateContent(client, adminSkill.getUuid(), "admin content v2");
		}

		try (LoomHttpClient client = loom.httpClient()) {
			loginJoeDoe(client);
			// A foreign skill's versions must be indistinguishable from missing ones
			expect(404, "Not Found", client.listSkillVersions(adminSkill.getUuid()));
			expect(404, "Not Found", client.loadSkillVersion(adminSkill.getUuid(), 1));
			expect(404, "Not Found", client.restoreSkillVersion(adminSkill.getUuid(), 1));
		}
	}

	@Test
	public void testLibraryAndInstall() throws Exception {
		SkillResponse published;
		SkillResponse unpublished;
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			published = createSkill(client, "shared-skill");
			SkillUpdateRequest publish = new SkillUpdateRequest();
			publish.setPublished(true);
			published = client.updateSkill(published.getUuid(), publish).sync().body();
			unpublished = createSkill(client, "private-skill");

			// Installing an own skill must be rejected
			expect(400, "Bad Request", client.installSkill(published.getUuid()));
		}

		try (LoomHttpClient client = loom.httpClient()) {
			loginJoeDoe(client);

			// Library lists the published skill but not the unpublished one
			SkillListResponse library = client.listSkillLibrary().sync().body();
			assertNotNull(library.getData());
			final SkillResponse publishedFinal = published;
			final SkillResponse unpublishedFinal = unpublished;
			assertTrue(library.getData().stream().anyMatch(s -> s.getUuid().equals(publishedFinal.getUuid())),
				"The published skill should be listed in the library");
			assertTrue(library.getData().stream().noneMatch(s -> s.getUuid().equals(unpublishedFinal.getUuid())),
				"Unpublished skills must not be listed in the library");

			// Unpublished foreign skills can't be installed
			expect(404, "Not Found", client.installSkill(unpublished.getUuid()));

			// Install copies the skill into the callers own set with provenance
			SkillResponse copy = client.installSkill(published.getUuid()).sync().body();
			assertNotNull(copy.getUuid());
			assertNotEquals(published.getUuid(), copy.getUuid(), "The install must create a copy");
			assertEquals("shared-skill", copy.getName());
			assertEquals(published.getUuid(), copy.getOriginSkillUuid(), "The copy should reference its origin");
			assertFalse(copy.getPublished(), "Installed copies must not be published themselves");

			// The copy is an own skill now
			SkillResponse loadedCopy = client.loadSkill(copy.getUuid()).sync().body();
			assertEquals(copy.getUuid(), loadedCopy.getUuid());

			// A second install resolves the name collision by suffixing
			SkillResponse secondCopy = client.installSkill(published.getUuid()).sync().body();
			assertEquals("shared-skill-2", secondCopy.getName());
		}
	}

}
