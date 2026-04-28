package io.metaloom.loom.rest.model.chat;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;
import io.vertx.core.json.JsonArray;

public class ChatCreateRequest extends AbstractMetaModel<ChatCreateRequest> implements RestRequestModel, ChatModel<ChatCreateRequest> {

	private String title;

	private JsonArray messages;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public ChatCreateRequest setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public JsonArray getMessages() {
		return messages;
	}

	@Override
	public ChatCreateRequest setMessages(JsonArray messages) {
		this.messages = messages;
		return this;
	}

	@Override
	public ChatCreateRequest self() {
		return this;
	}
}
