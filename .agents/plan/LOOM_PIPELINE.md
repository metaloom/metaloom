# Status

## Overview

The pipeline plumbing is roughly **85% complete end-to-end** after the
first implementation pass. All Phase 1 (CRITICAL) tasks have landed —
see the check-marks in the Progress section below — and the flow now
works end-to-end for the happy path:

- Pipelines loaded from Loom into Cortex resolve to **real cortex
  nodes** for the registered types (SHA-512/256, MD5, chunk-hash,
  thumbnail) via `RegistryNodeFactory` + `CortexNodeAdapter`; unknown
  types still fall back to stubs.
- Node results marked `syncToLoom=true` are pushed to Loom by
  `LoomBulkSyncWriterImpl` (grouped by SHA-512, sent via
  `bulkUpdateAssets`).
- The UI editor can **save and run** pipelines: `pipelines.ts`
  exposes `createPipeline`, `updatePipeline`, `deletePipeline`, and
  `runPipeline`; the toolbar has a wired Save chip + Run chip with
  loading state and Snackbar feedback.
- `POST /api/v1/pipelines/:uuid/run` dispatches a
  `WorkOrder(PIPELINE_RUN)` to a registered processor; Cortex's
  `PipelineWorkOrderHandler` handles a new `run-pipeline` command
  that resolves the pipeline by name and pushes a media stream
  (from `pathGlobs`) into `PipelineExecutor.execute(...)`.
- Both WebSockets (`/api/v1/pipelines/events/ws`,
  `/api/v1/processors/ws`) now accept a `?token=<jwt>` query
  parameter that is validated via `WebSocketAuthenticator`
  (delegating to the existing `LoomAuthenticationHandler`). Strict
  mode is opt-in via `LOOM_WS_STRICT_AUTH=true` so pre-token clients
  keep working during the rollout.

Remaining gaps are Phase 2 and later — dynamic parameter editor,
`NODE_STATS` emission, PASS/REJECT edge UI, server-side pipeline
validation, per-pipeline WS filtering, and pipeline run history.

## Per-surface status

### Cortex execution engine — ~96%

Working: [`ReactivePipelineExecutor`](../../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/executor/ReactivePipelineExecutor.java),
[`AbstractPipelineNode`](../../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/node/AbstractPipelineNode.java),
`AbstractFilterNode`, all 10 filter nodes, dual-channel event bus, 5
cache providers, [`LoomControlChannel`](../../cortex/core/src/main/java/io/metaloom/cortex/impl/loom/LoomControlChannel.java)
+ `PipelineWorkOrderHandler` (now 4 commands: `reload-pipelines`,
`flush-sync`, `list-pipelines`, `run-pipeline`), `CortexNodeAdapter`
bridge, serde round-trip, `RegistryNodeFactory` wired into
`LoomPipelineLoader` via `PipelineNodeFactoryModule`, a production
`LoomBulkSyncWriterImpl` wired as the third arg to
`ReactivePipelineExecutor` in `CortexBindModule`,
[`FacedescriptionNode`](../../cortex/nodes/facedetect/core/src/main/java/io/metaloom/cortex/node/facedescription/FacedescriptionNode.java)
now re-detects faces via the injected `InspireFacedetector`, crops
each face, and emits a JSON array of `FaceDescription` under
`face_description`, and
[`AbstractSceneDetector`](../../cortex/nodes/scene-detection/core/src/main/java/io/metaloom/cortex/node/scene/AbstractSceneDetector.java)
now logs frame failures via SLF4J instead of
`printStackTrace` + `System.in.read()`.

Gaps:

- Only 5 node types are registered with the factory today (hash
  family + thumbnail). Extend `PipelineNodeFactoryModule` with the
  remaining ~10 legacy nodes as they are exercised.
- `NODE_STATS` is advertised by all 14 node descriptors but the enum
  value is absent from `PipelineTrackingEvent.Type` and nothing emits it.
- `SidecarFileNodeCache.clear()` is a warn stub.
- `PipelineFilter` / `MediaFilter` SPI is orphaned — never called by
  the manager or executor.
- `run-pipeline` only resolves media from `pathGlobs`; UUID-based
  selection still logs a warning and is skipped.
- Video face description is deferred —
  `FacedescriptionNode` currently returns `skipped("Video face
  description not yet supported")` for video assets.

### Loom persistence — ~60%

Working: Flyway [`V2.19__add_pipeline.sql`](../../loom/db/flyway/src/main/resources/db/migration/V2.19__add_pipeline.sql)
(pipeline table with JSONB definition, permissions), `Pipeline` model,
`PipelineDao` interface, `PipelineDaoImpl` (jOOQ) with full CRUD via
`AbstractJooqDao`. Pipeline runs now flow end-to-end via the new
`POST /pipelines/:uuid/run` → `WorkOrder(PIPELINE_RUN)` →
`PipelineWorkOrderHandler.run-pipeline` chain, but no server-side run
record is persisted.

