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

	// Remix. A remix is a named group of assets that are versions of one another. Kept separate from the
	// asset permissions on purpose: grouping is curation, and a curator may build and rename groups
	// without being allowed to mutate the underlying assets. Reading a remix's members additionally
	// requires READ_ASSET, so a remix cannot be a side channel around asset visibility.
	CREATE_REMIX,             // doc:yes  ui:yes test:RemixEndpointTest (403 cases)
	READ_REMIX,               // doc:yes  ui:yes test:RemixEndpointTest (403 cases)
	DELETE_REMIX,             // doc:yes  ui:yes test:RemixEndpointTest (403 cases)
	UPDATE_REMIX,             // doc:yes  ui:yes test:RemixMemberEndpointTest (403 cases)

	// Share link. Governs the share ROW - creating a link, changing its expiry or password, revoking it.
	// The visitor who opens the link holds no permission at all and is never resolved to a user; what they
	// may do is decided entirely from the share row in ShareAccessService. There is deliberately no
	// VIEW_SHARE: a constant no code path can check would advertise an enforcement point that is not there.
	CREATE_SHARE,             // doc:yes  ui:yes test:ShareLinkEndpointTest (403 cases)
	READ_SHARE,               // doc:yes  ui:yes test:ShareLinkEndpointTest (403 cases)
	DELETE_SHARE,             // doc:yes  ui:yes test:ShareLinkEndpointTest (403 cases)
	UPDATE_SHARE,             // doc:yes  ui:yes test:ShareLinkEndpointTest

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
	DELETE_PIPELINE,          // doc:yes  ui:yes test:PipelineRunDispatchEndpointTest
	UPDATE_PIPELINE,          // doc:yes  ui:yes test:none
	READ_PIPELINE_VERSION,    // doc:yes  ui:no  test:PipelineGraphQLTest, PipelineVersionEndpointTest
	RESTORE_PIPELINE_VERSION, // doc:yes  ui:no  test:PipelineVersionEndpointTest
	CREATE_PIPELINE_RUN,      // doc:yes  ui:no  test:none  [unused: no code checks it]
	READ_PIPELINE_RUN,        // doc:yes  ui:no  test:PipelineGraphQLTest, PipelineRunItemEndpointTest, +1
	UPDATE_PIPELINE_RUN,      // doc:yes  ui:no  test:PipelineRunPauseEndpointTest, PipelineRunCancelEndpointTest
	DELETE_PIPELINE_RUN,      // doc:yes  ui:no  test:none  [unused: no code checks it]

	// Pipeline authoring through the MCP server — the chat agent and any external MCP client.
	// Deliberately separate from the *_PIPELINE quad above: letting an agent write a pipeline is a
	// different trust decision from letting a person draw one in the editor, and an admin has to be
	// able to grant one without the other. The authoring tools require BOTH — the base permission
	// says you may change pipelines at all, these say you may do it through an agent — so granting
	// one of these alone can never widen what a user can do.
	// VALIDATE_MCP_PIPELINE gates the dry run, which reads the node registry and the fleet but writes
	// nothing; it is separate so an operator can let the agent design and check a graph while
	// withholding the ability to store one.
	CREATE_MCP_PIPELINE,      // doc:yes  ui:yes test:MCPPipelineAuthoringTest
	UPDATE_MCP_PIPELINE,      // doc:yes  ui:yes test:MCPPipelineAuthoringTest
	VALIDATE_MCP_PIPELINE,    // doc:yes  ui:yes test:MCPPipelineAuthoringTest

	// Ad-hoc node execution — POST /api/v1/node-runs and the MCP execution tools. Separate from the
	// authoring trio above for the same reason those are separate from the CRUD quad: designing a
	// pipeline and spending worker time on one are different trust decisions, and this is the only
	// permission that lets a caller occupy the GPU fleet. The tools require it in addition to
	// READ_ASSET, so granting it alone can never widen what a user may read.
	EXECUTE_MCP_NODE,         // doc:yes  ui:yes test:NodeRunEndpointTest, MCPNodeExecutionTest

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
	CREATE_DETECTION,         // doc:yes  ui:yes  test:DetectionEndpointTest (403 cases)
	READ_DETECTION,           // doc:yes  ui:yes  test:DetectionEndpointTest (403 cases)
	DELETE_DETECTION,         // doc:yes  ui:yes  test:DetectionEndpointTest (403 cases)
	UPDATE_DETECTION,         // doc:yes  ui:yes  test:DetectionEndpointTest#testReviewIsForbiddenWithoutUpdatePermission

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
	READ_CORTEX_INSTANCE,     // doc:yes  ui:no  test:none  (NodeDescriptorEndpoint.mayNameWorkers)

	// Metrics. Gate on GET /api/v1/metrics, the JSON read of the loom_* catalog on the app REST
	// port. The Prometheus scrape on the monitoring port is network-gated and never sees this.
	READ_METRIC,              // doc:yes  ui:yes test:MetricsEndpointTest (403 case)

	// Search. Wholesale gate on /api/v1/search/*. The endpoint additionally narrows the requested
	// entity types against the READ_* permissions above and drops the ones the caller may not see,
	// because search is cross-entity by construction.
	READ_SEARCH,              // doc:yes  ui:no  test:SearchEndpointTest

	// Search index operation. Gate on /api/v1/search-indices. Split from READ_SEARCH because
	// querying the index and operating it are different authorities: these cover the lexical index,
	// the embedding vector spaces and the fingerprint index, none of which READ_SEARCH reaches.
	// MANAGE also covers the destructive actions - a drop empties an index until a reindex refills
	// it, though no source data is lost because every index here is a rebuildable cache.
	READ_SEARCH_INDEX,        // doc:yes  ui:yes test:SearchIndexEndpointTest (403 cases)
	MANAGE_SEARCH_INDEX,      // doc:yes  ui:yes test:SearchIndexEndpointTest (403 cases)

	// Deduplication review. Gate on /api/v1/dedup-groups and /api/v1/assets/:uuid/dedup-groups.
	// The discovery node creates PENDING groups (CREATE_DEDUP); a reviewer confirms/denies
	// (UPDATE_DEDUP); the apply node reads CONFIRMED groups (READ_DEDUP).
	CREATE_DEDUP,             // doc:yes  ui:yes test:DedupGroupEndpointTest (403 cases)
	READ_DEDUP,               // doc:yes  ui:yes test:DedupGroupEndpointTest (403 cases)
	UPDATE_DEDUP,             // doc:yes  ui:yes test:DedupGroupEndpointTest
	DELETE_DEDUP,             // doc:yes  ui:yes test:DedupGroupEndpointTest (403 cases)

	// The per-user notification inbox. Gate on /api/v1/notifications.
	// There is deliberately no CREATE_NOTIFICATION: notifications are dispatched server-side
	// by NotificationDispatcher and have no REST create route, so the constant would be dead.
	// Rows are recipient-scoped on top of these - holding READ_NOTIFICATION lets you read
	// YOUR inbox, never anybody else's (NotificationEndpointService 404s a foreign row).
	READ_NOTIFICATION,        // doc:yes  ui:yes test:NotificationEndpointTest (403 cases)
	UPDATE_NOTIFICATION,      // doc:yes  ui:yes test:NotificationEndpointTest (403 cases)
	DELETE_NOTIFICATION,      // doc:yes  ui:yes test:NotificationEndpointTest (403 cases)

	// The database integrity report. Gate on /api/v1/db-integrity.
	// Read only, and one constant rather than four: the checks are computed from the database on
	// every request, so there is nothing to create, edit or delete. It is a separate grant from
	// READ_METRIC because the report names uuids of rows that are wrong, which is closer to reading
	// the catalogue than to reading a counter.
	READ_DB_INTEGRITY,        // doc:yes  ui:yes test:DbIntegrityEndpointTest (403 cases)

	// The storage report. Gate on /api/v1/storage and /api/v1/storage/backends.
	// Read only, and one constant rather than four, for the same reason as READ_DB_INTEGRITY: the
	// report is computed on every request and owns no rows. Separate from READ_ASSET_POOL because
	// seeing how full a pool is and being able to repoint it at another bucket are different
	// authorities, and an operator on call needs only the first.
	READ_STORAGE,             // doc:yes  ui:yes test:StorageEndpointTest (403 cases)

	// Problem reports submitted from the UI. Gate on /api/v1/failure-reports.
	// There is deliberately no CREATE_FAILURE_REPORT: submitting one needs authentication and
	// nothing else, because a permission to report a failure would, on any upgraded installation
	// where it went ungranted, turn the product's one response to a breakage into a 403. Reading
	// the inbox is a separate authority because a report may carry a screenshot of assets its
	// reader is not otherwise cleared to see. See V2.106.
	READ_FAILURE_REPORT,      // doc:yes  ui:yes test:FailureReportEndpointTest (403 cases)
	UPDATE_FAILURE_REPORT,    // doc:yes  ui:yes test:FailureReportEndpointTest (403 cases)
	DELETE_FAILURE_REPORT;    // doc:yes  ui:yes test:FailureReportEndpointTest (403 cases)

}
