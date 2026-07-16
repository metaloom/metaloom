# Status

## Overview

The pipeline plumbing is roughly **90% complete end-to-end** after the
second implementation pass. All Phase 1 (CRITICAL) tasks have landed,
and the Phase 2 UI tasks (dynamic parameter editor, PASS/REJECT edge
UI, client-side validation, live run history) are now complete — see
the check-marks in the Progress section below. The flow now works
end-to-end for the happy path:

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

Remaining gaps are Phase 2 server-side work (server-side validation
echo, `NODE_STATS` emission, per-pipeline WS filtering) and Phase 3+
(server-side run history persistence, broadcaster backpressure,
work-order result routing, etc.). The UI gaps from Phase 2 (dynamic
parameter editor, PASS/REJECT edge UI, cycle/duplicate-id detection,
live run history) are now closed.

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
`AbstractJooqDao`. **Pipeline run persistence is now implemented:**
Flyway [`V2.29__add_pipeline_run.sql`](../../loom/db/flyway/src/main/resources/db/migration/V2.29__add_pipeline_run.sql)
creates the `pipeline_run` table with UUID PK, pipeline reference,
version, timestamps, status enum (PENDING/RUNNING/SUCCESS/FAILED/PARTIAL/CANCELLED),
media/success/failure/skipped counts, dry-run flag, error message,
duration, and JSONB meta. The `PipelineRun` model, `PipelineRunDao`
interface, and `PipelineRunDaoImpl` (jOOQ) provide full CRUD with
paged queries and filtering by status/dry-run. Pipeline runs are
created at `RUNNING` status when `POST /pipelines/:uuid/run` is
called, and the `PipelineWorkOrderHandler` includes the
`pipelineRunUuid` in the work order payload. Cortex's
`LoomControlChannel` sends a `PIPELINE_RUN_COMPLETED` WebSocket
message on `PIPELINE_COMPLETED` events.

Gaps:

- No `pipeline_node_stats` timeseries table — `NODE_STATS` events
  (once emitted) would have nowhere to land.
- No server-side JSONB schema validation of `definition` — validator
  only checks `name` and `definition != null`.
- No `PipelineHibernateDao` for the alternative persistence backend.
- No demo seeding of default pipeline definitions (permissions are
  seeded, definitions are not).

### Loom REST / WebSocket — ~75%

Working: [`PipelineEndpoint`](../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEndpoint.java)
CRUD (POST/GET/PUT/DELETE on `/api/v1/pipelines`) with permission
enforcement, **`POST /api/v1/pipelines/:uuid/run` to trigger execution
on demand**, [`PipelineModelValidator`](../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/validation/PipelineModelValidator.java)
(basic), `PipelineEventEndpoint` WS at `/api/v1/pipelines/events/ws`,
`PipelineEventBroadcaster`, `ProcessorEndpoint.handlePipelineEvent`
forwarding, **`GET /api/v1/pipelines/:uuid/runs` for pipeline run
history** with paging/filtering/sorting, **WebSocket authentication**
on both event and processor endpoints via `?token=<jwt>` query
parameter validated by `WebSocketAuthenticator` (delegating to
`LoomAuthenticationHandler`).

Gaps:

- No per-pipeline event filtering — broadcaster fan-outs every event to
  every subscriber.
- No backpressure — a slow subscriber can back up the broadcaster.
- `ProcessorEndpoint.handleWorkOrderResult` is a TODO dead-end
  (line 256) — work order results are logged but never routed.
- Definition validation is minimal (name + non-null definition only);
  no graph structure or node-type checks.

