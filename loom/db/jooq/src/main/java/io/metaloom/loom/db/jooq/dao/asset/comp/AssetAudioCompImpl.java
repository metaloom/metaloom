package io.metaloom.loom.db.jooq.dao.asset.comp;

import io.metaloom.loom.db.model.asset.AssetAudioComp;

public class AssetAudioCompImpl extends AbstractAssetCompImpl<AssetAudioComp> implements AssetAudioComp {

	private int streamIndex;
	private String lang;
	private String trackTitle;
	private Boolean isDefault;
	private Integer audioBpm;
	private Integer audioSamplingRate;
	private Integer audioChannels;
	private Integer audioBitrate;
	private String audioEncoding;
	private Long mediaDuration;

	@Override
	public int getStreamIndex() {
		return streamIndex;
	}

	@Override
	public AssetAudioComp setStreamIndex(int streamIndex) {
		this.streamIndex = streamIndex;
		return this;
	}

	@Override
	public String getLang() {
		return lang;
	}

	@Override
	public AssetAudioComp setLang(String lang) {
		this.lang = lang;
		return this;
	}

	@Override
	public String getTrackTitle() {
		return trackTitle;
	}

	@Override
	public AssetAudioComp setTrackTitle(String trackTitle) {
		this.trackTitle = trackTitle;
		return this;
	}

	@Override
	public Boolean getIsDefault() {
		return isDefault;
	}

	@Override
	public AssetAudioComp setIsDefault(Boolean isDefault) {
		this.isDefault = isDefault;
		return this;
	}

	@Override
	public Integer getAudioBpm() {
		return audioBpm;
	}

	@Override
	public AssetAudioComp setAudioBpm(Integer bpm) {
		this.audioBpm = bpm;
		return this;
	}

	@Override
	public Integer getAudioSamplingRate() {
		return audioSamplingRate;
	}

	@Override
	public AssetAudioComp setAudioSamplingRate(Integer samplingRate) {
		this.audioSamplingRate = samplingRate;
		return this;
	}

	@Override
	public Integer getAudioChannels() {
		return audioChannels;
	}

	@Override
	public AssetAudioComp setAudioChannels(Integer channels) {
		this.audioChannels = channels;
		return this;
	}

	@Override
	public Integer getAudioBitrate() {
		return audioBitrate;
	}

	@Override
	public AssetAudioComp setAudioBitrate(Integer bitrate) {
		this.audioBitrate = bitrate;
		return this;
	}

	@Override
	public String getAudioEncoding() {
		return audioEncoding;
	}

	@Override
	public AssetAudioComp setAudioEncoding(String encoding) {
		this.audioEncoding = encoding;
		return this;
	}

	@Override
	public Long getMediaDuration() {
		return mediaDuration;
	}

	@Override
	public AssetAudioComp setMediaDuration(Long duration) {
		this.mediaDuration = duration;
		return this;
	}
}
