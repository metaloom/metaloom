package io.metaloom.loom.db.jooq.dao.webhook;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqWebhook;
import io.metaloom.loom.db.model.webhook.Webhook;
import io.metaloom.loom.db.model.webhook.WebhookDao;

@Singleton
public class WebhookDaoImpl extends AbstractJooqDao<Webhook> implements WebhookDao {

	@Inject
	public WebhookDaoImpl(DSLContext ctx) {
		super(ctx);
	}
	
	@Override
	public String getTypeName() {
		return "Webhooks";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqWebhook.WEBHOOK;
	}

	@Override
	protected Class<? extends Webhook> getPojoClass() {
		return WebhookImpl.class;
	}

	@Override
	public Webhook createWebhook(UUID userUuid, String url) {
		Webhook webhook = new WebhookImpl();
		webhook.setURL(url);
		setCreatorEditor(webhook, userUuid);
		return webhook;
	}

}
