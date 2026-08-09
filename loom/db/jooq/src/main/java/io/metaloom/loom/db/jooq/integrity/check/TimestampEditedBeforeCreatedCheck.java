package io.metaloom.loom.db.jooq.integrity.check;

import java.util.List;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSweepCheck;

/**
 * A row that claims to have been edited before it was created.
 *
 * <p>
 * The comparison is strictly {@code edited < created}, and the strictness is the whole design.
 * {@code AbstractJooqDao.setCreatorEditor} calls {@code Instant.now()} twice in a row, so a freshly
 * stored row has {@code edited} a few microseconds <em>after</em> {@code created}; other DAOs
 * ({@code NotificationDaoImpl}, {@code CortexInstanceDaoImpl}, {@code NodeDescriptorRecordDaoImpl})
 * capture one instant and use it for both, so {@code edited == created} exactly. Both are correct
 * and neither may be flagged - only an actual inversion is a defect.
 * </p>
 *
 * <p>
 * This is the check that will fire first on a code path that copies timestamps between rows, or that
 * writes {@code edited} from a value captured before {@code created}.
 * </p>
 */
public final class TimestampEditedBeforeCreatedCheck extends AbstractSweepCheck {

	public TimestampEditedBeforeCreatedCheck() {
		super(new DbIntegrityCheckInfo(
			DbIntegrityCodes.TIMESTAMP_EDITED_BEFORE_CREATED,
			"Edited before created",
			DbIntegrityCategory.TIMESTAMP,
			DbIntegritySeverity.ERROR,
			"(every audited table)", "edited",
			"A row was edited before it was created. Compared strictly, so the microsecond gap a"
				+ " normal insert leaves between the two Instant.now() calls is not a finding, and"
				+ " neither is edited == created."),
			branches());
	}

	private static List<String> branches() {
		return AuditedTables.ALL.stream()
			.map(table -> branch(table,
				AuditedTables.hasUuid(table) ? "t.\"uuid\"" : null,
				"'created=' || t.\"created\" || ' edited=' || t.\"edited\"",
				"t.\"edited\" < t.\"created\""))
			.toList();
	}
}
