# NOTES — scratch backlog

Scratch pad for raw ideas and open questions that do **not yet have a spec file of their own**; once
an item grows teeth it moves into a real spec under `spec/features/…` or into a sibling task file in
this directory ([WORKFLOW_TASKS.md](WORKFLOW_TASKS.md), [PIPELINE_TASKS.md](PIPELINE_TASKS.md),
[DATABASE_TASKS.md](DATABASE_TASKS.md), [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md), …) and is deleted here.
This file tracks no progress — the linked specs do.

> **Workflows have graduated out of this file.** The twelve workflow specs live in
> [../workflows/](../workflows/) (start at [WORKFLOWS.md](../workflows/WORKFLOWS.md)) and their
> actionable work items in [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md). Do not add workflow items here.
> W1–W4 (`FilterBy.TAG`/`RATING`, the `move` node, review state on `detection`, the review record on
> the `asset_node_result` ledger) are done as of 2026-08-08.

---



## Tasks
* Custom 404 page loom

* Add coachmarks in certain features that help the user find the right documentation.Either have fixed links to metaloom.io (which could break) - or add a redirection page on the website which will automatically redirect the user to a page which matches semantic search query on the website semantic search index.


* **Video Manipulation Node** — the video half of
  [../features/nodes/image-manipulation/NODE_IMAGE_MANIPULATION.md](../features/nodes/image-manipulation/NODE_IMAGE_MANIPULATION.md):
  autorotate by container rotation side-data, crop, aspect ratio fix, VVS (blurred pad for vertical
  video). Should reuse that node's `ManipulationGeometry` and `watermark`'s `FfmpegRunner`; would
  also close watermark's open "rotation/SAR is not handled" item. *(The image half is specified and
  built; the plan moved out of `concept/`.)*
* Add a semantic ingestion node — lets constructed semantic data be ingested into Loom.
* Chapter extraction from video. Storage is already solved: `asset_segment_comp` (`V2.42`, see
  [DATABASE_TASKS.md](DATABASE_TASKS.md) task 5) keys chapters alongside scenes/shots/silence — but
  no node under `cortex/nodes/` produces them, and loom-ui would need a chapter strip on the asset
  detail view.
* Content-classification node (topic / zero-shot). Partly answered: `sentiment` already ships
  per-language transformer checkpoints (`SentimentNodeOptions.modelDe`/`modelEn`) and `guard`/`llm`
  cover safety and free-form verdicts — so the gap is a general-purpose classifier, not "BERT
  support" as such.
* Saliency-based focal point. The detection-driven half now has a home: `SUBJECT_CROP` frames
  upstream `detection/*` boxes, and an open item in
  [NODE_IMAGE_MANIPULATION.md](../features/nodes/image-manipulation/NODE_IMAGE_MANIPULATION.md)
  proposes emitting the subject centroid as a `focalPoint` output. Still homeless: saliency
  **without** detections.
* AI-aware agentic sync client that automatically mirrors the assets relevant to a user onto their
  machine (à la lucidlink) — how would that work for loom-app?

## Open ideas / questions

* **Binary delivery to the frontend.** How do asset bytes and derivatives reach a browser at scale?
  `Range` / 206 / 416 / `Accept-Ranges` landed on `/assets/:uuid/binary/data`, but
  [features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md) still records
  "Presigned URLs — not built: every S3 download is proxied through Loom, a hop per thumbnail", and
  nothing specifies a CDN in front of the pool, pre-encoded renditions, or HLS/DASH. Attachment
  downloads (`/attachments/:uuid/data`) still ignore `Range` entirely.

* **Complete the node provenance record.** The `asset_node_result` ledger already carries
  `node_kind` + `node_id` + `producer_version`, which identifies *which node kind* wrote a value but
  not *which worker build*. The schema is not the problem — `V2.45__add_asset_node_result.sql`
  already has `run_uuid`, `task_uuid` and a nullable `origin` with a
  `NULL|COMPUTED|LOCAL|REMOTE` CHECK. The write path is:
  - `NodeResultCreateRequest` has only 8 fields and none of them are `runUuid` / `taskUuid` /
    `cortexInstance` / `started` / `finished`, so the REST model cannot carry them even though
    `NodeResultEndpointService` passes `origin` through faithfully;
  - `origin` is hard-coded at both writers — `AbstractMediaNode.recordNodeResult` sets
    `ResultOrigin.COMPUTED` and ignores `ctx.resultOrigin()`, and `AdhocNodeResultWriter` uses a
    constant — so a `LOCAL` cache hit is indistinguishable from real work;
  - the `cortex_instance` table (`V2.33`) exists but is written only by worker registration and is
    never referenced from the node-result path; `V2.66` even notes that `asset_node_result.node_id`
    and `cortex_instance.node_id` mean different things, so there is no implicit join key either.
  Consequence: faulty data cannot be traced back to a worker. Blocks the review record in
  [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) and is cross-referenced from
  [WORKFLOW_AI_REVIEW.md](../workflows/WORKFLOW_AI_REVIEW.md). Line-level gaps are pinned in
  [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md). loom-ui would gain
  a provenance row on `NodeResultDetail.tsx`, which today shows only the element seq.


## Unowned defects (verified 2026-08-11)

Real code/spec disagreements found during the audit that no task file owns.

* **`ctx.failure(cause).next()` reports SUCCESS.** `NodeContextImpl.next()` reads only `skipReason`;
  `failureCause` is read by `abort()` alone, so `failure(...).next()` returns
  `ResultState.SUCCESS` with a `null` message and the diagnosis is dropped on the floor. ~20 call
  sites under `cortex/` do exactly that. Fix is a choice: have `next()` honour `failureCause`, or
  make `failure()` before `next()` illegal.

* **Dedup: `PATCH keepAssetUuid` never rewrites `dedup_group_member.role`.**
  `DedupGroupDaoImpl.updateStatus` updates `dedup_group` only; `role` is written solely by
  `addMember`. Readers were taught to prefer the `keepAssetUuid` pointer and `DedupGroup`'s javadoc
  was corrected, but two "the DAO keeps them consistent" claims survive and are now wrong: the
  comment in `V2.61__add_dedup_group.sql`, and
  [NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) §4 — which
  contradicts its own open item further down the same file. No test asserts member roles *after* a
  reassignment (`DedupGroupEndpointTest` and `DedupGroupDaoTest` both re-send the asset that is
  already KEEP). Recorded as "still open" under [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 3, but
  that task is ✅ DONE, so nothing live owns it. Server-side role rewrite is the actual fix.

* **The demo seeding path has no test anywhere in the repo.** `BootstrapInitializer` catches and
  logs (`"Error while populating demo data — continuing startup"`) where the migration and
  `DatabaseInitializer` blocks above it rethrow, so anything thrown inside
  `DemoDatabaseInitializer.init()` — `seedDemoDedupGroup` included — vanishes silently and the demo
  data is simply absent. Nothing invokes `init()` from a test; `DemoPipelineDefinitionTest` only
  exercises the static pipeline-definition strings.

* **A dead sam2 option still busts its cache.** `stabilityScoreThresh` is ignored by the sidecar
  (documented in [NODE_SAM2.md](../features/nodes/sam2/NODE_SAM2.md) §6.1) yet is part of the sam2
  cache-key digest in [NODES.md](../features/nodes/NODES.md), and the digest names the artifact
  directory — so nudging a knob that does nothing forces a full re-segment. The website options
  table (`website/content/english/docs/nodes/sam2/index.adoc`) omits the option entirely while the
  served descriptor advertises it.

---
_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (code audit)_
