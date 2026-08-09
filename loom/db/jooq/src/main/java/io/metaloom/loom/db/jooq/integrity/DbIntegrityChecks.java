package io.metaloom.loom.db.jooq.integrity;

import java.util.List;

import io.metaloom.loom.db.jooq.integrity.check.BlankNameCheck;
import io.metaloom.loom.db.jooq.integrity.check.ConstraintEchoCheck;
import io.metaloom.loom.db.jooq.integrity.check.DanglingMemoryEntryScopeCheck;
import io.metaloom.loom.db.jooq.integrity.check.DanglingNodeTaskLeaseCheck;
import io.metaloom.loom.db.jooq.integrity.check.DanglingSearchDocumentCheck;
import io.metaloom.loom.db.jooq.integrity.check.DanglingUserReferenceCheck;
import io.metaloom.loom.db.jooq.integrity.check.EnumColumnCheck;
import io.metaloom.loom.db.jooq.integrity.check.MissingNameCheck;
import io.metaloom.loom.db.jooq.integrity.check.RowCountCheck;
import io.metaloom.loom.db.jooq.integrity.check.SoftDeletedUserHasLiveWorkCheck;
import io.metaloom.loom.db.jooq.integrity.check.SoftDeletedUserNotAnonymisedCheck;
import io.metaloom.loom.db.jooq.integrity.check.TimestampChildBeforeParentCheck;
import io.metaloom.loom.db.jooq.integrity.check.TimestampEditedBeforeCreatedCheck;
import io.metaloom.loom.db.jooq.integrity.check.TimestampImplausibleCheck;

/**
 * The registry: every integrity check there is, in the order a report presents them.
 *
 * <p>
 * A plain static list, and the alternatives were both considered and rejected. Dagger
 * {@code @IntoSet} gives a {@link java.util.Set} with no defined iteration order, which would
 * reorder the report and its OpenAPI example between runs, and would cost a module edit per check.
 * {@link java.util.ServiceLoader} makes registration invisible and fails silently when a
 * {@code META-INF/services} line is forgotten - the worst possible failure mode for a subsystem
 * whose entire job is noticing things that fail silently.
 * </p>
 *
 * <p>
 * A list, by contrast, gives a stable order, a compile error if a check class is deleted, and one
 * added line per new check. {@code DbIntegrityChecksTest} guards the rest: codes are unique,
 * well-formed, and match the constants in {@code DbIntegrityCodes} exactly.
 * </p>
 *
 * <p>
 * Checks are stateless and take their {@code DSLContext} per call, so these instances are shared
 * across every request and every test database.
 * </p>
 */
public final class DbIntegrityChecks {

	private static final List<DbIntegrityCheck> ALL = List.of(

		// ── DANGLING ───────────────────────────────────────────────────
		// Only references the schema does not enforce. The 236 declared foreign keys are Postgres's
		// job; sweeping them would be 236 queries for a hit rate of zero.
		DanglingUserReferenceCheck.tokenEditor(),
		DanglingUserReferenceCheck.assetRemixEditor(),
		DanglingUserReferenceCheck.vectorConfigActor(),
		DanglingSearchDocumentCheck.documents(),
		DanglingSearchDocumentCheck.staleTombstones(),
		new DanglingMemoryEntryScopeCheck(),
		new DanglingNodeTaskLeaseCheck(),
		new SoftDeletedUserHasLiveWorkCheck(),

		// ── TIMESTAMP ──────────────────────────────────────────────────
		new TimestampEditedBeforeCreatedCheck(),
		new TimestampImplausibleCheck(),
		new TimestampChildBeforeParentCheck(),

		// ── MANDATORY_FIELD ────────────────────────────────────────────
		new BlankNameCheck(),
		MissingNameCheck.tokenName(),
		MissingNameCheck.blacklistName(),
		new SoftDeletedUserNotAnonymisedCheck(),

		// ── VOCABULARY ─────────────────────────────────────────────────
		EnumColumnCheck.reactionType(),
		EnumColumnCheck.pipelineRunStatus(),
		EnumColumnCheck.pipelineRunItemState(),
		EnumColumnCheck.pipelineNodeTaskState(),
		EnumColumnCheck.notificationType(),
		EnumColumnCheck.nodeDescriptorStatus(),
		EnumColumnCheck.searchDocumentEntityType(),
		EnumColumnCheck.memoryEntryScope(),

		// ── CARDINALITY ────────────────────────────────────────────────
		RowCountCheck.loomSingleton(),
		RowCountCheck.duplicateVectorConfigUuid(),
		ConstraintEchoCheck.assetPoolBackend(),
		ConstraintEchoCheck.taskAssignee(),
		ConstraintEchoCheck.embeddingDimensions(),
		ConstraintEchoCheck.pipelineRunKind());

	private DbIntegrityChecks() {
	}

	/** Every registered check, in report order. */
	public static List<DbIntegrityCheck> all() {
		return ALL;
	}
}
