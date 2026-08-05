package io.metaloom.loom.test.container;

import org.testcontainers.containers.PostgreSQLContainer;

import io.metaloom.loom.options.DatabaseOptions;

/**
 * Preconfigured {@link PostgreSQLContainer}
 */
public class LoomPostgreSQLContainer extends PostgreSQLContainer<LoomPostgreSQLContainer> {

	// This container is migrated by FlywayHelper, so it has to be new enough for every migration:
	// V2.71 uses UNIQUE NULLS NOT DISTINCT, which needs 15+. 16.3 matches the pooled test databases.
	public static final String DEFAULT_IMAGE = "postgres:16.3-bullseye";

	public LoomPostgreSQLContainer() {
		super(DEFAULT_IMAGE);
		withDatabaseName("loom");
		withUsername("sa");
		withPassword("sa");
	}

	public int getPort() {
		return getFirstMappedPort();
	}

	public DatabaseOptions getOptions() {
		DatabaseOptions options = new DatabaseOptions();
		options.setPort(getPort());
		options.setHost(getContainerIpAddress());
		options.setUsername(getUsername());
		options.setPassword(getPassword());
		options.setDatabaseName(getDatabaseName());
		return options;
	}
}
