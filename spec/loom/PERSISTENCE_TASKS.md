# Persistence Layer Tasks

Open work items for the Loom persistence layer, verified against the actual code:
migrations in `loom/db/flyway/src/main/resources/db/migration/` (`V1` – `V2.63`), the
`DaoCollection` registry, and the DAO tests in
`loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.

Format follows [../TASKS.template.md](../TASKS.template.md). Companion documents:
[PERSISTENCE.md](PERSISTENCE.md) (how the layer works), [DOMAIN.md](DOMAIN.md) (entities),
[../features/db/DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md) (the component /
node-result schema rework — **completed**, kept as the historical record of `V2.38`–`V2.50`).

## Closed gaps (outcome record)

| Gap | Closed by |
|---|---|
| Asset-component test coverage per modality | `V2.38`–`V2.45` rework; `AssetComponentKeyTest`, `AssetTranscriptCompDaoTest`, `AssetFingerprintSegmentCompDaoTest`, `AssetJsonCompDaoTest`, `AssetNodeResultDaoTest` |
| `LoomDao` missing (`V2.5` singleton) | `LoomDao` / `LoomDaoImpl` + `LoomDaoTest` |
| `PermissionDaoTest` too thin | `loom/db/jooq/src/test/java/io/metaloom/loom/db/perm/PermissionDaoTest.java` — 5 tests: direct grant, group→role inheritance, non-membership isolation, per-user isolation, admin perms |
| Cascade: Asset → children | `AssetCascadeTest` (cascade + the three delete-blocking join tables) |
| Cascade: ACL join tables | `AclCascadeTest` (role, group, soft vs. hard user delete) |
| Cascade: Pipeline → Version / Run / RunItem / NodeTask | `PipelineDaoTest` (4 cascade tests) |
| Cascade: Annotation joins, reaction/comment | `AnnotationDaoTest`; `V2.48` added the missing `ON DELETE CASCADE` |
| Cascade: Person → gallery images | `PersonDaoTest` (plus primary-image pointer null-out) |
| Cascade: Cluster / Collection joins | `ClusterDaoTest`, `CollectionDaoTest`; `V2.51` for `embedding_cluster` |
| Cascade: CortexInstance → node kinds | `CortexInstanceDaoTest.testDeletingInstanceCascadesNodeKinds` |
| `asset_location UNIQUE (asset_uuid)` wrong natural key | `V2.48` — replaced with `(library_uuid, path)` |

> ⚠️ [../features/db/DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md) §10.2 lists
> `DetectionDaoTest` among the suites it verified. That class does not exist — the run was
> almost certainly `DetectionEndpointTest`. Treat the task below as open.

---

## Task: Add `AssetPoolDaoTest` CRUD test

**Argumentation Summary:** `AssetPoolDaoImpl` is registered in `DaoCollection` but has zero test coverage — no test anywhere references `assetPoolDao()`. `V2.63` made pools load-bearing: `library.pool_uuid` and `attachment_binary.pool_uuid` now point at `asset_pool` with `ON DELETE RESTRICT`, so a pool regression breaks binary placement and library storage.
**Improvement Summary:** A standard `CRUDDaoTestcases` test covering all pool fields plus the `ON DELETE RESTRICT` behaviour introduced in `V2.63`.

```
Create `AssetPoolDaoTest` in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.
Extend `AbstractJooqTest`, implement `CRUDDaoTestcases<AssetPoolDao, AssetPool>`
(`PipelineDaoTest` is the reference implementation).

- Factory is `assetPoolDao().createAssetPool(UUID creatorUuid, String name)`.
- `AssetPool` carries: name, fsPath (filesystem pool), s3Bucket / s3Region / s3Endpoint
  (S3 pool), freeSpace and usedSpace (Long, bytes, V2.24). Cover both pool shapes.
