package io.metaloom.loom.db.jooq;

import org.jooq.DSLContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.db.DatabaseTest;
import io.metaloom.loom.db.DbIntegrityAsserts;
import io.metaloom.loom.db.FixtureElementProvider;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.integrity.DbIntegrityService;
import io.metaloom.loom.db.jooq.test.dagger.JooqTestContext;
import io.metaloom.loom.db.model.pipeline.PipelineVersionDao;
import io.metaloom.loom.db.storage.StorageStatsService;
import io.metaloom.loom.db.transaction.TransactionCallable;

public abstract class AbstractJooqTest implements DatabaseTest, FixtureElementProvider, DbIntegrityAsserts {

	@RegisterExtension
	public static JooqTestContext context = new JooqTestContext();

	@Override
	public DaoCollection daos() {
		return context.daos();
	}

	/**
	 * One override, and every DAO test underneath can call {@code assertIntegrity()}. It runs against
	 * the same leased database the test is using.
	 */
	@Override
	public DbIntegrityService dbIntegrity() {
		return context.dbIntegrity();
	}

	public StorageStatsService storageStats() {
		return context.storageStats();
	}

	@Override
	public void transaction(TransactionCallable callable) {
		DSLContext ctx = context.ctx();
		ctx.transaction(t -> {
			callable.accept(null);
		});
	}

	@Override
	public PipelineVersionDao pipelineVersionDao() {
		return daos().pipelineVersionDao();
	}
}
