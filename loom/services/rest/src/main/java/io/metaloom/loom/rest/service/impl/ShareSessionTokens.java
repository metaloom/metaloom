package io.metaloom.loom.rest.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.options.LoomOptions;

/**
 * Mints and verifies the opaque token a share visitor carries after satisfying the password.
 *
 * <p>
 * <b>Deliberately not a JWT, and deliberately not minted with {@code AuthenticationService.generate()}.</b> {@code LoomJWTAuthHandlerImpl}
 * authenticates <i>any</i> token that verifies against the Loom signing key; it does not inspect the claims. A share token issued from that key would
 * therefore satisfy {@code secure()} on every route in the API, and the only thing standing between a share visitor and the rest of the installation
 * would be that each endpoint remembers to call {@code requirePerm}. That is a large blast radius resting on a convention. This token verifies against
 * a different key, is checked by exactly one class, and is meaningless to the JWT handler.
 * </p>
 *
 * <p>
 * Format: {@code base64url(slug|expiryEpochSeconds|visitorId).base64url(HMAC-SHA256(key, payload))}. It is a bearer capability with an expiry and a
 * visitor id - it does not say what the holder may do. Every capability is re-read from the share row on each request, so revoking a link takes
 * effect immediately rather than when the last issued token happens to lapse.
 * </p>
 *
 * <p>
 * The visitor id tells the people holding one link apart (V2.120): feedback is stamped with it, and only its writer may change or delete it. It is
 * random, minted when a session is opened, and carried forward when a lapsed session is reopened from the same browser - see
 * {@link #visitorOf(String, String)}. Tokens issued before the id existed ({@code slug|expiry}) still verify, but carry no visitor and so own
 * nothing.
 * </p>
 *
 * <p>
 * The key is derived from the installation's keystore password rather than generated at boot, so a server restart does not throw every reviewer back
 * to the password box mid-review. It is a separate derivation from the JWT key, so recovering one tells an attacker nothing about the other.
 * </p>
 */
@Singleton
public class ShareSessionTokens {

	/** Domain separation for the key derivation. Bump the suffix to invalidate every outstanding session. */
	private static final String KEY_INFO = "loom-share-session-v1";

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	/**
	 * How long a redeemed session stays valid, in seconds.
	 *
	 * <p>
	 * Twelve hours: long enough that a reviewer working through a set of cuts over an afternoon is never interrupted, short enough that a token
	 * captured from a shared machine is not useful next week. The share's own expiry still wins whenever it is earlier - the token cannot outlive the
	 * link it was issued for, because the guard re-reads the link.
	 * </p>
	 */
	public static final long SESSION_TTL_SECONDS = 12 * 60 * 60;

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	/** 128 bits, base64url - 22 characters, no padding, and no dot (see {@code UIService}'s static-file fallback). */
	private static final int SLUG_BYTES = 16;

	private final SecureRandom random = new SecureRandom();
	private final byte[] key;

	@Inject
	public ShareSessionTokens(LoomOptions options) {
		String keystorePassword = options.getAuth().getKeystorePassword();
		this.key = deriveKey(keystorePassword);
	}

	/**
	 * Generate a fresh share slug: 128 bits of {@link SecureRandom}, base64url-encoded.
	 *
	 * <p>
	 * Not a UUID. A uuid in a URL invites trying it against {@code /api/v1/assets/<same uuid>}, and v4 uuids are designed to be unique rather than
	 * unguessable. base64url also has no dot, which matters because the SPA fallback routes a dotted path to the static file handler.
	 * </p>
	 */
	public String generateSlug() {
		byte[] bytes = new byte[SLUG_BYTES];
		random.nextBytes(bytes);
		return ENCODER.encodeToString(bytes);
	}

	/**
	 * Generate a readable password for the share dialog to prefill.
	 *
	 * <p>
	 * Words-and-digits rather than random characters: this password is read aloud, typed on a phone and pasted into an email by somebody who did not
	 * choose it. A string that cannot be transcribed gets replaced by "password1" the first time a client complains.
	 * </p>
	 */
	public String generatePassword() {
		String[] words = { "amber", "beacon", "cedar", "delta", "ember", "fable", "grove", "harbor", "indigo", "jetty",
			"kernel", "lantern", "meadow", "nimbus", "orchid", "pebble", "quarry", "ripple", "summit", "thicket",
			"umbra", "velvet", "willow", "zephyr" };
		StringBuilder builder = new StringBuilder();
		builder.append(words[random.nextInt(words.length)]);
		builder.append('-');
		builder.append(words[random.nextInt(words.length)]);
		builder.append('-');
		builder.append(10 + random.nextInt(90));
		return builder.toString();
	}

