package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.chatsession.ChatSessionCreateRequest;
import io.metaloom.loom.rest.model.chatsession.ChatSessionUpdateRequest;

public interface ChatSessionModelValidator extends ModelValidator {

	default void validate(ChatSessionCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		// chatUuid is optional — a manually created session need not capture an existing chat.
		requireNonNullOrEmpty(request.getName(), "A session name must be set");
	}

	default void validate(ChatSessionUpdateRequest request) {
		requireNonNull(request, "A valid request must be specified");
	}
}
