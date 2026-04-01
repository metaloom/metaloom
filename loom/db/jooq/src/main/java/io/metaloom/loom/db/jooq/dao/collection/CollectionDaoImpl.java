package io.metaloom.loom.db.jooq.dao.collection;

import static io.metaloom.loom.db.jooq.tables.JooqCollectionAsset.COLLECTION_ASSET;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqCollection;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.collection.CollectionDao;

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
		ctx().insertInto(COLLECTION_ASSET,
			COLLECTION_ASSET.COLLECTION_UUID, COLLECTION_ASSET.ASSET_UUID)
			.values(collectionUuid, assetUuid)
			.execute();
	}

	@Override
	public void unlinkAsset(UUID collectionUuid, UUID assetUuid) {
		ctx().deleteFrom(COLLECTION_ASSET)
			.where(COLLECTION_ASSET.COLLECTION_UUID.eq(collectionUuid)
				.and(COLLECTION_ASSET.ASSET_UUID.eq(assetUuid)))
			.execute();
	}

}
