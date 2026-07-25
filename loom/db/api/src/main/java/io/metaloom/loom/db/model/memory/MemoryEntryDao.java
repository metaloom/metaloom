package io.metaloom.loom.db.model.memory;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.CRUDDao;

public interface MemoryEntryDao extends CRUDDao<MemoryEntry> {

	/**
	 * Build an unsaved entry with the audit columns stamped. The caller sets body/title/size/sha256 before storing.
	 */
	MemoryEntry createMemoryEntry(UUID userUuid, MemoryScope scope, UUID scopeUuid, String memoryId);

	/**
	 * Load one entry by its natural key, or {@code null} when it does not exist.
	 */
	MemoryEntry loadByPath(MemoryScope scope, UUID scopeUuid, String memoryId);

	/**
	 * List the entries of one scope, ordered by {@code edited} descending.
	 *
	 * @param prefix
	 *            Optional id prefix filter (e.g. {@code projects/}); null or blank lists everything
	 * @param limit
	 *            Maximum number of rows
	 */
	List<MemoryEntry> listByScope(MemoryScope scope, UUID scopeUuid, String prefix, int limit);

	/**
	 * List the header fields of the entries across several scopes, ordered by {@code edited} descending.
	 *
	 * <p>This feeds the agent system prompt and therefore runs on every turn — it deliberately does <b>not</b> project {@code body}, so
	 * {@link MemoryEntry#getBody()} is null on the returned rows.</p>
	 */
	List<MemoryEntry> listIndex(List<MemoryScopeKey> scopes, int limit);

	/**
	 * Entry count and total body bytes of one scope, for quota checks.
	 */
	MemoryScopeStats stats(MemoryScope scope, UUID scopeUuid);

	/**
	 * Delete one entry by its natural key.
	 *
	 * @return true when a row was removed
	 */
	boolean deleteByPath(MemoryScope scope, UUID scopeUuid, String memoryId);

	/**
	 * A scope address — the pair that every memory query is keyed by.
	 */
	record MemoryScopeKey(MemoryScope scope, UUID scopeUuid) {
	}

	/**
	 * Aggregate usage of one scope.
	 */
	record MemoryScopeStats(int count, long bytes) {

		public static final MemoryScopeStats EMPTY = new MemoryScopeStats(0, 0L);
	}

}
