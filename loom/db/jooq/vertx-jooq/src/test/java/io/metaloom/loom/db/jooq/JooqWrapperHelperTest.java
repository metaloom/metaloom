package io.metaloom.loom.db.jooq;

import static io.metaloom.loom.db.jooq.JooqWrapperHelper.unwrap;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import io.metaloom.loom.db.jooq.dao.user.JooqUserImpl;
import io.metaloom.loom.db.jooq.tables.pojos.User;

public class JooqWrapperHelperTest {

	@Test
	public void testUnwrap() {
		User jooqUser = new User();
		JooqUserImpl wrapper = new JooqUserImpl(jooqUser);
		io.metaloom.loom.db.jooq.tables.pojos.User unwrappedUser = unwrap(wrapper);
		assertEquals("The user was not correctly unwrapped.", jooqUser, unwrappedUser);
	}
}
