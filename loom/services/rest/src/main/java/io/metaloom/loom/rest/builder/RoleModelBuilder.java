package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.role.RoleListResponse;
import io.metaloom.loom.rest.model.role.RoleResponse;

public interface RoleModelBuilder extends ModelBuilder, UserModelBuilder {

	default RoleResponse toResponse(Role role) {
		RoleResponse response = new RoleResponse();
		response.setUuid(role.getUuid());
		response.setName(role.getName());
		setStatus(role, response);
		return response;
	}

	default RoleListResponse toRoleList(Page<Role> page) {
		return setPage(new RoleListResponse(), page, this::toResponse);
	}
}
