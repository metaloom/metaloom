package io.metaloom.cortex.api.option;

/**
 * Worker-level OneDrive / SharePoint connection, credential and cache configuration, spoken over
 * Microsoft Graph v1.0.
 *
 * <p>Two authentication modes are supported, and they are not equal:</p>
 * <ul>
 * <li><b>App-only client credentials</b> ({@code tenantId} + {@code clientId} +
 * {@code clientSecret}) - the supported production mode. Note that an app-only token has no
 * {@code /me}, so a drive id must be given either here ({@code defaultDriveId}) or on the node.</li>
 * <li><b>Delegated refresh token</b> ({@code clientId} + {@code clientSecret} +
 * {@code refreshToken}) - a development convenience. Microsoft rotates the refresh token on every
 * use and expects the caller to persist the replacement, which a stateless worker reading an
 * environment variable cannot do; the configured token therefore dies silently.</li>
 * </ul>
 *
 * <p>The base URLs are configurable so the whole client can be pointed at a stub server in tests -
 * do not hard-code them in the store.</p>
 */
public class OneDriveClientOptions extends CloudClientOptions<OneDriveClientOptions> {

	public static final String DEFAULT_TENANT_ID = "common";
	public static final String DEFAULT_API_BASE_URL = "https://graph.microsoft.com/v1.0";
	public static final String DEFAULT_AUTHORITY_URL = "https://login.microsoftonline.com";

	/** App-only: the whole set of statically consented application permissions. */
	public static final String DEFAULT_APP_ONLY_SCOPES = "https://graph.microsoft.com/.default";

	/** Delegated: {@code offline_access} is what makes the refresh token renewable at all. */
	public static final String DEFAULT_DELEGATED_SCOPES = "offline_access https://graph.microsoft.com/Files.Read.All";

	private String tenantId = DEFAULT_TENANT_ID;
	private String clientId;
	private String clientSecret;
	private String refreshToken;

	private String scopes;
	private String apiBaseUrl = DEFAULT_API_BASE_URL;
	private String authorityUrl = DEFAULT_AUTHORITY_URL;

	@Override
	protected OneDriveClientOptions self() {
		return this;
	}

	@Override
	public boolean isConfigured() {
		return hasAppOnlyGrant() || hasRefreshTokenGrant();
	}

	@Override
	public String partialConfigurationReason() {
		if (isConfigured()) {
			return null;
		}
		if (!isSet(clientId) && !isSet(clientSecret) && !isSet(refreshToken)) {
			// Nothing at all is configured: Microsoft is simply not in use on this worker.
			return null;
		}
		StringBuilder missing = new StringBuilder();
		appendMissing(missing, clientId, "--onedrive-client-id (CORTEX_ONEDRIVE_CLIENT_ID)");
		appendMissing(missing, clientSecret, "--onedrive-client-secret (CORTEX_ONEDRIVE_CLIENT_SECRET)");
		if (!isSet(refreshToken) && !isRealTenant()) {
			appendMissing(missing, null, "--onedrive-tenant-id (CORTEX_ONEDRIVE_TENANT_ID)");
		}
		return "OneDrive credentials are incomplete; missing " + missing
			+ ". Configure app-only access (tenant id, client id and client secret) or a delegated "
			+ "refresh token (client id, client secret and --onedrive-refresh-token).";
	}

	private static void appendMissing(StringBuilder builder, String value, String flag) {
		if (value != null && !value.isBlank()) {
			return;
		}
		if (builder.length() > 0) {
			builder.append(", ");
		}
		builder.append(flag);
	}

	/**
	 * {@code common} is the multi-tenant placeholder and only works for a delegated flow; an
	 * app-only token has to be issued by a concrete tenant.
	 *
	 * @return true when a concrete tenant is configured
	 */
	private boolean isRealTenant() {
		return isSet(tenantId) && !DEFAULT_TENANT_ID.equals(tenantId.trim());
	}

	/**
	 * @return true when app-only client credentials are fully configured
	 */
	public boolean hasAppOnlyGrant() {
		return isRealTenant() && isSet(clientId) && isSet(clientSecret);
	}

	/**
	 * @return true when a delegated refresh-token grant is fully configured
	 */
	public boolean hasRefreshTokenGrant() {
		return isSet(clientId) && isSet(clientSecret) && isSet(refreshToken);
	}

	/**
	 * @return the effective scopes, defaulting per authentication mode
	 */
	public String getEffectiveScopes() {
		if (isSet(scopes)) {
			return scopes;
		}
		return hasRefreshTokenGrant() ? DEFAULT_DELEGATED_SCOPES : DEFAULT_APP_ONLY_SCOPES;
	}

	public String getTenantId() {
		return tenantId;
	}

	public OneDriveClientOptions setTenantId(String tenantId) {
		this.tenantId = isSet(tenantId) ? tenantId.trim() : DEFAULT_TENANT_ID;
		return this;
	}

	public String getClientId() {
		return clientId;
	}

	public OneDriveClientOptions setClientId(String clientId) {
		this.clientId = clientId;
		return this;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public OneDriveClientOptions setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
		return this;
	}

	public String getRefreshToken() {
		return refreshToken;
	}

	public OneDriveClientOptions setRefreshToken(String refreshToken) {
		this.refreshToken = refreshToken;
		return this;
	}

	/**
	 * @return the explicitly configured scopes, or null to use the per-mode default
	 */
	public String getScopes() {
		return scopes;
	}

	public OneDriveClientOptions setScopes(String scopes) {
		this.scopes = scopes;
		return this;
	}

	public String getApiBaseUrl() {
		return apiBaseUrl;
	}

	public OneDriveClientOptions setApiBaseUrl(String apiBaseUrl) {
		this.apiBaseUrl = isSet(apiBaseUrl) ? stripTrailingSlash(apiBaseUrl) : DEFAULT_API_BASE_URL;
		return this;
	}

	public String getAuthorityUrl() {
		return authorityUrl;
	}

	public OneDriveClientOptions setAuthorityUrl(String authorityUrl) {
		this.authorityUrl = isSet(authorityUrl) ? stripTrailingSlash(authorityUrl) : DEFAULT_AUTHORITY_URL;
		return this;
	}

	/**
	 * @return the token endpoint for the configured tenant
	 */
	public String tokenUrl() {
		return authorityUrl + "/" + tenantId + "/oauth2/v2.0/token";
	}

	private static String stripTrailingSlash(String url) {
		String value = url.trim();
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}
}
