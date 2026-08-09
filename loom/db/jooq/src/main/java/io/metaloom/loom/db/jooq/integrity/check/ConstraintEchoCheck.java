package io.metaloom.loom.db.jooq.integrity.check;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSqlCheck;

/**
 * An invariant that a {@code CHECK} constraint already enforces, asked again from outside.
 *
 * <p>
 * The duplication is the point, and it is worth being explicit about why it is not waste. A
 * constraint stops the application. It does not stop a migration backfill, a constraint added
 * {@code NOT VALID} over existing rows, a bulk load with {@code session_replication_role} set, or a
 * constraint that was dropped during a schema change and never put back. Every one of those leaves
 * rows the application will later choke on, and every one of them is invisible to the constraint
 * that is supposed to prevent them.
 * </p>
 *
 * <p>
 * These are also the cheapest checks in the catalogue - each is a single predicate over one table -
 * so asking twice costs almost nothing.
 * </p>
 */
public final class ConstraintEchoCheck extends AbstractSqlCheck {

	private final String table;
	private final String predicate;
	private final String sampleColumns;

	private ConstraintEchoCheck(DbIntegrityCheckInfo info, String table, String predicate,
		String sampleColumns) {
		super(info);
		this.table = table;
		this.predicate = predicate;
		this.sampleColumns = sampleColumns;
	}

	/** {@code asset_pool} is filesystem-backed or S3-backed, never neither and never both (V2.20). */
	public static ConstraintEchoCheck assetPoolBackend() {
		return new ConstraintEchoCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.XOR_ASSET_POOL_BACKEND,
			"Asset pool backend",
			DbIntegrityCategory.CARDINALITY,
			DbIntegritySeverity.ERROR,
			"asset_pool", "fs_path, s3_bucket",
			"An asset pool is neither filesystem-backed nor S3-backed, or claims to be both, so"
				+ " nothing can work out where its bytes live."),
			"asset_pool",
			"num_nonnulls(t.\"fs_path\", t.\"s3_bucket\") <> 1",
			"t.\"uuid\", t.\"name\", t.\"fs_path\", t.\"s3_bucket\"");
	}

	/**
	 * {@code task_assignee} names exactly one of a user or a group (V2.69). The table has no primary
	 * key - one cannot hold nullable columns - so the CHECK and two partial unique indexes are the
	 * only structure it has.
	 */
	public static ConstraintEchoCheck taskAssignee() {
		return new ConstraintEchoCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.XOR_TASK_ASSIGNEE,
			"Task assignee",
			DbIntegrityCategory.CARDINALITY,
			DbIntegritySeverity.ERROR,
			"task_assignee", "user_uuid, group_uuid",
			"A task assignment names neither a user nor a group, or both. The table has no primary"
				+ " key, so this CHECK is most of what keeps it well-formed."),
			"task_assignee",
			"num_nonnulls(t.\"user_uuid\", t.\"group_uuid\") <> 1",
			"null::uuid, t.\"task_uuid\", t.\"user_uuid\", t.\"group_uuid\"");
	}

	/** {@code embedding.vector} is as long as the row's own {@code dimensions} says (V2.75). */
	public static ConstraintEchoCheck embeddingDimensions() {
		return new ConstraintEchoCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.EMBEDDING_DIMENSION_MISMATCH,
			"Embedding vector length",
			DbIntegrityCategory.CARDINALITY,
			DbIntegritySeverity.ERROR,
			"embedding", "vector, dimensions",
			"An embedding's vector is not as long as its own dimensions column claims, which makes"
				+ " every distance computed against it meaningless rather than merely wrong."),
			"embedding",
			"array_length(t.\"vector\", 1) is distinct from t.\"dimensions\"",
			"t.\"uuid\", t.\"model\", t.\"dimensions\", array_length(t.\"vector\", 1) as actual");
	}

	/**
	 * A run is either a stored pipeline or an ad-hoc one, and {@code kind} says which (V2.83). The
	 * pairing exists so no consumer has to defend against a third state; a row that breaks it puts
	 * that third state back.
	 */
	public static ConstraintEchoCheck pipelineRunKind() {
		return new ConstraintEchoCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.PIPELINE_RUN_KIND_MISMATCH,
			"Pipeline run kind",
			DbIntegrityCategory.CARDINALITY,
			DbIntegritySeverity.ERROR,
			"pipeline_run", "kind, pipeline_uuid",
			"A pipeline run's kind disagrees with whether it has a pipeline. PIPELINE requires a"
				+ " pipeline_uuid and ADHOC forbids one; anything else is the third state V2.83"
				+ " exists to rule out."),
			"pipeline_run",
			"(t.\"kind\" = 'PIPELINE' and t.\"pipeline_uuid\" is null)"
				+ " or (t.\"kind\" = 'ADHOC' and t.\"pipeline_uuid\" is not null)",
			"t.\"uuid\", t.\"kind\", t.\"pipeline_uuid\"");
	}

	@Override
	protected String countSql() {
		return "select count(*) from \"" + table + "\" t where " + predicate;
	}

	@Override
	protected String sampleSql() {
		return "select " + sampleColumns + " from \"" + table + "\" t where " + predicate;
	}
}
