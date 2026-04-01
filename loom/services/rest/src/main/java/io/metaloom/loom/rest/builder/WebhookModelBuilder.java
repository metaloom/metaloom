package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.webhook.Webhook;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.webhook.WebhookListResponse;
import io.metaloom.loom.rest.model.webhook.WebhookResponse;

public interface WebhookModelBuilder extends ModelBuilder, UserModelBuilder {

	default WebhookResponse toResponse(Webhook webhook) {
		WebhookResponse response = new WebhookResponse();
		response.setUuid(webhook.getUuid());
		response.setUrl(webhook.getURL());
		setStatus(webhook, response);
		return response;
	}

	default WebhookListResponse toWebhookList(Page<Webhook> page) {
		return setPage(new WebhookListResponse(), page, this::toResponse);
	}
}
