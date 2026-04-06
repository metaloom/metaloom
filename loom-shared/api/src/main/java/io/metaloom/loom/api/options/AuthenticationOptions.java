package io.metaloom.loom.api.options;

public class AuthenticationOptions implements Option {

	public static final String DEFAULT_KEYSTORE_FILENAME = "keystore.jceks";

	private String keystorePassword = null;

	@EnvironmentVariable(name = "LOOM_INITIAL_PASSWORD", description = "Set the initial password for the initial admin account.")
	private String initialPassword = null;

	public String getKeystorePassword() {
		return keystorePassword;
	}

	public AuthenticationOptions setKeystorePassword(String keystorePassword) {
		this.keystorePassword = keystorePassword;
		return this;
	}

	public String getInitialPassword() {
		return initialPassword;
	}
}
