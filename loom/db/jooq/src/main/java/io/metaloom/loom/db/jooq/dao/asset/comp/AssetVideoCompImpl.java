package io.metaloom.loom.db.jooq.dao.asset.comp;

import io.metaloom.loom.db.model.asset.AssetVideoComp;

public class AssetVideoCompImpl extends AbstractAssetCompImpl<AssetVideoComp> implements AssetVideoComp {

	private int streamIndex;
	private Integer mediaWidth;
	private Integer mediaHeight;
	private Long mediaDuration;
	private Integer videoBitrate;
	private String videoEncoding;
	private Float fps;
	private Long frameCount;
	private Integer rotation;
	private Float blurriness;

	@Override
	public int getStreamIndex() {
		return streamIndex;
	}

	@Override
	public AssetVideoComp setStreamIndex(int streamIndex) {
		this.streamIndex = streamIndex;
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

	@Override
	public Float getFps() {
		return fps;
	}

	@Override
	public AssetVideoComp setFps(Float fps) {
		this.fps = fps;
		return this;
	}

	@Override
	public Long getFrameCount() {
		return frameCount;
	}

	@Override
	public AssetVideoComp setFrameCount(Long frameCount) {
		this.frameCount = frameCount;
		return this;
	}

	@Override
	public Integer getRotation() {
		return rotation;
	}

	@Override
	public AssetVideoComp setRotation(Integer rotation) {
		this.rotation = rotation;
		return this;
	}

	@Override
	public Float getBlurriness() {
		return blurriness;
	}

	@Override
	public AssetVideoComp setBlurriness(Float blurriness) {
		this.blurriness = blurriness;
		return this;
	}
}
