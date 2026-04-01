package io.metaloom.loom.rest.model.annotation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Spatial or temporal area used to annotate the asset.
 */
public class AreaInfo {

	@JsonPropertyDescription("Start time of the entry in milliseconds")
	private Long from;

	@JsonPropertyDescription("End time of the entry in milliseconds")
	private Long to;

	private Integer width;
	private Integer height;
	private Integer startX;
	private Integer startY;

	public Integer getWidth() {
		return width;
	}

	public AreaInfo setWidth(Integer width) {
		this.width = width;
		return this;
	}

	public Integer getHeight() {
		return height;
	}

	public AreaInfo setHeight(Integer height) {
		this.height = height;
		return this;
	}

	public Integer getStartX() {
		return startX;
	}

	public AreaInfo setStartX(Integer startX) {
		this.startX = startX;
		return this;
	}

	public Integer getStartY() {
		return startY;
	}

	public AreaInfo setStartY(Integer startY) {
		this.startY = startY;
		return this;
	}

	public Long getFrom() {
		return from;
	}

	public AreaInfo setFrom(Long from) {
		this.from = from;
		return this;
	}

	public Long getTo() {
		return to;
	}

	public AreaInfo setTo(Long to) {
		this.to = to;
		return this;
	}

	public static AreaInfo create(int startX, int startY, int width, int height) {
		return new AreaInfo().setStartX(startX).setStartY(startY).setWidth(width).setHeight(height);
	}
}
