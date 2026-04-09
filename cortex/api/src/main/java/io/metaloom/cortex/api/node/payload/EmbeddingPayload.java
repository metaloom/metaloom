package io.metaloom.cortex.api.node.payload;

/**
 * Payload carrying a vector embedding / fingerprint. Produced by nodes that
 * compute perceptual fingerprints, neural embeddings, or feature vectors.
 */
public interface EmbeddingPayload extends Payload {

	/**
	 * The embedding vector.
	 */
	float[] vector();

	/**
	 * The dimensionality of the vector.
	 */
	default int dimensions() {
		return vector().length;
	}

	static EmbeddingPayload of(float[] vector) {
		return () -> vector;
	}
}
