# MetaLoom Pipeline System — Requirements

> **Purpose of this document.** This is a plain-language guide to *what the
> MetaLoom pipeline system is expected to do* — the ground rules, not the code.
> It is meant to be readable by product owners, testers, and new contributors
> who need to understand the shape of the system before diving into the
> technical specification.
>
> It has three parts:
> 1. A short, non-technical description of the pipeline system.
> 2. The **mandatory ("rooted") requirements** — each checked against what is
>    actually built today, with **every deviation called out explicitly**.
> 3. **Additional requirements**, grouped by theme, with progress checkboxes so
>    the remaining gaps are visible at a glance.
>
> **Companion documents:** [PIPELINE.md](PIPELINE.md) (technical specification
> for AI coding agents) and [PIPELINE_TASKS.md](PIPELINE_TASKS.md) (actionable
> work items derived from the gaps below).
>
> **Status source:** every status in this file was re-verified against the
> code on 2026-07-18. Where a status contradicts an older spec document, the
> code wins.

---

## 1. What the pipeline system is (in plain terms)

MetaLoom manages large collections of media (images, video, audio, documents).
A **pipeline** is a recipe that describes how a batch of media should be
processed: where the media comes from, what should be extracted from it (hashes,
faces, transcripts, thumbnails, …), and which items should be filtered out along
the way.

- A pipeline is drawn as a **graph of nodes**. It always begins with a single
  **start node** that produces media items, and every other node does one small
  job on each item (for example, "compute a SHA-512 hash" or "detect faces").
- Pipelines are **designed in the Loom web UI**, **saved on the Loom server**,
  and **run on Cortex processor machines** — the heavy, CPU/GPU-intensive work
  happens on Cortex, away from the central server.
- Every save produces a **new immutable version** of the pipeline, so a user can
  review the history and restore an earlier revision.
- While a pipeline runs, Cortex **reports progress back to Loom**, which
  **forwards live updates to the UI** so a user can watch the processing happen.
- Results worth keeping are **sent back to Loom** and attached to the assets.

The two halves of the system:

| Side | Role |
|------|------|
| **Cortex** | Executes pipelines, runs the nodes, streams progress + results back |
| **Loom** | Stores pipeline definitions and versions, tracks runs, relays live events to the UI, persists results |

---

## 2. Mandatory ("rooted") requirements

These are the non-negotiable requirements for the pipeline system. Each row
states the requirement, whether the current implementation **meets** it,
and — as required — **any deviation is pointed out explicitly**.

Legend: ✅ Met · 🟡 Partially met (deviation noted) · 🔴 Not met / significant deviation

