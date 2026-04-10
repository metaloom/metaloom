package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.space.Space;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.space.SpaceListResponse;
import io.metaloom.loom.rest.model.space.SpaceResponse;

public interface SpaceModelBuilder extends ModelBuilder, UserModelBuilder {

	default SpaceResponse toResponse(Space space) {
		SpaceResponse response = new SpaceResponse();
		response.setUuid(space.getUuid());
		response.setName(space.getName());
		setStatus(space, response);
		return response;
	}

	default SpaceListResponse toSpaceList(Page<Space> page) {
		return setPage(new SpaceListResponse(), page, this::toResponse);
	}

}
