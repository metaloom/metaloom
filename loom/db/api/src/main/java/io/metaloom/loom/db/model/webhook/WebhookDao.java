package io.metaloom.loom.db.model.webhook;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface WebhookDao extends CRUDDao<Webhook> {

	default Webhook createWebhook(User user, String url) {
		return createWebhook(user.getUuid(), url);
	}

	Webhook createWebhook(UUID userUuid, String url);

}