- The CRUD harness builds 1024 elements — vary `name` by `i`, it is the unique field.
- Add an explicit test: a library referencing the pool must block `delete` on the pool
  (V2.63 `ON DELETE RESTRICT` on `library.pool_uuid`).
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §Test Infrastructure; migrations `V2.20`, `V2.24`, `V2.63`
**Test Requirements:** Five inherited CRUD tests plus the RESTRICT test green via `mvn test -pl loom/db/jooq -Dtest=AssetPoolDaoTest`.

---

## Task: Add `DetectionDaoTest` CRUD test

**Argumentation Summary:** `DetectionDaoImpl` still has no test class; the only test-side reference to `detectionDao()` is inside `AssetCascadeTest`. `V2.43` gave `detection` `NOT NULL` provenance columns and a `UNIQUE (asset_uuid, node_kind, frame_number, detection_index)` idempotency key — none of which is pinned by a test, so a re-run/retry regression would go unnoticed.
**Improvement Summary:** A CRUD test plus explicit coverage of the `V2.43` unique key and provenance columns.

```
Create `DetectionDaoTest` in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.
Extend `AbstractJooqTest`, implement `CRUDDaoTestcases<DetectionDao, Detection>`.

- Factory: `detectionDao().createDetection(UUID userUuid, String type)`; the row needs an
  asset FK — use the fixture asset from `AbstractJooqTest`.
- Populate the full V2.43 shape: nodeKind, producerVersion, detectionIndex, label,
  type, frameNumber, timeFrom, bboxX/Y/Width/Height, confidence.
- Vary `detectionIndex` (or `frameNumber`) by `i` — the unique key is
  (asset_uuid, node_kind, frame_number, detection_index) and the harness creates 1024 rows.
- Explicit tests: (a) storing a second detection with the same key must behave as the DAO
  contract defines (upsert-replace or constraint violation) — pin it; (b) `run_uuid` /
  `task_uuid` are `ON DELETE SET NULL`, deleting a run must not delete the detection.
```

**References:** migration `V2.43__rework_detection_embedding.sql`; [DOMAIN.md](DOMAIN.md) §4 AI/ML; `EmbeddingDaoTest` for the sibling pattern
**Test Requirements:** Inherited CRUD tests green; unique-key behaviour and provenance round-trip pinned.

---

## Task: Add `ChatDaoTest` CRUD test

**Argumentation Summary:** `ChatDaoImpl` has no test of its own. `chat` is still live — `V2.52` gave `chat_session.chat_uuid` an FK to it (`ON DELETE SET NULL`) — and `ChatSessionDaoTest` only creates a chat as a fixture, asserting nothing about it. The `messages` JSONB array goes through `JsonObjectConverter`; a conversion regression corrupts chat history silently.
**Improvement Summary:** A `ChatDaoTest` with deep-equality round-trip assertions on the JSONB payload.

```
Create `ChatDaoTest` in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.
Extend `AbstractJooqTest`, implement `CRUDDaoTestcases<ChatDao, Chat>`.

- Factory: `chatDao().createChat(UUID userUuid, String title)`; title `"chat_" + i`.
- `assertCreate`: a non-trivial `messages` array (role/content entries) must round-trip
  through `JsonObjectConverter` with deep equality, not just non-null.
- `updateElement`/`assertUpdate`: append a message and change the title; verify both persist.
- Explicit test: deleting a chat leaves an attached `chat_session` alive with a null
  `chat_uuid` (V2.52 ON DELETE SET NULL).
```

**References:** migrations `V2.28__add_chat.sql`, `V2.52__add_chat_session.sql`; [PERSISTENCE.md](PERSISTENCE.md) §JsonObjectConverter
**Test Requirements:** Inherited CRUD tests plus the JSONB deep-equality and SET NULL assertions.

---

## Task: Test `AssetBinaryDao` — and decide whether it should exist alongside `AssetLocationDao`

