package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.chat.ChatCreateRequest;
import io.metaloom.loom.rest.model.chat.ChatUpdateRequest;

public interface ChatModelValidator extends ModelValidator {

	default void validate(ChatUpdateRequest request) {

	}

	default void validate(ChatCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getTitle(), "A chat title must be set");
	}
}
