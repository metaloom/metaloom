# Persistence Layer Tasks

Task list for closing gaps in the persistence layer, derived from a comparison of
[DOMAIN.md](DOMAIN.md), [PERSISTENCE.md](PERSISTENCE.md), the `DaoCollection` /
`DaoCollectionImpl` DAO registry, the Flyway migrations (`V1` – `V2.35`) and the
existing test classes in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.

**Gap summary:**

| Area | Gap |
|---|---|
| Missing CRUD tests | `AssetPoolDaoTest`, `DetectionDaoTest`, `ChatDaoTest`, `AssetBinaryDaoTest`, `AssetComponentDaoTest` (geo/doc/image/video/audio/transcript), empty `RoleDaoTest`, thin `PermissionDaoTest` |
| Missing domain DAOs | `VectorConfigDao` (V2.6), `LoomDao` (V2.5 singleton), Asset Remix operations (V2.8 `asset_remix`) |
| Missing cascade tests | Asset→children, ACL join tables, Pipeline→Version/Run, Annotation joins, Person→images, Cluster/Collection joins, CortexInstance→node kinds |

---

## Task: Add AssetPoolDaoTest CRUD test

**Argumentation Summary:** `AssetPoolDaoImpl` exists and is registered in `DaoCollection`, but has no test coverage. PERSISTENCE.md explicitly lists it under "In-Progress / TODO" as a missing test. Storage pools (filesystem/S3, free/used space tracking) are the backbone of binary placement and regressions would break asset ingestion.
**Improvement Summary:** A `AssetPoolDaoTest` class following the standard `CRUDDaoTestcases` pattern, covering create/load/update/delete/paging of asset pools.

```
Create `AssetPoolDaoTest` in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.
Extend `AbstractJooqTest` and implement `CRUDDaoTestcases<AssetPoolDao, AssetPool>`
(see `PipelineDaoTest` as the reference implementation).

- `createElement(User user, int i)`: use `assetPoolDao().createAssetPool(...)` (check the
  DAO interface in `loom/db/api/.../model/pool/AssetPoolDao.java` for the factory signature)
  and populate all pool fields: name, type (FILESYSTEM and S3 variants), base path / bucket
  settings, free_space, used_space (added in V2.24).
- `assertCreate`, `updateElement`, `assertUpdate`: assert/mutate name, type-specific fields
  and the free/used space columns.
- `getDao()`: return `assetPoolDao()`.
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §Test Infrastructure, §In-Progress/TODO; migrations `V2.20__add_asset_pool.sql`, `V2.24__add_asset_pool_free_space.sql`
**Test Requirements:** All five inherited CRUD tests (`testCreate`, `testDelete`, `testUpdate`, `testLoad`, `testLoadPage`) pass via `mvn test -pl loom/db/jooq -Dtest=AssetPoolDaoTest`.

---

## Task: Add DetectionDaoTest CRUD test

**Argumentation Summary:** `DetectionDaoImpl` (object/face detections with bbox, confidence, frame number) has no test. Listed as a TODO in PERSISTENCE.md. Detections are written by Cortex processing nodes; an untested DAO risks silent data loss in the AI/ML pipeline.
**Improvement Summary:** A `DetectionDaoTest` covering CRUD and paging for detections bound to an asset.

```
Create `DetectionDaoTest` in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.
Extend `AbstractJooqTest`, implement `CRUDDaoTestcases<DetectionDao, Detection>`.

- Detections reference an asset — use the fixture `asset()` from `FixtureElementProvider`
  or `createAsset(...)` from `DatabaseTest` to satisfy the FK.
- `createElement(User user, int i)`: create a detection with type (e.g. FACE/OBJECT),
  bounding box coordinates, confidence and frame_number varying by `i`.
- Assert/update the bbox, confidence and type fields in the respective hooks.
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §CRUDDaoTestcases; migration `V2.27__add_detection.sql`; [DOMAIN.md](DOMAIN.md) §4 AI/ML
**Test Requirements:** All inherited CRUD tests green; detection rows correctly reference the fixture asset.

---

## Task: Add ChatDaoTest CRUD test

