package io.metaloom.loom.api.options;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class DatabaseOptions implements Option {

	public static final String DEFAULT_HOST = "127.0.0.1";

	public static final int DEFAULT_PORT = 5432;

	public static final String DEFAULT_USERNAME = "postgres";

	public static final String DEFAULT_PASSWORD = "finger";

	public static final String DEFAULT_DATABASE_NAME = "loom";

	public static final int DEFAULT_MIN_POOL_SIZE = 5;

	public static final int DEFAULT_ACQUIRE_INCREMENT = 5;

	public static final int DEFAULT_MAX_POOL_SIZE = 20;

	private String host = DEFAULT_HOST;

	private int port = DEFAULT_PORT;

	private String username = DEFAULT_USERNAME;

	private String password = DEFAULT_PASSWORD;

	private String databaseName = DEFAULT_DATABASE_NAME;

	private int minPoolSize = DEFAULT_MIN_POOL_SIZE;

	private int acquireIncrement = DEFAULT_ACQUIRE_INCREMENT;

	private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;

	public String getHost() {
		return host;
	}

	public DatabaseOptions setHost(String host) {
		this.host = host;
		return this;
	}

	public int getPort() {
		return port;
	}

	public DatabaseOptions setPort(int port) {
		this.port = port;
		return this;
	}

	public String getDatabaseName() {
		return databaseName;
	}

	public DatabaseOptions setDatabaseName(String databaseName) {
		this.databaseName = databaseName;
		return this;
	}

	public String getPassword() {
		return password;
	}

	public DatabaseOptions setPassword(String password) {
		this.password = password;
		return this;
	}

	public String getUsername() {
		return username;
	}

	public DatabaseOptions setUsername(String username) {
		this.username = username;
		return this;
	}

	@JsonIgnore
	public String getJdbcUrl() {
		return "jdbc:postgresql://" + getHost() + ":" + getPort() + "/" + getDatabaseName();
	}

	public int getMinPoolSize() {
		return minPoolSize;
	}

	public DatabaseOptions setMinPoolSize(int minPoolSize) {
		this.minPoolSize = minPoolSize;
		return this;
	}

	public int getAcquireIncrement() {
		return acquireIncrement;
	}

	public DatabaseOptions setAcquireIncrement(int acquireIncrement) {
		this.acquireIncrement = acquireIncrement;
		return this;
	}

	public int getMaxPoolSize() {
		return maxPoolSize;
	}

	public DatabaseOptions setMaxPoolSize(int maxPoolSize) {
		this.maxPoolSize = maxPoolSize;
		return this;
	}
}
