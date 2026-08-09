# MetaLoom Database — schema contracts and open schema work

> **Two jobs.** §1–§4 are the **historical record** of the component / node-result rework
> (tasks 1–13, migrations `V2.38`–`V2.50`, landed 2026-07-23) and the home of the three
> contracts other specs cite: the three-layer result model (§2), the rules the schema
> encodes (§3) and the shared component contract (§4). **Keep those section numbers stable.**
> §5 onwards are the **open schema work items** owned by this file.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> Schema now runs to **`V2.84`**. DAO/model/test gaps are *not* tracked here — they live in
> [PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md); the entity inventory lives in
> [../loom/DOMAIN.md](../loom/DOMAIN.md); the audit these tasks descend from is
> [../features/db/DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md), whose section
> numbers are cited below.
>
> **Context:** [../features/nodes/NODES.md](../features/nodes/NODES.md) (what nodes produce) ·
> [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) (run/task ledger) ·
> [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) (DAO layer, jOOQ codegen, migration workflow)

---

## 1. Outcome of the component / node-result rework (2026-07-23)

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
| 6 — Rework `detection` / `embedding` | `V2.43` | Provenance + idempotency keys on both; `embedding.detection_uuid` FK; duplicated geometry dropped (one normalized convention); `dimensions` added; camelCase `fromTime`/`toTime` → `time_from`/`time_to`; storage decision deferred in a column COMMENT (**since decided — see §5.1**) |
| 7 — `attachment` as the derived-binary sink | `V2.44` | `CONTACT_SHEET`/`POSTER_FRAME`/`WAVEFORM`/`PROXY`/`EXTRACTED_AUDIO` enum values; provenance + `variant`; partial unique index `(asset_uuid, type, node_kind, variant)`; `asset_uuid` gained `ON DELETE CASCADE` |
| 8 — Add `asset_node_result` ledger | `V2.45` | One row per `(asset_uuid, node_kind, node_id)` with `state`/`origin`/`producer_version`/timings/`result_ref`; invalidation index on `(node_kind, producer_version)` |
| 9 — Asset identity + consistency flag | `V2.46` | `asset.uuid` is now the PRIMARY KEY, `sha512sum` a `NOT NULL UNIQUE` natural key; `is_complete` added; legacy `s3_bucket_name`/`s3_object_path` dropped |
| 10 — Nullable audit columns | `V2.47` | `creator_uuid`/`editor_uuid` relaxed on machine-written tables — a Cortex worker is not a user (precedent: `V2.33` `cortex_instance`). Missed `cluster`, which is why a node could not write one until `V2.79` |
| 11 — Permission model for the new tables | — | **No enum change.** Components, `asset_fingerprint_comp`, `asset_segment_comp` and `asset_node_result` are sub-resources of an asset (`READ_ASSET` / `UPDATE_ASSET`); `detection` and `embedding` keep their dedicated permissions. Rule written into [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md) §2.5 |
| 12 — Java model + DAO realignment | — | `AssetComponent` gained `nodeKind`/`nodeId`/`producerVersion`/`runUuid`/`taskUuid`/`confidence`; `loadXComp`-by-key and `upsertXComp` (`onConflict().doUpdate()`, never `store()`); new `AssetNodeResultDao` registered on `DaoCollection` |
| 13 — Regenerate `dbdiagram.yaml` | — | Regenerated through `V2.50`; re-synced to `V2.84` by Task 14 |

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

