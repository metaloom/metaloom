package io.metaloom.cortex.cloud.auth;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * PKCS#8 PEM to {@link PrivateKey}, using nothing but the JDK.
 *
 * <p>A Google service-account key file carries its private key as a PKCS#8 PEM string. Parsing it
 * is four lines, which is a better trade than pulling BouncyCastle or the Google auth library into
 * the shaded cortex jar.</p>
 */
public final class PemKeys {

	private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
	private static final String END = "-----END PRIVATE KEY-----";

	private PemKeys() {
	}

	/**
	 * @param pem a PKCS#8 PEM block, with or without its armour, and with literal or escaped
	 *            newlines (a key pasted into an environment variable usually has {@code \n})
	 * @return the parsed RSA private key
	 * @throws IOException when the PEM cannot be decoded
	 */
	public static PrivateKey parsePkcs8(String pem) throws IOException {
		if (pem == null || pem.isBlank()) {
			throw new IOException("The service account key contains no private_key");
		}
		String body = pem.replace("\\n", "\n");
		int begin = body.indexOf(BEGIN);
		if (begin >= 0) {
			int end = body.indexOf(END);
			if (end < 0) {
				throw new IOException("The private key is missing its closing PEM armour");
			}
			body = body.substring(begin + BEGIN.length(), end);
		}
		body = body.replaceAll("\\s", "");

		byte[] der;
		try {
			der = Base64.getDecoder().decode(body);
		} catch (IllegalArgumentException e) {
			throw new IOException("The private key is not valid base64", e);
		}
		try {
			return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
		} catch (NoSuchAlgorithmException e) {
			// RSA is a required algorithm on every JRE.
			throw new IllegalStateException("RSA is not available", e);
		} catch (InvalidKeySpecException e) {
			throw new IOException("The private key is not a PKCS#8 RSA key", e);
		}
	}
}
