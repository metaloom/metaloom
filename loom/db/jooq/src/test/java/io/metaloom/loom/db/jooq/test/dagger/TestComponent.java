package io.metaloom.loom.db.jooq.test.dagger;

import javax.inject.Singleton;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;

import dagger.BindsInstance;
import dagger.Component;
import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.common.dagger.VertxModule;
import io.metaloom.loom.db.dagger.DBBindModule;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.flyway.dagger.FlywayModule;
import io.metaloom.loom.db.jooq.dagger.JooqLoomDaoBindModule;
import io.metaloom.loom.db.jooq.dagger.JooqModule;
import io.r2dbc.spi.ConnectionFactory;

@Singleton
@Component(modules = { JooqModule.class, DBBindModule.class, FlywayModule.class, JooqLoomDaoBindModule.class, VertxModule.class })
public interface TestComponent {

	Flyway flyway();

	DataSource dataSource();

	DSLContext context();

	ConnectionFactory r2dbcConnectionFactory();

	DaoCollection daos();

	@Component.Builder
	interface Builder {

		@BindsInstance
		Builder configuration(DatabaseOptions options);

		/**
		 * Build the component.
		 * 
		 * @return
		 */
		TestComponent build();

	}

}
