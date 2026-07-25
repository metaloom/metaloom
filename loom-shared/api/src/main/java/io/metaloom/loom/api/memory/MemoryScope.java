package io.metaloom.loom.api.memory;

/**
 * The scope a memory entry belongs to. Determines who can read and write it and which uuid {@code memory_entry.scope_uuid} points at.
 *
 * <p>Scopes are never resolved implicitly: a read or write always names the scope it means. A silent fall-through between scopes would make what an agent
 * reads depend on group membership, which is an authorization decision disguised as a convenience.</p>
 */
public enum MemoryScope {

	/** Private to one user. {@code scope_uuid} is a {@code user.uuid}. The default for every operation. */
	USER,

	/** Shared with the members of one RBAC group. {@code scope_uuid} is a {@code group.uuid}. */
	GROUP,

	/** Shared within one space (called "project" by users). {@code scope_uuid} is a {@code project.uuid}. */
	SPACE;

	/**
	 * Parse a scope from its lowercase wire form ({@code user}, {@code group}, {@code space}), as used by the MCP tools and the REST API.
	 *
	 * @return the scope, or {@code null} when the value is null, blank or unknown
	 */
	public static MemoryScope parse(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return valueOf(value.strip().toUpperCase());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * The lowercase wire form, also used as the directory name when memory is materialized into a session container.
	 */
	public String key() {
		return name().toLowerCase();
	}

	/**
	 * Whether this scope is shared with other users. Shared content is treated as untrusted data by the agent.
	 */
	public boolean isShared() {
		return this != USER;
	}

}
