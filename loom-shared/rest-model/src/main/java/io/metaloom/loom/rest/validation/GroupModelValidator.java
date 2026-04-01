package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.group.GroupCreateRequest;
import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.group.GroupUpdateRequest;

public interface GroupModelValidator extends ModelValidator {

	default void validate(GroupUpdateRequest request) {
		requireNonNull(request, "A valid request must be specified");
	}

	default void validate(GroupResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response, "No group name was set");
	}

	default void validate(GroupCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A group name must be set");
	}
}
