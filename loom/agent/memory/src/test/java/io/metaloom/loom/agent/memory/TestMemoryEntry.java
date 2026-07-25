package io.metaloom.loom.agent.memory;

import java.time.Instant;
import java.util.UUID;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.vertx.core.json.JsonObject;

/**
 * A plain in-memory {@link MemoryEntry} for the unit tests.
 *
 * <p>The production POJO lives in the jOOQ module, which this module deliberately does not depend on — the memory logic is storage-agnostic and its tests
 * should stay that way.</p>
 */
public class TestMemoryEntry implements MemoryEntry {

	private UUID uuid = UUID.randomUUID();
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
	private UUID creatorUuid;
	private UUID editorUuid;
	private Instant created;
	private Instant edited;

	@Override
	public UUID getUuid() {
		return uuid;
	}

	@Override
	public MemoryEntry setUuid(UUID uuid) {
		this.uuid = uuid;
		return this;
	}

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

	@Override
	public UUID getCreatorUuid() {
		return creatorUuid;
	}

	@Override
	public MemoryEntry setCreatorUuid(UUID creatorUuid) {
		this.creatorUuid = creatorUuid;
		return this;
	}

	@Override
	public UUID getEditorUuid() {
		return editorUuid;
	}

	@Override
	public MemoryEntry setEditorUuid(UUID editorUuid) {
		this.editorUuid = editorUuid;
		return this;
	}

	@Override
	public Instant getCreated() {
		return created;
	}

	@Override
	public MemoryEntry setCreated(Instant created) {
		this.created = created;
		return this;
	}

	@Override
	public Instant getEdited() {
		return edited;
	}

	@Override
	public MemoryEntry setEdited(Instant edited) {
		this.edited = edited;
		return this;
	}

}
