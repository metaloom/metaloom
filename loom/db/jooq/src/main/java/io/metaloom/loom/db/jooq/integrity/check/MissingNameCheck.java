package io.metaloom.loom.db.jooq.integrity.check;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractConditionCheck;
import io.metaloom.loom.db.jooq.tables.JooqBlacklist;
import io.metaloom.loom.db.jooq.tables.JooqToken;

/**
 * A nullable name column that the application nevertheless needs filled in.
 *
 * <p>
 * Most nullable names in this schema are legitimately empty and are deliberately <em>not</em>
 * checked: {@code cluster.name} is null precisely because the cluster is an unnamed machine
 * proposal, {@code person.firstname}/{@code lastname} defer to {@code alias},
 * {@code annotation.title}, {@code comment.title}, {@code detection.label} and
 * {@code memory_entry.title} are all optional by design. Sweeping those would report the fixture,
 * every Cortex run and every normal installation.
 * </p>
 *
 * <p>
 * Two are different, and each gets its own code so a caller can act on one without the other.
 * </p>
 */
public final class MissingNameCheck extends AbstractConditionCheck {

	private final Table<?> table;
	private final Field<String> column;

	private MissingNameCheck(DbIntegrityCheckInfo info, Table<?> table, Field<String> column) {
		super(info);
		this.table = table;
		this.column = column;
	}

	/**
	 * {@code token.name} takes part in {@code UNIQUE (creator_uuid, name)}. Postgres treats NULLs as
	 * distinct under a default unique index, so a null name does not merely look untidy - it defeats
	 * the constraint, and one user can accumulate any number of indistinguishable API tokens.
	 */
	public static MissingNameCheck tokenName() {
		return new MissingNameCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.MISSING_TOKEN_NAME,
			"Unnamed API token",
			DbIntegrityCategory.MANDATORY_FIELD,
			DbIntegritySeverity.ERROR,
			"token", "name",
			"An API token has no name. The column takes part in UNIQUE (creator_uuid, name), and"
				+ " because Postgres counts NULLs as distinct a user can hold any number of"
				+ " unnamed, indistinguishable tokens."),
			JooqToken.TOKEN, JooqToken.TOKEN.NAME);
	}

	/**
	 * {@code blacklist.name} was added nullable by V2.50 only so existing rows could be admitted; the
	 * whole REST surface speaks "name" and returned null for every row before that migration. New
	 * rows should always have one.
	 */
	public static MissingNameCheck blacklistName() {
		return new MissingNameCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.MISSING_BLACKLIST_NAME,
			"Unnamed blacklist entry",
			DbIntegrityCategory.MANDATORY_FIELD,
			DbIntegritySeverity.WARN,
			"blacklist", "name",
			"A blacklist entry has no name. V2.50 added the column nullable only to admit rows that"
				+ " predated it."),
			JooqBlacklist.BLACKLIST, JooqBlacklist.BLACKLIST.NAME);
	}

	@Override
	protected Table<?> table() {
		return table;
	}

	@Override
	protected Condition condition() {
		return column.isNull();
	}

	@Override
	protected Field<?>[] detailFields() {
		return new Field<?>[] { DSL.inline(column.getName() + " is null").as("reason") };
	}
}
