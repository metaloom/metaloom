package io.metaloom.loom.rest.model.webhook;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class WebhookListResponse extends AbstractListResponse<WebhookListResponse, WebhookResponse> {

	@Override
	public WebhookListResponse self() {
		return this;
	}

}
