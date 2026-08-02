package io.metaloom.cortex.cloud.auth;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.option.OneDriveClientOptions;
import io.vertx.core.json.JsonObject;

/**
 * Microsoft's delegated refresh-token grant.
 *
 * <p><b>A development convenience, not a production path.</b> Microsoft <em>rotates</em> the
 * refresh token on every use and expects the caller to store the replacement. A worker that reads
 * its credentials from an environment variable cannot, so the configured token stops working once
 * the issued one falls out of its overlap window - silently, and at an unpredictable time. Use
 * app-only client credentials for anything that has to keep running.</p>
 */
public class MicrosoftRefreshTokenSource extends AbstractCachingTokenSource {

	private static final Logger log = LoggerFactory.getLogger(MicrosoftRefreshTokenSource.class);

	private final String tenantId;
	private final String clientId;
	private final String clientSecret;
	private final String refreshToken;
	private final String scopes;
	private final String tokenUrl;
	private final long timeoutMs;

	public MicrosoftRefreshTokenSource(OneDriveClientOptions options, Clock clock) {
		super(clock);
		this.tenantId = options.getTenantId();
		this.clientId = options.getClientId();
		this.clientSecret = options.getClientSecret();
		this.refreshToken = options.getRefreshToken();
		this.scopes = options.getEffectiveScopes();
		this.tokenUrl = options.tokenUrl();
		this.timeoutMs = options.getRequestTimeoutMs();
		log.warn("OneDrive is authenticating with a delegated refresh token. Microsoft rotates that "
			+ "token on every use and this worker cannot persist the replacement, so it will stop "
			+ "working without warning - configure app-only credentials (--onedrive-tenant-id, "
			+ "--onedrive-client-id, --onedrive-client-secret) for production.");
	}

	@Override
	public String accountId() {
		return tenantId + "/" + clientId + "#delegated";
	}

	@Override
	protected JsonObject fetch() throws IOException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("client_id", clientId);
		form.put("client_secret", clientSecret);
		form.put("refresh_token", refreshToken);
		form.put("scope", scopes);
		return TokenEndpoint.post(tokenUrl, form, timeoutMs);
	}
}
