# DB Schema Feedback

> Audit of the Loom relational schema, focused on **asset management** and on whether the
> asset-side tables are a suitable persistence target for the Cortex node results described in
> [pipeline-nodes/NODES.md](nodes/NODES.md).
>
> Originally written against Flyway `V2.37`. **Re-verified against the migration chain up to
> `V2.63`.** Most of the audit has been executed — see [db/DATABASE_TASKS.md](db/DATABASE_TASKS.md)
> for the rework record. This file is now a **residual defect list**: closed findings are kept as
> one-line pointers (their `§` numbers are cited from other spec files and from migration comments,
> so the numbering must stay stable), and only the still-open findings carry detail.
>
> **Do not duplicate here:** the entity inventory lives in [../loom/DOMAIN.md](../loom/DOMAIN.md),
> the executed rework in [db/DATABASE_TASKS.md](db/DATABASE_TASKS.md), and the open DAO/test gaps
> in [../loom/PERSISTENCE_TASKS.md](../tasks/PERSISTENCE_TASKS.md).
>
> **Part of this audit is now executable.** [DB_INTEGRITY.md](DB_INTEGRITY.md) turns the structural
> findings into 29 named checks that run against a live database, from the admin area and from any
> test. Three findings this file records are detected there today:
>
> | Finding | Check |
> |---|---|
> | `token.editor_uuid` has no foreign key (V2.1 declares one for `creator_uuid` only) | `DANGLING_TOKEN_EDITOR` |
> | `asset_remix.editor_uuid` repeats the same omission (V2.8) | `DANGLING_ASSET_REMIX_EDITOR` |
> | `vector_config` has no primary key and no foreign keys at all (V2.6) | `DANGLING_VECTOR_CONFIG_ACTOR`, `DUPLICATE_VECTOR_CONFIG_UUID` |
>
> 🔴 **Detection is not a fix.** The constraints are still absent and should be added by a migration
> of their own; until then these checks are what notices when the absence costs something.

---

## 1. Executive summary

The three structural problems that dominated the original audit are **all fixed**:

1. *Component tables could not be written idempotently* — every `asset_*_comp` table now has an
   explicit unique key and an upsert DAO (`V2.38`–`V2.42`).
2. *Node results had no provenance* — every result table carries `node_kind` / `node_id` /
   `producer_version` / `run_uuid` / `task_uuid`, and `asset_node_result` (`V2.45`) is the
   per-asset processing ledger that answers "has node X at version V processed asset A?".
3. *`asset_location` was constrained into meaninglessness* — `UNIQUE (asset_uuid)` dropped and
   replaced by `UNIQUE (library_uuid, path)` (`V2.48`).

The result-write gap is also largely closed: ~15 Cortex nodes now write into the catalog through
the REST client (see §3.1), where the audit found exactly one.

**What remains open** falls into three groups:

| Group | Findings |
|---|---|
| Real correctness defects, cheap to fix | §7.1 ACL primary keys · §2.4 `filekey_*` widths |
| Missing referential integrity / typing in the pipeline tables | §6.1, §6.2, §6.4, §6.5, §6.6 |
| Undecided design questions | §4.2 vector storage · §3.6 ledger retention · §8.1 `timestamptz` |

Plus a new class the original audit did not have: **schema with no producer** (§10) — tables and
columns that exist, are indexed, and are never written.

---

## 2. Asset core

- **§2.1 Split identity (`sha512sum` PK + `uuid` unique)** — ✅ **RESOLVED** (`V2.46`): `uuid` is
  the primary key, `sha512sum` is `NOT NULL UNIQUE`.
- **§2.3 `asset_location UNIQUE (asset_uuid)`** — ✅ **RESOLVED** (`V2.48`): dropped, replaced by
  `UNIQUE (library_uuid, path)`. `V2.63` added the `(pool_uuid, path)` index.
- **§2.5 Dead legacy S3 columns on `asset`** — ✅ **RESOLVED** (`V2.46`): dropped.

### 2.2 Content mutation has no model — MEDIUM (open remainder of a resolved finding)

The identity half of this finding was **decided, not fixed**: `V2.46` keeps `sha512sum NOT NULL`,
so an asset row still cannot exist before hashing. Nodes upstream of hashing hold their outputs in
`pipeline_node_task.outputs`; `pipeline_run_item` carries the pre-hash identity. The rule is
recorded in `COMMENT ON TABLE "asset"` so it is not re-litigated.

