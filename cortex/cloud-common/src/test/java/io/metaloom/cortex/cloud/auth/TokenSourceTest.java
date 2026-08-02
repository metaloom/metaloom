package io.metaloom.cortex.cloud.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.metaloom.cortex.api.option.OneDriveClientOptions;
import io.metaloom.cortex.cloud.StubHttpServer;
import io.vertx.core.json.JsonObject;

/**
 * The four OAuth grants, exercised end to end against a local stub server.
 *
 * <p>No network: every grant takes its token URL from options, which is exactly why those URLs are
 * configurable rather than constants.</p>
 */
public class TokenSourceTest {

	private StubHttpServer server;
	private static KeyPair keyPair;

	@BeforeEach
	public void setup() throws Exception {
		server = new StubHttpServer();
		if (keyPair == null) {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			keyPair = generator.generateKeyPair();
		}
	}

	@AfterEach
	public void teardown() {
		server.close();
	}

	private static Map<String, String> form(String body) {
		Map<String, String> fields = new HashMap<>();
		for (String pair : body.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return fields;
	}

	private String serviceAccountKey() {
		String pem = "-----BEGIN PRIVATE KEY-----\n"
			+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
				.encodeToString(keyPair.getPrivate().getEncoded())
			+ "\n-----END PRIVATE KEY-----\n";
		return new JsonObject()
			.put("type", "service_account")
			.put("client_email", "ingest@example.iam.gserviceaccount.com")
			.put("private_key", pem)
			.encode();
	}

	private GDriveClientOptions googleServiceAccountOptions() {
		return new GDriveClientOptions()
			.setServiceAccountJson(serviceAccountKey())
			.setTokenUrl(server.baseUrl() + "/token");
	}

	// --- Google service account ---------------------------------------------------------

	@Test
	public void testServiceAccountExchangesASignedAssertion() throws Exception {
		server.enqueueJson(new JsonObject().put("access_token", "abc").put("expires_in", 3600).encode());
		GoogleServiceAccountTokenSource source =
			new GoogleServiceAccountTokenSource(googleServiceAccountOptions(), Clock.systemUTC());

		assertThat(source.accessToken()).isEqualTo("abc");

		Map<String, String> fields = form(server.lastRequest().body());
		assertThat(fields.get("grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:jwt-bearer");
		assertThat(fields.get("assertion")).isNotBlank();
	}

	@Test
	public void testTheAssertionIsAVerifiableRs256Jwt() throws Exception {
		GoogleServiceAccountTokenSource source =
			new GoogleServiceAccountTokenSource(googleServiceAccountOptions(), Clock.systemUTC());
		String assertion = source.buildAssertion();

		String[] parts = assertion.split("\\.");
		assertThat(parts).hasSize(3);

		JsonObject header = new JsonObject(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
		assertThat(header.getString("alg")).isEqualTo("RS256");

		Signature verifier = Signature.getInstance("SHA256withRSA");
		verifier.initVerify(keyPair.getPublic());
		verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
		assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
	}

	@Test
	public void testTheAssertionCarriesTheImpersonationSubject() throws Exception {
		GDriveClientOptions options = googleServiceAccountOptions().setImpersonateSubject("archive@example.com");
		GoogleServiceAccountTokenSource source = new GoogleServiceAccountTokenSource(options, Clock.systemUTC());

		JsonObject claims = claimsOf(source.buildAssertion());
		assertThat(claims.getString("sub")).isEqualTo("archive@example.com");
		// The subject is part of the identity: two impersonations must not share a scan index.
		assertThat(source.accountId()).contains("archive@example.com");
	}

	@Test
	public void testIssuedAtIsBackdatedForClockSkew() throws Exception {
		GoogleServiceAccountTokenSource source =
			new GoogleServiceAccountTokenSource(googleServiceAccountOptions(), Clock.systemUTC());
		JsonObject claims = claimsOf(source.buildAssertion());

		long now = System.currentTimeMillis() / 1000;
		// Google rejects an assertion issued in the future, so a worker clock running slightly
		// ahead is the realistic failure - the backdate is the fix.
		assertThat(claims.getLong("iat")).isLessThan(now);
		assertThat(claims.getLong("exp")).isGreaterThan(now);
	}

	@Test
	public void testTheAudienceIsTheTokenEndpoint() throws Exception {
		GoogleServiceAccountTokenSource source =
			new GoogleServiceAccountTokenSource(googleServiceAccountOptions(), Clock.systemUTC());

		assertThat(claimsOf(source.buildAssertion()).getString("aud")).isEqualTo(server.baseUrl() + "/token");
	}

	@Test
	public void testAMalformedKeyIsRejectedWithAUsefulMessage() {
		GDriveClientOptions options = new GDriveClientOptions()
			.setServiceAccountJson("{\"client_email\":\"a@b\",\"private_key\":\"not-a-key\"}");

		assertThatThrownBy(() -> new GoogleServiceAccountTokenSource(options, Clock.systemUTC()))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("private key");
	}

	// --- Google refresh token -----------------------------------------------------------

	@Test
	public void testGoogleRefreshTokenGrant() throws Exception {
		server.enqueueJson(new JsonObject().put("access_token", "abc").put("expires_in", 3600).encode());
		GDriveClientOptions options = new GDriveClientOptions()
			.setClientId("cid").setClientSecret("secret").setRefreshToken("rt")
			.setTokenUrl(server.baseUrl() + "/token");

		assertThat(new GoogleRefreshTokenSource(options, Clock.systemUTC()).accessToken()).isEqualTo("abc");

		Map<String, String> fields = form(server.lastRequest().body());
		assertThat(fields.get("grant_type")).isEqualTo("refresh_token");
		assertThat(fields.get("refresh_token")).isEqualTo("rt");
		assertThat(fields.get("client_id")).isEqualTo("cid");
	}

	@Test
	public void testARejectedGrantSurfacesTheProvidersReason() {
		server.enqueue(StubHttpServer.Response.error(400,
			new JsonObject().put("error", "invalid_grant").put("error_description", "Token expired").encode()));
		GDriveClientOptions options = new GDriveClientOptions()
			.setClientId("cid").setClientSecret("secret").setRefreshToken("rt")
			.setTokenUrl(server.baseUrl() + "/token");

		assertThatThrownBy(() -> new GoogleRefreshTokenSource(options, Clock.systemUTC()).accessToken())
			.isInstanceOf(IOException.class)
			.hasMessageContaining("invalid_grant")
			.hasMessageContaining("Token expired");
	}

	// --- Microsoft ----------------------------------------------------------------------

	@Test
	public void testMicrosoftAppOnlyUsesTheDefaultScopeAndTheTenantUrl() throws Exception {
		server.enqueueJson(new JsonObject().put("access_token", "abc").put("expires_in", 3600).encode());
		OneDriveClientOptions options = new OneDriveClientOptions()
			.setTenantId("tenant-1").setClientId("cid").setClientSecret("secret")
			.setAuthorityUrl(server.baseUrl());

		MicrosoftClientCredentialsTokenSource source =
			new MicrosoftClientCredentialsTokenSource(options, Clock.systemUTC());
		assertThat(source.accessToken()).isEqualTo("abc");

		assertThat(server.lastRequest().path()).isEqualTo("/tenant-1/oauth2/v2.0/token");
		Map<String, String> fields = form(server.lastRequest().body());
		assertThat(fields.get("grant_type")).isEqualTo("client_credentials");
		assertThat(fields.get("scope")).isEqualTo(OneDriveClientOptions.DEFAULT_APP_ONLY_SCOPES);
		assertThat(source.accountId()).isEqualTo("tenant-1/cid");
	}

	@Test
	public void testMicrosoftDelegatedGrantRequestsOfflineAccess() throws Exception {
		server.enqueueJson(new JsonObject().put("access_token", "abc").put("expires_in", 3600).encode());
		OneDriveClientOptions options = new OneDriveClientOptions()
			.setClientId("cid").setClientSecret("secret").setRefreshToken("rt")
			.setAuthorityUrl(server.baseUrl());

		assertThat(new MicrosoftRefreshTokenSource(options, Clock.systemUTC()).accessToken()).isEqualTo("abc");

		Map<String, String> fields = form(server.lastRequest().body());
		assertThat(fields.get("grant_type")).isEqualTo("refresh_token");
		// offline_access is what makes the refresh token renewable at all.
		assertThat(fields.get("scope")).contains("offline_access");
	}

	private static JsonObject claimsOf(String assertion) {
		String payload = assertion.split("\\.")[1];
		return new JsonObject(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
	}
}
