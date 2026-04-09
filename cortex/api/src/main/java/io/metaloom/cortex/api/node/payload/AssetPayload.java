package io.metaloom.cortex.api.node.payload;

import io.metaloom.cortex.api.media.LoomMedia;

/**
 * Payload representing a media asset. This is the most fundamental payload type
 * and is typically the entry point of a pipeline.
 */
public interface AssetPayload extends Payload {

	/**
	 * The media asset.
	 */
	LoomMedia media();

	static AssetPayload of(LoomMedia media) {
		return () -> media;
	}
}
