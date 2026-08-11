package io.metaloom.loom.rest.model.share;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Mark a moment, a region, or a region over a stretch of time.
 *
 * <p>
 * Coordinates are <b>normalised 0..1</b> against the media's own dimensions and times are <b>seconds as a float</b>. Pixels would be meaningless
 * across the viewport sizes a full-bleed viewer runs at, and whole seconds cannot name a frame.
 * </p>
 */
public class ShareAnnotationRequest implements RestRequestModel {

	@JsonPropertyDescription("The asset being marked. Must be a member of the share.")
	private UUID assetUuid;

	@JsonPropertyDescription("TEMPORAL, SPATIAL or SPATIOTEMPORAL. Must match the geometry supplied.")
	private String kind;

	@JsonPropertyDescription("Start in seconds from the beginning of the media. Required for TEMPORAL and SPATIOTEMPORAL.")
	private Double timeFrom;

	@JsonPropertyDescription("End in seconds. Omit to mark a single moment rather than a range.")
	private Double timeTo;

	@JsonPropertyDescription("Left edge of the region, 0..1 of the media width. Required for SPATIAL and SPATIOTEMPORAL.")
	private Double areaX;

	@JsonPropertyDescription("Top edge of the region, 0..1 of the media height. Required for SPATIAL and SPATIOTEMPORAL.")
	private Double areaY;

	@JsonPropertyDescription("Region width as a fraction of the media width, greater than 0 and at most 1.")
	private Double areaWidth;

	@JsonPropertyDescription("Region height as a fraction of the media height, greater than 0 and at most 1.")
	private Double areaHeight;

	@JsonPropertyDescription("What the visitor wants to say about the marked part.")
	private String text;

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public ShareAnnotationRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public String getKind() {
		return kind;
	}

	public ShareAnnotationRequest setKind(String kind) {
		this.kind = kind;
		return this;
	}

	public Double getTimeFrom() {
		return timeFrom;
	}

	public ShareAnnotationRequest setTimeFrom(Double timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	public Double getTimeTo() {
		return timeTo;
	}

	public ShareAnnotationRequest setTimeTo(Double timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	public Double getAreaX() {
		return areaX;
	}

	public ShareAnnotationRequest setAreaX(Double areaX) {
		this.areaX = areaX;
		return this;
	}

	public Double getAreaY() {
		return areaY;
	}

	public ShareAnnotationRequest setAreaY(Double areaY) {
		this.areaY = areaY;
		return this;
	}

	public Double getAreaWidth() {
		return areaWidth;
	}

	public ShareAnnotationRequest setAreaWidth(Double areaWidth) {
		this.areaWidth = areaWidth;
		return this;
	}

	public Double getAreaHeight() {
		return areaHeight;
	}

	public ShareAnnotationRequest setAreaHeight(Double areaHeight) {
		this.areaHeight = areaHeight;
		return this;
	}

	public String getText() {
		return text;
	}

	public ShareAnnotationRequest setText(String text) {
		this.text = text;
		return this;
	}
}
