package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.notification.NotificationUpdateRequest;

public interface NotificationModelValidator extends ModelValidator {

	default void validate(NotificationUpdateRequest request) {
		requireNonNull(request, "A valid request must be specified");
		// An absent `read` would otherwise be coerced to false and silently un-read the entry.
		requireNonNull(request.getRead(), "The read flag must be specified");
	}
}
