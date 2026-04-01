package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.cluster.ClusterCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.cluster.ClusterUpdateRequest;

public interface ClusterModelValidator extends ModelValidator {

	default void validate(ClusterUpdateRequest request) {
		requireNonNull(request, "A valid request must be specified");
	}

	default void validate(ClusterResponse response) {
		validateCreatorEditorResponse(response);
		requireNonNullOrEmpty(response.getName(), "A cluster name must be set");
	}

	default void validate(ClusterCreateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		requireNonNullOrEmpty(request.getName(), "A cluster name must be set");
	}
}
