package io.metaloom.loom.db.jooq.integrity.check;

import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSqlCheck;

/**
 * A node task leased by a worker that is not registered.
 *
 * <p>
 * {@code pipeline_node_task.leased_by} is a {@code varchar} holding a
 * {@code cortex_instance.node_id}. That column is UNIQUE, so a foreign key was possible and simply
 * was not declared (V2.31) - which means a typo'd or stale worker id is undetectable at write time.
 * </p>
 *
 * <p>
 * WARN, not ERROR, and only for tasks that are still leased: a finished task keeps the id of the
 * worker that ran it, and that worker is entitled to have been shut down since. A <em>live</em>
 * lease held by an absent worker is the interesting case, because {@code LeaseReaper} is what should
 * have reclaimed it.
 * </p>
 */
public final class DanglingNodeTaskLeaseCheck extends AbstractSqlCheck {

	public DanglingNodeTaskLeaseCheck() {
		super(new DbIntegrityCheckInfo(
			DbIntegrityCodes.DANGLING_NODE_TASK_LEASE,
			"Node task lease holder",
			DbIntegrityCategory.DANGLING,
			DbIntegritySeverity.WARN,
			"pipeline_node_task", "leased_by",
			"An unfinished node task is leased to a Cortex instance that is not registered, so"
				+ " nothing is going to run it and nothing is going to reclaim it either."));
	}

	private static final String PREDICATE = """
		t."leased_by" is not null
		  and t."finished" is null
		  and not exists (select 1 from "cortex_instance" c where c."node_id" = t."leased_by")
		""";

	@Override
	protected String countSql() {
		return "select count(*) from \"pipeline_node_task\" t where " + PREDICATE;
	}

	@Override
	protected String sampleSql() {
		return "select t.\"uuid\", t.\"node_id\", t.\"leased_by\", t.\"lease_expires_at\""
			+ " from \"pipeline_node_task\" t where " + PREDICATE;
	}
}
