package io.metaloom.loom.db.flyway;

import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.db.flyway.dagger.FlywayModule;

public final class FlywayHelper {

	private FlywayHelper() {
	}

	public static void migrate(DatabaseOptions options) {
		new FlywayModule().flyway(options).migrate();
	}
}
