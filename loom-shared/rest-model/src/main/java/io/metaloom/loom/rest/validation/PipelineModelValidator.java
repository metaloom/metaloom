package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.pipeline.PipelineCreateRequest;
import io.metaloom.loom.rest.model.pipeline.PipelineResponse;
import io.metaloom.loom.rest.model.pipeline.PipelineUpdateRequest;

/**
 * Model-shape checks for the pipeline requests and responses.
 *
 * <p>
 * <b>Deliberately says nothing about the contents of a definition.</b> This module has no descriptor
 * registry and no graph parser, so anything it could check about a definition it could only check
 * badly and in duplicate — and it did: node ids, edge references and its own copy of Kahn's
 * algorithm lived here as well as in {@code PipelineValidationService} and in the editor, three
 * implementations of one rule set, free to drift. The server-side
 * {@code PipelineValidationService} is now the single authority, and it runs on every path that
 * accepts a definition ({@code create}, {@code update}, {@code POST /pipelines/validate} and the
 * {@code validate_pipeline} MCP tool). Do not reintroduce structural rules here.
 * </p>
 */
public interface PipelineModelValidator extends ModelValidator {

	default void validate(PipelineUpdateRequest request) {
		// Every field is optional on update; the definition, when supplied, is checked by
		// PipelineValidationService before anything is stored.
	}

	default void validate(PipelineResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNull(response.getVersionUuid(), "A pipeline version UUID must be set");
		requireNonNull(response.getVersionNumber(), "A pipeline version number must be set");
		requireNonNullOrEmpty(response.getName(), "A pipeline name must be set");
		requireNonNull(response.getDefinition(), "A pipeline definition must be set");
	}

	default void validate(PipelineCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A pipeline name must be set");
		requireNonNull(request.getDefinition(), "A pipeline definition must be set");
	}
}
