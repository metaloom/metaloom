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
- [x] Task 2 — `DetectionDaoTest`
- [x] Task 3 — `ChatDaoTest`
- [x] Task 4 — `AssetBinaryDaoTest` + resolve the `AssetBinaryDao`/`AssetLocationDao` duplication
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
