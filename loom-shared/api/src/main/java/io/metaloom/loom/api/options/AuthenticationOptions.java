package io.metaloom.loom.api.options;

public class AuthenticationOptions implements Option {

	public static final String DEFAULT_KEYSTORE_FILENAME = "keystore.jceks";

	public static final String TOKEN_COOKIE_KEY = "__Host-loom_token";

	public static final int DEFAULT_TOKEN_EXPIRATION_TIME = 3600;

	private String keystorePassword = null;

	@EnvironmentVariable(name = "LOOM_INITIAL_PASSWORD", description = "Set the initial password for the initial admin account.")
	private String initialPassword = null;

	@EnvironmentVariable(name = "LOOM_TOKEN_EXPIRATION_TIME", description = "Token expiration time in seconds.")
	private int tokenExpirationTime = DEFAULT_TOKEN_EXPIRATION_TIME;

	private OAuth2Options oauth2 = new OAuth2Options();

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

	public int getTokenExpirationTime() {
		return tokenExpirationTime;
	}

	public AuthenticationOptions setTokenExpirationTime(int tokenExpirationTime) {
		this.tokenExpirationTime = tokenExpirationTime;
		return this;
	}

	public OAuth2Options getOauth2() {
		return oauth2;
	}

	public AuthenticationOptions setOauth2(OAuth2Options oauth2) {
		this.oauth2 = oauth2;
		return this;
	}

	@Override
	public void overrideWithEnv() {
		OptionUtils.applyEnv("LOOM_INITIAL_PASSWORD", v -> this.initialPassword = v);
		OptionUtils.applyEnvInt("LOOM_TOKEN_EXPIRATION_TIME", v -> this.tokenExpirationTime = v);
		oauth2.overrideWithEnv();
	}
}
