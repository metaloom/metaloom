# DB Schema Feedback

> Review of `loom/design/DB/dbdiagram.yaml` (schema as of Flyway `V2.37`), with a focus
> on **asset management** and on whether the asset-side tables are a suitable persistence
> target for the results produced by the Cortex nodes described in
> `spec/features/pipeline-nodes/NODES.md`.
>
> Findings were verified against the migrations under
> `loom/db/flyway/src/main/resources/db/migration/`, not only against the diagram.
> Where a claim depends on code, the relevant class is named.
>
> **Status:** the node-result findings were addressed by migrations `V2.38`–`V2.47`
> (see [db/DATABASE_TASKS.md](db/DATABASE_TASKS.md)). Resolved findings are marked
> **✅ RESOLVED** in place rather than deleted, so the reasoning stays readable.
> Everything unmarked is still open.
>
> | Finding | Closed by |
> |---|---|
> | §2.1 split identity, §2.5 dead S3 columns, §3.8 `is_complete` | `V2.46` |
> | §3.2 no idempotency key, §3.3 overloaded `source`, §3.4 no provenance, §8.2 audit columns | `V2.38`–`V2.42`, `V2.47` |
> | §3.8 no fingerprint / segment home | `V2.41`, `V2.42` |
> | §4.1 detection↔embedding link, §4.4 detection idempotency, §2.6 (detection only) | `V2.43` |
> | §5.2 (partly) one geometry convention for detection+embedding | `V2.43` |
> | §7.2 component permission model | documented in [permissions/PERMISSIONS.md](permissions/PERMISSIONS.md) §2.5 |
> | §2.3 `asset_location UNIQUE (asset_uuid)` + missing `(library_uuid, path)` key | `V2.48` |
> | §2.6 (partly) `reaction`/`comment` → `annotation` cascade | `V2.48` |
> | §6.3 circular pipeline/version pointer blocking deletes | `V2.49` |
>
> One finding was **withdrawn**: §8.4's suggestion of an "exactly one target" CHECK on
> `attachment` is wrong. An `EMBEDDING_ATTACHMENT` deliberately carries both the
> embedding it depicts *and* the asset that embedding came from — the test fixture and
> the embedding endpoints rely on it. `V2.44` records that in a comment instead.

---

## 1. Executive summary

The relational core (ACL, collections, libraries, tags, social) is conventional and sound.
The **asset subsystem is where the design does not yet match its stated purpose**.

Three structural problems dominate:

1. **The component tables cannot be written idempotently.** Every `asset_*_comp` table is
   keyed only by a surrogate `uuid`. There is no unique key on `(asset_uuid, source, …)`
   and no upsert in `AssetComponentDao`. A node that re-runs — which the pipeline explicitly
   supports via retries, lease expiry and re-delivery — appends a *second* row rather than
   replacing the first. Nothing in the schema tells a reader which of the resulting rows is
   current.
2. **Node results have no provenance.** No result row (`asset_*_comp`, `detection`,
   `embedding`) references `pipeline_run`, `pipeline_node_task`, `pipeline_version`, the node
   `kind`, or a model/algorithm version. The only link is a free-text `source varchar`. This
   makes the open NODES.md item *"No node versioning … no way to invalidate cached results
   from the previous version"* unsolvable at the DB level, not just at the cache level.
3. **`asset_location` was constrained into meaninglessness.** `V2.10` states in its own table
   comment that *"Multiple asset_locations may share the same asset"* — the entire reason the
   table exists. `V2.20` then added `UNIQUE (asset_uuid)`. The table can now hold exactly one
   path per content hash, which contradicts the comment, the content-addressed model, and the
   `HashDedupNode` use case.

Secondary but real: deleting an asset is impossible today (missing cascades), the execution
ledger and the catalog are joined only by an unconstrained hash string, and several
ACL tables have primary keys that silently discard rows.

---

## 2. Asset core

### 2.1 Split identity: `sha512sum` PK + `uuid` unique — HIGH ✅ RESOLVED (`V2.46`)

`asset` has `PRIMARY KEY (sha512sum)` and a separate `UNIQUE INDEX` on `uuid`. **Every child
table FKs to `uuid`, not to the primary key.** Consequences:

