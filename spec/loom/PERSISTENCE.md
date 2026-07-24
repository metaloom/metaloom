# Loom Persistence Layer

This document describes the MetaLoom persistence architecture for AI coding agents working on the codebase. It covers the jOOQ-based SQL implementation, DAO hierarchy, Flyway migrations, test infrastructure, and key patterns to follow when adding new entities.

## Architecture Overview

The persistence layer is split across multiple Maven modules under `loom/db/`:

| Module | Purpose |
|---|---|
| `loom-db-api` | DAO interfaces, model interfaces, `Element`/`CUDElement`/`CRUDDao` abstractions, `Page`, `DaoCollection`, `DaoProvider` |
| `loom-db-api-test` | Shared test infrastructure: `CRUDDaoTestcases`, `DatabaseTest`, `FixtureElementProvider`, `TestValues` |
| `loom-db-jooq` | jOOQ-based implementation: `AbstractJooqDao`, `*DaoImpl`, generated table classes, converters, filters |
| `loom-db-jooq-gen` | jOOQ code generation strategy (`LoomJooqStrategy` - prefixes all generated classes with `Jooq`) |
| `loom-db-flyway` | Flyway SQL migration scripts (`V1__`, `V2.*__`) |
| `loom-db-memory` | In-memory `AbstractMemDao` implementation (used for fast tests) |

All modules use the parent `loom-db` pom. The jOOQ module depends on `loom-db-api` and `loom-db-flyway`.

## Module Layout

```
loom/db/
  api/    - Interfaces (DAO, model, Element, CRUDDao, Page, DaoCollection, DaoProvider)
  api-test/ - Test mixins (CRUDDaoTestcases, DatabaseTest, FixtureElementProvider)
  jooq/   - jOOQ implementation (AbstractJooqDao, *DaoImpl, generated tables, converters)
  jooq-gen/ - jOOQ codegen strategy (prefixes table classes with "Jooq")
  flyway/ - SQL migration scripts
  memory/ - In-memory DAO implementation (AbstractMemDao)
```

## Element Hierarchy

```
Element<T>              - base interface: getUuid(), setUuid(), self()
  CUDElement<T>         - adds creatorUuid, editorUuid, created, edited, meta (JsonObject)
    AbstractElement     - base abstract class (no fields)
    AbstractEditableElement - fields for uuid, meta, creatorUuid, editorUuid, created, edited
```

- `Element<T extends Element<T>>` is the root interface with a self-referential generic for fluent API.
- `CUDElement<T>` extends `Element<T>` and `MetaElement<T>`, adding audit fields (creator, editor, created, edited) and a `JsonObject meta` field.
- `AbstractEditableElement` is the jOOQ-side abstract class that stores these fields. jOOQ auto-maps columns to the POJO via reflection.

## CRUDDao Interface

`CRUDDao<T extends Element<T>>` is the central DAO contract. Every DAO extends it.

```java
public interface CRUDDao<T extends Element<T>> extends Dao {
    void delete(UUID id);          // delete by UUID
    T update(T element);           // update an existing element
    T load(UUID id);               // load by UUID
    void store(T element);         // insert a new element (UUID auto-generated)
    void storeBatch(List<T> elements); // batch insert (default: loops store())
    Page<T> loadPage(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);
    Stream<? extends T> findAll(); // stream all rows
    // default void delete(T element) -> delegates to delete(element.getUuid())
}
```

The `Dao` base interface adds:
- `String getTypeName()` - human-readable name for the DAO (e.g. "Users", "Assets")
- `void clear()` - delete all rows
- `long count()` - count all rows

### Key Behaviors

- **store()**: Inserts a new record. If `uuid` is null, jOOQ lets the DB generate it via `uuid_generate_v4()` and returns the generated UUID. The element's UUID is set from the returning result.
- **storeBatch()**: Uses `ctx().batchInsert(records).execute()`. After insert, attempts to read back generated UUIDs from the records.
- **update()**: Uses jOOQ `UpdatableRecord` - creates a record from the element and calls `ctx().executeUpdate(reco)`.
- **delete()**: Deletes by primary key (UUID). Some DAOs override to add soft-delete filters (e.g. `UserDaoImpl` filters `deleted = false` on load).
- **loadPage()**: Keyset pagination using `seek(fromId)` with configurable page size, filters, and sort direction. Sorting is on a `SortKey` field.
- **findAll()**: Returns a `Stream` for lazy iteration over all rows.

## jOOQ Implementation

### AbstractJooqDao

`AbstractJooqDao<T extends Element<T>>` implements `CRUDDao<T>` and `JooqDao`. Each concrete DAO extends it.

Key methods to override:
- `getTable()` - return the jOOQ generated table (e.g. `JooqUser.USER`)
- `getPojoClass()` - return the POJO implementation class (e.g. `UserImpl.class`)
- `getTypeName()` - return a human-readable name (e.g. "Users")
- `applyFilter()` - override to add entity-specific filter logic (falls back to UUID filter)

Key helper methods:
- `ctx()` - returns the jOOQ `DSLContext` for building queries
- `getIdField()` - returns the `uuid` field for the table
- `setCreatorEditor(element, userUuid)` - sets creator/editor UUIDs and created/edited timestamps
- `pkSelect(pk)` - builds a condition on the primary key field
- `deleteCrossTableEntry()` - helper for deleting join-table entries

### Concrete DAO Pattern

Each DAO follows this pattern:

