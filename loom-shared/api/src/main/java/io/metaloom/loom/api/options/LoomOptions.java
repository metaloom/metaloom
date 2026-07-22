package io.metaloom.loom.api.options;

import io.metaloom.loom.api.error.ConfigurationValidationException;

public class LoomOptions implements Option {

	private DatabaseOptions database = new DatabaseOptions();

	private ServerOptions server = new ServerOptions();

	private AuthenticationOptions auth = new AuthenticationOptions();

	private StorageOptions storage = new StorageOptions();

	private AiOptions ai = new AiOptions();

	@Override
	public void overrideWithEnv() {
		database.overrideWithEnv();
		server.overrideWithEnv();
		auth.overrideWithEnv();
		storage.overrideWithEnv();
		ai.overrideWithEnv();
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

	public StorageOptions getStorage() {
		return storage;
	}

	public LoomOptions setStorage(StorageOptions storage) {
		this.storage = storage;
		return this;
	}

	public AiOptions getAi() {
		return ai;
	}

	public LoomOptions setAi(AiOptions ai) {
		this.ai = ai;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		errors.nested("database", database)
			.nested("server", server)
			.nested("auth", auth)
			.nested("storage", storage)
			.nested("ai", ai);
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