*(Cited by [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) and the node
plans — keep this section and its numbering stable.)*

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
│ + detection / embedding / cluster │        │                                  │
│ USE WHEN a feature must filter,   │        │ USE WHEN the result is opaque    │
│ search, sort, or render the value │        │ to Loom, or the node is new      │
└───────────────────────────────────┘        └──────────────────────────────────┘
```

**Promotion policy (Layer 2 → Layer 1).** A new node kind starts in `asset_json_comp`. It
graduates to a typed table when — and only when — one of these becomes true:

1. a query must filter or sort on a field inside the JSON,
2. the UI renders it as a first-class object with its own lifecycle,
3. it must participate in a foreign-key relationship.

Search is the fourth, indirect trigger: `search_extract_json_text` (`V2.58`, extended by
`V2.65`) is a **whitelist** of `schema_type`s, so a JSON component nobody added a branch for
is stored and silently unfindable.

**Layer 3 vs. `pipeline_node_task`.** `asset_node_result` is per *asset*: catalog state,
outlives every run, keyed by `(asset_uuid, node_kind, node_id)`. `pipeline_node_task` is
per *run item*: execution state, pruned with the run, keyed by
`(item_uuid, node_id, element_seq, generation)` since `V2.60`/`V2.68`. Both may exist for one
execution; `run_uuid`/`task_uuid` are the join. `AbstractMediaNode` writes the ledger
generically, replacing the old field-probing short-circuit (`md5 != null` and friends) which
never generalised to JSON-blob or legitimately-empty results.

---

## 3. Rules the schema encodes

- Multiplicity is **always** expressed by typed columns (`stream_index`, `page_number`,
  `lang`, `sector_index`, `seq`, `frame_number`+`detection_index`, `cluster_index`), never by
  appending rows with the same key.
- **Never gate a component write on the asset's mime type.** An MP3 legitimately owns an
  `asset_image_comp` (cover art); a PDF has embedded images; a video has a document track.
- Two producers of the same *kind* of fact coexist by `node_kind` (EXIF geo vs. an LLM
  location guess; Tika probing a video vs. `QualityNode` measuring it). The same producer
  re-running **replaces in place** — `producer_version` is deliberately *outside* the
  unique key, so a model upgrade upserts and
  `WHERE node_kind = ? AND producer_version <> ?` finds everything stale.
  **One exception, `embedding`:** `V2.75` put `model` *inside* the key, because upserting a
  new embedding model over the old model's vector is destructive and irreversible.
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
- **A human verdict outranks the node that proposed it.** See §3.1.

### 3.1 The review contract (machine proposal + human verdict)

Established by `V2.61` (`dedup_group`), generalised by `V2.79` (`cluster`) and `V2.81`
(`detection`), which renamed `cluster_status` to the shared enum **`review_status`**
(`PENDING` / `CONFIRMED` / `REJECTED`). The workflow specs
([../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md),
[../workflows/WORKFLOW_OBJECT_DETECT.md](../workflows/WORKFLOW_OBJECT_DETECT.md),
[WORKFLOW_TASKS.md](WORKFLOW_TASKS.md)) reuse it — **do not create a second, structurally
identical enum.** `dedup_status` is the one pre-existing duplicate and is left alone
deliberately.

A reviewable proposal table carries, on top of the §4 provenance columns:

```sql
status         "review_status" NOT NULL DEFAULT 'PENDING',
reviewed_at    timestamp,
reviewer_uuid  uuid REFERENCES "user" ("uuid"),
-- plus, where a human can correct rather than only judge: corrected_<field>
```

Rules:

1. **A node upsert must not clear a non-`PENDING` status.** The DAO excludes `status`,
   `reviewed_at`, `reviewer_uuid` and the corrected fields from the `DO UPDATE` set —
   `DetectionDaoImpl.upsertDetection` is the reference.
2. **A `producer_version` change resets the verdict to `PENDING`**: the human judged a
   different model's output, so the decision no longer applies.
3. **The verdict outlives its subject's neighbours.** `person_uuid` on `cluster` is
   `ON DELETE SET NULL`, not `CASCADE` — deleting a person must not erase the review record
   (same reasoning as `dedup_group.keep_asset_uuid`).
4. Index the review queue: per-asset (`asset_uuid, type, status`) *and* cross-asset
   (`status, type`) — the "what is waiting for me?" screen cannot use the first.

---

## 4. The shared component contract

*(Cited by [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md)
§4 — every new `asset_*_comp` table must follow it. Deviating is a review failure.)*

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

The same provenance block is now carried by tables that are not components but are
node-written: `tag_asset` (`V2.71`), `cluster` (`V2.79`). Adopt it there too rather than
inventing a second shape.

### 4.1 Per-table keys as built

| Table | Unique key |
|---|---|
| `asset_geo_comp` | `(asset_uuid, node_kind, method, time_from)` |
| `asset_doc_comp` | `(asset_uuid, node_kind, page_number)` — `0` = whole document. **No producer, see §5.5** |
| `asset_image_comp` | `(asset_uuid, node_kind, stream_index)` |
| `asset_video_comp` | `(asset_uuid, node_kind, stream_index)` |
| `asset_audio_comp` | `(asset_uuid, node_kind, stream_index)` |
| `asset_transcript_comp` | `(asset_uuid, node_kind, stream_index, lang)` |
| `asset_json_comp` | `(asset_uuid, node_kind, schema_type, variant)` |
| `asset_fingerprint_comp` | `(asset_uuid, node_kind, algorithm, sector_index)` |
| `asset_segment_comp` | `(asset_uuid, node_kind, segment_type, seq)` |
| `detection` | `(asset_uuid, node_kind, frame_number, detection_index)` |
| `embedding` | `(asset_uuid, node_kind, type, model, frame_number, subject_index)` — `model` added by `V2.75` |
| `cluster` | `(asset_uuid, node_kind, cluster_index)` (`V2.79`), plus `UNIQUE (type, name) WHERE name IS NOT NULL` |
| `tag_asset` | `UNIQUE NULLS NOT DISTINCT (tag_uuid, asset_uuid, time_from, time_to, areaStartX, areaStartY)` (`V2.71`, needs PostgreSQL 15+) |
| `attachment` | partial unique index `(asset_uuid, type, node_kind, variant)` |
| `asset_node_result` | `(asset_uuid, node_kind, node_id)` |

`asset_transcript_comp` carries **both** `audio_comp_uuid` and `stream_index`: the FK is
for navigation and cascade, the index is in the key because an audio-only asset may be
transcribed before any audio component row exists. Column-level detail is in the
migrations and in [../loom/DOMAIN.md](../loom/DOMAIN.md); the ER diagram is
`loom/design/DB/dbdiagram.yaml`, current through `V2.84`.

---

## 5. Open tasks

At a glance, in the order they should be taken:

| # | Task | Severity | Migration? |
|---|---|---|---|
| 15 | `user_permission` / `token_permission` primary keys discard grants | 🔴 HIGH | yes |
| 16 | Three soft references in the pipeline tables become real FKs | 🔴 HIGH | yes |
| 17 | Widen `asset_location.filekey_*` to `bigint` | 🟠 MEDIUM | yes |
| 18 | Dispatch index on `pipeline_node_task` | 🟠 MEDIUM | yes |
| 19 | Give `asset_doc_comp` a producer, or drop the table | 🟡 decision | maybe |
| 20 | Re-sync the resolved findings in `DB_SCHEMA_FEEDBACK.md` | 🟡 docs | no |
| ~~14~~ | ~~Re-sync `dbdiagram.yaml` to `V2.84`~~ | ✅ done 2026-08-09 | no |

Tasks 15–18 are independent migrations; each needs `loom/db/jooq/generate.sh` **and**
`./setup-pool.sh` after it. Do not batch them into one migration file — a failure in one
would roll back the others' verification.

---

### Task 15: Give `user_permission` and `token_permission` usable primary keys

**Argumentation Summary:** Both tables have carried `PRIMARY KEY (user_uuid)` /
`PRIMARY KEY (token_uuid)` since `V2.1`, alongside a `UNIQUE INDEX (…, resource, permission)`
that the narrower PK makes unreachable. The effect is that a user or a token can hold
**exactly one direct grant, ever** — a second `INSERT` is a primary-key violation, not an
added permission. The project's own test suite already works around it by granting through
group+role (`SkillEndpointTest` pattern), which is how the defect has stayed invisible.
`V2.64` fixed the third table, `role_permission`, by dropping the decorative `resource`
column, and explicitly scoped these two out because they change the semantics of direct
grants. This is the largest correctness payoff per line of SQL left in the schema
([DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §7.1, recommendation 1).

**Improvement Summary:** Re-key both tables on `(subject_uuid, permission)` and drop the
`resource` column, matching what `V2.64` decided for roles — permissions in Loom are global,
so a column that looks like a scope but scopes nothing invites grants that confer more
authority than they appear to.

```
1. V2.XX__fix_direct_permission_keys.sql:
   - For "user_permission" and "token_permission", in that order:
     * DROP the (…, resource, permission) unique index by its generated name (check
       pg_indexes; V2.1 created them without an explicit name, exactly like the
       role_permission one V2.64 had to name by hand).
     * ALTER TABLE … DROP CONSTRAINT "<table>_pkey";
     * ALTER TABLE … DROP COLUMN IF EXISTS "resource";
     * ALTER TABLE … ADD PRIMARY KEY ("user_uuid", "permission") / ("token_uuid", "permission").
   - No data migration is possible in the other direction, but none is needed: at most one
     row per subject exists today, so the wider key cannot collide. State that in the file
     comment rather than adding a NOT EXISTS guard.
   - COMMENT ON TABLE both, copying the wording V2.64 used for role_permission.
