package io.metaloom.loom.rest.model.skill;

import java.util.UUID;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class SkillResponse extends AbstractCreatorEditorRestResponse<SkillResponse> implements SkillModel<SkillResponse> {

	private String name;

	private String description;

	private String content;

	private Boolean enabled;

	private Boolean published;

	private UUID originSkillUuid;

	private Boolean updateAvailable;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public SkillResponse setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public SkillResponse setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public String getContent() {
		return content;
	}

	@Override
	public SkillResponse setContent(String content) {
		this.content = content;
		return this;
	}

	@Override
	public Boolean getEnabled() {
		return enabled;
	}

	@Override
	public SkillResponse setEnabled(Boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public Boolean getPublished() {
		return published;
	}

	@Override
	public SkillResponse setPublished(Boolean published) {
		this.published = published;
		return this;
	}

	public UUID getOriginSkillUuid() {
		return originSkillUuid;
	}

	public SkillResponse setOriginSkillUuid(UUID originSkillUuid) {
		this.originSkillUuid = originSkillUuid;
		return this;
	}

	/**
	 * Whether the origin skill of this installed copy has been edited since the copy was installed.
	 */
	public Boolean getUpdateAvailable() {
		return updateAvailable;
	}

	public SkillResponse setUpdateAvailable(Boolean updateAvailable) {
		this.updateAvailable = updateAvailable;
		return this;
	}

	@Override
	public SkillResponse self() {
		return this;
	}
}
