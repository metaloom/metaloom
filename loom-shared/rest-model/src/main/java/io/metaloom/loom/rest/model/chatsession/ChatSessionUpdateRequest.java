package io.metaloom.loom.rest.model.chatsession;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

/**
 * Request to edit a chat session's name / description / tags / publish flag. All fields are optional
 * (partial update).
 */
public class ChatSessionUpdateRequest extends AbstractMetaModel<ChatSessionUpdateRequest> implements RestRequestModel, ChatSessionModel<ChatSessionUpdateRequest> {

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
	public ChatSessionUpdateRequest setChatUuid(UUID chatUuid) {
		this.chatUuid = chatUuid;
		return this;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public ChatSessionUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public ChatSessionUpdateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public List<String> getTags() {
		return tags;
	}

	@Override
	public ChatSessionUpdateRequest setTags(List<String> tags) {
		this.tags = tags;
		return this;
	}

	@Override
	public boolean isPublished() {
		return published;
	}

	@Override
	public ChatSessionUpdateRequest setPublished(boolean published) {
		this.published = published;
		return this;
	}

	@Override
	public ChatSessionUpdateRequest self() {
		return this;
	}
}
