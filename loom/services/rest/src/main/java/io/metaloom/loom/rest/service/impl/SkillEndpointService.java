package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_SKILL;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_SKILL;
import static io.metaloom.loom.db.model.perm.Permission.READ_SKILL;
import static io.metaloom.loom.db.model.perm.Permission.READ_SKILL_VERSION;
import static io.metaloom.loom.db.model.perm.Permission.RESTORE_SKILL_VERSION;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_SKILL;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillDao;
import io.metaloom.loom.db.model.skill.SkillVersion;
import io.metaloom.loom.db.model.skill.SkillVersionDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.skill.SkillCreateRequest;
import io.metaloom.loom.rest.model.skill.SkillListResponse;
import io.metaloom.loom.rest.model.skill.SkillModel;
import io.metaloom.loom.rest.model.skill.SkillResponse;
import io.metaloom.loom.rest.model.skill.SkillUpdateRequest;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Skills are user-specific: every operation is scoped to the skills owned by the calling user. Since loom permissions are global per entity type (e.g.
 * READ_SKILL gates the feature, not individual skills), the per-object ownership checks are enforced here in the service layer. Foreign skills are only
 * reachable through the published-skill library and the install (copy) operation.
 *
 * <p>
 * The versioned body ({@code description} + {@code content}) lives in the {@code skill_version} table. The skill row holds only an
 * {@code active_version_uuid} pointer; the DAO projects the active version's body back onto the {@link Skill} POJO on load. Every edit that changes the body
 * appends a new version; reverting to a version deletes all newer versions and re-points the active version.
 */
@Singleton
public class SkillEndpointService extends AbstractCRUDEndpointService<SkillDao, Skill> {

	private final SkillVersionDao skillVersionDao;

