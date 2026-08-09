package io.metaloom.loom.db.integrity;

/**
 * The kind of defect a check looks for. Used to group the report and to run a subset of the
 * catalogue via {@link DbIntegrityScope}.
 */
public enum DbIntegrityCategory {

	/**
	 * A row points at something that is not there. Only reachable where the schema has no foreign key
	 * to do the job - a genuinely missing constraint, a polymorphic reference the database cannot
	 * express, or a soft-deleted target that survives the FK but not the application's reads.
	 */
	DANGLING,

	/** {@code created}/{@code edited} disagree with each other, with a parent row, or with reality. */
	TIMESTAMP,

	/** A column that is nullable or blank in SQL but that the application treats as required. */
	MANDATORY_FIELD,

	/**
	 * A {@code varchar} column holding a value outside the Java enum the application reads it back
	 * with. These are the ones that turn into a 500 rather than a wrong answer.
	 */
	VOCABULARY,

	/**
	 * Wrong number of rows, or a combination of nulls a {@code CHECK} constraint is supposed to
	 * prevent. Duplicates a constraint on purpose: the constraint stops the application, this catches
	 * rows written around it.
	 */
	CARDINALITY;
}