2. PermissionDaoImpl: the grant path must now be an upsert on the wider key
   (insertInto(...).onConflict(USER_UUID, PERMISSION).doNothing()), and any code that
   relied on "one row per user" — look for load()/loadByUser() returning a single row —
   must return a collection.
3. Delete the "one direct grant per user" workaround note wherever tests explain it.
4. Regenerate jOOQ (loom/db/jooq/generate.sh) and re-run ./setup-pool.sh.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §7.1 ·
migrations `V2.1__add_acl.sql`, `V2.64__fix_role_permission_key.sql` (its scope note names
this task) · [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md)
**Test Requirements:** Extend `PermissionDaoTest` with a test that grants **two different**
permissions directly to one user and reads both back, and the same for a token — that test
fails today. `AclCascadeTest` must still pass (the cascade is on the subject FK, untouched).
`mvn test -pl loom/db/jooq -Dtest='PermissionDaoTest,AclCascadeTest'`, then the endpoint
permission tests in `loom/core`.

---

### Task 16: Turn the three soft references in the pipeline tables into real foreign keys

**Argumentation Summary:** Three columns name a row in another table by copying a value
instead of referencing it, and all three predate `V2.46` making `asset.uuid` a primary key:

- `pipeline_run_item.sha512 varchar` (`V2.31`) — no FK, no index. This is *the* seam between
  "what the pipeline did" and "what is in the catalog"; "which runs touched this asset?" is a
  sequential scan today (§6.1, HIGH).
- `pipeline_run.pipeline_version int` (`V2.29`) — the bare version number, so nothing stops a
  run claiming version 7 of a pipeline that has three. `skill.active_version_uuid` is a proper
  FK; the pipeline side should match (§6.2).
- `pipeline_node_task.leased_by varchar` (`V2.31`) — holds a `cortex_instance.node_id`, which
  is `UNIQUE`. "Show me every task this dead worker was holding" cannot be joined reliably and
  a typo'd worker id is undetectable (§6.4).

**Improvement Summary:** Add `asset_uuid`, `pipeline_version_uuid` and a real FK on
`leased_by`, each nullable, each with the delete behaviour its lifecycle implies.

