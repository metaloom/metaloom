package io.metaloom.cortex.media.whisper;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.FS;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.media.whisper.impl.WhisperMediaType;

public interface WhisperMedia extends LoomMedia {

	public static final WhisperMediaType WHISPER = new WhisperMediaType();

	/**
	 * Store the whisper result as a JSON string in FS storage.
	 */
	public static final LoomMetaKey<String> WHISPER_FLAG_KEY = metaKey("whisper-result", 1, FS, String.class);

	default boolean hasWhisper() {
		return has(WHISPER_FLAG_KEY);
	}

	default WhisperResult getWhisperResult() {
		String json = get(WHISPER_FLAG_KEY);
		return WhisperResult.fromJson(json);
	}

	default void setWhisperResult(WhisperResult result) {
		put(WHISPER_FLAG_KEY, result.toJson());
	}

}
