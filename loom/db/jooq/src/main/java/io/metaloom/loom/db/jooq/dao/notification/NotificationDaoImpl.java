package io.metaloom.loom.db.jooq.dao.notification;

import static io.metaloom.loom.db.jooq.tables.JooqNotification.NOTIFICATION;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.api.uuid.LoomUUID;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqNotification;
import io.metaloom.loom.db.model.notification.Notification;
import io.metaloom.loom.db.model.notification.NotificationDao;
import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.db.page.Page;

@Singleton
public class NotificationDaoImpl extends AbstractJooqDao<Notification> implements NotificationDao {

	@Inject
	public NotificationDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Notifications";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqNotification.NOTIFICATION;
	}

	@Override
	protected Class<? extends Notification> getPojoClass() {
		return NotificationImpl.class;
	}

	@Override
	public Notification createNotification(UUID recipientUuid, UUID actorUuid, NotificationType type, String title) {
		Objects.requireNonNull(recipientUuid, "The recipient uuid must be provided");
		Objects.requireNonNull(type, "The notification type must be provided");
		Notification notification = new NotificationImpl();
		// Assign the uuid here rather than letting the column default do it. storeBatch uses
		// jOOQ's batchInsert, which does NOT return generated keys, so a row created without
		// one comes back uuid-less - and the dispatcher needs the uuid to put the notification
		// on the wire immediately after writing it.
		notification.setUuid(LoomUUID.timeOrdered());
		notification.setRecipientUuid(recipientUuid);
		notification.setType(type);
		notification.setTitle(title);
		notification.setRead(false);
		// The audit columns carry the ACTOR, which may be absent for a machine-generated
		// event. setCreatorEditor would stamp both with the same uuid and cannot take null,
		// so the timestamps are set directly here.
		notification.setCreatorUuid(actorUuid);
		notification.setEditorUuid(actorUuid);
		Instant now = Instant.now();
		notification.setCreated(now);
		notification.setEdited(now);
		return notification;
	}

	@Override
	public Page<Notification> loadPageForRecipient(UUID recipientUuid, boolean unreadOnly, int pageSize) {
		Objects.requireNonNull(recipientUuid, "The recipient uuid must be provided");
		Condition condition = NOTIFICATION.RECIPIENT_UUID.eq(recipientUuid);
		if (unreadOnly) {
			condition = condition.and(NOTIFICATION.READ.isFalse());
		}

		long totalCount = ctx().fetchCount(NOTIFICATION, condition);

		// Bespoke rather than the inherited loadPage: AbstractJooqDao.getField(SortKey) casts
		// every sort column to Field<UUID>, so ordering by `created` through the generic path
		// throws a ClassCastException. The uuid tie-break keeps a burst written inside one
		// millisecond in a deterministic order.
		List<Notification> list = ctx()
			.selectFrom(NOTIFICATION)
			.where(condition)
			.orderBy(NOTIFICATION.CREATED.desc(), NOTIFICATION.UUID.desc())
			.limit(pageSize)
			.fetchInto(NotificationImpl.class)
			.stream()
			.map(Notification.class::cast)
			.toList();

		return new Page<>(pageSize, totalCount, list);
	}

	@Override
	public long countUnread(UUID recipientUuid) {
		Objects.requireNonNull(recipientUuid, "The recipient uuid must be provided");
		return ctx().fetchCount(NOTIFICATION,
			NOTIFICATION.RECIPIENT_UUID.eq(recipientUuid).and(NOTIFICATION.READ.isFalse()));
	}

	@Override
	public int markAllRead(UUID recipientUuid) {
		Objects.requireNonNull(recipientUuid, "The recipient uuid must be provided");
		// Restricted to unread rows so re-running it does not rewrite read_at on entries the
		// user acknowledged days ago.
		return ctx()
			.update(NOTIFICATION)
			.set(NOTIFICATION.READ, true)
			.set(NOTIFICATION.READ_AT, LocalDateTime.now(ZoneOffset.UTC))
			.where(NOTIFICATION.RECIPIENT_UUID.eq(recipientUuid).and(NOTIFICATION.READ.isFalse()))
			.execute();
	}

	@Override
	public int deleteAllForRecipient(UUID recipientUuid) {
		Objects.requireNonNull(recipientUuid, "The recipient uuid must be provided");
		return ctx()
			.deleteFrom(NOTIFICATION)
			.where(NOTIFICATION.RECIPIENT_UUID.eq(recipientUuid))
			.execute();
	}

}