**Argumentation Summary:** `ChatDaoImpl` (LLM chat sessions with JSONB message history) has no test. Listed as a TODO in PERSISTENCE.md. The `messages` JSONB array uses `JsonObjectConverter` — a conversion regression would corrupt chat history without failing loudly.
**Improvement Summary:** A `ChatDaoTest` covering CRUD, with explicit round-trip assertions on the JSONB `messages` payload.

```
Create `ChatDaoTest` in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.
Extend `AbstractJooqTest`, implement `CRUDDaoTestcases<ChatDao, Chat>`.

- `createElement(User user, int i)`: create a chat with title `"chat_" + i` and a
  non-trivial messages payload (JSON array with role/content entries).
- `assertCreate`: verify the messages payload round-trips through the
  `JsonObjectConverter` unchanged (deep equality, not just non-null).
- `updateElement`/`assertUpdate`: append a message and change the title, verify both persist.
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §JsonObjectConverter, §In-Progress/TODO; migration `V2.28__add_chat.sql`
**Test Requirements:** Inherited CRUD tests plus deep-equality round-trip assertion on the JSONB messages field.

---

## Task: Add AssetBinaryDaoTest CRUD test

**Argumentation Summary:** `AssetBinaryDaoImpl` (binary storage in `attachment_binary`, addressed by SHA-512) has no test at all. Binary storage is content-addressed rather than plain UUID-CRUD, so the generic testcases may not apply 1:1 — which is exactly why explicit coverage is needed.
**Improvement Summary:** A test class covering store/load/delete of binaries by hash, plus duplicate-hash handling.

```
Create `AssetBinaryDaoTest` in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.
Extend `AbstractJooqTest`. First inspect
`loom/db/api/.../model/asset/AssetBinaryDao.java` — if the interface is a regular
`CRUDDao<AssetBinary>`, implement `CRUDDaoTestcases` like the other tests; otherwise
write explicit @Test methods:

- store a binary with a known SHA-512 sum, load it back by hash and by UUID, verify
  size/hash fields.
- storing a second binary with the same SHA-512 must behave as the DAO contract defines
  (upsert or constraint violation) — pin the current behavior in a test.
- delete and verify load returns null.
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §Current Entity Model (AssetBinary); migration `V2.13__add_attachment.sql`
**Test Requirements:** Store/load/delete by hash covered; duplicate-hash behavior pinned by a test.

---

## Task: Add AssetComponentDaoTest covering all component modalities

**Argumentation Summary:** `AssetComponentDaoImpl` serves seven component tables (geo, doc, image, video, audio, transcript, json), but only the json variant is tested (`AssetJsonCompDaoTest`). The other six modalities — populated by scanners and Cortex nodes — have zero coverage.
**Improvement Summary:** A test class exercising create/load/update/delete for each remaining component modality against a fixture asset.

```
Create `AssetComponentDaoTest` in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.
Extend `AbstractJooqTest`. Use `AssetJsonCompDaoTest` as the structural reference and
`AssetComponentDao` (loom/db/api/.../model/asset/AssetComponentDao.java) for the API.

For each modality (geo, doc, image, video, audio, transcript):
- create a component for a fixture asset with modality-specific fields populated and a
  `source` tag,
- load it back and assert field round-trip,
- update one field and verify persistence,
- delete and verify removal.

Also verify that multiple components of the same modality with different `source`
values can coexist on one asset.
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §Current Entity Model (AssetComponent); migrations `V2.18__add_asset_components.sql`, `V2.23__add_asset_json_comp.sql`; [DOMAIN.md](DOMAIN.md) §2 Asset Component
**Test Requirements:** All six untested modalities covered with round-trip assertions; multi-source coexistence verified.

---

## Task: Implement RoleDaoTest (currently an empty class)

**Argumentation Summary:** `RoleDaoTest` exists but is a completely empty class (no superclass, no tests) — it silently passes while providing zero coverage. Roles are a core RBAC entity; PERSISTENCE.md flags this explicitly.
**Improvement Summary:** Turn the empty shell into a real CRUD test following the standard pattern, plus role-specific queries.

```
Rewrite `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/RoleDaoTest.java`:
extend `AbstractJooqTest`, implement `CRUDDaoTestcases<RoleDao, Role>`.

