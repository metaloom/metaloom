# MetaLoom Pipeline System — Requirements

> **Purpose of this document.** This is a plain-language guide to *what the
> MetaLoom pipeline system is expected to do* — the ground rules, not the code.
> It is meant to be readable by product owners, testers, and new contributors
> who need to understand the shape of the system before diving into the
> technical specifications.
>
> It has three parts:
> 1. A short, non-technical description of the pipeline system.
> 2. The **mandatory ("rooted") requirements** — each checked against what is
>    actually built today, with **every deviation called out explicitly**.
> 3. **Additional requirements**, grouped by theme, with progress checkboxes so
>    the remaining gaps are visible at a glance.
>
> **Sources analysed:** [cortex/PIPELINE.md](cortex/PIPELINE.md),
> [loom/PIPELINE.md](loom/PIPELINE.md), [common/LOOM_PIPELINE.md](common/LOOM_PIPELINE.md),
> [cortex/NODES.md](cortex/NODES.md), and the code these describe.

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
- While a pipeline runs, Cortex **reports progress back to Loom**, which
  **forwards live updates to the UI** so a user can watch the processing happen.
- Results worth keeping are **sent back to Loom** and attached to the assets.

The two halves of the system:

| Side | Role |
|------|------|
| **Cortex** | Executes pipelines, runs the nodes, streams progress + results back |
| **Loom** | Stores pipeline definitions, tracks runs, relays live events to the UI, persists results |

---

## 2. Mandatory ("rooted") requirements

These are the non-negotiable requirements for the pipeline system. Each row
states the requirement, whether the current implementation/spec **meets** it,
and — as required — **any deviation is pointed out explicitly**.

Legend: ✅ Met · 🟡 Partially met (deviation noted) · 🔴 Not met / significant deviation

| # | Requirement | Status | Notes & deviations |
|---|-------------|--------|--------------------|
| R1 | Pipelines are executed on the Cortex processor instances | ✅ | `ReactivePipelineExecutor` runs inside Cortex; Loom only dispatches work orders. No deviation. |
| R2 | Pipelines send their status / updates via API calls back to the Loom backend | 🟡 | **Deviation:** live status is *not* sent as discrete REST API calls. It travels over the **processor WebSocket** (`/api/v1/processors/ws`) as `PIPELINE_EVENT` / `PIPELINE_RUN_COMPLETED` messages. Only bulk **result data** uses the REST API (`bulkUpdateAssets`). If "API calls" is meant strictly as REST, this is a deviation; if it means "reported back to Loom over its network interface", it is met. |
| R3 | Pipelines can be constructed in the Loom UI and persisted on the Loom backend server | ✅ | UI editor (`PipelineEditor.tsx`) + REST CRUD (`/api/v1/pipelines`) + `pipeline` table (Flyway `V2.19`). No deviation. |
| R4 | Pipelines always have a start node which emits assets | ✅ | Exactly one source node is enforced by `DefaultPipeline` (builder + validation); `AssetSourceNode` emits the asset(s). No deviation. |
| R5 | Additional nodes (facedetect, sha512, …) extract further metadata from assets | ✅ | ~18 processing nodes exist (hash family, facedetect, whisper, tika, OCR, quality, …). No deviation. |
| R6 | Pipeline execution results are tracked on the Loom server | 🟡 | **Deviation:** only **run-level** tracking exists — the `pipeline_run` table (Flyway `V2.29`: status, media/success/failure/skipped counts, duration, error). There is **no per-node result/stats table** (`pipeline_node_stats` is still pending), so node-by-node execution results are not tracked on the server. |
| R7 | Special pipeline nodes allow filtering of asset results (DateFilter, …) | ✅ | 10 filter nodes implemented (`DateFilterNode`, `MimeTypeFilterNode`, `SizeFilterNode`, `BlacklistFilterNode`, …) with PASS/REJECT branching. *Minor note:* the older `PipelineFilter`/`MediaFilter` SPI is orphaned (unused) — cleanup pending, not a functional deviation. |
| R8 | Pipelines are serialized / deserialized via JSON | ✅ | `PipelineSerializer` / `PipelineDeserializer`, round-trip guaranteed and tested (`PipelineSerdeRoundTripTest`). No deviation. |
| R9 | Pipeline execution is backpressure-aware and reactive | ✅ | Built on RxJava 3 `Flowable`; `flatMap(fn, maxConcurrentMedia)` bounds in-flight items; per-node semaphores. *Note:* the **event broadcaster** (a separate concern) has no backpressure — see A-EV3. |
| R10 | Intermediate pipeline node results are stored on the Loom backend service | 🔴 | **Deviation:** intermediate results are cached **locally on Cortex** (`NodeCacheProvider`: heap / xattr / sidecar). On the **Loom backend**, only nodes flagged `syncToLoom=true` are persisted, and only as **asset metadata** via bulk update — not as dedicated intermediate node-result records. There is no `pipeline_node_result` store. The requirement is only partially satisfied. |
| R11 | Pipelines can be validated using a validation endpoint in the Loom REST API | 🔴 | **Deviation:** there is **no dedicated validation endpoint** (e.g. `POST /api/v1/pipelines/validate`). Validation (`PipelineValidationService.validateDefinition`) runs **inline during create and update only**. A client cannot validate a draft without attempting to persist it. Both server-side (`PipelineValidationService`) and client-side (`validatePipeline()`) checks exist, but not as a standalone endpoint. |
| R12 | WebSocket events must be emitted so the Loom UI can visualize processing / status | ✅ | Tracking events → processor WS → `PipelineEventBroadcaster` → UI WS (`/api/v1/pipelines/events/ws`); the UI renders live node status. *Gaps (not deviations):* no per-pipeline event filtering (A-EV2), no broadcaster backpressure (A-EV3). |