- Two identity columns must be kept consistent forever; there is no single authoritative key.
- `asset.uuid` is **nullable** (`uuid DEFAULT uuid_generate_v4()` with no `NOT NULL`). An
  explicit `INSERT … (uuid) VALUES (NULL)` succeeds, and the unique index permits multiple
  NULLs. A FK target should always be `NOT NULL`.
- The PK is a 128-character `varchar`. Every index on `asset` (and the PK itself) is far larger
  than it needs to be. If the content hash must remain the PK, store it as `bytea` (64 bytes)
  and expose hex at the REST layer.

**Recommendation:** make `uuid` the primary key (it is already what everything references) and
demote `sha512sum` to `NOT NULL UNIQUE`. This is a mechanical migration and removes the whole
class of "which key do I use here?" bugs.

### 2.2 Content-addressed identity fights the pipeline's own lifecycle — HIGH

`sha512sum` is `NOT NULL` and is the PK, so **an asset row cannot exist before a hashing node
has run.** But per NODES.md the source node emits a `LoomMedia` path, and `SHA512Node` is an
ordinary downstream node in the DAG. This is precisely why `pipeline_run_item` had to invent
its own `media_path` + nullable `sha512` identity in `V2.31`.

The result is two parallel notions of "the thing being processed":

| | catalog | execution ledger |
|---|---|---|
| identity | `asset.sha512sum` / `asset.uuid` | `pipeline_run_item.uuid` |
| natural key | content hash | `(run_uuid, item_seq)` + `media_path` |
| exists from | after hashing | at discovery |

Nodes that run *before* hashing (filters, `TikaNode`, `QualityNode`, `ConsistencyNode`,
`FilesystemSourceNode`) therefore have **no asset row to write to**. Their output can only land
in `pipeline_node_task.outputs` or in xattrs. That is the actual reason most nodes never touch
the DB (see §3.1).

Also unaddressed: **content mutation**. If a file's bytes change, its hash changes, so it is a
*different* asset. Nothing migrates the old row's tags, annotations, detections or embeddings,
and the old `asset_location` row (unique per asset) is stranded pointing at a path whose content
no longer matches. There is no `superseded_by_uuid` or equivalent.

**Recommendation:** allow an asset row keyed by `uuid` with `sha512sum` nullable until a hashing
node fills it (paired with `UNIQUE` so the row collapses onto an existing asset when the hash
turns out to be known), and give `pipeline_run_item` a real `asset_uuid` FK once identity is
resolved.

> **Decision taken (`V2.46`), overriding the recommendation above:** `sha512sum` stays
> `NOT NULL`, so an asset row still cannot exist before hashing. The node system already
> assumes SHA-512 is available (`AbstractMediaNode` fetches the asset by SHA-512 in its
> lifecycle) and `pipeline_run_item` already carries the pre-hash identity, so nodes
> upstream of hashing hold their outputs in `pipeline_node_task.outputs` until identity
> exists. Only the PK/FK split (§2.1) was fixed. The rule is recorded in a
> `COMMENT ON TABLE "asset"` so it is not re-litigated. Content mutation and the
> `pipeline_run_item.asset_uuid` FK (§6.1) remain open.

### 2.3 `asset_location UNIQUE (asset_uuid)` — HIGH ✅ RESOLVED (`V2.48`)

Introduced in `V2.20` under the comment "Enforce one binary per asset". It conflates two
different things: *one binary blob per content hash* (true by construction — the hash **is** the
content) and *one filesystem path per content hash* (false, and the opposite of what a
deduplicating media catalog needs).

Concretely broken by this constraint:

- The same file present in two libraries (`asset_location.library_uuid` exists exactly to model
  this) cannot be recorded twice.
- `HashDedupNode`, whose entire job is finding SHA-512 duplicates across paths, has nowhere to
  record what it found.
- The differential filesystem scanner cannot record "this content now also appears at path B"
  without destroying the record of path A.

Additionally the table has **no unique constraint on its own natural key** — `(library_uuid,
path)` or `(pool_uuid, path)`. A repeated scan can insert duplicate rows for the same path
(prevented today only as a side effect of the `asset_uuid` unique constraint).

**Recommendation:** drop `UNIQUE (asset_uuid)`; add `UNIQUE (library_uuid, path)`. If "one
*canonical* location" is genuinely wanted, express it as a nullable
`asset.primary_location_uuid` or a partial unique index on a boolean `is_primary` flag.

