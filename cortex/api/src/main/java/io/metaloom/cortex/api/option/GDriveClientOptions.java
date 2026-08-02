package io.metaloom.cortex.api.option;

/**
 * Worker-level Google Drive connection, credential and cache configuration.
 *
 * <p>Two authentication modes are supported, and they are not equal:</p>
 * <ul>
 * <li><b>Service account</b> ({@code serviceAccountJson} or {@code serviceAccountFile}, optionally
 * with {@code impersonateSubject} for domain-wide delegation) - the supported production mode. The
 * credential does not expire on its own.</li>
 * <li><b>OAuth refresh token</b> ({@code clientId} + {@code clientSecret} + {@code refreshToken}) -
 * a development convenience. Refresh tokens issued by an app in Google's "Testing" publishing
 * status expire after seven days, and a stateless worker reading an environment variable has
 * nowhere to persist a replacement.</li>
 * </ul>
 *
 * <p>The base URLs are configurable so the whole client can be pointed at a stub server in tests -
 * do not hard-code them in the store.</p>
 */
public class GDriveClientOptions extends CloudClientOptions<GDriveClientOptions> {

	public static final String DEFAULT_SCOPES = "https://www.googleapis.com/auth/drive.readonly";
	public static final String DEFAULT_API_BASE_URL = "https://www.googleapis.com";
	public static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";

	private String serviceAccountJson;
	private String serviceAccountFile;
	private String impersonateSubject;

	private String clientId;
	private String clientSecret;
	private String refreshToken;

	private String scopes = DEFAULT_SCOPES;
	private String apiBaseUrl = DEFAULT_API_BASE_URL;
	private String tokenUrl = DEFAULT_TOKEN_URL;

	private boolean exportNativeDocs;

	@Override
	protected GDriveClientOptions self() {
		return this;
	}

	@Override
	public boolean isConfigured() {
		return hasServiceAccount() || hasRefreshTokenGrant();
	}

	@Override
	public String partialConfigurationReason() {
		if (isConfigured()) {
			return null;
		}
		boolean anyOauthField = isSet(clientId) || isSet(clientSecret) || isSet(refreshToken);
		if (!anyOauthField) {
			// Nothing at all is configured: Google is simply not in use on this worker.
			return null;
		}
		StringBuilder missing = new StringBuilder();
		appendMissing(missing, clientId, "--gdrive-client-id (CORTEX_GDRIVE_CLIENT_ID)");
		appendMissing(missing, clientSecret, "--gdrive-client-secret (CORTEX_GDRIVE_CLIENT_SECRET)");
		appendMissing(missing, refreshToken, "--gdrive-refresh-token (CORTEX_GDRIVE_REFRESH_TOKEN)");
		return "Google Drive OAuth credentials are incomplete; missing " + missing
			+ ". Set all three, or configure a service account with --gdrive-service-account-json "
			+ "(CORTEX_GDRIVE_SERVICE_ACCOUNT_JSON) instead.";
	}

	private static void appendMissing(StringBuilder builder, String value, String flag) {
		if (isSet(value)) {
			return;
		}
		if (builder.length() > 0) {
			builder.append(", ");
		}
		builder.append(flag);
	}

	/**
	 * @return true when a service-account key is configured, inline or as a file
	 */
	public boolean hasServiceAccount() {
		return isSet(serviceAccountJson) || isSet(serviceAccountFile);
	}

	/**
	 * @return true when a complete installed-app refresh-token grant is configured
	 */
	public boolean hasRefreshTokenGrant() {
		return isSet(clientId) && isSet(clientSecret) && isSet(refreshToken);
	}

	/**
	 * @return the service-account key as inline JSON, or null
	 */
	public String getServiceAccountJson() {
		return serviceAccountJson;
	}

	public GDriveClientOptions setServiceAccountJson(String serviceAccountJson) {
		this.serviceAccountJson = serviceAccountJson;
		return this;
	}

	/**
	 * @return a path to the service-account key file, or null. An alternative to the inline form,
	 *         not a fallback for it
	 */
	public String getServiceAccountFile() {
		return serviceAccountFile;
	}

	public GDriveClientOptions setServiceAccountFile(String serviceAccountFile) {
		this.serviceAccountFile = serviceAccountFile;
		return this;
	}

	/**
	 * @return the user to impersonate through domain-wide delegation, or null to act as the
	 *         service account itself
	 */
	public String getImpersonateSubject() {
		return impersonateSubject;
	}

	public GDriveClientOptions setImpersonateSubject(String impersonateSubject) {
		this.impersonateSubject = impersonateSubject;
		return this;
	}

	public String getClientId() {
		return clientId;
	}

	public GDriveClientOptions setClientId(String clientId) {
		this.clientId = clientId;
		return this;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public GDriveClientOptions setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
		return this;
	}

	public String getRefreshToken() {
		return refreshToken;
	}

	public GDriveClientOptions setRefreshToken(String refreshToken) {
		this.refreshToken = refreshToken;
		return this;
	}

	/**
	 * @return space-separated OAuth scopes
	 */
	public String getScopes() {
		return scopes;
	}

	public GDriveClientOptions setScopes(String scopes) {
		this.scopes = isSet(scopes) ? scopes : DEFAULT_SCOPES;
		return this;
	}

	public String getApiBaseUrl() {
		return apiBaseUrl;
	}

	public GDriveClientOptions setApiBaseUrl(String apiBaseUrl) {
		this.apiBaseUrl = isSet(apiBaseUrl) ? stripTrailingSlash(apiBaseUrl) : DEFAULT_API_BASE_URL;
		return this;
	}

	public String getTokenUrl() {
		return tokenUrl;
	}

	public GDriveClientOptions setTokenUrl(String tokenUrl) {
		this.tokenUrl = isSet(tokenUrl) ? tokenUrl : DEFAULT_TOKEN_URL;
		return this;
	}

	/**
	 * Worker-level default for the node's {@code exportNativeDocs} option.
	 *
	 * <p>Google Docs, Sheets and Slides have no downloadable bytes and no reported size; reading
	 * one means exporting it to PDF or CSV, which is capped at 10 MB and is rarely what a media
	 * pipeline wants. Off by default, so native documents are filtered out during the scan.</p>
	 *
	 * @return whether native Google documents may be exported
	 */
	public boolean isExportNativeDocs() {
		return exportNativeDocs;
	}

	public GDriveClientOptions setExportNativeDocs(boolean exportNativeDocs) {
		this.exportNativeDocs = exportNativeDocs;
		return this;
	}

	private static String stripTrailingSlash(String url) {
		String value = url.trim();
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}
}
