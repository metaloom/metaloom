package io.metaloom.loom.db.jooq.dao.space;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqProject;
import io.metaloom.loom.db.model.space.Space;
import io.metaloom.loom.db.model.space.SpaceDao;

@Singleton
public class SpaceDaoImpl extends AbstractJooqDao<Space> implements SpaceDao {

	@Inject
	public SpaceDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Projects";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqProject.PROJECT;
	}

	@Override
	protected Class<? extends Space> getPojoClass() {
		return SpaceImpl.class;
	}

	@Override
	public Space createSpace(UUID userUuid, String name) {
		Space space = new SpaceImpl();
		space.setName(name);
		setCreatorEditor(space, userUuid);
		return space;
	}

}