### 2.4 `filekey_*` column types are too narrow — MEDIUM

```
filekey_inode      int
filekey_stdev      int
filekey_edate_nano int
filekey_edate      int
```

`ino_t` on Linux is 64-bit; ext4/XFS/btrfs routinely exceed `int` on large or long-lived
filesystems. `filekey_edate` as a 32-bit epoch overflows in 2038, and `edate_nano` as `int`
cannot hold a nanosecond field at all if it is ever used as a full nanosecond timestamp. All four
should be `bigint`. This silently corrupts scanner change-detection rather than failing loudly.

### 2.5 Dead legacy columns — LOW ✅ RESOLVED (`V2.46`)

`asset.s3_bucket_name` / `asset.s3_object_path` are superseded by `asset_location.pool_uuid` →
`asset_pool`. The diagram calls them "legacy". Two ways to point at S3 for the same asset is a
correctness hazard; drop them.

### 2.6 Deleting an asset is impossible — HIGH (partly resolved: `detection` in `V2.43`, `attachment` in `V2.44`; `reaction`/`comment` → `annotation` in `V2.48`. The remaining join tables are still open.)

`ON DELETE CASCADE` was applied inconsistently. It is present on `asset_location`,
`asset_remix`, `embedding`, `annotation`, `person_image`, `blacklist` and all `asset_*_comp`
tables, but **absent** on:

- `detection.asset_uuid` (`V2.27` — plain FK)
- `collection_asset.asset_uuid`, `tag_asset.asset_uuid`, `asset_user_meta.asset_uuid`,
  `asset_task.asset_uuid` (`V2.8`)
- `attachment.asset_uuid`, `comment.asset_uuid`, `reaction.asset_uuid`

So `DELETE FROM asset` throws a FK violation for any asset that was ever tagged, put in a
collection, commented on, or had a face detected. `DELETE_ASSET` exists as a permission, which
suggests the operation is meant to work.

**Recommendation:** decide per relation — cascade for owned data (`detection`, `attachment`,
`asset_user_meta`, join tables), `ON DELETE SET NULL` for social rows that should survive
(`comment`, `reaction`) — and apply it uniformly in one migration.

---

## 3. Asset components as a node-result store

This is the part the review was asked to focus on. **The design is directionally right and
structurally incomplete.**

What is right: extracting media-specific columns out of the wide `asset` table (`V2.18`),
allowing N rows per asset per dimension, keeping a `source` discriminator, and adding a generic
`asset_json_comp` escape hatch (`V2.23`) for node output that has no dedicated shape. Those are
exactly the right instincts for a system where an open-ended set of nodes produces an open-ended
set of results.

What is missing is everything that makes such a table safe to write repeatedly from a
distributed, retrying executor.

### 3.1 Almost nothing actually writes to these tables — HIGH

A grep across `cortex/` for writes into the component tables returns **one** call site:

```
cortex/nodes/whisper/core/.../WhisperNode.java:82:  client().createAssetTranscript(assetUuid, request).sync();
```

Cross-referencing the node list in NODES.md §3, the following produce results that have an
obvious home in the schema but never reach it:

| Node | Output | Natural target | Actually persisted to |
|---|---|---|---|
| `FacedetectNode` | `face_count`, boxes | `detection` | xattr + AVRO only |
| `FacedescriptionNode` | `face_description` | `detection.meta` / `asset_json_comp` | xattr |
| `OCRNode` | `ocr_text` | `asset_doc_comp` | xattr |
| `TikaNode` | `tika_content`, flags | `asset_doc_comp` / `asset_json_comp` | xattr |
| `QualityNode` | width/height/fps/blurriness | `asset_image_comp` / `asset_video_comp` | xattr |
| `CaptioningNode` | `caption_result` | `asset_json_comp` | xattr |
| `LLMNode` | `llm_result_{promptId}` | `asset_json_comp` | xattr |
| `SceneDetectionNode` | `scene_detection` | `asset_json_comp` | xattr |
| `FingerprintNode` | `fingerprint` | `embedding` / `asset` column | xattr |
| `ConsistencyNode` | `zero_chunk_count` | `asset.zero_chunk_count` (exists!) | xattr |
| ~~`LoomNode`~~ / bulk sync | hashes only | `asset` hash columns | deleted — the hash nodes write those columns themselves |