Gaps:

- No `pipeline_run` history table — pipeline runs are not persisted;
  UI `RunHistory` component shows hard-coded mock data.
- No `pipeline_node_stats` timeseries table — `NODE_STATS` events
  (once emitted) would have nowhere to land.
- No server-side JSONB schema validation of `definition` — validator
  only checks `name` and `definition != null`.
- No `PipelineHibernateDao` for the alternative persistence backend.
- No demo seeding of default pipeline definitions (permissions are
  seeded, definitions are not).

### Loom REST / WebSocket — ~65%

Working: [`PipelineEndpoint`](../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEndpoint.java)
CRUD (POST/GET/PUT/DELETE on `/api/v1/pipelines`) with permission
enforcement, [`PipelineModelValidator`](../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/validation/PipelineModelValidator.java)
(basic), `PipelineEventEndpoint` WS at `/api/v1/pipelines/events/ws`,
`PipelineEventBroadcaster`, `ProcessorEndpoint.handlePipelineEvent`
forwarding.

Gaps:

- No `POST /api/v1/pipelines/:uuid/run` to trigger execution on demand.
- No WebSocket authentication on the event or processor endpoints
  (endpoint comment acknowledges this as a TODO).
- No per-pipeline event filtering — broadcaster fan-outs every event to
  every subscriber.
- No backpressure — a slow subscriber can back up the broadcaster.
- `ProcessorEndpoint.handleWorkOrderResult` is a TODO dead-end
  (line 256) — work order results are logged but never routed.
- Definition validation is minimal (name + non-null definition only);
  no graph structure or node-type checks.

### Loom UI — ~75%

