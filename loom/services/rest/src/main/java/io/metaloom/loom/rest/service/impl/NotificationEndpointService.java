package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.DELETE_NOTIFICATION;
import static io.metaloom.loom.db.model.perm.Permission.READ_NOTIFICATION;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_NOTIFICATION;

import java.time.Instant;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.notification.Notification;
import io.metaloom.loom.db.model.notification.NotificationDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.message.GenericMessageResponse;
import io.metaloom.loom.rest.model.notification.NotificationUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * The caller's own inbox.
 *
 * <p>
 * <b>Recipient-scoped, exactly as {@code SkillEndpointService} is owner-scoped.</b> The permissions gate the <i>feature</i>, not the individual row:
 * holding {@code READ_NOTIFICATION} lets you read <i>your</i> inbox and nobody else's. Every single-row operation therefore goes through
 * {@link #loadOwn(LoomRoutingContext, UUID)}, which answers <b>404 rather than 403</b> for a foreign notification — a 403 would confirm that the
 * uuid exists and leak the shape of another user's inbox.
 * </p>
 *
 * <p>
 * There is no create route. Notifications are dispatched server-side by {@code NotificationDispatcher}; letting a client post one would let anybody
 * forge a message from anybody.
 * </p>
 */
@Singleton
public class NotificationEndpointService extends AbstractCRUDEndpointService<NotificationDao, Notification> {

	/** A page big enough that the bell popover never needs a second request. */
	public static final int DEFAULT_PAGE_SIZE = 50;

	@Inject
	public NotificationEndpointService(NotificationDao dao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(dao, daos, modelBuilder, validator);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_NOTIFICATION, () -> {
			UUID userUuid = lrc.userUuid();
			// queryParam returns every occurrence; the first one wins, absent means false.
			java.util.List<String> unreadParam = lrc.queryParam("unread");
			boolean unreadOnly = !unreadParam.isEmpty() && Boolean.parseBoolean(unreadParam.get(0));
			int pageSize = lrc.pageSize() > 0 ? lrc.pageSize() : DEFAULT_PAGE_SIZE;
			Page<Notification> page = dao().loadPageForRecipient(userUuid, unreadOnly, pageSize);
			// The unread count is deliberately NOT derived from the page: the badge counts the
			// whole inbox, and with ?unread=true it would otherwise equal the page size.
			lrc.send(modelBuilder.toNotificationList(page, dao().countUnread(userUuid)));
		});
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		checkPerm(lrc, READ_NOTIFICATION, () -> {
			lrc.send(modelBuilder.toResponse(loadOwn(lrc, id)));
		});
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		// Unreachable: no create route is registered. Present only because the base class
		// declares it abstract.
		throw new LoomRestException(405, LoomRestErrorCode.BAD_REQUEST, "Notifications are dispatched by the server and cannot be created");
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		checkPerm(lrc, UPDATE_NOTIFICATION, () -> {
			NotificationUpdateRequest request = lrc.requestBody(NotificationUpdateRequest.class);
			validator.validate(request);

			Notification notification = loadOwn(lrc, id);
			boolean read = Boolean.TRUE.equals(request.getRead());
			notification.setRead(read);
			// Clearing the flag clears the timestamp too, so "read but never read" cannot occur.
			notification.setReadAt(read ? Instant.now() : null);
			notification.setEdited(Instant.now());
			dao().update(notification);
			lrc.send(modelBuilder.toResponse(notification));
		});
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		checkPerm(lrc, DELETE_NOTIFICATION, () -> {
			dao().delete(loadOwn(lrc, id));
			lrc.sendNoContent();
		});
	}

	/**
	 * Mark every unread entry of the caller as read.
	 */
	public void markAllRead(LoomRoutingContext lrc) {
		checkPerm(lrc, UPDATE_NOTIFICATION, () -> {
			int changed = dao().markAllRead(lrc.userUuid());
			lrc.send(new GenericMessageResponse().setMessage("Marked " + changed + " notifications as read"));
		});
	}

	/**
	 * Delete every entry of the caller. This is the retention escape hatch — nothing prunes the table automatically.
	 */
	public void clear(LoomRoutingContext lrc) {
		checkPerm(lrc, DELETE_NOTIFICATION, () -> {
			dao().deleteAllForRecipient(lrc.userUuid());
			lrc.sendNoContent();
		});
	}

	/**
	 * Load a notification, but only if it belongs to the caller.
	 *
	 * @throws LoomRestException 404 when it does not exist <b>or</b> belongs to somebody else — the two are deliberately indistinguishable
	 */
	private Notification loadOwn(LoomRoutingContext lrc, UUID uuid) {
		Notification notification = dao().load(uuid);
		if (notification == null || !notification.getRecipientUuid().equals(lrc.userUuid())) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Notification not found " + uuid);
		}
		return notification;
	}

}
