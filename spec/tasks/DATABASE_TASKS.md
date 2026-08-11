# MetaLoom Database — schema contracts and open schema work

> **Two jobs.** §1–§4 are the **historical record** of the component / node-result rework
> (tasks 1–13, migrations `V2.38`–`V2.50`, landed 2026-07-23) and the home of the three
> contracts other specs cite: the three-layer result model (§2), the rules the schema
> encodes (§3) and the shared component contract (§4). **Keep those section numbers stable.**
> §5 onwards are the **open schema work items** owned by this file.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> Schema now runs to **`V2.99`** (`V2.97`/`V2.99` added the share model; `V2.88`–`V2.93` the
> review author and the person/user avatar attachments; `V2.91` dropped `person_image`).
> **Sort migration versions numerically, not lexically** — `V2.9` is not the newest file.
> DAO/model/test gaps are *not* tracked here — they live in
> [PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md); the entity inventory lives in
> [../loom/DOMAIN.md](../loom/DOMAIN.md); the audit these tasks descend from is
> [../features/db/DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md), whose section
> numbers are cited below.
>
> **Context:** [../features/nodes/NODES.md](../features/nodes/NODES.md) (what nodes produce) ·
> [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) (run/task ledger) ·
> [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) (DAO layer, jOOQ codegen, migration workflow)
>
> **Not owned here.** The `asset_node_result` write path — `origin` hard-coded to `COMPUTED`,
> no `runUuid`/`taskUuid` on `NodeResultCreateRequest`, `cortex_instance` never joined — is
> [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 18**. The columns already exist (`V2.45`); only
> the writer is missing, so it is not a schema task. The `dedup_group.keep_asset_uuid` vs.
> `dedup_group_member.role` inconsistency, including the false *"The DAO keeps them consistent"*
> comment in `V2.61__add_dedup_group.sql:12`, is [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 3**.

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
(`PENDING` / `CONFIRMED` / `REJECTED`). `cluster` got only the *verdict* in `V2.79`; its
**author** (`reviewed_at` / `reviewer_uuid`) arrived in `V2.88`, because until then
`ClusterDaoImpl` had nowhere to put the deciding user but `editor_uuid`, which the producing
node rewrites on every re-run. The workflow specs
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
transcribed before any audio component row exists.

`attachment` now has **five** nullable target columns — `asset_uuid` (`V2.44`),
`embedding_uuid` (`V2.43`), `detection_uuid` (`V2.79`), `person_uuid` (`V2.90`) and
`user_uuid` (`V2.93`). They are deliberately not alternatives and carry no `num_nonnulls`
CHECK (§1.1). Only the last two are lifetime-owned: both `CASCADE` from their owner and
nothing else can reach them, which is why `V2.91` could drop `person_image` and
`person.primary_image_uuid` outright.

Column-level detail is in the migrations and in [../loom/DOMAIN.md](../loom/DOMAIN.md); the ER
diagram is `loom/design/DB/dbdiagram.yaml`, **currently stale at `V2.84` — Task 21**.

---

## 5. Open tasks

At a glance, in the order they should be taken:

| # | Task | Severity | Migration? | loom-ui? |
|---|---|---|---|---|
| 15 | `user_permission` / `token_permission` primary keys discard grants | 🔴 HIGH | yes | no |
| 24 | `vector_config` has no primary key; four actor FKs missing across three tables | 🔴 HIGH | yes | no |
| 16 | Three soft references in the pipeline tables become real FKs | 🔴 HIGH | yes | no |
| 22 | Index the referencing side of the cascade and provenance FKs | 🟠 MEDIUM | yes | no |
| 17 | Widen `asset_location.filekey_*` to `bigint` | 🟠 MEDIUM | yes | no |
| 18 | Index the lease-holder query; retire the speculative dispatch index | 🟠 MEDIUM | yes | no |
| 19 | Give `asset_doc_comp` a producer, or drop the table | 🟡 decision | maybe | **yes** |
| 23 | Drop the duplicate `collection.parent_collection_uuid` foreign key | 🟢 LOW | yes | no |
| 21 | Re-sync `dbdiagram.yaml` from `V2.84` to `V2.99` | 🟡 docs | no | no |
| 20 | Re-sync the resolved findings in `DB_SCHEMA_FEEDBACK.md` | 🟡 docs | no | no |

**Blocking relationships.** Task 24 **blocks**
[PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) Task 6: `JooqVectorConfigRecord` is generated as a
`TableRecordImpl` precisely because the table has no primary key, so a DAO written against it
today cannot `update()` or `delete()` a row. Task 15 **blocks** every endpoint permission test
that currently has to grant through group+role. Task 22 should land **after** Task 16, so the
two columns that task adds are indexed in one pass rather than two.

Tasks 15–18 and 22–24 are independent migrations; each needs `loom/db/jooq/generate.sh` **and**
`./setup-pool.sh` after it, with `loom/db/flyway` installed first or the pool silently keeps the
old schema. Do not batch them into one migration file — a failure in one would roll back the
others' verification. Each writes `V2.100`; **whoever lands second renumbers**, sorting the
migration directory numerically.

The task bodies below stay in **numeric** order so the numbers other files cite remain stable;
the table above is the severity order. Tasks 1–14 are closed and their text has been removed —
§1 keeps the record of the 1–13 rework, and Task 14 (`dbdiagram.yaml` → `V2.84`, closed
2026-08-09) is superseded by Task 21.

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
1. loom/db/flyway/src/main/resources/db/migration/V2.100__fix_direct_permission_keys.sql —
   for "user_permission" and "token_permission", in that order:
     * DROP the (…, resource, permission) unique index. V2.1:140 and V2.1:150 created them
       with CREATE UNIQUE INDEX ON "<table>" (...) and no name, so Postgres generated one:
       resolve it from pg_indexes rather than guessing, exactly as V2.64 had to for
       role_permission.
     * ALTER TABLE "<table>" DROP CONSTRAINT "<table>_pkey";
     * ALTER TABLE "<table>" DROP COLUMN IF EXISTS "resource";
     * ALTER TABLE "<table>" ADD PRIMARY KEY ("user_uuid", "permission")
       / ("token_uuid", "permission").
   No backfill is possible and none is needed: the narrow PK means at most one row per
   subject exists today, so the wider key cannot collide. State that in the file comment
   rather than adding a NOT EXISTS guard.
   COMMENT ON TABLE both, reusing the wording V2.64 wrote for role_permission.
2. loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/perm/PermissionDaoImpl.java: the
   grant path becomes an upsert on the wider key
   (insertInto(...).onConflict(USER_UUID, PERMISSION).doNothing()). Check every method on
   loom/db/api/src/main/java/io/metaloom/loom/db/model/perm/PermissionDao.java that assumes
   one row per subject and widen its return type to a collection.
3. loom/services/auth/auth-common/.../PermissionCache.java: confirm it aggregates direct
   grants rather than reading a single row, and that the ResourcePermissionSet it builds is
   unaffected by the dropped resource column.
4. Delete the "one direct grant per user" workaround note wherever a test explains it, and
   the matching bullet in §8 of this file.
5. Regenerate jOOQ (loom/db/jooq/generate.sh) and re-run ./setup-pool.sh — install
   loom/db/flyway first. Clean-rebuild loom/core afterwards if the DAO signature changed.
6. No loom-ui change. Checked: the UI edits role permissions only (loom-ui/src/api/roles.ts,
   loom-ui/src/features/admin/AdminArea.tsx); loom-ui/src/api/users.ts never touches direct
   grants, so nothing in the SPA can observe this key.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §7.1 ·
migrations `V2.1__add_acl.sql:134-152`, `V2.64__fix_role_permission_key.sql` (its scope note
names this task) ·
[../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md)
**Test Requirements:** Extend
`loom/db/jooq/src/test/java/io/metaloom/loom/db/perm/PermissionDaoTest.java` with a case that
grants **two different** permissions directly to one user and reads both back, and the same
for a token — that case fails today with a primary-key violation, which is the proof the task
is real. `AclCascadeTest` must still pass (the cascade is on the subject FK, untouched).
`mvn test -pl loom/db/jooq -Dtest='PermissionDaoTest,AclCascadeTest'`, then
`mvn test -pl loom/core -Dtest='RolePermissionEnforcementTest'` and
`mvn test -pl loom/services/rest -Dtest=RolePermissionParityTest`. Re-run `./setup-pool.sh`
before any of them.

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
1. loom/db/flyway/src/main/resources/db/migration/V2.100__pipeline_reference_integrity.sql
   (renumber if another task lands first — sort the migration directory NUMERICALLY):
   a) ALTER TABLE "pipeline_run_item" ADD COLUMN "asset_uuid" uuid
        REFERENCES "asset" ("uuid") ON DELETE SET NULL;
      CREATE INDEX "idx_pipeline_run_item_asset" ON "pipeline_run_item" ("asset_uuid");
      Backfill: UPDATE … SET asset_uuid = a.uuid FROM "asset" a WHERE a.sha512sum = item.sha512.
      Nullable and SET NULL, not CASCADE: the item exists before hashing (§3), and deleting an
      asset must not erase the record that a run processed it. Keep the sha512 column — it is
      the pre-hash identity, not a duplicate.
   b) ALTER TABLE "pipeline_run" ADD COLUMN "pipeline_version_uuid" uuid
        REFERENCES "pipeline_version" ("uuid") ON DELETE SET NULL;
      CREATE INDEX "idx_pipeline_run_pipeline_version" ON "pipeline_run" ("pipeline_version_uuid");
      Backfill by joining pipeline_version on (pipeline_uuid, version_number). Runs with
      kind = 'ADHOC' (V2.83) have no pipeline and must stay NULL — do not add a NOT NULL.
      Keep the int column for one release; drop it in a follow-up once readers are migrated.
   c) ALTER TABLE "pipeline_node_task" ADD CONSTRAINT "pipeline_node_task_leased_by_fkey"
        FOREIGN KEY ("leased_by") REFERENCES "cortex_instance" ("node_id") ON DELETE SET NULL;
      CREATE INDEX "idx_pipeline_node_task_leased_by" ON "pipeline_node_task" ("leased_by");
      Clear orphans first (UPDATE … SET leased_by = NULL WHERE leased_by NOT IN (…)) or the
      constraint will not validate. Verify LeaseReaper still releases a lease by setting the
      column to NULL rather than to a sentinel string.
   All three indexes are required by the rule in §8 and by Task 22: Postgres indexes only the
   referenced side, so a parent delete would seq-scan the largest tables in the schema.
