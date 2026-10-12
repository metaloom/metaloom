package io.metaloom.loom.db.jooq.dao.vectorconfig;

import static io.metaloom.loom.db.jooq.tables.JooqVectorConfig.VECTOR_CONFIG;

import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.model.vectorconfig.VectorConfig;
import io.metaloom.loom.db.model.vectorconfig.VectorConfigDao;

/**
 * Persistence for {@code vector_config}. Only possible since V2.119 gave the table a primary key - before it, jOOQ had nothing to update or delete by.
 */
@Singleton
public class VectorConfigDaoImpl extends AbstractJooqDao<VectorConfig> implements VectorConfigDao {

	@Inject
	public VectorConfigDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Vector Configs";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return VECTOR_CONFIG;
	}

	@Override
	protected Class<? extends VectorConfig> getPojoClass() {
		return VectorConfigImpl.class;
	}

	@Override
	public VectorConfig createVectorConfig(UUID creatorUuid, String name) {
		Objects.requireNonNull(creatorUuid, "Creator uuid must not be null");
		Objects.requireNonNull(name, "Name must not be null");
		VectorConfig config = new VectorConfigImpl();
		config.setName(name);
		setCreatorEditor(config, creatorUuid);
		return config;
	}

	@Override
	public VectorConfig loadByName(String name) {
		return ctx().selectFrom(VECTOR_CONFIG)
			.where(VECTOR_CONFIG.NAME.eq(name))
			.fetchOneInto(getPojoClass());
	}

}
