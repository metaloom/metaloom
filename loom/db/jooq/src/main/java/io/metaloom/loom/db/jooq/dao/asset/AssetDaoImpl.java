package io.metaloom.loom.db.jooq.dao.asset;

import static io.metaloom.loom.db.jooq.tables.JooqAsset.ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqCollectionAsset.COLLECTION_ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqLibraryAsset.LIBRARY_ASSET;

import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.filter.FilterKey;
import io.metaloom.filter.Operation;
import io.metaloom.filter.value.impl.range.SizeRangeFilterValue;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.api.sort.LoomSortKey;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqAsset;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.asset.AssetDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

@Singleton
public class AssetDaoImpl extends AbstractJooqDao<Asset> implements AssetDao {

	@Inject
	public AssetDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Assets";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqAsset.ASSET;
	}

	@Override
	protected Class<? extends Asset> getPojoClass() {
		return AssetImpl.class;
	}

	@Override
	public Asset createAsset(UUID userUuid, SHA512 sha512sum, String mimeType, String filename, String initialOrigin, long size) {
		Objects.requireNonNull(sha512sum, "Binary sha512sum must not be null");
		Asset asset = new AssetImpl();
		asset.setSHA512(sha512sum);
		asset.setSize(size);
		asset.setMimeType(mimeType);
		asset.setFilename(filename);
		asset.setInitialOrigin(initialOrigin);
		setCreatorEditor(asset, userUuid);
		return asset;
	}

	@Override
	public Asset loadById(AssetId assetId) {
		if (assetId.isUUID()) {
			return load(assetId.uuid());
		} else {
			return loadBySHA512(assetId.hashsum());
		}	
	}

	@Override
	public Asset load(UUID uuid) {
		return ctx()
			.select(getTable())
			.from(getTable())
			.where(JooqAsset.ASSET.UUID.eq(uuid))
			.fetchOneInto(getPojoClass());
	}

	@Override
	public Asset loadBySHA512(SHA512 sha512sum) {
		return ctx()
			.select(getTable())
			.from(getTable())
			.where(JooqAsset.ASSET.SHA512SUM.eq(sha512sum.toString()))
			.fetchOneInto(getPojoClass());
	}

	@Override
	public void deleteBySHA512(SHA512 sha512sum) {
		ctx().delete(getTable())
			.where(ASSET.SHA512SUM.eq(sha512sum.toString()))
			.execute();
	}

	@Override
	public void delete(UUID id) {
		ctx().delete(getTable())
			.where(ASSET.UUID.eq(id))
			.execute();
	}

	@Override
	public void storeUserMeta(User user, Asset asset, JsonObject meta) {
		// TODO Auto-generated method stub

	}

	@Override
	public void deleteUserMeta(User user, Asset asset) {
		// TODO Auto-generated method stub

	}

	@Override
	public JsonObject readUserMeta(User user, Asset asset) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Page<Asset> loadPageByCollection(UUID collectionUuid, UUID fromId, int pageSize) {
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.join(COLLECTION_ASSET).on(COLLECTION_ASSET.ASSET_UUID.eq(ASSET.UUID))
			.where(COLLECTION_ASSET.COLLECTION_UUID.eq(collectionUuid));

		return loadPage(query, fromId, pageSize, null, null, null);
	}

	@Override
	public Page<Asset> loadPageByLibrary(UUID libraryUuid, UUID fromId, int pageSize) {
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.join(LIBRARY_ASSET).on(LIBRARY_ASSET.ASSET_UUID.eq(ASSET.UUID))
			.where(LIBRARY_ASSET.LIBRARY_UUID.eq(libraryUuid));

		return loadPage(query, fromId, pageSize, null, null, null);
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		if (filter.matches(LoomFilterKey.FILE_SIZE, Operation.RANGE)) {
			return filter.apply(SizeRangeFilterValue.class, sv -> {
				return query.and(ASSET.SIZE.ge(sv.getFrom())).and(ASSET.SIZE.le(sv.getTo()));
			});
		}
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.NAME) {
			return query.and(ASSET.FILENAME.eq(filter.valueStr()));
		}
		if (key == LoomFilterKey.COLLECTION) {
			// Membership lives in the collection_asset join table, so this is a semi-join rather
			// than a column comparison. IN (subquery) rather than an actual join: joining would
			// multiply the asset row by its memberships, and the count taken in loadPage would
			// then report matches instead of assets.
			return query.and(ASSET.UUID.in(
				ctx().select(COLLECTION_ASSET.ASSET_UUID)
					.from(COLLECTION_ASSET)
					.where(COLLECTION_ASSET.COLLECTION_UUID.eq(parseUuid(filter.valueStr(), key)))));
		}
		return super.applyFilter(query, filter);
	}

	/**
	 * An asset's display name is {@code filename}; the table has no {@code name} column.
	 *
	 * <p>
	 * Mapping the key rather than making callers ask for {@code ?sort=filename} keeps one spelling across every list route — the UI's sort control
	 * offers the same three options everywhere and does not need to know which type it is looking at.
	 * </p>
	 */
	@Override
	protected Field<?> getSortField(SortKey sortBy) {
		if (sortBy == LoomSortKey.NAME) {
			return ASSET.FILENAME;
		}
		return super.getSortField(sortBy);
	}

}
