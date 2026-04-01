package io.metaloom.loom.db.jooq.user;

import static io.metaloom.loom.db.jooq.user.LoomExtensionHelper.toOptions;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.db.flyway.dagger.FlywayModule;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.jooq.dagger.JooqModule;
import io.metaloom.loom.test.LoomProviderExtension;
import io.metaloom.test.container.provider.model.DatabaseAllocationResponse;
import io.r2dbc.spi.ConnectionFactory;

public class RxDaoTest extends AbstractJooqTest {

	@RegisterExtension
	public static LoomProviderExtension provider = LoomProviderExtension.create();

	@Test
	public void testBasics() {

		DatabaseAllocationResponse db = provider.db();

		DatabaseOptions options = toOptions(db);
		new FlywayModule().flyway(options).migrate();
		System.out.println(options.getJdbcUrl());

		ConnectionFactory connectionFactory = new JooqModule().r2dbcConnectionFactory(options);

		DSLContext ctx = DSL.using(connectionFactory);

		// Flux<Record1<UUID>> f = Flux.from(ctx.insertInto(USER,
		// USER.UUID, USER.USERNAME, USER.FIRSTNAME, USER.LASTNAME)
		// .values(UUID.randomUUID(), "joedoe", "Joe", "Doe")
		// .returningResult(USER.UUID));

		// f.blockFirst();

		// UserRxDao dao = new UserRxDao(ctx.configuration());
		//
		// List<User> users = dao.fetchByUsername("joedoe");
		// for (User u : users) {
		// System.out.println("Found: " + u.getUsername() + " " + u.getUuid());
		// }
	}


}
