package io.metaloom.loom.db.model.perm;

/**
 * The permissions a role can carry. A permission is checked in the endpoint services / GraphQL
 * wirings before the request reaches a DAO.
 *
 * <p>Every constant carries an audit comment, taken on 2026-07-26:</p>
 *
 * <ul>
 * <li><code>doc</code> — whether the permission is <b>documented</b>, i.e. has a description under
 * <code>admin.roles.permission.&lt;NAME&gt;</code> in <code>loom-ui/src/i18n/locales/{en,de}.json</code>.
 * That text is what the ACL matrix shows below the permission name, so a missing entry leaves an
 * admin guessing what a permission actually grants.</li>
 * <li><code>ui</code> — whether the permission is offered by the ACL matrix
 * (<code>PERMISSION_GROUPS</code> in <code>loom-ui/src/features/admin/AdminArea.tsx</code>).
 * <code>ui:no</code> means it can only be granted through the REST API, never from the admin area.</li>
 * <li><code>test</code> — which test covers it: either a test that names the constant, or the
 * resource's endpoint test, which inherits the "permissionless user gets 403" cases of
 * <code>AbstractCRUDEndpointTest</code>. Those generic cases cover create / read / list / delete
 * only — <b>there is no RBAC case for update</b>, which is why every UPDATE_* below reads
 * <code>test:none</code> unless a test names it explicitly.</li>
 * </ul>
 *
 * <p><code>[unused: no code checks it]</code> marks a permission that no endpoint, service or wiring
 * currently reads — it can be granted but changes nothing.</p>
 *
 * <p>The comments are a snapshot, not a contract: refresh them when permissions, the ACL matrix or
 * the tests change.</p>
 */
public enum Permission {

	// Annotation
	CREATE_ANNOTATION,        // doc:yes  ui:yes test:AnnotationEndpointTest (403 cases)
	READ_ANNOTATION,          // doc:yes  ui:yes test:AnnotationEndpointTest (403 cases)
	DELETE_ANNOTATION,        // doc:yes  ui:yes test:AnnotationEndpointTest (403 cases)
	UPDATE_ANNOTATION,        // doc:yes  ui:yes test:none

	// Asset
	CREATE_ASSET,             // doc:yes  ui:yes test:VertxPermTest, PermissionDaoTest, +2
	READ_ASSET,               // doc:yes  ui:yes test:AgentLoopTest, AclCascadeTest, +6
	DELETE_ASSET,             // doc:yes  ui:yes test:AssetEndpointTest (403 cases)
	UPDATE_ASSET,             // doc:yes  ui:yes test:none

	// Asset Binary
	CREATE_ASSET_BINARY,      // doc:yes  ui:no  test:AssetBinaryEndpointTest (403 cases)
	READ_ASSET_BINARY,        // doc:yes  ui:no  test:AssetBinaryEndpointTest (403 cases)
	DELETE_ASSET_BINARY,      // doc:yes  ui:no  test:AssetBinaryEndpointTest (403 cases)
	UPDATE_ASSET_BINARY,      // doc:yes  ui:no  test:none

	// Asset Location (legacy)
	CREATE_ASSET_LOCATION,    // doc:yes  ui:yes test:none  [unused: no code checks it]
	READ_ASSET_LOCATION,      // doc:yes  ui:yes test:AssetGraphQLTest, LoomGraphQLProviderTest
	DELETE_ASSET_LOCATION,    // doc:yes  ui:yes test:none  [unused: no code checks it]
	UPDATE_ASSET_LOCATION,    // doc:yes  ui:yes test:none  [unused: no code checks it]

	// Attachment
	CREATE_ATTACHMENT,        // doc:yes  ui:yes test:AttachmentEndpointTest (403 cases)
	READ_ATTACHMENT,          // doc:yes  ui:yes test:AttachmentEndpointTest (403 cases)
	DELETE_ATTACHMENT,        // doc:yes  ui:yes test:AttachmentEndpointTest (403 cases)
	UPDATE_ATTACHMENT,        // doc:yes  ui:yes test:none

