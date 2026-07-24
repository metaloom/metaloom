package io.metaloom.loom.db.model.asset;

import java.util.UUID;

import io.vertx.core.json.JsonObject;

/**
 * Transcript component of an asset - the transcript of one audio track.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, stream_index, lang)</code>. A video with a German and an English track has two transcripts, and the same
 * track may be transcribed into more than one language.
 * </p>
 */
public interface AssetTranscriptComp extends AssetComponent<AssetTranscriptComp> {

	/**
	 * Return which audio track was transcribed. Part of the identity rather than relying on {@link #getAudioCompUuid()}, because an audio-only asset
	 * may be transcribed before any audio component exists.
	 */
	int getStreamIndex();

	AssetTranscriptComp setStreamIndex(int streamIndex);

	/**
	 * Return the BCP-47 language tag of the transcript. Never null - undetermined is the empty string.
	 */
	String getLang();

	AssetTranscriptComp setLang(String lang);

	/**
	 * Return the audio component this transcript was produced from, or null when none is known.
	 */
	UUID getAudioCompUuid();

	AssetTranscriptComp setAudioCompUuid(UUID audioCompUuid);

	String getTranscriptText();

	AssetTranscriptComp setTranscriptText(String text);

	/**
	 * Return the duration in milliseconds.
	 */
	Long getDuration();

	AssetTranscriptComp setDuration(Long duration);

	Integer getWordCount();

	AssetTranscriptComp setWordCount(Integer wordCount);

	/**
	 * Return the readable mirror of {@link #getProducerVersion()}, e.g. whisper-large-v3.
	 */
	String getModel();

	AssetTranscriptComp setModel(String model);

	/**
	 * Return the full model output including sections, words and timings. Consumed by the UI transcript panel.
	 */
	JsonObject getTranscriptJson();

	AssetTranscriptComp setTranscriptJson(JsonObject json);
}
