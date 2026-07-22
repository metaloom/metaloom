package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_SKILL;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_SKILL;
import static io.metaloom.loom.db.model.perm.Permission.READ_SKILL;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_SKILL;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.skill.Skill;
import io.metaloom.loom.db.model.skill.SkillDao;
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
 */
@Singleton
public class SkillEndpointService extends AbstractCRUDEndpointService<SkillDao, Skill> {

	@Inject
	public SkillEndpointService(SkillDao skillDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(skillDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
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
			update(request, skill);
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
			update(request, skill);
			setEditor(skill, lrc.userUuid());
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
			return copy;
		}, modelBuilder::toResponse);
	}

	private Skill loadOwned(LoomRoutingContext lrc, UUID uuid) {
		Skill skill = dao().load(uuid);
		// Foreign skills must be indistinguishable from missing ones
		if (skill == null || !skill.getCreatorUuid().equals(lrc.userUuid())) {
			return null;
		}
		return skill;
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

	private void update(SkillModel<?> model, Skill skill) {
		update(model::getName, skill::setName);
		update(model::getDescription, skill::setDescription);
		update(model::getContent, skill::setContent);
		update(model::getEnabled, skill::setEnabled);
		update(model::getPublished, skill::setPublished);
		update(model::getMeta, skill::setMeta);
	}
}
