package io.metaloom.loom.rest.model.pipeline;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * The verdict on a definition that was checked but not stored.
 *
 * <p>
 * A rejected definition is a <b>200 with {@code valid: false}</b>, not a 400: the caller asked a question and got an answer, and an author fixing a
 * draft wants every problem at once rather than one per round trip. That is why {@code errors} is a list — the create and update routes still fail on
 * the first problem, because there the definition is being stored and one reason not to store it is enough.
 * </p>
 *
 * <p>
 * {@code warnings} never block: a node kind no worker currently offers is a fact about the fleet, not about the definition.
 * </p>
 */
public class PipelineValidationResponse implements RestResponseModel<PipelineValidationResponse> {

	@JsonPropertyDescription("True when the definition would be accepted by create/update. Warnings do not make it false.")
	private boolean valid;

	@JsonPropertyDescription("Every problem found, not just the first. Empty when the definition is valid.")
	private List<PipelineValidationError> errors = new ArrayList<>();

	@JsonPropertyDescription("Things the author probably did not intend; may be non-empty for a valid definition.")
	private List<String> warnings = new ArrayList<>();

	public PipelineValidationResponse() {
	}

	public boolean isValid() {
		return valid;
	}

	public PipelineValidationResponse setValid(boolean valid) {
		this.valid = valid;
		return this;
	}

	public List<PipelineValidationError> getErrors() {
		return errors;
	}

	public PipelineValidationResponse setErrors(List<PipelineValidationError> errors) {
		this.errors = errors;
		return this;
	}

	public List<String> getWarnings() {
		return warnings;
	}

	public PipelineValidationResponse setWarnings(List<String> warnings) {
		this.warnings = warnings;
		return this;
	}

	@Override
	public PipelineValidationResponse self() {
		return this;
	}
}
