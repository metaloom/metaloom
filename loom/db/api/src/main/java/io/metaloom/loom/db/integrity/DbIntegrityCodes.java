package io.metaloom.loom.db.integrity;

/**
 * The stable identifiers of every registered check.
 *
 * <p>
 * These are the only part of a finding a client should branch on - the descriptions beside them are
 * for humans and are expected to be reworded. The pattern is borrowed from
 * {@code PipelineValidationService}, which learned it the hard way.
 * </p>
 *
 * <p>
 * They live in {@code loom-db-api} rather than next to the check classes in {@code loom-db-jooq} so
 * that a test in {@code loom-db-api-test} can name a code in its ignore list. {@code
 * DbIntegrityChecksTest} asserts that every registered check's code appears here and vice versa, so
 * this file cannot drift.
 * </p>
 */
public final class DbIntegrityCodes {

	private DbIntegrityCodes() {
	}

	// ── DANGLING ───────────────────────────────────────────────────────────
	// Only the references the schema does not enforce. The 236 declared foreign keys are the
	// database's job and are deliberately not swept - see spec/features/db/DB_INTEGRITY.md.

	/** {@code token.editor_uuid} names no user. V2.1 adds a foreign key for creator_uuid only. */
	public static final String DANGLING_TOKEN_EDITOR = "DANGLING_TOKEN_EDITOR";

	// DANGLING_ASSET_REMIX_EDITOR was retired by V2.100: that migration dropped asset_remix, and the
	// remix/remix_member tables replacing it declare foreign keys on both actor columns.

	/** {@code vector_config.creator_uuid}/{@code editor_uuid} name no user. V2.6 declares no constraints at all. */
	public static final String DANGLING_VECTOR_CONFIG_ACTOR = "DANGLING_VECTOR_CONFIG_ACTOR";

	/** A {@code search_document} row whose subject is gone - a trigger gap in V2.58/V2.59. */
	public static final String DANGLING_SEARCH_DOCUMENT = "DANGLING_SEARCH_DOCUMENT";

	/** A {@code search_document_deleted} tombstone for an entity that exists again. */
	public static final String STALE_SEARCH_TOMBSTONE = "STALE_SEARCH_TOMBSTONE";

	/** {@code memory_entry.scope_uuid} resolves to nothing in the table its {@code scope} names. */
	public static final String DANGLING_MEMORY_ENTRY_SCOPE = "DANGLING_MEMORY_ENTRY_SCOPE";

	/** {@code pipeline_node_task.leased_by} names a cortex instance that is not registered. */
	public static final String DANGLING_NODE_TASK_LEASE = "DANGLING_NODE_TASK_LEASE";

	/** A soft-deleted user still holds live work - assignments, notifications, tokens, memberships. */
	public static final String SOFT_DELETED_USER_HAS_LIVE_WORK = "SOFT_DELETED_USER_HAS_LIVE_WORK";

	// ── TIMESTAMP ──────────────────────────────────────────────────────────

	/** A row was edited before it was created. */
	public static final String TIMESTAMP_EDITED_BEFORE_CREATED = "TIMESTAMP_EDITED_BEFORE_CREATED";

	/** A timestamp predates the project or sits implausibly far in the future. */
	public static final String TIMESTAMP_IMPLAUSIBLE = "TIMESTAMP_IMPLAUSIBLE";

	/** A child row claims to predate the parent it hangs off. */
	public static final String TIMESTAMP_CHILD_BEFORE_PARENT = "TIMESTAMP_CHILD_BEFORE_PARENT";

	// ── MANDATORY_FIELD ────────────────────────────────────────────────────

	/** A NOT NULL name column holds the empty string or only whitespace. */
	public static final String BLANK_NAME = "BLANK_NAME";

	/** {@code token.name} is null, which defeats the UNIQUE (creator_uuid, name) it takes part in. */
	public static final String MISSING_TOKEN_NAME = "MISSING_TOKEN_NAME";

	/** {@code blacklist.name} is null - V2.50 made it nullable only to admit pre-existing rows. */
	public static final String MISSING_BLACKLIST_NAME = "MISSING_BLACKLIST_NAME";

	/** A soft-deleted user still carries the personal data {@code markDeleted()} is meant to clear. */
	public static final String SOFT_DELETED_USER_NOT_ANONYMISED = "SOFT_DELETED_USER_NOT_ANONYMISED";

	// ── VOCABULARY ─────────────────────────────────────────────────────────
	// Each column gets its own code so a client can branch on the one that matters to it.

	/** {@code reaction.type} is not a ReactionType. Every REST read of the row is a 500. */
	public static final String INVALID_REACTION_TYPE = "INVALID_REACTION_TYPE";

	public static final String VOCABULARY_PIPELINE_RUN_STATUS = "VOCABULARY_PIPELINE_RUN_STATUS";
	public static final String VOCABULARY_PIPELINE_RUN_ITEM_STATE = "VOCABULARY_PIPELINE_RUN_ITEM_STATE";
	public static final String VOCABULARY_PIPELINE_NODE_TASK_STATE = "VOCABULARY_PIPELINE_NODE_TASK_STATE";
	public static final String VOCABULARY_NOTIFICATION_TYPE = "VOCABULARY_NOTIFICATION_TYPE";
	public static final String VOCABULARY_NODE_DESCRIPTOR_STATUS = "VOCABULARY_NODE_DESCRIPTOR_STATUS";
	public static final String VOCABULARY_SEARCH_DOCUMENT_ENTITY_TYPE = "VOCABULARY_SEARCH_DOCUMENT_ENTITY_TYPE";
	public static final String VOCABULARY_MEMORY_ENTRY_SCOPE = "VOCABULARY_MEMORY_ENTRY_SCOPE";

	// ── CARDINALITY ────────────────────────────────────────────────────────
	// The last four duplicate CHECK constraints on purpose: the constraint stops the application,
	// this catches rows written around it by a backfill or a constraint that was never re-added.

	/** More than one row in the singleton {@code loom} table, which has no primary key. */
	public static final String LOOM_SINGLETON = "LOOM_SINGLETON";

	/** Duplicate or null {@code vector_config.uuid} - V2.6 declares no primary key. */
	public static final String DUPLICATE_VECTOR_CONFIG_UUID = "DUPLICATE_VECTOR_CONFIG_UUID";

	/** An asset pool is neither filesystem-backed nor S3-backed, or is somehow both. */
	public static final String XOR_ASSET_POOL_BACKEND = "XOR_ASSET_POOL_BACKEND";

	/** A task assignment names neither a user nor a group, or both. */
	public static final String XOR_TASK_ASSIGNEE = "XOR_TASK_ASSIGNEE";

	/** {@code embedding.vector} is not as long as the row's own {@code dimensions} says. */
	public static final String EMBEDDING_DIMENSION_MISMATCH = "EMBEDDING_DIMENSION_MISMATCH";

	/** A pipeline run's {@code kind} disagrees with whether it has a {@code pipeline_uuid}. */
	public static final String PIPELINE_RUN_KIND_MISMATCH = "PIPELINE_RUN_KIND_MISMATCH";
}
