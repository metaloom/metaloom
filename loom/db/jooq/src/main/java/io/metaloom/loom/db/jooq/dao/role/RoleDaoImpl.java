package io.metaloom.loom.db.jooq.dao.role;

import static io.metaloom.loom.db.jooq.tables.JooqRole.ROLE;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqRole;
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

}
