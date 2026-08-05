package io.metaloom.loom.rest.endpoint.impl;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;
import io.metaloom.loom.rest.model.ModelExamples;
import io.metaloom.loom.rest.service.impl.NotificationEndpointService;

/**
 * The caller's notification inbox.
 *
 * <p>
 * There is deliberately no create route: notifications are dispatched server-side. Every route here operates on the caller's own entries only — see
 * {@link NotificationEndpointService}.
 * </p>
 */
public class NotificationEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(NotificationEndpoint.class);

	private final NotificationEndpointService service;
	private final ModelExamples examples;

	@Inject
	public NotificationEndpoint(NotificationEndpointService service, EndpointDependencies deps, ModelExamples examples) {
		super(deps);
		this.service = service;
		this.examples = examples;
	}

	@Override
	public String name() {
		return "notification";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/notifications";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// List
		addListRoute(basePath(), GET,
			"Load a paged list of the caller's notifications. Pass ?unread=true to restrict to unread entries.",
			examples.notificationListResponseExample(),
			lrc -> {
				service.list(lrc);
			});

		// LITERAL PREFIX - must be registered before the /:uuid wildcard below, or Vert.x
		// matches "read-all" as a uuid path parameter and the route is unreachable.
		addRoute(basePath() + "/read-all", POST,
			"Mark all of the caller's notifications as read",
			null,
			examples.notificationMarkAllReadResponseExample(),
			lrc -> {
				service.markAllRead(lrc);
			});

		// Clear the whole inbox. Registered before /:uuid for the same reason as above, even
		// though the paths do not actually collide - keeping the literal routes together makes
		// the ordering rule visible rather than incidental.
		addRoute(basePath(), DELETE,
			"Delete all of the caller's notifications",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.clear(lrc);
			});

		// Read
		addRoute(basePath() + "/:uuid", GET,
			"Load one of the caller's notifications",
			null,
			examples.notificationResponseExample(),
			lrc -> {
				service.load(lrc, lrc.pathParamUUID("uuid"));
			});

		// Update (mark read / unread). POST /x/:uuid is the update verb across this API.
		addRoute(basePath() + "/:uuid", POST,
			"Mark a notification as read or unread",
			examples.notificationUpdateRequestExample(),
			examples.notificationResponseExample(),
			lrc -> {
				service.update(lrc, lrc.pathParamUUID("uuid"));
			});

		// Dismiss
		addRoute(basePath() + "/:uuid", DELETE,
			"Dismiss a notification",
			null,
			examples.deleteResponseExample(),
			lrc -> {
				service.delete(lrc, lrc.pathParamUUID("uuid"));
			});
	}
}
