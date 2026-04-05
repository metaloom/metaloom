package io.metaloom.loom.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface CRUDDaoTestcases<DAO extends CRUDDao<T>, T extends Element<T>> extends FixtureElementProvider {

	DAO getDao();

	T createElement(User user, int i);

	@Test
	default void testCreate() {
		DAO dao = getDao();
		long before = dao.count();

		AtomicReference<UUID> ref = new AtomicReference<>();
		User creator = dummyUser();
		transaction(t -> {
			T element = createElement(creator, 0);
			assertNotNull(element);
			dao.store(element);
			assertNotNull(element.getUuid());
			ref.set(element.getUuid());
		});

		assertEquals(before + 1, dao.count());
		T loaded = dao.load(ref.get());
		assertNotNull(loaded);
		assertCreate(loaded);
	}

	@Test
	default void testDelete() {
		DAO dao = getDao();
		User user = dummyUser();

		// Create library
		T element = createElement(user, 0);
		assertNotNull(element);

		// Now assert deletion
		dao.delete(element);
		assertNull(dao.load(element.getUuid()), "The library should be deleted.");
	}

	@Test
	default void testUpdate() {
		DAO dao = getDao();
		User user = dummyUser();

		// Create and store
		T element = createElement(user, 0);
		dao.store(element);

		// Now update
		updateElement(element);
		dao.update(element);

		// Load and assert update was persisted
		T updatedElement = dao.load(element.getUuid());
		assertUpdate(updatedElement);

	}

	void assertCreate(T createdElement);

	void assertUpdate(T updatedElement);

	void updateElement(T element);

	@Test
	default void testLoad() {
		DAO dao = getDao();
		User user = dummyUser();

		// Create and store element
		T element = createElement(user, 0);

		dao.store(element);

		// Now load again
		assertNotNull(element.getUuid());
	}

	@Test
	default void testLoadPage() {
		long before = getDao().count();
		for (int i = 0; i < 1024; i++) {
			T element = createElement(dummyUser(), i);
			getDao().store(element);
		}
		assertEquals(before + 1024, getDao().count());

		UUID id = null;
		long totalFound = 0;
		while (true) {
			Page<T> page = getDao().loadPage(id, 30, null, null, null);
			if (page.isEmpty()) {
				break;
			} else {
				id = page.last().getUuid();
			}
			for (T element : page) {
				System.out.println(element);
				totalFound++;
			}
			System.out.println("--");
		}

		assertEquals(before + 1024, totalFound);
	}

}