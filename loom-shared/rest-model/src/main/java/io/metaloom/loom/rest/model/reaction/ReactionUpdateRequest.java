package io.metaloom.loom.rest.model.reaction;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class ReactionUpdateRequest extends AbstractMetaModel<ReactionUpdateRequest>
	implements RestRequestModel, ReactionModel<ReactionUpdateRequest> {

	private ReactionType type;

	private Integer rating;

	@Override
	public ReactionType getType() {
		return type;
	}

	@Override
	public ReactionUpdateRequest setType(ReactionType type) {
		this.type = type;
		return this;
	}

	@Override
	public Integer getRating() {
		return rating;
	}

	@Override
	public ReactionUpdateRequest setRating(Integer rating) {
		this.rating = rating;
		return this;
	}

	@Override
	public ReactionUpdateRequest self() {
		return this;
	}

}
