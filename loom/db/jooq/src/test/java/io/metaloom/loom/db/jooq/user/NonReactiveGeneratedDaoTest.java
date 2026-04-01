package io.metaloom.loom.db.jooq.user;

import static io.metaloom.loom.db.jooq.user.LoomExtensionHelper.toOptions;

import javax.sql.DataSource;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.db.flyway.dagger.FlywayModule;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.jooq.dagger.JooqModule;
import io.metaloom.loom.db.jooq.dao.user.UserDaoImpl;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.model.user.UserDao;
import io.metaloom.loom.test.LoomProviderExtension;

public class NonReactiveGeneratedDaoTest extends AbstractJooqTest {

	@RegisterExtension
	public static LoomProviderExtension provider = LoomProviderExtension.create();

	@Test
	public void testBasics() {

		DatabaseOptions options = toOptions(provider.db());
		new FlywayModule().flyway(options).migrate();
		System.out.println(options.getJdbcUrl());

		DataSource datasource = new JooqModule().dataSource(options);

		DSLContext ctx = DSL.using(datasource, SQLDialect.POSTGRES);
		//
		// Flux<Record1<UUID>> f = Flux.from(ctx.insertInto(USER,
		// USER.UUID, USER.USERNAME, USER.FIRSTNAME, USER.LASTNAME)
		// .values(UUID.randomUUID(), "joedoe", "Joe", "Doe")
		// .returningResult(USER.UUID));
		//
		// f.blockFirst();

		UserDao dao = new UserDaoImpl(ctx);

		User user = dao.loadByUsername("joedoe");
		System.out.println("Found: " + user.getUsername() + " " + user.getUuid());

	}
}
