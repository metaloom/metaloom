# MetaLoom Database — Asset Result Persistence (build record)

> **Status: COMPLETE.** Tasks 1–13 landed on 2026-07-23 as migrations `V2.38`–`V2.50`.
> This file is kept as the **historical record** of the component / node-result schema
> rework and as the reference for the two contracts other specs cite: the three-layer
> result model (§2) and the shared component contract (§4).
> Format follows [../../TASKS.template.md](../../tasks/TASKS.template.md).
>
> **Do not add new persistence work here.** Open DAO/test gaps live in
> [../../loom/PERSISTENCE_TASKS.md](../../tasks/PERSISTENCE_TASKS.md); the current entity
> inventory (through `V2.63`) lives in [../../loom/DOMAIN.md](../../loom/DOMAIN.md).
>
> **Context:** [../pipeline-nodes/NODES.md](../nodes/NODES.md) (what nodes
> produce) · [../DB_SCHEMA_FEEDBACK.md](../DB_SCHEMA_FEEDBACK.md) (the audit that
> motivated this list) · [../pipeline/PIPELINE.md](../pipeline/PIPELINE.md) (run/task
> ledger) · [../../loom/PERSISTENCE.md](../../loom/PERSISTENCE.md) (DAO layer, jOOQ
> codegen, migration workflow)

---

## 1. Outcome

Before this rework exactly one node persisted anything to Loom (`WhisperNode` →
`createAssetTranscript`). The component tables existed but could not receive node output:
no idempotency key (a retry appended a row), no provenance (`source varchar` carried four
different meanings), and no multiplicity discriminator (two audio tracks were
inexpressible). Migrations were allowed to be destructive because nothing read or wrote
those tables yet.

| Task | Migration | What landed |
|---|---|---|
| 1 — Rework geo/doc/image/video/audio comps | `V2.38` | All five dropped and recreated on the §4 contract; typed discriminators (`method`+`time_from`, `page_number`, `stream_index`); restored `image_encoding`, added `fps`/`frame_count`/`blurriness`/`orientation`/`bit_depth`/`rotation`; generated `text_search tsvector` on `asset_doc_comp` |
| 2 — Rework `asset_transcript_comp` | `V2.39` | Key `(asset_uuid, node_kind, stream_index, lang)`; `audio_comp_uuid` FK `ON DELETE SET NULL`; `transcript_json` kept for the UI; generated `text_search`; `duration` moved to **bigint milliseconds** |
| 3 — Harden `asset_json_comp` as the generic sink | `V2.40` | `schema_type NOT NULL` + `variant`, key `(asset_uuid, node_kind, schema_type, variant)`, `data NOT NULL DEFAULT '{}'`, GIN `jsonb_path_ops`, promotion policy as a table COMMENT |
| 4 — Add `asset_fingerprint_comp` | `V2.41` | One row per sector, key `(asset_uuid, node_kind, algorithm, sector_index)`, dedup lookup index on `(algorithm, fingerprint)` |
| 5 — Add `asset_segment_comp` | `V2.42` | Scenes / silence / shots / chapters in one table, key `(asset_uuid, node_kind, segment_type, seq)`, range CHECK, overlap index |
| 6 — Rework `detection` / `embedding` | `V2.43` | Provenance + idempotency keys on both; `embedding.detection_uuid` FK; duplicated geometry dropped (one normalized convention); `dimensions` added; camelCase `fromTime`/`toTime` → `time_from`/`time_to`; **no pgvector** (decision deferred, recorded in a column COMMENT) |
| 7 — `attachment` as the derived-binary sink | `V2.44` | `CONTACT_SHEET`/`POSTER_FRAME`/`WAVEFORM`/`PROXY`/`EXTRACTED_AUDIO` enum values; provenance + `variant`; partial unique index `(asset_uuid, type, node_kind, variant)`; `asset_uuid` gained `ON DELETE CASCADE` |
| 8 — Add `asset_node_result` ledger | `V2.45` | One row per `(asset_uuid, node_kind, node_id)` with `state`/`origin`/`producer_version`/timings/`result_ref`; invalidation index on `(node_kind, producer_version)` |
| 9 — Asset identity + consistency flag | `V2.46` | `asset.uuid` is now the PRIMARY KEY, `sha512sum` a `NOT NULL UNIQUE` natural key; `is_complete` added; legacy `s3_bucket_name`/`s3_object_path` dropped |
| 10 — Nullable audit columns | `V2.47` | `creator_uuid`/`editor_uuid` relaxed on machine-written tables — a Cortex worker is not a user (precedent: `V2.33` `cortex_instance`) |
| 11 — Permission model for the new tables | — | **No enum change.** Components, `asset_fingerprint_comp`, `asset_segment_comp` and `asset_node_result` are sub-resources of an asset (`READ_ASSET` / `UPDATE_ASSET`); `detection` and `embedding` keep their dedicated permissions. Rule written into [../permissions/PERMISSIONS.md](../permissions/PERMISSIONS.md) §2.5 |
| 12 — Java model + DAO realignment | — | `AssetComponent` gained `nodeKind`/`nodeId`/`producerVersion`/`runUuid`/`taskUuid`/`confidence`; `loadXComp`-by-key and `upsertXComp` (`onConflict().doUpdate()`, never `store()`); new `AssetNodeResultDao` registered on `DaoCollection` |
| 13 — Regenerate `dbdiagram.yaml` | — | Regenerated through `V2.50`, resolved findings annotated in [../DB_SCHEMA_FEEDBACK.md](../DB_SCHEMA_FEEDBACK.md). **Has since gone stale — see Task 14.** |

