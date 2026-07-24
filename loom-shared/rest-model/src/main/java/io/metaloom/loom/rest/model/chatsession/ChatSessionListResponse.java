package io.metaloom.loom.rest.model.chatsession;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class ChatSessionListResponse extends AbstractListResponse<ChatSessionListResponse, ChatSessionResponse> {

	@Override
	public ChatSessionListResponse self() {
		return this;
	}

}
