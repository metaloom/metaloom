package io.metaloom.loom.db.jooq.integrity.check;

import java.util.List;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSweepCheck;

/**
 * An audit timestamp that cannot be right - a zeroed date, an epoch default, a year in the future.
 *
 * <p>
 * The future tolerance is deliberately wide, and it is worth saying why rather than leaving the
 * number looking arbitrary. {@code created} and {@code edited} are {@code timestamp WITHOUT TIME
 * ZONE}. Their SQL default is {@code now()}, which is the database session's local wall clock, while
 * a row written through a DAO carries a Java {@link java.time.Instant} converted by jOOQ. If those
 * two clocks are in different zones the values disagree by a whole offset - legitimately, and
 * systematically. A tight window would report that skew as corruption on every non-UTC deployment.
 * </p>
 *
 * <p>
 * So this check does not try to detect skew, which is not decidable from inside the column. It
 * detects timestamps that no timezone can explain: {@value #FLOOR} or earlier, and more than
 * {@value #FUTURE_TOLERANCE} ahead of now.
 * </p>
 */
public final class TimestampImplausibleCheck extends AbstractSweepCheck {

	/** MetaLoom did not exist before this. Catches epoch-zero and uninitialised values. */
	private static final String FLOOR = "2020-01-01";

	/** Wider than any real UTC offset (max is +14:00), so timezone skew alone never trips it. */
	private static final String FUTURE_TOLERANCE = "15 hours";

	public TimestampImplausibleCheck() {
		super(new DbIntegrityCheckInfo(
			DbIntegrityCodes.TIMESTAMP_IMPLAUSIBLE,
			"Timestamp out of range",
			DbIntegrityCategory.TIMESTAMP,
			DbIntegritySeverity.WARN,
			"(every audited table)", "created, edited",
			"An audit timestamp predates the project or sits further in the future than any"
				+ " timezone offset could explain."),
			branches());
	}

	private static List<String> branches() {
		String predicate = """
			t."created" < timestamp '%s'
			  or t."edited"  < timestamp '%s'
			  or t."created" > now() + interval '%s'
			  or t."edited"  > now() + interval '%s'
			""".formatted(FLOOR, FLOOR, FUTURE_TOLERANCE, FUTURE_TOLERANCE);

		return AuditedTables.ALL.stream()
			.map(table -> branch(table,
				"t.\"uuid\"",
				"'created=' || t.\"created\" || ' edited=' || t.\"edited\"",
				predicate))
			.toList();
	}
}
