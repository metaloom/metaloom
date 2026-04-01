package io.metaloom.loom.rest.validation;

import io.metaloom.loom.rest.model.webhook.WebhookCreateRequest;
import io.metaloom.loom.rest.model.webhook.WebhookResponse;
import io.metaloom.loom.rest.model.webhook.WebhookUpdateRequest;

public interface WebhookModelValidator extends ModelValidator {

	default void validate(WebhookUpdateRequest request) {

	}

	default void validate(WebhookResponse response) {
		validateCreatorEditorResponse(response);
	}

	default void validate(WebhookCreateRequest request) {
		requireNonNullOrEmpty(request.getUrl(), "A webhook url must be specified.");
	}
}