| # | Requirement | Status | Notes & deviations |
|---|-------------|--------|--------------------|
| R1 | Pipelines are executed on the Cortex processor instances | ✅ | `ReactivePipelineExecutor` runs inside Cortex; Loom only dispatches work orders. No deviation. |
| R2 | Pipelines send their status / updates via API calls back to the Loom backend | 🟡 | **Deviation:** live status is *not* sent as discrete REST calls. It travels over the **processor WebSocket** (`/api/v1/processors/ws`) as `PIPELINE_EVENT` / `PIPELINE_RUN_COMPLETED` messages. Only bulk **result data** uses REST (`bulkUpdateAssets`). Met if "API calls" means "reported back to Loom over its network interface"; a deviation if REST is meant strictly. |
| R3 | Pipelines can be constructed in the Loom UI and persisted on the Loom backend server | 🟡 | Authoring and persistence work (`PipelineEditor.tsx` + REST CRUD + `pipeline`/`pipeline_version` tables). **Deviation:** what is persisted cannot be executed. Loom stores the graph as `nodes[]` + `edges[]`; the Cortex loader reads `nodes[].dependencies[]` and ignores `edges` entirely, so a UI-authored pipeline loads on Cortex as disconnected nodes and collapses to just its source node. The round trip is broken. |
| R4 | Pipelines always have a start node which emits assets | ✅ | Exactly one source node is enforced by `DefaultPipeline`; `AssetSourceNode` emits the asset(s). No deviation. |
| R5 | Additional nodes (facedetect, sha512, …) extract further metadata from assets | ✅ | ~18 processing nodes exist (hash family, facedetect, whisper, tika, OCR, quality, …). No deviation. |
| R6 | Pipeline execution results are tracked on the Loom server | 🟡 | **Run-level tracking now works** (implemented 2026-07-18): runs transition to `SUCCESS`/`PARTIAL`/`FAILED` with real durations and all four counters populated, and an unacknowledged work order is failed by a dispatch watchdog rather than stranding at `RUNNING`. **Remaining deviation:** tracking is run-level only — there is still **no per-node result or stats table**, so node-by-node execution results are not tracked on the server. |
| R7 | Special pipeline nodes allow filtering of asset results (DateFilter, …) | ✅ | 8 concrete filter nodes implemented (`AssetAttributeFilterNode`, `BlacklistFilterNode`, `DateFilterNode`, `DuplicateFilterNode`, `MimeTypeFilterNode`, `QualityFilterNode`, `SamplingFilterNode`, `ThresholdFilterNode`) with PASS/REJECT branching. *Note:* older specs also listed a `SizeFilterNode` — **it does not exist**. *Minor note:* the older `PipelineFilter`/`MediaFilter` SPI is orphaned — cleanup pending, not a functional deviation. |
| R8 | Pipelines are serialized / deserialized via JSON | ✅ | `PipelineSerializer` / `PipelineDeserializer`, round-trip guaranteed and tested (`PipelineSerdeRoundTripTest`). No deviation. |
| R9 | Pipeline execution is backpressure-aware and reactive | ✅ | Built on RxJava 3 `Flowable`; `flatMap(fn, maxConcurrentMedia)` bounds in-flight items; per-node semaphores. No deviation. |
| R10 | Intermediate pipeline node results are stored on the Loom backend service | 🔴 | **Deviation:** intermediate results are cached **locally on Cortex** (`NodeCacheProvider`: heap / xattr / sidecar). On Loom, only nodes flagged `syncToLoom=true` are persisted, and only as **asset metadata** via bulk update — not as dedicated intermediate node-result records. There is no `pipeline_node_result` store. |
| R11 | Pipelines can be validated using a validation endpoint in the Loom REST API | 🔴 | **Deviation:** there is **no dedicated validation endpoint** (e.g. `POST /api/v1/pipelines/validate`). Validation runs inline during create and update only, so a client cannot validate a draft without persisting it. The checks themselves are thorough and exist in **three** independent copies (see A-VA2). |
| R12 | WebSocket events must be emitted so the Loom UI can visualize processing / status | ✅ | Tracking events → processor WS → `PipelineEventBroadcaster` → UI WS (`/api/v1/pipelines/events/ws`); the UI renders live node status. Per-pipeline filtering and drop-on-full backpressure are both implemented. No deviation. |

### Summary of deviations (must-read)

- **R3** — 🔴 **The authored pipeline is not the executed pipeline.** Loom and
  Cortex use incompatible definition schemas (`edges[]` vs `dependencies[]`).
  This is the single most damaging defect in the feature and defeats R1, R5,
  and R7 in practice: a pipeline drawn in the UI runs as a one-node pipeline on
  Cortex. See [PIPELINE_TASKS.md](PIPELINE_TASKS.md) Task 1.
- **R2** — Status is delivered over a **WebSocket**, not via REST calls; only
  result data uses REST.
- **R6** — Run tracking is now complete at the **run level**, but there is still
  no **per-node** tracking table.
- **R10** — Intermediate results live in **Cortex-local caches**; on Loom only
  `syncToLoom` outputs are stored, and only as **asset metadata**.
- **R11** — **No dedicated validation endpoint**; validation happens only as a
  side effect of create/update.

---

## 3. Additional requirements & progress

Beyond the mandatory rules, the pipeline system needs the following to be
production-grade.

Legend: `[x]` done · `[~]` partial · `[ ]` pending

### 3.1 Error handling

