# Persistence Layer Tasks

Task list for closing gaps in the persistence layer, derived from a comparison of
[DOMAIN.md](DOMAIN.md), [PERSISTENCE.md](PERSISTENCE.md), the `DaoCollection` /
`DaoCollectionImpl` DAO registry, the Flyway migrations (`V1` – `V2.50`) and the
existing test classes in `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/`.

> **Status note (2026-07-24):** the asset-component test gap has been closed by the
> V2.38–V2.50 rework — `AssetComponentKeyTest`, `AssetTranscriptCompDaoTest`,
> `AssetFingerprintSegmentCompDaoTest`, `AssetNodeResultDaoTest` and the extended
> `AssetJsonCompDaoTest` now cover every modality with provenance, idempotency and
> multi-source coexistence. See [../features/db/DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md).
> Remaining gaps below are still open.

**Gap summary:**

| Area | Gap |
|---|---|
| Missing CRUD tests | `AssetPoolDaoTest`, `DetectionDaoTest`, `ChatDaoTest`, `AssetBinaryDaoTest`, empty `RoleDaoTest`, thin `PermissionDaoTest` |
| ~~AssetComponent tests~~ | ✅ **Done** — see the status note above |
| Missing domain DAOs | `VectorConfigDao` (V2.6), ~~`LoomDao` (V2.5 singleton)~~ ✅ **Done** — `LoomDao`/`LoomDaoImpl` + `LoomDaoTest`, Asset Remix operations (V2.8 `asset_remix`) |
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

## Task: Add AssetComponentDaoTest covering all component modalities — ✅ DONE (2026-07-24)

> **Implemented** by the V2.38–V2.50 component rework rather than as a single
> `AssetComponentDaoTest`. The component tables were rebuilt on a shared contract
> (provenance columns + per-table idempotency key), so coverage is spread across:
> - `AssetComponentKeyTest` — geo/doc/image/video/audio identity keys, multi-producer
>   coexistence, upsert-replace, machine-written (null-audit) rows;
> - `AssetTranscriptCompDaoTest` — per-track transcripts, model-upgrade upsert, FTS;
> - `AssetFingerprintSegmentCompDaoTest` — fingerprint + segment components;
> - `AssetJsonCompDaoTest` (extended) — variant discriminator, GIN containment;
> - `AssetNodeResultDaoTest` — the per-asset processing ledger.
>
> "Multiple components of the same modality coexist on one asset" is verified through the
> typed discriminators (`stream_index`, `page_number`, `lang`, `node_kind`) rather than the
> old free-text `source`. See [../features/db/DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md).

**References:** migrations `V2.38`–`V2.45`; [../features/db/DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md)
**Test Requirements:** Met — every modality covered with round-trip, idempotency and coexistence assertions.

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

## Task: Update PERSISTENCE.md progress tracker after task completion — ✅ DONE (2026-07-24)

**Argumentation Summary:** PERSISTENCE.md carries a Progress Tracker (§Completed Entities, §In-Progress/TODO) that agents rely on for gap analysis. It had drifted well past the point this task originally described — missing everything from V2.29 onward.
**Improvement Summary:** Bring the tracker, migration history and test-class table in PERSISTENCE.md in sync with the actual codebase.

> **Steps 1 and 2 are done** (2026-07-24), and further than the original scope:
> - Migration History and Migration File Index now run **V2.29–V2.50** (not just V2.29–V2.35).
> - Entity model, Completed-Entities and jOOQ-tables tables gained `PipelineRun/Version/RunItem/NodeTask`,
>   `CortexInstance`, `Skill`, `SkillVersion`, `AssetNodeResult`, `AssetFingerprint/SegmentComp`;
>   `VectorConfig` and `Loom` were added to In-Progress/TODO (jOOQ table but no domain DAO).
> - The "Existing DAO Tests" table now lists all 35 test classes.
>
> **Step 3 does not apply yet.** The original wording assumed the CRUD-test tasks above
> would be finished first, and told the editor to move `AssetPool`, `Detection`, `Chat`,
> `AssetBinary`, `Role` and `Permission` out of In-Progress/TODO. Those tests still do not
> exist (`RoleDaoTest` is still an empty class), so those entries stay put — moving them
> would misreport coverage. Revisit when the tasks above land.

**References:** [PERSISTENCE.md](PERSISTENCE.md) §Progress Tracker, §Migration History; [../features/db/DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md)
**Test Requirements:** None (documentation); tracker/history/test tables verified against the `migration/`, `tables/` and `dao/` directories.

---

*Derived against migrations `V1`–`V2.50`. GIT HEAD: `b3b619287fd4d557c3adb232f6354a37702c3690` · Updated: 2026-07-24*
