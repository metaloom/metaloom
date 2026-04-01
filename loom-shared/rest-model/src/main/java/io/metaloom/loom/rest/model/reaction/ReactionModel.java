package io.metaloom.loom.rest.model.reaction;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

public interface ReactionModel<T extends ReactionModel<T>> extends MetaModel<T>, RestModel {

	ReactionType getType();

	T setType(ReactionType type);

	Integer getRating();

	T setRating(Integer rating);

}
