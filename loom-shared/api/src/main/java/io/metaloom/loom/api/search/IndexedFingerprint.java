package io.metaloom.loom.api.search;

import java.util.UUID;

/**
 * One fingerprint vector to be (re)indexed, as streamed from {@code asset_fingerprint_comp} during a
 * {@link SimilarityIndex#rebuild(java.util.stream.Stream)}.
 *
 * @param assetUuid
 *            owning asset
 * @param sha512
 *            asset content hash
 * @param algorithm
 *            fingerprint algorithm
 * @param vector
 *            the fingerprint vector
 */
public record IndexedFingerprint(UUID assetUuid, String sha512, String algorithm, float[] vector) {
}
