package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;

public interface PipelineModelValidator extends ModelValidator {

	default void validate(PipelineUpdateRequest request) {

	}

	default void validate(PipelineResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response.getName(), "A pipeline name must be set");
	}

	default void validate(PipelineCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A pipeline name must be set");
		requireNonNull(request.getDefinition(), "A pipeline definition must be set");
	}
}
