package io.metaloom.loom.agent.chat.rest;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static io.vertx.core.http.HttpMethod.PUT;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;

/**
 * CRUD + publish + context-composition endpoint for chat sessions.
 *
 * <ul>
 * <li>{@code GET  /chat/sessions?scope=mine|published} — list (own or the published library)</li>
 * <li>{@code POST /chat/sessions} — capture a chat as a session</li>
 * <li>{@code GET  /chat/sessions/:uuid} — detail (incl. skills + context refs)</li>
 * <li>{@code POST /chat/sessions/:uuid} — edit name/description/tags</li>
 * <li>{@code DELETE /chat/sessions/:uuid} — delete (cascades only its own refs/pins)</li>
 * <li>{@code POST /chat/sessions/:uuid/publish|unpublish} — toggle the publish flag</li>
 * <li>{@code GET|PUT /chat/sessions/:uuid/context} — read/replace the context references</li>
 * </ul>
 */
public class ChatSessionEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(ChatSessionEndpoint.class);

	private final ChatSessionEndpointService service;

	@Inject
	public ChatSessionEndpoint(ChatSessionEndpointService service, EndpointDependencies deps) {
		super(deps);
		this.service = service;
	}

	@Override
	public String name() {
		return "chat-session";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/chat/sessions";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		secure(basePath() + "*");

		// Literal sub-paths are registered before /:uuid so they are not consumed as a uuid.
		addRoute(basePath(), POST, "Capture a chat as a new chat session", lrc -> service.create(lrc));
		addRoute(basePath(), GET, "List chat sessions (scope=mine|published)", lrc -> service.list(lrc));

		addRoute(basePath() + "/:uuid/publish", POST, "Publish a chat session to the shared library",
			lrc -> service.publish(lrc, lrc.pathParamUUID("uuid")));
		addRoute(basePath() + "/:uuid/unpublish", POST, "Unpublish a chat session",
			lrc -> service.unpublish(lrc, lrc.pathParamUUID("uuid")));
		addRoute(basePath() + "/:uuid/context", GET, "Read the context references of a chat session",
			lrc -> service.getContext(lrc, lrc.pathParamUUID("uuid")));
		addRoute(basePath() + "/:uuid/context", PUT, "Replace the context references of a chat session",
			lrc -> service.putContext(lrc, lrc.pathParamUUID("uuid")));

		addRoute(basePath() + "/:uuid", POST, "Edit a chat session", lrc -> service.update(lrc, lrc.pathParamUUID("uuid")));
		addRoute(basePath() + "/:uuid", DELETE, "Delete a chat session", lrc -> service.delete(lrc, lrc.pathParamUUID("uuid")));
		addRoute(basePath() + "/:uuid", GET, "Load a chat session", lrc -> service.load(lrc, lrc.pathParamUUID("uuid")));
	}

}
