package io.metaloom.cortex.cloud.auth;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.cortex.api.option.OneDriveClientOptions;
import io.vertx.core.json.JsonObject;

/**
 * Microsoft's app-only {@code client_credentials} grant.
 *
 * <p>The production authentication mode for OneDrive and SharePoint: the token belongs to the
 * application rather than a user, so nothing expires or needs re-consent.</p>
 *
 * <p>Its one consequence is worth stating where the code lives: an app-only token has no
 * {@code /me}, so there is no implicit drive. A drive id has to come from the node definition or
 * the worker default, which is why {@code GraphFileStore.resolveDriveId} fails fast rather than
 * guessing.</p>
 */
public class MicrosoftClientCredentialsTokenSource extends AbstractCachingTokenSource {

	private final String tenantId;
	private final String clientId;
	private final String clientSecret;
	private final String scopes;
	private final String tokenUrl;
	private final long timeoutMs;

	public MicrosoftClientCredentialsTokenSource(OneDriveClientOptions options, Clock clock) {
		super(clock);
		this.tenantId = options.getTenantId();
		this.clientId = options.getClientId();
		this.clientSecret = options.getClientSecret();
		this.scopes = options.getEffectiveScopes();
		this.tokenUrl = options.tokenUrl();
		this.timeoutMs = options.getRequestTimeoutMs();
	}

	@Override
	public String accountId() {
		return tenantId + "/" + clientId;
	}

	@Override
	protected JsonObject fetch() throws IOException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "client_credentials");
		form.put("client_id", clientId);
		form.put("client_secret", clientSecret);
		form.put("scope", scopes);
		return TokenEndpoint.post(tokenUrl, form, timeoutMs);
	}
}
