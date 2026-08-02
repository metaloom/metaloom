package io.metaloom.cortex.cloud.auth;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.vertx.core.json.JsonObject;

/**
 * Google's installed-app refresh-token grant.
 *
 * <p><b>A development convenience, not a production path.</b> A refresh token issued by an app in
 * Google's "Testing" publishing status expires after seven days, and a worker reading its
 * credentials from an environment variable has nowhere to persist a replacement. Use a service
 * account for anything that has to keep running.</p>
 */
public class GoogleRefreshTokenSource extends AbstractCachingTokenSource {

	private static final Logger log = LoggerFactory.getLogger(GoogleRefreshTokenSource.class);

	private final String clientId;
	private final String clientSecret;
	private final String refreshToken;
	private final String tokenUrl;
	private final long timeoutMs;

	public GoogleRefreshTokenSource(GDriveClientOptions options, Clock clock) {
		super(clock);
		this.clientId = options.getClientId();
		this.clientSecret = options.getClientSecret();
		this.refreshToken = options.getRefreshToken();
		this.tokenUrl = options.getTokenUrl();
		this.timeoutMs = options.getRequestTimeoutMs();
		log.warn("Google Drive is authenticating with a refresh token. Refresh tokens for apps in "
			+ "'Testing' publishing status expire after seven days and this worker cannot renew one "
			+ "unattended - configure a service account (--gdrive-service-account-json) for production.");
	}

	@Override
	public String accountId() {
		return clientId;
	}

	@Override
	protected JsonObject fetch() throws IOException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("client_id", clientId);
		form.put("client_secret", clientSecret);
		form.put("refresh_token", refreshToken);
		return TokenEndpoint.post(tokenUrl, form, timeoutMs);
	}
}
