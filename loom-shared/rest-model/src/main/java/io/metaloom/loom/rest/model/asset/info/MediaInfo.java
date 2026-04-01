package io.metaloom.loom.rest.model.asset.info;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

public class MediaInfo implements RestModel {

	@JsonPropertyDescription("The duration of the media in milliseconds.")
	private Long duration;

	@JsonPropertyDescription("The video width in pixel of the asset.")
	private Integer width;

	@JsonPropertyDescription("The video height in pixel of the asset.")
	private Integer height;

	public Long getDuration() {
		return duration;
	}

	public MediaInfo setDuration(Long duration) {
		this.duration = duration;
		return this;
	}

	public Integer getWidth() {
		return width;
	}

	public MediaInfo setWidth(Integer width) {
		this.width = width;
		return this;
	}

	public Integer getHeight() {
		return height;
	}

	public MediaInfo setHeight(Integer height) {
		this.height = height;
		return this;
	}
}
