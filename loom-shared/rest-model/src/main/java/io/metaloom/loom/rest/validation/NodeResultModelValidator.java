package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;

public interface NodeResultModelValidator extends ModelValidator {

	default void validate(NodeResultCreateRequest request) {
		requireNonNullOrEmpty(request.getNodeKind(), "nodeKind");
		requireNonNullOrEmpty(request.getState(), "state");
	}

	default void validate(NodeResultResponse response) {

	}
}