**Argumentation Summary:** `AssetBinaryDao` and `AssetLocationDao` are **two DAOs over the same table**: both `AssetBinaryDaoImpl.getTable()` and `AssetLocationDaoImpl.getTable()` return `JooqAssetLocation.ASSET_LOCATION`. Only `AssetLocationDao` has a test (`AssetLocationDaoTest`), yet `AssetBinaryDao` is the one the REST layer actually uses (`AssetUploadEndpointService`, `AssetBinaryEndpointService`, `BinaryReclaimer`, `AttachmentEndpointService`, `PipelineEndpointService`). Its non-trivial methods — `loadPrimaryByAssetUuid` (the fix for the `TooManyRowsException` → HTTP 500 bug), `loadByAssetAndLibrary`, `countByPoolAndPath` (the dedup guard a delete must consult before unlinking shared bytes) — are untested at the DAO level.

> The earlier version of this task described `AssetBinaryDao` as content-addressed storage in `attachment_binary` keyed by SHA-512. That is wrong: it is `asset_location`, keyed `(library_uuid, path)` since `V2.48`.

**Improvement Summary:** An `AssetBinaryDaoTest` covering the multi-location semantics, plus a decision on collapsing the duplicate DAO pair.

```
1. Create `AssetBinaryDaoTest` in
   `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`, extending `AbstractJooqTest`
   and implementing `CRUDDaoTestcases<AssetBinaryDao, AssetBinary>`.
   Factory: `createAssetBinary(String path, UUID assetUuid, UUID creatorUuid, UUID libraryUuid)`.
   Vary `path` by `i` — `(library_uuid, path)` is the natural key and the harness builds 1024 rows.
2. Explicit tests for the methods the REST layer depends on:
   - several locations on one asset → `loadPrimaryByAssetUuid` returns the oldest, stably
     (tie-broken by uuid); `loadAllByAssetUuid` returns all of them oldest-first;
   - `loadByAssetAndLibrary` isolates per library, returns null when absent;
   - `countByPoolAndPath` counts every row sharing the same (pool, locator), including
     the null-pool default-local case;
   - `deleteByAssetUuid` removes all locations of one asset and nothing else.
3. Then decide the duplication: either delete `AssetLocationDao`/`AssetLocationDaoTest` and
   route its two callers through `AssetBinaryDao`, or document in
   [PERSISTENCE.md](PERSISTENCE.md) why one table deliberately carries two DAOs.
   Do not leave it undocumented.
```

**References:** `loom/db/api/src/main/java/io/metaloom/loom/db/model/asset/AssetBinaryDao.java` (its javadoc explains the cardinality); migrations `V2.10`, `V2.20`, `V2.48`, `V2.63`
**Test Requirements:** Inherited CRUD tests plus one test per non-CRUD method; duplication resolved or documented.

---

## Task: Implement `RoleDaoTest` (still an empty class)

**Argumentation Summary:** `RoleDaoTest.java` is literally `public class RoleDaoTest { }` — no superclass, no tests. It passes silently while providing zero coverage of a core RBAC entity. `AclCascadeTest` covers role *deletion* cascades, so only role CRUD and `loadByName` are missing.
**Improvement Summary:** Turn the empty shell into a real CRUD test plus a `loadByName` test.

```
Rewrite `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/RoleDaoTest.java`:
extend `AbstractJooqTest`, implement `CRUDDaoTestcases<RoleDao, Role>`.

- Factory: `roleDao().createRole(UUID creatorUuid, String name)`, name `"role_" + i`
  (unique field — the harness builds 1024 rows).
- Standard assert/update hooks on name and meta.
- `RoleDao` has exactly one method beyond `CRUDDao`: `loadByName(String name)`.
  Test the hit and the miss (null) case.
- Do not duplicate `AclCascadeTest.testDeletingARoleCascadesGrantsAndGroupLinks`.
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §In-Progress/TODO; migration `V2.1__add_acl.sql`
**Test Requirements:** Inherited CRUD tests green; both `loadByName` branches covered.

---

## Task: Implement `VectorConfigDao` (table exists, no domain DAO)

**Argumentation Summary:** `vector_config` (`V2.6`) has a generated `JooqVectorConfig` table but no model interface, DAO, POJO or `DaoCollection` registration — a repo-wide grep finds it only under `jooq/src/jooq/`. Custom vector index definitions cannot be persisted from application code.
**Improvement Summary:** Full DAO stack for `vector_config`.

```
Follow PERSISTENCE.md §"Adding a New Entity" steps 2–9 (the migration already exists).
V2.6 columns: uuid, name (varchar UNIQUE NOT NULL), weights (jsonb), created/creator_uuid,
edited/editor_uuid.

