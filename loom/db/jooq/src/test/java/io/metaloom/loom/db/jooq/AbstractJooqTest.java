package io.metaloom.loom.db.jooq;

import org.jooq.DSLContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.db.DatabaseTest;
import io.metaloom.loom.db.FixtureElementProvider;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.jooq.test.dagger.JooqTestContext;
import io.metaloom.loom.db.transaction.TransactionCallable;

public abstract class AbstractJooqTest implements DatabaseTest, FixtureElementProvider {

	@RegisterExtension
	public static JooqTestContext context = new JooqTestContext();

	@Override
	public DaoCollection daos() {
		return context.daos();
	}

	@Override
	public void transaction(TransactionCallable callable) {
		DSLContext ctx = context.ctx();
		ctx.transaction(t -> {
			callable.accept(null);
		});
	}
}
