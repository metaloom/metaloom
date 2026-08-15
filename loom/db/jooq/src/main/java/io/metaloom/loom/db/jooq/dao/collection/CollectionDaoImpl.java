package io.metaloom.loom.db.jooq.dao.collection;

import static io.metaloom.loom.db.jooq.tables.JooqCollectionAsset.COLLECTION_ASSET;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqCollection;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;
import io.metaloom.loom.db.page.Page;

@Singleton
public class CollectionDaoImpl extends AbstractJooqDao<Collection> implements CollectionDao {

	@Inject
	public CollectionDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Collections";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqCollection.COLLECTION;
	}

	@Override
	protected Class<? extends Collection> getPojoClass() {
		return CollectionImpl.class;
	}

	@Override
	public Collection createCollection(UUID userUuid, String name) {
		Collection collection = new CollectionImpl();
		collection.setName(name);
		setCreatorEditor(collection, userUuid);
		return collection;
	}

	@Override
	public void linkAsset(UUID collectionUuid, UUID assetUuid) {
		// onConflictDoNothing, because collection_asset is keyed on (collection_uuid, asset_uuid): a plain insert made a
		// second link throw a duplicate-key error, which surfaced as a 500. Re-assigning a corpus that is already curated
		// is the normal case for a pipeline, so membership is idempotent by construction rather than by a caller's check.
		ctx().insertInto(COLLECTION_ASSET,
			COLLECTION_ASSET.COLLECTION_UUID, COLLECTION_ASSET.ASSET_UUID)
			.values(collectionUuid, assetUuid)
			.onConflictDoNothing()
			.execute();
	}

	@Override
	public void unlinkAsset(UUID collectionUuid, UUID assetUuid) {
		ctx().deleteFrom(COLLECTION_ASSET)
			.where(COLLECTION_ASSET.COLLECTION_UUID.eq(collectionUuid)
				.and(COLLECTION_ASSET.ASSET_UUID.eq(assetUuid)))
			.execute();
	}

	@Override
	public boolean containsAsset(UUID collectionUuid, UUID assetUuid) {
		return ctx().fetchExists(ctx()
			.selectOne()
			.from(COLLECTION_ASSET)
			.where(COLLECTION_ASSET.COLLECTION_UUID.eq(collectionUuid)
				.and(COLLECTION_ASSET.ASSET_UUID.eq(assetUuid))));
	}

	@Override
	public long countAssets(UUID collectionUuid) {
		return ctx().fetchCount(COLLECTION_ASSET, COLLECTION_ASSET.COLLECTION_UUID.eq(collectionUuid));
	}

	@Override
	public Page<Collection> loadPageByAsset(UUID assetUuid, UUID fromId, int pageSize) {
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.join(COLLECTION_ASSET).on(COLLECTION_ASSET.COLLECTION_UUID.eq(JooqCollection.COLLECTION.UUID))
			.where(COLLECTION_ASSET.ASSET_UUID.eq(assetUuid));

		return loadPage(query, fromId, pageSize, null, null, null);
	}

	/**
	 * Exact-match name filter. Substring search over collections stays a client concern — the LHS filter grammar has no {@code contains} operation,
	 * and a listing route is not the place to grow one when {@code /search/results} already ranks by relevance.
	 *
	 * <p>
	 * Filtering by creator needs nothing here: {@code collection} carries the {@code creator_uuid} audit column, so {@code AbstractJooqDao} handles
	 * {@code creator[eq]=} for it.
	 * </p>
	 */
	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		if (filter.filterKey() == LoomFilterKey.NAME) {
			return query.and(JooqCollection.COLLECTION.NAME.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}

}
