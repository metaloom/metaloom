# MetaLoom Pipeline System — Requirements

> **What this file is.** The **conformance record** for the pipeline feature: each
> requirement, whether the shipped system meets it, and — where it does not — the
> deviation stated explicitly. Readable without the code.
>
> **What it is not.** Not the design (that is [PIPELINE.md](PIPELINE.md)) and not the
> work queue (that is [PIPELINE_TASKS.md](PIPELINE_TASKS.md)). A gap noted here must
> have a task there or be marked *accepted*; it must not be re-argued in both files.
>
> **Status source:** every row was re-verified against the code at HEAD `499f71f7`.
> Where a status contradicts an older document, the code wins.

| Related | Purpose |
|---|---|
| [PIPELINE.md](PIPELINE.md) | Technical spec — parser, engine, protocol, persistence, REST |
| [PIPELINE_TASKS.md](PIPELINE_TASKS.md) | The actionable breakdown of every 🔴/🟡 below |
| [PIPELINE_FLOW.md](PIPELINE_FLOW.md) | The mental model: what actually travels between nodes |
| [NODE_DATA_TYPES.md](NODE_DATA_TYPES.md) | Ports, content types, cardinality, fan-out/gather |
| [../pipeline-nodes/NODES.md](../pipeline-nodes/NODES.md) | Per-node reference; descriptor ↔ runnable-kind reconciliation (§5.2) |

---

## 1. What the pipeline system is (in plain terms)

MetaLoom manages large collections of media. A **pipeline** is a recipe describing how a
batch of media is processed: where the media comes from, what is extracted from it
(hashes, faces, transcripts, thumbnails, …), and what is filtered out along the way.

- A pipeline is a **graph of nodes**, drawn in the Loom web UI and saved on the Loom
  server. It begins with exactly one **source node** that emits media items; every other
  node does one small job per item.
- **Loom owns the graph; Cortex owns one node at a time.** Loom decides what runs next
  and dispatches a single task; the Cortex worker executes it and answers. The heavy
  CPU/GPU work is still exclusively on Cortex.
- Nodes are wired **port to port** — an edge names the output port it reads and the input
  port it feeds. Nothing is addressed by node id.
- Every save produces a **new immutable version**, so history is reviewable and an older
  revision can be restored.
- While a run proceeds, per-node progress counters are **pushed to the UI over a
  WebSocket**, and every node's outputs are **persisted on Loom**.

| Side | Role |
|---|---|
| **Loom** | Stores definitions + versions, parses and type-checks the graph, drives the DAG, persists run/item/task state and results, relays live events to the UI |
| **Cortex** | Executes one node (or one affinity segment) per task, streams source items, returns results |

---

## 2. Mandatory ("rooted") requirements

Legend: ✅ Met · 🟡 Met with a stated deviation · 🔴 Not met