2. Model + DAO: loom/db/api/.../db/model/pipeline/PipelineRunItem.java gains assetUuid,
   PipelineRun gains pipelineVersionUuid; implement them in
   loom/db/jooq/.../dao/pipeline/PipelineRunItemImpl.java and PipelineRunImpl.java. Add
   PipelineRunItemDao.loadByAsset(UUID) with its PipelineRunItemDaoImpl body — that is the
   query this task exists to enable.
3. Regenerate jOOQ (loom/db/jooq/generate.sh), re-run ./setup-pool.sh with loom/db/flyway
   installed first, and clean-rebuild loom/core (a new DAO method changes no constructor, but
   a new DaoCollection entry would).
4. No loom-ui change. Checked: loom-ui/src/api/pipelines.ts and
   loom-ui/src/features/pipeline/ read run state and item paths, never the version number or
   the lease holder, so no DTO field is required. If a later task surfaces "which runs touched
   this asset" in the asset detail view, that is where the UI work belongs.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §6.1, §6.2, §6.4
(recommendation 4) · [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) ·
migrations `V2.29`, `V2.31`, `V2.46`, `V2.83` · [PIPELINE_TASKS.md](PIPELINE_TASKS.md) ·
Task 22 (the index rule)
**Test Requirements:**
`loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/PipelineRunItemDaoTest.java` — an item
resolves to its asset, `loadByAsset` returns it, and deleting the asset nulls the column while
the item survives (a delete-cascade case is required by
[../guidelines/CODING.md](../guidelines/CODING.md)). `PipelineNodeTaskDaoTest` — a lease against
an unknown worker id is rejected, and deleting a `cortex_instance` releases its leases.
`LeaseReaperTest` must still pass unchanged. Assert **relative to your own fixtures**: the
pooled test DB is pre-populated and shared, so never assert an absolute row count or emptiness.
`./setup-pool.sh`, then `mvn test -pl loom/db/jooq -Dtest='PipelineRunItemDaoTest,PipelineNodeTaskDaoTest'`.

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
1. loom/db/flyway/src/main/resources/db/migration/V2.100__widen_filekey_columns.sql —
   for each of filekey_inode, filekey_stdev, filekey_edate, filekey_edate_nano
   (V2.10__add_asset_location.sql:6-9, all four still "int"):
     ALTER TABLE "asset_location" ALTER COLUMN "<col>" TYPE bigint;
   int -> bigint is a widening cast; no data can be lost. Add a COMMENT ON COLUMN naming the
   unit for each (inode number, device id, epoch seconds, nanosecond fraction).
