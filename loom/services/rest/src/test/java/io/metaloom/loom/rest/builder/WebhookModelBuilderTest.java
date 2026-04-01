package io.metaloom.loom.rest.builder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.model.webhook.Webhook;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.webhook.WebhookListResponse;

public class WebhookModelBuilderTest extends AbstractModelBuilderTest {

	@Test
	@Override
	void testResponseModel() throws IOException {
		Webhook webhook = mockWebhook();
		assertWithModel(builder().toResponse(webhook), "webhook.response");
	}

	@Test
	@Override
	void testListResponseModel() throws IOException {
		Webhook webhook1 = mockWebhook();
		Webhook webhook2 = mockWebhook();
		Page<Webhook> page = mockPage(webhook1, webhook2);
		WebhookListResponse list = builder().toWebhookList(page);
		assertWithModel(list, "webhook.list_response");
	}

	private Webhook mockWebhook() {
		Webhook hook = mock(Webhook.class);
		when(hook.getUuid()).thenReturn(WEBHOOK_UUID);
		return hook;
	}
}
