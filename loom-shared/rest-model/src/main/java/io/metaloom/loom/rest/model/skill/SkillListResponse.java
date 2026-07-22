package io.metaloom.loom.rest.model.skill;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class SkillListResponse extends AbstractListResponse<SkillListResponse, SkillResponse> {

	@Override
	public SkillListResponse self() {
		return this;
	}

}