	// User
	CREATE_USER,              // doc:yes  ui:yes test:UserEndpointTest (403 cases)
	READ_USER,                // doc:yes  ui:yes test:UserGraphQLTest, RoleGraphQLTest, +6
	DELETE_USER,              // doc:yes  ui:yes test:UserEndpointTest (403 cases)
	UPDATE_USER,              // doc:yes  ui:yes test:none

	// Role
	CREATE_ROLE,              // doc:yes  ui:yes test:RoleEndpointTest (403 cases)
	READ_ROLE,                // doc:yes  ui:yes test:RoleGraphQLTest
	DELETE_ROLE,              // doc:yes  ui:yes test:RoleEndpointTest (403 cases)
	UPDATE_ROLE,              // doc:yes  ui:yes test:none

	// Group
	CREATE_GROUP,             // doc:yes  ui:yes test:GroupEndpointTest (403 cases)
	READ_GROUP,               // doc:yes  ui:yes test:UserGraphQLTest, GroupGraphQLTest
	DELETE_GROUP,             // doc:yes  ui:yes test:GroupEndpointTest (403 cases)
	UPDATE_GROUP,             // doc:yes  ui:yes test:none

	// Space
	CREATE_SPACE,             // doc:yes  ui:no  test:SpaceEndpointTest (403 cases)
	READ_SPACE,               // doc:yes  ui:no  test:SpaceEndpointTest (403 cases)
	DELETE_SPACE,             // doc:yes  ui:no  test:SpaceEndpointTest (403 cases)
	UPDATE_SPACE,             // doc:yes  ui:no  test:none

	// Cluster
	CREATE_CLUSTER,           // doc:yes  ui:yes test:ClusterEndpointTest (403 cases)
	READ_CLUSTER,             // doc:yes  ui:yes test:ClusterEndpointTest (403 cases)
	DELETE_CLUSTER,           // doc:yes  ui:yes test:ClusterEndpointTest (403 cases)
	UPDATE_CLUSTER,           // doc:yes  ui:yes test:none

	// Collection
	CREATE_COLLECTION,        // doc:yes  ui:yes test:none
	READ_COLLECTION,          // doc:yes  ui:yes test:none
	DELETE_COLLECTION,        // doc:yes  ui:yes test:none
	UPDATE_COLLECTION,        // doc:yes  ui:yes test:none

	// Comment
	CREATE_COMMENT,           // doc:yes  ui:yes test:none
	READ_COMMENT,             // doc:yes  ui:yes test:none
	DELETE_COMMENT,           // doc:yes  ui:yes test:none
	UPDATE_COMMENT,           // doc:yes  ui:yes test:none

	// Embedding
	CREATE_EMBEDDING,         // doc:yes  ui:yes test:EmbeddingEndpointTest (403 cases)
	READ_EMBEDDING,           // doc:yes  ui:yes test:EmbeddingEndpointTest (403 cases)
	DELETE_EMBEDDING,         // doc:yes  ui:yes test:EmbeddingEndpointTest (403 cases)
	UPDATE_EMBEDDING,         // doc:yes  ui:yes test:none

	// Reaction
	CREATE_REACTION,          // doc:yes  ui:yes test:none
	READ_REACTION,            // doc:yes  ui:yes test:none
	DELETE_REACTION,          // doc:yes  ui:yes test:none
	UPDATE_REACTION,          // doc:yes  ui:yes test:none

	// Task
	CREATE_TASK,              // doc:yes  ui:yes test:TaskEndpointTest (403 cases)
	READ_TASK,                // doc:yes  ui:yes test:TaskEndpointTest (403 cases)
	DELETE_TASK,              // doc:yes  ui:yes test:TaskEndpointTest (403 cases)
	UPDATE_TASK,              // doc:yes  ui:yes test:none

	// Tag
	CREATE_TAG,               // doc:yes  ui:yes test:TagEndpointTest (403 cases)
	READ_TAG,                 // doc:yes  ui:yes test:TagEndpointTest (403 cases)
	DELETE_TAG,               // doc:yes  ui:yes test:TagEndpointTest (403 cases)
	UPDATE_TAG,               // doc:yes  ui:yes test:none
	TAG_ASSET,                // doc:yes  ui:yes test:TagAssetEndpointTest (403 cases)
	UNTAG_ASSET,              // doc:yes  ui:yes test:TagAssetEndpointTest (403 cases)

