package io.metaloom.loom.rest.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.role.RoleListResponse;
import io.metaloom.loom.rest.model.role.RolePermission;
import io.metaloom.loom.rest.model.role.RoleResponse;

public interface RoleModelBuilder extends ModelBuilder, UserModelBuilder {

	default RoleResponse toResponse(Role role) {
		RoleResponse response = new RoleResponse();
		response.setUuid(role.getUuid());
		response.setName(role.getName());
		response.setPermissions(toPermissions(role.getUuid()));
		setStatus(role, response);
		return response;
	}

	default RoleListResponse toRoleList(Page<Role> page) {
		return setPage(new RoleListResponse(), page, this::toResponse);
	}

	/**
	 * Load the role's grants from <code>role_permission</code> and map them onto the REST enum.
	 *
	 * <p>
	 * A role without grants yields an empty list rather than <code>null</code>, so a client can tell "this role grants nothing" from "the server did
	 * not tell me". The list is sorted by name to keep the response stable across calls - the grants come out of an {@code EnumSet}, whose order is
	 * declaration order, and reordering the enum would otherwise reorder every response.
	 * </p>
	 */
	private List<RolePermission> toPermissions(UUID roleUuid) {
		List<RolePermission> permissions = new ArrayList<>();
		if (roleUuid == null) {
			return permissions;
		}
		Set<Permission> granted = daos().roleDao().loadPermissions(roleUuid);
		if (granted == null) {
			return permissions;
		}
		for (Permission perm : granted) {
			// Guarded by RolePermissionParityTest: every Permission constant has a RolePermission twin.
			permissions.add(RolePermission.valueOf(perm.name()));
		}
		permissions.sort(Comparator.comparing(Enum::name));
		return permissions;
	}
}