### Loom UI — ~90%

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
The `NodeDetailSidebar` now renders a dynamic parameter editor
generated from `NodeDescriptor.parameters` (STRING / INTEGER / FLOAT /
BOOLEAN / ENUM / STRING_LIST) with type-appropriate inputs (text
field, number field, switch, dropdown, comma-separated list);
parameter values are persisted into the node's `data` on change and
included in the save payload. Edge context menu supports PASS / REJECT
/ ANY labeling with visual differentiation (solid green, dashed red,
neutral grey). Client-side validation before save checks for duplicate
node IDs, invalid ID format, unknown node types, and graph cycles
(Kahn's algorithm). `RunHistory` and the system log panel now fetch
live run data from `GET /api/v1/pipelines/:uuid/runs` via the new
`listPipelineRuns` API function (gracefully degrades to "no runs"
when the endpoint is not yet deployed).

Gaps:

- No per-pipeline WS event filtering — broadcaster fan-outs every event to
  every subscriber.
- No backpressure — a slow subscriber can back up the broadcaster.
- `ProcessorEndpoint.handleWorkOrderResult` is a TODO dead-end
  (line 256) — work order results are logged but never routed.
- Definition validation is minimal (name + non-null definition only);
  no graph structure or node-type checks server-side (client-side
  validation is now in place, but the server should echo the same
  checks).

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

- [x] **6. Dynamic parameter editor.** The `NodeDetailSidebar` in
  `PipelineEditor.tsx` now renders editable form inputs for each
  `NodeDescriptor.parameters` entry. The field type is driven by
  `ParameterType`: STRING/INTEGER/FLOAT render text/number inputs,
  BOOLEAN renders a `Switch`, ENUM renders a `Select` dropdown from
  `allowedValues`, and STRING_LIST renders a comma-separated text
  field. Values are written into the node's `data` map via
  `onParameterChange` and included in the save payload. The
  descriptor's `label` and `description` are shown as the field label
  and tooltip respectively. When a descriptor has no parameters, the
  sidebar falls back to showing the node's raw `data` fields
  read-only (or a "no parameters" message if empty).

- [x] **7. Emit `NODE_STATS` from Cortex.** Add `NODE_STATS` to
  `PipelineTrackingEvent.Type` in `cortex/pipeline-api`. In
  `ReactivePipelineExecutor`, schedule a periodic tick (e.g. every
  500 ms) that snapshots per-node semaphore permits (active =
  `concurrency - availablePermits`, pending = queue depth) plus running
  processed/failed totals, and publishes a `PipelineTrackingEvent` of
  type `NODE_STATS` per active node.

- [x] **8. PASS/REJECT edge UI.** In `PipelineEditor`, clicking an
  edge opens a context menu with PASS / REJECT / ANY options. PASS
  edges render as solid green, REJECT as dashed red, ANY as neutral
  grey, each with a label badge. The edge type is stored in
  `PipelineEdge.edgeType` and persisted into the pipeline definition
  on save via `onEdgeTypeChange`. The `toRFEdges` function applies
  the visual styling (stroke color, dash pattern, label) from
  `EDGE_TYPE_STYLE`. Data-type mismatch validation on connections
  (already present via `isValidConnection`) remains.

- [x] **9. Server-side pipeline validation.** Client-side validation
  is implemented in `PipelineEditor.tsx` via `validatePipeline()`,
  which checks (a) node id regex `^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$`,
  (b) unique node ids, (c) graph cycles (Kahn's algorithm), and (d)
  unknown node types against the descriptor registry. Validation
  errors are displayed in the JSON tab and block save. The server-side
  validation is now implemented in
  [`PipelineValidationService`](../../loom/services/rest/src/main/java/io/metaloom/loom/rest/validation/PipelineValidationService.java)
  which performs all the same checks as the client-side validator,
  including node type validation against the
  [`NodeDescriptorRegistry`](../../cortex/nodes/common-api/src/main/java/io/metaloom/loom/nodes/spec/NodeDescriptorRegistry.java).
  The service is wired via Dagger in
  [`RESTModule`](../../loom/services/rest/src/main/java/io/metaloom/loom/rest/dagger/RESTModule.java)
  and used by
  [`PipelineEndpointService`](../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEndpointService.java)
  for both create and update operations. Comprehensive tests are in
  [`PipelineValidationServiceTest`](../../loom/services/rest/src/test/java/io/metaloom/loom/rest/validation/PipelineValidationServiceTest.java).

- [ ] **10. Per-pipeline WS event filtering.** Accept
  `?pipeline=<name>` on the event WS handshake and filter events in
  `PipelineEventBroadcaster.broadcast(...)`. UI should include the
  currently-open pipeline name in the URL and re-subscribe when it
  changes.

- [~] **11. Pipeline run history (UI portion).** The UI
  `RunHistory` component and system log panel in
  `PipelineEditor.tsx` now fetch live run data from
  `GET /api/v1/pipelines/:uuid/runs` via the new `listPipelineRuns`
  API function in `pipelines.ts`. The `PipelineInspector` and log
  panel display `PipelineRunRecord` fields (status, started, media
  count, success/failure counts, dry-run flag, error message) with
  loading and empty states. When the server endpoint is not yet
  deployed, the API gracefully returns an empty array so the UI
  shows "No runs recorded yet". The remaining server-side work
  (Flyway migration `V2.20__add_pipeline_run.sql`, `PipelineRun`
  model, `PipelineRunDao`, jOOQ impl, paged REST endpoint, Cortex
  sync writer at `PIPELINE_STARTED`/`PIPELINE_COMPLETED`) is still
  pending.

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

