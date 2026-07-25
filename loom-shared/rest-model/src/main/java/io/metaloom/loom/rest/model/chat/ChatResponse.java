package io.metaloom.loom.rest.model.chat;

import java.util.UUID;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.vertx.core.json.JsonArray;

public class ChatResponse extends AbstractCreatorEditorRestResponse<ChatResponse> implements ChatModel<ChatResponse> {

	private String title;

	private JsonArray messages;

	private UUID spaceUuid;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public ChatResponse setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public JsonArray getMessages() {
		return messages;
	}

	@Override
	public ChatResponse setMessages(JsonArray messages) {
		this.messages = messages;
		return this;
	}

	@Override
	public UUID getSpaceUuid() {
		return spaceUuid;
	}

	@Override
	public ChatResponse setSpaceUuid(UUID spaceUuid) {
		this.spaceUuid = spaceUuid;
		return this;
	}

	@Override
	public ChatResponse self() {
		return this;
	}
}
