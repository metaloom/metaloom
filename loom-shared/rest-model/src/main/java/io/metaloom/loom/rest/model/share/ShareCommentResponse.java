package io.metaloom.loom.rest.model.share;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.common.AbstractResponse;

/**
 * A comment left through a share link.
 *
 * <p>
 * There is no creator uuid, and that absence is the point: the author is a name a visitor typed, not a user. Anything rendering this next to an
 * internal comment must keep the two visually distinct.
 * </p>
 */
public class ShareCommentResponse extends AbstractResponse<ShareCommentResponse> {

	@JsonPropertyDescription("The asset this comment is about. Null when it addresses the shared collection as a whole.")
	private UUID assetUuid;

	@JsonPropertyDescription("The comment this one replies to, when it is a reply.")
	private UUID parentUuid;

	@JsonPropertyDescription("The mark on the media this comment belongs to, when it is anchored to one.")
	private UUID annotationUuid;

	private String text;

	@JsonPropertyDescription("The visitor name as it stood when the comment was written. Not a user reference - nobody here has an account.")
	private String authorName;

	private Instant created;

	private Instant edited;

	public UUID getAssetUuid() {
		return assetUuid;
	}

	public ShareCommentResponse setAssetUuid(UUID assetUuid) {
		this.assetUuid = assetUuid;
		return this;
	}

	public UUID getParentUuid() {
		return parentUuid;
	}

	public ShareCommentResponse setParentUuid(UUID parentUuid) {
		this.parentUuid = parentUuid;
		return this;
	}

	public UUID getAnnotationUuid() {
		return annotationUuid;
	}

	public ShareCommentResponse setAnnotationUuid(UUID annotationUuid) {
		this.annotationUuid = annotationUuid;
		return this;
	}

	public String getText() {
		return text;
	}

	public ShareCommentResponse setText(String text) {
		this.text = text;
		return this;
	}

	public String getAuthorName() {
		return authorName;
	}

	public ShareCommentResponse setAuthorName(String authorName) {
		this.authorName = authorName;
		return this;
	}

	public Instant getCreated() {
		return created;
	}

	public ShareCommentResponse setCreated(Instant created) {
		this.created = created;
		return this;
	}

	public Instant getEdited() {
		return edited;
	}

	public ShareCommentResponse setEdited(Instant edited) {
		this.edited = edited;
		return this;
	}

	@Override
	public ShareCommentResponse self() {
		return this;
	}
}
