package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;

public interface NodeResultModelValidator extends ModelValidator {

	default void validate(NodeResultCreateRequest request) {
		requireNonNullOrEmpty(request.getNodeKind(), "nodeKind");
		requireNonNullOrEmpty(request.getState(), "state");
		requireUuidFormat(request.getRunUuid(), "runUuid");
		requireUuidFormat(request.getTaskUuid(), "taskUuid");
	}

	/**
	 * Reject a value that is present but not a uuid - a malformed run/task reference must be a 400, not a 500 out of the database layer. Null stays
	 * legal: the ad-hoc and CLI paths have no run.
	 */
	private static void requireUuidFormat(String value, String fieldName) {
		if (value == null) {
			return;
		}
		try {
			java.util.UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			throw new ValidationException("The field " + fieldName + " is not a valid uuid: " + value);
		}
	}

	default void validate(NodeResultResponse response) {

	}
}
