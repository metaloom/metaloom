package io.metaloom.loom.rest.model.chat;

import java.util.UUID;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;
import io.vertx.core.json.JsonArray;

public interface ChatModel<T extends ChatModel<T>> extends MetaModel<T>, RestModel {

	String getTitle();

	T setTitle(String title);

	JsonArray getMessages();

	T setMessages(JsonArray messages);

	/**
	 * The space (project) the chat belongs to. Scopes space-level agent memory; may be null.
	 */
	UUID getSpaceUuid();

	T setSpaceUuid(UUID spaceUuid);

}