2. No Java model change is needed on the interface: AssetBinary.java:80-94 already declares
   getFilekeyInode / getFilekeyStDev / getFilekeyEdate / getFilekeyEdateNano as Long. What
   must be re-checked is the POJO FIELD spelling in
   loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/asset/binary/AssetBinaryImpl.java —
   the default record mapper matches the camel-cased COLUMN name (filekeyStdev), not the
   interface getter (getFilekeyStDev), and that exact mismatch already caused this column to
   silently never round-trip once. Confirm FileKey in loom-shared/rest-model carries longs
   end to end.
3. Regenerate jOOQ (loom/db/jooq/generate.sh). The generated field type changes from Integer
   to Long — the downstream compile errors are the point. Re-run ./setup-pool.sh with
   loom/db/flyway installed first.
4. No loom-ui change. The file key is scanner state; nothing in loom-ui/src reads it.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §2.4
(recommendation 5) · migration `V2.10__add_asset_location.sql` ·
[../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) §Conventions (the `filekeyStDev` mapper gotcha)
**Test Requirements:**
`loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/AssetBinaryDaoTest.java:287-303`
already round-trips these four columns, but only with small values (4711, 66, 1600000000,
123456) that fit an `int`. Raise them past `Integer.MAX_VALUE` (e.g. `4294967296L`) and read
the exact value back — that assertion fails against the current schema with an out-of-range
error, which is the proof the task is real. `./setup-pool.sh`, then
`mvn test -pl loom/db/jooq -Dtest=AssetBinaryDaoTest`.

---

### Task 18: Index the lease-holder query, and retire the speculative dispatch index

**Argumentation Summary:** This task previously proposed
`("node_kind") WHERE "state" = 'PENDING'` to serve worker dispatch. **That query does not
exist.** A sweep of every `PipelineNodeTaskDao` caller
(`PipelineRunRecovery`, `PipelineEndpointService`, `NodeRunService`, `LeaseReaper`,
`DaoRunStateStore`, all in `loom/services/rest/.../service/impl/`) and of
`PipelineNodeTaskDaoImpl` finds no `WHERE state = 'PENDING' AND node_kind …` anywhere —
`node_kind` is written by `createNodeTask` and read back, never filtered on. Building an index
for it would be maintenance cost against a query no code issues. What *is* unindexed and does
run is `PipelineNodeTaskDaoImpl.java:131`,
`STATE = 'RUNNING' AND LEASED_BY = ?` — "what is this worker holding" — on the largest table
in the schema, which `V2.60` (`element_seq` fan-out) and `V2.68` (a row per `generation`)
have each multiplied. The existing indexes are `(item_uuid)`, `(run_uuid, state)` and the
partial `(lease_expires_at) WHERE state = 'RUNNING'`; the last one serves `LeaseReaper`'s
expiry sweep at `:114` but not the by-holder lookup.

**Improvement Summary:** Index the lease-holder lookup that exists, and record in the
migration why the `node_kind` index is deferred, so the next audit does not re-propose it.

```
1. loom/db/flyway/src/main/resources/db/migration/V2.100__pipeline_node_task_lease_index.sql:
     CREATE INDEX "idx_pipeline_node_task_leased_by"
       ON "pipeline_node_task" ("leased_by") WHERE "state" = 'RUNNING';
   Partial on purpose: a lease only exists while RUNNING, so the index never has to be
   maintained for rows in a terminal state, which is almost all of them.
   ⚠️ COORDINATE WITH TASK 16c, which adds a plain FK index on the same column. Land one or
   the other, not both: if Task 16 goes first, the partial index here is redundant for the
   lookup but still wanted for cascade checks — in that case, skip this migration and only do
   step 2.
2. In the same file (or, if step 1 is skipped, as a COMMENT ON TABLE amendment), record the
   finding: no PENDING-by-node_kind dispatcher exists in this codebase, so the partial
   dispatch index is deliberately NOT created. State the condition that would change the
   answer — a dispatcher that claims work by node kind, which
   [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) describes as intended
   but which is not built. Add the index in the same change that adds the dispatcher, with
   its real column order (add `created` as a second column if it orders by age).
3. Confirm with EXPLAIN against a pooled DB that the by-holder plan changes to an index scan.
   Record the before/after in the migration comment.
4. No loom-ui change — an index changes no response.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §3.7
(recommendation 6 — its premise is corrected here) · migrations `V2.31`, `V2.60`, `V2.68` ·
[../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) · Task 16c, Task 22
**Test Requirements:** No behavioural test — an index changes no results.
`loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/PipelineNodeTaskDaoTest.java` and
`LeaseReaperTest` must stay green. `./setup-pool.sh`, then
`mvn test -pl loom/db/jooq -Dtest=PipelineNodeTaskDaoTest` and
`mvn test -pl loom/services/rest -Dtest=LeaseReaperTest`.

---

### Task 19: Give `asset_doc_comp` a producer, or drop the table

**Argumentation Summary:** `asset_doc_comp` was reworked in `V2.38` with a `page_number`
grain and a generated `text_search tsvector`, and its own table comment
(`V2.38__rework_asset_components.sql:118`) says *"Tika writes the whole document as page 0,
OCR writes one row per page"*. **Neither does** — the comment is a description of an intention,
not of the code. `TikaNode` and `OCRNode` both call `createAssetJsonComp`
(`cortex/nodes/tika/core/src/main/java/io/metaloom/cortex/node/tika/TikaNode.java:109`,
`cortex/nodes/ocr/core/src/main/java/io/metaloom/cortex/node/ocr/OCRNode.java:104`), and
`createAssetDocComp` **does not exist on `LoomClient` at all** — a grep across `loom-shared/`
and `clients/` finds no such method, so no Cortex node could write the table even if it tried.
The only writers are the manual REST paths
(`AssetComponentEndpointService.java:153-163`, `AssetEndpointService.java:265`, both with
`NODE_KIND_MANUAL`). So the schema's only per-page full-text surface is empty while the
extracted text sits in an opaque `jsonb`. Two smaller defects ride along: the read path
`AssetModelBuilder.toDocumentInfo` sets only `source`, `wordCount` and `plainText` — it never
reads `page_count` or `text_lang`, though both columns exist and `DocumentInfo` has fields for
them — and the UI type `loom-ui/src/api/assets.ts:84` declares `DocumentInfo` with only
`wordCount`/`pageCount`, with `documentComponents` (`assets.ts:157`) **never rendered by any
component**. This is the last gap in §3.1 of the audit, and exactly the promotion trigger §2
describes: the text must be searchable per page.

**Improvement Summary:** Decide, then act — either point both nodes at the typed table (with
the JSON write retired, not doubled) and finish the read path through to the UI, or drop the
table and its `text_search` column and make `asset_json_comp` the documented home for
extracted text.

```
Recommended direction: implement the producer. Search is the reason the table exists, and
V2.65 shows the alternative — teaching search_extract_json_text about another schema_type —
does not give per-page granularity.

