package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.skill.SkillListResponse;
import io.metaloom.loom.rest.model.skill.SkillResponse;

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
		response.setMeta(skill.getMeta());
		setStatus(skill, response);
		return response;
	}

	default SkillListResponse toSkillList(Page<Skill> page) {
		return setPage(new SkillListResponse(), page, this::toResponse);
	}
}
