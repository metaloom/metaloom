package io.metaloom.cortex.cloud.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;

/**
 * Google's JWT-bearer grant: sign an assertion with the service account's key and exchange it for
 * an access token.
 *
 * <p>This is the production authentication mode. Unlike a refresh token it does not expire on its
 * own and needs no interactive consent, which is what makes it usable from a stateless worker.</p>
 *
 * <p>With {@code impersonateSubject} set the assertion additionally carries a {@code sub} claim, so
 * the token acts as that user through domain-wide delegation - the only way to reach a specific
 * person's My Drive rather than the service account's own (empty) drive.</p>
 */
public class GoogleServiceAccountTokenSource extends AbstractCachingTokenSource {

	/** The assertion grant type, per RFC 7523. */
	private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

	/** Assertion lifetime; Google rejects anything over an hour. */
	private static final long ASSERTION_TTL_SECONDS = 3600;

	/**
	 * Backdate {@code iat} slightly. Google rejects an assertion whose issue time is in the future,
	 * and a worker clock a few seconds ahead of Google's is the realistic failure - the reverse
	 * costs nothing.
	 */
	private static final long ISSUED_AT_BACKDATE_SECONDS = 30;

	private final String clientEmail;
	private final PrivateKey privateKey;
	private final String tokenUrl;
	private final String scopes;
	private final String subject;
	private final long timeoutMs;

	public GoogleServiceAccountTokenSource(GDriveClientOptions options, Clock clock) throws IOException {
		super(clock);
		JsonObject key = readKey(options);
		this.clientEmail = key.getString("client_email");
		if (clientEmail == null || clientEmail.isBlank()) {
			throw new IOException("The Google service account key contains no client_email");
		}
		this.privateKey = PemKeys.parsePkcs8(key.getString("private_key"));
		// The key file names its own token endpoint; the option is the override, not the source of
		// truth, so a key issued against a non-default endpoint keeps working.
		String keyTokenUri = key.getString("token_uri");
		this.tokenUrl = options.getTokenUrl() != null && !GDriveClientOptions.DEFAULT_TOKEN_URL.equals(options.getTokenUrl())
			? options.getTokenUrl()
			: keyTokenUri != null && !keyTokenUri.isBlank() ? keyTokenUri : GDriveClientOptions.DEFAULT_TOKEN_URL;
		this.scopes = options.getScopes();
		this.subject = options.getImpersonateSubject();
		this.timeoutMs = options.getRequestTimeoutMs();
	}

	private static JsonObject readKey(GDriveClientOptions options) throws IOException {
		String raw = options.getServiceAccountJson();
		if (raw == null || raw.isBlank()) {
			String file = options.getServiceAccountFile();
			if (file == null || file.isBlank()) {
				throw new IOException("No Google service account key configured");
			}
			raw = Files.readString(Paths.get(file), StandardCharsets.UTF_8);
		}
		try {
			return new JsonObject(raw.trim());
		} catch (DecodeException e) {
			throw new IOException("The Google service account key is not valid JSON", e);
		}
	}

	@Override
	public String accountId() {
		return clientEmail + (subject == null || subject.isBlank() ? "" : "#" + subject);
	}

	@Override
	protected JsonObject fetch() throws IOException {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", GRANT_TYPE);
		form.put("assertion", buildAssertion());
		return TokenEndpoint.post(tokenUrl, form, timeoutMs);
	}

	/**
	 * Build and sign the RS256 JWT assertion.
	 *
	 * @return the compact-serialized JWT
	 * @throws IOException when signing fails
	 */
	String buildAssertion() throws IOException {
		long now = clock().millis() / 1000;
		JsonObject header = new JsonObject().put("alg", "RS256").put("typ", "JWT");
		JsonObject claims = new JsonObject()
			.put("iss", clientEmail)
			.put("scope", scopes)
			// The audience is the token endpoint itself - Google validates it, so an assertion
			// minted for one endpoint cannot be replayed against another.
			.put("aud", tokenUrl)
			.put("iat", now - ISSUED_AT_BACKDATE_SECONDS)
			.put("exp", now + ASSERTION_TTL_SECONDS);
		if (subject != null && !subject.isBlank()) {
			claims.put("sub", subject);
		}

		String signingInput = base64Url(header.encode()) + "." + base64Url(claims.encode());
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(privateKey);
			signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
			return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
		} catch (GeneralSecurityException e) {
			throw new IOException("Failed to sign the Google service account assertion", e);
		}
	}

	private static String base64Url(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
}