	// Token
	CREATE_TOKEN,             // doc:yes  ui:yes test:none
	READ_TOKEN,               // doc:yes  ui:yes test:none
	DELETE_TOKEN,             // doc:yes  ui:yes test:none
	UPDATE_TOKEN,             // doc:yes  ui:yes test:none

	// Library
	CREATE_LIBRARY,           // doc:yes  ui:yes test:LibraryEndpointTest (403 cases)
	READ_LIBRARY,             // doc:yes  ui:yes test:LibraryEndpointTest (403 cases)
	DELETE_LIBRARY,           // doc:yes  ui:yes test:LibraryEndpointTest (403 cases)
	UPDATE_LIBRARY,           // doc:yes  ui:yes test:none

	// Pipeline
	CREATE_PIPELINE,          // doc:yes  ui:yes test:none
	READ_PIPELINE,            // doc:yes  ui:yes test:PipelineGraphQLTest
	DELETE_PIPELINE,          // doc:yes  ui:yes test:none
	UPDATE_PIPELINE,          // doc:yes  ui:yes test:none
	READ_PIPELINE_VERSION,    // doc:yes  ui:no  test:PipelineGraphQLTest
	RESTORE_PIPELINE_VERSION, // doc:yes  ui:no  test:none
	CREATE_PIPELINE_RUN,      // doc:yes  ui:no  test:none  [unused: no code checks it]
	READ_PIPELINE_RUN,        // doc:yes  ui:no  test:PipelineGraphQLTest, PipelineRunItemEndpointTest, +1
	UPDATE_PIPELINE_RUN,      // doc:yes  ui:no  test:PipelineRunPauseEndpointTest, PipelineRunCancelEndpointTest
	DELETE_PIPELINE_RUN,      // doc:yes  ui:no  test:none  [unused: no code checks it]

	// Asset Pool
	CREATE_ASSET_POOL,        // doc:yes  ui:yes test:AssetPoolEndpointTest (403 cases)
	READ_ASSET_POOL,          // doc:yes  ui:yes test:AssetPoolEndpointTest (403 cases)
	DELETE_ASSET_POOL,        // doc:yes  ui:yes test:AssetPoolEndpointTest (403 cases)
	UPDATE_ASSET_POOL,        // doc:yes  ui:yes test:none

	// Blacklist
	CREATE_BLACKLIST,         // doc:yes  ui:no  test:none
	READ_BLACKLIST,           // doc:yes  ui:no  test:none
	DELETE_BLACKLIST,         // doc:yes  ui:no  test:none
	UPDATE_BLACKLIST,         // doc:yes  ui:no  test:none

	// Person
	CREATE_PERSON,            // doc:yes  ui:no  test:PersonEndpointTest (403 cases)
	READ_PERSON,              // doc:yes  ui:no  test:PersonEndpointTest (403 cases)
	DELETE_PERSON,            // doc:yes  ui:no  test:PersonEndpointTest (403 cases)
	UPDATE_PERSON,            // doc:yes  ui:no  test:none

	// Detection
	CREATE_DETECTION,         // doc:yes  ui:no  test:DetectionEndpointTest (403 cases)
	READ_DETECTION,           // doc:yes  ui:no  test:DetectionEndpointTest (403 cases)
	DELETE_DETECTION,         // doc:yes  ui:no  test:DetectionEndpointTest (403 cases)
	UPDATE_DETECTION,         // doc:yes  ui:no  test:none

	// Chat
	CREATE_CHAT,              // doc:yes  ui:no  test:ChatEndpointTest (403 cases)
	READ_CHAT,                // doc:yes  ui:no  test:ChatEndpointTest (403 cases)
	DELETE_CHAT,              // doc:yes  ui:no  test:ChatEndpointTest (403 cases)
	UPDATE_CHAT,              // doc:yes  ui:no  test:none

	// Skill
	CREATE_SKILL,             // doc:yes  ui:no  test:SkillEndpointTest
	READ_SKILL,               // doc:yes  ui:no  test:SkillGraphQLTest, SkillEndpointTest
	DELETE_SKILL,             // doc:yes  ui:no  test:SkillEndpointTest
	UPDATE_SKILL,             // doc:yes  ui:no  test:SkillEndpointTest

