package io.metaloom.loom.api.options;

public class AuthenticationOptions {

	public static final String DEFAULT_KEYSTORE_FILENAME = "keystore.jceks";

	private String keystorePassword = null;

	public String getKeystorePassword() {
		return keystorePassword;
	}

	public AuthenticationOptions setKeystorePassword(String keystorePassword) {
		this.keystorePassword = keystorePassword;
		return this;
	}
}
