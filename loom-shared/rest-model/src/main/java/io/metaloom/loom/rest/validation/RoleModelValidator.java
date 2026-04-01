package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.role.RoleCreateRequest;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.model.role.RoleUpdateRequest;

public interface RoleModelValidator extends ModelValidator {

	default void validate(RoleUpdateRequest request) {
		requireNonNull(request, "A valid request must be specified");
	}

	default void validate(RoleResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response, "No role name was set");
	}

	default void validate(RoleCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A role name must be set");
	}
}
