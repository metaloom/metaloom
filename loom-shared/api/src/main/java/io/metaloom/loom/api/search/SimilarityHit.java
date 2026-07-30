package io.metaloom.loom.api.search;

import java.util.UUID;

/**
 * One near-duplicate hit from a {@link SimilarityIndex#query(String, float[], int, float)}.
 *
 * @param assetUuid
 *            the matched asset
 * @param sha512
 *            the matched asset's content hash (stored in the index)
 * @param score
 *            the k-NN similarity score (higher = more similar); not a probability
 */
public record SimilarityHit(UUID assetUuid, String sha512, float score) {
}
