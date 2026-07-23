package io.metaloom.loom.rest.model.skill;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

/**
 * Paged list of a skill's versions. Each entry is a {@link SkillResponse} rendered from one historic version — the {@code uuid} identifies the skill,
 * {@code versionUuid} / {@code versionNumber} identify the version and {@code description} / {@code content} carry that version's body.
 */
public class SkillVersionListResponse extends AbstractListResponse<SkillVersionListResponse, SkillResponse> {

	public SkillVersionListResponse() {
	}

	@Override
	public SkillVersionListResponse self() {
		return this;
	}
}
