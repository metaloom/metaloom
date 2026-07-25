package io.metaloom.loom.db.jooq.dao.memory;

import java.util.UUID;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.vertx.core.json.JsonObject;

public class MemoryEntryImpl extends AbstractEditableElement<MemoryEntry> implements MemoryEntry {

	private MemoryScope scope;

	private UUID scopeUuid;

	private String memoryId;

	private String title;

	private String body;

	private int size;

	private String sha256;

	private int version = 1;

	private String sessionName;

	private UUID chatUuid;

	private JsonObject meta;

	@Override
	public MemoryScope getScope() {
		return scope;
	}

	@Override
	public MemoryEntry setScope(MemoryScope scope) {
		this.scope = scope;
		return this;
	}

	@Override
	public UUID getScopeUuid() {
		return scopeUuid;
	}

	@Override
	public MemoryEntry setScopeUuid(UUID scopeUuid) {
		this.scopeUuid = scopeUuid;
		return this;
	}

	@Override
	public String getMemoryId() {
		return memoryId;
	}

	@Override
	public MemoryEntry setMemoryId(String memoryId) {
		this.memoryId = memoryId;
		return this;
	}

	@Override
	public String getTitle() {
		return title;
	}

	@Override
	public MemoryEntry setTitle(String title) {
		this.title = title;
		return this;
	}

	@Override
	public String getBody() {
		return body;
	}

	@Override
	public MemoryEntry setBody(String body) {
		this.body = body;
		return this;
	}

	@Override
	public int getSize() {
		return size;
	}

	@Override
	public MemoryEntry setSize(int size) {
		this.size = size;
		return this;
	}

	@Override
	public String getSha256() {
		return sha256;
	}

	@Override
	public MemoryEntry setSha256(String sha256) {
		this.sha256 = sha256;
		return this;
	}

	@Override
	public int getVersion() {
		return version;
	}

	@Override
	public MemoryEntry setVersion(int version) {
		this.version = version;
		return this;
	}

	@Override
	public String getSessionName() {
		return sessionName;
	}

	@Override
	public MemoryEntry setSessionName(String sessionName) {
		this.sessionName = sessionName;
		return this;
	}

	@Override
	public UUID getChatUuid() {
		return chatUuid;
	}

	@Override
	public MemoryEntry setChatUuid(UUID chatUuid) {
		this.chatUuid = chatUuid;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public MemoryEntry setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}
