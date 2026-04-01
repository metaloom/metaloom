package io.metaloom.loom.db.jooq;

import static org.junit.Assert.assertNotNull;

import java.util.UUID;

import org.junit.Test;

import io.metaloom.loom.db.LoomDaoCollection;
import io.metaloom.loom.db.group.LoomGroup;
import io.metaloom.loom.db.group.LoomGroupDao;
import io.metaloom.loom.db.user.LoomUser;
import io.metaloom.loom.db.user.LoomUserDao;
import io.metaloom.loom.test.dagger.DaggerLoomJooqTestComponent;
import io.metaloom.loom.test.dagger.LoomJooqTestComponent;
import io.reactivex.Observable;

public class BasicDaoTest {

	@Test
	public void testUserDao() {
		LoomJooqTestComponent loomComponent = DaggerLoomJooqTestComponent.builder().build();
		LoomUser user = loomComponent.daos().getUserDao().createUser("test-" + System.currentTimeMillis()).blockingGet();
		assertNotNull(user.getUuid());
	}
	
	@Test
	public void testTransactions() {
		LoomJooqTestComponent loomComponent = DaggerLoomJooqTestComponent.builder().build();
		loomComponent.daos().getGroupDao().testMultiOp();
	}

	@Test
	public void testGroupDao() {
		LoomJooqTestComponent loomComponent = DaggerLoomJooqTestComponent.builder().build();
		LoomDaoCollection daos = loomComponent.daos();
		LoomGroupDao groupDao = daos.getGroupDao();
		LoomUserDao userDao = daos.getUserDao();
		// SqlClient client = loomComponent.sqlClient();

		LoomGroup group = groupDao.loadGroup(UUID.fromString("6031fcd0-4a92-4d6d-ae43-f37c2f9def63")).blockingGet();
		for (int i = 0; i < 10; i++) {
			LoomUser user = userDao.createUser("Name-" + System.currentTimeMillis()).blockingGet();
			assertNotNull(user.getUuid());
			// Group group = groupDao.createGroup("Test").blockingGet();
			groupDao.addUserToGroup(group, user).blockingAwait();
		}

		// userDao.updateUser(user);
		// 4ccdfb6f-b2d4-4ffa-a9d4-5cac8b457b39

		Observable<LoomUser> result = groupDao.loadUsers(group);
		result.blockingForEach(e -> {
			System.out.println("found user: " + e.getUsername());
		});
	}
}
