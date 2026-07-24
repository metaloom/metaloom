package io.metaloom.loom.rest.model.chatsession;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

/**
 * Request to capture a chat as a new chat session. Name/description default to the AI-generated ones
 * when omitted.
 */
public class ChatSessionCreateRequest extends AbstractMetaModel<ChatSessionCreateRequest> implements RestRequestModel, ChatSessionModel<ChatSessionCreateRequest> {

	private UUID chatUuid;
	private String name;
	private String description;
	private List<String> tags;
	private boolean published;

	@Override
	public UUID getChatUuid() {
		return chatUuid;
	}

	@Override
	public ChatSessionCreateRequest setChatUuid(UUID chatUuid) {
		this.chatUuid = chatUuid;
		return this;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public ChatSessionCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public ChatSessionCreateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public List<String> getTags() {
		return tags;
	}

	@Override
	public ChatSessionCreateRequest setTags(List<String> tags) {
		this.tags = tags;
		return this;
	}

	@Override
	public boolean isPublished() {
		return published;
	}

	@Override
	public ChatSessionCreateRequest setPublished(boolean published) {
		this.published = published;
		return this;
	}

	@Override
	public ChatSessionCreateRequest self() {
		return this;
	}
}