### Summary of deviations (must-read)

- **R2** — Status is delivered over a **WebSocket**, not via REST API calls; only result data uses REST.
- **R6** — Only **run-level** results are tracked on Loom; **no per-node** tracking table.
- **R10** — Intermediate results live in **Cortex-local caches**; on Loom only `syncToLoom` outputs are stored, and only as **asset metadata**.
- **R11** — **No dedicated validation endpoint**; validation happens only as a side effect of create/update.

---

## 3. Additional requirements & progress

Beyond the mandatory rules, the pipeline system needs the following to be
production-grade. Checkboxes reflect current status per the specs above.

Legend: `[x]` done · `[~]` partial · `[ ]` pending

### 3.1 Error handling

- [x] A failing node is caught and turned into a `FAILED` result instead of crashing the run.
- [x] A failed **blocking** dependency causes downstream nodes to be skipped (`"Dependency <id> failed"`).
- [x] Blocking scene-detection error handling fixed (no more `printStackTrace` + `System.in.read()` hang).
- [x] **Per-node execution timeout** — implemented with configurable timeout per node (via `timeoutMs` property), default timeouts from Cortex config, and proper timeout handling in the executor.
- [ ] **Retry mechanism** — the `retryFailed` option exists but is never honoured by the executor.
- [ ] **Work-order result routing** — `ProcessorEndpoint.handleWorkOrderResult` is a TODO dead-end; run failures are not surfaced back to the caller.

### 3.2 Persistence

- [x] Pipeline **definitions** persisted (`pipeline` table, JSONB definition).
- [x] Pipeline **run history** persisted (`pipeline_run` table, `GET /api/v1/pipelines/:uuid/runs`).
- [~] Server-side validation of the definition (structure + node types checked; **no JSONB schema** validation).
- [ ] **Per-node stats/results table** (`pipeline_node_stats`) so `NODE_STATS` events have somewhere to land (**relates to R6**).
- [ ] **Dedicated store for intermediate node results** on Loom (**relates to R10**).
- [ ] Demo seeding of a default pipeline definition (only permissions are seeded today).
- [ ] Alternative persistence backend (`PipelineHibernateDao`) — only if the project keeps a Hibernate backend.

### 3.3 Execution

- [x] Reactive, backpressure-aware execution (RxJava 3).
- [x] Per-node concurrency via semaphores; media-level concurrency via `maxConcurrentMedia`.
- [x] Dry-run mode (all nodes skipped, no side effects).
- [x] On-demand run trigger (`POST /api/v1/pipelines/:uuid/run` → work order → Cortex).
- [~] Media selection for a run — resolved from `pathGlobs` only; **UUID-based** selection still logs a warning and is skipped.
- [ ] Configurable `maxConcurrentMedia` (currently hard-coded to `4` in `CortexBindModule`).
- [ ] Virtual-thread scheduler option for I/O-bound nodes (whisper, OCR, LLM, facedetect).

### 3.4 Custom nodes

- [x] JSON node definitions resolve to real Cortex nodes via `RegistryNodeFactory` + `CortexNodeAdapter`.
- [x] Node descriptors published to the UI (`NodeDescriptorRegistry`) for the palette and parameter editor.
- [x] Reference examples for adding custom nodes (`examples/cortex-custom-node`, `examples/cortex-custom-cli`).
- [~] Only **5** node types are registered with the factory today (hash family + thumbnail); the remaining ~10 legacy nodes must be added as they are exercised.
- [ ] **Node option validation** — invalid configs (negative concurrency, empty model paths) are caught only at runtime.
- [ ] **Node versioning** — no way to invalidate cached results when a node's algorithm changes.

### 3.5 Event handling

- [x] Dual-channel event bus (full-fidelity node completion + lightweight tracking).
- [x] Tracking events forwarded Cortex → Loom → UI over WebSockets (**satisfies R12**).
- [x] WebSocket authentication via `?token=<jwt>` (opt-in strict mode).
- [x] `NODE_STATS` events emitted from the executor (periodic per-node snapshot).
- [ ] **Per-pipeline event filtering** (`?pipeline=<name>`) — the broadcaster currently fans every event out to every subscriber.
- [ ] **Broadcaster backpressure** — a slow subscriber can back up the broadcaster (no bounded per-subscriber queue / drop policy).
- [ ] Persist `NODE_STATS` snapshots + a query API (`.../nodes/:nodeId/stats`).

### 3.6 Validation (cross-cutting — see R11)

- [x] Client-side validation before save (id format, duplicate ids, cycles, unknown node types).
- [x] Server-side validation on create/update (`PipelineValidationService`, same checks + descriptor registry).
- [ ] **Standalone validation endpoint** so drafts can be validated without persisting (**closes R11**).

---

## 4. Where to go next (spec cross-references)

| Topic | Spec |
|-------|------|
| Cortex execution engine, nodes, serde, caching, sync | [cortex/PIPELINE.md](cortex/PIPELINE.md) |
| Loom-side persistence, REST, event bridge | [loom/PIPELINE.md](loom/PIPELINE.md) |
| End-to-end status & detailed progress checklist | [common/LOOM_PIPELINE.md](common/LOOM_PIPELINE.md) |
| Node lifecycle, per-node reference, MetaStorage | [cortex/NODES.md](cortex/NODES.md) |
| Server startup, ports, endpoints | [loom/SERVER.md](loom/SERVER.md) |
| WebSocket protocols | [loom/WEBSOCKET.md](loom/WEBSOCKET.md) |
