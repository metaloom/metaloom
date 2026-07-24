package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.loom.Loom;
import io.metaloom.loom.db.model.loom.LoomDao;

public class LoomDaoTest extends AbstractJooqTest {

	public LoomDao getDao() {
		return loomDao();
	}

	@Test
	public void testLoadUpdateRoundtrip() {
		LoomDao dao = getDao();

		// Start from a clean singleton state - the migration does not seed the row.
		dao.clear();
		assertNull(dao.load(), "No loom row should exist before it is created");
		assertEquals(0, dao.count());

		// Create the singleton row.
		Loom created = dao.createLoom("2.5");
		assertNotNull(created, "The created loom must not be null");
		assertEquals("2.5", created.getDatabaseRevision());
		assertNotNull(created.getLastUsedTimestamp());
		assertEquals(1, dao.count(), "Exactly one loom row must exist");

		// Load the singleton back.
		Loom loaded = dao.load();
		assertNotNull(loaded, "The loom row must be loadable after creation");
		assertEquals("2.5", loaded.getDatabaseRevision());
		assertNotNull(loaded.getLastUsedTimestamp());

		// Update the revision and timestamp. Postgres timestamps have microsecond precision.
		Instant newTimestamp = Instant.parse("2026-07-24T10:15:30.123456Z").truncatedTo(ChronoUnit.MICROS);
		loaded.setDatabaseRevision("2.6")
			.setLastUsedTimestamp(newTimestamp);
		dao.update(loaded);

		// Reload and assert the update persisted without adding a second row.
		Loom reloaded = dao.load();
		assertEquals("2.6", reloaded.getDatabaseRevision());
		assertEquals(newTimestamp, reloaded.getLastUsedTimestamp());
		assertEquals(1, dao.count(), "Update must not create an additional loom row");
	}
}
