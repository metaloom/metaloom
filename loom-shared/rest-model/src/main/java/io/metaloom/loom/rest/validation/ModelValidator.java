package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.metaloom.loom.rest.model.common.CreatorEditorStatus;
import io.metaloom.loom.rest.model.common.PagingInfo;
import io.metaloom.loom.rest.model.user.UserReference;

public interface ModelValidator {

	default void requireNonNull(Object value, String msg) {
		if (value == null) {
			throw new ValidationException(msg);
		}
	}

	default void requireNonNullOrEmpty(Object value, String msg) {
		if (value == null) {
			throw new ValidationException(msg);
		}
		if (value instanceof String s && s.trim().length() == 0) {
			throw new ValidationException(msg);
		}
	}

	default void validateCreatorEditorResponse(AbstractCreatorEditorRestResponse<?> response) {
		requireNonNull(response, null);
		requireNonNullOrEmpty(response.getUuid(), "A uuid must be set");
		validate(response.getStatus());
	}

	default void validate(CreatorEditorStatus status) {
		requireNonNull(status, "The editing status has not been set");
		requireNonNull(status.getCreator(), "A creator must be set");
		validate(status.getCreator());

		requireNonNull(status.getEditor(), "A editor must be set");
		validate(status.getEditor());

		requireNonNull(status.getEdited(), "The editing date was not set");
		requireNonNull(status.getCreated(), "The creation date was not set");
	}

	default void validate(UserReference reference) {
		requireNonNull(reference, "The user reference must be set");
		requireNonNullOrEmpty(reference.getName(), "The user reference name was not set");
		requireNonNull(reference.getUuid(), "The user reference uuid was not set");
	}

	default void validate(PagingInfo info) {
		requireNonNull(info.getPerPage(), "The per page info was not set");
	}

}
