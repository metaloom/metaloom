package io.metaloom.loom.agent.chat.rest;

import static io.metaloom.loom.db.model.perm.Permission.UPDATE_CHAT;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.agent.chat.AgentRequest;
import io.metaloom.loom.agent.chat.AgentService;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.model.chat.ChatStreamRequest;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

/**
 * Handles the chat agent stream routes ({@code POST/DELETE /chats/:uuid/stream}): validates the request, guards busy chats and bridges {@link AgentService}
 * events onto an SSE response via {@link SseAgentEventSink}.
 */
@Singleton
public class ChatStreamEndpointService {

	private static final Logger log = LoggerFactory.getLogger(ChatStreamEndpointService.class);

	private final Vertx vertx;
	private final DaoCollection daos;
	private final AgentService agentService;

	@Inject
	public ChatStreamEndpointService(Vertx vertx, DaoCollection daos, AgentService agentService) {
		this.vertx = vertx;
		this.daos = daos;
		this.agentService = agentService;
	}

	/**
	 * Run the agent for a new user message and stream the run events back as SSE.
	 */
	public void stream(LoomRoutingContext lrc, UUID chatUuid) {
		lrc.requirePerm(UPDATE_CHAT).onSuccess(ignore -> {
			Chat chat = loadOwnedChat(lrc, chatUuid);

			ChatStreamRequest request = lrc.requestBody(ChatStreamRequest.class);
			if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "A message must be set.");
			}
			if (agentService.isBusy(chatUuid)) {
				throw new LoomRestException(409, LoomRestErrorCode.BAD_REQUEST, "An agent run is already active for this chat.");
			}

			List<UUID> skillUuids = parseSkillUuids(request.getSkillUuids());
			AgentRequest agentRequest = new AgentRequest(chatUuid, lrc.userUuid(), lrc.routingContext().user(), request.getMessage(), skillUuids);

			Context context = vertx.getOrCreateContext();
			SseAgentEventSink sink = new SseAgentEventSink(context, lrc.routingContext().response());

			// A client disconnect aborts the run
			lrc.routingContext().response().closeHandler(v -> agentService.abort(chatUuid));

			agentService.run(agentRequest, sink).onComplete(done -> {
				if (done.failed() && !sink.headersSent()) {
					if (done.cause() instanceof AgentService.AgentBusyException) {
						lrc.routingContext().fail(new LoomRestException(409, LoomRestErrorCode.BAD_REQUEST,
							"An agent run is already active for this chat."));
					} else {
						log.error("Agent run for chat {} failed before streaming started", chatUuid, done.cause());
						lrc.routingContext().fail(new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "Agent run failed."));
					}
					return;
				}
				sink.end();
			});
		}).onFailure(e -> {
			log.error("Failed to check perms", e);
			throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Invalid permissions");
		});
	}

	/**
	 * Explicitly cancel the active agent run of the given chat (idempotent).
	 */
	public void cancel(LoomRoutingContext lrc, UUID chatUuid) {
		lrc.requirePerm(UPDATE_CHAT).onSuccess(ignore -> {
			loadOwnedChat(lrc, chatUuid);
			agentService.abort(chatUuid);
			lrc.sendNoContent();
		}).onFailure(e -> {
			log.error("Failed to check perms", e);
			throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Invalid permissions");
		});
	}

	private Chat loadOwnedChat(LoomRoutingContext lrc, UUID chatUuid) {
		Chat chat = daos.chatDao().load(chatUuid);
		// Foreign chats must be indistinguishable from missing ones
		if (chat == null || !chat.getCreatorUuid().equals(lrc.userUuid())) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
		}
		return chat;
	}

	private List<UUID> parseSkillUuids(List<String> raw) {
		if (raw == null) {
			return List.of();
		}
		try {
			return raw.stream().map(UUID::fromString).toList();
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Invalid skill uuid.");
		}
	}

}
