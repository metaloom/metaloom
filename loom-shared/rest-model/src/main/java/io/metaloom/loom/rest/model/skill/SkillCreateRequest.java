package io.metaloom.loom.rest.model.skill;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class SkillCreateRequest extends AbstractMetaModel<SkillCreateRequest> implements RestRequestModel, SkillModel<SkillCreateRequest> {

	private String name;

	private String description;

	private String content;

	private Boolean enabled;

	private Boolean published;

	@Override
	public String getName() {
		return name;
	}

	@Override
	public SkillCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public SkillCreateRequest setDescription(String description) {
		this.description = description;
		return this;
	}

	@Override
	public String getContent() {
		return content;
	}

	@Override
	public SkillCreateRequest setContent(String content) {
		this.content = content;
		return this;
	}

	@Override
	public Boolean getEnabled() {
		return enabled;
	}

	@Override
	public SkillCreateRequest setEnabled(Boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	@Override
	public Boolean getPublished() {
		return published;
	}

	@Override
	public SkillCreateRequest setPublished(Boolean published) {
		this.published = published;
		return this;
	}

	@Override
	public SkillCreateRequest self() {
		return this;
	}
}