1. **Interface** in `loom-db-api` (e.g. `UserDao extends CRUDDao<User>`)
   - Defines entity-specific methods like `createUser()`, `loadByUsername()`
   - Often has `default` methods that delegate to the UUID-based variant

2. **Implementation** in `loom-db-jooq` (e.g. `UserDaoImpl extends AbstractJooqDao<User>`)
   - `@Singleton` with `@Inject DSLContext ctx` constructor
   - Implements `getTable()`, `getPojoClass()`, `getTypeName()`
   - Implements entity-specific query methods
   - Overrides `applyFilter()` for entity-specific filters
   - May override `load()` to add soft-delete or other filters

3. **POJO** in `loom-db-jooq` (e.g. `UserImpl extends AbstractEditableElement<User> implements User`)
   - jOOQ maps columns to fields by name

### Example: PipelineDao

```java
// Interface (loom-db-api)
public interface PipelineDao extends CRUDDao<Pipeline> {
    Pipeline createPipeline(UUID userUuid, String name);
}

// Implementation (loom-db-jooq)
@Singleton
public class PipelineDaoImpl extends AbstractJooqDao<Pipeline> implements PipelineDao {
    @Inject
    public PipelineDaoImpl(DSLContext ctx) { super(ctx); }

    @Override
    protected Table<? extends TableRecord<?>> getTable() {
        return JooqPipeline.PIPELINE;
    }

    @Override
    protected Class<? extends Pipeline> getPojoClass() {
        return PipelineImpl.class;
    }

    @Override
    public Pipeline createPipeline(UUID userUuid, String name) {
        Pipeline pipeline = new PipelineImpl();
        pipeline.setName(name);
        setCreatorEditor(pipeline, userUuid);
        return pipeline;
    }

    @Override
    protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
        FilterKey key = filter.filterKey();
        if (key == LoomFilterKey.NAME) {
            return query.and(PIPELINE.NAME.eq(filter.valueStr()));
        }
        return super.applyFilter(query, filter);
    }
}
```

### jOOQ Code Generation

- The `loom-db-jooq-gen` module contains `LoomJooqStrategy` which prefixes all generated table classes with `Jooq` (e.g. `JooqUser`, `JooqAsset`, `JooqPipeline`).
- Generated sources go to `loom/db/jooq/src/jooq/java/` (separate source folder).
- Code generation is triggered by the `generate` Maven profile in `loom-db-jooq/pom.xml`, which:
  1. Starts a PostgreSQL Testcontainer
  2. Runs Flyway migrations against it
  3. Runs jOOQ codegen against the migrated schema
- The generated table classes (e.g. `JooqChat`, `JooqAsset`, `JooqPipeline`) provide typed `TableField` constants for every column.

### JsonObjectConverter

`loom-db-jooq/converter/JsonObjectConverter.java` converts between jOOQ's `JSONB` type and Vert.x `JsonObject`. This is used for all `meta` columns (jsonb) and any JSONB fields like `chat.messages` or `pipeline.definition`.

### Filter System

- `LoomFilterKey` (in `loom-db-jooq/filter/`) defines filter keys: `USER_USERNAME`, `FILE_SIZE`.
- `Filter` objects carry a `FilterKey`, an operation, and a value.
- `AbstractJooqDao.applyFilter()` handles `UUID` filter by default; each DAO overrides to add entity-specific filters (e.g. `AssetDaoImpl` handles `FILE_SIZE` with `SizeRangeFilterValue`).

## DaoCollection and Dagger DI

`DaoCollection` is the master interface listing all DAOs. `DaoCollectionImpl` is the `@Singleton` implementation that lazily provides every DAO via Dagger `Lazy<>` wrappers.

```java
public interface DaoCollection {
    UserDao userDao();
    AssetDao assetDao();
    PipelineDao pipelineDao();
    AssetPoolDao assetPoolDao();
    PersonDao personDao();
    DetectionDao detectionDao();
    ChatDao chatDao();
    // ... 25+ DAOs total
}
```

`DaoProvider` extends `DaoCollection` and provides default methods that delegate to `daos()`. Both `DatabaseTest` and `FixtureElementProvider` extend `DaoProvider`, giving tests access to all DAOs.

### Adding a New DAO to DaoCollection

1. Add the DAO method to the `DaoCollection` interface
2. Add a `Lazy<YourDao>` field and constructor parameter in `DaoCollectionImpl`
3. Add the `@Override` accessor method in `DaoCollectionImpl`
4. Add a `default` delegate method in `DaoProvider` (if desired)

## Flyway Migrations

Migrations live in `loom/db/flyway/src/main/resources/db/migration/`. They are PostgreSQL-specific SQL scripts.

### Migration History

