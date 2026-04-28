package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.chat.ChatListResponse;
import io.metaloom.loom.rest.model.chat.ChatResponse;

public interface ChatModelBuilder extends ModelBuilder, UserModelBuilder {

	default ChatResponse toResponse(Chat chat) {
		ChatResponse response = new ChatResponse();
		response.setTitle(chat.getTitle());
		response.setMessages(chat.getMessages());
		response.setUuid(chat.getUuid());
		response.setMeta(chat.getMeta());
		setStatus(chat, response);
		return response;
	}

	default ChatListResponse toChatList(Page<Chat> page) {
		return setPage(new ChatListResponse(), page, this::toResponse);
	}
}
