package io.metaloom.loom.api.search;

import java.util.UUID;

/**
 * One fingerprint in its <b>stored hex form</b>, as read from {@code asset_fingerprint_comp}, for a
 * {@link SimilarityIndex#rebuildFromHex(java.util.stream.Stream)}.
 *
 * <p>
 * The hex is decoded to a vector by the {@link SimilarityIndex} implementation, so callers never need the fingerprint codec.
 * </p>
 *
 * @param assetUuid
 *            owning asset
 * @param sha512
 *            asset content hash
 * @param algorithm
 *            fingerprint algorithm
 * @param fingerprint
 *            the fingerprint, hex encoded
 */
public record HexFingerprint(UUID assetUuid, String sha512, String algorithm, String fingerprint) {
}
