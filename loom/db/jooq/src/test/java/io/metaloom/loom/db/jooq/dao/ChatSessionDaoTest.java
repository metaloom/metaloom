package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.chatsession.ChatSession;
import io.metaloom.loom.db.model.chatsession.ChatSessionContextRef;
import io.metaloom.loom.db.model.chatsession.ChatSessionDao;
import io.metaloom.loom.db.model.chatsession.ChatSessionSkillPin;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public class ChatSessionDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<ChatSessionDao, ChatSession> {

	private static Stream<ChatSession> stream(Page<ChatSession> page) {
		return StreamSupport.stream(page.spliterator(), false);
	}

	@Override
	public ChatSessionDao getDao() {
		return chatSessionDao();
	}

	@Override
	public ChatSession createElement(User user, int i) {
		return getDao().createChatSession(user, "session_" + i, "description_" + i);
	}

	@Override
	public void assertCreate(ChatSession created) {
		assertEquals("session_0", created.getName());
		assertEquals("description_0", created.getDescription());
		assertFalse(created.isPublished(), "New sessions should not be published by default");
		assertNotNull(created.getTags(), "Tags should default to an empty array");
		assertEquals(0, created.getTags().length);
	}

	@Override
	public void updateElement(ChatSession session) {
		session.setName("updated_name");
		session.setDescription("updated_description");
		session.setPublished(true);
		session.setTags(new String[] { "video", "pipeline" });
	}

	@Override
	public void assertUpdate(ChatSession updated) {
		assertEquals("updated_name", updated.getName());
		assertEquals("updated_description", updated.getDescription());
		assertTrue(updated.isPublished(), "The published flag should round-trip through the DB");
		assertNotNull(updated.getTags());
		assertEquals(2, updated.getTags().length, "Tags should round-trip through the DB");
		assertEquals("video", updated.getTags()[0]);
	}

	@Test
	public void testFindByCreatorIsolation() {
		User userA = dummyUser();
		User userB = adminUser();

		ChatSession a = store(userA, "iso_a");
		ChatSession b = store(userB, "iso_b");

		Page<ChatSession> pageA = getDao().findByCreator(userA.getUuid(), null, 100, null, null, null);
		assertTrue(stream(pageA).anyMatch(s -> s.getUuid().equals(a.getUuid())), "User A should see their own session");
		assertTrue(stream(pageA).noneMatch(s -> s.getUuid().equals(b.getUuid())), "User A must not see user B's session");
	}

	@Test
	public void testFindPublished() {
		User user = dummyUser();
		ChatSession unpublished = store(user, "lib_unpublished");
		ChatSession published = store(user, "lib_published");
		published.setPublished(true);
		getDao().update(published);

		Page<ChatSession> page = getDao().findPublished(null, 100, null, null, null);
		assertTrue(stream(page).anyMatch(s -> s.getUuid().equals(published.getUuid())), "Published sessions should be listed in the library");
		assertTrue(stream(page).noneMatch(s -> s.getUuid().equals(unpublished.getUuid())), "Unpublished sessions must not be listed");
	}

	@Test
	public void testLoadByChat() {
		User user = dummyUser();
		Chat chat = chatDao().createChat(user, "chat title");
		chatDao().store(chat);

		ChatSession session = getDao().createChatSession(user, "by_chat", "desc");
		session.setChatUuid(chat.getUuid());
		getDao().store(session);

		ChatSession loaded = getDao().loadByChat(chat.getUuid());
		assertNotNull(loaded);
		assertEquals(session.getUuid(), loaded.getUuid());
		assertNull(getDao().loadByChat(java.util.UUID.randomUUID()), "Unknown chat should yield null");
	}

	@Test
	public void testContextRefsRoundtrip() {
		User user = dummyUser();
		ChatSession a = store(user, "ctx_a");
		ChatSession b = store(user, "ctx_b");

		getDao().replaceContextRefs(a.getUuid(), List.of(
			new ChatSessionContextRef(a.getUuid(), b.getUuid(), true, false, true, 0)));

		List<ChatSessionContextRef> refs = getDao().loadContextRefs(a.getUuid());
		assertEquals(1, refs.size());
		assertEquals(b.getUuid(), refs.get(0).getSourceSessionUuid());
		assertTrue(refs.get(0).isIncludeChatHistory());
		assertFalse(refs.get(0).isIncludeSkills());
		assertTrue(refs.get(0).isIncludeFilesystem());

		// Replace clears the previous set.
		getDao().replaceContextRefs(a.getUuid(), List.of());
		assertTrue(getDao().loadContextRefs(a.getUuid()).isEmpty());
	}

	@Test
	public void testSkillPinsRoundtrip() {
		User user = dummyUser();
		ChatSession session = store(user, "pins");
		Skill skill = newSkill(user, "pinned_skill");

		getDao().replaceSkillPins(session.getUuid(), List.of(new ChatSessionSkillPin(session.getUuid(), skill.getUuid(), 3)));
		List<ChatSessionSkillPin> pins = getDao().loadSkillPins(session.getUuid());
		assertEquals(1, pins.size());
		assertEquals(skill.getUuid(), pins.get(0).getSkillUuid());
		assertEquals(3, pins.get(0).getSkillVersion());
	}

	/**
	 * Deleting a session A that references another session B must cascade ONLY A's own rows (its context
	 * refs + skill pins). It must NOT delete the referenced session B, B's skill pins, or the skills.
	 */
	@Test
	public void testDeleteSessionDoesNotAffectReferencedSession() {
		User user = dummyUser();
		ChatSession a = store(user, "cascade_a");
		ChatSession b = store(user, "cascade_b");

		// B pins a real skill; A references B (chat history + filesystem) and pins the same skill.
		Skill skill = newSkill(user, "shared_skill");
		getDao().replaceSkillPins(b.getUuid(), List.of(new ChatSessionSkillPin(b.getUuid(), skill.getUuid(), 1)));
		getDao().replaceSkillPins(a.getUuid(), List.of(new ChatSessionSkillPin(a.getUuid(), skill.getUuid(), 1)));
		getDao().replaceContextRefs(a.getUuid(), List.of(new ChatSessionContextRef(a.getUuid(), b.getUuid(), true, true, true, 0)));

		assertEquals(1, getDao().loadContextRefs(a.getUuid()).size());
		assertEquals(1, getDao().loadSkillPins(a.getUuid()).size());
		assertEquals(1, getDao().loadSkillPins(b.getUuid()).size());

		// Delete A.
		getDao().delete(a.getUuid());

		// A and its own rows are gone.
		assertNull(getDao().load(a.getUuid()), "Session A should be deleted");
		assertTrue(getDao().loadContextRefs(a.getUuid()).isEmpty(), "A's context refs should be cascade-deleted");
		assertTrue(getDao().loadSkillPins(a.getUuid()).isEmpty(), "A's skill pins should be cascade-deleted");

		// B is completely unaffected.
		assertNotNull(getDao().load(b.getUuid()), "Deleting A must NOT delete the referenced session B");
		assertEquals(1, getDao().loadSkillPins(b.getUuid()).size(), "Deleting A must NOT delete B's skill pins");
		assertEquals(skill.getUuid(), getDao().loadSkillPins(b.getUuid()).get(0).getSkillUuid());

		// The skill itself is untouched.
		assertNotNull(skillDao().load(skill.getUuid()), "Deleting A must NOT delete the pinned skill");
	}

	private ChatSession store(User user, String name) {
		ChatSession session = getDao().createChatSession(user, name, "desc");
		getDao().store(session);
		return session;
	}

	private Skill newSkill(User user, String name) {
		Skill skill = skillDao().createSkill(user, name, "desc", "# content");
		skillDao().store(skill);
		return skill;
	}

}
