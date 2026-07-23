package io.metaloom.loom.db.jooq.dao.skill;

import java.util.UUID;

import io.metaloom.loom.db.jooq.AbstractEditableElement;
import io.metaloom.loom.db.model.skill.Skill;
import io.vertx.core.json.JsonObject;

public class SkillImpl extends AbstractEditableElement<Skill> implements Skill {

	private String name;

	private String description;

	private String content;

	private boolean enabled = true;

	private boolean published;

	private UUID originSkillUuid;

	private UUID activeVersionUuid;

	private int activeVersionNumber;

	private JsonObject meta;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Skill setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public Skill setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public String getContent() {
		return content;
	}

	@Override
	public Skill setContent(String content) {
		this.content = content;
		return this;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public Skill setEnabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public boolean isPublished() {
		return published;
	}

	@Override
	public Skill setPublished(boolean published) {
		this.published = published;
		return this;
	}

	@Override
	public UUID getOriginSkillUuid() {
		return originSkillUuid;
	}

	@Override
	public Skill setOriginSkillUuid(UUID originSkillUuid) {
		this.originSkillUuid = originSkillUuid;
		return this;
	}

	@Override
	public UUID getActiveVersionUuid() {
		return activeVersionUuid;
	}

	@Override
	public Skill setActiveVersionUuid(UUID activeVersionUuid) {
		this.activeVersionUuid = activeVersionUuid;
		return this;
	}

	@Override
	public int getActiveVersionNumber() {
		return activeVersionNumber;
	}

	@Override
	public Skill setActiveVersionNumber(int activeVersionNumber) {
		this.activeVersionNumber = activeVersionNumber;
		return this;
	}

	@Override
	public JsonObject getMeta() {
		return meta;
	}

	@Override
	public Skill setMeta(JsonObject meta) {
		this.meta = meta;
		return this;
	}

}