| # | Requirement | Status | Verification & deviations |
|---|---|---|---|
| **R1** | Pipelines are executed on the Cortex processor instances | ✅ | All node work runs in a Cortex worker (`NodeTaskRunner` / `SegmentTaskRunner` / `SourceTaskRunner`). Loom only schedules. *Clarification, not a deviation:* the DAG itself moved to Loom (`PipelineRunEngine`); there is no executor on the worker any more. |
| **R2** | Pipelines send their status / updates via API calls back to the Loom backend | 🟡 | **Deviation: no REST.** Progress *and* results travel over the processor WebSocket `/api/v1/processors/ws` (`NODE_TASK_RESULT(_BATCH)`, `SEGMENT_TASK_RESULT`, `SOURCE_ITEMS`, `PIPELINE_RUN_COMPLETED`). The one REST write-back path, `LoomBulkSyncWriterImpl`, is **dormant** — nothing calls `collect(...)`. Met if "API calls" means Loom's network interface; a deviation if REST is meant strictly. |
| **R3** | Pipelines can be constructed in the Loom UI and persisted on the Loom backend | ✅ | `PipelineEditor.tsx` → REST CRUD → `pipeline` + `pipeline_version` (JSONB). The old **authored ≠ executed** defect is closed: there is exactly one parser (`PipelineGraphParser`), Cortex no longer parses definitions at all, and `nodes[].dependencies[]` is now **rejected outright**. |
| **R4** | Pipelines always have a start node which emits assets | ✅ | `resolveSourceNode` is strict — a declared `source: true` wins, ambiguity is an error, never a guess. The source's output port must literally be `media` (`PipelineRunEngine.SOURCE_MEDIA_PORT`). |
| **R5** | Additional nodes (facedetect, sha512, …) extract further metadata from assets | ✅ | **33** runnable kinds with S3 configured, **32** without (30 `@IntoMap @StringKey` bindings + `filesystem-source` + `asset-source` + conditional `s3-source`). Coverage gap is scoped to filters — see R7. |
| **R6** | Pipeline execution results are tracked on the Loom server | ✅ | Three levels, all durable: `pipeline_run` (status, `duration_ms`, four counters, first-terminal-verdict-wins), `pipeline_run_item` per media item, and `pipeline_node_task` per node **per element** (`UNIQUE (item_uuid, node_id, element_seq)`). **Minor deviation:** no REST route exposes the task rows — `GET /:uuid/runs/:runUuid/items` exists, a per-node `/tasks` route does not (owned by [../../cortex/METALOOM_ARCHITECTURE_TASK.md](../../cortex/METALOOM_ARCHITECTURE_TASK.md) and [../../loom/ui/TASK_UI_PIPELINE.md](../../loom/ui/TASK_UI_PIPELINE.md)). |
| **R7** | Special pipeline nodes allow filtering of asset results (DateFilter, …) | 🔴 | **The largest live deviation.** Everything except execution is in place: 8 filter classes exist in `cortex/pipeline-core` (`AssetAttributeFilterNode`, `BlacklistFilterNode`, `DateFilterNode`, `DuplicateFilterNode`, `MimeTypeFilterNode`, `QualityFilterNode`, `SamplingFilterNode`, `ThresholdFilterNode`), the format expresses `branch: PASS/REJECT`, and the engine honours `conditionalDependencies`. But **not one `filter-*` kind is registered as runnable** — no `@StringKey` binding, no `factory.register`. Worker acceptance is a config whitelist, not a capability list, so dispatch is **not** refused at 503: the task is sent, `RegistryNodeFactory.createNode` returns `null`, and the task fails. Also `filter-size` is advertised with no class; `SamplingFilterNode` is a class with no descriptor. See [PIPELINE_TASKS.md](PIPELINE_TASKS.md) Task 3. |
| **R8** | Pipelines are serialized / deserialized via JSON | ✅ | JSONB definition with a top-level `version` (`CURRENT_DEFINITION_VERSION = 1`; absent ⇒ 1; higher refused **by name**, never half-read). `stampVersion` runs on create/update and in `DemoDatabaseInitializer`. **Deviation:** serde is one-way — `PipelineSerializer`/`PipelineDeserializer` are deleted, so no code writes a definition back out of a graph object, and there is **no checked-in fixture** (the six demo pipelines are the de-facto reference). |
| **R9** | Pipeline execution is backpressure-aware and reactive | ✅ | Bounded end to end: `maxInFlight` (default 256) + per-kind bulkheads, and `SOURCE_ITEMS_ACK` is **withheld** at capacity, which throttles the source scan itself rather than only node dispatch. Cortex `SourceTaskRunner` waits for each ack. **Deviation of wording:** "reactive" no longer means RxJava — the reactive executor was deleted; backpressure is explicit accounting under one monitor. |
| **R10** | Intermediate pipeline node results are stored on the Loom backend service | ✅ | Every node result is persisted to `pipeline_node_task.outputs` (JSONB, keyed by output **port id**, `PortPayloads` codec) — this is the dedicated intermediate-result store the requirement asks for. Nodes flagged `syncToLoom` additionally write onto the asset via `DaoAssetSink`, with the `asset_node_result` ledger (`V2.45`). **Deviation:** nothing prunes it — retention is decided but not enforced ([PIPELINE.md §9.2](PIPELINE.md)). |
| **R11** | Pipelines can be validated using a validation endpoint in the Loom REST API | 🔴 | There is **no `POST /api/v1/pipelines/validate`**. Validation runs only as a side effect of create/update, so a draft cannot be checked without persisting it. The checks themselves are thorough, but **structural** rules exist in three copies (`PipelineValidationService`, `PipelineModelValidator`, `validatePipeline()` in the editor); only **port** rules are single-sourced in the parser. Task 8. |
| **R12** | WebSocket events must be emitted so the Loom UI can visualize processing / status | ✅ | `RunStatsAggregator` (1 s timer) → `PipelineEventBroadcaster` → `/api/v1/pipelines/events/ws`, with `?pipeline=` / `?run=` filters and drop-on-`writeQueueFull` backpressure. Failures are forwarded immediately. **Deliberate deviation:** progress is **aggregated, not streamed** — there is no per-item event; forwarding every settle would be millions of frames to move a progress bar. |

