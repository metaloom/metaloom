package io.metaloom.loom.rest.model.chatsession;

import java.util.UUID;

import io.metaloom.loom.rest.model.RestModel;

/**
 * One context reference: a source (published) session and which of its parts feed the referencing
 * session's context.
 */
public class ChatSessionContextRefModel implements RestModel {

	private UUID sourceSessionUuid;
	private boolean includeChatHistory;
	private boolean includeSkills;
	private boolean includeFilesystem;
	private int ordinal;

	public UUID getSourceSessionUuid() {
		return sourceSessionUuid;
	}

	public ChatSessionContextRefModel setSourceSessionUuid(UUID sourceSessionUuid) {
		this.sourceSessionUuid = sourceSessionUuid;
		return this;
	}

	public boolean isIncludeChatHistory() {
		return includeChatHistory;
	}

	public ChatSessionContextRefModel setIncludeChatHistory(boolean includeChatHistory) {
		this.includeChatHistory = includeChatHistory;
		return this;
	}

	public boolean isIncludeSkills() {
		return includeSkills;
	}

	public ChatSessionContextRefModel setIncludeSkills(boolean includeSkills) {
		this.includeSkills = includeSkills;
		return this;
	}

	public boolean isIncludeFilesystem() {
		return includeFilesystem;
	}

	public ChatSessionContextRefModel setIncludeFilesystem(boolean includeFilesystem) {
		this.includeFilesystem = includeFilesystem;
		return this;
	}

	public int getOrdinal() {
		return ordinal;
	}

	public ChatSessionContextRefModel setOrdinal(int ordinal) {
		this.ordinal = ordinal;
		return this;
	}
}