| Migration | Description |
|---|---|
| `V1__db_setup` | Creates `loom` schema, enables `uuid-ossp` extension for UUID generation |
| `V2.1__add_acl` | User, Token, Role, Group, Permission tables + `loom_permission` enum |
| `V2.2__add_tag` | Tag and tag_user_meta tables |
| `V2.3__add_workflow` | Task table with `task_status` enum |
| `V2.5__add_loom` | Loom metadata table + `loom_events` enum |
| `V2.6__add_vector_config` | Vector config table for custom vector indices |
| `V2.7__add_collection` | Collection and tag_collection tables |
| `V2.8__add_asset` | Asset table (sha512 PK, media metadata, geo, S3 fields) + asset_remix, collection_asset, tag_asset, asset_user_meta, asset_task |
| `V2.9__add_library` | Library, library_asset, library_collection tables |
| `V2.10__add_asset_location` | Asset location (file path, library reference, lock state) |
| `V2.11__add_project` | Project, project_library, project_collection (later renamed to Space) |
| `V2.12__add_embedding` | Embedding (vector data), cluster, tag_cluster, embedding_cluster |
| `V2.13__add_attachment` | Attachment binary, attachment (thumbnails, embedding attachments) |
| `V2.14__add_blacklist` | Blacklist table for blocked assets |
| `V2.15__add_webhook` | Webhook table with event triggers |
| `V2.16__add_annotation` | Annotation (FEEDBACK/TAG/CHAPTER types), annotation_task, annotation_asset, annotation_tag |
| `V2.17__add_social` | Comment, reaction tables |
| `V2.18__add_asset_components` | Extracted component tables: asset_geo_comp, asset_doc_comp, asset_image_comp, asset_video_comp, asset_audio_comp, asset_transcript_comp |
| `V2.19__add_pipeline` | Pipeline table (name, definition JSONB, enabled, priority, dry_run) + pipeline permissions |
| `V2.20__add_asset_pool` | Asset pool (storage pool: filesystem or S3) + pool_uuid on asset_location |
| `V2.21__add_pool_binary_permissions` | Asset binary and asset pool permissions |
| `V2.22__rename_project_permissions_to_space` | Renames PROJECT -> SPACE permissions in the enum |
| `V2.23__add_asset_json_comp` | Generic JSON component table for Cortex processing node output |
| `V2.24__add_asset_pool_free_space` | Adds free_space, used_space columns to asset_pool |
| `V2.25__add_blacklist_and_person_permissions` | Blacklist and person permissions |
| `V2.26__add_person` | Person table (alias, firstname, lastname, primary_image_uuid) + person_image gallery |
| `V2.27__add_detection` | Detection table (object/face detections: type, bbox, confidence, frame_number) + detection permissions |
| `V2.28__add_chat` | Chat table (title, messages JSONB array) + chat permissions |
| `V2.29__add_pipeline_run` | Pipeline run history table (status, counts, duration) + `*_PIPELINE_RUN` permissions |
| `V2.30__add_pipeline_version` | Immutable pipeline_version history; name/definition/enabled/priority/dry_run move off `pipeline` onto the version + version permissions |
| `V2.31__add_pipeline_execution_state` | Durable per-item / per-node execution ledger: `pipeline_run_item`, `pipeline_node_task` (leases, retries, idempotency keys) |
| `V2.32__add_pipeline_run_item_path_index` | Index on `pipeline_run_item.media_path` |
| `V2.33__add_cortex_instance` | Registered worker record `cortex_instance` + `cortex_instance_node_kind` whitelist/blacklist + `MANAGE/READ_CORTEX_INSTANCE` |
| `V2.34__add_task_priority` | `task.priority` column + `task_priority` enum (LOW/MEDIUM/HIGH/CRITICAL) |
| `V2.35__add_task_delete_cascade` | `ON DELETE CASCADE` for comment/reaction FKs into task and comment |
| `V2.36__add_skill` | User-owned agent `skill` table + `*_SKILL` permissions |
| `V2.37__add_skill_version` | Immutable `skill_version` history; description/content move off `skill` onto the version + version permissions |
| `V2.38__rework_asset_components` | **Rework** geo/doc/image/video/audio comps onto the shared component contract (provenance columns, per-table idempotency key, nullable audit columns) |
| `V2.39__rework_asset_transcript_comp` | **Rework** transcript comp: per-track FK, `lang` in the key, generated `tsvector` FTS column |
| `V2.40__rework_asset_json_comp` | **Rework** the generic sink: `schema_type NOT NULL`, `variant` discriminator, GIN index on `data` |
| `V2.41__add_asset_fingerprint_comp` | Multi-sector perceptual fingerprint component, indexed for dedup lookups |
| `V2.42__add_asset_segment_comp` | Time-ranged segment component (scenes, silence, shots, chapters) |
| `V2.43__rework_detection_embedding` | **Rework** detection + embedding: provenance, idempotency keys, `embedding.detection_uuid` FK, one geometry convention, cascade on `detection.asset_uuid` |
| `V2.44__attachment_provenance` | `attachment` becomes the derived-binary sink: node provenance, `variant`, new `attachment_type` values, cascade on `asset_uuid` |
| `V2.45__add_asset_node_result` | `asset_node_result` per-asset processing ledger (node-agnostic "has node X @ version V run") |
| `V2.46__asset_identity` | `asset.uuid` promoted to PK, `sha512sum` becomes `NOT NULL UNIQUE`, adds `is_complete`, drops legacy inline S3 columns |
| `V2.47__machine_written_audit_columns` | Nullable `creator_uuid`/`editor_uuid` on `attachment` (result tables recreated nullable in V2.38–V2.43) |
| `V2.48__fix_asset_location_key_and_annotation_cascade` | Drops `asset_location UNIQUE (asset_uuid)` for the real key `(library_uuid, path)`; cascades reaction/comment → annotation |
| `V2.49__version_pointer_delete_behaviour` | `pipeline.latest_version_uuid` / `skill.active_version_uuid` → `ON DELETE SET NULL` so the version cycle no longer blocks deletes |
| `V2.50__add_blacklist_name` | Adds the `blacklist.name` column the whole stack already referenced |

### Common Migration Patterns