Working: [`PipelineEditor`](../../loom-ui/src/features/pipeline/PipelineEditor.tsx)
renders the graph, fetches node descriptors from
`/api/pipeline/node-descriptors`, supports drag-and-drop node creation,
keyboard-driven auto-arrange (Kahn's algorithm), live status via
`subscribePipelineEvents`, category-colored nodes, chip counters,
inspector panel with `RunHistory` scaffolding, routed at `/pipelines`.
[`pipelines.ts`](../../loom-ui/src/api/pipelines.ts) now exposes full
CRUD (`createPipeline` / `updatePipeline` / `deletePipeline`) plus
`runPipeline`, and the editor toolbar has functional Save and Run
chips with loading state, dirty-state tracking, and Snackbar
feedback. The events subscription in
[`pipelineEvents.ts`](../../loom-ui/src/api/pipelineEvents.ts)
includes the bearer token in the WS URL and reconnects on 4401.

Gaps:

- Node parameters shown read-only — no dynamic form generated from
  `NodeDescriptor.parameters`.
- No PASS/REJECT edge labeling UI; all edges look identical; only
  data-type mismatch validation on connections.
- No cycle/duplicate-id detection before save.
- `RunHistory` uses hard-coded mock data at
  `PipelineEditor.tsx:620-627`.

# Progress

## Phase 1 — Unblock end-to-end execution (CRITICAL) — COMPLETE

- [x] **1. Bulk sync writer.** `LoomBulkSyncWriterImpl` in `cortex/core`
  groups sync entries by SHA-512 and pushes them via
  `LoomClient.bulkUpdateAssets(...)`; wired in
  [`CortexBindModule`](../../cortex/core/src/main/java/io/metaloom/cortex/cli/dagger/CortexBindModule.java)
  via `@Binds BulkSyncWriter` plus a `@Provides` for
  `LoomBulkSyncCollector` that goes into the third
  `ReactivePipelineExecutor` argument. Node results with
  `syncToLoom=true` now reach Loom instead of being silently dropped.
  When no `LoomClient` is available (offline mode) the writer logs
  and drops — no more surprising `NullPointerException`.

- [x] **2. Node factory / resolver.**
  [`RegistryNodeFactory`](../../cortex/core/src/main/java/io/metaloom/cortex/pipeline/loader/RegistryNodeFactory.java)
  implements the previously-orphaned `NodeFactory` interface with a
  type-string → producer registry, wrapping each cortex node in a
  `CortexNodeAdapter`. It is populated by the new
  [`PipelineNodeFactoryModule`](../../cortex/cli/src/main/java/io/metaloom/cortex/cli/dagger/PipelineNodeFactoryModule.java)
  Dagger module and pushed onto `LoomPipelineLoader` at startup via
  `CortexBootstrapInitializer`. The loader's `parseNode` bug —
  which used to discard non-stub nodes returned by the factory — is
  fixed. Currently registered: `sha512`, `sha256`, `md5`,
  `chunk-hash`, `thumbnail`; adding more is a one-line addition per
  node.

- [x] **3. Run-pipeline endpoint + work order.**
  `POST /api/v1/pipelines/:uuid/run` on Loom accepts a
  [`PipelineRunRequest`](../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/PipelineRunRequest.java)
  and dispatches a
  `WorkOrder(WorkOrderType.PIPELINE_RUN, params={command:"run-pipeline", pipelineName, ...})`
  to a registered processor via `ProcessorRegistry`. Cortex's
  [`PipelineWorkOrderHandler`](../../cortex/core/src/main/java/io/metaloom/cortex/impl/loom/PipelineWorkOrderHandler.java)
  handles the new `run-pipeline` command, resolves the pipeline by
  name, expands `pathGlobs` into `LoomMedia` instances, and pushes
  them through `PipelineExecutor.execute(...)`. Response is
  [`PipelineRunResponse`](../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/PipelineRunResponse.java)
  with the work-order id + processor id.

- [x] **4. UI CRUD + Save/Run buttons.**
  [`pipelines.ts`](../../loom-ui/src/api/pipelines.ts) now exposes
  `createPipeline`, `updatePipeline`, `deletePipeline`, and
  `runPipeline`. `PipelineEditor.tsx` has a Save chip (dirty-state
  tracking, disabled while saving, amber when dirty) and a wired
  Run chip that calls `runPipeline` and shows a Snackbar with the
  dispatch result.

- [x] **5. WebSocket auth.**
  [`WebSocketAuthenticator`](../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/WebSocketAuthenticator.java)
  reads `?token=<jwt>` on the WS handshake and validates via the new
  `LoomAuthenticationHandler.authenticateToken(String)` method
  (implemented by `LoomJWTAuthHandlerImpl`). Both
  `PipelineEventEndpoint` and `ProcessorEndpoint` now gate their WS
  upgrades through it. Invalid tokens close the socket with code
  `4401`. Cortex passes the token via `LoomClientOptions.token` (or
  the `LOOM_TOKEN` env var); the UI appends the auth-context token
  to the events WS URL in
  [`pipelineEvents.ts`](../../loom-ui/src/api/pipelineEvents.ts).
  Strict mode (reject if no token) is opt-in via
  `LOOM_WS_STRICT_AUTH=true` so existing dev flows keep working
  during the rollout.

## Phase 2 — Editor / observability polish (HIGH)

- [ ] **6. Dynamic parameter editor.** Render editable inputs in the
  `PipelineEditor` node detail sidebar based on
  `NodeDescriptor.parameters` (string / number / boolean / enum /
  content-type). Persist values into node `options` on save. Validate
  types on the client and echo the same on the server in Task 9.

- [ ] **7. Emit `NODE_STATS` from Cortex.** Add `NODE_STATS` to
  `PipelineTrackingEvent.Type` in `cortex/pipeline-api`. In
  `ReactivePipelineExecutor`, schedule a periodic tick (e.g. every
  500 ms) that snapshots per-node semaphore permits (active =
  `concurrency - availablePermits`, pending = queue depth) plus running
  processed/failed totals, and publishes a `PipelineTrackingEvent` of
  type `NODE_STATS` per active node.

- [ ] **8. PASS/REJECT edge UI.** In `PipelineEditor`, add an edge
  label ("PASS" / "REJECT" / "ANY") settable via edge context menu.
  Render PASS as solid green, REJECT as dashed red, ANY as neutral
  grey. Only allow branched edges from nodes whose descriptor category
  is `FILTER`. Block connections whose source/target content types
  don't match.

- [ ] **9. Server-side pipeline validation.** Extend
  [PipelineModelValidator](../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/validation/PipelineModelValidator.java)
  to check (a) node id regex `^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$`,
  (b) unique node ids within a pipeline, (c) exactly one node with
  `isSource=true`, (d) no dependency cycles (Kahn's algorithm on the
  parsed graph), (e) all node `type`s resolvable against the
  descriptor registry. Expose the descriptor registry server-side —
  either seed from the last-known processor snapshot or pull it on
  demand from a registered processor via a WorkOrder.

- [ ] **10. Per-pipeline WS event filtering.** Accept
  `?pipeline=<name>` on the event WS handshake and filter events in
  `PipelineEventBroadcaster.broadcast(...)`. UI should include the
  currently-open pipeline name in the URL and re-subscribe when it
  changes.

- [ ] **11. Pipeline run history.** New Flyway migration
  `V2.20__add_pipeline_run.sql` with columns
  `(uuid, pipeline_uuid, started, finished, status, media_count,
  success_count, failure_count, dry_run, error_message)`. Add
  `PipelineRun` model, `PipelineRunDao`, jOOQ impl, REST endpoint
  `GET /api/v1/pipelines/:uuid/runs` (paged), a lightweight sync
  writer on the Cortex side that writes a run row at
  `PIPELINE_STARTED` and updates it at `PIPELINE_COMPLETED`, and swap
  the UI's hard-coded `RunHistory` mock for live data.

## Phase 3 — Data & runtime hardening (MEDIUM)

- [ ] **12. Persist `NODE_STATS` snapshots.** Migration
  `V2.21__add_pipeline_node_stats.sql` with schema
  `(pipeline_uuid, node_id, ts, active, pending, processed, failed)`
  and a supporting `(pipeline_uuid, node_id, ts DESC)` index. Handle
  `NODE_STATS` in `ProcessorEndpoint.handlePipelineEvent`. Query API
  `GET /api/v1/pipelines/:uuid/nodes/:nodeId/stats?from=&to=`.

- [ ] **13. Broadcaster backpressure.** In
  `PipelineEventBroadcaster` maintain a per-subscriber bounded queue
  (e.g. 1024 entries). On overflow, drop-oldest and increment a
  per-subscriber `droppedCount`. Emit a `NODE_STATS_DROPPED` metric
  observable via `/api/v1/metrics` (if present) or expose via a new
  debug endpoint.

- [ ] **14. Route work-order results.** Replace the TODO at
  `ProcessorEndpoint.java:256` with a per-work-order callback registry
  keyed by request UUID; when a `WORK_ORDER_RESULT` message arrives,
  look up the awaiting `Promise` / `Future` and complete it. Wire the
  new run endpoint (Task 3) through this so callers can wait for
  completion synchronously or receive an ack + poll a run row.

- [x] **15. Finish `FacedescriptionNode`.** The node now injects the
  same `InspireFacedetector` used by `FacedetectNode` (nullable, so
  the module still works when the detector is absent), re-runs face
  detection on the source image, crops each face using its
  bounding box, feeds every crop through the vision LLM, and emits
  the collected `FaceDescription` list as a JSON array under
  `face_description`. Video assets are cleanly skipped for now with
  a `"Video face description not yet supported"` reason instead of
  the previous `"pending"` placeholder.
  File: [FacedescriptionNode.java](../../cortex/nodes/facedetect/core/src/main/java/io/metaloom/cortex/node/facedescription/FacedescriptionNode.java).

- [x] **16. Fix `AbstractSceneDetector` error handling.** Replaced
  the two `printStackTrace()` calls plus the blocking
  `System.in.read()` hang with SLF4J
  `log.error("Scene detection failed at frame {} of {} — skipping frame", ...)`.
  Progress logging (`System.out.println` every 1000 frames) is now
  `log.debug`. The frame is skipped via `continue` so processing
  keeps moving instead of stalling on user input.

- [ ] **17. Seed default pipeline in demo init.** Extend
  [DemoDatabaseInitializer](../../loom/core/src/main/java/io/metaloom/loom/core/boot/DemoDatabaseInitializer.java)
  to create a default `ingest` pipeline (source → sha256 → thumbnail →
  loom-sync) so a fresh dev environment shows a real pipeline in the
  UI.

- [ ] **18. Server-side default layout.** When
  `GET /api/v1/pipelines/:uuid` returns a definition whose nodes lack
  `position.x`/`position.y`, compute a Kahn's-layered left-to-right
  layout server-side so UIs beyond the current editor don't have to
  replicate the algorithm.

- [ ] **19. Descriptor / event alignment audit.** For each of the 14
  descriptor providers that advertises `NODE_STATS`, either wire the
  node to actually emit stats (Task 7 covers executor-level; per-node
  business-metric stats need per-node hooks) or remove `NODE_STATS`
  from the descriptor's `events` list.

## Phase 4 — Cleanup (LOW)

- [ ] **20. `SidecarFileNodeCache.clear()`.** Implement recursive
  delete of the sidecar `.json` files under the configured base
  directory (respect the file name pattern, don't touch unrelated
  files).

- [ ] **21. Orphaned `PipelineFilter` SPI.** Either delete
  `PipelineFilter` / `MediaFilter` from `cortex/pipeline-api` and
  update docs, or wire them into `DefaultPipelineManager.resolve(...)`
  as a pipeline-level pre-filter (in addition to the node-level filter
  branches).

- [ ] **22. Hibernate DAO.** If the codebase ships a Hibernate backend
  for other tables, add `PipelineHibernateDao` for parity. Skip if
  jOOQ is the only supported backend.

- [ ] **23. Configurable type-handler cache.** Replace the hard-coded
  cache in
  [AbstractCachingLoomTypeHandler](../../cortex/common/src/main/java/io/metaloom/cortex/api/media/type/handler/AbstractCachingLoomTypeHandler.java)
  with an injected `NodeCacheProvider` (existing TODO on line 14).

