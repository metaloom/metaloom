package io.metaloom.loom.db.jooq.dao.library;

import static io.metaloom.loom.db.jooq.tables.JooqLibrary.LIBRARY;

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
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.NAME) {
			return query.and(LIBRARY.NAME.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}

}