1. `VectorConfig` model interface in `loom/db/api/.../model/vectorconfig/`, extending
   `CUDElement<VectorConfig>`, with `name` and a `weights` JSONB accessor
   (use the same `JsonObjectConverter`-backed type as `AssetJsonComp`).
2. `VectorConfigDao extends CRUDDao<VectorConfig>` with
   `createVectorConfig(UUID creatorUuid, String name)` and `loadByName(String)`.
3. `VectorConfigImpl` + `VectorConfigDaoImpl` in `loom/db/jooq` (extend
   `AbstractEditableElement` / `AbstractJooqDao`, table `JooqVectorConfig`).
4. Register in `DaoCollection`, `DaoCollectionImpl` (Lazy field + ctor param + accessor),
   `DaoProvider` default method and `JooqLoomDaoBindModule`.
5. `VectorConfigDaoTest` implementing `CRUDDaoTestcases` (vary `name` by `i`).

After the DI change: clean-rebuild `loom/core` and re-run `./setup-pool.sh` before running
dependent tests, otherwise you get `NoSuchMethodError` on the generated Dagger factory.
```

**References:** [DOMAIN.md](DOMAIN.md) §4 Vector Config; [PERSISTENCE.md](PERSISTENCE.md) §Adding a New Entity, §DaoCollection and Dagger DI; migration `V2.6__add_vector_config.sql`
**Test Requirements:** `VectorConfigDaoTest` with full `CRUDDaoTestcases` coverage; `mvn test-compile -q -DskipTests` clean across `loom/db`.

---

## Task: Implement Asset Remix DAO operations

**Argumentation Summary:** `asset_remix` (`V2.8`) models derivation links between assets, but the only non-generated references to it are `JooqAssetRemix` and a comment in `AssetCascadeTest:65` — "`asset_remix` also cascades (V2.8) but has no DAO yet, so it is intentionally left out until those operations exist." The table is unreachable from application code, and its cascade is therefore untested.
**Improvement Summary:** Remix link operations on `AssetDao` (join-table pattern), plus the cascade test that is currently deferred.

```
`asset_remix` is an asset↔asset join table. Follow the existing cross-table pattern
(PERSISTENCE.md §Cross-Table Operations — see the tag_asset handling in
TagDaoImpl/AssetDaoImpl).

1. Read `V2.8__add_asset.sql` for the exact columns (two asset FKs + any relation-type
   column) and the direction of the relation.
2. Add to `AssetDao` / `AssetDaoImpl`:
   - `addRemix(...)`, `removeRemix(...)` (use the `deleteCrossTableEntry()` helper),
   - `loadRemixes(...)` — both directions if the relation is directed.
   Introduce a dedicated `AssetRemixDao` only if the table carries enough own state.
3. Extend `AssetDaoTest`: link two assets, list from both sides, unlink.
4. Extend `AssetCascadeTest.testDeletingAssetCascadesAllDependents` to include remix rows
   and delete the deferral comment at line 65.
```

**References:** [DOMAIN.md](DOMAIN.md) §2 Asset Remix; migration `V2.8__add_asset.sql`; [PERSISTENCE.md](PERSISTENCE.md) §Cross-Table Operations
**Test Requirements:** Link/list/unlink covered; remix rows asserted in the asset cascade test.

---

## Task: Re-sync the PERSISTENCE.md progress tracker to `V2.63`

**Argumentation Summary:** [PERSISTENCE.md](PERSISTENCE.md) states "Schema current through `V2.50`", but the migration directory now runs to `V2.63`. Missing from the tracker: `chat_session` (`V2.52`), agent memory (`V2.53`) and `memory_deny_rule` (`V2.54`), the webhook removal (`V2.55`), `search_document` + triggers (`V2.58`/`V2.59`), `dedup_group` (`V2.61`) and the `library`/`attachment_binary` pool pointers (`V2.63`). Agents use this tracker for gap analysis, so drift produces wrong conclusions — as it already did for `DetectionDaoTest`.
**Improvement Summary:** Bring migration history, entity model, test-class table and the In-Progress/TODO list in sync with the tree.

```
In `spec/loom/PERSISTENCE.md`:

