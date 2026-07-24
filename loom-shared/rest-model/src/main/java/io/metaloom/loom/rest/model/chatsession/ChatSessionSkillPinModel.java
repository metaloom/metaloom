package io.metaloom.loom.rest.model.chatsession;

import java.util.UUID;

import io.metaloom.loom.rest.model.RestModel;

/**
 * A pinned active skill version of a chat session (skill + version number).
 */
public class ChatSessionSkillPinModel implements RestModel {

	private UUID skillUuid;
	private int skillVersion;

	public UUID getSkillUuid() {
		return skillUuid;
	}

	public ChatSessionSkillPinModel setSkillUuid(UUID skillUuid) {
		this.skillUuid = skillUuid;
		return this;
	}

	public int getSkillVersion() {
		return skillVersion;
	}

	public ChatSessionSkillPinModel setSkillVersion(int skillVersion) {
		this.skillVersion = skillVersion;
		return this;
	}
}