	@Inject
	public SkillEndpointService(SkillDao skillDao, SkillVersionDao skillVersionDao, DaoCollection daos, LoomModelBuilder modelBuilder,
		LoomModelValidator validator) {
		super(skillDao, daos, modelBuilder, validator);
		this.skillVersionDao = skillVersionDao;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		// The skill_version.skill_uuid FK cascades on delete, so removing the skill removes all its versions.
		delete(lrc, DELETE_SKILL, () -> {
			return loadOwned(lrc, uuid);
		});
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_SKILL, () -> {
			PagingParameters paging = lrc.pagingParams();
			Page<Skill> page = dao().findByCreator(lrc.userUuid(), paging.from(), paging.limit(), lrc.filterParams().filters(),
				lrc.sortParams().sortBy(), lrc.sortParams().sortOrder());
			SkillListResponse response = modelBuilder.toSkillList(page);
			List<SkillResponse> data = response.getData();
			if (data != null) {
				int i = 0;
				for (Skill skill : page) {
					flagUpdateAvailable(data.get(i++), skill);
				}
			}
			lrc.send(response);
		});
	}

	/**
	 * List the published skills of all users (the shared skill library).
	 */
	public void listLibrary(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_SKILL, () -> {
			PagingParameters paging = lrc.pagingParams();
			Page<Skill> page = dao().findPublished(paging.from(), paging.limit(), lrc.filterParams().filters(),
				lrc.sortParams().sortBy(), lrc.sortParams().sortOrder());
			lrc.send(modelBuilder.toSkillList(page));
		});
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_SKILL, () -> {
			return loadOwned(lrc, uuid);
		}, skill -> {
			SkillResponse response = modelBuilder.toResponse(skill);
			response.setUpdateAvailable(isUpdateAvailable(skill));
			return response;
		});
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_SKILL, () -> {
			SkillCreateRequest request = lrc.requestBody(SkillCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Skill skill = dao().createSkill(userUuid, request.getName(), request.getDescription(), request.getContent());
			applyToggles(request, skill);
			dao().store(skill);

			// Create the initial version (v1) and point the skill at it.
			appendVersion(userUuid, skill, 1, request.getDescription(), request.getContent());
			dao().update(skill);
			return skill;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_SKILL, () -> {
			SkillUpdateRequest request = lrc.requestBody(SkillUpdateRequest.class);
			validator.validate(request);

			Skill skill = loadOwned(lrc, uuid);
			if (skill == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			}
			UUID userUuid = lrc.userUuid();

			// name / enabled / published / meta are owner-level fields on the skill row (not versioned).
			applyToggles(request, skill);

			// A new version is appended only when the versioned body (description or content) actually changes.
			String newDescription = request.getDescription();
			String newContent = request.getContent();
			boolean bodyChanged = (newDescription != null && !Objects.equals(newDescription, skill.getDescription()))
				|| (newContent != null && !Objects.equals(newContent, skill.getContent()));
			if (bodyChanged) {
				String description = newDescription != null ? newDescription : skill.getDescription();
				String content = newContent != null ? newContent : skill.getContent();
				SkillVersion latest = skillVersionDao.loadLatestBySkill(skill.getUuid());
				int nextVersion = latest != null ? latest.getVersionNumber() + 1 : 1;
				appendVersion(userUuid, skill, nextVersion, description, content);
			}

			setEditor(skill, userUuid);
			return skill;
		}, modelBuilder::toResponse);
	}

	/**
	 * Install a published skill by copying it into the callers own skill set. The copy records the source skill as its origin so that later updates of the
	 * source can be detected. Name collisions with existing own skills are resolved by suffixing the name.
	 */
	public void install(LoomRoutingContext lrc, UUID uuid) {
		create(lrc, CREATE_SKILL, () -> {
			Skill source = dao().load(uuid);
			// Unpublished foreign skills must be indistinguishable from missing ones
			if (source == null || !source.isPublished()) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			}
			UUID userUuid = lrc.userUuid();
			if (source.getCreatorUuid().equals(userUuid)) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "Own skills can't be installed.");
			}

			Skill copy = dao().createSkill(userUuid, freeName(userUuid, source.getName()), source.getDescription(), source.getContent());
			copy.setMeta(source.getMeta());
			copy.setOriginSkillUuid(source.getUuid());
			dao().store(copy);

			// The installed copy starts its own version history at v1.
			appendVersion(userUuid, copy, 1, source.getDescription(), source.getContent());
			dao().update(copy);
			return copy;
		}, modelBuilder::toResponse);
	}

	/**
	 * List the versions of a skill (newest handling is left to the client; the DAO returns them in the requested sort order).
	 */
	public void listVersions(LoomRoutingContext lrc, UUID skillUuid) {
		checkPerm(lrc, READ_SKILL_VERSION, () -> {
			Skill skill = loadOwned(lrc, skillUuid);
			if (skill == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			}
			PagingParameters paging = lrc.pagingParams();
			Page<SkillVersion> page = skillVersionDao.loadPageBySkill(skillUuid, paging.from(), paging.limit(), lrc.filterParams().filters(),
				lrc.sortParams().sortBy(), lrc.sortParams().sortOrder());
			lrc.send(modelBuilder.toSkillVersionList(skill, page));
		});
	}

	/**
	 * Load a specific version of a skill.
	 */
	public void loadVersion(LoomRoutingContext lrc, UUID skillUuid, int versionNumber) {
		checkPerm(lrc, READ_SKILL_VERSION, () -> {
			Skill skill = loadOwned(lrc, skillUuid);
			if (skill == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			}
			SkillVersion version = skillVersionDao.loadBySkillAndVersion(skillUuid, versionNumber);
			if (version == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Skill version not found.");
			}
			lrc.send(modelBuilder.toVersionResponse(skill, version));
		});
	}

	/**
	 * Revert a skill to a specific version. This deletes all versions newer than the target and re-points the skill's active version at the target. No new
	 * version is created.
	 */
	public void restoreVersion(LoomRoutingContext lrc, UUID skillUuid, int versionNumber) {
		checkPerm(lrc, RESTORE_SKILL_VERSION, () -> {
			Skill skill = loadOwned(lrc, skillUuid);
			if (skill == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			}
			SkillVersion target = skillVersionDao.loadBySkillAndVersion(skillUuid, versionNumber);
			if (target == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Skill version not found.");
			}

			UUID userUuid = lrc.userUuid();
			// Re-point the active version to the target first so the following delete never removes the referenced (active) row.
			skill.setActiveVersionUuid(target.getUuid());
			skill.setActiveVersionNumber(target.getVersionNumber());
			skill.setDescription(target.getDescription());
			skill.setContent(target.getContent());
			setEditor(skill, userUuid);
			dao().update(skill);

			// Discard every version newer than the one we reverted to.
			skillVersionDao.deleteVersionsAfter(skillUuid, target.getVersionNumber());

			lrc.send(modelBuilder.toResponse(skill));
		});
	}

	private Skill loadOwned(LoomRoutingContext lrc, UUID uuid) {
		Skill skill = dao().load(uuid);
		// Foreign skills must be indistinguishable from missing ones
		if (skill == null || !skill.getCreatorUuid().equals(lrc.userUuid())) {
			return null;
		}
		return skill;
	}

	/**
	 * Create + store a new version of the given skill and re-point the skill's active-version fields at it (in memory). The caller is responsible for
	 * persisting the skill row.
	 */
	private void appendVersion(UUID userUuid, Skill skill, int versionNumber, String description, String content) {
		SkillVersion version = skillVersionDao.createVersion(userUuid, skill.getUuid(), versionNumber, description, content, skill.getMeta());
		skillVersionDao.store(version);
		skill.setActiveVersionUuid(version.getUuid());
		skill.setActiveVersionNumber(versionNumber);
		skill.setDescription(description);
		skill.setContent(content);
	}

	private String freeName(UUID userUuid, String name) {
		if (dao().loadByName(userUuid, name) == null) {
			return name;
		}
		for (int i = 2;; i++) {
			String candidate = name + "-" + i;
			if (dao().loadByName(userUuid, candidate) == null) {
				return candidate;
			}
		}
	}

	private void flagUpdateAvailable(SkillResponse response, Skill skill) {
		response.setUpdateAvailable(isUpdateAvailable(skill));
	}

	private Boolean isUpdateAvailable(Skill skill) {
		if (skill.getOriginSkillUuid() == null) {
			return null;
		}
		Skill origin = dao().load(skill.getOriginSkillUuid());
		if (origin == null) {
			return false;
		}
		Instant originEdited = origin.getEdited();
		Instant copyCreated = skill.getCreated();
		return originEdited != null && copyCreated != null && originEdited.isAfter(copyCreated);
	}

	/**
	 * Apply the non-versioned, owner-level fields (name, enabled, published, meta) from the request onto the skill row. The versioned body
	 * ({@code description} / {@code content}) is handled separately via {@link #appendVersion}.
	 */
	private void applyToggles(SkillModel<?> model, Skill skill) {
		update(model::getName, skill::setName);
		update(model::getEnabled, skill::setEnabled);
		update(model::getPublished, skill::setPublished);
		update(model::getMeta, skill::setMeta);
	}
}
