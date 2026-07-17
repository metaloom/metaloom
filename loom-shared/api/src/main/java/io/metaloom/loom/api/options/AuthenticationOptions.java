package io.metaloom.loom.api.options;

public class AuthenticationOptions implements Option {

	public static final String DEFAULT_KEYSTORE_FILENAME = "keystore.jceks";

	public static final String TOKEN_COOKIE_KEY = "__Host-loom_token";

	public static final int DEFAULT_TOKEN_EXPIRATION_TIME = 3600;

	// MCP authentication configuration
	public static final boolean DEFAULT_MCP_AUTH_ENABLED = false;
	public static final boolean DEFAULT_MCP_AUTH_STRICT_MODE = false;
	public static final String DEFAULT_MCP_AUTH_ALLOWED_ORIGINS = "*";

	private String keystorePassword = null;

	@EnvironmentVariable(name = "LOOM_INITIAL_PASSWORD", description = "Set the initial password for the initial admin account.")
	private String initialPassword = null;

	@EnvironmentVariable(name = "LOOM_TOKEN_EXPIRATION_TIME", description = "Token expiration time in seconds.")
	private int tokenExpirationTime = DEFAULT_TOKEN_EXPIRATION_TIME;

	private OAuth2Options oauth2 = new OAuth2Options();

	// MCP authentication options
	@EnvironmentVariable(name = "LOOM_MCP_AUTH_ENABLED", description = "Enable authentication on MCP endpoints (SSE, message, WebSocket).")
	private boolean mcpAuthEnabled = DEFAULT_MCP_AUTH_ENABLED;

	@EnvironmentVariable(name = "LOOM_MCP_AUTH_STRICT_MODE", description = "Require authentication on all MCP endpoints (no lenient mode).")
	private boolean mcpAuthStrictMode = DEFAULT_MCP_AUTH_STRICT_MODE;

	@EnvironmentVariable(name = "LOOM_MCP_AUTH_ALLOWED_ORIGINS", description = "Comma-separated list of allowed origins for MCP SSE endpoint (CORS).")
	private String mcpAuthAllowedOrigins = DEFAULT_MCP_AUTH_ALLOWED_ORIGINS;

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

	// MCP authentication getters/setters
	public boolean isMcpAuthEnabled() {
		return mcpAuthEnabled;
	}

	public AuthenticationOptions setMcpAuthEnabled(boolean mcpAuthEnabled) {
		this.mcpAuthEnabled = mcpAuthEnabled;
		return this;
	}

	public boolean isMcpAuthStrictMode() {
		return mcpAuthStrictMode;
	}

	public AuthenticationOptions setMcpAuthStrictMode(boolean mcpAuthStrictMode) {
		this.mcpAuthStrictMode = mcpAuthStrictMode;
		return this;
	}

	public String getMcpAuthAllowedOrigins() {
		return mcpAuthAllowedOrigins;
	}

	public AuthenticationOptions setMcpAuthAllowedOrigins(String mcpAuthAllowedOrigins) {
		this.mcpAuthAllowedOrigins = mcpAuthAllowedOrigins;
		return this;
	}

	@Override
	public void overrideWithEnv() {
		OptionUtils.applyEnv("LOOM_INITIAL_PASSWORD", v -> this.initialPassword = v);
		OptionUtils.applyEnvInt("LOOM_TOKEN_EXPIRATION_TIME", v -> this.tokenExpirationTime = v);
		OptionUtils.applyEnvBoolean("LOOM_MCP_AUTH_ENABLED", v -> this.mcpAuthEnabled = v);
		OptionUtils.applyEnvBoolean("LOOM_MCP_AUTH_STRICT_MODE", v -> this.mcpAuthStrictMode = v);
		OptionUtils.applyEnv("LOOM_MCP_AUTH_ALLOWED_ORIGINS", v -> this.mcpAuthAllowedOrigins = v);
		oauth2.overrideWithEnv();
	}
}
