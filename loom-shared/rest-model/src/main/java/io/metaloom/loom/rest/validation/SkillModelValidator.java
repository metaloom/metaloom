package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.skill.SkillCreateRequest;
import io.metaloom.loom.rest.model.skill.SkillUpdateRequest;

public interface SkillModelValidator extends ModelValidator {

	int MAX_DESCRIPTION_LENGTH = 1024;

	default void validate(SkillCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A skill name must be set");
		requireNonNullOrEmpty(request.getDescription(), "A skill description must be set");
		requireNonNullOrEmpty(request.getContent(), "The skill content must be set");
		validateDescriptionLength(request.getDescription());
	}

	default void validate(SkillUpdateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		validateDescriptionLength(request.getDescription());
	}

	private void validateDescriptionLength(String description) {
		if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
			throw new ValidationException("The skill description must not exceed " + MAX_DESCRIPTION_LENGTH + " characters");
		}
	}

}
