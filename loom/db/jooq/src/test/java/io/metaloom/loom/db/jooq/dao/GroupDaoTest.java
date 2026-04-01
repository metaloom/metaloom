package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.user.User;

public class GroupDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<GroupDao, Group> {

	@Override
	public GroupDao getDao() {
		return groupDao();
	}

	@Override
	public Group createElement(User user, int i) {
		return getDao().create(user, "group_" + i);
	}

	@Override
	public void assertCreate(Group createdElement) {
		assertEquals("group_0", createdElement.getName());
	}

	@Override
	public void assertUpdate(Group updatedElement) {
		assertEquals("new_name", updatedElement.getName());
	}

	@Override
	public void updateElement(Group group) {
		group.setName("new_name");
	}

}
