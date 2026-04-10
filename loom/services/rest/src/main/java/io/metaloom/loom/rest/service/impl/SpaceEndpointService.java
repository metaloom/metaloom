package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_SPACE;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_SPACE;
import static io.metaloom.loom.db.model.perm.Permission.READ_SPACE;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_SPACE;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.space.Space;
import io.metaloom.loom.db.model.space.SpaceDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.space.SpaceCreateRequest;
import io.metaloom.loom.rest.model.space.SpaceUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class SpaceEndpointService extends AbstractCRUDEndpointService<SpaceDao, Space> {

	@Inject
	public SpaceEndpointService(SpaceDao spaceDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(spaceDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID id) {
		delete(lrc, DELETE_SPACE, id);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_SPACE, modelBuilder::toSpaceList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID id) {
		load(lrc, READ_SPACE, () -> {
			return dao().load(id);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_SPACE, () -> {
			SpaceCreateRequest request = lrc.requestBody(SpaceCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String name = request.getName();
			Space space = dao().createSpace(userUuid, name);
			update(request::getMeta, space::setMeta);
			return space;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID id) {
		update(lrc, UPDATE_SPACE, () -> {
			SpaceUpdateRequest request = lrc.requestBody(SpaceUpdateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			Space space = dao().load(id);
			// TOOD update
			update(request::getName, space::setName);
			setEditor(space, userUuid);
			return space;
		}, modelBuilder::toResponse);
	}

}
