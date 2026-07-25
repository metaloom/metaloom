package io.metaloom.loom.db.jooq.dao.chat;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.chat.Chat;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class ChatImpl extends AbstractEditableElement<Chat> implements Chat {

	private String title;

	private JsonArray messages = new JsonArray();

	private UUID spaceUuid;

	private JsonObject meta;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public Chat setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public JsonArray getMessages() {
		return messages;
	}

	@Override
	public Chat setMessages(JsonArray messages) {
		this.messages = messages;
		return this;
	}

	@Override
	public UUID getSpaceUuid() {
		return spaceUuid;
	}

	@Override
	public Chat setSpaceUuid(UUID spaceUuid) {
		this.spaceUuid = spaceUuid;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public Chat setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}