- [x] A failing node is caught and turned into a `FAILED` result instead of crashing the run.
- [x] A failed **blocking** dependency causes downstream nodes to be skipped (`"Dependency <id> failed"`).
- [x] **Per-node execution timeout** — `PipelineNode.timeoutMs()` is honoured by the executor; hung nodes fail instead of holding a semaphore forever.
- [ ] **A-EH1 — Retry mechanism.** `retryFailed` is advertised as a node parameter by 10 descriptor providers but is never read by the executor. Either implement it or remove it from the descriptors.
- [x] **A-EH2 — Work-order result routing.** The run path registers a callback with `WorkOrderResultRegistry`, including a 60 s dispatch watchdog that fails the run when no processor acknowledges the work order.

### 3.2 Persistence

- [x] Pipeline **definitions** persisted (`pipeline` + `pipeline_version`, JSONB definition).
- [x] Pipeline **versioning** — every update creates a new immutable `pipeline_version`; restore is copy-forward.
- [x] Pipeline **run history** rows created (`pipeline_run` table, `GET /api/v1/pipelines/:uuid/runs`).
- [x] **A-PE1 — Run completion.** Runs transition to `SUCCESS`/`PARTIAL`/`FAILED` with `finished`, `duration_ms` and all four counters written. The first terminal verdict wins, so a late watchdog cannot overwrite a real result.
- [ ] **A-PE2 — Run status enum.** `status` is a free-form `String` end-to-end; the vocabulary exists only as a SQL comment.
- [ ] **A-PE3 — Per-node stats/results table** (`pipeline_node_stats`) so `NODE_STATS` events have somewhere to land.
- [ ] **A-PE4 — Dedicated store for intermediate node results** on Loom. (**relates to R10**)
- [ ] **A-PE5 — In-memory DB backend has no pipeline DAOs**, so `loom/db/memory` cannot serve pipelines at all.
- [x] Demo seeding of a default pipeline definition (`DemoDatabaseInitializer` seeds a `filesystem-source → filter-mimetype → sha256` graph).

### 3.2b Definition interchange (**new — see R3**)

- [ ] **A-DI1 — Unify the definition schema.** Loom writes `edges[]`; the Cortex
      loader reads `dependencies[]`. One side must change, and a shared
      reference fixture must be checked in. There is currently no pipeline
      definition JSON resource anywhere in the repo.
- [ ] **A-DI2 — Filter branches are not expressible from Loom.** The Loom format
      has no `conditionalDependencies`, so PASS/REJECT branching — the whole
      point of R7 — cannot be authored in the UI.
- [ ] **A-DI3 — Loader has no test.** A single round-trip test over a real
      Loom-authored definition would have caught A-DI1 and A-DI2.

### 3.3 Execution

- [x] Reactive, backpressure-aware execution (RxJava 3).
- [x] Per-node concurrency via semaphores; media-level concurrency via `maxConcurrentMedia`.
- [x] Configurable `maxConcurrentMedia` via `CortexOptions` (default 4), injected in `CortexBindModule`.
- [x] Dry-run mode (all nodes skipped, no side effects).
- [x] On-demand run trigger (`POST /api/v1/pipelines/:uuid/run` → work order → Cortex).
- [~] **A-EX1 — Media selection for a run** resolves `pathGlobs` only; UUID-based selection logs a warning and is skipped, even though the UI and DTO both offer it.
- [ ] **A-EX2 — Processor capability selection** is hardcoded to `CPU`; a GPU-only pipeline cannot request a GPU processor.
- [ ] **A-EX3 — Executor instances are single-use.** A second `execute()` on the same (Dagger-singleton) executor throws `RejectedExecutionException` because the stats scheduler is shut down after the first run.
- [ ] **A-EX4 — `node.shutdown()` is never called**, so nodes holding native resources leak.
- [ ] **A-EX5 — Per-node throttling is a blocking semaphore**, not reactive backpressure; a saturated node parks `Schedulers.io()` threads. Timeouts are applied outside the permit, so a hung node still starves its peers.
- [ ] **A-EX6 — Virtual-thread scheduler** option for I/O-bound nodes (whisper, OCR, LLM, facedetect).

