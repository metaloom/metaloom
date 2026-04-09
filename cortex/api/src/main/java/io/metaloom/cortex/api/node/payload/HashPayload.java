package io.metaloom.cortex.api.node.payload;

/**
 * Payload carrying a cryptographic hash of the asset (e.g. MD5, SHA-256, SHA-512).
 */
public interface HashPayload extends Payload {

	/**
	 * The hash algorithm name (e.g. "MD5", "SHA-256", "SHA-512").
	 */
	String algorithm();

	/**
	 * The hex-encoded hash value.
	 */
	String hash();

	record Default(String algorithm, String hash) implements HashPayload {
	}

	static HashPayload of(String algorithm, String hash) {
		return new Default(algorithm, hash);
	}
}
