package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.AssetAudioComp;

public class AssetAudioCompImpl extends AbstractEditableElement<AssetAudioComp> implements AssetAudioComp {

	private UUID assetUuid;
	private String source;
	private Integer audioBpm;
	private Integer audioSamplingRate;
	private Integer audioChannels;
	private Integer audioBitrate;
	private String audioEncoding;
	private Long mediaDuration;

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AssetAudioComp setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public AssetAudioComp setSource(String source) {
		this.source = source;
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
