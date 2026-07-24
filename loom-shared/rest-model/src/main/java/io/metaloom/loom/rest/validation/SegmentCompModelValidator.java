package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.segmentcomp.SegmentCompCreateRequest;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompResponse;

public interface SegmentCompModelValidator extends ModelValidator {

	default void validate(SegmentCompCreateRequest request) {
		requireNonNullOrEmpty(request.getNodeKind(), "nodeKind");
		requireNonNullOrEmpty(request.getSegmentType(), "segmentType");
	}

	default void validate(SegmentCompResponse response) {

	}
}