Follow-up in the same change (`V2.48`–`V2.50`) fixed pre-existing suite failures:
`asset_location`'s bogus `UNIQUE (asset_uuid)` replaced by the real natural key
`(library_uuid, path)`; `reaction`/`comment` → `annotation` cascade; the
`pipeline.latest_version_uuid` / `skill.active_version_uuid` FK cycle that made
`DELETE /pipelines/:uuid` always fail; and the missing `blacklist.name` column that the
whole stack already spoke. Product-code defects fixed alongside: `ClusterEndpointService.create`
(every create 500'd), `AssetBinaryEndpointService.update` (a `// TODO` that silently kept the
old path), unknown sort key → 400 instead of 500, `PipelineModelValidator` `ClassCastException`,
and `UserModelBuilder.setStatus` NPE'ing on machine-written rows.

### 1.1 Deviations from the plan

1. **`attachment` got no "exactly one target" CHECK.** The plan called for
   `num_nonnulls(asset_uuid, embedding_uuid) = 1`. `TestFixtureProvider` disproves the
   assumption: an `EMBEDDING_ATTACHMENT` deliberately carries **both**. Finding withdrawn,
   reasoning written into `V2.44`.
2. **`attachment.asset_uuid` gained `ON DELETE CASCADE`** even though delete cascades were
   scoped out — a thumbnail blocking deletion of the asset it depicts is unusable.
3. **`detection`/`embedding` needed more model work than planned**; `EmbeddingDaoImpl.store`
   derives `dimensions` from the vector length rather than making callers repeat it.
4. **The REST/GraphQL field stays named `source`**, mapped to `node_kind` in the builders.
   Renaming the public field belongs with the endpoint rework.

---

## 2. The three-layer result model

*(Cited by [../pipeline/PIPELINE.md](../pipeline/PIPELINE.md) and the node plans —
keep this section and its numbering stable.)*

```
                    ┌─────────────────────────────────────────────┐
                    │ Layer 3 — asset_node_result (ledger)        │
                    │ "has node X @ version V processed asset A?" │
                    │ node-agnostic, one row per (asset,node)     │
                    └─────────────────────────────────────────────┘
                                       │ describes
        ┌──────────────────────────────┴──────────────────────────────┐
        ▼                                                             ▼
┌───────────────────────────────────┐        ┌──────────────────────────────────┐
│ Layer 1 — typed component tables  │        │ Layer 2 — asset_json_comp        │
│ asset_geo/doc/image/video/audio/  │        │ generic, node-agnostic sink      │
│ transcript/fingerprint/segment    │        │ keyed by schema_type + variant   │
│ + detection / embedding           │        │                                  │
│ USE WHEN a feature must filter,   │        │ USE WHEN the result is opaque    │
│ search, sort, or render the value │        │ to Loom, or the node is new      │
└───────────────────────────────────┘        └──────────────────────────────────┘
```

**Promotion policy (Layer 2 → Layer 1).** A new node kind starts in `asset_json_comp`. It
graduates to a typed table when — and only when — one of these becomes true:

1. a query must filter or sort on a field inside the JSON,
2. the UI renders it as a first-class object with its own lifecycle,
3. it must participate in a foreign-key relationship.