- `createElement(User user, int i)`: `roleDao().createRole(...)` with name `"role_" + i`.
- Standard assert/update hooks on the name and meta fields.
- Add explicit tests for any entity-specific methods on `RoleDao`
  (e.g. load-by-name, role↔group assignment helpers if present on the interface).
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §In-Progress/TODO (Role); migration `V2.1__add_acl.sql`; spec/loom/PERMISSION.md if present
**Test Requirements:** Inherited CRUD tests green; every public method of `RoleDao` beyond `CRUDDao` has at least one test.

---

## Task: Extend the thin PermissionDaoTest

**Argumentation Summary:** `PermissionDaoTest` only loads admin permissions and asserts non-nullity (and prints to stdout). Grant/revoke paths for user, role and token permissions are untested, yet permissions gate every endpoint.
**Improvement Summary:** Real assertions for granting, loading and revoking permissions across all three bindings (user, role, token).

```
Extend `loom/db/jooq/src/test/java/io/metaloom/loom/db/perm/PermissionDaoTest.java`:

- Grant a specific permission (e.g. CREATE_ASSET) to a fresh user; load and assert the
  set contains exactly the granted permission for the right resource.
- Repeat for role-bound (`role_permission`) and token-bound (`token_permission`) grants.
- Revoke and assert the permission disappears.
- Verify a user inherits role permissions via group membership if the DAO exposes an
  effective-permission load.
- Remove the `System.out.println` loop; replace with assertions.
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §In-Progress/TODO (Permission); migration `V2.1__add_acl.sql`; [DOMAIN.md](DOMAIN.md) §1 Permission
**Test Requirements:** Grant/load/revoke covered for user, role and token permission bindings with exact-content assertions.

---

## Task: Implement VectorConfigDao (missing domain DAO)

**Argumentation Summary:** The Vector Config entity ([DOMAIN.md](DOMAIN.md) §4, migration `V2.6__add_vector_config.sql`) has a table but no model interface, no DAO, no POJO and no registration in `DaoCollection` — it is unreachable from application code. Custom vector indices cannot be persisted.
**Improvement Summary:** Full DAO stack (model interface, DAO interface, jOOQ impl, POJO, DI wiring, test) for `vector_config`.

```
Follow PERSISTENCE.md §"Adding a New Entity" steps 2–9 (the migration already exists):

1. `VectorConfig.java` model interface in `loom-db-api/.../model/vectorconfig/`
   extending `CUDElement<VectorConfig>` with fields matching V2.6 (name, weight
   definition, etc. — read the migration for exact columns).
2. `VectorConfigDao.java` extending `CRUDDao<VectorConfig>` with a
   `createVectorConfig(UUID userUuid, String name)` factory.
3. `VectorConfigImpl` + `VectorConfigDaoImpl` in `loom-db-jooq` (extend
   `AbstractEditableElement` / `AbstractJooqDao`, table `JooqVectorConfig` — the jOOQ
   class should already be generated; if not, run `mvn -Dgenerate generate-sources -pl loom/db/jooq`).
4. Register in `DaoCollection`, `DaoCollectionImpl` (Lazy field + ctor param + accessor),
   `DaoProvider` default method, and the Dagger bind module
   (`JooqLoomDaoBindModule`).
5. `VectorConfigDaoTest` implementing `CRUDDaoTestcases`.

After DI changes: clean-rebuild loom/core and re-run ./setup-pool.sh before running
dependent tests (see agent notes).
```

**References:** [DOMAIN.md](DOMAIN.md) §4 Vector Config; [PERSISTENCE.md](PERSISTENCE.md) §Adding a New Entity, §DaoCollection and Dagger DI; migration `V2.6__add_vector_config.sql`
**Test Requirements:** `VectorConfigDaoTest` with full `CRUDDaoTestcases` coverage; `mvn test-compile -q -DskipTests` clean across `loom/db`.

---

## Task: Implement LoomDao for the system singleton row

**Argumentation Summary:** The `loom` table ([DOMAIN.md](DOMAIN.md) §7, migration `V2.5__add_loom.sql`: DB revision + last-used timestamp) has no DAO. Any code needing the system row must bypass the DAO layer, breaking the persistence abstraction.
**Improvement Summary:** A minimal `LoomDao` for reading/updating the singleton system row, registered in `DaoCollection`.

```
The `loom` table is a singleton system row, not a standard CRUD entity — model the DAO
accordingly rather than forcing `CRUDDao`:

