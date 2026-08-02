package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_ROLE;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_ROLE;
import static io.metaloom.loom.db.model.perm.Permission.READ_ROLE;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_ROLE;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.auth.PermissionCache;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.role.RoleDao;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.role.RoleCreateRequest;
import io.metaloom.loom.rest.model.role.RolePermission;
import io.metaloom.loom.rest.model.role.RoleUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class RoleEndpointService extends AbstractCRUDEndpointService<RoleDao, Role> {

	private final PermissionCache permissionCache;

	@Inject
	public RoleEndpointService(RoleDao roleDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator,
		PermissionCache permissionCache) {
		super(roleDao, daos, modelBuilder, validator);
		this.permissionCache = permissionCache;
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_ROLE, () -> {
			Role role = dao().load(uuid);
			if (role != null) {
				// role_permission cascades with the role, but the cascade happens after this supplier
				// returns. Revoking explicitly here means the cache drop below cannot be beaten by an
				// authorization refresh that still sees the grants.
				dao().setPermissions(uuid, Collections.emptySet());
				permissionCache.invalidateAll();
			}
			return role;
		});
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
			// The role has to exist before its grants can reference it. Storing it here rather than
			// leaving it to the create() helper is safe - the helper only stores elements which still
			// have no uuid.
			dao().store(role);
			applyPermissions(role.getUuid(), request.getPermissions());
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
			applyPermissions(uuid, request.getPermissions());
			return dao().update(role);
		}, modelBuilder::toResponse);
	}

	/**
	 * Persist the requested permission list to <code>role_permission</code>.
	 *
	 * <p>
	 * The distinction between the two "no permissions" cases is deliberate and part of the endpoint contract:
	 * </p>
	 *
	 * <ul>
	 * <li><b>absent / <code>null</code></b> - the request says nothing about permissions, so the role's existing grants are left untouched. This is
	 * what an update which only renames a role sends.</li>
	 * <li><b><code>[]</code></b> - an explicit empty list revokes every permission the role grants.</li>
	 * </ul>
	 *
	 * <p>
	 * Anything else replaces the role's grants wholesale (see {@link RoleDao#setPermissions(UUID, Set)}); the list is not appended to.
	 * </p>
	 */
	private void applyPermissions(UUID roleUuid, List<RolePermission> permissions) {
		if (permissions == null) {
			return;
		}
		Set<Permission> target = EnumSet.noneOf(Permission.class);
		for (RolePermission perm : permissions) {
			if (perm != null) {
				// Guarded by RolePermissionParityTest: every RolePermission constant has a Permission twin.
				target.add(Permission.valueOf(perm.name()));
			}
		}
		dao().setPermissions(roleUuid, target);
		// The effective-permission cache has no expiry, so a grant that is not followed by an
		// invalidation stays invisible to every session that already authenticated - the write would
		// persist and still change nothing. Roles reach users backwards through role_group and
		// user_group, a direction neither index supports, so the whole cache is dropped.
		permissionCache.invalidateAll();
	}
}
