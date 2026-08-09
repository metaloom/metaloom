package io.metaloom.loom.db.jooq.integrity.check;

import java.util.Arrays;
import java.util.stream.Collectors;

import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.api.pipeline.RunItemState;
import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.api.search.SearchEntityType;
import io.metaloom.loom.db.integrity.DbIntegrityCategory;
import io.metaloom.loom.db.integrity.DbIntegrityCheckInfo;
import io.metaloom.loom.db.integrity.DbIntegrityCodes;
import io.metaloom.loom.db.integrity.DbIntegritySeverity;
import io.metaloom.loom.db.jooq.integrity.AbstractSqlCheck;

/**
 * A {@code varchar} column holding a value the Java enum it is read back through does not have.
 *
 * <p>
 * Several columns in this schema are typed as text in SQL and as an enum in Java - the migrations
 * chose {@code varchar} over a Postgres enum on purpose, because {@code V2.55} showed what removing
 * a single enum value costs. The consequence is that the vocabulary is enforced somewhere other than
 * the column, and a value that slips past turns into a failure at <em>read</em> time, on a code path
 * that has nothing to do with whatever wrote it.
 * </p>
 *
 * <p>
 * One class, one hand-written row per column, one stable code each so a caller can branch on the one
 * that concerns it. The SQL compares the raw text and never reads the value through jOOQ, which
 * matters: three of these columns have {@code forcedTypes} converters that throw on an unknown
 * string, so selecting them the ordinary way would fail with the very problem being looked for.
 * </p>
 *
 * <p>
 * Deliberately not a metadata sweep. The list is short, each entry is a decision, and a column added
 * here should be added by someone who has thought about what a bad value there would do.
 * </p>
 */
public final class EnumColumnCheck extends AbstractSqlCheck {

	private final String table;
	private final String column;
	private final String allowed;
	private final boolean lowercase;

	private EnumColumnCheck(DbIntegrityCheckInfo info, String table, String column, Enum<?>[] values,
		boolean lowercase) {
		super(info);
		this.table = table;
		this.column = column;
		this.lowercase = lowercase;
		this.allowed = Arrays.stream(values)
			.map(v -> "'" + (lowercase ? v.name().toLowerCase() : v.name()) + "'")
			.collect(Collectors.joining(", "));
	}

	private static EnumColumnCheck of(String code, DbIntegritySeverity severity, String table, String column,
		Enum<?>[] values, String description) {
		return new EnumColumnCheck(new DbIntegrityCheckInfo(code, DbIntegrityCategory.VOCABULARY, severity,
			table, column, description), table, column, values, false);
	}

	/**
	 * {@code reaction.type} is the worst of these and has its own class-level warning. Nothing guards
	 * it - there is no converter and no CHECK - and {@code ReactionModelBuilder.toResponse} reads it
	 * back with {@code ReactionType.valueOf}. A single bad string therefore makes <em>every</em> REST
	 * read of that row a 500. This has happened: the fixture once stored the asset's mime type here.
	 */
	public static EnumColumnCheck reactionType() {
		return of(DbIntegrityCodes.INVALID_REACTION_TYPE, DbIntegritySeverity.ERROR,
			"reaction", "type", ReactionType.values(),
			"A reaction's type is not a ReactionType. Nothing guards this column and the REST layer"
				+ " reads it with valueOf, so every read of the row is a 500.");
	}

	/** Guarded by a converter that throws on read; this finds the row before a user does. */
	public static EnumColumnCheck pipelineRunStatus() {
		return of(DbIntegrityCodes.VOCABULARY_PIPELINE_RUN_STATUS, DbIntegritySeverity.ERROR,
			"pipeline_run", "status", PipelineRunStatus.values(),
			"A pipeline run's status is not a PipelineRunStatus. The forcedType converter rejects it"
				+ " on read, so the run cannot be loaded at all.");
	}