```
1. V2.XX__pipeline_reference_integrity.sql:
   a) ALTER TABLE "pipeline_run_item" ADD COLUMN "asset_uuid" uuid
        REFERENCES "asset" ("uuid") ON DELETE SET NULL;
      CREATE INDEX idx_pipeline_run_item_asset ON "pipeline_run_item" ("asset_uuid");
      Backfill: UPDATE … SET asset_uuid = a.uuid FROM "asset" a WHERE a.sha512sum = item.sha512.
      Nullable and SET NULL, not CASCADE: the item exists before hashing (§2.2/§3), and
      deleting an asset must not erase the record that a run processed it. Keep the sha512
      column — it is the pre-hash identity, not a duplicate.
   b) ALTER TABLE "pipeline_run" ADD COLUMN "pipeline_version_uuid" uuid
        REFERENCES "pipeline_version" ("uuid") ON DELETE SET NULL;
      Backfill by joining pipeline_version on (pipeline_uuid, version_number). Note runs with
      kind = 'ADHOC' (V2.83) have no pipeline and must stay NULL — do not add a NOT NULL.
      Keep the int column for one release; drop it in a follow-up once readers are migrated.
   c) ALTER TABLE "pipeline_node_task" ADD CONSTRAINT "pipeline_node_task_leased_by_fkey"
        FOREIGN KEY ("leased_by") REFERENCES "cortex_instance" ("node_id") ON DELETE SET NULL;
      Clear orphans first (UPDATE … SET leased_by = NULL WHERE leased_by NOT IN (…)) or the
      constraint will not validate. Verify LeaseReaper still releases a lease by setting the
      column to NULL rather than to a sentinel string.
2. Model + DAO: PipelineRunItem gains assetUuid, PipelineRun gains pipelineVersionUuid; add
   PipelineRunItemDao.loadByAsset(UUID) — that is the query this task exists to enable.
3. Regenerate jOOQ, re-run ./setup-pool.sh.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §6.1, §6.2, §6.4
(recommendation 4) · [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) ·
migrations `V2.29`, `V2.31`, `V2.46`, `V2.83` · [PIPELINE_TASKS.md](PIPELINE_TASKS.md)
**Test Requirements:** `PipelineRunItemDaoTest` — an item resolves to its asset, `loadByAsset`
returns it, and deleting the asset nulls the column while the item survives.
`PipelineNodeTaskDaoTest` — a lease against an unknown worker id is rejected, and deleting a
`cortex_instance` releases its leases. `LeaseReaperTest` must still pass unchanged.

---

### Task 17: Widen `asset_location.filekey_*` to `bigint`

**Argumentation Summary:** `filekey_inode`, `filekey_stdev`, `filekey_edate_nano` and
`filekey_edate` are all `int` (`V2.10`, never widened) while the Java model
(`AssetBinary.getFilekeyInode()` and friends) already uses `Long`. `ino_t` is 64-bit and
ext4/XFS/btrfs routinely exceed `int`; `filekey_edate` overflows in 2038; `filekey_edate_nano`
cannot hold a nanosecond field at all. The file key is what the differential filesystem
scanner uses to decide "this file has not changed" — so the failure mode is silent
mis-detection, not an error. The same four columns already produced one silent bug: the POJO
field was spelled `filekeyStDev`, the mapper looked for `filekeyStdev`, and the column never
round-tripped at all until `AssetBinaryDaoTest` was written.

**Improvement Summary:** One `ALTER TABLE … TYPE bigint` per column, plus a DAO assertion that
a value above `Integer.MAX_VALUE` survives the round trip.

```
1. V2.XX__widen_filekey_columns.sql — for each of filekey_inode, filekey_stdev,
   filekey_edate, filekey_edate_nano:
     ALTER TABLE "asset_location" ALTER COLUMN "<col>" TYPE bigint;
   int -> bigint is a widening cast Postgres performs without a table rewrite of the values'
   meaning, and no data can be lost. Add a COMMENT ON COLUMN naming the unit for each
   (inode number, device id, epoch seconds, nanosecond fraction).
2. Confirm AssetBinaryImpl's fields are `Long` (they are named filekeyInode / filekeyStdev /
   filekeyEdate / filekeyEdateNano — camel-cased from the column, see PERSISTENCE.md's mapper
   gotcha) and that FileKey in loom-shared/rest-model carries longs end to end.
3. Regenerate jOOQ (the generated field type changes from Integer to Long — downstream
   compile errors are the point) and re-run ./setup-pool.sh.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §2.4
(recommendation 5) · migration `V2.10__add_asset_location.sql` ·
[../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) §Conventions (the `filekeyStDev` mapper gotcha)
**Test Requirements:** Extend `AssetBinaryDaoTest`'s full-column round trip to store
`Integer.MAX_VALUE + 1L` in all four columns and read the exact value back — that assertion
fails against the current schema with an out-of-range error, which is the proof the task is
real. `mvn test -pl loom/db/jooq -Dtest=AssetBinaryDaoTest`.

---

### Task 18: Add the worker-dispatch index on `pipeline_node_task`

**Argumentation Summary:** Dispatch asks *"give me `PENDING` tasks whose `node_kind` this
worker accepts"*. The table's indexes are `(item_uuid)`, `(run_uuid, state)` and the partial
lease index `(lease_expires_at) WHERE state = 'RUNNING'` — none serves that query, so it
degrades to a scan filtered by `state` on the largest table in the schema. `V2.60` and `V2.68`
both multiplied the row count per run (`element_seq` fan-out, then a row per re-execution
`generation`), so the scan gets worse with every feature.

**Improvement Summary:** One partial index.

```
1. V2.XX__pipeline_node_task_dispatch_index.sql:
     CREATE INDEX "idx_pipeline_node_task_dispatch"
       ON "pipeline_node_task" ("node_kind") WHERE "state" = 'PENDING';
   Partial on purpose: PENDING is a small and shrinking fraction of the table, so the index
   stays small and does not have to be maintained for rows that reached a terminal state.
2. Read the actual dispatch query first (the dispatcher in loom/services/rest, and
   PipelineNodeTaskDaoImpl) and confirm the column order matches. If it also orders by
   priority or created, make the index (node_kind, created) rather than adding a second one.
3. Confirm with EXPLAIN against a pooled DB that the plan changes to an index scan.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §3.7
(recommendation 6) · migrations `V2.31`, `V2.60`, `V2.68` ·
[../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md)
**Test Requirements:** No behavioural test — an index changes no results. Record the `EXPLAIN`
output before and after in the migration comment. `PipelineNodeTaskDaoTest` must stay green.

---

### Task 19: Give `asset_doc_comp` a producer, or drop the table

**Argumentation Summary:** `asset_doc_comp` was reworked in `V2.38` with a `page_number`
grain and a generated `text_search tsvector`, and its own table comment says *"Tika writes the
whole document as page 0, OCR writes one row per page"*. Neither does. `TikaNode` and
`OCRNode` both call `createAssetJsonComp`
(`cortex/nodes/tika/core/…/TikaNode.java:109`, `cortex/nodes/ocr/core/…/OCRNode.java:104`),
so the schema's only per-page full-text surface is empty while the extracted text sits in an
opaque `jsonb`. This is the one remaining gap in §3.1 of the audit, and it is exactly the
promotion trigger §2 describes: the text must be searchable per page.

**Improvement Summary:** Decide, then act — either point both nodes at the typed table (with
the JSON write retired, not doubled), or drop the table and its `text_search` column and make
`asset_json_comp` the documented home for extracted text.

```
Recommended direction: implement the producer. Search is the reason the table exists, and
V2.65 shows the alternative — teaching search_extract_json_text about another schema_type —
does not give per-page granularity.

