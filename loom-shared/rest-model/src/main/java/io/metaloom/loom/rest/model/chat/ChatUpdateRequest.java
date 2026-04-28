package io.metaloom.loom.rest.model.chat;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;
import io.vertx.core.json.JsonArray;

public class ChatUpdateRequest extends AbstractMetaModel<ChatUpdateRequest> implements RestRequestModel, ChatModel<ChatUpdateRequest> {

	private String title;

	private JsonArray messages;

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public ChatUpdateRequest setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public JsonArray getMessages() {
		return messages;
	}

	@Override
	public ChatUpdateRequest setMessages(JsonArray messages) {
		this.messages = messages;
		return this;
	}

	@Override
	public ChatUpdateRequest self() {
		return this;
	}
}
