package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.db.model.memory.MemoryEntryDao;
import io.metaloom.loom.db.model.memory.MemoryEntryDao.MemoryScopeKey;
import io.metaloom.loom.db.model.memory.MemoryEntryDao.MemoryScopeStats;
import io.metaloom.loom.db.model.user.User;

public class MemoryEntryDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<MemoryEntryDao, MemoryEntry> {

	@Override
	public MemoryEntryDao getDao() {
		return memoryEntryDao();
	}

	@Override
	public MemoryEntry createElement(User user, int i) {
		MemoryEntry entry = getDao().createMemoryEntry(user.getUuid(), MemoryScope.USER, user.getUuid(), "notes-" + i + ".md");
		entry.setTitle("Title " + i);
		entry.setBody("body " + i);
		entry.setSize(("body " + i).length());
		entry.setSha256("digest-" + i);
		return entry;
	}

	@Override
	public void assertCreate(MemoryEntry created) {
		assertEquals(MemoryScope.USER, created.getScope());
		assertEquals("notes-0.md", created.getMemoryId());
		assertEquals("Title 0", created.getTitle());
		assertEquals("body 0", created.getBody());
		assertEquals(1, created.getVersion(), "A new entry starts at version 1");
	}

	@Override
	public void updateElement(MemoryEntry entry) {
		entry.setTitle("updated title");
		entry.setBody("updated body");
		entry.setSize("updated body".length());
		entry.setVersion(entry.getVersion() + 1);
		entry.setSessionName("jOOQ regen");
	}

	@Override
	public void assertUpdate(MemoryEntry updated) {
		assertEquals("updated title", updated.getTitle());
		assertEquals("updated body", updated.getBody());
		assertEquals(2, updated.getVersion(), "The version must round-trip through the DB");
		assertEquals("jOOQ regen", updated.getSessionName());
	}

	@Test
	public void testLoadByPathIsScopeKeyed() {
		User user = dummyUser();
		UUID otherScope = UUID.randomUUID();

		MemoryEntry mine = store(user, MemoryScope.USER, user.getUuid(), "shared-name.md", "mine");
		store(user, MemoryScope.GROUP, otherScope, "shared-name.md", "theirs");

		// The same id in two scopes are two independent rows.
		assertEquals("mine", getDao().loadByPath(MemoryScope.USER, user.getUuid(), "shared-name.md").getBody());
		assertEquals("theirs", getDao().loadByPath(MemoryScope.GROUP, otherScope, "shared-name.md").getBody());

		// ...and a scope which holds nothing sees nothing, even with a matching id.
		assertNull(getDao().loadByPath(MemoryScope.SPACE, user.getUuid(), "shared-name.md"));
		assertNotNull(mine.getUuid());
	}

	@Test
	public void testNaturalKeyIsUnique() {
		User user = dummyUser();
		store(user, MemoryScope.USER, user.getUuid(), "dup.md", "first");

		assertThrows(Exception.class, () -> store(user, MemoryScope.USER, user.getUuid(), "dup.md", "second"),
			"The (scope, scope_uuid, memory_id) unique constraint must reject a duplicate");
	}

	@Test
	public void testListByScopeFiltersByPrefix() {
		User user = dummyUser();
		store(user, MemoryScope.USER, user.getUuid(), "projects/a.md", "a");
		store(user, MemoryScope.USER, user.getUuid(), "projects/b.md", "b");
		store(user, MemoryScope.USER, user.getUuid(), "notes.md", "c");

		assertEquals(3, getDao().listByScope(MemoryScope.USER, user.getUuid(), null, 50).size());
		assertEquals(2, getDao().listByScope(MemoryScope.USER, user.getUuid(), "projects/", 50).size());
		assertEquals(1, getDao().listByScope(MemoryScope.USER, user.getUuid(), "notes", 50).size());
	}

	@Test
	public void testListByScopeRespectsTheLimit() {
		User user = dummyUser();
		for (int i = 0; i < 5; i++) {
			store(user, MemoryScope.USER, user.getUuid(), "note-" + i + ".md", "b");
		}
		assertEquals(2, getDao().listByScope(MemoryScope.USER, user.getUuid(), null, 2).size());
	}

