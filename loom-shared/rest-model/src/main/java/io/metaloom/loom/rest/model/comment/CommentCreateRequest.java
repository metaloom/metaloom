package io.metaloom.loom.rest.model.comment;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class CommentCreateRequest extends AbstractMetaModel<CommentCreateRequest> implements RestRequestModel, CommentModel<CommentCreateRequest> {

	private String title;

	private String text;

	@JsonProperty(required = false)
	@JsonPropertyDescription("Uuid of the comment this one replies to. Omit for a top-level comment.")
	private UUID parentUuid;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public CommentCreateRequest setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getText() {
		return text;
	}

	@Override
	public CommentCreateRequest setText(String text) {
		this.text = text;
		return this;
	}

	/**
	 * The comment being replied to, or null for a root comment.
	 *
	 * <p>
	 * Deliberately declared here and on the response rather than on {@code CommentModel}: the model interface is also implemented by
	 * {@code CommentUpdateRequest}, and putting it there would let a client re-parent an existing comment, which is a thread rewrite rather than an
	 * edit.
	 * </p>
	 */
	public UUID getParentUuid() {
		return parentUuid;
	}

	public CommentCreateRequest setParentUuid(UUID parentUuid) {
		this.parentUuid = parentUuid;
		return this;
	}

	@Override
	public CommentCreateRequest self() {
		return this;
	}
}