1. LoomClient / REST: confirm createAssetDocComp exists on the client (the endpoint side is
   AssetComponentEndpointService, which already handles doc comps). Add the client method if
   it is missing, and the Python client mirror + parity test (see spec/loom/PYTHON_CLIENT.md).
2. TikaNode: write ONE asset_doc_comp with page_number = 0 carrying doc_plain_text,
   doc_word_count, text_lang and page_count. Keep the json comp only for fields the typed
   table has no column for; do not write the same text twice.
3. OCRNode: one row per page, page_number 1..N. Because the set can shrink between runs,
   follow the segment-set rule in §3 — delete rows with page_number > N for that
   (asset, node_kind) after the upserts.
4. Search: verify the generated text_search column feeds search_document. If the trigger set
   (V2.58/V2.59) has no branch for asset_doc_comp, add one — otherwise this task moves the
   text into a table that is still unfindable.
5. Update ../features/nodes/NODES.md for both nodes and note the schema_type retirement.

If instead the decision is to drop it: remove the table in a migration, delete AssetDocComp,
its DAO methods, the REST/GraphQL surface and DocumentInfo, and record the reason here.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §3.1, §10 ·
§2 above (promotion policy) · migrations `V2.38`, `V2.58`, `V2.59`, `V2.65` ·
[../features/nodes/NODES.md](../features/nodes/NODES.md)
**Test Requirements:** A node test per node asserting the typed rows (`TikaNodeTest`,
`OCRNodeTest` against `LoomClientMock`), a DAO test for the multi-page upsert and the
shrink-set delete, and a `SearchDocumentSourceTest` case proving the extracted text is
findable through `search_document`.

---

### Task 14: Re-sync `loom/design/DB/dbdiagram.yaml` to the current schema — ✅ DONE (2026-08-09)

**Outcome.** The diagram now covers all **80 tables** and all **138 `loom_permission` values** the
migrations produce through `V2.84`, verified by diffing it against
`CREATE TABLE` / `ALTER TYPE` across the whole migration chain — both diffs are empty, every
`ref:` target resolves, and every table belongs to a tablegroup. New tablegroups: `Search`,
`DedupReview`; `Agent` grew the chat-session and memory tables, `Pipeline` the two node-descriptor
tables, `Task` `task_assignee` + `notification`. Reworked in place: `cluster` (V2.79 — the whole
review model), `detection` (V2.81), `embedding` (V2.75 index contract, `model` in the key),
`tag_asset` (V2.71 placements), `pipeline_run` (V2.83 `kind`), `pipeline_node_task`
(`element_seq`/`generation`/`previews` and the four-column key), `role_permission` (V2.64), plus
every asset delete cascade from V2.72–V2.74 and V2.80. Three known defects are now annotated
where a reader meets them rather than only in the audit: the `user_permission` primary key, the
`filekey_*` widths, and the missing dispatch index.

**Deviation:** step 2 was a no-op — the V2.50 diagram never contained `webhook` or `loom_events`,
so there was nothing to remove. The task text below is kept as written.

**Argumentation Summary:** Task 13 regenerated the diagram through `V2.50` and its header
still says *"up to and including V2.50"*. **Thirty-four migrations have landed since.** The
diagram documents tables that no longer exist (`webhook` and the `loom_events` enum, dropped
by `V2.55`) and is silent about a third of the schema — including every entity the agent,
search, dedup, notification and review features added. It is the artefact people read before
they read SQL, and it is now wrong in both directions.

**Improvement Summary:** Regenerate from the migrations through `V2.84` and update the header,
keeping the existing conventions.

```
1. Update loom/design/DB/dbdiagram.yaml from the migrations through V2.84. Preserve the
   conventions already in the file: headercolor per group, tablegroup blocks, notes on
   non-obvious columns.
2. Remove `webhook` and the `loom_events` enum (dropped by V2.55).
3. Add tablegroups for everything added since V2.50:
   - agent:        chat_session (+skill, +context_ref), memory_entry, memory_deny_rule
   - search:       search_document, search_document_deleted
   - review:       dedup_group, dedup_group_member; the review_status enum and the
                   status/reviewed_at/reviewer_uuid block on cluster (V2.79) and
                   detection (V2.81)
   - pipeline:     node_descriptor (V2.66), pipeline_node_task.previews (V2.67),
                   .generation (V2.68), pipeline_run.kind + nullable pipeline_uuid (V2.83)
   - workflow:     task_assignee (V2.69), notification (V2.70)
4. Update the changed shapes on existing tables: library.pool_uuid and
   attachment_binary.pool_uuid (V2.63, both ON DELETE RESTRICT); tag_asset's surrogate uuid
   PK and provenance columns (V2.71); the embedding index contract columns
   dirty/synced_at/index_version/normalized and `model` in the unique key (V2.75); the
   asset delete cascades (V2.72–V2.74, V2.80).
5. Update the Project note's "up to and including V2.xx" line (line ~6) and its list of
   permission enum values (V2.76, V2.82, V2.84 added four).
```

**References:** `loom/design/DB/dbdiagram.yaml` · [../loom/DOMAIN.md](../loom/DOMAIN.md) ·
migrations `V2.51`–`V2.84`
**Test Requirements:** None (documentation). Verify by pasting into dbdiagram.io and
confirming it renders without parse errors.

---