**Still open:** if a file's bytes change its hash changes, so it is a *different* asset. Nothing
migrates the old row's tags, annotations, detections, embeddings or components, and the old
`asset_location` row is stranded pointing at a path whose content no longer matches. There is no
`superseded_by_uuid` or equivalent. Related: §6.1 (`pipeline_run_item` still has no `asset_uuid` FK).

### 2.4 `filekey_*` column types are too narrow — MEDIUM

`asset_location.filekey_inode`, `filekey_stdev`, `filekey_edate_nano`, `filekey_edate` are all
`int` (`V2.10`, never widened). `ino_t` is 64-bit and ext4/XFS/btrfs routinely exceed `int`;
`filekey_edate` overflows in 2038; `filekey_edate_nano` cannot hold a nanosecond field at all.
All four should be `bigint`. This silently corrupts scanner change-detection rather than failing
loudly.

### 2.6 Deleting an asset — RESOLVED

Partly fixed: `detection` (`V2.43`), `attachment` (`V2.44`), `reaction`/`comment` → `annotation`
(`V2.48`), `embedding_cluster` (`V2.51`) now cascade, alongside the `asset_*_comp` tables,
`asset_location`, `annotation`, `annotation_asset`, `blacklist` and
`asset_node_result`. (`person_image` was in that list until `V2.91` dropped the table — a person's
pictures now hang off the person, not off an asset.)

`tag_asset` was fixed in `V2.72` — **both** its foreign keys now cascade, so deleting an asset drops
its tag assignments and deleting a tag drops the assignments to every asset. Neither delete touches
the object on the other side: a tag outlives the assets that carried it, and an asset outlives the
tags it wore. The tag is deliberately not reference-counted; an unused tag is an empty tag, not a
deleted one. `AssetCascadeTest.testDeletingATaggedAssetKeepsTheTag` / `testDeletingATagKeepsTheAsset`
assert it from both directions, `TagAssetEndpointTest` the same over REST — where this used to be a
**500**.

`V2.73` finished the job for the other three links, each with the same shape — the link goes, the
thing on the far end stays:

| Link | Deleting the asset… | …leaves |
| --- | --- | --- |
| `collection_asset` | takes it out of its collections | the **collection**, with every other asset filed in it |
| `asset_task` | drops that one reference | the **task** — title, status, comments, assignees — and the other assets it is about (`asset_task` has been many-to-many since `V2.8`) |
| `asset_user_meta` | removes the per-user notes on it | the **user** |

`AssetCascadeTest.testDeletingAssetLeavesTheCollection` / `testDeletingAssetLeavesTheTask` /
`testDeletingAssetRemovesUserMeta` assert it — each creating a second asset on the same
collection/task as a negative control, so "it survived" cannot be satisfied by a delete that did
nothing. `AssetEndpointTest.testDeleteAssetReferencedByTask` covers it over REST, where this used to
be a **500**. Collection membership has no REST route to link through (it is DAO-only), so that half
is pinned at DAO level alone.

`V2.74` closed the last three, on an explicit product decision:

| Link | Deleting the asset… | …leaves |
| --- | --- | --- |
| `comment` | deletes the comments written **about it**, and their reply subtrees (`comment.parent_uuid`, `V2.35`) and any reactions on those comments (`reaction.comment_uuid`, `V2.35`) | every comment anchored to a **task** or an **annotation** |
| `reaction` | deletes the reactions **to it** | reactions on tasks, comments and annotations |
| `library_asset` | removes it from its libraries | the **library**, with every other asset in it |

The other direction of `library_asset` is deliberately *not* a cascade: `library_uuid` stays a plain
reference so a library cannot be deleted out from under the assets in it — the stance `V2.63` already
took for `library → asset_pool`.

**After `V2.74` the only non-`CASCADE` foreign key to `asset` is the intentional `SET NULL` on**
`dedup_group.keep_asset_uuid`. (`person.primary_image_uuid` was the other one until `V2.91` removed
it.) `DELETE_ASSET` works for every link the system writes.

**How the tests are built.** All eight link tests share one fixture (`AssetCascadeTest.linkedPair`):
two assets wired into the same tag, collection, task and library, each with its own user meta,
comment (plus a reply and a reaction on that comment) and reaction — and a comment and a reaction on
the **task**, which no asset owns. Each test deletes the first asset, asserts its own point, then
calls `assertOnlyTheVictimsLinksAreGone`, which asserts the other half: the second asset still holds
all of its links, every shared object is still there, and the task's social content was not caught in
the blast. `AssetEndpointTest.testDeleteAssetReferencedByTask` and `TagAssetEndpointTest` do the same
over REST. Collection and library membership have no REST route to link through (both are DAO-only),
so those two are pinned at DAO level alone.

