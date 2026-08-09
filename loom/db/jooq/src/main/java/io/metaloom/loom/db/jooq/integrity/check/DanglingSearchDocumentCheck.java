package io.metaloom.loom.db.jooq.integrity.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSqlCheck;

/**
 * Whether a polymorphic search row agrees with the entity it names.
 *
 * <p>
 * The index is maintained entirely by triggers (V2.58/V2.59), and {@code entity_uuid} cannot carry a
 * foreign key because {@code (entity_type, entity_uuid)} is polymorphic. So nothing but this notices
 * when a delete path fails to fire the matching cleanup.
 * </p>
 *
 * <p>
 * Two instances, mirror images of each other:
 * </p>
 * <ul>
 * <li>{@link #documents()} - a {@code search_document} row whose subject is gone. Search offers a hit
 * that resolves to nothing.</li>
 * <li>{@link #staleTombstones()} - a {@code search_document_deleted} row whose subject is back. An
 * external index would be told to drop a live row.</li>
 * </ul>
 *
 * <p>
 * A row whose {@code entity_type} is not a known type at all is not this check's business - see
 * {@link EnumColumnCheck}. Such a row matches no branch here and is reported once, by the check that
 * can say something useful about it.
 * </p>
 */
public final class DanglingSearchDocumentCheck extends AbstractSqlCheck {

	private final String table;

	/** Whether an offending row is one whose subject IS present (a stale tombstone) or is absent. */
	private final boolean offendingWhenSubjectPresent;

	private DanglingSearchDocumentCheck(DbIntegrityCheckInfo info, String table,
		boolean offendingWhenSubjectPresent) {
		super(info);
		this.table = table;
		this.offendingWhenSubjectPresent = offendingWhenSubjectPresent;
	}

	public static DanglingSearchDocumentCheck documents() {
		return new DanglingSearchDocumentCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.DANGLING_SEARCH_DOCUMENT,
			DbIntegrityCategory.DANGLING,
			DbIntegritySeverity.ERROR,
			"search_document", "entity_uuid",
			"A search document points at an entity that no longer exists, so a search can return a"
				+ " hit that resolves to nothing. The index is trigger-maintained and entity_uuid"
				+ " carries no foreign key, so a missed delete trigger is invisible without this."),
			"search_document", false);
	}

	public static DanglingSearchDocumentCheck staleTombstones() {
		return new DanglingSearchDocumentCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.STALE_SEARCH_TOMBSTONE,
			DbIntegrityCategory.DANGLING,
			DbIntegritySeverity.WARN,
			"search_document_deleted", "entity_uuid",
			"A deletion tombstone names an entity that exists again, so an external index would be"
				+ " told to drop a live row."),
			"search_document_deleted", true);
	}

	private String predicate() {
		String exists = offendingWhenSubjectPresent ? "exists" : "not exists";
		List<String> branches = new ArrayList<>();
		for (Map.Entry<SearchEntityType, String> entry : SearchDocumentEntities.TABLES.entrySet()) {
			branches.add("(d.\"entity_type\" = '" + entry.getKey().id() + "'"
				+ " and " + exists + " (select 1 from \"" + entry.getValue() + "\" e"
				+ " where e.\"uuid\" = d.\"entity_uuid\"))");
		}
		return String.join("\n     or ", branches);
	}

	@Override
	protected String countSql() {
		return "select count(*) from \"" + table + "\" d where " + predicate();
	}

	@Override
	protected String sampleSql() {
		return "select d.\"entity_uuid\", d.\"entity_type\" from \"" + table + "\" d where " + predicate();
	}
}