**Layer 3 vs. `pipeline_node_task`.** `asset_node_result` is per *asset*: catalog state,
outlives every run, keyed by `(asset_uuid, node_kind, node_id)`. `pipeline_node_task` is
per *run item*: execution state, pruned with the run, keyed by `(item_uuid, node_id, …)`.
Both may exist for one execution; `run_uuid`/`task_uuid` are the join. `AbstractMediaNode`
writes the ledger generically, replacing the old field-probing short-circuit
(`md5 != null` and friends) which never generalised to JSON-blob or legitimately-empty
results.

---

## 3. Rules the schema encodes

- Multiplicity is **always** expressed by typed columns (`stream_index`, `page_number`,
  `lang`, `sector_index`, `seq`, `frame_number`+`detection_index`), never by appending
  rows with the same key.
- **Never gate a component write on the asset's mime type.** An MP3 legitimately owns an
  `asset_image_comp` (cover art); a PDF has embedded images; a video has a document track.
- Two producers of the same *kind* of fact coexist by `node_kind` (EXIF geo vs. an LLM
  location guess; Tika probing a video vs. `QualityNode` measuring it). The same producer
  re-running **replaces in place** — `producer_version` is deliberately *outside* the
  unique key, so a model upgrade upserts and
  `WHERE node_kind = ? AND producer_version <> ?` finds everything stale.
- **Read-side merge rule.** Because `node_kind` is in every key, two producers of the same
  dimension yield two partially-filled rows. A consumer that wants "the" width of a video
  coalesces across the rows for that `stream_index` in a documented producer precedence,
  in `AssetComponentModelBuilder`. Do **not** make writers merge into one row — that
  reintroduces the lost-update problem the keys exist to prevent.
- Intrinsic properties of the **bytes** (hashes, size, `zero_chunk_count`, `is_complete`)
  stay on `asset`. Everything *derived by interpretation* goes into a component table.
- `asset.sha512sum` stays `NOT NULL`: no asset row exists before a hashing node has run.
  Nodes upstream of hashing hold their outputs in `pipeline_node_task.outputs`, and
  `pipeline_run_item` carries the pre-hash identity (`media_path` + nullable sha512).
  Recorded in a `COMMENT ON TABLE "asset"` so it is not re-litigated.
- Segment sets replace at the **set** level: a re-run writes `seq` `0..N-1` and must
  `DELETE` rows with `seq >= N` for that `(asset, node_kind, segment_type)`. The one place
  where the upsert is not a single statement.

---

## 4. The shared component contract

*(Cited by [../permissions/PERMISSIONS.md](../permissions/PERMISSIONS.md) §4 — every new
`asset_*_comp` table must follow it. Deviating is a review failure.)*

```sql
uuid              uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
asset_uuid        uuid    NOT NULL REFERENCES "asset" ("uuid") ON DELETE CASCADE,

-- provenance ---------------------------------------------------------------
node_kind         varchar NOT NULL,              -- 'whisper','ocr','tika','facedetect','manual'
node_id           varchar,                       -- graph-local id; NULL when not pipeline-produced
producer_version  varchar NOT NULL DEFAULT '',   -- model/algorithm version, e.g. 'whisper-large-v3'
run_uuid          uuid REFERENCES "pipeline_run" ("uuid")       ON DELETE SET NULL,
task_uuid         uuid REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
confidence        real,

meta              jsonb,

-- audit (nullable: these rows are written by workers, not users) ------------
created           timestamp NOT NULL DEFAULT now(),
creator_uuid      uuid REFERENCES "user" ("uuid"),
edited            timestamp NOT NULL DEFAULT now(),
editor_uuid       uuid REFERENCES "user" ("uuid")
```

plus, per table, typed discriminators and `UNIQUE (asset_uuid, node_kind, <discriminators>)`.
There is no `source` column anywhere: its four conflicting meanings split into `node_kind`
(who), `producer_version` (with what) and — geo only — `method` (how).

### 4.1 Per-table keys as built

