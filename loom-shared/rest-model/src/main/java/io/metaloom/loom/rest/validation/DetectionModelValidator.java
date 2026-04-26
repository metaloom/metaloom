package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.detection.DetectionUpdateRequest;

public interface DetectionModelValidator extends ModelValidator {

	default void validate(DetectionUpdateRequest request) {

	}

	default void validate(DetectionResponse response) {

	}

	default void validate(DetectionCreateRequest request) {

	}
}
