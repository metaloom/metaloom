package io.metaloom.loom.db.jooq.user;

import static io.metaloom.loom.db.jooq.user.LoomExtensionHelper.toOptions;

import java.util.UUID;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.db.jooq.dagger.JooqModule;
import io.metaloom.loom.db.jooq.dao.user.UserDaoImpl;
import io.metaloom.loom.db.jooq.dao.user.UserImpl;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.test.LoomProviderExtension;

public class PoolingTest {

	@RegisterExtension
	public static LoomProviderExtension provider = LoomProviderExtension.create();

	@Test
	public void testPooling() {
		DatabaseOptions options = toOptions(provider.db());
		DataSource ds = new JooqModule().dataSource(options);
		DSLContext ctx = DSL.using(ds, SQLDialect.POSTGRES);

		UserDao dao = new UserDaoImpl(ctx);

		User u = new UserImpl();
		UUID uuid = UUID.randomUUID();
		u.setUuid(uuid);
		u.setUsername("dummy");
		// user.creator_uuid/editor_uuid are NOT NULL and the table references itself, so the
		// first user has to be its own creator.
		u.setCreatorUuid(uuid);
		u.setEditorUuid(uuid);
		dao.store(u);

		Stream<? extends User> us = dao.findAll();
		us.forEach(cu -> {
			System.out.println(cu.getUuid() + " " + cu.getUsername());
		});

	}

}