| Table | Unique key |
|---|---|
| `asset_geo_comp` | `(asset_uuid, node_kind, method, time_from)` |
| `asset_doc_comp` | `(asset_uuid, node_kind, page_number)` — `0` = whole document |
| `asset_image_comp` | `(asset_uuid, node_kind, stream_index)` |
| `asset_video_comp` | `(asset_uuid, node_kind, stream_index)` |
| `asset_audio_comp` | `(asset_uuid, node_kind, stream_index)` |
| `asset_transcript_comp` | `(asset_uuid, node_kind, stream_index, lang)` |
| `asset_json_comp` | `(asset_uuid, node_kind, schema_type, variant)` |
| `asset_fingerprint_comp` | `(asset_uuid, node_kind, algorithm, sector_index)` |
| `asset_segment_comp` | `(asset_uuid, node_kind, segment_type, seq)` |
| `detection` | `(asset_uuid, node_kind, frame_number, detection_index)` |
| `embedding` | `(asset_uuid, node_kind, type, frame_number, subject_index)` |
| `attachment` | partial unique index `(asset_uuid, type, node_kind, variant)` |
| `asset_node_result` | `(asset_uuid, node_kind, node_id)` |

`asset_transcript_comp` carries **both** `audio_comp_uuid` and `stream_index`: the FK is
for navigation and cascade, the index is in the key because an audio-only asset may be
transcribed before any audio component row exists. Column-level detail is in the
migrations and in [../../loom/DOMAIN.md](../../loom/DOMAIN.md); the ER diagram is
`loom/design/DB/dbdiagram.yaml`.

---

## Task 14: Re-sync `loom/design/DB/dbdiagram.yaml` to the current schema

**Argumentation Summary:** Task 13 regenerated the diagram through `V2.50`, and its header
still says "up to and including V2.50". Thirteen migrations have landed since — `V2.51`
(`embedding_cluster` cascade), `V2.52` `chat_session`, `V2.53`/`V2.54` agent memory,
`V2.55` (**`webhook` table and the `loom_events` enum dropped**, so the diagram documents
tables that no longer exist), `V2.56`–`V2.57`, `V2.58`/`V2.59` `search_document` + its
triggers, `V2.60` per-element node task key, `V2.61`/`V2.62` `dedup_group`, `V2.63`
`library.pool_uuid` / `attachment_binary.pool_uuid`. The diagram is the artefact people
read before they read SQL, and it is now wrong in both directions.

**Improvement Summary:** Regenerate the diagram from the migrations through `V2.63` and
update its header, keeping the existing conventions.

```
1. Update loom/design/DB/dbdiagram.yaml from the migrations through V2.63.
   Preserve the conventions already in the file: headercolor per group, tablegroup
   blocks, notes on non-obvious columns.
2. Remove `webhook` and the `loom_events` enum (dropped by V2.55).
3. Add tablegroups for the entities added since V2.50: chat_session, memory_entry /
   memory_deny_rule, search_document, dedup_group / dedup_group_member.
4. Add the V2.63 pool pointers: library.pool_uuid and attachment_binary.pool_uuid,
   both ON DELETE RESTRICT.
5. Update the Project note's "up to and including V2.xx" line (line ~6).
```

**References:** `loom/design/DB/dbdiagram.yaml` ·
[../../loom/DOMAIN.md](../../loom/DOMAIN.md) (entity inventory, current through `V2.63`) ·
migrations `V2.51`–`V2.63`

**Test Requirements:** None (documentation). Verify by pasting into dbdiagram.io and
confirming it renders without parse errors.

---

## 5. Test setup

```bash
./setup-pool.sh                     # once, and again after EVERY Flyway change
loom/db/jooq/generate.sh            # testcontainer -> flyway migrate -> jOOQ codegen
mvn test -pl loom/db/jooq           # DAO round-trip + constraint tests
mvn test -pl loom/core              # endpoint tests (needs the pool)
```

`AbstractJooqTest` provides `asset()`, `dummyUser()` and `assetComponentDao()`;
`AssetJsonCompDaoTest` is the reference for a component DAO test. Full workflow:
[../../loom/PERSISTENCE.md](../../loom/PERSISTENCE.md).

---

## 6. Conventions and Gotchas

- **`./setup-pool.sh` after every Flyway change** — otherwise `loom/core` tests fail with
  `Pool not found {loom-dev}`. Compile `loom/fixture` first.
- **`store()` is INSERT-only** and throws on the second write. Every idempotent write path
  must use `insertInto(...).onConflict(...).doUpdate()`. This bit `PipelineRunTracker`
  already (fixed 2026-07-18 — see `PipelineRunTracker.complete(...)`).
- **`ALTER TYPE … ADD VALUE` cannot be *used* in the transaction that added it**, and
  Flyway wraps each migration in one. Add the value in its own migration and seed grants in
  a later one (`V2.57`, `V2.62` are the pattern), or use
  `-- flyway:executeInTransaction=false`.
