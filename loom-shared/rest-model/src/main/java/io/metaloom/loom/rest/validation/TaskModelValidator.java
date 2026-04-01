package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.model.task.TaskUpdateRequest;

public interface TaskModelValidator extends ModelValidator {

	default void validate(TaskUpdateRequest request) {

	}

	default void validate(TaskResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response.getTitle(), "The task title is missing");
	}

	default void validate(TaskCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getTitle(), "The task title is missing");
	}
}