### Deviations at a glance

| # | Deviation | Where it is tracked |
|---|---|---|
| R7 | No filter kind is runnable — a filter in a graph fails at execution time | [PIPELINE_TASKS.md](PIPELINE_TASKS.md) Task 3 (**blocking**) |
| R11 | No standalone validation endpoint; structural validation triplicated | Task 8 |
| R2 | Status/results ride the WebSocket, not REST; the REST bulk-sync path is dormant | Task 10 (decide: wire or delete) |
| R6 | Per-node task rows are durable but not exposed over REST | [../../cortex/METALOOM_ARCHITECTURE_TASK.md](../../cortex/METALOOM_ARCHITECTURE_TASK.md) |
| R8 | No checked-in definition fixture; no graph→JSON writer | Task 6 |
| R10 | No retention sweep for run/item/task rows | [../../cortex/METALOOM_ARCHITECTURE_TASK.md](../../cortex/METALOOM_ARCHITECTURE_TASK.md) |

---

## 3. Additional requirements

Production-grade expectations beyond the rooted set. `[x]` met · `[~]` partial · `[ ]` open.
Each open line names the task that owns it; none is re-argued here.

| Theme | Requirement | State |
|---|---|---|
| **Error handling** | A failing node becomes a `FAILED` result, never a crashed run | `[x]` |
| | A failed **blocking** dependency skips downstream work — identically in the engine *and* `SegmentTaskRunner` | `[x]` |
| | Retry with exponential backoff and an attempt ceiling (`retryFailed`, `maxAttempts`, `RetryScheduler`) | `[x]` |
| | Cross-run circuit breaker per node kind (`NodeKindCircuitBreaker`) | `[x]` |
| | Lease reclaim + dead-lettering for a vanished worker (`LeaseReaper`, `DEAD_LETTER`) | `[x]` |
| | Per-node execution timeout honoured on the worker | `[x]` |
| **Persistence** | Definitions + immutable versioning + copy-forward restore | `[x]` |
| | Durable run / item / node-task state; restart recovery rebuilds live engines | `[x]` |
| | Recovery re-parses **with** the descriptor registry so ports are checked and fan-out survives a restart | `[ ]` Task 12 |
| | Typed run status instead of a free-form `String` | `[ ]` Task 9 |
| | Retention sweep for terminal-run detail | `[ ]` [ARCHITECTURE_TASK](../../cortex/METALOOM_ARCHITECTURE_TASK.md) |
| | `loom/db/memory` can serve pipelines | `[ ]` Task 11 — jOOQ backend required today |
| **Execution** | Per-element fan-out + implicit gather (`ExecutionMode.PER_ELEMENT`, `isSettled()`) | `[x]` |
| | Affinity segmentation — connected, acyclic, source excluded | `[x]` |
| | Pause / resume / cancel that gate the source scan, not just dispatch | `[x]` |
| | Result reuse adopts a prior result as `COMPLETED` **carrying outputs**, never as a skip | `[x]` |
| | Media selection precedence `mediaUuids` > `pathGlobs` > `path` | `[x]` |
| | Dispatch refuses a graph no worker accepts (503) and an invalid graph (400) **without** leaving a run row | `[x]` |
| | Processor **capability** derived from node kinds instead of hardcoded `CPU` | `[ ]` Task 10 |
| | Node `initialize()` / `shutdown()` invoked by the runtime | `[ ]` Task 10 — native handles are never released |
| **Node coverage** | Descriptors drive the UI palette and are enforced by `PortGraphAnalyzer` at save **and** run start | `[x]` |
| | `NodePortConformanceTest` holds every node's port constants against its descriptor | `[x]` |
| | Unknown kind fails loudly rather than reporting a fake success | `[x]` — `createNode` returns `null`, the task fails |
| | Every advertised descriptor kind has a runtime producer | `[ ]` Task 3 — 10 kinds do not |
| | Node versioning so a changed algorithm invalidates cached results | `[ ]` — accepted gap, no caching is live |
| **Events & metrics** | Aggregated per-node counters to the UI; failures immediate | `[x]` |
| | Prometheus `/metrics` on both components | `[x]` |
| | The run engine is instrumented | `[ ]` Task 13 — `loom/pipeline` has **zero** `LoomMetrics` references; 5 documented meters have no registration ([../ops/METRICS.md §5.2](../ops/METRICS.md)) |
| | Per-item opt-in event stream for debugging one file | `[ ]` [PLAN_C §3.3](../../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md) |
| **Validation** | Port rules single-sourced in the parser | `[x]` |
| | Structural rules single-sourced; standalone validate endpoint | `[ ]` Task 8 (**closes R11**) |
| **API & clients** | Full REST surface: CRUD, run, pause/resume/cancel, run history, run items, cross-pipeline stats, versions + restore | `[x]` |
| | Java client reaches all of it (`PipelineMethods`) | `[x]` — landed 2026-07-26 |
| | Java endpoint tests for the versioning surface and `POST /run` dispatch shape | `[ ]` Task 7 |
| | UI surfaces server errors instead of returning `[]` | `[ ]` Task 11 |
| | Pipeline gRPC surface | `[ ]` — accepted omission, see [../../loom/GRPC.md](../../loom/GRPC.md) |
| **Caching** | Node result caching wired in production | `[ ]` Task 5 — `cacheProvider()` is dead wiring |
| | Segment-scoped intermediate **artifact** cache (decode once per segment) | `[ ]` [../../plans/TASKS.md](../../plans/TASKS.md) |

