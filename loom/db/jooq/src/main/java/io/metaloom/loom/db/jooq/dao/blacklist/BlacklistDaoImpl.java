package io.metaloom.loom.db.jooq.dao.blacklist;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqBlacklist;
import io.metaloom.loom.db.model.blacklist.Blacklist;
import io.metaloom.loom.db.model.blacklist.BlacklistDao;
import io.metaloom.filter.Filter;
import io.metaloom.filter.FilterKey;
import io.metaloom.loom.api.filter.LoomFilterKey;
import org.jooq.SelectConditionStep;

@Singleton
public class BlacklistDaoImpl extends AbstractJooqDao<Blacklist> implements BlacklistDao {

	@Inject
	public BlacklistDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Blacklists";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqBlacklist.BLACKLIST;
	}

	@Override
	protected Class<? extends Blacklist> getPojoClass() {
		return BlacklistImpl.class;
	}

	@Override
	public Blacklist createBlacklist(UUID userUuid, UUID binaryUuid, String name) {
		Blacklist list = new BlacklistImpl();
		list.setName(name);
		list.setAssetUuid(binaryUuid);
		setCreatorEditor(list, userUuid);
		return list;
	}

	@Override
	protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
		FilterKey key = filter.filterKey();
		if (key == LoomFilterKey.NAME) {
			return query.and(JooqBlacklist.BLACKLIST.NAME.eq(filter.valueStr()));
		}
		if (key == LoomFilterKey.TYPE) {
			return query.and(JooqBlacklist.BLACKLIST.TYPE.eq(filter.valueStr()));
		}
		return super.applyFilter(query, filter);
	}
}
