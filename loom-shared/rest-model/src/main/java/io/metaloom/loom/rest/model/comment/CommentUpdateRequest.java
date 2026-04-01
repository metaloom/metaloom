package io.metaloom.loom.rest.model.comment;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class CommentUpdateRequest extends AbstractMetaModel<CommentUpdateRequest> implements RestRequestModel, CommentModel<CommentUpdateRequest> {

	private String title;

	private String text;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public CommentUpdateRequest setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getText() {
		return text;
	}

	@Override
	public CommentUpdateRequest setText(String text) {
		this.text = text;
		return this;
	}

	@Override
	public CommentUpdateRequest self() {
		return this;
	}
}
