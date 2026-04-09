package io.metaloom.cortex.api.node.payload;

/**
 * Payload carrying a vector embedding / fingerprint. Produced by nodes that
 * compute perceptual fingerprints, neural embeddings, or feature vectors.
 */
public interface EmbeddingPayload extends Payload {

	/**
	 * The embedding as a float vector, or {@code null} if only a hex representation is available.
	 */
	float[] vector();

	/**
	 * A hex-encoded representation of the embedding (e.g. a perceptual fingerprint),
	 * or {@code null} if only a float vector is available.
	 */
	default String hex() {
		return null;
	}

	/**
	 * The dimensionality of the vector, or 0 if no vector is present.
	 */
	default int dimensions() {
		return vector() != null ? vector().length : 0;
	}

	static EmbeddingPayload of(float[] vector) {
		return () -> vector;
	}

	static EmbeddingPayload ofHex(String hex) {
		return new EmbeddingPayload() {
			@Override
			public float[] vector() {
				return null;
			}

			@Override
			public String hex() {
				return hex;
			}
		};
	}
}