- **`user_permission` has `PRIMARY KEY (user_uuid)`** — one direct grant per user, ever.
  Grant test permissions via group+role (`SkillEndpointTest` pattern). Known defect, still
  open (§7).
- **Clean-rebuild `loom/core`** after endpoint constructor changes, or Dagger fails at
  runtime with `NoSuchMethodError`.
- **Generated columns.** A `tsvector GENERATED ALWAYS AS … STORED` column must be excluded
  from codegen or jOOQ tries to write it on insert.
- **`jsonb` columns** only arrive as `io.vertx.core.json.JsonObject` if they match the
  `forcedTypes` `includeExpression` in `loom/db/jooq/pom.xml`; otherwise they come through
  as `org.jooq.JSONB` and need manual conversion, as in `AssetJsonCompImpl`.
- **Milliseconds, everywhere.** New time columns are `bigint` milliseconds.
- **`timestamp`, not `timestamptz`** — the whole schema uses `timestamp without time zone`;
  new tables follow suit (converting is out of scope, §7).

---

## 7. Progress Assessment

### Delivered (see §1 for the outcome table)

- [x] **Tasks 1–10** — schema rework, `V2.38`–`V2.47`
- [x] **Task 11** — permission rule (sub-resource of asset, no enum change)
- [x] **Task 12** — Java model + DAO realignment, upsert-by-key
- [x] **Task 13** — `dbdiagram.yaml` regenerated (through `V2.50` only — superseded by Task 14)
- [x] **`V2.48`–`V2.50`** — pre-existing suite failures fixed (§1)
- [x] REST endpoints for the new tables — `FingerprintCompEndpointService`,
      `SegmentCompEndpointService`, `JsonCompEndpointService`, `NodeResultEndpointService`
      (`/assets/:uuid/node-results`)
- [x] **Asset delete cascades (§2.6)** — `tag_asset` (`V2.72`), `collection_asset` / `asset_task` /
      `asset_user_meta` (`V2.73`), `comment` / `reaction` / `library_asset` (`V2.74`). Everything
      said *about* an asset dies with it; the tag, collection, task, library and user survive, as
      does social content anchored to a task. The only remaining non-cascading asset FKs are two
      intentional `SET NULL`s. Asserted from both sides by `AssetCascadeTest` over a shared
      two-asset fixture, and over REST by `AssetEndpointTest` / `TagAssetEndpointTest`
- [x] Node write-back — most Cortex nodes now persist through `client()`
      (`createAssetJsonComp`, `createAssetSegmentComps`, `createAssetFingerprintComp`,
      `createAssetTranscript`), and `AbstractMediaNode` records the ledger row generically

### Still open

- [ ] **Task 14** — re-sync `dbdiagram.yaml` to `V2.63` (the only actionable item owned by
      this file; full detail above)
- [ ] **DAO/test gaps** — including the `DetectionDaoTest` that §10.2 of the old revision
      wrongly claimed to have run (see the correction below). Tracked in
      [../../loom/PERSISTENCE_TASKS.md](../../tasks/PERSISTENCE_TASKS.md), not here.
- [ ] **ACL primary keys** — `user_permission` / `token_permission` / `role_permission` are
      still `PRIMARY KEY (user_uuid)` etc. (`V2.1`, unchanged) —
      [../DB_SCHEMA_FEEDBACK.md](../DB_SCHEMA_FEEDBACK.md) §7.1
- [x] **`tag_asset`** — done in `V2.71`: surrogate `uuid` PK plus
      `UNIQUE NULLS NOT DISTINCT (tag_uuid, asset_uuid, time_from, time_to, areaStartX, areaStartY)`,
      so one tag can be placed once per *region* rather than once per asset, and provenance columns
      (`node_kind` default `manual`, `node_id`, `producer_version`, `confidence`, `created`,
      `creator_uuid`) say who attached it (§5.1, §5.4). ⚠️ Needs PostgreSQL 15+
- [ ] **`timestamptz` sweep** (§8.1) — no migration uses `timestamptz`; and `filekey_*`
      widening on `asset_location` (still `int`, `V2.10`) (§2.4)
- [ ] **pgvector vs. external vector index** — `embedding.vector` still has no ANN index
      (§4.2; also [../../loom/DOMAIN.md](../../loom/DOMAIN.md))