	public static EnumColumnCheck pipelineRunItemState() {
		return of(DbIntegrityCodes.VOCABULARY_PIPELINE_RUN_ITEM_STATE, DbIntegritySeverity.ERROR,
			"pipeline_run_item", "state", RunItemState.values(),
			"A run item's state is not a RunItemState. The forcedType converter rejects it on read."
				+ " V2.77 had to repair exactly this after the engine wrote FAILURE for FAILED.");
	}

	public static EnumColumnCheck pipelineNodeTaskState() {
		return of(DbIntegrityCodes.VOCABULARY_PIPELINE_NODE_TASK_STATE, DbIntegritySeverity.ERROR,
			"pipeline_node_task", "state", NodeTaskState.values(),
			"A node task's state is not a NodeTaskState. The forcedType converter rejects it on read.");
	}

	public static EnumColumnCheck notificationType() {
		return of(DbIntegrityCodes.VOCABULARY_NOTIFICATION_TYPE, DbIntegritySeverity.ERROR,
			"notification", "type", NotificationType.values(),
			"A notification's type is not a NotificationType. There is a CHECK constraint, so a row"
				+ " here means the constraint and the Java enum have drifted apart.");
	}

	public static EnumColumnCheck memoryEntryScope() {
		return of(DbIntegrityCodes.VOCABULARY_MEMORY_ENTRY_SCOPE, DbIntegritySeverity.ERROR,
			"memory_entry", "scope", MemoryScope.values(),
			"A memory entry's scope is not a MemoryScope, so nothing can work out which table its"
				+ " scope_uuid points into.");
	}

	/** {@code node_descriptor.status} carries a CHECK; a row here means the CHECK and the code differ. */
	public static EnumColumnCheck nodeDescriptorStatus() {
		return new EnumColumnCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.VOCABULARY_NODE_DESCRIPTOR_STATUS,
			DbIntegrityCategory.VOCABULARY,
			DbIntegritySeverity.ERROR,
			"node_descriptor", "status",
			"A node descriptor's status is neither ACTIVE nor CONFLICTED, so the CHECK constraint"
				+ " from V2.66 has been dropped or worked around."),
			"node_descriptor", "status", NodeDescriptorStatus.values(), false);
	}

	/**
	 * {@code search_document.entity_type} stores the lowercase {@link SearchEntityType#id()}, not the
	 * constant name, so this one compares in lower case. An unknown value does not throw - it makes
	 * the row permanently unreachable, because {@code PostgresSearchProvider} filters on the raw
	 * string.
	 */
	public static EnumColumnCheck searchDocumentEntityType() {
		return new EnumColumnCheck(new DbIntegrityCheckInfo(
			DbIntegrityCodes.VOCABULARY_SEARCH_DOCUMENT_ENTITY_TYPE,
			DbIntegrityCategory.VOCABULARY,
			DbIntegritySeverity.ERROR,
			"search_document", "entity_type",
			"A search document's entity_type is not a SearchEntityType. It will not throw - the row"
				+ " simply becomes unreachable, because the provider filters on the raw string."),
			"search_document", "entity_type", SearchEntityType.values(), true);
	}

	/** The two values {@code V2.66} allows. There is no Java enum for this one. */
	private enum NodeDescriptorStatus {
		ACTIVE, CONFLICTED
	}

	private String predicate() {
		String value = lowercase ? "lower(t.\"" + column + "\")" : "t.\"" + column + "\"";
		return "t.\"" + column + "\" is not null and " + value + " not in (" + allowed + ")";
	}

	@Override
	protected String countSql() {
		return "select count(*) from \"" + table + "\" t where " + predicate();
	}

	@Override
	protected String sampleSql() {
		// search_document has no uuid; its identity is (entity_type, entity_uuid).
		String id = "search_document".equals(table) ? "t.\"entity_uuid\"" : "t.\"uuid\"";
		return "select " + id + ", t.\"" + column + "\" from \"" + table + "\" t where " + predicate();
	}
}
