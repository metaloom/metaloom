package io.metaloom.loom.db.jooq.integrity.check;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSqlCheck;

/**
 * Uniqueness a table has no key to enforce.
 *
 * <p>
 * Both cases here come from the same omission: a table that was created without a primary key, so
 * the thing that makes a row identifiable is a convention held in the application rather than a
 * constraint held in the database.
 * </p>
 */
public final class RowCountCheck extends AbstractSqlCheck {

	private final String countSql;
	private final String sampleSql;

	private RowCountCheck(DbIntegrityCheckInfo info, String countSql, String sampleSql) {
		super(info);
		this.countSql = countSql;
		this.sampleSql = sampleSql;
	}

	/**
	 * {@code loom} holds the instance's own record - schema revision and last-used timestamp - and
	 * {@code LoomDao} treats it as a singleton, loading it with no key at all. The table has no
	 * primary key and no unique constraint, so a second row is physically possible, and whichever one
	 * Postgres returns first silently becomes the truth.
	 */
	public static RowCountCheck loomSingleton() {
		return new RowCountCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.LOOM_SINGLETON,
			"Single instance row",
			DbIntegrityCategory.CARDINALITY,
			DbIntegritySeverity.ERROR,
			"loom", null,
			"The loom table holds more than one row. It is a singleton by convention only - there is"
				+ " no primary key - so a second row means whichever one Postgres returns first"
				+ " becomes the instance's schema revision."),
			// Report the surplus, so the count reads as "how many rows too many".
			"select greatest(count(*) - 1, 0) from \"loom\"",
			"select null::uuid, \"db_rev\", \"last_used_timestamp\" from \"loom\"");
	}

	/**
	 * {@code vector_config} was created by V2.6 with a {@code uuid} column, no primary key and no
	 * unique index on it - the only unique thing about a row is its {@code name}. Two rows can
	 * therefore share a uuid, and anything loading by uuid gets an arbitrary one.
	 */
	public static RowCountCheck duplicateVectorConfigUuid() {
		return new RowCountCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.DUPLICATE_VECTOR_CONFIG_UUID,
			"Vector config identity",
			DbIntegrityCategory.CARDINALITY,
			DbIntegritySeverity.ERROR,
			"vector_config", "uuid",
			"Two vector configs share a uuid, or one has none. V2.6 declared the column without a"
				+ " primary key or unique index, so a load by uuid returns an arbitrary row."),
			"""
				select coalesce(sum(n), 0) from (
				  select count(*) as n from "vector_config" where "uuid" is not null
				   group by "uuid" having count(*) > 1
				  union all
				  select count(*) from "vector_config" where "uuid" is null
				) dupes
				""",
			"""
				select "uuid", count(*) as copies from "vector_config"
				 group by "uuid" having count(*) > 1 or "uuid" is null
				""");
	}

	@Override
	protected String countSql() {
		return countSql;
	}

	@Override
	protected String sampleSql() {
		return sampleSql;
	}
}
