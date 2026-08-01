package io.metaloom.loom.db.jooq.dao.asset.binary;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqAssetLocation;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.asset.AssetBinaryDao;

@Singleton
public class AssetBinaryDaoImpl extends AbstractJooqDao<AssetBinary> implements AssetBinaryDao {

	@Inject
	public AssetBinaryDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Asset Locations";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqAssetLocation.ASSET_LOCATION;
	}

	@Override
	protected Class<? extends AssetBinary> getPojoClass() {
		return AssetBinaryImpl.class;
	}

	@Override
	public AssetBinary createAssetBinary(String filename, UUID assetUuid, UUID creatorUuid, UUID libraryUuid) {
		Objects.requireNonNull(creatorUuid, "Creator uuid must not be null");
		Objects.requireNonNull(libraryUuid, "Library uuid must not be null");
		Objects.requireNonNull(assetUuid, "Binary uuid must not be null");
		AssetBinary location = new AssetBinaryImpl();
		location.setPath(filename);
		setCreatorEditor(location, creatorUuid);
		location.setAssetUuid(assetUuid);
		location.setLibraryUuid(libraryUuid);
		return location;
	}

	@Override
	public AssetBinary loadPrimaryByAssetUuid(UUID assetUuid) {
		// Ordered + limited rather than fetchOne. An asset legitimately has several locations since
		// V2.48 relaxed the unique constraint to (library_uuid, path), and fetchOne answered that with
		// a TooManyRowsException, i.e. an HTTP 500, on every read path: GET /assets/:uuid/binary,
		// GET .../binary/data, the upload replace check and asset-scoped run dispatch. Oldest-first
		// with a uuid tie-break so the "primary" is stable rather than whatever the planner returned.
		return ctx()
			.select(getTable())
			.from(getTable())
			.where(JooqAssetLocation.ASSET_LOCATION.ASSET_UUID.eq(assetUuid))
			.orderBy(JooqAssetLocation.ASSET_LOCATION.CREATED.asc(), JooqAssetLocation.ASSET_LOCATION.UUID.asc())
			.limit(1)
			.fetchOneInto(getPojoClass());
	}

	@Override
	public List<AssetBinary> loadAllByAssetUuid(UUID assetUuid) {
		return ctx()
			.select(getTable())
			.from(getTable())
			.where(JooqAssetLocation.ASSET_LOCATION.ASSET_UUID.eq(assetUuid))
			.orderBy(JooqAssetLocation.ASSET_LOCATION.CREATED.asc(), JooqAssetLocation.ASSET_LOCATION.UUID.asc())
			.fetchInto(getPojoClass())
			.stream()
			.map(AssetBinary.class::cast)
			.collect(Collectors.toList());
	}

	@Override
	public AssetBinary loadByAssetAndLibrary(UUID assetUuid, UUID libraryUuid) {
		return ctx()
			.select(getTable())
			.from(getTable())
			.where(JooqAssetLocation.ASSET_LOCATION.ASSET_UUID.eq(assetUuid))
			.and(JooqAssetLocation.ASSET_LOCATION.LIBRARY_UUID.eq(libraryUuid))
			.orderBy(JooqAssetLocation.ASSET_LOCATION.CREATED.asc(), JooqAssetLocation.ASSET_LOCATION.UUID.asc())
			.limit(1)
			.fetchOneInto(getPojoClass());
	}

	@Override
	public long countByPoolAndPath(UUID poolUuid, String path) {
		Condition poolCondition = poolUuid == null
			? JooqAssetLocation.ASSET_LOCATION.POOL_UUID.isNull()
			: JooqAssetLocation.ASSET_LOCATION.POOL_UUID.eq(poolUuid);
		return ctx()
			.selectCount()
			.from(getTable())
			.where(JooqAssetLocation.ASSET_LOCATION.PATH.eq(path))
			.and(poolCondition)
			.fetchOne(0, long.class);
	}

	@Override
	public void deleteByAssetUuid(UUID assetUuid) {
		ctx()
			.deleteFrom(getTable())
			.where(JooqAssetLocation.ASSET_LOCATION.ASSET_UUID.eq(assetUuid))
			.execute();
	}

	// @Override
	// public Completable deleteAsset(LoomAsset asset) {
	// Objects.requireNonNull(asset, "Asset must not be null");
	// return deleteById(asset.getUuid()).ignoreElement();
	// }
	//
	// @Override
	// public Single<? extends LoomAsset> createAsset() {
	// Asset asset = new Asset();
	// insert(asset);
	// return insertReturningPrimary(asset).map(pk -> new LoomAssetImpl(asset.setUuid(pk)));
	// }
	//
	// @Override
	// public Completable updateAsset(LoomAsset asset) {
	// Objects.requireNonNull(asset, "Asset must not be null");
	// Asset jooqAsset = unwrap(asset);
	// return update(jooqAsset).ignoreElement();
	// }

}