- **UUID primary keys**: `uuid DEFAULT uuid_generate_v4()` with `PRIMARY KEY ("uuid")`
- **Audit columns**: Every CUD table has `created`, `creator_uuid`, `edited`, `editor_uuid` with FK to `user`
- **Meta column**: `meta jsonb` for custom user-defined properties
- **Permissions**: New entity types add `ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'CREATE_X'` etc.
- **Foreign keys**: `ON DELETE CASCADE` for child tables referencing assets
- **Join tables**: Named `X_Y` (e.g. `tag_asset`, `user_group`, `role_group`, `collection_asset`)
- **Indexes**: Unique indexes on natural keys (e.g. `username`, `name`), regular indexes on FK columns

### Adding a New Entity (Migration Checklist)

1. Create `V2.XX__add_<entity>.sql` in the flyway migration directory
2. Define the table with standard columns: `uuid`, `meta jsonb`, `created`/`creator_uuid`/`edited`/`editor_uuid`
3. Add FK constraints to `user` for creator/editor
4. Add permission enum values: `ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'CREATE_<ENTITY>'` etc.
5. Add `COMMENT ON TABLE` and `COMMENT ON COLUMN` for documentation
6. Run jOOQ codegen (via `mvn -Dgenerate generate-sources` in `loom-db-jooq`) to generate the `Jooq<Entity>` table class

## Test Infrastructure

### AbstractJooqTest

All jOOQ DAO tests extend `AbstractJooqTest`, which:
- Registers a `JooqTestContext` JUnit extension (`@RegisterExtension`)
- The context starts a PostgreSQL Testcontainer, runs Flyway migrations, generates jOOQ classes, and provides a `DaoCollection`
- Provides `daos()` and `transaction()` methods
- Implements `DatabaseTest` and `FixtureElementProvider`

### CRUDDaoTestcases

`CRUDDaoTestcases<DAO extends CRUDDao<T>, T extends Element<T>>` is a test interface that provides default CRUD tests for every DAO:

| Test | Description |
|---|---|
| `testCreate()` | Creates an element, stores it, verifies count increased, loads it back |
| `testDelete()` | Creates an element, deletes it, verifies `load()` returns null |
| `testUpdate()` | Creates, stores, modifies, updates, reloads and asserts the change persisted |
| `testLoad()` | Creates, stores, and verifies UUID is set |
| `testLoadPage()` | Creates 1024 elements, pages through them with page size 30, verifies total count |

Each DAO test implements:
- `getDao()` - return the DAO under test
- `createElement(User user, int i)` - create a test fixture element
- `assertCreate(T)` - assert the created element has expected field values
- `updateElement(T)` - mutate the element for the update test
- `assertUpdate(T)` - assert the updated element has the expected new values

### Example: PipelineDaoTest

```java
public class PipelineDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PipelineDao, Pipeline> {
    @Override
    public Pipeline createElement(User user, int i) {
        Pipeline pipeline = pipelineDao().createPipeline(user, "pipeline_" + i);
        pipeline.setDescription("Test pipeline " + i);
        pipeline.setDefinition(new JsonObject().put("nodes", new JsonArray()));
        pipeline.setEnabled(true);
        pipeline.setPriority(i);
        pipeline.setDryRun(false);
        return pipeline;
    }

    @Override
    public void assertCreate(Pipeline createdElement) {
        assertEquals("pipeline_0", createdElement.getName());
        // ...
    }

    @Override
    public PipelineDao getDao() { return pipelineDao(); }

    @Override
    public void updateElement(Pipeline element) {
        element.setName("updated-pipeline");
        // ...
    }

    @Override
    public void assertUpdate(Pipeline updatedPipeline) {
        assertEquals("updated-pipeline", updatedPipeline.getName());
        // ...
    }
}
```

### Existing DAO Tests

