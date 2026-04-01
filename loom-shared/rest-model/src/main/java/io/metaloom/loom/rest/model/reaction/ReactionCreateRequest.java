package io.metaloom.loom.rest.model.reaction;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class ReactionCreateRequest extends AbstractMetaModel<ReactionCreateRequest>
	implements RestRequestModel, ReactionModel<ReactionCreateRequest> {

	private ReactionType type;

	private Integer rating;

	@Override
	public ReactionType getType() {
		return type;
	}

	@Override
	public ReactionCreateRequest setType(ReactionType type) {
		this.type = type;
		return this;
	}

	@Override
	public Integer getRating() {
		return rating;
	}

	@Override
	public ReactionCreateRequest setRating(Integer rating) {
		this.rating = rating;
		return this;
	}

	@Override
	public ReactionCreateRequest self() {
		return this;
	}

}
