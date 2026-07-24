package io.metaloom.loom.db.jooq.dao.chatsession;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.chatsession.ChatSession;
import io.vertx.core.json.JsonObject;

public class ChatSessionImpl extends AbstractEditableElement<ChatSession> implements ChatSession {

	private UUID chatUuid;
	private String name;
	private String description;
	private String[] tags;
	private boolean published;
	private UUID poolUuid;
	private String blobPath;
	private Long fsSize;
	private String fsSha256;
	private JsonObject meta;

	@Override
	public UUID getChatUuid() {
		return chatUuid;
	}

	@Override
	public ChatSession setChatUuid(UUID chatUuid) {
		this.chatUuid = chatUuid;
		return this;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public ChatSession setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public ChatSession setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public String[] getTags() {
		return tags;
	}

	@Override
	public ChatSession setTags(String[] tags) {
		this.tags = tags;
		return this;
	}

	@Override
	public boolean isPublished() {
		return published;
	}

	@Override
	public ChatSession setPublished(boolean published) {
		this.published = published;
		return this;
	}

	@Override
	public UUID getPoolUuid() {
		return poolUuid;
	}

	@Override
	public ChatSession setPoolUuid(UUID poolUuid) {
		this.poolUuid = poolUuid;
		return this;
	}

	@Override
	public String getBlobPath() {
		return blobPath;
	}

	@Override
	public ChatSession setBlobPath(String blobPath) {
		this.blobPath = blobPath;
		return this;
	}

	@Override
	public Long getFsSize() {
		return fsSize;
	}

	@Override
	public ChatSession setFsSize(Long fsSize) {
		this.fsSize = fsSize;
		return this;
	}

	@Override
	public String getFsSha256() {
		return fsSha256;
	}

	@Override
	public ChatSession setFsSha256(String fsSha256) {
		this.fsSha256 = fsSha256;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public ChatSession setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}
