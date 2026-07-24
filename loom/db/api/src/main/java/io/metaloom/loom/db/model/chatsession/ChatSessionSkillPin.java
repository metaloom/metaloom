package io.metaloom.loom.db.model.chatsession;

import java.util.UUID;

/**
 * Pins the skill version that was active for a chat session, so a shared session is reproducible.
 */
public class ChatSessionSkillPin {

	private UUID sessionUuid;
	private UUID skillUuid;
	private int skillVersion;

	public ChatSessionSkillPin() {
	}

	public ChatSessionSkillPin(UUID sessionUuid, UUID skillUuid, int skillVersion) {
		this.sessionUuid = sessionUuid;
		this.skillUuid = skillUuid;
		this.skillVersion = skillVersion;
	}

	public UUID getSessionUuid() {
		return sessionUuid;
	}

	public ChatSessionSkillPin setSessionUuid(UUID sessionUuid) {
		this.sessionUuid = sessionUuid;
		return this;
	}

	public UUID getSkillUuid() {
		return skillUuid;
	}

	public ChatSessionSkillPin setSkillUuid(UUID skillUuid) {
		this.skillUuid = skillUuid;
		return this;
	}

	public int getSkillVersion() {
		return skillVersion;
	}

	public ChatSessionSkillPin setSkillVersion(int skillVersion) {
		this.skillVersion = skillVersion;
		return this;
	}
}
