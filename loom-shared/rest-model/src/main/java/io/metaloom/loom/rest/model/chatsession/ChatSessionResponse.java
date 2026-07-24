package io.metaloom.loom.rest.model.chatsession;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

/**
 * Chat session response. Beyond the shared fields it carries the pinned skill versions and the context
 * references, so the session detail page has everything from one load.
 */
public class ChatSessionResponse extends AbstractCreatorEditorRestResponse<ChatSessionResponse> implements ChatSessionModel<ChatSessionResponse> {

	private UUID chatUuid;
	private String name;
	private String description;
	private List<String> tags;
	private boolean published;
	private Long fsSize;
	private boolean hasFilesystem;
	private List<ChatSessionSkillPinModel> skills;
	private List<ChatSessionContextRefModel> contextRefs;

	@Override
	public UUID getChatUuid() {
		return chatUuid;
	}

	@Override
	public ChatSessionResponse setChatUuid(UUID chatUuid) {
		this.chatUuid = chatUuid;
		return this;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public ChatSessionResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public ChatSessionResponse setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public List<String> getTags() {
		return tags;
	}

	@Override
	public ChatSessionResponse setTags(List<String> tags) {
		this.tags = tags;
		return this;
	}

	@Override
	public boolean isPublished() {
		return published;
	}

	@Override
	public ChatSessionResponse setPublished(boolean published) {
		this.published = published;
		return this;
	}

	public Long getFsSize() {
		return fsSize;
	}

	public ChatSessionResponse setFsSize(Long fsSize) {
		this.fsSize = fsSize;
		return this;
	}

	public boolean isHasFilesystem() {
		return hasFilesystem;
	}

	public ChatSessionResponse setHasFilesystem(boolean hasFilesystem) {
		this.hasFilesystem = hasFilesystem;
		return this;
	}

	public List<ChatSessionSkillPinModel> getSkills() {
		return skills;
	}

	public ChatSessionResponse setSkills(List<ChatSessionSkillPinModel> skills) {
		this.skills = skills;
		return this;
	}

	public List<ChatSessionContextRefModel> getContextRefs() {
		return contextRefs;
	}

	public ChatSessionResponse setContextRefs(List<ChatSessionContextRefModel> contextRefs) {
		this.contextRefs = contextRefs;
		return this;
	}

	@Override
	public ChatSessionResponse self() {
		return this;
	}
}
