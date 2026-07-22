package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public class SkillDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<SkillDao, Skill> {

	private static Stream<Skill> stream(Page<Skill> page) {
		return StreamSupport.stream(page.spliterator(), false);
	}

	@Override
	public SkillDao getDao() {
		return skillDao();
	}

	@Override
	public Skill createElement(User user, int i) {
		return getDao().createSkill(user, "skill_" + i, "description_" + i, "# Skill " + i + "\nInstructions.");
	}

	@Override
	public void assertCreate(Skill createdElement) {
		assertEquals("skill_0", createdElement.getName());
		assertEquals("description_0", createdElement.getDescription());
		assertEquals("# Skill 0\nInstructions.", createdElement.getContent());
		assertTrue(createdElement.isEnabled(), "New skills should be enabled by default");
		assertFalse(createdElement.isPublished(), "New skills should not be published by default");
		assertNull(createdElement.getOriginSkillUuid(), "New skills should not carry an origin reference");
	}

	@Override
	public void updateElement(Skill skill) {
		skill.setName("updated_name");
		skill.setDescription("updated_description");
		skill.setContent("updated content");
		skill.setEnabled(false);
		skill.setPublished(true);
	}

	@Override
	public void assertUpdate(Skill updatedSkill) {
		assertEquals("updated_name", updatedSkill.getName());
		assertEquals("updated_description", updatedSkill.getDescription());
		assertEquals("updated content", updatedSkill.getContent());
		assertFalse(updatedSkill.isEnabled(), "The updated enabled flag should round-trip through the DB");
		assertTrue(updatedSkill.isPublished(), "The updated published flag should round-trip through the DB");
	}

	@Test
	public void testFindByCreatorIsolation() {
		User userA = dummyUser();
		User userB = adminUser();

		Skill skillA = getDao().createSkill(userA, "isolation_a", "descA", "contentA");
		getDao().store(skillA);
		Skill skillB = getDao().createSkill(userB, "isolation_b", "descB", "contentB");
		getDao().store(skillB);

		Page<Skill> pageA = getDao().findByCreator(userA.getUuid(), null, 100, null, null, null);
		assertTrue(stream(pageA).anyMatch(s -> s.getUuid().equals(skillA.getUuid())), "User A should see their own skill");
		assertTrue(stream(pageA).noneMatch(s -> s.getUuid().equals(skillB.getUuid())), "User A must not see user B's skill");

		Page<Skill> pageB = getDao().findByCreator(userB.getUuid(), null, 100, null, null, null);
		assertTrue(stream(pageB).anyMatch(s -> s.getUuid().equals(skillB.getUuid())), "User B should see their own skill");
		assertTrue(stream(pageB).noneMatch(s -> s.getUuid().equals(skillA.getUuid())), "User B must not see user A's skill");
	}

	@Test
	public void testLoadByUuids() {
		User user = dummyUser();
		Skill skill1 = getDao().createSkill(user, "load_by_uuid_1", "desc", "content");
		getDao().store(skill1);
		Skill skill2 = getDao().createSkill(user, "load_by_uuid_2", "desc", "content");
		getDao().store(skill2);

		List<Skill> loaded = getDao().loadByUuids(List.of(skill1.getUuid(), skill2.getUuid(), UUID.randomUUID()));
		assertEquals(2, loaded.size(), "Unknown uuids should be silently omitted");
		assertTrue(loaded.stream().anyMatch(s -> s.getUuid().equals(skill1.getUuid())));
		assertTrue(loaded.stream().anyMatch(s -> s.getUuid().equals(skill2.getUuid())));

		assertTrue(getDao().loadByUuids(List.of()).isEmpty(), "An empty uuid list should yield an empty result");
	}

	@Test
	public void testFindPublished() {
		User user = dummyUser();
		Skill unpublished = getDao().createSkill(user, "lib_unpublished", "desc", "content");
		getDao().store(unpublished);

		Skill published = getDao().createSkill(user, "lib_published", "desc", "content");
		published.setPublished(true);
		getDao().store(published);

		Page<Skill> page = getDao().findPublished(null, 100, null, null, null);
		assertTrue(stream(page).anyMatch(s -> s.getUuid().equals(published.getUuid())), "Published skills should be listed in the library");
		assertTrue(stream(page).noneMatch(s -> s.getUuid().equals(unpublished.getUuid())), "Unpublished skills must not be listed in the library");
	}

	@Test
	public void testLoadByName() {
		User user = dummyUser();
		Skill skill = getDao().createSkill(user, "by_name", "desc", "content");
		getDao().store(skill);

		Skill loaded = getDao().loadByName(user.getUuid(), "by_name");
		assertNotNull(loaded);
		assertEquals(skill.getUuid(), loaded.getUuid());

		assertNull(getDao().loadByName(user.getUuid(), "does_not_exist"), "Unknown names should yield null");
		assertNull(getDao().loadByName(adminUser().getUuid(), "by_name"), "The name lookup must be owner-scoped");
	}

	@Test
	public void testOriginDeletionKeepsCopy() {
		User user = dummyUser();
		Skill origin = getDao().createSkill(user, "origin_skill", "desc", "content");
		origin.setPublished(true);
		getDao().store(origin);

		Skill copy = getDao().createSkill(adminUser(), "installed_skill", "desc", "content");
		copy.setOriginSkillUuid(origin.getUuid());
		getDao().store(copy);

		Skill loadedCopy = getDao().load(copy.getUuid());
		assertEquals(origin.getUuid(), loadedCopy.getOriginSkillUuid(), "The origin reference should round-trip through the DB");

		getDao().delete(origin);

		loadedCopy = getDao().load(copy.getUuid());
		assertNotNull(loadedCopy, "Deleting the origin must keep the installed copy");
		assertNull(loadedCopy.getOriginSkillUuid(), "The origin reference should be cleared via ON DELETE SET NULL");
	}

}