1. LoomClient: ADD createAssetDocComp (it is missing — verified). The endpoint side already
   exists in loom/services/rest/.../service/impl/AssetComponentEndpointService.java:153-163.
   Mirror it in the Python client and update the parity test that guards the client method
   count (see ../loom/PYTHON_CLIENT.md).
2. TikaNode: write ONE asset_doc_comp with page_number = 0 carrying doc_plain_text,
   doc_word_count, text_lang and page_count. Keep the json comp only for fields the typed
   table has no column for; do not write the same text twice.
3. OCRNode: one row per page, page_number 1..N. Because the set can shrink between runs,
   follow the segment-set rule in §3 — delete rows with page_number > N for that
   (asset, node_kind) after the upserts.
4. Read path: loom/services/rest/.../builder/AssetModelBuilder.java toDocumentInfo currently
   drops two columns — add info.setPageCount(comp.getPageCount()) and
   info.setTextLang(comp.getTextLang()). Both fields already exist on the DocumentInfo REST
   model; they are simply never populated.
5. Search: verify the generated text_search column feeds search_document. If the trigger set
   (V2.58/V2.59) has no branch for asset_doc_comp, add one — otherwise this task moves the
   text into a table that is still unfindable.
6. loom-ui (REQUIRED — this task is the reason the UI has a dead type):
   a) loom-ui/src/api/assets.ts:84 — extend the DocumentInfo interface with
      source?: string, plainText?: string, textLang?: string, pageNumber?: number, so it
      matches the REST DocumentInfo model instead of a two-field subset.
   b) loom-ui/src/features/assetDetail/AssetDetail.tsx — render documentComponents. It is
      declared at assets.ts:157 and read by NOTHING today. Follow the existing panel pattern:
      TranscriptPanel.tsx is the closest analogue (per-segment text with a producer label),
      and loom-ui/src/features/assetDetail/helpers.ts is where the other component arrays are
      grouped for display. Group rows by node_kind (tika vs ocr) and order by page_number.
7. Update ../features/nodes/NODES.md for both nodes, note the schema_type retirement, and FIX
   the V2.38:118 table comment so it describes what the code does rather than what was
   planned.

If instead the decision is to drop it: remove the table in a migration, delete AssetDocComp,
AssetComponentDao's six doc-comp methods, the REST/GraphQL surface, DocumentInfo, and the
loom-ui DocumentInfo type plus the documentComponents field — and record the reason here.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §3.1, §10 ·
§2 above (promotion policy) · migrations `V2.38`, `V2.58`, `V2.59`, `V2.65` ·
[../features/nodes/NODES.md](../features/nodes/NODES.md) ·
[../guidelines/CODING.md](../guidelines/CODING.md) (customer-facing website docs are part of
done for a node behaviour change)
**Test Requirements:** A node test per node asserting the typed rows (`TikaNodeTest`,
`OCRNodeTest` against `LoomClientMock` — note `LoomClientMock` works fine, the "Java 25
Mockito restrictions" comment in that area is wrong, so do not fall back to a null client and
skip the write-back coverage). A DAO case in
`loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/AssetComponentKeyTest.java` (it
already exercises the `(asset_uuid, node_kind, page_number)` key at :109-116) for the
multi-page upsert and the shrink-set delete. A `SearchDocumentSourceTest` case proving the
extracted text is findable through `search_document`. A Playwright mocked e2e under
`loom-ui/e2e/` for the new panel — component tests in this repo are mocked Playwright specs,
not RTL. `mvn test -pl loom/db/jooq -Dtest=AssetComponentKeyTest` and
`./node_modules/.bin/playwright test` from `loom-ui/` (never `npx` — it hangs).

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
- **The header (line 7-8) still says the chain was re-verified to `V2.63`.** It runs to
  `V2.99` — thirty-six migrations later, including the whole share model.
- **§6.4 (`leased_by` soft reference)** and **§3.7 (dispatch index)** — §3.7's premise is
  wrong and is corrected by Task 18 here: there is no `PENDING`-by-`node_kind` query in the
  codebase. Do not mark it resolved; mark it **superseded** and point at Task 18.

**Improvement Summary:** Mark the resolved findings in place — never renumber — and refresh
the version claims and the prioritised list in §9.