### 3.4 Custom nodes

- [x] JSON node definitions resolve to real Cortex nodes via `RegistryNodeFactory` + `CortexNodeAdapter`.
- [x] Node descriptors published to the UI (`NodeDescriptorRegistry`) for the palette and parameter editor.
- [x] Reference examples for adding custom nodes (`examples/cortex-custom-node`, `examples/cortex-custom-cli`).
- [x] Node option validation at config-load and pipeline-creation time.
- [~] **A-CN1 — Node type coverage.** The descriptor registry advertises **29 kinds** to the UI palette; only **5** are registered with the executable factory (`sha512`, `sha256`, `md5`, `chunk-hash`, `thumbnail`). The other 24 — including `whisper`, `ocr`, `llm`, `facedetect`, `tika`, and every `filter-*` — are selectable in the editor but silently fall back to no-op stubs that *report success*. A user can build a pipeline entirely out of nodes that do nothing and see a green run.
- [ ] **A-CN2 — Node versioning** — no way to invalidate cached results when a node's algorithm changes.
- [ ] **A-CN3 — Result caching is not wired in production.** No code calls `setCacheProvider`, and there is no Dagger provider for any `NodeCacheProvider`, so caching is test-only. Both persistent caches also stringify all values, so a cached `boolean` returns as a `String`.

### 3.5 Event handling

- [x] Dual-channel event bus (full-fidelity node completion + lightweight tracking).
- [x] Tracking events forwarded Cortex → Loom → UI over WebSockets (**satisfies R12**).
- [x] WebSocket authentication via `?token=<jwt>` (opt-in strict mode).
- [x] `NODE_STATS` events emitted periodically (500 ms) from the executor.
- [x] Per-pipeline event filtering (`?pipeline=<name>` on the events WS).
- [x] Broadcaster backpressure — messages are dropped when the socket write queue is full, with a drop counter.
- [ ] **A-EV1 — Persist `NODE_STATS`** snapshots + a query API. (**relates to A-PE3**)
- [ ] **A-EV2 — Descriptor/event alignment.** All 14 descriptor providers advertise `NODE_STATS`, but stats are emitted generically by the executor, not per node — the advertisement is misleading.

### 3.6 Validation (cross-cutting — see R11)

- [x] Client-side validation before save (id format, duplicate ids, cycles, unknown node types).
- [x] Server-side validation on create/update (`PipelineValidationService`, same checks plus descriptor-registry lookup).
- [ ] **A-VA1 — Standalone validation endpoint** so drafts can be validated without persisting. (**closes R11**)
- [ ] **A-VA2 — Validation logic is triplicated** across `PipelineModelValidator` (loom-shared), `PipelineValidationService` (loom rest), and `validatePipeline()` in `PipelineEditor.tsx`. Only the middle one checks node types. These will drift.

### 3.7 API surface & clients

- [x] Full REST surface for CRUD, run, run history, versions, and version restore.
- [ ] **A-AP1 — Java REST client is incomplete.** `PipelineMethods` lacks `run`, `listVersions`, `loadVersion`, and `restoreVersion`, which is why no Java test covers the versioning API.
- [ ] **A-AP2 — No pipeline gRPC surface.** Despite the gRPC service being wired and running, there is no `pipeline.proto`.
- [ ] **A-AP3 — UI error masking.** `listPipelineVersions` and `listPipelineRuns` return `[]` on *any* non-OK response, so a server failure is indistinguishable from "no data".

---

## 4. Where to go next

| Topic | Document |
|-------|----------|
| Technical specification (architecture, classes, execution model, testing) | [PIPELINE.md](PIPELINE.md) |
| Actionable work items derived from the gaps above | [PIPELINE_TASKS.md](PIPELINE_TASKS.md) |
| Cortex node lifecycle, MetaStorage, per-node reference | [../pipeline-nodes/NODES.md](../pipeline-nodes/NODES.md) |
| Loom architecture, REST, persistence, WebSocket | [../../loom/](../../loom/) |
| Cortex architecture, configuration, build | [../../cortex/](../../cortex/) |
