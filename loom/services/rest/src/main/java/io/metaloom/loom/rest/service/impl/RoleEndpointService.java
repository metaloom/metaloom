package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ROLE;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ROLE;
import static io.metaloom.loom.db.model.perm.Permission.READ_ROLE;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ROLE;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.role.RoleCreateRequest;
import io.metaloom.loom.rest.model.role.RoleUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class RoleEndpointService extends AbstractCRUDEndpointService<RoleDao, Role> {

	@Inject
	public RoleEndpointService(RoleDao roleDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(roleDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_ROLE, uuid);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_ROLE, modelBuilder::toRoleList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_ROLE, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_ROLE, () -> {
			RoleCreateRequest request = lrc.requestBody(RoleCreateRequest.class);
			validator.validate(request);

			String name = request.getName();
			UUID userUuid = lrc.userUuid();
			Role role = dao().createRole(userUuid, name);
			return role;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_ROLE, () -> {
			RoleUpdateRequest request = lrc.requestBody(RoleUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Role role = dao().load(uuid);
			update(request::getName, role::setName);
			update(request::getMeta, role::setMeta);
			setEditor(role, userUuid);
			return dao().update(role);
		}, modelBuilder::toResponse);
	}
}