1. `Loom.java` model interface in `loom-db-api/.../model/loom/` (fields per V2.5:
   database revision, last-used timestamp).
2. `LoomDao.java` with `Loom load()`, `void update(Loom loom)` (and a
   `Loom createLoom(...)` seed method if the row is not created by migration —
   check V2.5 for an INSERT).
3. `LoomImpl` + `LoomDaoImpl` in `loom-db-jooq` using `JooqLoom`.
4. Register in `DaoCollection`/`DaoCollectionImpl`/`DaoProvider`/Dagger bind module.
5. `LoomDaoTest`: load the singleton, update the revision/timestamp, reload and assert.

After DI changes: clean-rebuild loom/core and re-run ./setup-pool.sh.
```

**References:** [DOMAIN.md](DOMAIN.md) §7 System; migration `V2.5__add_loom.sql`; [PERSISTENCE.md](PERSISTENCE.md) §DaoCollection and Dagger DI
**Test Requirements:** `LoomDaoTest` covering load/update round-trip of the singleton row.

---

## Task: Implement Asset Remix DAO operations

**Argumentation Summary:** The `asset_remix` table ([DOMAIN.md](DOMAIN.md) §2, migration `V2.8__add_asset.sql`) models derivation links between assets, but a repo-wide search finds no remix-related code in `loom-db-api` or `loom-db-jooq` — the table is completely unreachable.
**Improvement Summary:** Remix link operations (add/remove/list) exposed on `AssetDao` (join-table pattern) or as a dedicated `AssetRemixDao`.

```
`asset_remix` is an asset↔asset join table. Follow the existing cross-table pattern
(PERSISTENCE.md §4 Cross-Table Operations, e.g. tag_asset handling in TagDaoImpl/AssetDaoImpl):

1. Read V2.8 for the exact columns of `asset_remix` (two asset FKs + any
   relation-type column).
2. Add methods to `AssetDao` / `AssetDaoImpl`:
   - `addRemix(SHA512Sum origin, SHA512Sum derived, ...)`
   - `removeRemix(...)` (use `deleteCrossTableEntry()` helper)
   - `Result/List loadRemixes(SHA512Sum asset)` (both directions if the relation
     is directed).
   Use a dedicated `AssetRemixDao` instead only if the table carries enough own
   state to justify it.
3. Extend `AssetDaoTest` with remix tests: link two assets, list from both sides,
   unlink, and verify deleting an asset cascades the remix rows (V2.8 defines
   ON DELETE CASCADE on both FKs).
```

**References:** [DOMAIN.md](DOMAIN.md) §2 Asset Remix; migration `V2.8__add_asset.sql`; [PERSISTENCE.md](PERSISTENCE.md) §Cross-Table Operations
**Test Requirements:** Link/list/unlink tests in `AssetDaoTest` (or new `AssetRemixDaoTest`) plus cascade verification on asset deletion.

---

## Task: Add asset delete-cascade tests

**Argumentation Summary:** The asset is the hub of the schema: `asset_location` (V2.10), all seven component tables (V2.18/V2.23), `embedding` (V2.12), `blacklist` (V2.14), `annotation_asset` (V2.16), `asset_remix`/`asset_user_meta` (V2.8) and `person_image` (V2.26) all declare `ON DELETE CASCADE` on the asset FK. None of these cascades are tested; a migration regression (e.g. a recreated FK without CASCADE) would surface as production delete failures or orphans.
**Improvement Summary:** A dedicated test verifying that deleting an asset removes all dependent rows across every cascading table.

```
Add cascade tests to `AssetDaoTest` (or a new `AssetCascadeTest` in the same package,
modeled on `TaskDaoTest.testDeleteCascadesCommentSubtree`):

