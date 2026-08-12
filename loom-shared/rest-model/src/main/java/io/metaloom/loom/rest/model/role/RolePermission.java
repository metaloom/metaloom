package io.metaloom.loom.rest.model.role;

/**
 * The permissions a role can carry, as expressed over the REST API.
 *
 * <p>
 * This enum is a <b>literal mirror</b> of <code>io.metaloom.loom.db.model.perm.Permission</code> in the <code>loom-db-api</code> module. The REST model
 * module must not depend on the database module, so the constants are duplicated rather than shared. Both enums are kept in lock-step by
 * <code>RolePermissionParityTest</code> (module <code>loom-service-rest</code>), which fails as soon as one side gains, loses or renames a constant.
 * </p>
 *
 * <p>
 * When adding a permission, add it to <code>Permission</code> first (it is the source of truth and carries the per-constant audit comments), then
 * mirror it here, then add the matching Postgres <code>loom_permission</code> value in a Flyway migration.
 * </p>
 *
 * <p>
 * Permissions are <b>global</b>, not per-object: <code>READ_ASSET</code> grants read access to every asset. There is no resource scoping.
 * </p>
 */
public enum RolePermission {

	// Annotation
	CREATE_ANNOTATION,
	READ_ANNOTATION,
	DELETE_ANNOTATION,
	UPDATE_ANNOTATION,

	// Asset
	CREATE_ASSET,
	READ_ASSET,
	DELETE_ASSET,
	UPDATE_ASSET,

	// Asset Binary
	CREATE_ASSET_BINARY,
	READ_ASSET_BINARY,
	DELETE_ASSET_BINARY,
	UPDATE_ASSET_BINARY,

	// Asset Location (legacy)
	CREATE_ASSET_LOCATION,
	READ_ASSET_LOCATION,
	DELETE_ASSET_LOCATION,
	UPDATE_ASSET_LOCATION,

	// Attachment
	CREATE_ATTACHMENT,
	READ_ATTACHMENT,
	DELETE_ATTACHMENT,
	UPDATE_ATTACHMENT,

	// User
	CREATE_USER,
	READ_USER,
	DELETE_USER,
	UPDATE_USER,

	// Role
	CREATE_ROLE,
	READ_ROLE,
	DELETE_ROLE,
	UPDATE_ROLE,

	// Group
	CREATE_GROUP,
	READ_GROUP,
	DELETE_GROUP,
	UPDATE_GROUP,

	// Space
	CREATE_SPACE,
	READ_SPACE,
	DELETE_SPACE,
	UPDATE_SPACE,

	// Cluster
	CREATE_CLUSTER,
	READ_CLUSTER,
	DELETE_CLUSTER,
	UPDATE_CLUSTER,

	// Collection
	CREATE_COLLECTION,
	READ_COLLECTION,
	DELETE_COLLECTION,
	UPDATE_COLLECTION,

	// Remix
	CREATE_REMIX,
	READ_REMIX,
	DELETE_REMIX,
	UPDATE_REMIX,

	// Share link. Mirrored late: V2.96 added these to the database enum and to Permission without
	// mirroring them here, which left them ungrantable over the REST API and RolePermissionParityTest red.
	CREATE_SHARE,
	READ_SHARE,
	DELETE_SHARE,
	UPDATE_SHARE,

	// Comment
	CREATE_COMMENT,
	READ_COMMENT,
	DELETE_COMMENT,
	UPDATE_COMMENT,

	// Embedding
	CREATE_EMBEDDING,
	READ_EMBEDDING,
	DELETE_EMBEDDING,
	UPDATE_EMBEDDING,

	// Reaction
	CREATE_REACTION,
	READ_REACTION,
	DELETE_REACTION,
	UPDATE_REACTION,

	// Task
	CREATE_TASK,
	READ_TASK,
	DELETE_TASK,
	UPDATE_TASK,

	// Tag
	CREATE_TAG,
	READ_TAG,
	DELETE_TAG,
	UPDATE_TAG,
	TAG_ASSET,
	UNTAG_ASSET,

	// Token
	CREATE_TOKEN,
	READ_TOKEN,
	DELETE_TOKEN,
	UPDATE_TOKEN,

	// Library
	CREATE_LIBRARY,
	READ_LIBRARY,
	DELETE_LIBRARY,
	UPDATE_LIBRARY,

