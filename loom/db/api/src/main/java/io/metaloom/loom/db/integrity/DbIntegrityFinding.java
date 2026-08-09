package io.metaloom.loom.db.integrity;

import java.util.UUID;

/**
 * One offending row, as far as a report is willing to name it.
 *
 * @param entityUuid
 *            primary key of the offending row, or {@code null} for the tables that have none
 *            ({@code loom}, {@code task_assignee}, {@code search_document}, the join tables)
 * @param detail
 *            the values that make the row offending, rendered for a human - the two timestamps, the
 *            unresolvable uuid, the bad enum string. Never the whole row
 */
public record DbIntegrityFinding(UUID entityUuid, String detail) {

	public static DbIntegrityFinding of(UUID entityUuid, String detail) {
		return new DbIntegrityFinding(entityUuid, detail);
	}

	public static DbIntegrityFinding of(String detail) {
		return new DbIntegrityFinding(null, detail);
	}

	@Override
	public String toString() {
		if (entityUuid == null) {
			return detail == null ? "?" : detail;
		}
		return detail == null ? entityUuid.toString() : entityUuid + " (" + detail + ")";
	}
}
