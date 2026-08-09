package io.metaloom.loom.db.jooq.dao.library;

import static io.metaloom.loom.db.jooq.tables.JooqLibrary.LIBRARY;
import static io.metaloom.loom.db.jooq.tables.JooqLibraryAsset.LIBRARY_ASSET;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.filter.FilterKey;
import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqLibrary;
import io.metaloom.loom.db.model.library.Library;
import io.metaloom.loom.db.model.library.LibraryDao;
import io.metaloom.loom.db.page.Page;

@Singleton
public class LibraryDaoImpl extends AbstractJooqDao<Library> implements LibraryDao {

	@Inject
	public LibraryDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Libraries";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqLibrary.LIBRARY;
	}

	@Override
	protected Class<? extends Library> getPojoClass() {
		return LibraryImpl.class;
	}

	@Override
	public Library createLibrary(UUID userUuid, String name) {
		Library library = new LibraryImpl();
		library.setName(name);
		setCreatorEditor(library, userUuid);
		return library;
	}

	@Override
	public void linkAsset(UUID libraryUuid, UUID assetUuid) {
		// Idempotent for the same reason as CollectionDaoImpl.linkAsset: library_asset is keyed on
		// (library_uuid, asset_uuid), so a re-link must be a no-op rather than a duplicate-key 500.
		ctx().insertInto(LIBRARY_ASSET,
			LIBRARY_ASSET.LIBRARY_UUID, LIBRARY_ASSET.ASSET_UUID)
			.values(libraryUuid, assetUuid)
			.onConflictDoNothing()
			.execute();
	}

	@Override
	public void unlinkAsset(UUID libraryUuid, UUID assetUuid) {
		ctx().deleteFrom(LIBRARY_ASSET)
			.where(LIBRARY_ASSET.LIBRARY_UUID.eq(libraryUuid)
				.and(LIBRARY_ASSET.ASSET_UUID.eq(assetUuid)))
			.execute();
	}

	@Override
	public boolean containsAsset(UUID libraryUuid, UUID assetUuid) {
		return ctx().fetchExists(ctx()
			.selectOne()
			.from(LIBRARY_ASSET)
			.where(LIBRARY_ASSET.LIBRARY_UUID.eq(libraryUuid)
				.and(LIBRARY_ASSET.ASSET_UUID.eq(assetUuid))));
	}

	@Override
	public long countAssets(UUID libraryUuid) {
		return ctx().fetchCount(LIBRARY_ASSET, LIBRARY_ASSET.LIBRARY_UUID.eq(libraryUuid));
	}

	@Override
	public Page<Library> loadPageByAsset(UUID assetUuid, UUID fromId, int pageSize) {
		SelectConditionStep<?> query = ctx()
			.select(getTable())
			.from(getTable())
			.join(LIBRARY_ASSET).on(LIBRARY_ASSET.LIBRARY_UUID.eq(LIBRARY.UUID))
			.where(LIBRARY_ASSET.ASSET_UUID.eq(assetUuid));

		return loadPage(query, fromId, pageSize, null, null, null);
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.NAME) {
			return query.and(LIBRARY.NAME.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}

}
