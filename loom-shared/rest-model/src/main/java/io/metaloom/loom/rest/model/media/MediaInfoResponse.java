package io.metaloom.loom.rest.model.media;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * What the decoder says about a video file: how long it is, how big, and how fast it runs.
 *
 * <p>
 * This is a <em>measurement</em>, not stored metadata. {@code asset_video_comp} has carried duration, dimensions and frame rate since V1 and has no
 * producer - no Cortex node writes it, so an ingested video reports no duration at all. A player handed no duration falls back to what the media
 * element reports, and a fragmented MP4 arriving over a pipe reports only what has already arrived: a 43-minute episode drew a five-second
 * timeline, and no amount of clicking on it could reach minute twenty.
 * </p>
 *
 * <p>
 * {@code frameRate} is here for the same reason. Detections are stored against a {@code frameNumber}, and without a frame rate there is no way to
 * turn one into a position on a timeline - so a face found at frame 54757 could be listed but never located.
 * </p>
 *
 * <p>
 * The durable answer remains a node that measures the file once and writes the component row. Until one exists this route is what every screen
 * showing a video timeline depends on, so it is cached per binary rather than probed per request.
 * </p>
 */
public class MediaInfoResponse implements RestResponseModel<MediaInfoResponse> {

	@JsonPropertyDescription("Duration of the video in seconds, as reported by the container. Null when it could not be determined.")
	private Double duration;

	@JsonPropertyDescription("Frame rate in frames per second. Needed to place a detection's frameNumber on a timeline.")
	private Double frameRate;

	@JsonPropertyDescription("Width of the first video stream in pixels.")
	private Integer width;

	@JsonPropertyDescription("Height of the first video stream in pixels.")
	private Integer height;

	@JsonPropertyDescription("Codec of the first video stream, e.g. h264. The stream route can only remux a subset of these.")
	private String videoCodec;

	@JsonPropertyDescription("Codec of the first audio stream, e.g. ac3, or null when the file carries no audio.")
	private String audioCodec;

	@JsonPropertyDescription("True when the stream route can serve this file without a full re-encode.")
	private boolean streamable;

	public Double getDuration() {
		return duration;
	}

	public MediaInfoResponse setDuration(Double duration) {
		this.duration = duration;
		return this;
	}

	public Double getFrameRate() {
		return frameRate;
	}

	public MediaInfoResponse setFrameRate(Double frameRate) {
		this.frameRate = frameRate;
		return this;
	}

	public Integer getWidth() {
		return width;
	}

	public MediaInfoResponse setWidth(Integer width) {
		this.width = width;
		return this;
	}

	public Integer getHeight() {
		return height;
	}

	public MediaInfoResponse setHeight(Integer height) {
		this.height = height;
		return this;
	}

	public String getVideoCodec() {
		return videoCodec;
	}

	public MediaInfoResponse setVideoCodec(String videoCodec) {
		this.videoCodec = videoCodec;
		return this;
	}

	public String getAudioCodec() {
		return audioCodec;
	}

	public MediaInfoResponse setAudioCodec(String audioCodec) {
		this.audioCodec = audioCodec;
		return this;
	}

	public boolean isStreamable() {
		return streamable;
	}

	public MediaInfoResponse setStreamable(boolean streamable) {
		this.streamable = streamable;
		return this;
	}

	@Override
	public MediaInfoResponse self() {
		return this;
	}
}
