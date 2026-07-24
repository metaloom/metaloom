package io.metaloom.loom.db.model.chatsession;

import java.util.UUID;

/**
 * A live reference from one chat session to another (published) chat session, with per-part toggles
 * controlling which aspects of the source feed the referencing session's context.
 *
 * <p>Deleting a session cascades only its own ref rows (as referencing session and as source) — it
 * never deletes the referenced session or its skills.</p>
 */
public class ChatSessionContextRef {

	private UUID sessionUuid;
	private UUID sourceSessionUuid;
	private boolean includeChatHistory;
	private boolean includeSkills;
	private boolean includeFilesystem;
	private int ordinal;

	public ChatSessionContextRef() {
	}

	public ChatSessionContextRef(UUID sessionUuid, UUID sourceSessionUuid, boolean includeChatHistory, boolean includeSkills, boolean includeFilesystem,
		int ordinal) {
		this.sessionUuid = sessionUuid;
		this.sourceSessionUuid = sourceSessionUuid;
		this.includeChatHistory = includeChatHistory;
		this.includeSkills = includeSkills;
		this.includeFilesystem = includeFilesystem;
		this.ordinal = ordinal;
	}

	public UUID getSessionUuid() {
		return sessionUuid;
	}

	public ChatSessionContextRef setSessionUuid(UUID sessionUuid) {
		this.sessionUuid = sessionUuid;
		return this;
	}

	public UUID getSourceSessionUuid() {
		return sourceSessionUuid;
	}

	public ChatSessionContextRef setSourceSessionUuid(UUID sourceSessionUuid) {
		this.sourceSessionUuid = sourceSessionUuid;
		return this;
	}

	public boolean isIncludeChatHistory() {
		return includeChatHistory;
	}

	public ChatSessionContextRef setIncludeChatHistory(boolean includeChatHistory) {
		this.includeChatHistory = includeChatHistory;
		return this;
	}

	public boolean isIncludeSkills() {
		return includeSkills;
	}

	public ChatSessionContextRef setIncludeSkills(boolean includeSkills) {
		this.includeSkills = includeSkills;
		return this;
	}

	public boolean isIncludeFilesystem() {
		return includeFilesystem;
	}

	public ChatSessionContextRef setIncludeFilesystem(boolean includeFilesystem) {
		this.includeFilesystem = includeFilesystem;
		return this;
	}

	public int getOrdinal() {
		return ordinal;
	}

	public ChatSessionContextRef setOrdinal(int ordinal) {
		this.ordinal = ordinal;
		return this;
	}
}