- [ ] **Execution-ledger retention** — policy decided in
      [../pipeline/PIPELINE.md](../pipeline/PIPELINE.md) §10.1a (7 days of per-item detail,
      30 for failures, the `pipeline_run` row forever); no sweep is implemented
- [ ] **`AssetBulkUpdateEntry` still carries only `HashInfo`** — the generic bulk sync path
      cannot express component payloads; nodes write per-resource endpoints instead
- [ ] **`asset_doc_comp` has no producer** — OCR/Tika still write `asset_json_comp`; the
      typed table is the documented graduation path (§2)

### 7.1 Correction to the original verification record

The superseded §10.2 listed `DetectionDaoTest` among the DAO suites verified on
2026-07-23. **That class has never existed** — the run was `DetectionEndpointTest`
(`loom/core/src/test/java/io/metaloom/loom/core/endpoint/test/DetectionEndpointTest.java`).
`detection`'s `V2.43` provenance columns and idempotency key are therefore pinned by no DAO
test; the only test-side use of `detectionDao()` is inside `AssetCascadeTest`. Writing that
test is an open item in
[../../loom/PERSISTENCE_TASKS.md](../../tasks/PERSISTENCE_TASKS.md).

The rest of the record stands: at the time of the rework `loom/db/jooq` (211 tests),
`loom/db/api` (8), `loom/services/rest` (161), `loom/services/graphql` (8) and `loom/core`
(229, 3 skipped without a local LLM server) were green against a freshly provisioned pool. Note
that the migration count quoted there is historical — the schema now runs to **`V2.63`**.

---

## 8. Key Classes Reference

| Class / file | Package or path | Purpose |
|---|---|---|
| `AssetComponent` | `io.metaloom.loom.db.model.asset` | Base interface; carries the §4 provenance contract |
| `AssetComponentDao` / `AssetComponentDaoImpl` | `…db.model.asset` / `…db.jooq.dao.asset.comp` | CRUD + `loadXComp`-by-key + `upsertXComp` |
| `AssetNodeResultDao` | `io.metaloom.loom.db.model.asset` | The Layer-3 ledger: `loadByAsset`, `loadByNode`, `findStale`, upsert |
| `AssetJsonCompImpl` | `…db.jooq.dao.asset.comp` | Reference for manual `JSONB` → `JsonObject` conversion |
| `AssetComponentModelBuilder` | `io.metaloom.loom.rest.builder` | DB → REST; owns the read-side producer precedence (§3) |
| `NodeResultEndpointService` | `io.metaloom.loom.rest.service.impl` | `/api/v1/assets/:uuid/node-results` |
| `AbstractMediaNode` | `io.metaloom.cortex.common.node` | Writes the ledger row for every node |
| `AbstractJooqTest` / `AssetJsonCompDaoTest` | `io.metaloom.loom.db.jooq[.dao]` | Test base and reference component DAO test |
| `AssetBulkUpdateEntry` | `io.metaloom.loom.rest.model.asset` | Hash-shaped bulk sync payload, still ungeneralised (§7) |

---

## 9. Where do I find …?

| Concept | Path |
|---|---|
| Migrations | `loom/db/flyway/src/main/resources/db/migration/` |
| Generated jOOQ sources | `loom/db/jooq/src/jooq/java/io/metaloom/loom/db/jooq/` |
| Codegen config (`forcedTypes`, includes/excludes) | `loom/db/jooq/pom.xml` |
| Regenerate jOOQ | `loom/db/jooq/generate.sh` |
| Test pool provisioning | `./setup-pool.sh` → `io.metaloom.loom.test.PoolSetupRunner` |
| DAO interfaces / implementations | `loom/db/api/…/db/model/asset/` · `loom/db/jooq/…/dao/asset/comp/` |
| REST models / services | `loom-shared/rest-model/…/model/asset/` · `loom/services/rest/…/service/impl/` |
| ER diagram | `loom/design/DB/dbdiagram.yaml` (stale — Task 14) |
| Entity inventory | [../../loom/DOMAIN.md](../../loom/DOMAIN.md) |
| Open persistence work | [../../loom/PERSISTENCE_TASKS.md](../../tasks/PERSISTENCE_TASKS.md) |
| Node behaviour | [../pipeline-nodes/NODES.md](../nodes/NODES.md) |
| Schema audit | [../DB_SCHEMA_FEEDBACK.md](../DB_SCHEMA_FEEDBACK.md) |

---
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_