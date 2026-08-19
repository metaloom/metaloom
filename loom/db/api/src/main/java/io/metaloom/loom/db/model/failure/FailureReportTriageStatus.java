package io.metaloom.loom.db.model.failure;

/**
 * Where a {@link FailureReport} stands in triage.
 *
 * <p>
 * The column is a {@code varchar} with a CHECK rather than a Postgres enum (V2.107), so this type is the only place the vocabulary is enforced in
 * Java. Parse with {@link #parse(String)} rather than {@link #valueOf(String)}: a row written by hand with the wrong spelling should surface as a named
 * error at the boundary, not as an {@link IllegalArgumentException} three frames deeper.
 * </p>
 */
public enum FailureReportTriageStatus {

	/** Submitted and not yet looked at. The default the row is created with. */
	NEW,

	/** Somebody has read it. Deliberately distinct from RESOLVED, so an inbox can show what is in hand. */
	ACKNOWLEDGED,

	/** Dealt with. Kept rather than deleted, because the trace id stays useful after the fix. */
	RESOLVED;

	/**
	 * Resolve the stored column value, or {@code null} when it is absent or not a member of this vocabulary.
	 *
	 * @param value
	 *            the raw {@code failure_report.triage_status} value
	 * @return the parsed status, or null
	 */
	public static FailureReportTriageStatus parse(String value) {
		if (value == null) {
			return null;
		}
		for (FailureReportTriageStatus status : values()) {
			if (status.name().equals(value)) {
				return status;
			}
		}
		return null;
	}
}
