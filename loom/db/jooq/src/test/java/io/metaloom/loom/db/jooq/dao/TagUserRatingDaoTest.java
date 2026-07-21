package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.tag.TagDao;

public class TagUserRatingDaoTest extends AbstractJooqTest {

	private UUID createTag() {
		TagDao dao = tagDao();
		AtomicReference<UUID> ref = new AtomicReference<>();
		transaction(t -> {
			Tag tag = dao.createTag(dummyUser(), "rated-tag", "colors");
			dao.store(tag);
			ref.set(tag.getUuid());
		});
		return ref.get();
	}

	@Test
	public void testStoreAndReadIsolation() {
		TagDao dao = tagDao();
		UUID tagUuid = createTag();

		// User A (dummy) rates the tag.
		transaction(t -> dao.storeUserRating(tagUuid, USER_UUID, 7));

		// User A can read their own rating.
		assertEquals(Integer.valueOf(7), dao.readUserRating(tagUuid, USER_UUID), "The dummy user should see their rating");

		// User B (admin) has not rated the tag - the rating is per-user isolated.
		assertNull(dao.readUserRating(tagUuid, ADMIN_UUID), "The admin user must not see the dummy user's rating");
	}

	@Test
	public void testUpsert() {
		TagDao dao = tagDao();
		UUID tagUuid = createTag();

		transaction(t -> dao.storeUserRating(tagUuid, USER_UUID, 4));
		assertEquals(Integer.valueOf(4), dao.readUserRating(tagUuid, USER_UUID));

		// Storing again for the same (tag, user) updates rather than inserts a second row.
		transaction(t -> dao.storeUserRating(tagUuid, USER_UUID, 9));
		assertEquals(Integer.valueOf(9), dao.readUserRating(tagUuid, USER_UUID), "The rating should have been updated in place");
	}

	@Test
	public void testDelete() {
		TagDao dao = tagDao();
		UUID tagUuid = createTag();

		transaction(t -> dao.storeUserRating(tagUuid, USER_UUID, 5));
		assertEquals(Integer.valueOf(5), dao.readUserRating(tagUuid, USER_UUID));

		transaction(t -> dao.deleteUserRating(tagUuid, USER_UUID));
		assertNull(dao.readUserRating(tagUuid, USER_UUID), "The rating should have been removed");
	}

}
