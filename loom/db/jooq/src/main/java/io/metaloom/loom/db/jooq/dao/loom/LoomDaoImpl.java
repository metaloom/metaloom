package io.metaloom.loom.db.jooq.dao.loom;

import static io.metaloom.loom.db.jooq.tables.JooqLoom.LOOM;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;

import io.metaloom.loom.db.jooq.JooqDao;
import io.metaloom.loom.db.jooq.tables.records.JooqLoomRecord;
import io.metaloom.loom.db.model.loom.Loom;
import io.metaloom.loom.db.model.loom.LoomDao;

@Singleton
public class LoomDaoImpl implements JooqDao, LoomDao {

	private final DSLContext ctx;

	@Inject
	public LoomDaoImpl(DSLContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public String getTypeName() {
		return "Loom";
	}

	@Override
	public Loom load() {
		JooqLoomRecord record = ctx.selectFrom(LOOM)
			.limit(1)
			.fetchOne();
		return toLoom(record);
	}

	@Override
	public Loom createLoom(String databaseRevision) {
		Instant now = Instant.now();
		ctx.insertInto(LOOM)
			.set(LOOM.DB_REV, databaseRevision)
			.set(LOOM.LAST_USED_TIMESTAMP, toLocalDateTime(now))
			.execute();
		return new LoomImpl()
			.setDatabaseRevision(databaseRevision)
			.setLastUsedTimestamp(now);
	}

	@Override
	public void update(Loom loom) {
		// The loom table holds a single row - update it in place. There is no key to filter on.
		ctx.update(LOOM)
			.set(LOOM.DB_REV, loom.getDatabaseRevision())
			.set(LOOM.LAST_USED_TIMESTAMP, toLocalDateTime(loom.getLastUsedTimestamp()))
			.execute();
	}

	@Override
	public void clear() {
		ctx.deleteFrom(LOOM).execute();
	}

	@Override
	public long count() {
		return ctx.selectCount()
			.from(LOOM)
			.fetchOne(0, Long.class);
	}

	private Loom toLoom(JooqLoomRecord record) {
		if (record == null) {
			return null;
		}
		return new LoomImpl()
			.setDatabaseRevision(record.getDbRev())
			.setLastUsedTimestamp(toInstant(record.getLastUsedTimestamp()));
	}

	private static LocalDateTime toLocalDateTime(Instant instant) {
		return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant toInstant(LocalDateTime ldt) {
		return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
	}
}
