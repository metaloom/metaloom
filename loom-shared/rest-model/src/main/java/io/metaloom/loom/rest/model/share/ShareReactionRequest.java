package io.metaloom.loom.rest.model.share;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * React to an asset, a guest comment or a guest annotation.
 *
 * <p>
 * Exactly one subject must be named. Reacting twice the same way is a no-op rather than an error - a double-clicked thumbs-up is a normal thing for a
 * person to do.
 * </p>
 */
public class ShareReactionRequest implements RestRequestModel {

	@JsonPropertyDescription("APPROVE, REJECT, THUMBSUP, THUMBSDOWN, LOVE or QUESTION. Its own vocabulary, not the internal ReactionType.")
	private String type;

	@JsonPropertyDescription("The asset being reacted to. Must be a member of the share.")
	private UUID assetUuid;

	@JsonPropertyDescription("The guest comment being reacted to.")
	private UUID commentUuid;

	@JsonPropertyDescription("The guest annotation being reacted to.")
	private UUID annotationUuid;

	public String getType() {
		return type;
	}

	public ShareReactionRequest setType(String type) {
		this.type = type;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public ShareReactionRequest setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public UUID getCommentUuid() {
		return commentUuid;
	}

	public ShareReactionRequest setCommentUuid(UUID commentUuid) {
		this.commentUuid = commentUuid;
		return this;
	}

	public UUID getAnnotationUuid() {
		return annotationUuid;
	}

	public ShareReactionRequest setAnnotationUuid(UUID annotationUuid) {
		this.annotationUuid = annotationUuid;
		return this;
	}
}
