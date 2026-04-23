package io.metaloom.loom.api.options;

public class OAuth2Options implements Option {

	public static final int DEFAULT_TOKEN_EXPIRATION_TIME = 3600;

	@EnvironmentVariable(name = "LOOM_OAUTH2_ENABLED", description = "Enable OAuth2 authentication.")
	private boolean enabled = false;

	@EnvironmentVariable(name = "LOOM_OAUTH2_CLIENT_ID", description = "OAuth2 client ID.")
	private String clientId;

	@EnvironmentVariable(name = "LOOM_OAUTH2_CLIENT_SECRET", description = "OAuth2 client secret.")
	private String clientSecret;

	@EnvironmentVariable(name = "LOOM_OAUTH2_AUTH_URL", description = "OAuth2 authorization endpoint URL.")
	private String authUrl;

	@EnvironmentVariable(name = "LOOM_OAUTH2_TOKEN_URL", description = "OAuth2 token endpoint URL.")
	private String tokenUrl;

	@EnvironmentVariable(name = "LOOM_OAUTH2_USERINFO_URL", description = "OAuth2 userinfo endpoint URL.")
	private String userInfoUrl;

	@EnvironmentVariable(name = "LOOM_OAUTH2_CALLBACK_URL", description = "OAuth2 callback URL (e.g. https://loom.example.com/api/v1/auth/oauth2/callback).")
	private String callbackUrl;

	@EnvironmentVariable(name = "LOOM_OAUTH2_LOGOUT_URL", description = "OAuth2 end session / logout endpoint URL.")
	private String logoutUrl;

	@EnvironmentVariable(name = "LOOM_OAUTH2_SCOPE", description = "OAuth2 scopes to request.")
	private String scope = "openid profile email";

	public boolean isEnabled() {
		return enabled;
	}

	public OAuth2Options setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public String getClientId() {
		return clientId;
	}

	public OAuth2Options setClientId(String clientId) {
		this.clientId = clientId;
		return this;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public OAuth2Options setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
		return this;
	}

	public String getAuthUrl() {
		return authUrl;
	}

	public OAuth2Options setAuthUrl(String authUrl) {
		this.authUrl = authUrl;
		return this;
	}

	public String getTokenUrl() {
		return tokenUrl;
	}

	public OAuth2Options setTokenUrl(String tokenUrl) {
		this.tokenUrl = tokenUrl;
		return this;
	}

	public String getUserInfoUrl() {
		return userInfoUrl;
	}

	public OAuth2Options setUserInfoUrl(String userInfoUrl) {
		this.userInfoUrl = userInfoUrl;
		return this;
	}

	public String getCallbackUrl() {
		return callbackUrl;
	}

	public OAuth2Options setCallbackUrl(String callbackUrl) {
		this.callbackUrl = callbackUrl;
		return this;
	}

	public String getLogoutUrl() {
		return logoutUrl;
	}

	public OAuth2Options setLogoutUrl(String logoutUrl) {
		this.logoutUrl = logoutUrl;
		return this;
	}

	public String getScope() {
		return scope;
	}

	public OAuth2Options setScope(String scope) {
		this.scope = scope;
		return this;
	}

	@Override
	public void overrideWithEnv() {
		OptionUtils.applyEnvBoolean("LOOM_OAUTH2_ENABLED", this::setEnabled);
		OptionUtils.applyEnv("LOOM_OAUTH2_CLIENT_ID", this::setClientId);
		OptionUtils.applyEnvSensitive("LOOM_OAUTH2_CLIENT_SECRET", this::setClientSecret);
		OptionUtils.applyEnv("LOOM_OAUTH2_AUTH_URL", this::setAuthUrl);
		OptionUtils.applyEnv("LOOM_OAUTH2_TOKEN_URL", this::setTokenUrl);
		OptionUtils.applyEnv("LOOM_OAUTH2_USERINFO_URL", this::setUserInfoUrl);
		OptionUtils.applyEnv("LOOM_OAUTH2_CALLBACK_URL", this::setCallbackUrl);
		OptionUtils.applyEnv("LOOM_OAUTH2_LOGOUT_URL", this::setLogoutUrl);
		OptionUtils.applyEnv("LOOM_OAUTH2_SCOPE", this::setScope);
	}
}
