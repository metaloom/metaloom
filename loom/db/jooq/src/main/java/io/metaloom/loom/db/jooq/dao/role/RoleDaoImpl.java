package io.metaloom.loom.db.jooq.dao.role;

import static io.metaloom.loom.db.jooq.tables.JooqRole.ROLE;
import static io.metaloom.loom.db.jooq.tables.JooqRolePermission.ROLE_PERMISSION;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.enums.JooqLoomPermission;
import io.metaloom.loom.db.jooq.tables.JooqRole;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.role.RoleDao;

@Singleton
public class RoleDaoImpl extends AbstractJooqDao<Role> implements RoleDao {

	@Inject
	public RoleDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Roles";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqRole.ROLE;
	}

	@Override
	protected Class<? extends Role> getPojoClass() {
		return RoleImpl.class;
	}

	@Override
	public Role createRole(UUID creatorUuid, String name) {
		Role role = new RoleImpl();
		role.setName(name);
		setCreatorEditor(role, creatorUuid);
		return role;
	}

	@Override
	public Role loadByName(String name) {
		return ctx().selectFrom(ROLE)
			.where(ROLE.NAME.equal(name))
			.fetchOneInto(Role.class);
	}

	@Override
	public Set<Permission> loadPermissions(UUID roleUuid) {
		Objects.requireNonNull(roleUuid, "The role uuid must be provided");
		Set<Permission> permissions = EnumSet.noneOf(Permission.class);
		for (JooqLoomPermission perm : ctx().select(ROLE_PERMISSION.PERMISSION)
			.from(ROLE_PERMISSION)
			.where(ROLE_PERMISSION.ROLE_UUID.eq(roleUuid))
			.fetch(ROLE_PERMISSION.PERMISSION)) {
			// The Postgres loom_permission type carries a few values which have no Java constant
			// (e.g. CREATE_PIPELINE_VERSION). Such a row cannot have been written through this DAO
			// and cannot be checked either, so it is skipped rather than blowing up the load.
			Permission mapped = toPermission(perm);
			if (mapped != null) {
				permissions.add(mapped);
			}
		}
		return permissions;
	}

	@Override
	public void setPermissions(UUID roleUuid, Set<Permission> permissions) {
		Objects.requireNonNull(roleUuid, "The role uuid must be provided");
		Objects.requireNonNull(permissions, "The permission set must be provided - pass an empty set to revoke all permissions");

		// Copy defensively: the caller's set must not be able to change under the transaction.
		Set<Permission> target = permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);

		ctx().transaction(cfg -> {
			DSLContext tx = cfg.dsl();

			// Revoke everything which is no longer in the target set. Deleting only the surplus rows
			// (rather than all of them) keeps grants which survive the edit untouched, so a concurrent
			// permission check never observes a role that momentarily grants nothing.
			if (target.isEmpty()) {
				tx.deleteFrom(ROLE_PERMISSION)
					.where(ROLE_PERMISSION.ROLE_UUID.eq(roleUuid))
					.execute();
			} else {
				Set<JooqLoomPermission> keep = new LinkedHashSet<>();
				for (Permission perm : target) {
					keep.add(JooqLoomPermission.valueOf(perm.name()));
				}
				tx.deleteFrom(ROLE_PERMISSION)
					.where(ROLE_PERMISSION.ROLE_UUID.eq(roleUuid))
					.and(ROLE_PERMISSION.PERMISSION.notIn(keep))
					.execute();

				// Grant the ones which are missing. onConflict makes the insert idempotent against the
				// rows which were just kept.
				for (Permission perm : target) {
					tx.insertInto(ROLE_PERMISSION, ROLE_PERMISSION.ROLE_UUID, ROLE_PERMISSION.PERMISSION)
						.values(roleUuid, JooqLoomPermission.valueOf(perm.name()))
						.onConflict(ROLE_PERMISSION.ROLE_UUID, ROLE_PERMISSION.PERMISSION)
						.doNothing()
						.execute();
				}
			}
		});
	}

	private Permission toPermission(JooqLoomPermission perm) {
		if (perm == null) {
			return null;
		}
		try {
			return Permission.valueOf(perm.name());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

}