`AssetBulkUpdateEntry` carries only `HashInfo` + `AssetUpdateRequest`, so the one generic
sync path is hash-shaped and cannot express component results at all.

The practical consequence: **the schema's asset-result surface is not being exercised, so its
gaps have not surfaced yet.** They will all surface at once when the sync path is generalised.
The fixes below are cheapest now, before there is data to migrate.

### 3.2 No idempotency key on any component table — HIGH ✅ RESOLVED (`V2.38`–`V2.42`)

None of the seven `asset_*_comp` tables has a unique constraint beyond the surrogate PK, and
`AssetComponentDao` exposes only `createXComp(userUuid, assetUuid, source)` / `loadXComps(assetUuid)`
— no `loadBySource`, no upsert. Meanwhile `pipeline_node_task` was deliberately given
`UNIQUE (item_uuid, node_id)` with the comment *"Once retries exist duplicate delivery is
inevitable, and a node must run at most once per item."*

The exact same reasoning applies one layer down and was not applied. A lease expiry, a worker
restart, or a manual re-run of the OCR node yields two `asset_doc_comp` rows for the same asset
and the same source, with no way to tell which is authoritative. Readers will end up doing
`ORDER BY created DESC LIMIT 1`, which is a convention, not a constraint.

**Recommendation:** add a unique key per component table over its identity tuple:

| Table | Suggested unique key |
|---|---|
| `asset_geo_comp` | `(asset_uuid, source)` |
| `asset_doc_comp` | `(asset_uuid, source)` |
| `asset_image_comp` | `(asset_uuid, source)` |
| `asset_video_comp` | `(asset_uuid, source)` |
| `asset_audio_comp` | `(asset_uuid, source)` — plus a track discriminator if multi-track audio is in scope |
| `asset_transcript_comp` | `(asset_uuid, source, lang, model)` — a video legitimately has one transcript per language |
| `asset_json_comp` | `(asset_uuid, source, schema_type)` |

and make the DAO do `INSERT … ON CONFLICT … DO UPDATE`. Note `source` is currently **nullable**
in every table; it must become `NOT NULL` for any of these keys to bite (Postgres treats NULLs
as distinct).

### 3.3 `source varchar` is doing too much work — HIGH ✅ RESOLVED (`V2.38`–`V2.42`)

`source` is simultaneously used for the extraction method (`'exif'`), the producing node
(`'Name of the source node that produced this data'` — `asset_json_comp`), the pipeline
(`'pipeline/source of transcription'` — `asset_transcript_comp`), and the migration marker
`'migrated'` written by `V2.18`. Four different meanings in one free-text column, with no
constraint and no vocabulary.

Split it into explicit columns:

```
node_kind        varchar NOT NULL   -- 'ocr', 'whisper', 'facedetect'  (matches pipeline_node_task.node_kind)
node_id          varchar            -- graph-local id, for multi-instance graphs
producer_version varchar            -- model/algorithm version: 'whisper-large-v3', 'tesseract-5.3'
```

`asset_transcript_comp` already has a `model` column — that instinct is correct and should be
generalised, not kept as a one-off.

### 3.4 No provenance link to the run that produced the result — HIGH ✅ RESOLVED (`V2.38`–`V2.45`)

There is no FK from any result row to `pipeline_run`, `pipeline_node_task` or
`pipeline_version`. The following questions cannot be answered by a query today:

- *Which run produced this transcript?*
- *Show me everything the pipeline wrote in run X so I can roll it back.*
- *Node `facedetect` upgraded its model — invalidate every detection produced by the old one.*
- *This detection looks wrong; which worker and which node version produced it?*

The third is listed verbatim in NODES.md §10 as an open item ("No node versioning"). It cannot
be closed by a cache change alone: the *durable* results have no version stamp either.

**Recommendation:** add to every result table (`asset_*_comp`, `detection`, `embedding`):

```
produced_by_task_uuid uuid REFERENCES pipeline_node_task (uuid) ON DELETE SET NULL
run_uuid              uuid REFERENCES pipeline_run (uuid) ON DELETE SET NULL   -- denormalised, as pipeline_node_task already does
```

`ON DELETE SET NULL` matters: run history will be pruned long before catalog data is.

