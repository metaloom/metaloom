package io.metaloom.loom.db.model.chat;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;
import io.vertx.core.json.JsonArray;

public interface Chat extends CUDElement<Chat>, MetaElement<Chat> {

	String getTitle();

	Chat setTitle(String title);

	JsonArray getMessages();

	Chat setMessages(JsonArray messages);

	/**
	 * The space (called "project" by users, table {@code project}) this chat originates from, or {@code null} when the chat was not created within one.
	 * Scopes space-level agent memory.
	 */
	UUID getSpaceUuid();

	Chat setSpaceUuid(UUID spaceUuid);

}
