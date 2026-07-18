package io.metaloom.loom.api.options;

import io.metaloom.loom.api.error.ConfigurationValidationException;

public class LoomOptions implements Option {

	private DatabaseOptions database = new DatabaseOptions();

	private ServerOptions server = new ServerOptions();

	private AuthenticationOptions auth = new AuthenticationOptions();

	@Override
	public void overrideWithEnv() {
		database.overrideWithEnv();
		server.overrideWithEnv();
		auth.overrideWithEnv();
	}

	public DatabaseOptions getDatabase() {
		return database;
	}

	public LoomOptions setDatabase(DatabaseOptions database) {
		this.database = database;
		return this;
	}

	public ServerOptions getServer() {
		return server;
	}

	public LoomOptions setServer(ServerOptions server) {
		this.server = server;
		return this;
	}

	public AuthenticationOptions getAuth() {
		return auth;
	}

	public void setAuth(AuthenticationOptions auth) {
		this.auth = auth;
	}

	@Override
	public void validate(OptionErrors errors) {
		errors.nested("database", database)
			.nested("server", server)
			.nested("auth", auth);
	}

	/**
	 * Validate the whole option tree and fail with a single exception listing every detected problem.
	 *
	 * @throws ConfigurationValidationException
	 *             when at least one setting is missing or invalid
	 */
	public void validate() {
		OptionErrors errors = new OptionErrors();
		validate(errors);
		errors.throwOnError();
	}

}