1. Extend Migration History / Migration File Index to V2.63.
2. Add the entities introduced since V2.50 to the entity-model and Completed-Entities
   tables: ChatSession (+ chat_session_skill, chat_session_context_ref), MemoryEntry,
   MemoryDenyRule, DedupGroup, SearchDocument. Remove Webhook (dropped in V2.55).
3. Rebuild the "Existing DAO Tests" table from
   `find loom/db/jooq/src/test -name '*Test.java'` — it currently claims 35 classes;
   there are 40 `*DaoTest` classes plus the cascade, component-key and search tests.
   Note that `search_document` has no DAO: it is covered by `SearchDocumentLifecycleTest`,
   `SearchDocumentSourceTest` and `SearchQueryBehaviourTest` under `.../jooq/search/`.
4. Move `Permission` and `Loom` out of In-Progress/TODO (both are now covered);
   keep AssetPool, Detection, Chat, AssetBinary, Role and VectorConfig there until the
   tasks above land.
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §Progress Tracker, §Migration History; migrations `V2.51`–`V2.63`
**Test Requirements:** None (documentation). Verify every table claim against `migration/`, `tables/` and `dao/` before writing it.

---

## Progress Assessment

- [x] Asset-component coverage per modality (`V2.38`–`V2.45`)
- [x] `LoomDao` + `LoomDaoTest`
- [x] `PermissionDaoTest` fleshed out
- [x] Cascade tests: Asset, ACL, Pipeline, Annotation, Person, Cluster/Collection, CortexInstance
- [ ] `AssetPoolDaoTest`
- [ ] `DetectionDaoTest`
- [ ] `ChatDaoTest`
- [ ] `AssetBinaryDaoTest` + resolve the `AssetBinaryDao`/`AssetLocationDao` duplication
- [ ] `RoleDaoTest` (still an empty class)
- [ ] `VectorConfigDao` stack
- [ ] Asset remix operations + cascade test
- [ ] Re-sync `PERSISTENCE.md` tracker to `V2.63`

## Test Setup

```bash
# once, and again after EVERY Flyway change (install loom/db/flyway first, or the
# pool silently keeps the old schema)
./setup-pool.sh

# run one DAO test
mvn test -pl loom/db/jooq -Dtest=RoleDaoTest
```

`CRUDDaoTestcases` builds **1024** elements for its paging test — every entity's unique
column must vary with `i`, or the create loop fails on a constraint violation.
The provider pool is finite: a test class with 20+ methods can exhaust it and the last few
methods error in `ProviderExtension.beforeEach` — that is pool capacity, not a regression.

## Where do I find …?

| Concept | Path |
|---|---|
| Migrations | `loom/db/flyway/src/main/resources/db/migration/` |
| Model + DAO interfaces | `loom/db/api/src/main/java/io/metaloom/loom/db/model/<entity>/` |
| jOOQ DAO implementations | `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/<entity>/` |
| Generated jOOQ tables | `loom/db/jooq/src/jooq/java/io/metaloom/loom/db/jooq/tables/` |
| DAO tests | `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/` |
| Permission tests | `loom/db/jooq/src/test/java/io/metaloom/loom/db/perm/` |
| Search index tests | `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/search/` |
| DI registration | `DaoCollection`, `DaoCollectionImpl`, `JooqLoomDaoBindModule` |

_Git HEAD revision: `2e5981cb`_
_Last updated: 2026-08-01 (verified every gap against `V1`–`V2.63`; collapsed the closed component and cascade gaps to one-line records and left eight open tasks)_
