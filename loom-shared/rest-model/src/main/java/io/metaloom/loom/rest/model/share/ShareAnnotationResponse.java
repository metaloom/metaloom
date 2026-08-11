package io.metaloom.loom.rest.model.share;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractResponse;

/**
 * A mark a share visitor drew on the media. Coordinates are normalised 0..1; times are seconds as a float.
 */
public class ShareAnnotationResponse extends AbstractResponse<ShareAnnotationResponse> {

	private UUID assetUuid;

	@JsonPropertyDescription("TEMPORAL, SPATIAL or SPATIOTEMPORAL.")
	private String kind;

	private Double timeFrom;

	private Double timeTo;

	@JsonPropertyDescription("Left edge of the region, 0..1 of the media width.")
	private Double areaX;

	@JsonPropertyDescription("Top edge of the region, 0..1 of the media height.")
	private Double areaY;

	private Double areaWidth;

	private Double areaHeight;

	private String text;

	@JsonPropertyDescription("The visitor name as it stood when the mark was drawn.")
	private String authorName;

	private Instant created;

	private Instant edited;

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public ShareAnnotationResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public String getKind() {
		return kind;
	}

	public ShareAnnotationResponse setKind(String kind) {
		this.kind = kind;
		return this;
	}

	public Double getTimeFrom() {
		return timeFrom;
	}

	public ShareAnnotationResponse setTimeFrom(Double timeFrom) {
		this.timeFrom = timeFrom;
		return this;
	}

	public Double getTimeTo() {
		return timeTo;
	}

	public ShareAnnotationResponse setTimeTo(Double timeTo) {
		this.timeTo = timeTo;
		return this;
	}

	public Double getAreaX() {
		return areaX;
	}

	public ShareAnnotationResponse setAreaX(Double areaX) {
		this.areaX = areaX;
		return this;
	}

	public Double getAreaY() {
		return areaY;
	}

	public ShareAnnotationResponse setAreaY(Double areaY) {
		this.areaY = areaY;
		return this;
	}

	public Double getAreaWidth() {
		return areaWidth;
	}

	public ShareAnnotationResponse setAreaWidth(Double areaWidth) {
		this.areaWidth = areaWidth;
		return this;
	}

	public Double getAreaHeight() {
		return areaHeight;
	}

	public ShareAnnotationResponse setAreaHeight(Double areaHeight) {
		this.areaHeight = areaHeight;
		return this;
	}

	public String getText() {
		return text;
	}

	public ShareAnnotationResponse setText(String text) {
		this.text = text;
		return this;
	}

	public String getAuthorName() {
		return authorName;
	}

	public ShareAnnotationResponse setAuthorName(String authorName) {
		this.authorName = authorName;
		return this;
	}

	public Instant getCreated() {
		return created;
	}

	public ShareAnnotationResponse setCreated(Instant created) {
		this.created = created;
		return this;
	}

	public Instant getEdited() {
		return edited;
	}

	public ShareAnnotationResponse setEdited(Instant edited) {
		this.edited = edited;
		return this;
	}

	@Override
	public ShareAnnotationResponse self() {
		return this;
	}
}