	// Skill Version
	READ_SKILL_VERSION,       // doc:yes  ui:no  test:SkillGraphQLTest, SkillEndpointTest
	RESTORE_SKILL_VERSION,    // doc:yes  ui:no  test:SkillEndpointTest

	// Chat Session (publishable session record + shared session library)
	CREATE_CHAT_SESSION,      // doc:yes  ui:no  test:none
	READ_CHAT_SESSION,        // doc:yes  ui:no  test:none
	DELETE_CHAT_SESSION,      // doc:yes  ui:no  test:none
	UPDATE_CHAT_SESSION,      // doc:yes  ui:no  test:none

	// Agent Memory (scoped markdown notes the chat agent reads and writes)
	CREATE_MEMORY,            // doc:yes  ui:no  test:MemoryEndpointTest, MemoryDenyRuleEndpointTest
	READ_MEMORY,              // doc:yes  ui:no  test:MemoryGraphQLTest, MemoryEndpointTest, +1
	DELETE_MEMORY,            // doc:yes  ui:no  test:MemoryEndpointTest, MemoryDenyRuleEndpointTest
	UPDATE_MEMORY,            // doc:yes  ui:no  test:MemoryEndpointTest, MemoryDenyRuleEndpointTest

	// Agent Memory Denylist (instance-wide patterns that must never be stored)
	CREATE_MEMORY_DENY_RULE,  // doc:yes  ui:no  test:MemoryDenyRuleEndpointTest
	READ_MEMORY_DENY_RULE,    // doc:yes  ui:no  test:MemoryGraphQLTest, MemoryDenyRuleEndpointTest
	DELETE_MEMORY_DENY_RULE,  // doc:yes  ui:no  test:MemoryDenyRuleEndpointTest
	UPDATE_MEMORY_DENY_RULE,  // doc:yes  ui:no  test:MemoryDenyRuleEndpointTest

	// Cortex Instance (registered processor worker)
	MANAGE_CORTEX_INSTANCE,   // doc:yes  ui:no  test:none
	READ_CORTEX_INSTANCE,     // doc:yes  ui:no  test:none  [unused: no code checks it]

	// Search. Wholesale gate on /api/v1/search/*. The endpoint additionally narrows the requested
	// entity types against the READ_* permissions above and drops the ones the caller may not see,
	// because search is cross-entity by construction.
	READ_SEARCH,              // doc:yes  ui:no  test:SearchEndpointTest

	// Deduplication review. Gate on /api/v1/dedup-groups and /api/v1/assets/:uuid/dedup-groups.
	// The discovery node creates PENDING groups (CREATE_DEDUP); a reviewer confirms/denies
	// (UPDATE_DEDUP); the apply node reads CONFIRMED groups (READ_DEDUP).
	CREATE_DEDUP,             // doc:yes  ui:no  test:DedupGroupEndpointTest (403 cases)
	READ_DEDUP,               // doc:yes  ui:no  test:DedupGroupEndpointTest (403 cases)
	UPDATE_DEDUP,             // doc:yes  ui:no  test:DedupGroupEndpointTest
	DELETE_DEDUP,             // doc:yes  ui:no  test:DedupGroupEndpointTest (403 cases)

	// The per-user notification inbox. Gate on /api/v1/notifications.
	// There is deliberately no CREATE_NOTIFICATION: notifications are dispatched server-side
	// by NotificationDispatcher and have no REST create route, so the constant would be dead.
	// Rows are recipient-scoped on top of these - holding READ_NOTIFICATION lets you read
	// YOUR inbox, never anybody else's (NotificationEndpointService 404s a foreign row).
	READ_NOTIFICATION,        // doc:yes  ui:yes test:NotificationEndpointTest (403 cases)
	UPDATE_NOTIFICATION,      // doc:yes  ui:yes test:NotificationEndpointTest (403 cases)
	DELETE_NOTIFICATION;      // doc:yes  ui:yes test:NotificationEndpointTest (403 cases)

}
