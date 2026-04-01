package io.metaloom.loom.db.flyway.dagger;

import javax.inject.Singleton;

import org.flywaydb.core.Flyway;
import dagger.Module;
import dagger.Provides;
import io.metaloom.loom.api.options.DatabaseOptions;

@Module
public class FlywayModule {

	@Provides
	@Singleton
	public Flyway flyway(DatabaseOptions options) {
		int port = options.getPort();
		String dbName = options.getDatabaseName();
		String user = options.getUsername();
		String password = options.getPassword();
		String url = "jdbc:postgresql://" + options.getHost() + ":" + port + "/" + dbName;
		Flyway flyway = Flyway.configure()
			.dataSource(url, user, password)
			// .baselineOnMigrate(true)
			.load();
		return flyway;
	}
}
