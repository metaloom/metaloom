# Persistence Layer Tasks

> Open work items for the Loom persistence layer, verified against the actual code:
> migrations in `loom/db/flyway/src/main/resources/db/migration/` (`V1` – `V2.74`), the
> `DaoCollection` registry, and the DAO tests in
> `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) (how the layer works) ·
> [../loom/DOMAIN.md](../loom/DOMAIN.md) (entities) ·
> [../features/db/DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md) (the component /
> node-result schema rework — completed, kept as the historical record of `V2.38`–`V2.50`)
>
> No blocking order between the tasks below; Task 6 and Task 7 add DAO code and therefore
> touch the Dagger registry, so run them one at a time.

---


---

## Task 2: Add `DetectionDaoTest` CRUD test

**Argumentation Summary:** `DetectionDaoImpl` still has no test class; the only test-side reference to `detectionDao()` is inside `AssetCascadeTest`. `V2.43` gave `detection` `NOT NULL` provenance columns and a `UNIQUE (asset_uuid, node_kind, frame_number, detection_index)` idempotency key — none of which is pinned by a test, so a re-run/retry regression would go unnoticed.

> ⚠️ [../features/db/DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md) §10.2 lists
> `DetectionDaoTest` among the suites it verified. That class does not exist — the run was
> almost certainly `DetectionEndpointTest`. Treat this task as open.

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

**References:** migration `V2.43__rework_detection_embedding.sql` · [../loom/DOMAIN.md](../loom/DOMAIN.md) §4 AI/ML · `EmbeddingDaoTest` for the sibling pattern
**Test Requirements:** Inherited CRUD tests green; unique-key behaviour and provenance round-trip pinned.

---



---

## Task 4: Test `AssetBinaryDao` — and decide whether it should exist alongside `AssetLocationDao`

**Argumentation Summary:** `AssetBinaryDao` and `AssetLocationDao` are **two DAOs over the same table**: both `AssetBinaryDaoImpl.getTable()` and `AssetLocationDaoImpl.getTable()` return `JooqAssetLocation.ASSET_LOCATION` (they even share a `getTypeName()` of `"Asset Locations"`). Only `AssetLocationDao` has a test (`AssetLocationDaoTest`), yet `AssetBinaryDao` is the one the REST layer actually uses (`AssetUploadEndpointService`, `AssetBinaryEndpointService`, `BinaryReclaimer`, `AttachmentEndpointService`, `PipelineEndpointService`). Its non-trivial methods — `loadPrimaryByAssetUuid` (the fix for the `TooManyRowsException` → HTTP 500 bug), `loadByAssetAndLibrary`, `countByPoolAndPath` (the dedup guard a delete must consult before unlinking shared bytes) — are untested at the DAO level.

> `AssetBinaryDao` is *not* content-addressed storage in `attachment_binary` keyed by SHA-512.
> It is `asset_location`, keyed `(library_uuid, path)` since `V2.48`.

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
   [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) why one table deliberately carries two DAOs.
   Do not leave it undocumented.
```

**References:** `loom/db/api/src/main/java/io/metaloom/loom/db/model/asset/AssetBinaryDao.java` (its javadoc explains the cardinality) · migrations `V2.10`, `V2.20`, `V2.48`, `V2.63`
**Test Requirements:** Inherited CRUD tests plus one test per non-CRUD method; duplication resolved or documented.

---

---

## Task 6: Implement `VectorConfigDao` (table exists, no domain DAO)

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

**References:** [../loom/DOMAIN.md](../loom/DOMAIN.md) §4 Vector Config · [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) §Adding a New Entity, §DaoCollection and Dagger DI · migration `V2.6__add_vector_config.sql`
**Test Requirements:** `VectorConfigDaoTest` with full `CRUDDaoTestcases` coverage; `mvn test-compile -q -DskipTests` clean across `loom/db`.

---

## Task 7: Implement Asset Remix DAO operations

**Argumentation Summary:** `asset_remix` (`V2.8`) models derivation links between assets, but the only non-generated reference to it is a comment in `AssetCascadeTest:87` — "`asset_remix` also cascades (V2.8) but has no DAO yet, so it is intentionally left out until those operations exist." The table is unreachable from application code, and its cascade is therefore untested.

**Improvement Summary:** Remix link operations on `AssetDao` (join-table pattern), plus the cascade test that is currently deferred.

```
`asset_remix` is an asset↔asset join table (`asset_a_uuid` / `asset_b_uuid`, both
ON DELETE CASCADE, plus `creator_uuid` and `meta`). Follow the existing cross-table pattern
(PERSISTENCE.md §Cross-Table Operations — see the tag_asset handling in
TagDaoImpl/AssetDaoImpl).

1. Read `V2.8__add_asset.sql` lines 65–80 for the exact columns and whether the relation
   is directed.
2. Add to `AssetDao` / `AssetDaoImpl`:
   - `addRemix(...)`, `removeRemix(...)` (use the `deleteCrossTableEntry()` helper),
   - `loadRemixes(...)` — both directions if the relation is directed.
   Introduce a dedicated `AssetRemixDao` only if the table carries enough own state.
3. Extend `AssetDaoTest`: link two assets, list from both sides, unlink.
4. Extend `AssetCascadeTest.testDeletingAssetCascadesAllDependents` to include remix rows
   and delete the deferral comment at line 87.
```

**References:** [../loom/DOMAIN.md](../loom/DOMAIN.md) §2 Asset Remix · migration `V2.8__add_asset.sql` · [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) §Cross-Table Operations
**Test Requirements:** Link/list/unlink covered; remix rows asserted in the asset cascade test.

---

## Progress Assessment

- [ ] Task 1 — `AssetPoolDaoTest`
- [ ] Task 2 — `DetectionDaoTest`
- [x] Task 3 — `ChatDaoTest`
- [ ] Task 4 — `AssetBinaryDaoTest` + resolve the `AssetBinaryDao`/`AssetLocationDao` duplication
- [ ] Task 5 — `RoleDaoTest` CRUD + `loadByName`
- [ ] Task 6 — `VectorConfigDao` stack
- [ ] Task 7 — Asset remix operations + cascade test

Closed items are not kept here; the outcome record lives in
[../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) §Progress Assessment, which also tracks the
gaps this file does not own (`SpaceDaoTest` is still an empty class, `AnnotationDaoTest` does
not implement `CRUDDaoTestcases`, cascade suites are missing for several entities,
`JooqTestContext.afterEach` is disabled, `loom-db-memory` is unused).

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
A test class with 20+ methods used to error in its last few methods with *"Error while initializing
database"*. That was **not** provider-pool capacity — it was a leaked JDBC connection pool, since
fixed in `BootstrapInitializer.deinit()` (see [../loom/SERVER.md](../loom/SERVER.md) §shutdown).

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

_Git HEAD revision: `1e12f39e`_
_Last updated: 2026-08-06 (closed Task 3 — `ChatDaoTest` now covers CRUD, the `chat.messages` JSONB
deep-equality round-trip and the V2.52 SET NULL detach. Earlier: dropped the closed-gap table and the completed PERSISTENCE.md re-sync task — that tracker now runs to V2.74; rewrote the RoleDaoTest task, which was stale: the class is no longer empty, it covers role_permission but still lacks CRUD and loadByName; numbered the tasks and fixed the broken ../ links to spec/loom/)_