---

## 4. Conventions and Gotchas

Requirement-level traps. Implementation-level ones live in [PIPELINE.md §16](PIPELINE.md).

| Area | Gotcha |
|---|---|
| **"Advertised" ≠ "works"** | A descriptor makes a kind appear in the palette; only a `@StringKey` binding makes it run. R7 is exactly this gap. Never read a descriptor count as a capability count. |
| **A 503 does not protect you** | `unsupportedNodeKinds` tests the worker's **config whitelist/blacklist**, not what it can actually construct. An unimplemented kind therefore dispatches and fails at the worker. |
| **"Reactive" is historic wording** | R9 predates the rewrite. The RxJava executor is deleted; backpressure is explicit accounting plus withheld source acks. Do not re-add reactive operators to satisfy the letter of R9. |
| **"Intermediate results on Loom" is satisfied by `pipeline_node_task.outputs`** | Not by the Cortex `NodeCacheProvider` family, which is dead wiring, and not by `asset_node_result`, which is per *asset* catalog state that outlives every run. |
| **Progress is aggregated on purpose** | R12 does not require per-item events. Adding them is a new opt-in feature, not a fix. |
| **One requirement, one owner** | If a gap here is tracked in `METALOOM_ARCHITECTURE_TASK.md`, `PLAN_C` or `plans/TASKS.md`, link it — do not open a duplicate task in `PIPELINE_TASKS.md`. |
| **Deviations are load-bearing text** | This file's value is the explicit deviation column. When you close a gap, edit the row in the same change ([SPEC_RULES.md](../../SPEC_RULES.md)). |

