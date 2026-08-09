package io.metaloom.loom.db.jooq.integrity.check;

import java.util.List;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSqlCheck;

/**
 * A {@code *_uuid} column that points at {@code "user"} but carries no foreign key doing so.
 *
 * <p>
 * These are the only places in the schema where a reference to a user can genuinely dangle. There
 * are 236 declared foreign keys and Postgres enforces every one of them, so sweeping those would
 * cost 236 queries for a hit rate of zero. The columns below are the ones nobody declared:
 * </p>
 * <ul>
 * <li>{@code token.editor_uuid} - {@code V2.1__add_acl.sql} adds a foreign key for
 * {@code creator_uuid} and simply omits the line for {@code editor_uuid}, while {@code user},
 * {@code role} and {@code group} in the same file get both.</li>
 * <li>{@code asset_remix.editor_uuid} - {@code V2.8__add_asset.sql} repeats the same omission.</li>
 * <li>{@code vector_config.creator_uuid} and {@code editor_uuid} -
 * {@code V2.6__add_vector_config.sql} declares no primary key and no foreign keys at all.</li>
 * </ul>
 *
 * <p>
 * Detecting these is not the same as fixing them. The constraints should still be added; until they
 * are, this is what notices.
 * </p>
 */
public final class DanglingUserReferenceCheck extends AbstractSqlCheck {

	private final String table;

	/** Columns to check, each of which should name a user and might not. */
	private final List<String> columns;

	/** Columns naming the offending row in a sample, uuid first. */
	private final List<String> identity;

	private DanglingUserReferenceCheck(DbIntegrityCheckInfo info, String table, List<String> identity,
		List<String> columns) {
		super(info);
		this.table = table;
		this.identity = identity;
		this.columns = columns;
	}

	public static DanglingUserReferenceCheck tokenEditor() {
		return new DanglingUserReferenceCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.DANGLING_TOKEN_EDITOR,
			DbIntegrityCategory.DANGLING,
			DbIntegritySeverity.ERROR,
			"token", "editor_uuid",
			"An API token names an editor that is not a user. The column has no foreign key -"
				+ " V2.1 declared one for creator_uuid only."),
			"token", List.of("uuid"), List.of("editor_uuid"));
	}

	public static DanglingUserReferenceCheck assetRemixEditor() {
		// asset_remix has no uuid at all: it is keyed by the pair of assets it links.
		return new DanglingUserReferenceCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.DANGLING_ASSET_REMIX_EDITOR,
			DbIntegrityCategory.DANGLING,
			DbIntegritySeverity.ERROR,
			"asset_remix", "editor_uuid",
			"A remix link names an editor that is not a user. The column has no foreign key -"
				+ " V2.8 repeated the V2.1 omission."),
			"asset_remix", List.of("asset_a_uuid", "asset_b_uuid"), List.of("editor_uuid"));
	}

	public static DanglingUserReferenceCheck vectorConfigActor() {
		return new DanglingUserReferenceCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.DANGLING_VECTOR_CONFIG_ACTOR,
			DbIntegrityCategory.DANGLING,
			DbIntegritySeverity.ERROR,
			"vector_config", "creator_uuid, editor_uuid",
			"A vector config names a creator or editor that is not a user. V2.6 declared the table"
				+ " with no primary key and no foreign keys at all."),
			"vector_config", List.of("uuid"), List.of("creator_uuid", "editor_uuid"));
	}

	private String predicate() {
		return columns.stream()
			.map(c -> "(t.\"" + c + "\" is not null and not exists"
				+ " (select 1 from \"user\" u where u.\"uuid\" = t.\"" + c + "\"))")
			.reduce((a, b) -> a + " or " + b)
			.orElseThrow();
	}

	@Override
	protected String countSql() {
		return "select count(*) from \"" + table + "\" t where " + predicate();
	}

	@Override
	protected String sampleSql() {
		StringBuilder select = new StringBuilder("select ");
		// A uuid first column is read as the finding's identity; anything else falls into the detail.
		if (identity.size() == 1 && "uuid".equals(identity.get(0))) {
			select.append("t.\"uuid\"");
		} else {
			select.append("null::uuid");
			identity.forEach(c -> select.append(", t.\"").append(c).append('"'));
		}
		columns.forEach(c -> select.append(", t.\"").append(c).append('"'));
		return select.append(" from \"").append(table).append("\" t where ").append(predicate()).toString();
	}
}
