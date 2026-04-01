package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.blacklist.Blacklist;
import io.metaloom.loom.db.model.blacklist.BlacklistDao;
import io.metaloom.loom.db.model.user.User;

public class BlacklistDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<BlacklistDao, Blacklist> {

	@Override
	public BlacklistDao getDao() {
		return blacklistDao();
	}

	@Override
	public Blacklist createElement(User user, int i) {
		return getDao().createBlacklist(user, asset(), "name_" + i);
	}

	@Override
	public void assertCreate(Blacklist createdElement) {
		assertEquals("name_0", createdElement.getName());
	}

	@Override
	public void assertUpdate(Blacklist updatedElement) {
		assertEquals("new_name", updatedElement.getName());
	}

	@Override
	public void updateElement(Blacklist element) {
		element.setName("new_name");
	}

}
