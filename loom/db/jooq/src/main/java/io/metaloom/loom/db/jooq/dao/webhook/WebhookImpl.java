package io.metaloom.loom.db.jooq.dao.webhook;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.webhook.Webhook;

public class WebhookImpl extends AbstractEditableElement<Webhook> implements Webhook {

	private String url;

	@Override
	public String getURL() {
		return url;
	}

	@Override
	public Webhook setURL(String url) {
		this.url = url;
		return this;
	}

}
