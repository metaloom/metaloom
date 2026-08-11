package io.metaloom.loom.rest.model.share;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractResponse;

/**
 * One asset, as an unauthenticated share visitor sees it.
 *
 * <p>
 * <b>Deliberately not {@code AssetResponse}.</b> This is the only response model in the API that is rendered for somebody with no account, so it is
 * built by hand from a hand-picked list of fields rather than by reusing the internal one. Reuse would be less code and the wrong shape: every field a
 * future change adds to {@code AssetResponse} - a storage path, an internal note, a node's confidence score, the uuid of whoever uploaded it - would
 * be published to every share link in the world the day it was added, silently, with no test failing. A narrow projection makes the opposite
 * mistake the loud one: a field that is missing from the customer view is a visible gap, not an invisible leak.
 * </p>
 *
 * <p>
 * When the share has {@code showMetadata} off, everything below the mime type is left null - the viewer can still play the file, and that is all.
 * </p>
 */
public class SharedAssetResponse extends AbstractResponse<SharedAssetResponse> {

	@JsonPropertyDescription("Original file name.")
	private String filename;

	@JsonPropertyDescription("Media type, so the viewer knows whether to show a player, an image or a download link.")
	private String mimeType;

	@JsonPropertyDescription("File size in bytes. Null when the share hides metadata.")
	private Long size;

	@JsonPropertyDescription("Duration in seconds for audio and video. Null otherwise, or when the share hides metadata.")
	private Double duration;

	@JsonPropertyDescription("Pixel width for images and video. Null when the share hides metadata.")
	private Integer width;

	@JsonPropertyDescription("Pixel height for images and video. Null when the share hides metadata.")
	private Integer height;

	@JsonPropertyDescription("Title, when the material carries one. Null when the share hides metadata.")
	private String title;

	@JsonPropertyDescription("Description or caption. Null when the share hides metadata.")
	private String description;

	@JsonPropertyDescription("When the material was added. Null when the share hides metadata.")
	private Instant created;

	public String getFilename() {
		return filename;
	}

	public SharedAssetResponse setFilename(String filename) {
		this.filename = filename;
		return this;
	}

	public String getMimeType() {
		return mimeType;
	}

	public SharedAssetResponse setMimeType(String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public Long getSize() {
		return size;
	}

	public SharedAssetResponse setSize(Long size) {
		this.size = size;
		return this;
	}

	public Double getDuration() {
		return duration;
	}

	public SharedAssetResponse setDuration(Double duration) {
		this.duration = duration;
		return this;
	}

	public Integer getWidth() {
		return width;
	}

	public SharedAssetResponse setWidth(Integer width) {
		this.width = width;
		return this;
	}

	public Integer getHeight() {
		return height;
	}

	public SharedAssetResponse setHeight(Integer height) {
		this.height = height;
		return this;
	}

	public String getTitle() {
		return title;
	}

	public SharedAssetResponse setTitle(String title) {
		this.title = title;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public SharedAssetResponse setDescription(String description) {
		this.description = description;
		return this;
	}

	public Instant getCreated() {
		return created;
	}

	public SharedAssetResponse setCreated(Instant created) {
		this.created = created;
		return this;
	}

	@Override
	public SharedAssetResponse self() {
		return this;
	}
}
