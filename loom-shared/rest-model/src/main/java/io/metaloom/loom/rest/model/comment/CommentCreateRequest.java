package io.metaloom.loom.rest.model.comment;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class CommentCreateRequest extends AbstractMetaModel<CommentCreateRequest> implements RestRequestModel, CommentModel<CommentCreateRequest> {

	private String title;

	private String text;

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

	@Override
	public CommentCreateRequest self() {
		return this;
	}
}