```
1. §4.2 (line 219): retitle to "RESOLVED (V2.75 + VectorIndex SPI)", keep the original text,
   append what was decided and where the code lives. Update the two inbound citations that
   call it an open decision: spec/features/search/SEMANTIC_SEARCH.md §1.3 and
   spec/features/search/SEARCH.md.
2. §4.3 (line 236): mark "RESOLVED (V2.79)".
3. §7.1 (line 347): mark the role_permission third resolved by V2.64, keep the other two open
   and point at Task 15 in this file.
4. §3.1 (line 169): strike the "no Cortex node writes embeddings" sentence; keep the
   asset_doc_comp gap and point it at Task 19, noting the additional finding that
   LoomClient has no createAssetDocComp method at all.
5. §3.7: mark SUPERSEDED and point at Task 18 with its reason.
6. Header lines 7-8 and the "migrations are the source of truth" gotcha: V2.63 -> V2.99.
7. §9 Remaining prioritised recommendations (line 430): drop item 7 (vector storage),
   renumber nothing else, and note that items 1, 4, 5 and 6 are now Tasks 15-18 here.
8. The blockquote at lines 23-25 lists three structural findings detected at runtime by
   DB_INTEGRITY (DANGLING_TOKEN_EDITOR, DANGLING_ASSET_REMIX_EDITOR,
   DANGLING_VECTOR_CONFIG_ACTOR / DUPLICATE_VECTOR_CONFIG_UUID). Point them at Task 24 here,
   which is the migration that makes them unreachable.
9. Same sweep for the Progress Assessment checkboxes at the end of that file.
10. No loom-ui change — documentation only.
```

**References:** [DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) (its own
"section numbers are an API" rule) · migrations `V2.64`, `V2.75`, `V2.79` ·
[../guidelines/SPEC_RULES.md](../guidelines/SPEC_RULES.md) · Tasks 15, 18, 19, 24
**Test Requirements:** None (documentation). No test parses this file — unlike
`spec/features/metrics/METRICS.md`, whose §3/§5 tables `MetricsCatalogScrapeTest` reads at
runtime — but re-run `grep -rn "§3.7\|§4.2\|§4.3\|§7.1" spec/` and fix every citation that now
contradicts the marked state.

---

### Task 21: Re-sync `loom/design/DB/dbdiagram.yaml` from `V2.84` to `V2.99`

**Argumentation Summary:** Task 14 re-synced the diagram from `V2.50` to `V2.84` on
2026-08-09 and it has gone stale again: `dbdiagram.yaml:6` still says *"up to and including
V2.84"* while the migration chain runs to `V2.99`. It is wrong in **both** directions, which is
the failure mode that makes a diagram worse than no diagram. It documents a table that no
longer exists — `person_image` is still drawn at `dbdiagram.yaml:1021` with its
`(person_uuid, asset_uuid)` primary key, and `person.primary_image_uuid` alongside it, both
**dropped by `V2.91`** — and it is silent about the entire share subsystem (`share`,
`share_annotation`, `share_comment`, `share_reaction`), the four new `loom_permission` values
for it, and the person/user avatar model that replaced what it still shows.

**Improvement Summary:** Bring the diagram forward fifteen migrations, keeping the file's
existing conventions.

```
1. Update loom/design/DB/dbdiagram.yaml from the migrations V2.85-V2.99. Preserve the
   conventions already in the file: headercolor per group, tablegroup blocks, notes on
   non-obvious columns, and the practice Task 14 established of annotating a known defect
   where a reader meets it.
2. REMOVE what V2.91 dropped: the person_image table (currently at :1021) and
   person.primary_image_uuid. Both are gone from the schema.
3. ADD a "Share" tablegroup: share (V2.97) with its slug/target_type/expiry/capability
   columns and its three CHECK constraints, plus share_annotation, share_comment and
   share_reaction (V2.99). Note in the group that these are guest-authored rows with an
   author_name string and NO creator_uuid, which is why they are a separate group from the
   internal social tables.
4. UPDATE the changed shapes on existing tables:
   - attachment: person_uuid (V2.90) and user_uuid (V2.93), bringing its nullable target
     columns to five; the partial unique index on (user_uuid) WHERE type = 'USER_AVATAR'
     (V2.93) and the deliberate ABSENCE of one for person_uuid (V2.90 argues why).
   - person: avatar_attachment_uuid (V2.90), ON DELETE SET NULL, and the person <-> attachment
     FK cycle it forms.
   - cluster: reviewed_at / reviewer_uuid (V2.88) — the review AUTHOR, which V2.79 did not add.
   - notification: the type CHECK gained SHARE_FEEDBACK (V2.99).
   - attachment_type enum: PERSON_IMAGE (V2.89), USER_AVATAR (V2.92).
5. Update the Project note's "up to and including V2.xx" line (line 6) to V2.99 and its list
   of loom_permission values — V2.85, V2.87, V2.94 and V2.96 added eight between them
   (READ_SEARCH_INDEX, MANAGE_SEARCH_INDEX, READ_DB_INTEGRITY, READ_STORAGE, CREATE_SHARE,
   READ_SHARE, UPDATE_SHARE, DELETE_SHARE).
6. Re-verify the way Task 14 did: diff the diagram's tables against CREATE TABLE / DROP TABLE
   across the whole chain and its enum values against ALTER TYPE, and confirm both diffs are
   empty, every ref: target resolves, and every table belongs to a tablegroup.
7. No loom-ui change — this is a design artefact.
```

**References:** `loom/design/DB/dbdiagram.yaml` · [../loom/DOMAIN.md](../loom/DOMAIN.md) ·
migrations `V2.85`–`V2.99` · Task 14 (closed 2026-08-09, did the `V2.50`→`V2.84` pass)
**Test Requirements:** None (documentation). Verify by pasting into dbdiagram.io and
confirming it renders without parse errors, and by the two empty diffs from step 6.

---

### Task 22: Index the referencing side of the cascade and provenance foreign keys

**Argumentation Summary:** §8 of this file states the rule — *"Index the referencing side of
every FK you add. Postgres indexes only the referenced side, so without it each parent delete
seq-scans the child hunting cascade victims."* — and the schema does not follow it. Counting
against the generated `Keys.java` / `Indexes.java` (which reflect the migrated schema, not the
SQL text): **252 foreign keys, 158 with no index on the referencing column.** Most are
`creator_uuid` / `editor_uuid` and only cost on a user delete, but three groups are on paths
the product exercises routinely:

- **The asset delete cascade.** `V2.72`–`V2.74` and `V2.80` made every asset FK cascade — and
  added no indexes. `annotation.asset_uuid`, `comment.asset_uuid`, `library_asset.asset_uuid`,
  `annotation_asset.asset_uuid` and `asset_location.asset_uuid` are all unindexed, so
  `DELETE /assets/:uuid` seq-scans five tables. `AssetCascadeTest` proves the cascade is
  *correct*; nothing measures what it costs.
