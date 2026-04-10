package io.metaloom.loom.db.jooq.dao.pool;

import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqAssetPool;
import io.metaloom.loom.db.model.pool.AssetPool;
import io.metaloom.loom.db.model.pool.AssetPoolDao;

@Singleton
public class AssetPoolDaoImpl extends AbstractJooqDao<AssetPool> implements AssetPoolDao {

	@Inject
	public AssetPoolDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Asset Pools";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqAssetPool.ASSET_POOL;
	}

	@Override
	protected Class<? extends AssetPool> getPojoClass() {
		return AssetPoolImpl.class;
	}

	@Override
	public AssetPool createAssetPool(UUID creatorUuid, String name) {
		Objects.requireNonNull(creatorUuid, "Creator uuid must not be null");
		Objects.requireNonNull(name, "Name must not be null");
		AssetPool pool = new AssetPoolImpl();
		pool.setName(name);
		setCreatorEditor(pool, creatorUuid);
		return pool;
	}

}
