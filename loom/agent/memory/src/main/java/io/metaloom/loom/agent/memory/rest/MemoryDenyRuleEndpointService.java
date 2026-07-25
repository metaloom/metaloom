package io.metaloom.loom.agent.memory.rest;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_MEMORY_DENY_RULE;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_MEMORY_DENY_RULE;
import static io.metaloom.loom.db.model.perm.Permission.READ_MEMORY_DENY_RULE;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_MEMORY_DENY_RULE;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.agent.memory.MemoryDenylist;
import io.metaloom.loom.agent.memory.MemoryException;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.memory.MemoryDenyRule;
import io.metaloom.loom.db.model.memory.MemoryDenyRuleDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.memory.MemoryDenyRuleCreateRequest;
import io.metaloom.loom.rest.model.memory.MemoryDenyRuleModel;
import io.metaloom.loom.rest.model.memory.MemoryDenyRuleUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * Administration of the memory denylist.
 *
 * <p>The list is instance-wide rather than per-user: it exists to stop secrets and forbidden phrases from entering the memory bank at all, so it is
 * deliberately not something the owner of a note can opt out of. Its own {@code *_MEMORY_DENY_RULE} permissions keep it in the admin area, separate from the
 * {@code *_MEMORY} permissions that gate ordinary note access.</p>
 *
 * <p>Patterns are validated on write, so a broken regular expression is rejected here rather than silently skipping at write time.</p>
 */
@Singleton
public class MemoryDenyRuleEndpointService extends AbstractCRUDEndpointService<MemoryDenyRuleDao, MemoryDenyRule> {

	@Inject
	public MemoryDenyRuleEndpointService(MemoryDenyRuleDao dao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(dao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_MEMORY_DENY_RULE, uuid);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_MEMORY_DENY_RULE, modelBuilder::toMemoryDenyRuleList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_MEMORY_DENY_RULE, () -> dao().load(uuid), modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_MEMORY_DENY_RULE, () -> {
			MemoryDenyRuleCreateRequest request = lrc.requestBody(MemoryDenyRuleCreateRequest.class);
			validator.validate(request);

			String name = requireText(request.getName(), "name");
			String pattern = requireText(request.getPattern(), "pattern");
			String message = requireText(request.getMessage(), "message");
			validatePattern(pattern);
			if (dao().loadByName(name) != null) {
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT, "A deny rule with this name already exists.");
			}

			MemoryDenyRule rule = dao().createMemoryDenyRule(lrc.userUuid(), name, pattern, message);
			if (request.getEnabled() != null) {
				rule.setEnabled(request.getEnabled());
			}
			update(request::getMeta, rule::setMeta);
			return rule;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_MEMORY_DENY_RULE, () -> {
			MemoryDenyRuleUpdateRequest request = lrc.requestBody(MemoryDenyRuleUpdateRequest.class);
			validator.validate(request);

			MemoryDenyRule rule = dao().load(uuid);
			if (rule == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			}
			apply(request, rule);
			return rule;
		}, modelBuilder::toResponse);
	}

	private void apply(MemoryDenyRuleModel<?> model, MemoryDenyRule rule) {
		if (model.getPattern() != null) {
			validatePattern(model.getPattern());
		}
		update(model::getName, rule::setName);
		update(model::getPattern, rule::setPattern);
		update(model::getMessage, rule::setMessage);
		update(model::getMeta, rule::setMeta);
		if (model.getEnabled() != null) {
			rule.setEnabled(model.getEnabled());
		}
	}

	/**
	 * Surface an invalid pattern as a 400 with the compiler's own description, so an admin can fix the typo.
	 */
	private void validatePattern(String pattern) {
		try {
			MemoryDenylist.validatePattern(pattern);
		} catch (MemoryException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, e.getMessage());
		}
	}

	private String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The '" + field + "' field must be set.");
		}
		return value.strip();
	}

}
