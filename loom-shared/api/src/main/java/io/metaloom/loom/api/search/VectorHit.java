package io.metaloom.loom.api.search;

import java.util.UUID;

/**
 * One nearest-neighbour hit from a {@link VectorIndex#query(VectorQuery)}.
 *
 * @param embeddingUuid
 *            the matched {@code embedding} row
 * @param assetUuid
 *            the asset it belongs to
 * @param detectionUuid
 *            the detection it was computed from, or null when it has none. For a face hit this is what turns "this asset is a match" into "this face,
 *            in this frame, at this box, is a match"
 * @param score
 *            similarity, higher is closer. Not a probability, and not comparable across index backends
 */
public record VectorHit(UUID embeddingUuid, UUID assetUuid, UUID detectionUuid, float score) {
}
