package io.metaloom.loom.rest.model.comment;

import io.metaloom.loom.rest.model.asset.location.social.SocialInfo;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class CommentResponse extends AbstractCreatorEditorRestResponse<CommentResponse> implements CommentModel<CommentResponse> {

	private String title;

	private String text;

	private SocialInfo social;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public CommentResponse setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getText() {
		return text;
	}

	@Override
	public CommentResponse setText(String text) {
		this.text = text;
		return this;
	}

	public SocialInfo getSocial() {
		return social;
	}

	public CommentResponse setSocial(SocialInfo social) {
		this.social = social;
		return this;
	}

	@Override
	public CommentResponse self() {
		return this;
	}

}