All tests are in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`:

| Test Class | Entity |
|---|---|
| `UserDaoTest` | User |
| `AssetDaoTest` | Asset (also tests meta CRUD) |
| `AssetLocationDaoTest` | AssetLocation |
| `AssetComponentKeyTest` | Asset geo/doc/image/video/audio components — identity keys, coexistence, upsert |
| `AssetTranscriptCompDaoTest` | Asset transcript component — per-track, model-upgrade upsert, FTS |
| `AssetFingerprintSegmentCompDaoTest` | Asset fingerprint + segment components |
| `AssetJsonCompDaoTest` | AssetJsonComp (generic sink) |
| `AssetNodeResultDaoTest` | AssetNodeResult (processing ledger) |
| `AttachmentDaoTest` | Attachment |
| `BlacklistDaoTest` | Blacklist |
| `ClusterDaoTest` | Cluster |
| `CollectionDaoTest` | Collection |
| `CommentDaoTest` | Comment |
| `EmbeddingDaoTest` | Embedding |
| `GroupDaoTest` | Group |
| `LibraryDaoTest` | Library |
| `PersonDaoTest` | Person |
| `PipelineDaoTest` | Pipeline |
| `PipelineVersionDaoTest` | PipelineVersion |
| `PipelineRunDaoTest` | PipelineRun |
| `PipelineRunItemDaoTest` | PipelineRunItem |
| `PipelineNodeTaskDaoTest` | PipelineNodeTask |
| `CortexInstanceDaoTest` | CortexInstance |
| `SkillDaoTest` | Skill |
| `SkillVersionDaoTest` | SkillVersion |
| `ReactionDaoTest` | Reaction |
| `RoleDaoTest` | Role — ⚠️ empty class, no tests (see In-Progress/TODO) |
| `SpaceDaoTest` | Space |
| `TagDaoTest` | Tag |
| `TagUserRatingDaoTest` | Tag user rating |
| `TaskDaoTest` | Task (includes comment-subtree cascade) |
| `TokenDaoTest` | Token |
| `WebhookDaoTest` | Webhook |
| `AnnotationDaoTest` | Annotation |

*(Helper `PipelineFixtures` is a shared fixture builder, not a test.)*

### FixtureElementProvider

Provides fixture elements from the database test fixture:
- `dummyUser()` - loads `USER_UUID`
- `adminUser()` - loads `ADMIN_UUID`
- `space()` - loads `PROJECT_UUID`
- `library()` - loads `LIBRARY_UUID`
- `asset()` - loads `ASSET_UUID`

`DatabaseTest` adds convenience creation methods: `createUser()`, `createAsset()`, `createLibrary()`, `createAsset(filename, user)`.

## In-Memory Implementation

`loom-db-memory` provides `AbstractMemDao<T>` - a simple `HashMap<UUID, T>` backed implementation of `CRUDDao`. This is used for fast unit tests that don't need a real database.

## Current Entity Model

The following entities have full DAO + model + migration + jOOQ table support:

| Entity | DAO Interface | Table | Key Features |
|---|---|---|---|
| User | `UserDao` | `user` | Username, SSO, soft-delete (`deleted` flag), admin user |
| Token | `TokenDao` | `token` | API tokens with permissions |
| Role | `RoleDao` | `role` | Named roles with permissions |
| Group | `GroupDao` | `group` | User groups with role assignments |
| Permission | `PermissionDao` | `user_permission`, `role_permission`, `token_permission` | Per-resource permissions |
| Asset | `AssetDao` | `asset` | `uuid` PK (V2.46), `sha512sum NOT NULL UNIQUE` content key, hashes, size, `is_complete` |
| AssetLocation | `AssetLocationDao` | `asset_location` | File path, library ref, lock state, pool ref; key `(library_uuid, path)` (V2.48) |
| AssetBinary | `AssetBinaryDao` | `attachment_binary` | Binary storage by SHA-512 |
| AssetComponent | `AssetComponentDao` | `asset_geo/doc/image/video/audio/transcript/fingerprint/segment/json_comp` | Node results on the shared component contract (node_kind/producer_version/run/task provenance, `UNIQUE(asset, node_kind, discriminators)`) — V2.38–V2.42. The generic `asset_json_comp` sink is REST-exposed as a slim, customer-facing endpoint at `/assets/:uuid/json-comps` (see `JsonCompEndpointService`) for cortex nodes to persist agnostic JSON results. |
| AssetNodeResult | `AssetNodeResultDao` | `asset_node_result` | Per-asset processing ledger: has node X @ version V run, and its outcome (V2.45) |
| AssetPool | `AssetPoolDao` | `asset_pool` | Storage pool (filesystem or S3), free/used space |
| Library | `LibraryDao` | `library` | Named library with assets and collections |
| Collection | `CollectionDao` | `collection` | Asset grouping with parent collection hierarchy |
| Space | `SpaceDao` | `project` (renamed) | Named space with libraries and collections |
| Tag | `TagDao` | `tag` | Tags with name, collection, rating, color |
| Embedding | `EmbeddingDao` | `embedding` | Vector data with provenance, `detection_uuid` FK, `dimensions` (V2.43) |
| Cluster | `ClusterDao` | `cluster` | Aggregates embeddings (e.g. person identity) |
| Annotation | `AnnotationDao` | `annotation` | FEEDBACK/TAG/CHAPTER types on assets |
| Comment | `CommentDao` | `comment` | Comments on tasks, annotations, assets |
| Reaction | `ReactionDao` | `reaction` | Social reactions (thumbsup, rating) |
| Task | `TaskDao` | `task` | Tasks with status + `priority` (LOW/MEDIUM/HIGH/CRITICAL, V2.34) |
| Webhook | `WebhookDao` | `webhook` | Event-driven webhooks with secret token |
| Blacklist | `BlacklistDao` | `blacklist` | Blocked assets with `name` (V2.50), type and review count |
| Attachment | `AttachmentDao` | `attachment` | Derived-binary sink: thumbnails, contact sheets, poster frames, proxies (V2.44) |
| Person | `PersonDao` | `person` | Person identity with image gallery |
| Detection | `DetectionDao` | `detection` | Object/face detections with provenance + idempotency key (V2.43) |
| Pipeline | `PipelineDao` | `pipeline` | Pipeline; name/definition/etc. live on `pipeline_version` (V2.30) |
| PipelineVersion | `PipelineVersionDao` | `pipeline_version` | Immutable version history of a pipeline definition (V2.30) |
| PipelineRun | `PipelineRunDao` | `pipeline_run` | Run history: status, counts, duration (V2.29) |
| PipelineRunItem | `PipelineRunItemDao` | `pipeline_run_item` | One media item discovered by a run's source node (V2.31) |
| PipelineNodeTask | `PipelineNodeTaskDao` | `pipeline_node_task` | One node execution against one item: leases, retries, idempotency (V2.31) |
| CortexInstance | `CortexInstanceDao` | `cortex_instance`, `cortex_instance_node_kind` | Registered worker + node-kind whitelist/blacklist (V2.33) |
| Skill | `SkillDao` | `skill` | User-owned agent skill; body lives on `skill_version` (V2.36) |
| SkillVersion | `SkillVersionDao` | `skill_version` | Immutable version history of a skill body (V2.37) |
| Chat | `ChatDao` | `chat` | LLM chat sessions with message history (JSONB) |

## Key Patterns for AI Agents

### 1. Adding a New Entity

To add a new persistence entity:

1. **Migration**: Create `V2.XX__add_<entity>.sql` following the standard pattern (uuid PK, audit columns, meta jsonb, FK to user, permission enum values)
2. **Model Interface**: Create `<Entity>.java` in `loom-db-api/.../model/<entity>/` extending `CUDElement<Entity>`
3. **DAO Interface**: Create `<Entity>Dao.java` in `loom-db-api/.../model/<entity>/` extending `CRUDDao<Entity>`
4. **POJO Implementation**: Create `<Entity>Impl.java` in `loom-db-jooq/.../dao/<entity>/` extending `AbstractEditableElement<Entity>` implementing `<Entity>`
5. **DAO Implementation**: Create `<Entity>DaoImpl.java` in `loom-db-jooq/.../dao/<entity>/` extending `AbstractJooqDao<Entity>` implementing `<Entity>Dao`
6. **DaoCollection**: Add the DAO to `DaoCollection` interface and `DaoCollectionImpl` (Lazy field, constructor param, accessor method)
7. **DaoProvider**: Add a `default` delegate method if desired
8. **jOOQ Codegen**: Run `mvn -Dgenerate generate-sources` in `loom-db-jooq` to generate the `Jooq<Entity>` table class
9. **Test**: Create `<Entity>DaoTest.java` extending `AbstractJooqTest` implementing `CRUDDaoTestcases<EntityDao, Entity>`

### 2. Soft Deletes

`UserDaoImpl` demonstrates soft-delete pattern: the `user` table has a `deleted` boolean column. `load()` and `loadPage()` filter `WHERE deleted = false`. The `delete()` method should set `deleted = true` rather than removing the row (though the base `AbstractJooqDao.delete()` does a hard delete).

### 3. Filter Implementation

Override `applyFilter()` in the DAO implementation to support entity-specific filters:

```java
@Override
protected SelectConditionStep<?> applyFilter(SelectConditionStep<?> query, Filter filter) {
    FilterKey key = filter.filterKey();
    if (key == LoomFilterKey.NAME && filter.getOperationKey().equals("eq")) {
        return query.and(TABLE.NAME.eq(filter.valueStr()));
    }
    return super.applyFilter(query, filter);
}
```

Add new filter keys to `LoomFilterKey`.

### 4. Cross-Table Operations

For join tables (e.g. `tag_asset`, `user_group`, `collection_asset`), use the `deleteCrossTableEntry()` helper or direct jOOQ queries:

```java
ctx().deleteFrom(JOIN_TABLE)
    .where(FIELD_A.eq(a).and(FIELD_B.eq(b)))
    .execute();
