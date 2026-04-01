package io.metaloom.loom.db.jooq.dao.asset.comp;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.asset.AssetVideoComp;

public class AssetVideoCompImpl extends AbstractEditableElement<AssetVideoComp> implements AssetVideoComp {

	private UUID assetUuid;
	private String source;
	private Integer mediaWidth;
	private Integer mediaHeight;
	private Long mediaDuration;
	private Integer videoBitrate;
	private String videoEncoding;

	@Override
	public UUID getAssetUuid() {
		return assetUuid;
	}

	@Override
	public AssetVideoComp setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	@Override
	public String getSource() {
		return source;
	}

	@Override
	public AssetVideoComp setSource(String source) {
		this.source = source;
		return this;
	}

	@Override
	public Integer getMediaWidth() {
		return mediaWidth;
	}

	@Override
	public AssetVideoComp setMediaWidth(Integer width) {
		this.mediaWidth = width;
		return this;
	}

	@Override
	public Integer getMediaHeight() {
		return mediaHeight;
	}

	@Override
	public AssetVideoComp setMediaHeight(Integer height) {
		this.mediaHeight = height;
		return this;
	}

	@Override
	public Long getMediaDuration() {
		return mediaDuration;
	}

	@Override
	public AssetVideoComp setMediaDuration(Long duration) {
		this.mediaDuration = duration;
		return this;
	}

	@Override
	public Integer getVideoBitrate() {
		return videoBitrate;
	}

	@Override
	public AssetVideoComp setVideoBitrate(Integer bitrate) {
		this.videoBitrate = bitrate;
		return this;
	}

	@Override
	public String getVideoEncoding() {
		return videoEncoding;
	}

	@Override
	public AssetVideoComp setVideoEncoding(String encoding) {
		this.videoEncoding = encoding;
		return this;
	}
}
