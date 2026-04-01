package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.model.user.User;

public class CollectionDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<CollectionDao, Collection> {

	@Override
	public CollectionDao getDao() {
		return collectionDao();
	}

	@Override
	public Collection createElement(User user, int i) {
		return getDao().createCollection(user, "name_" + i);
	}

	@Override
	public void assertCreate(Collection createdElement) {
		assertEquals("name_" + 0, createdElement.getName());
	}

	@Override
	public void assertUpdate(Collection updatedElement) {
		assertEquals("new_name", updatedElement.getName());
	}

	@Override
	public void updateElement(Collection collection) {
		collection.setName("new_name");
	}

}