### Task 20: Re-sync the resolved findings in `DB_SCHEMA_FEEDBACK.md`

**Argumentation Summary:** The audit file is cited by section number from six other specs
**and from SQL migration comments**, and four of its open findings have since been resolved
without being marked. Reading it today produces work that is already done:

- **§4.2 (vector storage, "open decision, widely cited")** — decided *and shipped*. `V2.75`
  gave `embedding` the exporter contract (`dirty`, `synced_at`, `index_version`,
  `normalized`, the `array_length(vector,1) = dimensions` CHECK) and put `model` in the unique
  key; ANN lives outside Postgres behind the `VectorIndex` SPI
  (`loom-shared/api/.../api/search/VectorIndex.java`, `LuceneVectorIndex`,
  `EmbeddingIndexSyncService`/`EmbeddingIndexDrainer`). The claim "no producer — no Cortex node
  writes embeddings" is also false: `FacedetectNode` writes them through
  `/assets/:uuid/embeddings/bulk`.
- **§4.3 (`cluster.name` globally unique)** — resolved by `V2.79`
  (`cluster_type_name_key` on `(type, name) WHERE name IS NOT NULL`), which also gave
  `cluster` the provenance block and the review model, so "two competing models of a person"
  now has a `person_uuid` link.
- **§7.1** — the `role_permission` third of it is resolved by `V2.64`, differently from the
  recommendation (the `resource` column was dropped rather than the key widened). The
  `user_permission` / `token_permission` two-thirds is Task 15 and stays open.
- **The header and the Gotchas section** both say the migrations run to `V2.63`; they run to
  `V2.84`.

**Improvement Summary:** Mark the four in place — never renumber — and refresh the two
version claims and the prioritised list in §9.