	// Pipeline
	CREATE_PIPELINE,
	READ_PIPELINE,
	DELETE_PIPELINE,
	UPDATE_PIPELINE,
	READ_PIPELINE_VERSION,
	RESTORE_PIPELINE_VERSION,
	CREATE_PIPELINE_RUN,
	READ_PIPELINE_RUN,
	UPDATE_PIPELINE_RUN,
	DELETE_PIPELINE_RUN,
	CREATE_MCP_PIPELINE,
	UPDATE_MCP_PIPELINE,
	VALIDATE_MCP_PIPELINE,
	EXECUTE_MCP_NODE,

	// Asset Pool
	CREATE_ASSET_POOL,
	READ_ASSET_POOL,
	DELETE_ASSET_POOL,
	UPDATE_ASSET_POOL,

	// Blacklist
	CREATE_BLACKLIST,
	READ_BLACKLIST,
	DELETE_BLACKLIST,
	UPDATE_BLACKLIST,

	// Person
	CREATE_PERSON,
	READ_PERSON,
	DELETE_PERSON,
	UPDATE_PERSON,

	// Detection
	CREATE_DETECTION,
	READ_DETECTION,
	DELETE_DETECTION,
	UPDATE_DETECTION,

	// Chat
	CREATE_CHAT,
	READ_CHAT,
	DELETE_CHAT,
	UPDATE_CHAT,

	// Skill
	CREATE_SKILL,
	READ_SKILL,
	DELETE_SKILL,
	UPDATE_SKILL,

	// Skill Version
	READ_SKILL_VERSION,
	RESTORE_SKILL_VERSION,

	// Chat Session (publishable session record + shared session library)
	CREATE_CHAT_SESSION,
	READ_CHAT_SESSION,
	DELETE_CHAT_SESSION,
	UPDATE_CHAT_SESSION,

	// Agent Memory (scoped markdown notes the chat agent reads and writes)
	CREATE_MEMORY,
	READ_MEMORY,
	DELETE_MEMORY,
	UPDATE_MEMORY,

	// Agent Memory Denylist (instance-wide patterns that must never be stored)
	CREATE_MEMORY_DENY_RULE,
	READ_MEMORY_DENY_RULE,
	DELETE_MEMORY_DENY_RULE,
	UPDATE_MEMORY_DENY_RULE,

	// Cortex Instance (registered processor worker)
	MANAGE_CORTEX_INSTANCE,
	READ_CORTEX_INSTANCE,

	// Metrics. Gate on GET /api/v1/metrics, the JSON read of the loom_* catalog on the app REST
	// port. The Prometheus scrape on the monitoring port is network-gated and never sees this.
	READ_METRIC,

	// Search. Wholesale gate on /api/v1/search/*. The endpoint additionally narrows the requested
	// entity types against the READ_* permissions above and drops the ones the caller may not see,
	// because search is cross-entity by construction.
	READ_SEARCH,

	// Search index operation. Gate on /api/v1/search-indices. Reading reports sizes, backlogs and
	// the producing embedding model; managing runs a reindex, a delta sync or a drop.
	READ_SEARCH_INDEX,
	MANAGE_SEARCH_INDEX,

	// Deduplication review. Gate on /api/v1/dedup-groups and /api/v1/assets/:uuid/dedup-groups.
	// The discovery node creates PENDING groups (CREATE_DEDUP); a reviewer confirms/denies
	// (UPDATE_DEDUP); the apply node reads CONFIRMED groups (READ_DEDUP).
	CREATE_DEDUP,
	READ_DEDUP,
	UPDATE_DEDUP,
	DELETE_DEDUP,

	// The per-user notification inbox. Gate on /api/v1/notifications.
	// No CREATE_NOTIFICATION - notifications are dispatched server-side, never posted.
	READ_NOTIFICATION,
	UPDATE_NOTIFICATION,
	DELETE_NOTIFICATION,

	// The database integrity report. Gate on /api/v1/db-integrity.
	// Read only - the report is computed per request, so there is nothing to create, edit or delete.
	READ_DB_INTEGRITY,

	// The storage report. Gate on /api/v1/storage and /api/v1/storage/backends.
	// Read only, for the same reason as READ_DB_INTEGRITY. Separate from READ_ASSET_POOL: seeing how
	// full a pool is and being able to repoint it at another bucket are different authorities.
	READ_STORAGE;

}
