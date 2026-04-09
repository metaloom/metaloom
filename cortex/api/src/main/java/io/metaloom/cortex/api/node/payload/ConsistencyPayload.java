package io.metaloom.cortex.api.node.payload;

/**
 * Payload carrying a consistency check result. Produced by consistency-check nodes
 * that verify whether a media file is complete and structurally sound.
 * Downstream filter nodes can use this to constrain processing based on quality.
 */
public interface ConsistencyPayload extends Payload {

	/**
	 * Whether the asset is considered complete / intact.
	 */
	boolean isComplete();

	/**
	 * A consistency score (e.g. 0.0–1.0). Interpretation depends on the producing node.
	 */
	double score();

	record Default(boolean isComplete, double score) implements ConsistencyPayload {
	}

	static ConsistencyPayload of(boolean isComplete, double score) {
		return new Default(isComplete, score);
	}

	static ConsistencyPayload complete() {
		return new Default(true, 1.0);
	}

	static ConsistencyPayload incomplete(double score) {
		return new Default(false, score);
	}
}