```
1. §4.2: retitle to "✅ RESOLVED (V2.75 + VectorIndex SPI)", keep the original text, append
   what was decided and where the code lives. Update the two inbound citations that call it
   an open decision: spec/features/search/SEMANTIC_SEARCH.md §1.3 and
   spec/features/search/SEARCH.md.
2. §4.3: mark "✅ RESOLVED (V2.79)".
3. §7.1: mark the role_permission third resolved by V2.64, keep the other two open and point
   at Task 15 in this file.
4. §3.1: strike the "no Cortex node writes embeddings" sentence; keep the asset_doc_comp gap
   and point it at Task 19.
5. Header line 8 and the "migrations are the source of truth" gotcha: V2.63 -> V2.84.
6. §9 Remaining prioritised recommendations: drop item 7 (vector storage), renumber nothing
   else, and note that items 1, 4, 5 and 6 are now Tasks 15–18 here.
7. Same sweep for the Progress Assessment checkboxes at the end of that file.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) (its own
"section numbers are an API" rule) · migrations `V2.64`, `V2.75`, `V2.79` ·
[../guidelines/SPEC_RULES.md](../guidelines/SPEC_RULES.md)
**Test Requirements:** None (documentation). `MetricsCatalogScrapeTest`-style parsing does not
apply to this file, but re-run `grep -rn "§4.2\|§4.3\|§7.1" spec/` and fix every citation that
now contradicts the marked state.

---

## 6. Open, but owned elsewhere — do not duplicate

| Item | Owner |
|---|---|
| DAO / model / DAO-test gaps (`VectorConfigDao`, `asset_remix` operations, `SpaceDaoTest`, missing cascade suites) | [PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) · [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) §Progress Assessment |
| Execution-ledger retention / partitioning — decided, not built (7 days of per-item detail, 30 for failures, the `pipeline_run` row forever) | [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) · [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §10.1a |
| `timestamptz` sweep (§8.1) — no migration uses it; converting is a whole-schema change | [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §8.1 |
| Content-mutation model (§2.2) — a changed file is a different asset and nothing migrates the old row's tags/detections/components; no `superseded_by_uuid` | [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §2.2 |
| `varchar` state columns without CHECKs (§6.5) — mitigated in Java by the three `forcedTypes` converters, which reject an unknown value naming the column; the DB side is still unconstrained | [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) §jOOQ code generation |
| `cortex_instance_node_kind` PK permits WHITELIST and BLACKLIST for the same kind (§6.6) | [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §6.6 |
| `tag_asset` / `annotation` geometry not on the normalized convention (§5.2); `annotation_asset` duplicates a direct FK (§5.3) | [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §5.2, §5.3 |
| `AssetBulkUpdateEntry` carries only `HashInfo`, so the generic bulk-sync path cannot express component payloads — nodes use per-resource endpoints instead | [../loom/RESTAPI.md](../loom/RESTAPI.md) |
| `search_document_deleted` is never drained by the Postgres provider (bounded by distinct deleted entities, not unbounded — `V2.59` deletes the tombstone on re-insert) | [../features/search/SEARCH.md](../features/search/SEARCH.md) |
| `notification` has no automatic retention — "delete every entry of the caller" is the only pruning mechanism (`NotificationEndpointService:117`) | [../loom/RESTAPI.md](../loom/RESTAPI.md) · `V2.70` |

---

## 7. Test setup

```bash
./setup-pool.sh                     # once, and again after EVERY Flyway change
loom/db/jooq/generate.sh            # testcontainer -> flyway migrate -> jOOQ codegen
mvn test -pl loom/db/jooq           # DAO round-trip + constraint tests
mvn test -pl loom/core              # endpoint tests (needs the pool)
```

`AbstractJooqTest` provides `asset()`, `dummyUser()` and `assetComponentDao()`;
`AssetJsonCompDaoTest` is the reference for a component DAO test. Full workflow:
[../loom/PERSISTENCE.md](../loom/PERSISTENCE.md).

---

## 8. Conventions and Gotchas

- **`./setup-pool.sh` after every Flyway change** — otherwise `loom/core` tests fail with
  `Pool not found {loom-dev}`. Install `loom/db/flyway` first, or a brand-new migration file
  is silently skipped and the pool keeps the old schema.
- 🔴 **"Found more than one migration with version X" is a stale shaded jar**, not a duplicate
  file: `loom-container-server` / `loom-container-demo` bundle `db/migration/*.sql`, and an
  `install` without `clean` re-shades the previous fat jar. `unzip -l` the jars; `find`ing the
  version in the source tree proves nothing.
- **`store()` is INSERT-only** and throws on the second write. Every idempotent write path
  must use `insertInto(...).onConflict(...).doUpdate()` — `AbstractJooqDao.upsert()` does
  this and deliberately excludes `uuid`, `created`, `creator_uuid` and the key columns from
  the UPDATE set so first-write provenance survives. Review columns are excluded too (§3.1).
- **`ALTER TYPE … ADD VALUE` cannot be *used* in the transaction that added it**, and
  Flyway wraps each migration in one. Put the value in its own migration with nothing else in
  the file (`V2.57`, `V2.62`, `V2.76`, `V2.82`, `V2.84` are the pattern) and seed grants in a
  later one, or use `-- flyway:executeInTransaction=false`.
- **Prefer `varchar` + `CHECK` over a Postgres enum for a value list that will churn.**
  `V2.55` had to rename `loom_permission`, rebuild it from `pg_enum` and re-type three columns
  inside a `DO` block just to remove four values. `node_descriptor.status` (`V2.66`),
  `notification.type` (`V2.70`) and `pipeline_run.kind` (`V2.83`) all chose CHECK.
  `ALTER TYPE … RENAME`, unlike `ADD VALUE`, *is* transactional — that is how `V2.81` folded
  `cluster_status` into the shared `review_status`.
- **`user_permission` has `PRIMARY KEY (user_uuid)`** — one direct grant per user, ever.
  Grant test permissions via group+role (`SkillEndpointTest` pattern) until Task 15 lands.
- **`tag_asset` needs PostgreSQL 15+** (`NULLS NOT DISTINCT`, `V2.71`). Without it an
  asset-level tag re-attaches on every write, since NULL region columns never conflict under
  the default semantics.
- **Index the referencing side of every FK you add.** Postgres indexes only the referenced
  side, so without it each parent delete seq-scans the child hunting cascade victims.
- **Clean-rebuild `loom/core`** after endpoint or DAO constructor changes, or Dagger fails at
  runtime with `NoSuchMethodError` — including during `./setup-pool.sh`.
- **Generated columns.** A `tsvector GENERATED ALWAYS AS … STORED` column must be excluded
  from codegen (`includeExcludeColumns=true`) or jOOQ tries to write it on insert; reach it
  with `DSL.field()`.
- **`jsonb` columns** only arrive as `io.vertx.core.json.JsonObject` if they match the
  `forcedTypes` `includeExpression` in `loom/db/jooq/pom.xml`; otherwise they come through
  as `org.jooq.JSONB` and need manual conversion, as in `AssetJsonCompImpl`.
- **Name POJO fields by camel-casing the column.** The default record mapper drops a field it
  cannot name, without a word — `filekeyStDev` vs. `filekey_stdev` meant that column never
  round-tripped for as long as it existed. Prove each column round trips in the DAO test.
- **Milliseconds, everywhere.** New time columns are `bigint` milliseconds.
- **`timestamp`, not `timestamptz`** — the whole schema uses `timestamp without time zone`;
  new tables follow suit (converting is out of scope, §6).
- **jOOQ codegen output is committed** under `loom/db/jooq/src/jooq/java` — regenerate and
  commit it with the migration, or downstream modules fail to compile.

---

## 9. Progress Assessment

### Delivered

- [x] **Tasks 1–13** — the component / node-result rework, `V2.38`–`V2.50` (§1)
- [x] REST endpoints for the new tables — `FingerprintCompEndpointService`,
      `SegmentCompEndpointService`, `JsonCompEndpointService`, `NodeResultEndpointService`
      (`/assets/:uuid/node-results`)
- [x] **Node write-back** — ~15 Cortex nodes persist through `client()`; `AbstractMediaNode`
      records the `asset_node_result` ledger row generically. One typed table still has no
      producer (`asset_doc_comp`, Task 19)
- [x] **Asset delete cascades** — `tag_asset` (`V2.72`), `collection_asset` / `asset_task` /
      `asset_user_meta` (`V2.73`), `comment` / `reaction` / `library_asset` (`V2.74`),
      `collection_asset.collection_uuid` (`V2.80`). The only non-cascading asset FKs left are
      two intentional `SET NULL`s (`dedup_group.keep_asset_uuid`, `person.primary_image_uuid`).
      Asserted from both sides by `AssetCascadeTest` over a shared two-asset fixture
- [x] **`tag_asset` placements** (`V2.71`) — surrogate PK, `NULLS NOT DISTINCT` region key and
      provenance, so one tag sits on an asset once per region and every placement names its writer
- [x] **Vector storage decided and built** (`V2.75` + the `VectorIndex` SPI) — Postgres is the
      system of record with a `dirty`/`synced_at`/`index_version` exporter contract, ANN lives
      outside it (`LuceneVectorIndex`), and `model` is in the unique key so a model upgrade
      adds rows instead of overwriting. `FacedetectNode` is the producer
- [x] **The review contract** (`V2.61`, `V2.79`, `V2.81`) — shared `review_status` enum, verdicts
      that survive a node re-run, review-queue indexes. Documented as §3.1
- [x] **`role_permission` key** (`V2.64`) — the unreachable unique index and the decorative
      `resource` column are gone
- [x] **`cluster.name`** (`V2.79`) — `UNIQUE (type, name)`, plus the provenance block, the
      `person_uuid` link and nullable audit columns that let a node write a cluster at all

### Open — this file

- [ ] **Task 15** — `user_permission` / `token_permission` primary keys (🔴 HIGH)
- [ ] **Task 16** — `pipeline_run_item.asset_uuid`, `pipeline_run.pipeline_version_uuid`,
      `pipeline_node_task.leased_by` FKs (🔴 HIGH)
- [ ] **Task 17** — widen `asset_location.filekey_*` to `bigint`
- [ ] **Task 18** — dispatch index on `pipeline_node_task`
- [ ] **Task 19** — `asset_doc_comp` producer, or drop the table
- [ ] **Task 20** — re-sync the resolved findings in `DB_SCHEMA_FEEDBACK.md`
- [x] **Task 14** — `dbdiagram.yaml` re-synced to `V2.84` (2026-08-09): 80 tables, 138 permission
      values, both diffs against the migrations empty

### Open — tracked elsewhere

See §6. Retention, `timestamptz`, content mutation, the remaining audit findings and every
DAO/test gap have owners outside this file.

---

## 10. Key Classes Reference

| Class / file | Package or path | Purpose |
|---|---|---|
| `AssetComponent` | `io.metaloom.loom.db.model.asset` | Base interface; carries the §4 provenance contract |
| `AssetComponentDao` / `AssetComponentDaoImpl` | `…db.model.asset` / `…db.jooq.dao.asset.comp` | CRUD + `loadXComp`-by-key + `upsertXComp` |
| `AssetNodeResultDao` | `io.metaloom.loom.db.model.asset` | The Layer-3 ledger: `loadByAsset`, `loadByNode`, `findStale`, upsert |
| `AbstractJooqDao.upsert(...)` | `io.metaloom.loom.db.jooq` | The idempotent write path every node-written table uses |
| `AssetJsonCompImpl` | `…db.jooq.dao.asset.comp` | Reference for manual `JSONB` → `JsonObject` conversion |
| `AssetComponentModelBuilder` | `io.metaloom.loom.rest.builder` | DB → REST; owns the read-side producer precedence (§3) |
| `DetectionDaoImpl.upsertDetection` | `…db.jooq.dao.detection` | Reference for the §3.1 review contract — the verdict survives a re-run |
| `NodeResultEndpointService` | `io.metaloom.loom.rest.service.impl` | `/api/v1/assets/:uuid/node-results` |
| `VectorIndex` / `LuceneVectorIndex` | `io.metaloom.loom.api.search` / `io.metaloom.loom.vector.lucene` | The external ANN SPI the `V2.75` contract feeds |
| `EmbeddingIndexSyncService` / `EmbeddingIndexDrainer` | `io.metaloom.loom.rest.vector` | Drains `embedding.dirty` into the index |
| `AbstractMediaNode` | `io.metaloom.cortex.common.node` | Writes the ledger row for every node |
| `AbstractJooqTest` / `AssetJsonCompDaoTest` | `io.metaloom.loom.db.jooq[.dao]` | Test base and reference component DAO test |
| `AssetCascadeTest` | `io.metaloom.loom.db.jooq.dao` | Both halves of the asset delete cascade over a two-asset fixture |

---

## 11. Where do I find …?

| Concept | Path |
|---|---|
| Migrations | `loom/db/flyway/src/main/resources/db/migration/` |
| Generated jOOQ sources | `loom/db/jooq/src/jooq/java/io/metaloom/loom/db/jooq/` |
| Codegen config (`forcedTypes`, includes/excludes) | `loom/db/jooq/pom.xml` |
| Regenerate jOOQ | `loom/db/jooq/generate.sh` |
| Test pool provisioning | `./setup-pool.sh` → `io.metaloom.loom.test.PoolSetupRunner` |
| DAO interfaces / implementations | `loom/db/api/…/db/model/asset/` · `loom/db/jooq/…/dao/asset/comp/` |
| REST models / services | `loom-shared/rest-model/…/model/asset/` · `loom/services/rest/…/service/impl/` |
| ER diagram | `loom/design/DB/dbdiagram.yaml` (current through `V2.84`) |
| Entity inventory | [../loom/DOMAIN.md](../loom/DOMAIN.md) |
| Persistence layer design | [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) |
| Open DAO / DAO-test work | [PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) |
| Node behaviour | [../features/nodes/NODES.md](../features/nodes/NODES.md) |
| Schema audit (section numbers are an API) | [../features/db/DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) |

---
_Git HEAD revision: `27894151`_
_Last updated: 2026-08-09 (closed Task 14 — `dbdiagram.yaml` re-synced from `V2.50` to `V2.84`:
80 tables and 138 permission values, both diffs against the migrations empty, with the three known
schema defects annotated where a reader meets them. Earlier the same day: task sweep against the
live schema at `V2.84`. Closed as delivered:
the vector-storage decision — `V2.75` plus the `VectorIndex` SPI, with `FacedetectNode` as the
producer — and `cluster.name` uniqueness, `role_permission`'s key and the `tag_asset` placement
model. Opened Tasks 15–20: the `user_permission`/`token_permission` primary keys `V2.64`
deferred, the three pipeline soft references, the `filekey_*` widening, the dispatch index, the
missing `asset_doc_comp` producer and the audit-file re-sync. Task 14 retargeted from `V2.63` to
`V2.84` with the entities added since. Added §3.1 documenting the shipped `review_status`
contract four workflow specs call "proposed", §6 listing what is open but owned elsewhere, and
fixed every relative link — this file moved to `spec/tasks/` and none of them resolved.)_