```

### 5. Batch Operations

`storeBatch()` is available on all DAOs via `CRUDDao`. The default implementation loops over `store()`, but `AbstractJooqDao` overrides with jOOQ `batchInsert` for performance.

### 6. JSONB Fields

Use `JsonObject` (Vert.x) in model interfaces. The `JsonObjectConverter` handles conversion to/from jOOQ `JSONB`. Fields like `meta`, `pipeline.definition`, and `chat.messages` use this pattern.

### 7. Database Options and Connection

The jOOQ module uses a `DSLContext` injected via Dagger. Tests use `JooqTestContext` which starts a PostgreSQL Testcontainer, runs Flyway, and provides the `DSLContext`. Production uses `DatabaseOptions` configured externally.

## Build Commands

```bash
# Full compilation check (skip tests)
mvn test-compile -q -DskipTests

# Run jOOQ code generation (starts Testcontainer + Flyway + codegen)
mvn -Dgenerate generate-sources -pl loom/db/jooq

# Run DAO tests
mvn test -pl loom/db/jooq
```

## Progress Tracker

This section tracks the status of each persistence entity and its associated features. Entities are listed in migration order.

### Completed Entities

| Entity | Migration | DAO Interface | DAO Impl | POJO | Test | Notes |
|---|---|---|---|---|---|---|
| User | V2.1 | UserDao | UserDaoImpl | UserImpl | UserDaoTest | Soft-delete, username load, admin user |
| Token | V2.1 | TokenDao | TokenDaoImpl | TokenImpl | TokenDaoTest | API tokens with permissions |
| Role | V2.1 | RoleDao | RoleDaoImpl | RoleImpl | RoleDaoTest | Named roles |
| Group | V2.1 | GroupDao | GroupDaoImpl | GroupImpl | GroupDaoTest | User groups |
| Permission | V2.1 | PermissionDao | PermissionDaoImpl | - | - | Per-resource permissions (user/role/token) |
| Tag | V2.2 | TagDao | TagDaoImpl | TagImpl | TagDaoTest | Tags with collection, rating, color |
| Task | V2.3 | TaskDao | TaskDaoImpl | TaskImpl | TaskDaoTest | Task status enum |
| Library | V2.9 | LibraryDao | LibraryDaoImpl | LibraryImpl | LibraryDaoTest | Libraries with assets/collections |
| Collection | V2.7 | CollectionDao | CollectionDaoImpl | CollectionImpl | CollectionDaoTest | Hierarchical collections |
| Space | V2.11 | SpaceDao | SpaceDaoImpl | SpaceImpl | SpaceDaoTest | Renamed from project |
| Asset | V2.8 | AssetDao | AssetDaoImpl | AssetImpl | AssetDaoTest | SHA-512 hash, media metadata, S3, geo |
| AssetLocation | V2.10 | AssetLocationDao | AssetLocationDaoImpl | AssetLocationImpl | AssetLocationDaoTest | File paths, library ref, pool ref |
| AssetBinary | V2.13 | AssetBinaryDao | AssetBinaryDaoImpl | - | - | Binary storage by SHA-512 |
| AssetComponent | V2.18/V2.38+ | AssetComponentDao | AssetComponentDaoImpl | `Abstract/*CompImpl` | AssetComponentKeyTest, AssetTranscriptCompDaoTest, AssetFingerprintSegmentCompDaoTest, AssetJsonCompDaoTest | 9 comp tables on the shared contract; upsert-by-key |
| AssetNodeResult | V2.45 | AssetNodeResultDao | AssetNodeResultDaoImpl | AssetNodeResultImpl | AssetNodeResultDaoTest | Per-asset processing ledger |
| Embedding | V2.12/V2.43 | EmbeddingDao | EmbeddingDaoImpl | EmbeddingImpl | EmbeddingDaoTest | Vector + provenance + detection FK |
| Cluster | V2.12 | ClusterDao | ClusterDaoImpl | ClusterImpl | ClusterDaoTest | Embedding clusters (person identity) |
| Annotation | V2.16 | AnnotationDao | AnnotationDaoImpl | AnnotationImpl | AnnotationDaoTest | FEEDBACK/TAG/CHAPTER types |
| Comment | V2.17 | CommentDao | CommentDaoImpl | CommentImpl | CommentDaoTest | Comments on tasks/annotations/assets |
| Reaction | V2.17 | ReactionDao | ReactionDaoImpl | ReactionImpl | ReactionDaoTest | Social reactions |
| Webhook | V2.15 | WebhookDao | WebhookDaoImpl | WebhookImpl | WebhookDaoTest | Event webhooks |
| Blacklist | V2.14/V2.50 | BlacklistDao | BlacklistDaoImpl | BlacklistImpl | BlacklistDaoTest | Blocked assets (with `name`) |
| Attachment | V2.13/V2.44 | AttachmentDao | AttachmentDaoImpl | AttachmentImpl | AttachmentDaoTest | Derived-binary sink |
| AssetPool | V2.20 | AssetPoolDao | AssetPoolDaoImpl | AssetPoolImpl | - | Storage pools (FS or S3) |
| Person | V2.26 | PersonDao | PersonDaoImpl | PersonImpl | PersonDaoTest | Person identity with image gallery |
| Detection | V2.27/V2.43 | DetectionDao | DetectionDaoImpl | DetectionImpl | - | Object/face detections (bbox, confidence) |
| Pipeline | V2.19 | PipelineDao | PipelineDaoImpl | PipelineImpl | PipelineDaoTest | Pipeline (definition on version) |
| PipelineVersion | V2.30 | PipelineVersionDao | PipelineVersionDaoImpl | PipelineVersionImpl | PipelineVersionDaoTest | Immutable version history |
| PipelineRun | V2.29 | PipelineRunDao | PipelineRunDaoImpl | PipelineRunImpl | PipelineRunDaoTest | Run history |
| PipelineRunItem | V2.31 | PipelineRunItemDao | PipelineRunItemDaoImpl | PipelineRunItemImpl | PipelineRunItemDaoTest | Per-item execution state |
| PipelineNodeTask | V2.31 | PipelineNodeTaskDao | PipelineNodeTaskDaoImpl | PipelineNodeTaskImpl | PipelineNodeTaskDaoTest | Per-node execution state (leases/retries) |
| CortexInstance | V2.33 | CortexInstanceDao | CortexInstanceDaoImpl | CortexInstanceImpl | CortexInstanceDaoTest | Registered worker + node-kind lists |
| Skill | V2.36 | SkillDao | SkillDaoImpl | SkillImpl | SkillDaoTest | User-owned agent skill |
| SkillVersion | V2.37 | SkillVersionDao | SkillVersionDaoImpl | SkillVersionImpl | SkillVersionDaoTest | Immutable skill body history |
| Chat | V2.28 | ChatDao | ChatDaoImpl | ChatImpl | - | LLM chat sessions with message history |

### In-Progress / TODO

| Entity | Status | Notes |
|---|---|---|
| AssetPool | Missing test | `AssetPoolDaoTest` not yet created |
| Detection | Missing test | `DetectionDaoTest` not yet created |
| Chat | Missing test | `ChatDaoTest` not yet created |
| AssetBinary | Missing test | No `AssetBinaryDaoTest` found |
| VectorConfig | Missing DAO | `vector_config` table (V2.6) has a jOOQ table but no domain DAO |
| Loom | ✅ Done | `loom` singleton row (V2.5) has `LoomDao`/`LoomDaoImpl` (load/createLoom/update); covered by `LoomDaoTest` |
| Permission | Thin test | `PermissionDaoTest` exists but only asserts non-nullity - see [PERMISSIONS.md](../features/permissions/PERMISSIONS.md) §9 |
| Role | Missing test | `RoleDaoTest` exists but is an empty class with zero tests |

### jOOQ Generated Tables

Generated table classes are in `loom/db/jooq/src/jooq/java/io/metaloom/loom/db/jooq/tables/`. Key tables:

`JooqUser`, `JooqToken`, `JooqRole`, `JooqRolePermission`, `JooqUserPermission`, `JooqTokenPermission`, `JooqGroup`, `JooqRoleGroup`, `JooqUserGroup`, `JooqTag`, `JooqTagAsset`, `JooqTagUserMeta`, `JooqTagCollection`, `JooqTagCluster`, `JooqTask`, `JooqAsset`, `JooqAssetRemix`, `JooqAssetLocation`, `JooqAssetUserMeta`, `JooqAssetTask`, `JooqCollection`, `JooqCollectionAsset`, `JooqCollectionCluster`, `JooqLibrary`, `JooqLibraryAsset`, `JooqLibraryCollection`, `JooqProject`, `JooqProjectLibrary`, `JooqProjectCollection`, `JooqEmbedding`, `JooqEmbeddingCluster`, `JooqCluster`, `JooqAnnotation`, `JooqAnnotationTask`, `JooqAnnotationAsset`, `JooqAnnotationTag`, `JooqComment`, `JooqReaction`, `JooqWebhook`, `JooqBlacklist`, `JooqAttachment`, `JooqAttachmentBinary`, `JooqAssetPool`, `JooqAssetGeoComp`, `JooqAssetDocComp`, `JooqAssetImageComp`, `JooqAssetVideoComp`, `JooqAssetAudioComp`, `JooqAssetTranscriptComp`, `JooqAssetJsonComp`, `JooqAssetFingerprintComp`, `JooqAssetSegmentComp`, `JooqAssetNodeResult`, `JooqPerson`, `JooqPersonImage`, `JooqDetection`, `JooqPipeline`, `JooqPipelineVersion`, `JooqPipelineRun`, `JooqPipelineRunItem`, `JooqPipelineNodeTask`, `JooqCortexInstance`, `JooqCortexInstanceNodeKind`, `JooqSkill`, `JooqSkillVersion`, `JooqChat`, `JooqVectorConfig`, `JooqLoom`, `JooqFlywaySchemaHistory`

### Migration File Index

```
loom/db/flyway/src/main/resources/db/migration/
  V1__db_setup.sql                         - Schema + UUID extension
  V2.1__add_acl.sql                        - User, Token, Role, Group, Permissions
  V2.2__add_tag.sql                        - Tag, tag_user_meta
  V2.3__add_workflow.sql                   - Task
  V2.5__add_loom.sql                       - Loom metadata + events enum
  V2.6__add_vector_config.sql              - Vector config
  V2.7__add_collection.sql                 - Collection, tag_collection, collection_cluster
  V2.8__add_asset.sql                      - Asset + join tables
  V2.9__add_library.sql                    - Library, library_asset, library_collection
  V2.10__add_asset_location.sql            - Asset location
  V2.11__add_project.sql                   - Project (later Space)
  V2.12__add_embedding.sql                 - Embedding, cluster, join tables
  V2.13__add_attachment.sql                - Attachment, attachment_binary
  V2.14__add_blacklist.sql                 - Blacklist
  V2.15__add_webhook.sql                   - Webhook
  V2.16__add_annotation.sql                - Annotation + join tables
  V2.17__add_social.sql                    - Comment, reaction
  V2.18__add_asset_components.sql          - Asset component tables (geo, doc, image, video, audio, transcript)
  V2.19__add_pipeline.sql                  - Pipeline
  V2.20__add_asset_pool.sql                - Asset pool
  V2.21__add_pool_binary_permissions.sql   - Asset binary/pool permissions
  V2.22__rename_project_permissions_to_space.sql - Project -> Space rename
  V2.23__add_asset_json_comp.sql           - Generic JSON component
  V2.24__add_asset_pool_free_space.sql     - Pool free/used space columns
  V2.25__add_blacklist_and_person_permissions.sql - Blacklist/person permissions
  V2.26__add_person.sql                    - Person, person_image
  V2.27__add_detection.sql                 - Detection
  V2.28__add_chat.sql                      - Chat
  V2.29__add_pipeline_run.sql              - Pipeline run history + run permissions
  V2.30__add_pipeline_version.sql          - Pipeline version history (definition moves off pipeline)
  V2.31__add_pipeline_execution_state.sql  - pipeline_run_item, pipeline_node_task
  V2.32__add_pipeline_run_item_path_index.sql - Index on run item media_path
  V2.33__add_cortex_instance.sql           - cortex_instance, cortex_instance_node_kind
  V2.34__add_task_priority.sql             - task.priority + task_priority enum
  V2.35__add_task_delete_cascade.sql       - Comment/reaction cascade into task/comment
  V2.36__add_skill.sql                     - Skill
  V2.37__add_skill_version.sql             - Skill version history (body moves off skill)
  V2.38__rework_asset_components.sql       - Rework geo/doc/image/video/audio comps (shared contract)
  V2.39__rework_asset_transcript_comp.sql  - Rework transcript comp (per-track, FTS)
  V2.40__rework_asset_json_comp.sql        - Rework generic json comp (variant, GIN)
  V2.41__add_asset_fingerprint_comp.sql    - Fingerprint component
  V2.42__add_asset_segment_comp.sql        - Segment component (scenes/silence/chapters)
  V2.43__rework_detection_embedding.sql    - Rework detection + embedding (provenance, FK, geometry)
  V2.44__attachment_provenance.sql         - Attachment as derived-binary sink
  V2.45__add_asset_node_result.sql         - Per-asset processing ledger
  V2.46__asset_identity.sql                - uuid PK, sha512sum NOT NULL UNIQUE, is_complete
  V2.47__machine_written_audit_columns.sql - Nullable audit columns on machine-written rows
  V2.48__fix_asset_location_key_and_annotation_cascade.sql - (library_uuid, path) key; annotation cascades
  V2.49__version_pointer_delete_behaviour.sql - Version pointers ON DELETE SET NULL
  V2.50__add_blacklist_name.sql            - blacklist.name column
```

---

*Schema current through `V2.50`. GIT HEAD: `b3b619287fd4d557c3adb232f6354a37702c3690` · Updated: 2026-07-24*
