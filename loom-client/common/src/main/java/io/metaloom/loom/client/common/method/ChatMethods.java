package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.chat.ChatCreateRequest;
import io.metaloom.loom.rest.model.chat.ChatListResponse;
import io.metaloom.loom.rest.model.chat.ChatResponse;
import io.metaloom.loom.rest.model.chat.ChatUpdateRequest;

public interface ChatMethods {

	LoomClientRequest<ChatResponse> loadChat(UUID chatUuid);

	LoomClientRequest<ChatResponse> createChat(ChatCreateRequest request);

	LoomClientRequest<ChatResponse> updateChat(UUID chatUuid, ChatUpdateRequest request);

	LoomClientRequest<ChatListResponse> listChats();

	LoomClientRequest<NoResponse> deleteChat(UUID chatUuid);

}