---

## 3. Asset components as a node-result store

All seven-plus component tables were reworked onto a shared contract by `V2.38`–`V2.42`. See
[db/DATABASE_TASKS.md](db/DATABASE_TASKS.md) §2 ("the three-layer model") and §4 ("the shared
component contract") for the executed design; it is not repeated here.

- **§3.2 No idempotency key** — ✅ **RESOLVED** (`V2.38`–`V2.42`): every component table has an
  explicit `*_unique_key` and the DAO upserts.
- **§3.3 Overloaded `source varchar`** — ✅ **RESOLVED** (`V2.38`–`V2.42`): split into
  `node_kind` / `node_id` / `producer_version`.
- **§3.4 No provenance link to the producing run** — ✅ **RESOLVED** (`V2.38`–`V2.45`):
  `run_uuid` / `task_uuid` (`ON DELETE SET NULL`) on every result table, plus `asset_node_result`.
- **§3.5 `pipeline_node_task.outputs` competing with the component tables** — ✅ **RESOLVED**
  (documented): `outputs` is **transport** (DAG edge payload, pruned with the run), the component
  tables are the **catalog** (permanent, queryable). Stated in
  [db/DATABASE_TASKS.md](db/DATABASE_TASKS.md) §2 and in the `V2.45` table comment.
- **§3.8 Component coverage vs. the node list** — ✅ **RESOLVED**: `asset_fingerprint_comp`
  (`V2.41`), `asset_segment_comp` (`V2.42`, carries the time dimension), `attachment` provenance
  + `UNIQUE (asset_uuid, type, node_kind, variant)` (`V2.44`), `asset.zero_chunk_count` documented
  as the completeness flag (`V2.46`), GIN on `asset_json_comp.data` (`V2.40`).

### 3.1 Result writes — mostly closed, one table still has no producer — LOW

The audit found **one** call site. There are now ~15. Nodes writing into the catalog via
`LoomClient`: `TikaNode`, `OCRNode`, `QualityNode`, `CaptioningNode`, `LLMNode`, `VlmNode`,
`SentimentNode`, `DominantColorNode`, `SceneLayoutNode`, `ScriptNode`, `S3SinkNode` →
`asset_json_comp`; `WhisperNode` → `asset_transcript_comp`; `SceneDetectionNode`, `ScriptNode` →
`asset_segment_comp`; `FingerprintNode` → `asset_fingerprint_comp`; `FacedescriptionNode` →
components; `FingerprintDedupNode` → `dedup_group`; `MD5Node`/`SHA256Node`/`ChunkHashNode`/
`ConsistencyNode` → `asset` columns. `AbstractMediaNode` writes the `asset_node_result` ledger row
for all of them.

**Remaining gap:** `asset_doc_comp` — reworked in `V2.38` with a `page_number` grain and a GIN
`text_search` column, and explicitly commented *"Tika writes the whole document as page 0, OCR
writes one row per page"* — **has no producer**. Both `TikaNode` and `OCRNode` call
`createAssetJsonComp` instead. So the schema's only full-text asset surface is unpopulated while
the extracted text sits in an opaque `jsonb`. Either point those two nodes at `asset_doc_comp` or
drop it. See also §10.

### 3.6 Retention: the execution ledger grows without bound — MEDIUM

`pipeline_node_task` is one row per (node × media item), and `V2.60` added `element_seq` fan-out
*inside* an item, so the row count per run went up, not down. A 100k-file run over a 12-node graph
is >1.2M rows. There is still no partitioning, no TTL and no archival column; the only pruning
mechanism is deleting whole runs — which is also what `asset_*_comp.run_uuid` points at (hence
`ON DELETE SET NULL` there).

Range-partition `pipeline_node_task` and `pipeline_run_item` by `created`, or add a documented
retention job. Cited from [../cortex/METALOOM_ARCHITECTURE_TASK.md](../tasks/METALOOM_ARCHITECTURE_TASK.md).

### 3.7 Missing dispatch index — MEDIUM

Worker dispatch asks *"give me PENDING tasks whose `node_kind` this worker accepts"*. The indexes
on `pipeline_node_task` are `(item_uuid)`, `(run_uuid, state)` and the partial lease index
`(lease_expires_at) WHERE state = 'RUNNING'` — none serves that query, so it degrades to a scan
filtered by `state`.

```sql
CREATE INDEX idx_pipeline_node_task_dispatch
  ON pipeline_node_task (node_kind) WHERE state = 'PENDING';
```

---

## 4. Detection / embedding / cluster

- **§4.1 `detection` and `embedding` not linked** — ✅ **RESOLVED** (`V2.43`):
  `embedding.detection_uuid` FK; the duplicated absolute-pixel geometry was dropped and
  normalized `bbox_*` on `detection` is now the single convention.
- **§4.4 `detection` has no idempotency key** — ✅ **RESOLVED** (`V2.43`).

### 4.2 `embedding.vector real[]` — MEDIUM (open decision, widely cited)

`V2.43` improved the metadata (`model`, `dimensions`, `producer_version`) but left the storage
decision open, verbatim in the column comment: *"similarity search is either pgvector in Postgres
or an external index fed via `vector_config`. Until that is decided this column is a staging
buffer with no ANN index."*

Current state: **no ANN index, and no producer** — no Cortex node writes embeddings; the only
writers are `EmbeddingEndpointService` and the test fixture, so the table is effectively empty in
practice. `vector_config` (`V2.6`), the external-index half of the plan, has no DAO at all
(tracked in [../loom/PERSISTENCE_TASKS.md](../tasks/PERSISTENCE_TASKS.md)).

If search stays in Postgres, `pgvector` with a per-`type` dimension is the answer. If it is
delegated, `embedding` needs `synced_at` / `index_version` so the exporter knows what is stale.
Neither story is expressed today. Cross-referenced from
[search/SEMANTIC_SEARCH.md](search/SEMANTIC_SEARCH.md) §1.3 and [search/SEARCH.md](search/SEARCH.md).

### 4.3 `cluster.name` is globally unique — MEDIUM

`CREATE UNIQUE INDEX ON "cluster" ("name")` (`V2.12`) spans all cluster types. A person cluster and
a visual-similarity cluster cannot share a name, and two distinct people with the same name cannot
both be clusters. Should be `UNIQUE (type, name)` at minimum; for people, name uniqueness is wrong
regardless. Related: `person` still overlaps `cluster` of `type = 'person'` with no
relation between them — two competing models of the same concept (see
[../CLUSTERING.md](../concept/CLUSTERING.md)). (`person_image` is no longer part of that overlap:
`V2.91` dropped it in favour of person-owned attachments.)

---

## 5. Tagging and annotation

### 5.1 `tag_asset` PK defeats its own columns — ✅ RESOLVED (`V2.71`)

Was: `PRIMARY KEY (tag_uuid, asset_uuid)` alongside the region columns, so a tag could be placed
**once** per asset and tagging two faces in one photo was impossible.

Fixed as recommended: surrogate `uuid` primary key plus
`UNIQUE NULLS NOT DISTINCT (tag_uuid, asset_uuid, time_from, time_to, areaStartX, areaStartY)`.
`NULLS NOT DISTINCT` is load-bearing — an asset-level tag has NULL in every region column, and
under the default semantics those rows would never conflict, so re-tagging would append forever.
It requires **PostgreSQL 15+** (test env 16.3, chart 17). `areaWidth`/`areaHeight` sit outside the
key on purpose: resizing a box updates the placement, moving it creates one.

The same migration added the provenance §5.4 records. `TagPlacementDaoTest` pins the behaviour.

### 5.2 Region conventions — still open

`V2.43` unified `detection` + `embedding` on normalized `real` bbox. `tag_asset` and `annotation`
still carry absolute-int `areaStartX/Y` + `areaWidth/Height`, so two conventions remain and the UI
still needs two code paths.

§5.1 was the event this was waiting on, and `V2.71` deliberately did **not** take it: converting the
columns is a data migration over existing region tags, not a schema change, and it would have made a
migration that is otherwise additive destructive. A detection-driven region tag must convert until
then — absolute ints in, normalized reals out.

### 5.4 `tag_asset` had no provenance — ✅ RESOLVED (`V2.71`)

Nothing on the join row said whether a person or a pipeline attached a tag, which meant the UI could
not offer "hide auto tags" and a node could not prove a tag was its own before withdrawing it — it
had to read back a component it wrote itself. The row now carries `node_kind` (defaulting to
`manual`), `node_id`, `producer_version`, `confidence`, `created` and `creator_uuid`, mirroring
`detection` (`V2.43`).

Two rules ride on those columns, both pinned by `TagPlacementDaoTest`:

- **The first author keeps the row.** The upsert carries
  `WHERE tag_asset.node_id IS NOT DISTINCT FROM excluded.node_id`, so a node attaching a tag a person
  already placed leaves that row untouched rather than taking authorship of it.
- **A writer withdraws only its own placements.** `TagDao.bulkTagAsset` scopes its delete by
  `node_id` when the caller names one; a person (no node id) removes them all.

### 5.3 `annotation` has both a direct FK and a join table — LOW

`annotation.asset_uuid` is `NOT NULL` **and** `annotation_asset` exists as an M:N join (`V2.16`).
Both cascade correctly (`AssetCascadeTest` pins that), but `annotation_asset` has no DAO and no
hand-written Java outside `AnnotationDaoImpl`'s import and the cascade test — it is vestigial.
Pick one; dropping the join table is the smaller change.

---

## 6. Pipeline tables

- **§6.3 Circular `pipeline` ↔ `pipeline_version` pointer blocking deletes** — ✅ **RESOLVED**
  (`V2.49`).

### 6.1 The ledger↔catalog join is an unconstrained string — HIGH

`pipeline_run_item.sha512 varchar` with no FK and no index (`V2.31`). This is *the* seam between
"what the pipeline did" and "what is in the catalog". Since `V2.46` made `asset.uuid` the primary
key, the clean fix is `asset_uuid uuid REFERENCES asset (uuid) ON DELETE SET NULL` plus an index —
nullable because the item exists before hashing (§2.2). "Which runs touched this asset?" is a scan
today.

### 6.2 `pipeline_run.pipeline_version int` is not a foreign key — MEDIUM

`pipeline_version` is keyed by `uuid` with `UNIQUE (pipeline_uuid, version_number)`;
`pipeline_run.pipeline_version` stores the bare integer (`V2.29`), so nothing prevents a run
claiming version 7 of a pipeline that has 3. Note `skill.active_version_uuid` → `skill_version.uuid`
is a proper FK — the pipeline side should match.

### 6.4 `leased_by` is a soft reference to `cortex_instance` — MEDIUM

`pipeline_node_task.leased_by varchar` holds a processor `node_id`, which is
`cortex_instance.node_id UNIQUE`. A real FK is available. Without it, "show me every task this dead
worker was holding" cannot be joined reliably and a typo'd worker id is undetectable.

### 6.5 State columns are `varchar` while the rest of the schema uses PG enums — LOW

`pipeline_run.status`, `pipeline_run_item.state`, `pipeline_node_task.state`,
`asset_location.state` and `cortex_instance.state` are unconstrained `varchar` with the legal
values only in a `COMMENT` — `V2.56` added `PAUSED` by *editing a comment*, which makes the point.
Meanwhile `task_status`, `task_priority`, `annotation_type`, `attachment_type`, `dedup_status`
(`V2.61`) and `loom_permission` are proper enums. A typo'd `'COMPLETE'` vs `'COMPLETED'` is
currently a silent no-match. Note `asset_node_result` (`V2.45`) uses `CHECK` constraints — that is
the acceptable minimum.

### 6.6 `cortex_instance_node_kind` permits self-contradiction — LOW

`PRIMARY KEY (instance_uuid, node_kind, list)` lets the same kind appear in both `WHITELIST` and
`BLACKLIST`. NODES.md defines the precedence (blacklist wins), so behaviour is defined — but
`PRIMARY KEY (instance_uuid, node_kind)` with `list` as an attribute makes the contradiction
unrepresentable.

---

## 7. ACL

### 7.1 Permission-table primary keys discard rows — HIGH

Unchanged since `V2.1`:

```
role_permission:  PRIMARY KEY (role_uuid, permission)  + UNIQUE INDEX (role_uuid, resource, permission)
user_permission:  PRIMARY KEY (user_uuid)              + UNIQUE INDEX (user_uuid, resource, permission)
token_permission: PRIMARY KEY (token_uuid)             + UNIQUE INDEX (token_uuid, resource, permission)
```

In all three the PK is narrower than the accompanying unique index, making that index unreachable:

- **`user_permission` / `token_permission`**: exactly **one direct grant per user (or token),
  ever**. A second `INSERT` is a PK violation, not an added permission. The project's own test
  suite works around this by granting via group+role instead (`SkillEndpointTest` pattern).
- **`role_permission`**: a role can grant `READ_ASSET` on **one** resource only; `resource` is
  effectively decorative.

**Recommendation:** promote the unique index to the primary key in all three tables and drop the
narrow one. Small migration, large correctness payoff — this is likely masking bugs today.
See [permissions/PERMISSIONS.md](permissions/PERMISSIONS.md).

- **§7.2 No component-level permissions** — ✅ **RESOLVED** (documented, no enum change):
  components are sub-resources guarded by `READ_ASSET`/`UPDATE_ASSET`; recorded in
  [permissions/PERMISSIONS.md](permissions/PERMISSIONS.md) §2.5.

---

## 8. Cross-cutting

### 8.1 `timestamp` without time zone, everywhere — MEDIUM

Every temporal column is `TIMESTAMP WITHOUT TIME ZONE` with `DEFAULT now()`, including the
migrations added since the audit (`V2.45`, `V2.58`, `V2.61`). For workers in different zones
(`cortex_instance.last_seen`, `pipeline_node_task.lease_expires_at`) lease expiry compares
wall-clock values whose zone is implicit. Use `timestamptz`. Explicitly deferred as out of scope by
[db/DATABASE_TASKS.md](db/DATABASE_TASKS.md); it gets more expensive with every migration.

- **§8.2 Audit columns `NOT NULL` on machine-written rows** — ✅ **RESOLVED**
  (`V2.38`–`V2.43`, `V2.47`): `creator_uuid`/`editor_uuid` are nullable on result tables, with
  `COMMENT`s saying "NULL when written by a Cortex worker rather than a user".

### 8.3 `loom` singleton has no primary key — LOW

`CREATE TABLE "loom" ("db_rev" varchar, "last_used_timestamp" timestamp …)` (`V2.5`) — nothing
stops a second row. `CHECK (id = 1)` on a constant-valued PK column is the usual guard. (`V2.5`
also defined the `loom_events` enum, which `V2.55` dropped along with `webhook`.)

### 8.4 `reaction` uniqueness is asymmetric — LOW

Unique indexes exist for `(creator_uuid, type, asset_uuid)`, `…comment_uuid` and
`…annotation_uuid` — but **not** `…task_uuid`, although `task_uuid` is one of the four targets.
The four target columns are all nullable with nothing enforcing that exactly one is set;
`CHECK (num_nonnulls(asset_uuid, task_uuid, comment_uuid, annotation_uuid) = 1)` would make the
"one of" intent real. Same polymorphic-nullable-FK pattern in `comment` (three targets).

> The `attachment` half of this finding was **WITHDRAWN**. An `EMBEDDING_ATTACHMENT` deliberately
> carries both the embedding it depicts *and* the asset that embedding came from; the fixture and
> the embedding endpoints rely on it. `V2.44` records that in a comment.

---

## 10. Schema with no producer

Found during the `V2.63` re-verification. These objects exist, are indexed and/or constrained, and
are never written outside tests. Each is either a missing implementation or dead weight — decide
which, per row.

| Object | Since | State | Action |
|---|---|---|---|
| `asset_doc_comp` (+ GIN `text_search`) | `V2.38` | DAO exists; OCR/Tika write `asset_json_comp` instead | Point the two nodes at it, or drop (§3.1) |
| `embedding.vector real[]` | `V2.43` | No ANN index, no node producer, effectively zero rows | Blocked on the §4.2 decision |
| `vector_config` | `V2.6` | No DAO, jOOQ-generated code only | Tracked in [../loom/PERSISTENCE_TASKS.md](../tasks/PERSISTENCE_TASKS.md) |
| `asset_remix` | `V2.8` | No DAO, jOOQ-generated code only | Tracked in [../loom/PERSISTENCE_TASKS.md](../tasks/PERSISTENCE_TASKS.md) |
| `asset_user_meta`, `tag_user_meta` | `V2.2`, `V2.8` | No DAO, no hand-written Java | Drop, or spec the feature |
| `annotation_asset` | `V2.16` | Vestigial M:N alongside `annotation.asset_uuid` | Drop (§5.3) |
| `search_document.dirty`, `.es_synced_at`, `search_document_deleted` | `V2.58` | Outbox for an external index; unused by the Postgres provider — the migration says so itself | Keep as documented dead weight until an external indexer exists ([search/SEARCH.md](search/SEARCH.md)) |

Removed since the audit: `webhook` and the `loom_events` enum (`V2.55`), `asset.s3_bucket_name` /
`s3_object_path` (`V2.46`).

---

## 9. Remaining prioritised recommendations

Original items 1–5 (idempotency keys, provenance, `asset_location` key) and 7, 11, 12 are done.
What is left, in order:

1. **Fix the ACL primary keys** (§7.1) — likely masking live bugs; smallest migration with the
   largest payoff.
2. **Finish the asset delete cascades** (§2.6) — four join tables, plus flipping the pinning
   assertions in `AssetCascadeTest`.
3. **Fix `tag_asset`'s primary key** (§5.1) and fold its geometry onto the normalized convention
   (§5.2).
4. **Give `pipeline_run_item` an `asset_uuid` FK** (§6.1); make `pipeline_run.pipeline_version` and
   `pipeline_node_task.leased_by` real FKs (§6.2, §6.4).
5. **Widen `filekey_*` to `bigint`** (§2.4) — silent data corruption today.
6. **Add the dispatch index** (§3.7) and **`CHECK` constraints or enums on the state columns**
   (§6.5, §6.6).
7. **Decide vector storage** (§4.2) — pgvector vs. external via `vector_config`; blocks
   [search/SEMANTIC_SEARCH.md](search/SEMANTIC_SEARCH.md).
8. **Decide the fate of every row in §10** — especially `asset_doc_comp`.
9. **Ledger retention/partitioning plan** (§3.6) and **`timestamptz`** (§8.1) — both grow more
   expensive with every migration.
10. **Model content mutation** (§2.2) — `superseded_by_uuid` or an explicit "the old asset is
    abandoned" policy.

---

## Conventions and Gotchas

- **Section numbers are an API.** `§2.3`, `§3.4`, `§4.2`, `§7.1`, `§8.4` and others are cited from
  [db/DATABASE_TASKS.md](db/DATABASE_TASKS.md), [search/SEARCH.md](search/SEARCH.md),
  [search/SEMANTIC_SEARCH.md](search/SEMANTIC_SEARCH.md), [../loom/DOMAIN.md](../loom/DOMAIN.md),
  [../cortex/METALOOM_ARCHITECTURE_TASK.md](../tasks/METALOOM_ARCHITECTURE_TASK.md) **and from
  SQL migration comments**. Never renumber; mark resolved in place. §9 stays last-but-one by
  convention even though §10 was appended after it.
- **The migrations are the source of truth, not the diagram.** `loom/design/DB/dbdiagram.yaml` lags.
  Verify against `loom/db/flyway/src/main/resources/db/migration/*.sql`, which runs to `V2.63`.
- **Two ledgers, deliberately.** `pipeline_node_task` is per *run item* (execution state, pruned
  with the run, keyed `(item_uuid, node_id)`); `asset_node_result` is per *asset* (catalog state,
  outlives every run, keyed `(asset_uuid, node_kind, node_id)`). Do not merge them.
- **`result_ref` is advisory.** `asset_node_result.result_ref` is a jsonb pointer, not a FK. Do not
  build integrity on it.
- **After any migration change**, re-provision the test pool — install `loom/db/flyway` first, then
  `./setup-pool.sh`, or the pooled DBs silently keep the old schema. After schema changes also run
  `loom/db/jooq/generate.sh`.
- **`AssetCascadeTest` pins current behaviour, including the broken parts.** Its
  `*BlocksAssetDelete` tests assert that the delete *fails*. Fixing §2.6 requires editing them.
- **Nullable audit columns are intentional** on machine-written tables (`V2.47`). Do not
  "fix" them back to `NOT NULL`.
- **`source varchar` is gone.** Anything still referring to a component `source` column predates
  `V2.38`; the discriminators are `node_kind` / `node_id` / `producer_version`.

## Where do I find …?

| Concept | Location |
|---|---|
| The schema itself (authoritative) | `loom/db/flyway/src/main/resources/db/migration/*.sql` |
| Executed component/result rework | [db/DATABASE_TASKS.md](db/DATABASE_TASKS.md) |
| Entity inventory & domain model | [../loom/DOMAIN.md](../loom/DOMAIN.md) |
| Open DAO / DAO-test gaps | [../loom/PERSISTENCE_TASKS.md](../tasks/PERSISTENCE_TASKS.md) |
| Persistence layer design | [../loom/PERSISTENCE.md](../loom/PERSISTENCE.md) |
| Node catalogue and outputs | [pipeline-nodes/NODES.md](nodes/NODES.md) |
| Permission model incl. components | [permissions/PERMISSIONS.md](permissions/PERMISSIONS.md) |
| Search schema + the external-index outbox | [search/SEARCH.md](search/SEARCH.md), `V2.58`–`V2.59` |
| The embedding storage open decision | [search/SEMANTIC_SEARCH.md](search/SEMANTIC_SEARCH.md) §1.3, `V2.43` col. comment |
| Component DAO + upsert | `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/asset/comp/AssetComponentDaoImpl.java` |
| Cascade behaviour tests | `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/AssetCascadeTest.java` |
| Component key tests | `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/AssetComponentKeyTest.java` |
| Node → Loom write-back template | `cortex/common/src/main/java/io/metaloom/cortex/common/node/AbstractMediaNode.java` |
| jOOQ regeneration | `loom/db/jooq/generate.sh` |
| Test pool provisioning | `./setup-pool.sh` |

## Progress Assessment

### Resolved (no action)

- [x] §2.1 asset identity — `uuid` PK, `sha512sum NOT NULL UNIQUE` (`V2.46`)
- [x] §2.3 `asset_location` natural key (`V2.48`, `V2.63`)
- [x] §2.5 dead S3 columns dropped (`V2.46`)
- [x] §2.6 asset delete cascades — `tag_asset` (`V2.72`), `collection_asset` / `asset_task` / `asset_user_meta` (`V2.73`), `comment` / `reaction` / `library_asset` (`V2.74`); `AssetCascadeTest` rewritten from block-pins to survival assertions over a shared two-asset fixture
- [x] §5.1 surrogate PK for `tag_asset` (`V2.71`)
- [x] §3.1 node results reach the catalog — ~15 writers (one gap left, `asset_doc_comp`)
- [x] §3.2 component idempotency keys (`V2.38`–`V2.42`)
- [x] §3.3 `source` split into `node_kind`/`node_id`/`producer_version` (`V2.38`–`V2.42`)
- [x] §3.4 provenance + `asset_node_result` ledger (`V2.38`–`V2.45`)
- [x] §3.5 transport-vs-catalog contract written down
- [x] §3.8 component coverage — fingerprint, segment, attachment provenance, json GIN
- [x] §4.1 `embedding.detection_uuid` + one geometry convention (`V2.43`)
- [x] §4.4 detection idempotency key (`V2.43`)
- [x] §6.3 version pointer delete behaviour (`V2.49`)
- [x] §7.2 component permission model documented
- [x] §8.2 nullable audit columns on machine-written rows (`V2.47`)
- [x] §8.4 `attachment` "exactly one target" CHECK — **withdrawn**, documented in `V2.44`

### Open — correctness

- [ ] §7.1 promote the unique index to PK on `role_permission`, `user_permission`, `token_permission`
- [ ] §2.4 widen `filekey_*` to `bigint`
- [ ] §8.4 `reaction` unique index on `task_uuid` + `num_nonnulls` CHECK
- [ ] §4.3 `cluster` name uniqueness → `(type, name)`

### Open — integrity / typing

- [ ] §6.1 `pipeline_run_item.asset_uuid` FK + index
- [ ] §6.2 `pipeline_run.pipeline_version_uuid` FK
- [ ] §6.4 `pipeline_node_task.leased_by` → `cortex_instance` FK
- [ ] §6.5 enums or CHECKs on the varchar state columns
- [ ] §6.6 `cortex_instance_node_kind` PK excludes `list`
- [ ] §3.7 dispatch index `(node_kind) WHERE state = 'PENDING'`

### Open — decisions

- [ ] §4.2 vector storage: pgvector in Postgres vs. external via `vector_config`
- [ ] §3.6 ledger retention / partitioning plan
- [ ] §8.1 migrate to `timestamptz`
- [ ] §2.2 content-mutation model (`superseded_by_uuid` or explicit policy)
- [ ] §10 decide per row: implement the producer, or drop the object
- [ ] §5.2 fold `tag_asset` / `annotation` geometry onto the normalized convention
- [ ] §5.3 drop `annotation_asset`

### Verification / test setup

Findings above were re-verified by reading the migrations directly; no build or test run is
required to re-check them. To re-verify against a live database:

```bash
# install flyway first, or the pooled DBs keep the old schema
mvn -q install -pl loom/db/flyway
./setup-pool.sh

# the tests that exercise the audited constraints
mvn -q test -pl loom/db/jooq -Dtest='AssetCascadeTest,AssetComponentKeyTest,EmbeddingDaoTest'
```
_Git HEAD revision: `27894151`_
_Last updated: 2026-08-09 (three structural findings are now detected at runtime by the checks in
DB_INTEGRITY.md - the two missing editor_uuid foreign keys and vector_config's missing constraints.
The constraints themselves are still unwritten. Earlier: 2026-08-06 reference sweep, no content changes)_