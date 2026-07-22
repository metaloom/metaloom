package io.metaloom.loom.ai.rest;

import static io.metaloom.loom.rest.RESTConstants.API_V1_PATH;
import static io.vertx.core.http.HttpMethod.DELETE;
import static io.vertx.core.http.HttpMethod.POST;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.AbstractEndpoint;
import io.metaloom.loom.rest.EndpointDependencies;

/**
 * Streaming routes of the chat agent. Lives in the ai service module (not in {@code loom-service-rest}) since the agent loop depends on the MCP tool
 * registry, which itself depends on the rest module.
 *
 * <p>{@code POST /chats/:uuid/stream} runs the agent for a new user message and streams the run as Server-Sent Events;
 * {@code DELETE /chats/:uuid/stream} cancels the active run.</p>
 */
public class ChatStreamEndpoint extends AbstractEndpoint {

	private static final Logger log = LoggerFactory.getLogger(ChatStreamEndpoint.class);

	private final ChatStreamEndpointService service;

	@Inject
	public ChatStreamEndpoint(ChatStreamEndpointService service, EndpointDependencies deps) {
		super(deps);
		this.service = service;
	}

	@Override
	public String name() {
		return "chat-stream";
	}

	@Override
	public String basePath() {
		return API_V1_PATH + "/chats";
	}

	@Override
	public void register() {
		log.info("Registering {} endpoint", name());

		// Secure our own subtree — endpoint registration order across endpoints is undefined, so we
		// must not rely on the auth handler installed by the chat CRUD endpoint.
		secure(basePath() + "/:uuid/stream");

		// Stream (send message + receive agent events via SSE)
		addRoute(basePath() + "/:uuid/stream", POST,
			"Send a chat message to the agent and stream the run events back as Server-Sent Events",
			lrc -> {
				service.stream(lrc, lrc.pathParamUUID("uuid"));
			});

		// Cancel
		addRoute(basePath() + "/:uuid/stream", DELETE,
			"Cancel the active agent run of the chat session",
			lrc -> {
				service.cancel(lrc, lrc.pathParamUUID("uuid"));
			});
	}

}