- **The provenance links.** `run_uuid` and `task_uuid` are unindexed on **all nine**
  `asset_*_comp` tables and on `detection`, `embedding`, `cluster` and `attachment`
  (`asset_node_result.run_uuid` is the one exception — `V2.45:57` indexed it). These are
  `ON DELETE SET NULL`, so pruning one `pipeline_run` scans thirteen tables. This is what the
  execution-ledger retention work in
  [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) will run into.
- **`attachment.binary_sha512sum`**, unindexed, while `asset_uuid`, `detection_uuid`,
  `person_uuid` and `user_uuid` on the same table all have one — `embedding_uuid` is unindexed
  too. The newest columns follow the rule and the oldest do not.

**Improvement Summary:** One migration adding the indexes on the paths that are actually
walked, leaving the pure-audit `creator_uuid`/`editor_uuid` columns alone with a written
reason.

```
1. Regenerate the finding before writing SQL — do not trust the list above. Parse
   loom/db/jooq/src/jooq/java/io/metaloom/loom/db/jooq/Keys.java (createForeignKey) and
   Indexes.java (createIndex) plus the unique keys, and report every FK whose LEADING column
   is not the leading column of some existing index. That is the same method that produced
   these numbers and it re-runs in seconds.
2. loom/db/flyway/src/main/resources/db/migration/V2.100__cascade_fk_indexes.sql — create,
   with explicit idx_<table>_<column> names:
   a) The asset cascade: annotation(asset_uuid), comment(asset_uuid),
      library_asset(asset_uuid), annotation_asset(asset_uuid), asset_location(asset_uuid).
   b) The provenance links: (run_uuid) and (task_uuid) on asset_geo_comp, asset_doc_comp,
      asset_image_comp, asset_video_comp, asset_audio_comp, asset_transcript_comp,
      asset_json_comp, asset_fingerprint_comp, asset_segment_comp, detection, embedding,
      cluster, attachment; plus asset_node_result(task_uuid) only — its run_uuid is already
      indexed by V2.45.
   c) attachment(binary_sha512sum) and attachment(embedding_uuid).
   d) The remaining non-actor links a re-run of step 1 reports: comment(parent_uuid),
      comment(annotation_uuid), comment(task_uuid), reaction(annotation_uuid),
      reaction(comment_uuid), reaction(task_uuid), asset_task(task_uuid),
      annotation_tag(tag_uuid), annotation_task(task_uuid), library_collection(collection_uuid),
      project_collection(collection_uuid), project_library(library_uuid),
      tag_collection(collection_uuid), tag_cluster(cluster_uuid), user_group(group_uuid),
      role_group(role_uuid), chat_session(chat_uuid), chat_session(pool_uuid),
      chat_session_skill(skill_uuid), memory_entry(chat_uuid), library(pool_uuid),
      attachment_binary(pool_uuid).
3. DELIBERATELY SKIP every creator_uuid / editor_uuid / reviewer_uuid / locked_by_uuid FK and
   write the reason into the migration comment: users are not deleted in bulk, these columns
   are read by uuid rather than scanned, and ~120 single-column indexes would cost more write
   amplification than they save. State the condition that would reverse the decision (a bulk
   user-purge feature).
4. Do NOT touch pipeline_run_item.asset_uuid, pipeline_run.pipeline_version_uuid or
   pipeline_node_task.leased_by here — Task 16 creates those columns WITH their indexes, and
   Task 18 owns the partial lease index. Land Task 16 first.
5. Regenerate jOOQ (loom/db/jooq/generate.sh) — Indexes.java changes — and re-run
   ./setup-pool.sh with loom/db/flyway installed first.
6. No loom-ui change — an index changes no response.
```

**References:** §8 of this file (the rule) · [../guidelines/CODING.md](../guidelines/CODING.md)
(delete-cascade tests are part of done) · migrations `V2.44`, `V2.45`, `V2.72`–`V2.74`,
`V2.80`, `V2.90`, `V2.93` · Task 16, Task 18 ·
[METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) (ledger retention, the consumer
of the provenance indexes)
**Test Requirements:** An index changes no results, so there is no new behavioural assertion.
`loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/AssetCascadeTest.java` and
`ShareCascadeTest` must stay green — they are the proof the cascades still behave. Record an
`EXPLAIN (ANALYZE)` of `DELETE FROM asset WHERE uuid = ?` before and after in the migration
comment. Assert relative to your own fixtures, never absolute counts: the pooled DB is shared
and pre-populated. `./setup-pool.sh`, then
`mvn test -pl loom/db/jooq -Dtest='AssetCascadeTest,ShareCascadeTest,AclCascadeTest'`.

---

### Task 23: Drop the duplicate `collection.parent_collection_uuid` foreign key

**Argumentation Summary:** `V2.7__add_collection.sql` declares the same foreign key twice —
lines 16 and 17 are byte-identical
(`ALTER TABLE "collection" ADD FOREIGN KEY ("parent_collection_uuid") REFERENCES "collection" ("uuid");`).
Postgres accepts it and creates two constraints, which is why the generated `Keys.java` carries
both `collection_parent_collection_uuid_fkey` and `collection_parent_collection_uuid_fkey1`.
It is the only duplicated FK in the schema — a sweep of all 252 generated foreign keys finds no
other table with two constraints over the same column set. The cost is small but real: every
insert and update of `collection.parent_collection_uuid` performs the same referential check
twice, and a reader of the generated jOOQ or of `\d collection` is left wondering which of the
two is meaningful and whether they differ.

**Improvement Summary:** Drop the redundant constraint, and index the column while it is open —
it is unindexed, and a self-referencing parent pointer is walked on every collection tree read.

```
1. loom/db/flyway/src/main/resources/db/migration/V2.100__collection_parent_fk_cleanup.sql:
     ALTER TABLE "collection" DROP CONSTRAINT IF EXISTS "collection_parent_collection_uuid_fkey1";
     CREATE INDEX "idx_collection_parent_collection_uuid"
       ON "collection" ("parent_collection_uuid");
   Verify the generated name against pg_constraint first rather than trusting jOOQ's rendering;
   V2.7 named neither constraint, so both names are Postgres-generated and an installation
   that ran the migrations in a different order could in principle differ.
   Write into the file comment that V2.7:16-17 is a duplicated line, so the next reader does
   not conclude the two constraints once meant different things.
2. Regenerate jOOQ (loom/db/jooq/generate.sh) — Keys.java loses the ...fkey1 entry, and any
   code that referenced it by name will fail to compile, which is the point. Re-run
   ./setup-pool.sh with loom/db/flyway installed first.
3. No loom-ui change — no DTO field changes.
```

