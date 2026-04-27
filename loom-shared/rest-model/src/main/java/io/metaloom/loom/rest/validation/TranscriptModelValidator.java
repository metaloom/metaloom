package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.transcript.TranscriptCreateRequest;
import io.metaloom.loom.rest.model.transcript.TranscriptResponse;
import io.metaloom.loom.rest.model.transcript.TranscriptUpdateRequest;

public interface TranscriptModelValidator extends ModelValidator {

	default void validate(TranscriptUpdateRequest request) {

	}

	default void validate(TranscriptResponse response) {

	}

	default void validate(TranscriptCreateRequest request) {

	}
}
