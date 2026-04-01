package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.role.RoleCreateRequest;
import io.metaloom.loom.rest.model.role.RoleListResponse;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.model.role.RoleUpdateRequest;

public interface RoleMethods {

	LoomClientRequest<RoleResponse> loadRole(UUID roleUuid);

	LoomClientRequest<RoleResponse> createRole(RoleCreateRequest request);

	LoomClientRequest<RoleResponse> updateRole(UUID roleUuid, RoleUpdateRequest request);

	LoomClientRequest<RoleListResponse> listRoles();

	LoomClientRequest<NoResponse> deleteRole(UUID roleUuid);
}
