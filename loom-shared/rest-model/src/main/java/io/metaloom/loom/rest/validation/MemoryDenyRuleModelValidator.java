package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.memory.MemoryDenyRuleCreateRequest;
import io.metaloom.loom.rest.model.memory.MemoryDenyRuleUpdateRequest;

public interface MemoryDenyRuleModelValidator extends ModelValidator {

	int MAX_NAME_LENGTH = 255;

	int MAX_MESSAGE_LENGTH = 1024;

	default void validate(MemoryDenyRuleCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A deny rule name must be set");
		requireNonNullOrEmpty(request.getPattern(), "A deny rule pattern must be set");
		requireNonNullOrEmpty(request.getMessage(), "A deny rule message must be set");
		validateLengths(request.getName(), request.getMessage());
	}

	default void validate(MemoryDenyRuleUpdateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		validateLengths(request.getName(), request.getMessage());
	}

	/**
	 * The pattern itself is validated in the endpoint service, which compiles it — that check lives next to the matcher that has to run it.
	 */
	private void validateLengths(String name, String message) {
		if (name != null && name.length() > MAX_NAME_LENGTH) {
			throw new ValidationException("The deny rule name must not exceed " + MAX_NAME_LENGTH + " characters");
		}
		if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
			throw new ValidationException("The deny rule message must not exceed " + MAX_MESSAGE_LENGTH + " characters");
		}
	}

}
