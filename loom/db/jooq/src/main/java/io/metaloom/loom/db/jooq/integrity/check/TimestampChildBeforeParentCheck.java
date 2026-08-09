package io.metaloom.loom.db.jooq.integrity.check;

import java.util.List;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSweepCheck;

/**
 * A row that claims to predate the row it hangs off.
 *
 * <p>
 * Only the parent/child pairs where the ordering is a real invariant: a version cannot exist before
 * the thing it versions, a run item cannot exist before its run, a binary cannot be recorded before
 * the asset it belongs to. Pairs where the child legitimately predates the parent - an asset filed
 * into a collection created later, say - are not listed and must not be.
 * </p>
 *
 * <p>
 * WARN. The DAOs write parent and child in separate statements with separate {@code Instant.now()}
 * calls, and a row created inside a transaction can carry a {@code DEFAULT now()} transaction
 * timestamp while its sibling carries wall clock. Sub-second inversions here are noise; a
 * consistently ordered violation is not, and that is what a human is being asked to look at.
 * </p>
 */
public final class TimestampChildBeforeParentCheck extends AbstractSweepCheck {

	public TimestampChildBeforeParentCheck() {
		super(new DbIntegrityCheckInfo(
			DbIntegrityCodes.TIMESTAMP_CHILD_BEFORE_PARENT,
			"Child created before its parent",
			DbIntegrityCategory.TIMESTAMP,
			DbIntegritySeverity.WARN,
			"asset_location, pipeline_run_item, pipeline_version, skill_version", "created",
			"A row was created before the row it belongs to, which cannot have happened in the"
				+ " order the application writes them."),
			branches());
	}

	private static List<String> branches() {
		return List.of(
			childOf("asset_location", "asset_uuid", "asset"),
			childOf("pipeline_run_item", "run_uuid", "pipeline_run"),
			childOf("pipeline_version", "pipeline_uuid", "pipeline"),
			childOf("skill_version", "skill_uuid", "skill"));
	}

	/**
	 * @param child
	 *            the dependent table
	 * @param fkColumn
	 *            its reference to the parent
	 * @param parent
	 *            the table it depends on
	 */
	private static String childOf(String child, String fkColumn, String parent) {
		String parentCreated = "(select p.\"created\" from \"" + parent + "\" p"
			+ " where p.\"uuid\" = t.\"" + fkColumn + "\")";
		return branch(child, "t.\"uuid\"",
			"'created=' || t.\"created\" || ' " + parent + ".created=' || " + parentCreated,
			"t.\"" + fkColumn + "\" is not null and t.\"created\" < " + parentCreated);
	}
}
