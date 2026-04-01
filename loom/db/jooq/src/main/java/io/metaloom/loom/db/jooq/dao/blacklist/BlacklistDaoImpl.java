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

}
