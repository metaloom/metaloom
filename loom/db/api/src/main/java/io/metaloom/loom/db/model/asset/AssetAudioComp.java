package io.metaloom.loom.db.model.asset;

/**
 * Audio component of an asset. Multiple audio components can exist per asset (e.g. multiple audio tracks in different languages).
 */
public interface AssetAudioComp extends AssetComponent<AssetAudioComp> {

	Integer getAudioBpm();

	AssetAudioComp setAudioBpm(Integer bpm);

	Integer getAudioSamplingRate();

	AssetAudioComp setAudioSamplingRate(Integer samplingRate);

	Integer getAudioChannels();

	AssetAudioComp setAudioChannels(Integer channels);

	Integer getAudioBitrate();

	AssetAudioComp setAudioBitrate(Integer bitrate);

	String getAudioEncoding();

	AssetAudioComp setAudioEncoding(String encoding);

	Long getMediaDuration();

	AssetAudioComp setMediaDuration(Long duration);
}
