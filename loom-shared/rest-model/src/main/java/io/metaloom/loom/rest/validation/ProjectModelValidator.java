package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.project.ProjectCreateRequest;
import io.metaloom.loom.rest.model.project.ProjectResponse;
import io.metaloom.loom.rest.model.project.ProjectUpdateRequest;

public interface ProjectModelValidator extends ModelValidator {

	default void validate(ProjectUpdateRequest request) {

	}

	default void validate(ProjectResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response.getName(), "A project name must be set");
	}

	default void validate(ProjectCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A project name must be set");
	}
}
