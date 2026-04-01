package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;

public class UserDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<UserDao, User> {

	@Override
	public UserDao getDao() {
		return userDao();
	}

	@Override
	public User createElement(User user, int i) {
		return getDao().createUser("user_" + i);
	}

	@Override
	public void assertCreate(User createdElement) {
		assertEquals("user_0", createdElement.getUsername());
	}

	@Override
	public void assertUpdate(User updatedElement) {
		assertEquals("new_name", updatedElement.getUsername());
	}

	@Override
	public void updateElement(User user) {
		user.setUsername("new_name");
	}

	@Test
	public void testBasics() {
		User createdUser = userDao().createUser("dummy");
		userDao().store(createdUser);
		Stream<? extends User> obs = userDao().findAll();

		User loadedUser = userDao().load(createdUser.getUuid());
		assertNotNull(loadedUser);
		obs.forEach(u -> {
			System.out.println("User: " + u.getUsername() + " " + u.getUuid());
		});

		GroupDao groupDao = groupDao();
		Group group = groupDao.create(loadedUser, "test");
		groupDao.store(group);
		assertNotNull(group);
		assertNotNull(group.getUuid());

		groupDao.addUserToGroup(group, loadedUser);

		Group loadedGroup = groupDao.load(group.getUuid());

	}

}
