package io.metaloom.loom.rest.model.reaction;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class ReactionResponse extends AbstractCreatorEditorRestResponse<ReactionResponse>
	implements ReactionModel<ReactionResponse> {

	private ReactionType type;

	private Integer rating;

	@Override
	public ReactionType getType() {
		return type;
	}

	@Override
	public ReactionResponse setType(ReactionType type) {
		this.type = type;
		return this;
	}

	@Override
	public Integer getRating() {
		return rating;
	}

	@Override
	public ReactionResponse setRating(Integer rating) {
		this.rating = rating;
		return this;
	}

	@Override
	public ReactionResponse self() {
		return this;
	}

}
