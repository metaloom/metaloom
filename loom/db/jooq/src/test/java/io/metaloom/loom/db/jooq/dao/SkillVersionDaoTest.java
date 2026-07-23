package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillVersion;
import io.metaloom.loom.db.model.skill.SkillVersionDao;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonObject;

public class SkillVersionDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<SkillVersionDao, SkillVersion> {

	@Override
	public SkillVersionDao getDao() {
		return skillVersionDao();
	}

	/**
	 * Create a fresh skill and a first version for it. The version number is {@code i + 1} so that the {@code i == 0} case yields version 1.
	 */
	@Override
	public SkillVersion createElement(User user, int i) {
		Skill skill = skillDao().createSkill(user, "skillver_" + i, "description_" + i, "# Skill " + i + "\nInstructions.");
		skillDao().store(skill);

		SkillVersion version = getDao().createVersion(
			user.getUuid(),
			skill.getUuid(),
			i + 1,
			"description_" + i,
			"# Skill " + i + "\nInstructions.",
			new JsonObject().put("versionKey", "versionValue"));
		return version;
	}

	@Override
	public void assertCreate(SkillVersion createdElement) {
		assertEquals(1, createdElement.getVersionNumber());
		assertEquals("description_0", createdElement.getDescription());
		assertEquals("# Skill 0\nInstructions.", createdElement.getContent());
		assertNotNull(createdElement.getSkillUuid());
		assertNotNull(createdElement.getMeta());
	}

	@Override
	public void updateElement(SkillVersion element) {
		element.setDescription("updated_description");
		element.setContent("updated content");
	}

	@Override
	public void assertUpdate(SkillVersion updatedElement) {
		assertEquals("updated_description", updatedElement.getDescription());
		assertEquals("updated content", updatedElement.getContent());
	}

	private Skill newSkill(User user, String name) {
		Skill skill = skillDao().createSkill(user, name, "desc", "content v1");
		skillDao().store(skill);
		return skill;
	}

	private SkillVersion addVersion(User user, Skill skill, int versionNumber, String description, String content) {
		SkillVersion version = getDao().createVersion(user.getUuid(), skill.getUuid(), versionNumber, description, content, null);
		getDao().store(version);
		return version;
	}

	@Test
	public void testLoadBySkillOrdering() {
		User user = dummyUser();
		Skill skill = newSkill(user, "ordering_skill");
		addVersion(user, skill, 1, "d1", "c1");
		addVersion(user, skill, 2, "d2", "c2");
		addVersion(user, skill, 3, "d3", "c3");

		List<SkillVersion> versions = getDao().loadBySkill(skill.getUuid());
		assertEquals(3, versions.size());
		assertEquals(1, versions.get(0).getVersionNumber(), "Versions should come back ordered by version number ascending");
		assertEquals(2, versions.get(1).getVersionNumber());
		assertEquals(3, versions.get(2).getVersionNumber());
	}

	@Test
	public void testLoadLatestAndByVersion() {
		User user = dummyUser();
		Skill skill = newSkill(user, "latest_skill");
		addVersion(user, skill, 1, "d1", "c1");
		SkillVersion v2 = addVersion(user, skill, 2, "d2", "c2");

		SkillVersion latest = getDao().loadLatestBySkill(skill.getUuid());
		assertNotNull(latest);
		assertEquals(2, latest.getVersionNumber());
		assertEquals(v2.getUuid(), latest.getUuid());

		SkillVersion loaded = getDao().loadBySkillAndVersion(skill.getUuid(), 1);
		assertNotNull(loaded);
		assertEquals("d1", loaded.getDescription());

		assertNull(getDao().loadBySkillAndVersion(skill.getUuid(), 99), "Unknown version numbers should yield null");
	}

	@Test
	public void testDeleteVersionsAfter() {
		User user = dummyUser();
		Skill skill = newSkill(user, "revert_skill");
		addVersion(user, skill, 1, "d1", "c1");
		addVersion(user, skill, 2, "d2", "c2");
		addVersion(user, skill, 3, "d3", "c3");

		int deleted = getDao().deleteVersionsAfter(skill.getUuid(), 1);
		assertEquals(2, deleted, "Versions 2 and 3 should be deleted");

		List<SkillVersion> remaining = getDao().loadBySkill(skill.getUuid());
		assertEquals(1, remaining.size());
		assertEquals(1, remaining.get(0).getVersionNumber());
	}

	@Test
	public void testDeleteSkillCascadesVersions() {
		User user = dummyUser();
		Skill skill = newSkill(user, "cascade_skill");
		addVersion(user, skill, 1, "d1", "c1");
		addVersion(user, skill, 2, "d2", "c2");
		addVersion(user, skill, 3, "d3", "c3");

		assertEquals(3, getDao().loadBySkill(skill.getUuid()).size(), "All versions should exist before the skill is deleted");

		// Deleting the skill must cascade to its versions via the ON DELETE CASCADE FK.
		skillDao().delete(skill.getUuid());

		assertTrue(getDao().loadBySkill(skill.getUuid()).isEmpty(), "Deleting a skill must delete all of its versions (ON DELETE CASCADE)");
		assertNull(skillDao().load(skill.getUuid()), "The skill itself should be gone");
	}

}
