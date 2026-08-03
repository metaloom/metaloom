package io.metaloom.loom.rest.model.asset.info;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

public class AudioInfo implements RestModel {

	private String source;

	@JsonPropertyDescription("The track language as declared by the container.")
	private String lang;

	@JsonPropertyDescription("The track title as declared by the container.")
	private String trackTitle;

	@JsonPropertyDescription("Whether the container marks this as the default track.")
	private Boolean isDefault;

	private Integer channels;

	private String encoding;

	private Integer bitrate;

	private Integer samplingRate;

	private Integer bpm;

	private Long duration;

	public String getSource() {
		return source;
	}

	public AudioInfo setSource(String source) {
		this.source = source;
		return this;
	}

	public Long getDuration() {
		return duration;
	}

	public AudioInfo setDuration(Long duration) {
		this.duration = duration;
		return this;
	}

	public Integer getChannels() {
		return channels;
	}

	public AudioInfo setChannels(Integer channels) {
		this.channels = channels;
		return this;
	}

	public Integer getSamplingRate() {
		return samplingRate;
	}

	public AudioInfo setSamplingRate(Integer samplingRate) {
		this.samplingRate = samplingRate;
		return this;
	}

	public Integer getBpm() {
		return bpm;
	}

	public AudioInfo setBpm(Integer bpm) {
		this.bpm = bpm;
		return this;
	}

	public String getEncoding() {
		return encoding;
	}

	public AudioInfo setEncoding(String encoding) {
		this.encoding = encoding;
		return this;
	}

	public Integer getBitrate() {
		return bitrate;
	}

	public AudioInfo setBitrate(Integer bitrate) {
		this.bitrate = bitrate;
		return this;
	}

	public String getLang() {
		return lang;
	}

	public AudioInfo setLang(String lang) {
		this.lang = lang;
		return this;
	}

	public String getTrackTitle() {
		return trackTitle;
	}

	public AudioInfo setTrackTitle(String trackTitle) {
		this.trackTitle = trackTitle;
		return this;
	}

	public Boolean getIsDefault() {
		return isDefault;
	}

	public AudioInfo setIsDefault(Boolean isDefault) {
		this.isDefault = isDefault;
		return this;
	}

}
