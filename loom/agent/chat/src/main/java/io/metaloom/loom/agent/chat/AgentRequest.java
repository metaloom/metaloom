package io.metaloom.loom.agent.chat;

import java.util.List;
import java.util.UUID;

import io.vertx.ext.auth.User;

/**
 * A single "send message to the agent" request.
 *
 * @param chatUuid
 *            The chat session to run against (already loaded + ownership-checked by the endpoint service)
 * @param userUuid
 *            The loom user uuid of the caller
 * @param user
 *            The authenticated Vert.x user — used for permission-checked MCP tool dispatch (may be null when auth is disabled)
 * @param message
 *            The user message text
 * @param skillUuids
 *            The active skill uuids for this run (never null, may be empty)
 */
public record AgentRequest(UUID chatUuid, UUID userUuid, User user, String message, List<UUID> skillUuids) {

	public AgentRequest {
		skillUuids = skillUuids == null ? List.of() : skillUuids;
	}

}