### 3.5 `pipeline_node_task.outputs` is a second, competing result store — MEDIUM

`outputs jsonb` holds "Node outputs consumed by downstream nodes". For nodes that never sync
(§3.1) it is *the* durable copy of the result. So the system currently has three overlapping
persistence layers for the same values — xattr/`MetaStorage`, `pipeline_node_task.outputs`, and
the component tables — which is the DB-side reflection of the NODES.md item *"MetaStorage and
NodeCacheProvider are separate systems … creates confusion about where data is stored"*.

Worth stating explicitly in the design: `outputs` is **transport** (DAG edge payload, bounded
lifetime, prunable with the run) and the component tables are the **catalog** (permanent,
queryable, user-visible). Once that is written down, the sync gap in §3.1 becomes a bug rather
than a design choice, and `outputs` becomes safe to prune.

### 3.6 Retention: the ledger grows without bound — MEDIUM

`pipeline_node_task` is one row per (node × media item). A single 100k-file run over a 12-node
graph is 1.2M rows. There is no partitioning, no TTL, no archival column, and — with
`ON DELETE CASCADE` from `pipeline_run` — the only pruning mechanism is deleting whole runs,
which under §3.4's proposal would also be the thing catalog rows point at.

The lease-reaper index is correctly partial (`WHERE state = 'RUNNING'`), which shows the growth
was anticipated. Follow through: range-partition `pipeline_node_task` and `pipeline_run_item` by
`created`, or add a documented retention job.

### 3.7 Missing dispatch index — MEDIUM

Worker dispatch asks: *"give me PENDING tasks whose `node_kind` this worker accepts"*
(NODES.md §11, `ConnectedProcessor.accepts(kind)`). The available indexes are
`(item_uuid)`, `(run_uuid, state)`, and the partial lease index — none of which serves that
query. It degrades to a scan filtered by `state`.

Add `CREATE INDEX … ON pipeline_node_task (node_kind, state) WHERE state = 'PENDING';`

### 3.8 Component coverage vs. the node list — MEDIUM ✅ RESOLVED (`V2.41`, `V2.42`, `V2.44`, `V2.46`)

Mapping NODES.md §3 onto the seven component tables leaves real gaps:

- **No thumbnail component.** `ThumbnailNode` outputs `thumbnail_path`. The schema has
  `attachment` with `attachment_type = ASSET_THUMBNAIL`, which is a reasonable home — but
  `attachment` has no `source`/`node_kind`, so contact-sheet thumbnails from different node
  versions are indistinguishable, and `annotation.thumbnail varchar` is a *third* thumbnail
  mechanism.
- **No fingerprint home.** `FingerprintNode` produces a video fingerprint used by
  `FingerprintDedupNode`. It is not a hash column on `asset`, not an `embedding` row
  (the cluster comment mentions "media fingerprint embeddings", so that was the intent), and
  not a component. Nothing indexes it, so dedup-by-fingerprint cannot be a DB query.
- **`asset.zero_chunk_count` exists but `is_complete` does not.** `ConsistencyNode` emits both;
  the more useful of the two (a boolean the `ThumbnailNode` reads as an upstream output) has no
  column.
- **No per-frame time dimension on components.** `SceneDetectionNode` output is inherently a
  list of time ranges. `embedding` and `tag_asset` have `fromTime`/`toTime`; components do not,
  so scene data can only land as opaque `asset_json_comp.data`.

