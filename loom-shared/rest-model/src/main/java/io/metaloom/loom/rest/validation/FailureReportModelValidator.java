package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.failure.FailureReportCreateRequest;
import io.metaloom.loom.rest.model.failure.FailureReportResponse;
import io.metaloom.loom.rest.model.failure.FailureReportUpdateRequest;

public interface FailureReportModelValidator extends ModelValidator {

	/**
	 * Deliberately thin.
	 *
	 * <p>
	 * Only {@code action} is required, and nothing else here rejects a report. Every other field is a best-effort description of a failure that has
	 * already happened, and the endpoint truncates or drops what it cannot use rather than refusing the submission - see
	 * {@code FailureReportEndpointService}. A validator that turned a malformed status code into a 400 would mean the client's own bug silently
	 * swallowed the user's only way to tell anyone about the bug they actually cared about.
	 * </p>
	 */
	default void validate(FailureReportCreateRequest request) {
		requireNonNullOrEmpty(request.getAction(), "An action must be set");
	}

	default void validate(FailureReportUpdateRequest request) {
		requireNonNullOrEmpty(request.getTriageStatus(), "A triage status must be set");
	}

	/**
	 * Like {@code ShareResponse}, this does <b>not</b> go through {@code validateCreatorEditorResponse}: the creator FK is
	 * {@code ON DELETE SET NULL}, so a report whose reporter has since been deleted has no creator and must still render.
	 */
	default void validate(FailureReportResponse response) {
		requireNonNull(response, null);
		requireNonNull(response.getUuid(), "A uuid must be set");
		requireNonNullOrEmpty(response.getAction(), "An action must be set");
	}
}
