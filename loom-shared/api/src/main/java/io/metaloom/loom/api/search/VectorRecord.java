package io.metaloom.loom.api.search;

import java.util.UUID;

/**
 * One vector to index, as read from the {@code embedding} table.
 *
 * @param embeddingUuid
 *            the {@code embedding} row this came from; the index key, so re-indexing the same row replaces it
 * @param assetUuid
 *            owning asset, kept so an asset delete can drop every vector belonging to it in one call
 * @param detectionUuid
 *            the detection the vector was computed from, or null for a whole-image or audio-window embedding. This is what lets a face hit be resolved
 *            back to a box in a frame rather than only to an asset
 * @param space
 *            which set of comparable vectors this belongs to
 * @param vector
 *            the vector itself; its length must equal {@code space.dimensions()}
 */
public record VectorRecord(UUID embeddingUuid, UUID assetUuid, UUID detectionUuid, VectorSpace space, float[] vector) {

	public VectorRecord {
		if (embeddingUuid == null) {
			throw new IllegalArgumentException("A vector record needs an embedding uuid - it is the index key");
		}
		if (vector == null || vector.length != space.dimensions()) {
			throw new IllegalArgumentException("Vector length " + (vector == null ? "null" : vector.length)
				+ " disagrees with the space dimension " + space.dimensions() + " for " + space.key());
		}
	}
}