	@Test
	public void testListIndexSpansScopesAndOmitsTheBody() {
		User user = dummyUser();
		UUID groupUuid = UUID.randomUUID();
		store(user, MemoryScope.USER, user.getUuid(), "mine.md", "PRIVATE BODY");
		store(user, MemoryScope.GROUP, groupUuid, "ours.md", "SHARED BODY");

		List<MemoryEntry> index = getDao().listIndex(List.of(
			new MemoryScopeKey(MemoryScope.USER, user.getUuid()),
			new MemoryScopeKey(MemoryScope.GROUP, groupUuid)), 50);

		assertEquals(2, index.size());
		// The index feeds the system prompt on every turn — pulling bodies would make that a full-table read.
		for (MemoryEntry entry : index) {
			assertNull(entry.getBody(), "listIndex must not project the body");
			assertNotNull(entry.getMemoryId());
			assertNotNull(entry.getTitle());
		}
	}

	@Test
	public void testListIndexWithoutScopesReturnsNothing() {
		assertTrue(getDao().listIndex(List.of(), 50).isEmpty());
		assertTrue(getDao().listIndex(null, 50).isEmpty());
	}

	@Test
	public void testStatsCountsAndSumsOnlyTheGivenScope() {
		User user = dummyUser();
		UUID groupUuid = UUID.randomUUID();
		storeSized(user, MemoryScope.USER, user.getUuid(), "a.md", 100);
		storeSized(user, MemoryScope.USER, user.getUuid(), "b.md", 250);
		storeSized(user, MemoryScope.GROUP, groupUuid, "c.md", 999);

		MemoryScopeStats stats = getDao().stats(MemoryScope.USER, user.getUuid());
		assertEquals(2, stats.count());
		assertEquals(350L, stats.bytes());

		MemoryScopeStats empty = getDao().stats(MemoryScope.SPACE, UUID.randomUUID());
		assertEquals(0, empty.count());
		assertEquals(0L, empty.bytes(), "An empty scope must sum to zero, not null");
	}

	@Test
	public void testDeleteByPath() {
		User user = dummyUser();
		store(user, MemoryScope.USER, user.getUuid(), "gone.md", "b");

		assertTrue(getDao().deleteByPath(MemoryScope.USER, user.getUuid(), "gone.md"));
		assertNull(getDao().loadByPath(MemoryScope.USER, user.getUuid(), "gone.md"));
		assertFalse(getDao().deleteByPath(MemoryScope.USER, user.getUuid(), "gone.md"), "A second delete removes nothing");
	}

	@Test
	public void testDeleteByPathCannotReachAnotherScope() {
		User user = dummyUser();
		UUID groupUuid = UUID.randomUUID();
		store(user, MemoryScope.GROUP, groupUuid, "target.md", "b");

		assertFalse(getDao().deleteByPath(MemoryScope.USER, user.getUuid(), "target.md"));
		assertNotNull(getDao().loadByPath(MemoryScope.GROUP, groupUuid, "target.md"));
	}

	private MemoryEntry store(User user, MemoryScope scope, UUID scopeUuid, String memoryId, String body) {
		MemoryEntry entry = getDao().createMemoryEntry(user.getUuid(), scope, scopeUuid, memoryId);
		entry.setTitle("Title of " + memoryId);
		entry.setBody(body);
		entry.setSize(body.length());
		entry.setSha256("digest");
		getDao().store(entry);
		return entry;
	}

	private MemoryEntry storeSized(User user, MemoryScope scope, UUID scopeUuid, String memoryId, int size) {
		MemoryEntry entry = getDao().createMemoryEntry(user.getUuid(), scope, scopeUuid, memoryId);
		entry.setTitle("Title of " + memoryId);
		entry.setBody("x");
		entry.setSize(size);
		entry.setSha256("digest");
		getDao().store(entry);
		return entry;
	}

}
