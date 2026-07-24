package io.metaloom.loom.db.model.asset;

/**
 * Audio track component of an asset.
 *
 * <p>
 * Identity: <code>(asset_uuid, node_kind, stream_index)</code>. A video with a German and an English track has two components, and each may be
 * transcribed separately - see {@link AssetTranscriptComp}.
 * </p>
 */
public interface AssetAudioComp extends AssetComponent<AssetAudioComp> {

	/**
	 * Return which audio track within the container this describes.
	 */
	int getStreamIndex();

	AssetAudioComp setStreamIndex(int streamIndex);

	/**
	 * Return the track language as declared by the container.
	 */
	String getLang();

	AssetAudioComp setLang(String lang);

	/**
	 * Return the track title as declared by the container.
	 */
	String getTrackTitle();

	AssetAudioComp setTrackTitle(String trackTitle);

	/**
	 * Return whether the container marks this as the default track.
	 */
	Boolean getIsDefault();

	AssetAudioComp setIsDefault(Boolean isDefault);

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

	/**
	 * Return the duration in milliseconds.
	 */
	Long getMediaDuration();

	AssetAudioComp setMediaDuration(Long duration);
}
