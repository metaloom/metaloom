package io.metaloom.cortex.node.whisper;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.FS;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.meta.AbstractMetaStorage;
import io.metaloom.cortex.api.meta.MetaStorage;
import io.metaloom.cortex.media.whisper.WhisperResult;

@Singleton
public class WhisperMetaStorage extends AbstractMetaStorage {

	public static final LoomMetaKey<String> WHISPER_FLAG_KEY = metaKey("whisper-result", 1, FS, String.class);

	@Inject
	public WhisperMetaStorage(MetaStorage storage) {
		super(storage);
	}

	public boolean hasWhisper(LoomMedia media) {
		return has(media, WHISPER_FLAG_KEY);
	}

	public WhisperResult getWhisperResult(LoomMedia media) {
		String json = get(media, WHISPER_FLAG_KEY);
		return WhisperResult.fromJson(json);
	}

	public void setWhisperResult(LoomMedia media, WhisperResult result) {
		put(media, WHISPER_FLAG_KEY, result.toJson());
	}

}
