package io.metaloom.loom.rest.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.options.LoomOptions;

/**
 * The share session token: what it proves, and the visitor id it carries since V2.120.
 */
public class ShareSessionTokensTest {

	private static final String KEYSTORE_PASSWORD = "share-tokens-test-password";

	private final ShareSessionTokens tokens = new ShareSessionTokens(options());

	@Test
	public void testIssuedTokenCarriesItsVisitor() {
		String slug = tokens.generateSlug();
		UUID visitor = UUID.randomUUID();
		String token = tokens.issue(slug, visitor);

		assertTrue(tokens.isValid(token, slug));
		assertEquals(visitor, tokens.visitorOf(token, slug));
	}

	@Test
	public void testTokenIsBoundToItsSlug() {
		String token = tokens.issue(tokens.generateSlug(), UUID.randomUUID());
		String otherSlug = tokens.generateSlug();

		assertFalse(tokens.isValid(token, otherSlug));
		assertNull(tokens.visitorOf(token, otherSlug), "A token for another link must not lend its visitor to this one");
	}

	@Test
	public void testTamperedTokenIsRejected() {
		String slug = tokens.generateSlug();
		String token = tokens.issue(slug, UUID.randomUUID());
		// Swap the visitor in the payload and keep the old signature.
		String payload = Base64.getUrlEncoder().withoutPadding()
			.encodeToString((slug + "|" + (Instant.now().getEpochSecond() + 3600) + "|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
		String forged = payload + token.substring(token.indexOf('.'));

		assertFalse(tokens.isValid(forged, slug));
		assertNull(tokens.visitorOf(forged, slug));
		assertNull(tokens.visitorOf("garbage", slug));
		assertNull(tokens.visitorOf(null, slug));
	}

	/**
	 * Expiry ends the session but not the visitor: a reviewer reopening the link from the same browser is still the same reviewer.
	 */
	@Test
	public void testExpiredTokenStillNamesItsVisitor() throws Exception {
		String slug = tokens.generateSlug();
		UUID visitor = UUID.randomUUID();
		String expired = sign(slug + "|" + (Instant.now().getEpochSecond() - 60) + "|" + visitor);

		assertFalse(tokens.isValid(expired, slug), "An expired token authorises nothing");
		assertEquals(visitor, tokens.visitorOf(expired, slug));
	}

	/**
	 * Tokens issued before V2.120 ({@code slug|expiry}) keep working for reading, and name nobody.
	 */
	@Test
	public void testTokenWithoutVisitorStillVerifies() throws Exception {
		String slug = tokens.generateSlug();
		String legacy = sign(slug + "|" + (Instant.now().getEpochSecond() + 3600));

		assertTrue(tokens.isValid(legacy, slug));
		assertNull(tokens.visitorOf(legacy, slug));
	}

	/**
	 * Mint a token the way {@link ShareSessionTokens} does, for payloads it would no longer issue itself.
	 */
	private static String sign(String claims) throws Exception {
		Mac derive = Mac.getInstance("HmacSHA256");
		derive.init(new SecretKeySpec(KEYSTORE_PASSWORD.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] key = derive.doFinal("loom-share-session-v1".getBytes(StandardCharsets.UTF_8));

		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		String payload = encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return payload + "." + encoder.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
	}

	private static LoomOptions options() {
		LoomOptions options = new LoomOptions();
		options.getAuth().setKeystorePassword(KEYSTORE_PASSWORD);
		return options;
	}
}
