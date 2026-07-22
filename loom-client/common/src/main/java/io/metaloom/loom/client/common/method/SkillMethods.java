package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.skill.SkillCreateRequest;
import io.metaloom.loom.rest.model.skill.SkillListResponse;
import io.metaloom.loom.rest.model.skill.SkillResponse;
import io.metaloom.loom.rest.model.skill.SkillUpdateRequest;

public interface SkillMethods {

	LoomClientRequest<SkillResponse> loadSkill(UUID skillUuid);

	LoomClientRequest<SkillResponse> createSkill(SkillCreateRequest request);

	LoomClientRequest<SkillResponse> updateSkill(UUID skillUuid, SkillUpdateRequest request);

	LoomClientRequest<SkillListResponse> listSkills();

	LoomClientRequest<NoResponse> deleteSkill(UUID skillUuid);

	/**
	 * List the published skills of all users (the shared skill library).
	 */
	LoomClientRequest<SkillListResponse> listSkillLibrary();

	/**
	 * Install the published skill with the given uuid by copying it into the callers own skill set.
	 */
	LoomClientRequest<SkillResponse> installSkill(UUID skillUuid);

}