**References:** migration `V2.7__add_collection.sql:16-17` ·
`loom/db/jooq/src/jooq/java/io/metaloom/loom/db/jooq/Keys.java` · §8 of this file (the FK index
rule) · Task 22 (the same rule at scale)
**Test Requirements:** `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/CollectionDaoTest.java`
must stay green, including whatever it asserts about nesting a collection under a parent. Add a
case that deleting a parent collection behaves as the single remaining constraint says it does
(`V2.7` declares no ON DELETE action, so the delete is RESTRICTed) — that is the delete-cascade
test [../guidelines/CODING.md](../guidelines/CODING.md) requires and it does not exist today.
`./setup-pool.sh`, then `mvn test -pl loom/db/jooq -Dtest=CollectionDaoTest`.

---

### Task 24: Give `vector_config` a primary key, and add the four missing actor foreign keys

**Argumentation Summary:** `V2.6__add_vector_config.sql` creates a table with **no primary key,
no unique constraint on `uuid`, and no foreign keys at all**, while declaring
`creator_uuid uuid NOT NULL` and `editor_uuid uuid NOT NULL`. Two things follow. First, the
codegen consequence is already visible and already blocking:
`loom/db/jooq/src/jooq/java/io/metaloom/loom/db/jooq/tables/records/JooqVectorConfigRecord.java:29`
extends **`TableRecordImpl`**, not `UpdatableRecordImpl` (compare `JooqShareRecord:27`), because
jOOQ has no key to update or delete by — so
[PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) Task 6, which asks for a `VectorConfigDao extends
CRUDDao<VectorConfig>`, **cannot be implemented against this table as it stands**. Second, the
defect is not theoretical: the runtime integrity report already hunts for its consequences —
`DbIntegrityCodes.DUPLICATE_VECTOR_CONFIG_UUID` (`RowCountCheck.java:57`) and
`DANGLING_VECTOR_CONFIG_ACTOR` exist precisely because the schema cannot prevent them. The
same file carries two more: `DANGLING_TOKEN_EDITOR` and `DANGLING_ASSET_REMIX_EDITOR`
(`DanglingUserReferenceCheck.java:53,66`) — `V2.1:141` gives `token` a FK for `creator_uuid`
only, and `V2.8:76-78` does the same for `asset_remix`, so in both tables `editor_uuid` is a
`NOT NULL uuid` referencing nothing. Detecting a broken invariant at runtime is a fallback for
data that predates a constraint; here there is no constraint to have predated.

**Improvement Summary:** Add the primary key and the four foreign keys the schema always
implied, turning three runtime integrity checks into structural impossibilities.

```
1. Establish that the repair is a no-op before writing it. vector_config has NO writer at all
   — PERSISTENCE_TASKS.md Task 6 records that a repo-wide grep finds it only under
   loom/db/jooq/src/jooq/ — so the table is empty on every installation and no dedup or
   backfill step is needed. Confirm that against a pooled DB rather than assuming it, and for
   token / asset_remix run the two integrity checks first (GET /api/v1/db-integrity, or call
   DanglingUserReferenceCheck directly) to see whether any real row would block the
   constraint.
2. loom/db/flyway/src/main/resources/db/migration/V2.100__vector_config_constraints.sql:
     ALTER TABLE "vector_config" ADD CONSTRAINT "vector_config_pkey" PRIMARY KEY ("uuid");
     ALTER TABLE "vector_config" ALTER COLUMN "uuid" SET NOT NULL;   -- if not implied
     ALTER TABLE "vector_config" ADD CONSTRAINT "vector_config_creator_uuid_fkey"
       FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
     ALTER TABLE "vector_config" ADD CONSTRAINT "vector_config_editor_uuid_fkey"
       FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
   No ON DELETE action, matching detection_creator_uuid_fkey (V2.43) and
   cluster_reviewer_uuid_fkey (V2.88): users are not deleted casually and losing the author
   is worse than blocking the delete. If step 1 found rows with a dangling actor, decide
   between repairing them and relaxing the column to nullable per the V2.47 precedent — and
   write which, and why, into the file comment.
3. In the SAME migration, the two omissions of the same shape:
     ALTER TABLE "token" ADD CONSTRAINT "token_editor_uuid_fkey"
       FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
     ALTER TABLE "asset_remix" ADD CONSTRAINT "asset_remix_editor_uuid_fkey"
       FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
   V2.1:141 and V2.8:78 declared the creator side and forgot the editor side.
4. Update spec/features/db/DB_INTEGRITY.md: the three checks stay (they still guard older
   installations mid-upgrade) but must now record that the schema prevents the condition from
   V2.100 onward. Do not delete the checks or their tests.
5. Regenerate jOOQ (loom/db/jooq/generate.sh). JooqVectorConfigRecord becomes an
   UpdatableRecordImpl — that type change is the unblocking effect and is the thing to verify.
   Re-run ./setup-pool.sh with loom/db/flyway installed first.
   ⚠️ If you also land PERSISTENCE_TASKS.md Task 6 in the same change, a new DAO alters the
   DaoCollection constructor and loom/core must be CLEAN-rebuilt or setup-pool.sh fails with
   NoSuchMethodError.
6. No loom-ui change. Checked: nothing under loom-ui/src references vector_config or a vector
   configuration; the search index admin screen (loom-ui/src/features/admin/SearchIndicesAdmin.tsx)
   operates on indices, not on this table.
```

**References:** migrations `V2.6__add_vector_config.sql`, `V2.1__add_acl.sql:141`,
`V2.8__add_asset.sql:76-78` ·
[DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) (its opening blockquote lists all
three as runtime-detected) · [../features/db/DB_INTEGRITY.md](../features/db/DB_INTEGRITY.md) ·
[PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) Task 6 (blocked by this) ·
[../loom/PERSISTENCE.md](../loom/PERSISTENCE.md)
**Test Requirements:**
`loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/integrity/DbIntegrityServiceTest.java`
seeds the very rows these constraints forbid in order to assert the findings — **it will fail
after this migration**, and fixing it is part of the task: the seeding must move to a path the
constraints permit, or those cases must assert that the insert is now rejected. Same for
`loom/core/src/test/java/io/metaloom/loom/core/endpoint/test/DbIntegrityEndpointTest.java:104`.
Add a `VectorConfigDaoTest` only if Task 6 lands with this; otherwise assert the key exists by
round-tripping an insert and an update through jOOQ. `./setup-pool.sh`, then
`mvn test -pl loom/db/jooq -Dtest='DbIntegrityServiceTest'` and
`mvn test -pl loom/core -Dtest=DbIntegrityEndpointTest`.

