package io.metaloom.loom.test.integration;

import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.test.container.provider.model.DatabaseAllocationResponse;

public final class LoomExtensionHelper {

	private LoomExtensionHelper() {
	}

	public static DatabaseOptions toOptions(DatabaseAllocationResponse db) {
		DatabaseOptions options = new DatabaseOptions();
		options.setDatabaseName(db.getDatabaseName());
		options.setHost(db.getHost());
		options.setPort(db.getPort());
		options.setPassword(db.getPassword());
		options.setUsername(db.getUsername());
		return options;
	}
}
