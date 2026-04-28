package io.metaloom.loom.rest.model.chat;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class ChatListResponse extends AbstractListResponse<ChatListResponse, ChatResponse> {

	@Override
	public ChatListResponse self() {
		return this;
	}

}
