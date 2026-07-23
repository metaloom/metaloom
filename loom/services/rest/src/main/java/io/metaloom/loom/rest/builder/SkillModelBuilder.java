package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillVersion;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.skill.SkillListResponse;
import io.metaloom.loom.rest.model.skill.SkillResponse;
import io.metaloom.loom.rest.model.skill.SkillVersionListResponse;

public interface SkillModelBuilder extends ModelBuilder, UserModelBuilder {

	default SkillResponse toResponse(Skill skill) {
		SkillResponse response = new SkillResponse();
		response.setUuid(skill.getUuid());
		response.setName(skill.getName());
		response.setDescription(skill.getDescription());
		response.setContent(skill.getContent());
		response.setEnabled(skill.isEnabled());
		response.setPublished(skill.isPublished());
		response.setOriginSkillUuid(skill.getOriginSkillUuid());
		response.setVersionUuid(skill.getActiveVersionUuid());
		response.setVersionNumber(skill.getActiveVersionNumber());
		response.setMeta(skill.getMeta());
		setStatus(skill, response);
		return response;
	}

	default SkillListResponse toSkillList(Page<Skill> page) {
		return setPage(new SkillListResponse(), page, this::toResponse);
	}

	/**
	 * Render a single historic version of a skill. The {@code uuid} identifies the skill and the {@code name} is taken from the (non-versioned) skill; the
	 * body ({@code description} / {@code content}) and {@code versionUuid} / {@code versionNumber} come from the version. The creator/editor status is taken
	 * from the version, since that is who authored this revision.
	 */
	default SkillResponse toVersionResponse(Skill skill, SkillVersion version) {
		SkillResponse response = new SkillResponse();
		response.setUuid(skill.getUuid());
		response.setName(skill.getName());
		response.setDescription(version.getDescription());
		response.setContent(version.getContent());
		response.setEnabled(skill.isEnabled());
		response.setPublished(skill.isPublished());
		response.setOriginSkillUuid(skill.getOriginSkillUuid());
		response.setVersionUuid(version.getUuid());
		response.setVersionNumber(version.getVersionNumber());
		response.setMeta(version.getMeta());
		setStatus(version, response);
		return response;
	}

	default SkillVersionListResponse toSkillVersionList(Skill skill, Page<SkillVersion> page) {
		return setPage(new SkillVersionListResponse(), page, version -> toVersionResponse(skill, version));
	}
}
