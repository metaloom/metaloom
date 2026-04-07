package io.metaloom.cortex.api.media;

import java.util.List;

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

	/**
	 * @deprecated Typed media wrappers are deprecated. Use NodeResult output maps instead.
	 */
	@Deprecated
	default <T extends LoomMedia> T of(MediaType<T> type) {
		return type.wrap(this);
	}

	/**
	 * @deprecated Legacy method from MetaStorageAccess. Use NodeResult output maps instead.
	 */
	@Deprecated
	default <T> T get(LoomMetaKey<T> metaKey) {
		throw new UnsupportedOperationException("MetaStorage access removed. Use NodeResult output maps.");
	}

	/**
	 * @deprecated Legacy method from MetaStorageAccess. Use NodeResult output maps instead.
	 */
	@Deprecated
	default <T> List<T> getAll(LoomMetaKey<T> metaKey) {
		throw new UnsupportedOperationException("MetaStorage access removed. Use NodeResult output maps.");
	}

	/**
	 * @deprecated Legacy method from MetaStorageAccess. Use NodeResult output maps instead.
	 */
	@Deprecated
	default <T> void put(LoomMetaKey<T> metaKey, T value) {
		throw new UnsupportedOperationException("MetaStorage access removed. Use NodeResult output maps.");
	}

	/**
	 * @deprecated Legacy method from MetaStorageAccess. Use NodeResult output maps instead.
	 */
	@Deprecated
	default <T> boolean has(LoomMetaKey<T> metaKey) {
		throw new UnsupportedOperationException("MetaStorage access removed. Use NodeResult output maps.");
	}

	/**
	 * @deprecated Legacy method from MetaStorageAccess. Use NodeResult output maps instead.
	 */
	@Deprecated
	default <T> void append(LoomMetaKey<T> metaKey, T value) {
		throw new UnsupportedOperationException("MetaStorage access removed. Use NodeResult output maps.");
	}

}
