package io.metaloom.loom.api.options;

/**
 * Options which control the agent memory bank — scoped markdown notes the chat agent can list, read and write.
 *
 * <p>Notes live in the {@code memory_entry} table. When {@link #isMountEnabled()} is set they are additionally materialized into the per-chat Session
 * Runner as a <b>read-only</b> {@code /memory} folder, so the agent can also reach them with the ordinary coding tools.</p>
 */
public class MemoryOptions implements Option {

	public static final int DEFAULT_MAX_ENTRY_BYTES = 256 * 1024;

	public static final int DEFAULT_MAX_ENTRIES_PER_SCOPE = 500;

	public static final long DEFAULT_MAX_SCOPE_BYTES = 16L * 1024 * 1024;

	public static final int DEFAULT_MAX_DEPTH = 4;

	public static final int DEFAULT_MAX_WRITES_PER_RUN = 20;

	public static final int DEFAULT_PROMPT_MAX_ENTRIES = 50;

	public static final int DEFAULT_PROMPT_MAX_CHARS = 4096;

	public static final String DEFAULT_MOUNT_PATH = "/memory";

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_ENABLED", description = "Enable the agent memory bank (list_memory/get_memory/put_memory/delete_memory tools and the memory system prompt block).")
	private boolean enabled = false;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_MOUNT_ENABLED", description = "Materialize memory into the Session Runner as a read-only folder. The tools work without it.")
	private boolean mountEnabled = true;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_MOUNT_PATH", description = "Read-only path the memory folder is exposed at inside the Session Runner.")
	private String mountPath = DEFAULT_MOUNT_PATH;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_MAX_ENTRY_BYTES", description = "Maximum size in bytes of a single memory entry body.")
	private int maxEntryBytes = DEFAULT_MAX_ENTRY_BYTES;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_MAX_ENTRIES_PER_SCOPE", description = "Maximum number of memory entries per scope.")
	private int maxEntriesPerScope = DEFAULT_MAX_ENTRIES_PER_SCOPE;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_MAX_SCOPE_BYTES", description = "Maximum total body bytes per scope.")
	private long maxScopeBytes = DEFAULT_MAX_SCOPE_BYTES;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_MAX_DEPTH", description = "Maximum number of path segments of a memory id.")
	private int maxDepth = DEFAULT_MAX_DEPTH;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_MAX_WRITES_PER_RUN", description = "Maximum number of memory writes/deletes a single agent run may perform.")
	private int maxWritesPerRun = DEFAULT_MAX_WRITES_PER_RUN;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_PROMPT_MAX_ENTRIES", description = "Maximum number of memory index entries injected into the agent system prompt.")
	private int promptMaxEntries = DEFAULT_PROMPT_MAX_ENTRIES;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_PROMPT_MAX_CHARS", description = "Maximum size of the injected memory system prompt block.")
	private int promptMaxChars = DEFAULT_PROMPT_MAX_CHARS;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_SHARED_SCOPES_ENABLED", description = "Allow the group and space (project) memory scopes at all.")
	private boolean sharedScopesEnabled = true;

	@EnvironmentVariable(name = "LOOM_AGENT_MEMORY_SHARED_WRITE_ENABLED", description = "Allow the agent to write shared scopes. When off, shared memory is agent-read-only and curated by humans via REST/UI.")
	private boolean sharedWriteEnabled = true;

	public boolean isEnabled() {
		return enabled;
	}

	public MemoryOptions setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	public boolean isMountEnabled() {
		return mountEnabled;
	}

	public MemoryOptions setMountEnabled(boolean mountEnabled) {
		this.mountEnabled = mountEnabled;
		return this;
	}

	public String getMountPath() {
		return mountPath;
	}

	public MemoryOptions setMountPath(String mountPath) {
		this.mountPath = mountPath;
		return this;
	}

	public int getMaxEntryBytes() {
		return maxEntryBytes;
	}

	public MemoryOptions setMaxEntryBytes(int maxEntryBytes) {
		this.maxEntryBytes = maxEntryBytes;
		return this;
	}

	public int getMaxEntriesPerScope() {
		return maxEntriesPerScope;
	}

	public MemoryOptions setMaxEntriesPerScope(int maxEntriesPerScope) {
		this.maxEntriesPerScope = maxEntriesPerScope;
		return this;
	}

	public long getMaxScopeBytes() {
		return maxScopeBytes;
	}

	public MemoryOptions setMaxScopeBytes(long maxScopeBytes) {
		this.maxScopeBytes = maxScopeBytes;
		return this;
	}

	public int getMaxDepth() {
		return maxDepth;
	}

	public MemoryOptions setMaxDepth(int maxDepth) {
		this.maxDepth = maxDepth;
		return this;
	}

	public int getMaxWritesPerRun() {
		return maxWritesPerRun;
	}

	public MemoryOptions setMaxWritesPerRun(int maxWritesPerRun) {
		this.maxWritesPerRun = maxWritesPerRun;
		return this;
	}

	public int getPromptMaxEntries() {
		return promptMaxEntries;
	}

	public MemoryOptions setPromptMaxEntries(int promptMaxEntries) {
		this.promptMaxEntries = promptMaxEntries;
		return this;
	}

	public int getPromptMaxChars() {
		return promptMaxChars;
	}

	public MemoryOptions setPromptMaxChars(int promptMaxChars) {
		this.promptMaxChars = promptMaxChars;
		return this;
	}

	public boolean isSharedScopesEnabled() {
		return sharedScopesEnabled;
	}

	public MemoryOptions setSharedScopesEnabled(boolean sharedScopesEnabled) {
		this.sharedScopesEnabled = sharedScopesEnabled;
		return this;
	}

	public boolean isSharedWriteEnabled() {
		return sharedWriteEnabled;
	}

	public MemoryOptions setSharedWriteEnabled(boolean sharedWriteEnabled) {
		this.sharedWriteEnabled = sharedWriteEnabled;
		return this;
	}

	@Override
	public void validate(OptionErrors errors) {
		if (!enabled) {
			return;
		}
		errors.min("maxEntryBytes", maxEntryBytes, 1)
			.min("maxEntriesPerScope", maxEntriesPerScope, 1)
			.min("maxDepth", maxDepth, 1)
			.min("promptMaxEntries", promptMaxEntries, 1)
			.min("promptMaxChars", promptMaxChars, 1);
		if (maxScopeBytes <= 0) {
			errors.add("maxScopeBytes", "The maximum scope size (LOOM_AGENT_MEMORY_MAX_SCOPE_BYTES) must be positive.");
		}
		if (mountEnabled && (mountPath == null || mountPath.isBlank() || !mountPath.startsWith("/"))) {
			errors.add("mountPath", "The mount path (LOOM_AGENT_MEMORY_MOUNT_PATH) must be an absolute path.");
		}
	}

}
