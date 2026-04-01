package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.reaction.ReactionCreateRequest;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.metaloom.loom.rest.model.reaction.ReactionUpdateRequest;

public interface ReactionModelValidator extends ModelValidator {

	default void validate(ReactionUpdateRequest request) {

	}

	default void validate(ReactionResponse response) {

	}

	default void validate(ReactionCreateRequest request) {

	}
}
