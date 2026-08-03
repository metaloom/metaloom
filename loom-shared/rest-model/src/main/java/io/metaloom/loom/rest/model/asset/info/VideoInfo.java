package io.metaloom.loom.rest.model.asset.info;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

public class VideoInfo implements RestModel {

	private String source;

	private Integer bitrate;

	private String encoding;

	private Integer width;

	private Integer height;

	private Long duration;

	@JsonPropertyDescription("Frames per second. A measured value from the decoder, not a container claim.")
	private Float fps;

	@JsonPropertyDescription("Total number of frames.")
	private Long frameCount;

	@JsonPropertyDescription("The container rotation flag in degrees (0, 90, 180, 270).")
	private Integer rotation;

	public String getSource() {
		return source;
	}

	public VideoInfo setSource(String source) {
		this.source = source;
		return this;
	}

	public Integer getWidth() {
		return width;
	}

	public VideoInfo setWidth(Integer width) {
		this.width = width;
		return this;
	}

	public Integer getHeight() {
		return height;
	}

	public VideoInfo setHeight(Integer height) {
		this.height = height;
		return this;
	}

	public Long getDuration() {
		return duration;
	}

	public VideoInfo setDuration(Long duration) {
		this.duration = duration;
		return this;
	}

	public String getEncoding() {
		return encoding;
	}

	public VideoInfo setEncoding(String encoding) {
		this.encoding = encoding;
		return this;
	}

	public Integer getBitrate() {
		return bitrate;
	}

	public VideoInfo setBitrate(Integer bitrate) {
		this.bitrate = bitrate;
		return this;
	}

	public Float getFps() {
		return fps;
	}

	public VideoInfo setFps(Float fps) {
		this.fps = fps;
		return this;
	}

	public Long getFrameCount() {
		return frameCount;
	}

	public VideoInfo setFrameCount(Long frameCount) {
		this.frameCount = frameCount;
		return this;
	}

	public Integer getRotation() {
		return rotation;
	}

	public VideoInfo setRotation(Integer rotation) {
		this.rotation = rotation;
		return this;
	}

}
