package io.metaloom.loom.db.jooq.dao.skill;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.skill.SkillVersion;
import io.vertx.core.json.JsonObject;

public class SkillVersionImpl extends AbstractEditableElement<SkillVersion> implements SkillVersion {

	private UUID skillUuid;

	private int versionNumber;

	private String description;

	private String content;

	private JsonObject meta;

	@Override
	public UUID getSkillUuid() {
		return skillUuid;
	}

	@Override
	public SkillVersion setSkillUuid(UUID skillUuid) {
		this.skillUuid = skillUuid;
		return this;
	}

	@Override
	public int getVersionNumber() {
		return versionNumber;
	}

	@Override
	public SkillVersion setVersionNumber(int versionNumber) {
		this.versionNumber = versionNumber;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public SkillVersion setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public String getContent() {
		return content;
	}

	@Override
	public SkillVersion setContent(String content) {
		this.content = content;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public SkillVersion setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}