---

## 5. Where do I find …?

| Need | Path |
|---|---|
| Whether a kind is actually runnable | `cortex/cli/…/dagger/RegistryNodeRegistrar.java` + every `<X>NodeModule` `@StringKey` |
| Descriptor ↔ runnable reconciliation table | [../pipeline-nodes/NODES.md §5.2](../pipeline-nodes/NODES.md) |
| The definition format and its version rules | [PIPELINE.md §4](PIPELINE.md) · `loom/pipeline/…/graph/PipelineGraphParser.java` |
| What "backpressure" concretely means here | `loom/pipeline/…/engine/PipelineRunEngine.java` (`whenCapacityAvailable`) |
| Where node outputs land | `loom/services/rest/…/service/impl/DaoRunStateStore.java` → `pipeline_node_task.outputs` |
| Where `syncToLoom` outputs land on the asset | `…/service/impl/DaoAssetSink.java` · migration `V2.45__add_asset_node_result.sql` |
| REST route inventory + permissions | [PIPELINE.md §10](PIPELINE.md) · `loom/services/rest/…/endpoint/impl/PipelineEndpoint.java` |
| Which meters exist vs. are fiction | [../ops/METRICS.md §3, §5](../ops/METRICS.md) |
| Filter node implementations | `cortex/pipeline-core/…/node/filter/` |
| Demo pipelines (the de-facto fixtures) | `loom/core/…/boot/DemoDatabaseInitializer.java` |

---

## 6. Progress Assessment

**Rooted requirements: 10 of 12 met, 2 with deviations stated.**

- [x] R1 Execution on Cortex processors
- [~] R2 Status reported back to Loom — over WebSocket, not REST
- [x] R3 Authored in the UI, persisted on Loom, executed as drawn
- [x] R4 Exactly one start node emitting assets
- [x] R5 Metadata-extracting nodes (33 runnable kinds)
- [x] R6 Execution results tracked at run / item / node-element level
- [ ] **R7 Filter nodes — no `filter-*` kind is runnable** (**blocking**, Task 3)
- [x] R8 JSON serialization with a versioned format
- [x] R9 Bounded, backpressure-aware execution
- [x] R10 Intermediate node results stored on Loom
- [ ] **R11 No standalone validation endpoint** (Task 8)
- [x] R12 WebSocket events for UI visualization

**Additional requirements — open, in severity order:**

- [ ] Register the 10 descriptor-only kinds (Task 3)
- [ ] Recovery must re-parse with the descriptor registry (Task 12)
- [ ] Validation endpoint + de-triplicated structural rules (Task 8)
- [ ] Instrument `loom/pipeline` (Task 13)
- [ ] Java endpoint tests for versioning and dispatch (Task 7)
- [ ] Typed run status (Task 9)
- [ ] Resolve dead surfaces: caches, capability, node lifecycle (Tasks 5, 10)
- [ ] Persistence/API gaps: memory DAOs, DAO naming, UI error masking (Task 11)
- [ ] Tracked elsewhere: retention sweep, per-node task API, per-item events, artifact cache

---

_Git HEAD revision: `499f71f7`_
_Last updated: 2026-08-01 (rewritten as a verified requirement→status table: R3/R6/R8/R10 now met after the Loom-owns-the-graph rewrite, R7 downgraded to 🔴 because no filter kind is runnable, and the A-xx gap list folded into PIPELINE_TASKS.md)_