	/**
	 * Issue a session token for the given slug and visitor.
	 *
	 * @param slug
	 *            the share the session is for
	 * @param visitorId
	 *            who holds the session, see the class comment
	 * @return the opaque token
	 */
	public String issue(String slug, UUID visitorId) {
		long expiry = Instant.now().getEpochSecond() + SESSION_TTL_SECONDS;
		String payload = ENCODER.encodeToString((slug + "|" + expiry + "|" + visitorId).getBytes(StandardCharsets.UTF_8));
		return payload + "." + ENCODER.encodeToString(sign(payload));
	}

	/**
	 * When a token issued now would stop being accepted.
	 */
	public Instant expiryOfNewToken() {
		return Instant.now().plusSeconds(SESSION_TTL_SECONDS);
	}

	/**
	 * Verify a token and return whether it was issued for the given slug and has not lapsed.
	 *
	 * <p>
	 * The slug is checked as well as the signature. Without that, a valid token for any share would open every share - the signature alone only proves
	 * this server issued something, not what it issued it for.
	 * </p>
	 *
	 * @param token
	 *            the token presented by the visitor, or null
	 * @param slug
	 *            the share being addressed
	 * @return true when the token is well formed, correctly signed, issued for this slug, and unexpired
	 */
	public boolean isValid(String token, String slug) {
		Claims claims = verify(token, slug);
		return claims != null && Instant.now().getEpochSecond() < claims.expiry();
	}

	/**
	 * The visitor a token was issued to, <b>whether or not it has expired</b>.
	 *
	 * <p>
	 * Expiry is ignored on purpose, and only here. A session lapses after twelve hours; the reviewer who reopens the link from the same browser is the
	 * same reviewer, and must still be able to edit the notes they left yesterday. The signature and the slug are still checked, so this can only ever
	 * return an id this server issued for this link. Authorising a request is {@link #isValid(String, String)}'s job, never this method's.
	 * </p>
	 *
	 * @return the visitor id, or null when the token is missing, forged, for another link, or predates visitor ids
	 */
	public UUID visitorOf(String token, String slug) {
		Claims claims = verify(token, slug);
		return claims == null ? null : claims.visitorId();
	}

	private record Claims(long expiry, UUID visitorId) {
	}

	/**
	 * Check the signature and the slug, and decode the claims. Says nothing about expiry.
	 */
	private Claims verify(String token, String slug) {
		if (token == null || slug == null) {
			return null;
		}
		int dot = token.indexOf('.');
		if (dot <= 0 || dot == token.length() - 1) {
			return null;
		}
		String payload = token.substring(0, dot);
		String signature = token.substring(dot + 1);

		byte[] expected = sign(payload);
		byte[] presented;
		byte[] decodedPayload;
		try {
			presented = DECODER.decode(signature);
			decodedPayload = DECODER.decode(payload);
		} catch (IllegalArgumentException e) {
			return null;
		}
		// Constant-time. A byte-by-byte comparison that returns early leaks how much of a forged signature was
		// right, which is enough to reconstruct one given enough attempts against an endpoint anyone can call.
		if (!MessageDigest.isEqual(expected, presented)) {
			return null;
		}

		// slug|expiry, or slug|expiry|visitorId since V2.120. A slug is base64url and never contains the separator.
		String[] parts = new String(decodedPayload, StandardCharsets.UTF_8).split("\\|", -1);
		if (parts.length < 2 || parts.length > 3) {
			return null;
		}
		// The slug is checked as well as the signature. Without that, a valid token for any share would open every
		// share - the signature alone only proves this server issued something, not what it issued it for.
		if (!slug.equals(parts[0])) {
			return null;
		}
		try {
			long expiry = Long.parseLong(parts[1]);
			UUID visitorId = parts.length == 3 ? UUID.fromString(parts[2]) : null;
			return new Claims(expiry, visitorId);
		} catch (IllegalArgumentException e) {
			// NumberFormatException included.
			return null;
		}
	}

	private byte[] sign(String payload) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
			return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("Could not sign the share session token", e);
		}
	}

	/**
	 * HMAC the domain-separation string under the keystore password.
	 *
	 * <p>
	 * The keystore password is validated non-blank at startup, so it is always present; the null guard exists only for tests that build options by
	 * hand, and falls back to a process-lifetime random key. That degrades a restart into "reviewers re-enter the password", which is the right
	 * failure for a misconfigured install - never "every token verifies".
	 * </p>
	 */
	private byte[] deriveKey(String keystorePassword) {
		try {
			if (keystorePassword == null || keystorePassword.isBlank()) {
				byte[] fallback = new byte[32];
				new SecureRandom().nextBytes(fallback);
				return fallback;
			}
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(keystorePassword.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return mac.doFinal(KEY_INFO.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("Could not derive the share session key", e);
		}
	}
}
