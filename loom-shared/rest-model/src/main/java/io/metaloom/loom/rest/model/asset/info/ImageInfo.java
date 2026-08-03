package io.metaloom.loom.rest.model.asset.info;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestModel;

public class ImageInfo implements RestModel {

	private String source;

	@JsonPropertyDescription("The dominant color for the asset.")
	private String dominantColor;

	private Integer width;

	private Integer height;

	@JsonPropertyDescription("The EXIF orientation flag (1-8) as declared by the file.")
	private Integer orientation;

	@JsonPropertyDescription("Bits per channel.")
	private Integer bitDepth;

	@JsonPropertyDescription("The image codec / container encoding, e.g. jpeg, png.")
	private String encoding;

	public String getSource() {
		return source;
	}

	public ImageInfo setSource(String source) {
		this.source = source;
		return this;
	}

	public Integer getWidth() {
		return width;
	}

	public ImageInfo setWidth(Integer width) {
		this.width = width;
		return this;
	}

	public Integer getHeight() {
		return height;
	}

	public ImageInfo setHeight(Integer height) {
		this.height = height;
		return this;
	}

	public String getDominantColor() {
		return dominantColor;
	}

	public ImageInfo setDominantColor(String dominantColor) {
		this.dominantColor = dominantColor;
		return this;
	}

	public Integer getOrientation() {
		return orientation;
	}

	public ImageInfo setOrientation(Integer orientation) {
		this.orientation = orientation;
		return this;
	}

	public Integer getBitDepth() {
		return bitDepth;
	}

	public ImageInfo setBitDepth(Integer bitDepth) {
		this.bitDepth = bitDepth;
		return this;
	}

	public String getEncoding() {
		return encoding;
	}

	public ImageInfo setEncoding(String encoding) {
		this.encoding = encoding;
		return this;
	}

}
