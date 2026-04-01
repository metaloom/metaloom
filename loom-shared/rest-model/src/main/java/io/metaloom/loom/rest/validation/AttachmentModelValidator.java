package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.attachment.AttachmentResponse;
import io.metaloom.loom.rest.model.attachment.AttachmentUpdateRequest;

public interface AttachmentModelValidator extends ModelValidator {

	default void validate(AttachmentUpdateRequest request) {

	}

	default void validate(AttachmentResponse response) {
		requireNonNull(response, "No valid request was provided.");
		validateCreatorEditorResponse(response);
		requireNonNull(response.getSha512sum(), "The SHA512 checksum must be set");
		requireNonNull(response.getFilename(), "The filename must be set");
		requireNonNull(response.getMimeType(), "The mimeType must be set");
	}

}
