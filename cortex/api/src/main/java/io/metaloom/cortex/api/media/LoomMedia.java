package io.metaloom.cortex.api.media;

import io.metaloom.utils.hash.SHA512;

/**
 * A media file with identity (SHA-512 hash). This is a pure file handle
 * with content-based identity — no metadata storage concerns.
 */
public interface LoomMedia extends ProcessableMedia {

	/**
	 * Return the SHA-512 hash of this media. Computes it if not yet available.
	 */
	SHA512 getSHA512();

	/**
	 * Store the SHA-512 hash for this media (caches to xattr).
	 */
	void setSHA512(SHA512 hash);

	/**
	 * Check whether the SHA-512 hash is already known.
	 */
	boolean hasSHA512();

	default String shortHash() {
		SHA512 hash = getSHA512();
		if (hash == null) {
			return null;
		} else {
			return hash.toString().substring(0, 8);
		}
	}

}