---

## 6. Open, but owned elsewhere — do not duplicate

| Item | Owner |
|---|---|
| **`asset_node_result` write path** — `origin` hard-coded to `COMPUTED` in `AbstractMediaNode:149`, no `runUuid`/`taskUuid` on `NodeResultCreateRequest`, `cortex_instance` never joined. The **columns already exist** (`V2.45`); this is a writer gap, not a schema gap | [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 18** |
| **`dedup_group.keep_asset_uuid` vs. `dedup_group_member.role`** — `DedupGroupDaoImpl.updateStatus` never rewrites `role`, and the *"The DAO keeps them consistent"* comment at `V2.61__add_dedup_group.sql:12` is false. Fix the comment there, not here | [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 3** |
| DAO / model / DAO-test gaps (`VectorConfigDao` — **blocked by Task 24**, `asset_remix` operations, `SpaceDaoTest`, missing cascade suites) | [PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) · [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) §Progress Assessment |
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
- 🔴 **Not all of that output is generated.** Five registry files — `Tables.java`, `Keys.java`,
  `Indexes.java`, `JooqPublic.java`, `DefaultCatalog.java` — and the `JooqLoomPermission` enum
  are **hand-written** and must be edited by hand when a table, key, index or permission value
  is added. A new table that is not registered in `Tables.java` is invisible to every DAO.
- **A table with no single-column primary key generates a `TableRecordImpl`**, not an
  `UpdatableRecordImpl`, so jOOQ gives it no `update()` or `delete()`. `vector_config` is the
  live example (Task 24). Any task proposing a **new table** must give it a `uuid PRIMARY KEY`
  unless it deliberately wants an insert-only record — and must say which.
- **Sort migration versions numerically, not lexically.** `ls` puts `V2.9` after `V2.99`; the
  next free version today is `V2.100`. Getting this wrong produces a duplicate-version file.
- **The pooled test database is shared and pre-populated.** Never assert an absolute row count,
  or that a table is empty — seed your own fixtures and assert relative to them. A test that
  passes alone and fails in the suite is almost always this.
- **A new DAO changes the `DaoCollection` constructor**, which fans out through Dagger:
  `loom/core` must be **clean**-rebuilt or even `./setup-pool.sh` fails with `NoSuchMethodError`.

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
      `collection_asset.collection_uuid` (`V2.80`). The only non-cascading asset FK left is one
      intentional `SET NULL` (`dedup_group.keep_asset_uuid`); `person.primary_image_uuid` was the
      other until `V2.91` removed it.
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
- [x] **The review author** (`V2.88`) — `cluster.reviewed_at` / `reviewer_uuid`, so a node
      re-run can no longer erase which human attributed a face to a person. `V2.81` did the
      same for `detection`; `ClusterDaoImpl.upsertCluster` excludes both from `DO UPDATE`
- [x] **Person- and user-owned images** (`V2.89`–`V2.93`) — `attachment.person_uuid` and
      `attachment.user_uuid`, `person.avatar_attachment_uuid`, and a partial unique index
      making "one avatar per user" a schema fact. `V2.91` dropped the asset-backed
      `person_image` gallery and `person.primary_image_uuid` that this replaced
- [x] **The share model** (`V2.96`–`V2.99`) — `share` plus `share_annotation` /
      `share_comment` / `share_reaction` for guest feedback, kept apart from the internal
      social tables because guest rows have an `author_name` and no `creator_uuid`. Full DAO
      stack (`ShareDao`, `ShareFeedbackDao`) and delete-cascade coverage in `ShareCascadeTest`

### Open — this file

- [ ] **Task 15** — `user_permission` / `token_permission` primary keys (🔴 HIGH)
- [ ] **Task 24** — `vector_config` primary key + four missing actor FKs (`vector_config`
      creator/editor, `token.editor_uuid`, `asset_remix.editor_uuid`)
      (🔴 HIGH; **blocks** [PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) Task 6)
- [ ] **Task 16** — `pipeline_run_item.asset_uuid`, `pipeline_run.pipeline_version_uuid`,
      `pipeline_node_task.leased_by` FKs (🔴 HIGH)
- [ ] **Task 22** — index the referencing side of the cascade and provenance FKs
      (158 of 252 FKs unindexed; the asset cascade and the 13 provenance tables are the ones
      that matter)
- [ ] **Task 17** — widen `asset_location.filekey_*` to `bigint`
- [ ] **Task 18** — index the lease-holder query; retire the speculative dispatch index
- [ ] **Task 19** — `asset_doc_comp` producer, or drop the table (**needs loom-ui work**)
- [ ] **Task 23** — drop the duplicate `collection.parent_collection_uuid` FK
- [ ] **Task 21** — re-sync `dbdiagram.yaml` from `V2.84` to `V2.99`
- [ ] **Task 20** — re-sync the resolved findings in `DB_SCHEMA_FEEDBACK.md`

Closed tasks are removed rather than marked, so this list is the whole of the open work owned
here. See §5 for severity order and the blocking relationships.

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
| ER diagram | `loom/design/DB/dbdiagram.yaml` (**stale — through `V2.84` only, Task 21**) |
| Which FKs lack an index | parse `Keys.java` + `Indexes.java` under `loom/db/jooq/src/jooq/java/…/db/jooq/` — they reflect the migrated schema, the SQL text does not |
| Entity inventory | [../loom/DOMAIN.md](../loom/DOMAIN.md) |
| Persistence layer design | [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) |
| Open DAO / DAO-test work | [PERSISTENCE_TASKS.md](PERSISTENCE_TASKS.md) |
| Node behaviour | [../features/nodes/NODES.md](../features/nodes/NODES.md) |
| Schema audit (section numbers are an API) | [../features/db/DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) |

---
_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (code audit)_
