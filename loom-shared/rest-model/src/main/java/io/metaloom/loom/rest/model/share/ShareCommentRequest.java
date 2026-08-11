package io.metaloom.loom.rest.model.share;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Write or edit a comment through a share link.
 *
 * <p>
 * The author is not in the request. It is taken from the share row server-side, because a field a visitor can set is a field a visitor can set to
 * somebody else's name.
 * </p>
 */
public class ShareCommentRequest implements RestRequestModel {

	@JsonPropertyDescription("The asset being commented on. Omit to comment on the shared collection as a whole. "
		+ "Must be a member of the share.")
	private UUID assetUuid;

	@JsonPropertyDescription("The comment being replied to. Replies are one level deep; replying to a reply attaches to its parent.")
	private UUID parentUuid;

	@JsonPropertyDescription("The mark on the media this comment belongs to, when anchoring it to one.")
	private UUID annotationUuid;

	@JsonPropertyDescription("The comment text.")
	private String text;

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public ShareCommentRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public UUID getParentUuid() {
		return parentUuid;
	}

	public ShareCommentRequest setParentUuid(UUID parentUuid) {
		this.parentUuid = parentUuid;
		return this;
	}

	public UUID getAnnotationUuid() {
		return annotationUuid;
	}

	public ShareCommentRequest setAnnotationUuid(UUID annotationUuid) {
		this.annotationUuid = annotationUuid;
		return this;
	}

	public String getText() {
		return text;
	}

	public ShareCommentRequest setText(String text) {
		this.text = text;
		return this;
	}
}