`asset_json_comp` absorbs all of these — which is fine as a deliberate staging area, but it
should be an explicit policy ("new node kinds start in `asset_json_comp`; promote to a typed
table when queries need it"), not an accident. Add a GIN index on `asset_json_comp.data` if it
is ever to be queried by content; today only `schema_type` is indexed.

---

## 4. Detection / embedding / cluster

### 4.1 `detection` and `embedding` are not linked — HIGH ✅ RESOLVED (`V2.43`)

A face detection and the face embedding computed from that same detected region are the
canonical pair, and there is no FK between the two tables. Both carry an independent copy of the
geometry — `detection` as normalized `bbox_x/y/width/height real`, `embedding` as absolute
`areaStartX/areaStartY/areaWidth/areaHeight int` — in **different units and different
coordinate conventions**. Correlating them requires floating-point geometry matching plus the
asset's pixel dimensions.

**Recommendation:** add `embedding.detection_uuid uuid REFERENCES detection (uuid) ON DELETE CASCADE`
and drop the duplicated geometry from `embedding` (keep `fromTime`/`toTime`, which `detection`
lacks — or better, move the frame/time dimension onto `detection` alongside `frame_number` and
let `embedding` inherit it).

Related: `detection` has no path to `cluster` either. Clusters attach to `embedding` via
`embedding_cluster`, so "which person is in this bounding box" is a two-hop join that only works
if the embedding exists. And `person` (with `person_image`) overlaps `cluster` of
`type = 'person'` with no relation between them — two competing models of the same concept.

### 4.2 `embedding.vector real[]` — MEDIUM

The diagram is explicit: "plain PG array; not pgvector". Implications:

- No ANN index is possible. Similarity search is a full scan with array arithmetic, or it must
  be exported to an external vector DB — which `vector_config` ("used to build indices in an
  external vector database") confirms is the plan.
- No dimension constraint. A 512-d InspireFace vector and a 128-d dlib vector coexist in one
  column, distinguishable only by the free-text `type`.
- No embedding-model version column, so re-embedding with a new model produces vectors that are
  silently incomparable with the old ones. `type varchar` ('dlib_facemark') is being used as a
  proxy for model identity.

If similarity search is ever to run in Postgres, `pgvector` with a per-type dimension is the
answer. If it is genuinely delegated to an external store, then `embedding.vector` is a staging
buffer and should say so — plus it needs a `synced_at` / `index_version` column so the exporter
knows what is stale. Right now neither story is expressed.

### 4.3 `cluster.name` is globally unique — MEDIUM

`CREATE UNIQUE INDEX ON cluster (name)` spans all cluster types. A person cluster named
`"sunset"` and a visual-similarity cluster named `"sunset"` cannot coexist, and two distinct
people with the same name cannot both be clusters. Should be `UNIQUE (type, name)` at minimum;
for people, name uniqueness is wrong regardless.

### 4.4 `detection` has no idempotency key — MEDIUM ✅ RESOLVED (`V2.43`)

Same problem as §3.2, and worse in effect: re-running `FacedetectNode` on a video appends a
complete second set of detections for every frame. There is no `(asset_uuid, type, frame_number,
produced_by_task_uuid)` constraint and no delete-then-insert contract documented anywhere.

---

## 5. Tagging and annotation

### 5.1 `tag_asset` PK defeats its own columns — HIGH

```
PRIMARY KEY (tag_uuid, asset_uuid)
+ columns time_from, time_to, areaStartX/Y, areaWidth/Height
```

The extra columns exist to place a tag at a timecode or a region. The PK allows **one placement
per (tag, asset)**. Tagging the same person in two shots of the same video, or in two faces of
the same photo, is impossible — which is exactly the output `FacedetectNode` + clustering is
meant to produce.

**Recommendation:** give `tag_asset` a surrogate `uuid` PK plus
`UNIQUE (tag_uuid, asset_uuid, time_from, time_to, areaStartX, areaStartY)`, or split the
spatial placement into its own table.

### 5.2 Three overlapping ways to attach a region to an asset — MEDIUM

`tag_asset`, `annotation`, and `detection` each carry their own bbox/time-range representation,
in three different conventions (int absolute, int absolute, normalized real). `embedding` makes
four. A single shared "region" concept — or at minimum one agreed convention (normalized floats
+ optional frame/time) — would remove a class of conversion bugs and let the UI render all four
with one code path.

### 5.3 `annotation` has both a direct FK and a join table — LOW

`annotation.asset_uuid` is `NOT NULL`, and `annotation_asset` also exists as an M:N join. Two
ways to express the same relation; one of them is presumably vestigial. Pick one.

---

## 6. Pipeline tables

### 6.1 The ledger↔catalog join is an unconstrained string — HIGH

`pipeline_run_item.sha512 varchar` with no FK. This is *the* seam between "what the pipeline
did" and "what is in the catalog", and it is a soft reference to a column that is the PK of
`asset` — so a real FK is available and simply was not declared. It is also not indexed, so
"which runs touched this asset?" is a scan.

Add `asset_uuid uuid REFERENCES asset (uuid) ON DELETE SET NULL` (see §2.2 for why the hash
alone cannot carry this) and index it.

### 6.2 `pipeline_run.pipeline_version int` is not a foreign key — MEDIUM

`pipeline_version` is identified by `uuid` with `UNIQUE (pipeline_uuid, version_number)`.
`pipeline_run.pipeline_version` stores the bare integer, so nothing prevents a run from claiming
version 7 of a pipeline that only has 3 versions, and reconstructing "what definition did this
run execute?" needs a two-column lookup instead of a join. Use
`pipeline_version_uuid uuid REFERENCES pipeline_version (uuid)`.

Note the same pattern is handled *correctly* one table over: `skill.active_version_uuid` is a
proper FK to `skill_version.uuid`. The pipeline side should match.

### 6.3 Circular FK between `pipeline` and `pipeline_version` — LOW ✅ RESOLVED (`V2.49`)

`pipeline.latest_version_uuid → pipeline_version.uuid` and
`pipeline_version.pipeline_uuid → pipeline.uuid`. Insert order requires a nullable column plus
a follow-up `UPDATE` (or a deferred constraint). Workable, but `skill`/`skill_version` has the
identical shape, so if it is ever tightened, tighten both. Alternatively derive "latest" as
`MAX(version_number)` and drop the denormalised pointer.

### 6.4 `leased_by` is a soft reference to `cortex_instance` — MEDIUM

`pipeline_node_task.leased_by varchar` holds a processor `node_id`, which is
`cortex_instance.node_id UNIQUE`. A real FK is available. Without it, "show me every task this
dead worker was holding" cannot be joined reliably, and a typo'd worker id is undetectable.
Same for tracking *which* worker produced a result (§3.4).

### 6.5 State columns are `varchar` while the rest of the schema uses PG enums — LOW

`pipeline_run.status`, `pipeline_run_item.state`, `pipeline_node_task.state`, `asset_location.state`
and `cortex_instance.state` are all unconstrained `varchar` with the legal values written only in
a `COMMENT`. Meanwhile `task_status`, `task_priority`, `annotation_type`, `attachment_type` and
`loom_permission` are proper enums. Given the codebase already regenerates jOOQ enums
(`loom/db/jooq/generate.sh`), the varchar states are the odd ones out — a typo'd
`'COMPLETE'` vs `'COMPLETED'` is currently a silent no-match. At minimum add `CHECK`
constraints.

### 6.6 `cortex_instance_node_kind` permits self-contradiction — LOW

`PRIMARY KEY (instance_uuid, node_kind, list)` lets the same kind appear in both `WHITELIST` and
`BLACKLIST`. NODES.md §11 defines the precedence (blacklist wins), so behaviour is defined — but
the constraint could just be `PRIMARY KEY (instance_uuid, node_kind)` with `list` as an
attribute, making the contradiction unrepresentable. `list` is also a `CHECK`ed varchar rather
than an enum (§6.5).

---

## 7. ACL

### 7.1 Permission-table primary keys discard rows — HIGH

```
role_permission:  PRIMARY KEY (role_uuid, permission)     + UNIQUE (role_uuid, resource, permission)
user_permission:  PRIMARY KEY (user_uuid)                 + UNIQUE (user_uuid, resource, permission)
token_permission: PRIMARY KEY (token_uuid)                + UNIQUE (token_uuid, resource, permission)
```

In all three the PK is narrower than the accompanying unique index, which makes the unique index
unreachable:

- **`user_permission` / `token_permission`**: PK on the owner alone means **exactly one direct
  grant per user (or token), ever.** A second `INSERT` is a PK violation, not an added
  permission. This is a known trap — the project's own test suite works around it by granting
  permissions via group+role instead of directly (`SkillEndpointTest`).
- **`role_permission`**: PK `(role_uuid, permission)` means a role can grant `READ_ASSET` on
  **one** resource only. The `resource` column is effectively decorative, and the intended
  `(role_uuid, resource, permission)` grain — spelled out by the unique index right below it —
  is unreachable.

**Recommendation:** in all three tables, promote the unique index to the primary key and drop the
narrow one. This is a small migration with a large correctness payoff, and it is likely masking
bugs today rather than merely limiting the model.

### 7.2 No component-level permissions — LOW ✅ RESOLVED (documented, no enum change)

`loom_permission` has `*_ASSET`, `*_ASSET_LOCATION`, `*_ASSET_BINARY`, `*_ASSET_POOL`,
`*_DETECTION`, `*_EMBEDDING` — but nothing for asset components. Component reads/writes are
presumably guarded by `READ_ASSET`/`UPDATE_ASSET`. That may well be the right call (components
are sub-resources, exactly as `pipeline_run_item` is deliberately guarded by
`READ_PIPELINE_RUN`), but it deserves a one-line note in the design, because `detection` and
`embedding` — which are equally sub-resources of an asset — went the other way and got their
own permissions. The inconsistency reads as an oversight.

---

## 8. Cross-cutting

### 8.1 `timestamp` without time zone, everywhere — MEDIUM

Every temporal column in the schema is `TIMESTAMP WITHOUT TIME ZONE` with `DEFAULT now()`.
For a system whose workers (`cortex_instance.host`, `last_seen`, `lease_expires_at`) may run on
machines in different zones, this is a latent bug: lease expiry compares wall-clock values whose
zone is implicit. Use `timestamptz` throughout. Cheap to change now, painful later.

### 8.2 Audit columns on machine-written rows — MEDIUM ✅ RESOLVED (`V2.38`–`V2.43`, `V2.47`)

`creator_uuid`/`editor_uuid` are `NOT NULL` on every result table (`asset_*_comp`, `detection`,
`embedding`) — but these rows are written by *workers*, not users. `cortex_instance` already
recognised this and made its audit columns nullable, with a comment explaining why. The result
tables have the same property and did not get the same treatment, which forces the sync path to
invent a synthetic user. Either make them nullable there too, or introduce an explicit
system/service principal and document it.

### 8.3 `loom` singleton has no primary key — LOW

Diagram note: "Singleton bookkeeping row (no primary key)." Nothing stops a second row.
`CHECK (id = 1)` on a constant-valued PK column is the usual guard.

### 8.4 `reaction` uniqueness is asymmetric — LOW (the `attachment` half of this finding was WITHDRAWN — see the status box)

Unique indexes exist for `(creator_uuid, type, asset_uuid)`, `…comment_uuid`, `…annotation_uuid`
— but not `…task_uuid`, although `task_uuid` is one of the four targets. Also, the four target
columns are all nullable with nothing enforcing that exactly one is set; a `CHECK` with
`num_nonnulls(asset_uuid, task_uuid, comment_uuid, annotation_uuid) = 1` would make the
"one of" intent real. The same polymorphic-nullable-FK pattern is used by `comment` (three
targets) and `attachment` (two), with no such check anywhere.

---

## 9. Prioritised recommendations

**Do first — cheap now, expensive after the sync path lands data:**

1. Add idempotency keys to all `asset_*_comp` tables and make `source NOT NULL` (§3.2).
2. Add provenance columns (`node_kind`, `producer_version`, `produced_by_task_uuid`) to all
   result tables (§3.3, §3.4) — this is the DB half of NODES.md's "no node versioning" item.
3. Fix the ACL primary keys (§7.1) — likely masking live bugs.
4. Drop `asset_location UNIQUE (asset_uuid)`; add `UNIQUE (library_uuid, path)` (§2.3).
5. Make the asset delete cascades consistent (§2.6).

**Do next — structural, needs a decision:**

6. Settle asset identity: `uuid` as PK, `sha512sum` as `NOT NULL UNIQUE`, and define what
   happens when content changes (§2.1, §2.2).
7. Link `embedding → detection` and unify the geometry convention (§4.1, §5.2).
8. Give `pipeline_run_item` a real `asset_uuid` FK (§6.1).
9. Fix `tag_asset`'s primary key (§5.1).
10. Move to `timestamptz` (§8.1) and widen the `filekey_*` columns (§2.4).

**Decide and document — no migration required, but the ambiguity is costly:**

11. State the contract: `pipeline_node_task.outputs` = transport, components = catalog; then
    close the sync gap so node results actually reach the DB (§3.1, §3.5).
12. State the policy for new node kinds: land in `asset_json_comp`, promote to a typed table
    when queried (§3.8).
13. State whether vector search lives in Postgres (`pgvector`) or in an external store, and
    make `embedding` reflect that choice (§4.2).
14. Add a retention/partitioning plan for the execution ledger (§3.6).
