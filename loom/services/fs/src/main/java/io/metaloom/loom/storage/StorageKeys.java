package io.metaloom.loom.storage;

import io.metaloom.utils.hash.SHA512;

/**
 * The content-addressed key layout, in one place so the filesystem and S3 backends cannot drift apart.
 *
 * <p>
 * {@code ab/cd/ef/<sha512>} — three levels of two hex characters, then the full hash as the file name. The fan-out exists for the filesystem case: a
 * flat directory of a few hundred thousand entries is slow to list and, on some filesystems, slow to open. S3 has no directories and does not need
 * the fan-out, but reusing the same key means an operator can rsync a filesystem pool into a bucket (or the reverse) without rewriting a single row
 * in {@code asset_location}.
 * </p>
 */
public final class StorageKeys {

	private StorageKeys() {
	}

	/**
	 * @param sha512
	 *            content hash
	 * @return the relative key, always using {@code /} as the separator (S3 has no other option, and the filesystem accepts it on every platform Loom
	 *         runs on)
	 */
	public static String contentKey(SHA512 sha512) {
		if (sha512 == null) {
			throw new IllegalArgumentException("A SHA-512 is required to derive a storage key");
		}
		String hex = sha512.toString();
		if (hex.length() < 6) {
			throw new IllegalArgumentException("Not a SHA-512 hex string: " + hex);
		}
		return hex.substring(0, 2) + "/" + hex.substring(2, 4) + "/" + hex.substring(4, 6) + "/" + hex;
	}
}