1. Create an asset and attach one row in each cascading table:
   asset_location, asset_geo_comp, asset_doc_comp, asset_image_comp, asset_video_comp,
   asset_audio_comp, asset_transcript_comp, asset_json_comp, embedding, blacklist,
   annotation (+ annotation_asset link), asset_remix (once implemented), person_image
   (via personDao image gallery).
2. Delete the asset via `assetDao().delete(...)`.
3. Assert every dependent row is gone (load returns null / count is 0) and that
   unrelated rows (e.g. a second asset's components) survive.

Note: asset PK is the sha512sum — create dependents through the existing DAOs, not raw SQL,
so the test also validates DAO-level FK usage.
```

**References:** migrations `V2.8`, `V2.10`, `V2.12`, `V2.14`, `V2.16`, `V2.18`, `V2.23`, `V2.26`; existing pattern `TaskDaoTest.testDeleteCascadesCommentSubtree`
**Test Requirements:** One assertion per cascading table; negative control (second asset's rows untouched).

---

## Task: Add ACL delete-cascade tests (user/role/group join tables)

**Argumentation Summary:** V2.1 defines cascades from `user`, `role` and `group` into `user_permission`, `role_permission`, `user_group` and `role_group`. Deleting a role or group must clean up memberships and grants — untested today, and particularly subtle because `UserDaoImpl` uses soft-delete (cascade only fires on hard delete).
**Improvement Summary:** Cascade tests for role and group deletion, plus a test pinning the soft-delete semantics of user deletion.

```
Add to `RoleDaoTest` / `GroupDaoTest` (and `UserDaoTest` for the soft-delete case):

1. Role: create role, grant a permission, assign to a group; delete role → assert
   role_permission and role_group rows are gone; group itself survives.
2. Group: create group with a user member and a role; delete group → assert user_group
   and role_group rows are gone; user and role survive.
3. User: create user with permission + group membership; call userDao().delete(...) →
   pin the actual behavior: soft-delete keeps join rows (assert they remain and the user
   is filtered from load()), documenting that cascades only apply to hard deletes.
```

**References:** migration `V2.1__add_acl.sql`; [PERSISTENCE.md](PERSISTENCE.md) §Soft Deletes
**Test Requirements:** Role and group cascade verified with surviving-neighbor assertions; user soft-delete semantics pinned explicitly.

---

## Task: Add pipeline hierarchy delete-cascade tests (pipeline → version/run → state)

**Argumentation Summary:** `pipeline_version` (V2.30), `pipeline_run` (V2.29) and the execution-state tables (V2.31: `pipeline_run_item`, `pipeline_node_task` referencing run) cascade from their parents. Existing tests cover run→items and item→tasks, but pipeline→versions, pipeline→runs and run→node_task (the direct run FK on node tasks) are untested.
**Improvement Summary:** Complete the cascade chain: deleting a pipeline removes its versions, runs, items and node tasks transitively.

```
Extend the pipeline DAO tests (PipelineDaoTest / PipelineVersionDaoTest, reuse
`PipelineFixtures`):

1. Pipeline → versions: create pipeline with 2 versions; delete pipeline → versions gone.
2. Pipeline → runs (transitive): create pipeline, run, run item, node task; delete the
   pipeline → run, item and node task all gone (full V2.29/V2.31 chain).
3. Run → node tasks (direct FK from V2.31): create run with an item and node task;
   delete the run → node tasks gone even though the item cascade also covers them —
   this pins the direct run FK.
4. Negative control: a second pipeline's runs/versions survive.
```

**References:** migrations `V2.29__add_pipeline_run.sql`, `V2.30__add_pipeline_version.sql`, `V2.31__add_pipeline_execution_state.sql`; existing tests `PipelineRunItemDaoTest.testDeletingARunCascadesToItsItems`, `PipelineNodeTaskDaoTest.testDeletingAnItemCascadesToItsTasks`
**Test Requirements:** Transitive pipeline-deletion test plus direct run→node_task cascade; negative control included.

---

## Task: Add annotation, tag and cluster join-table cascade tests

**Argumentation Summary:** V2.16 cascades annotation joins (`annotation_asset`, `annotation_task`, `annotation_tag`) from annotation, task and tag; V2.12 cascades `embedding_cluster` from cluster and `collection_cluster` from collection. None are tested — orphaned join rows would silently accumulate or deletes would fail if a cascade is lost.
**Improvement Summary:** Cascade tests for each join-table direction in the annotation and clustering subsystems.

```
Add tests to AnnotationDaoTest, TagDaoTest, ClusterDaoTest, CollectionDaoTest:

1. Annotation: create annotation linked to asset, task and tag; delete the annotation →
   all three join rows gone; asset/task/tag survive.
2. Tag: delete a tag that is linked to an annotation → annotation_tag row gone,
   annotation survives.
3. Task: delete a task linked to an annotation → annotation_task row gone, annotation
   survives (complements the existing V2.35 comment-cascade test in TaskDaoTest).
4. Cluster: create embeddings linked via embedding_cluster; delete the cluster →
   join rows gone, embeddings survive.
5. Collection: link a cluster via collection_cluster; delete the collection →
   join row gone, cluster survives.
```

**References:** migrations `V2.12__add_embedding.sql`, `V2.16__add_annotation.sql`, `V2.35__add_task_delete_cascade.sql`; [DOMAIN.md](DOMAIN.md) §3/§4
**Test Requirements:** Each join-table direction tested with the joined entity asserted to survive.

---

## Task: Add person and cortex-instance cascade tests

**Argumentation Summary:** V2.26 cascades `person_image` from both `person` and `asset`; V2.33 cascades `cortex_instance_node_kind` from `cortex_instance`. The asset→person_image direction is covered by the asset cascade task; the person→person_image and cortex directions remain untested.
**Improvement Summary:** Cascade tests for person deletion (image gallery cleanup, primary image handling) and cortex instance deletion (node-kind whitelist cleanup).

```
1. In PersonDaoTest: create a person with 2+ gallery images (person_image rows) and a
   primary image; delete the person → person_image rows gone, the referenced assets
   survive. Also pin the primary_image_uuid behavior when the referenced asset is
   deleted (SET NULL vs CASCADE vs RESTRICT — read V2.26 for the actual FK action and
   assert it).
2. In CortexInstanceDaoTest: register an instance with node-kind whitelist/blacklist
   entries; delete the instance → cortex_instance_node_kind rows gone.
```

**References:** migrations `V2.26__add_person.sql`, `V2.33__add_cortex_instance.sql`; [DOMAIN.md](DOMAIN.md) §4 Person, §5 Cortex Instance
**Test Requirements:** person→person_image and cortex_instance→node_kind cascades verified; primary-image FK action pinned by test.

---

## Task: Update PERSISTENCE.md progress tracker after task completion

**Argumentation Summary:** PERSISTENCE.md carries a Progress Tracker (§Completed Entities, §In-Progress/TODO) that agents rely on for gap analysis. It is already stale (e.g. missing PipelineRun/Version/RunItem/NodeTask/CortexInstance rows, missing V2.29–V2.35 in the migration history). Completing the tasks above without updating it would compound the drift.
**Improvement Summary:** Bring the tracker, migration history and test-class table in PERSISTENCE.md in sync with the actual codebase.

```
After completing the tasks in this file (or in batches):

1. Add V2.29–V2.35 to the Migration History and Migration File Index sections.
2. Add rows for PipelineRun, PipelineVersion, PipelineRunItem, PipelineNodeTask,
   CortexInstance, VectorConfig, Loom to the entity/progress tables.
3. Move resolved entries out of "In-Progress / TODO" (AssetPool, Detection, Chat,
   AssetBinary, Role, Permission) and add the new test classes to the
   "Existing DAO Tests" table.
```

**References:** [PERSISTENCE.md](PERSISTENCE.md) §Progress Tracker, §Migration History
**Test Requirements:** None (documentation); verify tables match `find`-based reality check of DAO/test files.
