package io.metaloom.cortex.api.node.payload;

/**
 * Payload carrying a quality score. Produced by quality-assessment nodes that
 * evaluate the visual or audio quality of an asset.
 */
public interface QualityPayload extends Payload {

	/**
	 * The quality score. The scale depends on the producing node
	 * (e.g. 0.0–1.0 normalized, or an absolute metric).
	 */
	double score();

	static QualityPayload of(double score) {
		return () -> score;
	}
}
