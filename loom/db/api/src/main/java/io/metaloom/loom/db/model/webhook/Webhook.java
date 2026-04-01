package io.metaloom.loom.db.model.webhook;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

public interface Webhook extends CUDElement<Webhook>, MetaElement<Webhook> {

	/**
	 * Return the connection URL of the webhook.
	 * 
	 * @return
	 */
	String getURL();

	/**
	 * Set the connection URL for the webhook.
	 * 
	 * @param url
	 * @return Fluent API
	 */
	Webhook setURL(String url);
}
