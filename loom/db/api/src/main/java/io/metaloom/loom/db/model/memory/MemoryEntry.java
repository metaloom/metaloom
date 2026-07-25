package io.metaloom.loom.db.model.memory;

import java.util.UUID;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;

/**
 * One markdown note in the agent memory bank, addressed by {@code (scope, scopeUuid, memoryId)}.
 *
 * <p>The frontmatter an agent sees is <b>not</b> stored: it is rendered from these columns whenever the note is read or materialized as a file, so the
 * header can neither drift from the row nor be forged by the model.</p>
 */
public interface MemoryEntry extends CUDElement<MemoryEntry>, MetaElement<MemoryEntry> {

	MemoryScope getScope();

	MemoryEntry setScope(MemoryScope scope);

	/**
	 * The {@code user.uuid}, {@code group.uuid} or {@code project.uuid} this entry belongs to, depending on {@link #getScope()}.
	 */
	UUID getScopeUuid();

	MemoryEntry setScopeUuid(UUID scopeUuid);

	/**
	 * The path-like id relative to the scope, e.g. {@code projects/loom-db.md}. Validated by {@code MemoryId} before it ever reaches the DAO.
	 */
	String getMemoryId();

	MemoryEntry setMemoryId(String memoryId);

	String getTitle();

	MemoryEntry setTitle(String title);

	/**
	 * The markdown body without frontmatter. Null on entries loaded through an index query, which deliberately does not project it.
	 */
	String getBody();

	MemoryEntry setBody(String body);

	/**
	 * Byte length of the body, denormalized so scope quotas are a single {@code SUM()}.
	 */
	int getSize();

	MemoryEntry setSize(int size);

	/**
	 * Digest of the rendered file (header + body), used to skip unchanged files when syncing into a session container.
	 */
	String getSha256();

	MemoryEntry setSha256(String sha256);

	/**
	 * Bumped on every update. The anchor for a future {@code memory_entry_version} table.
	 */
	int getVersion();

	MemoryEntry setVersion(int version);

	/**
	 * Name of the chat session that last wrote this entry, denormalized for the rendered header.
	 */
	String getSessionName();

	MemoryEntry setSessionName(String sessionName);

	UUID getChatUuid();

	MemoryEntry setChatUuid(UUID chatUuid);

}
