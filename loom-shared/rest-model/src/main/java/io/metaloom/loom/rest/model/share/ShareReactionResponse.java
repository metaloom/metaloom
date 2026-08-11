package io.metaloom.loom.rest.model.share;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractResponse;

/**
 * A share visitor's reaction to an asset, a guest comment or a guest annotation.
 */
public class ShareReactionResponse extends AbstractResponse<ShareReactionResponse> {

	private String type;

	private UUID assetUuid;

	private UUID commentUuid;

	private UUID annotationUuid;

	@JsonPropertyDescription("The visitor name as it stood when the reaction was left.")
	private String authorName;

	private Instant created;

	public String getType() {
		return type;
	}

	public ShareReactionResponse setType(String type) {
		this.type = type;
		return this;
	}

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public ShareReactionResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public UUID getCommentUuid() {
		return commentUuid;
	}

	public ShareReactionResponse setCommentUuid(UUID commentUuid) {
		this.commentUuid = commentUuid;
		return this;
	}

	public UUID getAnnotationUuid() {
		return annotationUuid;
	}

	public ShareReactionResponse setAnnotationUuid(UUID annotationUuid) {
		this.annotationUuid = annotationUuid;
		return this;
	}

	public String getAuthorName() {
		return authorName;
	}

	public ShareReactionResponse setAuthorName(String authorName) {
		this.authorName = authorName;
		return this;
	}

	public Instant getCreated() {
		return created;
	}

	public ShareReactionResponse setCreated(Instant created) {
		this.created = created;
		return this;
	}

	@Override
	public ShareReactionResponse self() {
		return this;
	}
}
