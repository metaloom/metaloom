package io.metaloom.loom.rest.model.assertj;

import io.metaloom.loom.rest.model.webhook.WebhookResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class WebhookModelAssert extends AbstractModelAssert<WebhookModelAssert, WebhookResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public WebhookModelAssert(WebhookResponse actual) {
		super(actual, WebhookModelAssert.class);
	}

	public WebhookModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

}
