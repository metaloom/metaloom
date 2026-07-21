# TASK_UI_PIPELINE — Pipeline / Processing (Cortex)

Gap-analysis tasks between the Loom REST API (+ WebSocket) and the Loom UI for the
Pipeline/Processing entities (Pipeline, Pipeline Version, Pipeline Run, Run Item,
Node Task, Cortex Instance) and the node/editor surface. Pipelines and nodes are the
most complex area of the product, so this file is intentionally more granular than the
other TASK_UI_* files. Follows [../../TASKS.template.md](../../TASKS.template.md).

> **Important — the spec's CRUD matrix is stale.** [PIPELINE_EDITOR.md](PIPELINE_EDITOR.md)
> section 5.1 still marks pipeline **Create** and **Delete** as "❌ Not implemented".
> That is no longer true: [PipelineEditor.tsx](loom-ui/src/features/pipeline/PipelineEditor.tsx)
> now wires `createPipeline` (handleCreate ~line 2365), `deletePipeline`
> (~line 2383, with a confirm dialog ~line 3175), `cancelPipelineRun`, run history
> (`listPipelineRuns` / `listPipelineRunItems`), version history + restore
> (`listPipelineVersions` / `restorePipelineVersion` + `PipelineVersionDiff`), and a
> live event feed (`subscribePipelineEvents`). This file reflects the **actual code**,
> not the stale matrix. The first task below is to fix that matrix so the spec stops
> lying about what exists.

## Coverage Matrix

| Entity / Surface | REST/WS Operation (path · method) | UI Status | Where / Gap |
|------------------|-----------------------------------|-----------|-------------|
| Pipeline | `GET /api/v1/pipelines` · list | Implemented | `listPipelines` → pipeline list sidebar in [PipelineEditor.tsx](loom-ui/src/features/pipeline/PipelineEditor.tsx) |
| Pipeline | `GET /api/v1/pipelines/:uuid` · get | Implemented | `loadPipeline`; list response carries flattened definition |
| Pipeline | `POST /api/v1/pipelines` · create | Implemented | `createPipeline` (handleCreate ~2365) — contrary to stale matrix |
| Pipeline | `POST /api/v1/pipelines/:uuid` · update | Implemented | `updatePipeline`, canvas edit → Save; optimistic |
| Pipeline | `DELETE /api/v1/pipelines/:uuid` · delete | Implemented | `deletePipeline` (~2383) + confirm dialog (~3175) |
| Pipeline Run | `POST /api/v1/pipelines/:uuid/run` · trigger | Implemented | `runPipeline`, Run button; handles `dispatched` false → toast |
| Pipeline Run | `GET /api/v1/pipelines/:uuid/runs` · list | Implemented | `listPipelineRuns` → `RunHistory` / `PipelineInspector` |
| Pipeline Run | `GET /api/v1/pipelines/:uuid/runs/:runUuid` · get | **Missing** | `loadPipelineRun` exists in [pipelines.ts](loom-ui/src/api/pipelines.ts) but is **never called** — no single-run refresh / deep-link |
| Pipeline Run | `POST /api/v1/pipelines/:uuid/runs/:runUuid/cancel` · cancel | Implemented | `cancelPipelineRun` → `onCancelRun` |
| Run Item | `GET /api/v1/pipelines/:uuid/runs/:runUuid/items` · list | Implemented | `listPipelineRunItems` → run-detail drill-down (openRunDetail ~2045) |
| Node Task | *(no REST endpoint)* | **No REST surface** | `pipeline_node_task` is internal (LeaseReaper, WebSocketNodeDispatcher, DaoRunStateStore). Per-node lease/retry/dead-letter state is **not** exposed to the UI |
| Pipeline Version | `GET /api/v1/pipelines/:uuid/versions` · list | Implemented | `listPipelineVersions` (~2106) |
| Pipeline Version | `GET /api/v1/pipelines/:uuid/versions/:version` · get | Implemented | `loadPipelineVersion` (used by diff view) |
| Pipeline Version | `POST /api/v1/pipelines/:uuid/versions/:version/restore` · restore | Implemented | `restorePipelineVersion` (~2127) + [PipelineVersionDiff.tsx](loom-ui/src/features/pipeline/PipelineVersionDiff.tsx) |
| Cortex Instance | `GET /api/v1/processors` · list | Implemented | `listProcessors` → [CortexView.tsx](loom-ui/src/features/cortex/CortexView.tsx) |
| Cortex Instance | `GET /api/v1/processors/:uuid` · get | Implemented | `getProcessor` |
| Cortex Instance | `PUT /api/v1/processors/:uuid/restrictions` · node-kind whitelist/blacklist | Implemented | `updateProcessorRestrictions` |
| Cortex Instance | `DELETE /api/v1/processors/:uuid` · forget | Implemented | `forgetProcessor` |
| Cortex Instance | *(self-registers via processor WS)* — no create | N/A | Instances register over the processor WebSocket; no create UI needed |
| Node Descriptors | `GET /api/v1/pipeline/node-descriptors` · list | Implemented | `fetchNodeDescriptors` → node palette / command bar |
| Node Descriptors | `GET /api/v1/pipeline/node-descriptors/:kind` · get one | **Missing** | `fetchNodeDescriptor(kind)` exists in [nodeDescriptors.ts](loom-ui/src/api/nodeDescriptors.ts) but is **never called** (bulk list used for everything) — low impact |
| Content Types | `GET /api/v1/pipeline/content-types` · list | **Missing** | `fetchContentTypes` exists but is **never called**; editor handle colors / connection validation use a hardcoded data-type map instead of the server catalog |
| Pipeline Events (WS) | `/api/v1/pipelines/events/ws` · live event stream | Implemented | `subscribePipelineEvents` → live run updates (handleEvent ~2065); also legacy [PipelineArea.tsx](loom-ui/src/Pipeline/PipelineArea.tsx) |
| Processor Events (WS) | `/api/v1/processors/ws` (UI-side status) | Implemented | `subscribeProcessorEvents` → live processor state in CortexView |
| Node CRUD (in-editor) | add / delete / move / re-parameterize / edit edge type | Implemented | Local mutation, synced on save (PIPELINE_EDITOR.md §5.3) |
| Monitoring | cross-pipeline run stats | **Partial / mock** | [MonitoringArea.tsx](loom-ui/src/features/monitoring/MonitoringArea.tsx) renders pipeline-run KPIs/charts from **synthetic data** with hardcoded deltas (`delta="+8%"`), not real aggregated `listPipelineRuns` |

---

## Task: Correct the stale CRUD matrix in the pipeline editor spec

**Argumentation Summary:** [PIPELINE_EDITOR.md](PIPELINE_EDITOR.md) §5.1 states pipeline
Create and Delete are "❌ Not implemented" and §5.2 calls Update "The Only Implemented
Write". The actual editor implements create, delete, run, cancel, version restore, and
run-history — so the reference document actively misleads any agent or developer who
trusts it, and every downstream task derived from it starts from a false premise.

**Improvement Summary:** Bring §5 (and any other section that asserts write coverage)
into line with the shipped [PipelineEditor.tsx](loom-ui/src/features/pipeline/PipelineEditor.tsx),
so the spec documents what exists and only flags the genuine remaining gaps below.

```
Update PIPELINE_EDITOR.md so its CRUD matrix and prose match the code.

- §5.1 CRUD Matrix: mark Create (✅ `createPipeline`, handleCreate), Delete
  (✅ `deletePipeline` + confirm dialog), Run (already ✅), and add rows for
  Cancel Run, Version Restore, and Run-History read.
- §5.2: it is no longer "the only implemented write" — retitle to describe the
  save/update flow specifically.
- Add a short subsection documenting run history, run-item drill-down, version
  diff/restore, and the live pipeline-event subscription, cross-linking the
  component functions (RunHistory, PipelineInspector, PipelineVersionDiff).
- Verify every other "not implemented" claim in the file against the current
  source before leaving it in place.
```

**References:**
- [PIPELINE_EDITOR.md](PIPELINE_EDITOR.md) §5 (CRUD Matrix / Update Flow / Node CRUD / Run)
- [PipelineEditor.tsx](loom-ui/src/features/pipeline/PipelineEditor.tsx) — handleCreate, handleDelete, handleCancelRun, restore, RunHistory, PipelineInspector
- [pipelines.ts](loom-ui/src/api/pipelines.ts) — client functions

**Test Requirements:**
- Documentation-only change; no runtime test. Reviewer confirms each matrix cell against a grep of the named function in the source.

## Task: Expose Node Task (per-node execution) state in the run-item drill-down

**Argumentation Summary:** A `Pipeline Run` fans out into `Run Item`s, and each item into
`Node Task`s that are leased, retried, and dead-lettered
(`pipeline_node_task`, handled by `LeaseReaper`, `WebSocketNodeDispatcher`,
`PipelineRunRecovery`). The UI can list run items and their coarse `state`/`errorMessage`
([PipelineRunItemRecord](loom-ui/src/api/pipelines.ts)) but cannot see *which node* of the
graph failed, how many times it was retried, or whether it is dead-lettered. When a run
partially fails, an operator has no way to localize the failure to a node — the most
important debugging question for a pipeline goes unanswered in the UI.

**Improvement Summary:** Surface node-task state per run item so a failed run points at the
offending node. This is **backend-first**: no REST endpoint exposes node tasks today, so the
task includes adding one.

```
Two parts — backend then UI.

Backend (loom/services/rest):
- Add a read endpoint for node tasks of a run item, e.g.
  `GET /api/v1/pipelines/:uuid/runs/:runUuid/items/:itemUuid/tasks`
  returning nodeKind/nodeId, state (PENDING/LEASED/DONE/FAILED/DEAD_LETTER),
  attempt count, lease owner, timestamps, and error. Mirror the paging/response
  conventions of the existing run-items route in PipelineEndpoint.java. Read from
  DaoRunStateStore / the pipeline_node_task table.

UI (loom-ui):
- Add `listPipelineRunItemTasks(...)` to src/api/pipelines.ts.
- In the run-detail drill-down of PipelineEditor.tsx (openRunDetail), when an item is
  expanded, load its node tasks and render a per-node status list: node kind, state
  chip, attempt/retry count, error tooltip, dead-letter badge.
- Optionally highlight the failing node on the canvas when a run item is selected.
```

**References:**
- [DOMAIN.md](../DOMAIN.md) group 5 (Node Task)
- `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEndpoint.java`
- `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/DaoRunStateStore.java`
- [pipelines.ts](loom-ui/src/api/pipelines.ts), [PipelineEditor.tsx](loom-ui/src/features/pipeline/PipelineEditor.tsx)
- [RESTAPI.md](../RESTAPI.md) §3.4 (Pipeline Run), [WEBSOCKET.md](../WEBSOCKET.md)

**Test Requirements:**
- API-client test: `listPipelineRunItemTasks` GETs the new path with UUIDs encoded.
- Component test: a run item with a FAILED node renders that node's state + retry count and a dead-letter badge.
- e2e: trigger a run that fails one node → run detail localizes the failure to that node.

## Task: Drive handle colors and connection validation from the server content-type catalog

**Argumentation Summary:** The backend publishes a content-type catalog at
`GET /api/v1/pipeline/content-types` and the client already has `fetchContentTypes`, but
**nothing calls it**. The editor instead hardcodes data-type→color mapping and connection
compatibility (PIPELINE_EDITOR.md §7.3 Data Type Colors, §7.5 Connection Validation). When
the backend adds or renames a content type, the editor silently diverges: new types render
with a fallback color and connection validation cannot reason about them.

**Improvement Summary:** Load the content-type catalog at editor startup and use it as the
source of truth for handle colors and for `isValidConnection`, so the palette stays in sync
with the backend automatically.

```
- Call fetchContentTypes (src/api/nodeDescriptors.ts) once when the editor mounts,
  alongside fetchNodeDescriptors, and store it in state/context.
- Replace the hardcoded data-type color map (PipelineEditor.tsx §7.3 area) with a lookup
  keyed by the catalog; keep a neutral fallback for unknown types.
- Feed the catalog into the connection-validation logic (§7.5) so producer→consumer
  compatibility is decided by catalog metadata rather than a static table.
- Degrade gracefully if the fetch fails (fall back to the current static map, log a warning).
```

**References:**
- [nodeDescriptors.ts](loom-ui/src/api/nodeDescriptors.ts) — `fetchContentTypes` (unused)
- [PIPELINE_EDITOR.md](PIPELINE_EDITOR.md) §7.3 (Handle Colors), §7.5 (Connection Validation)
- [PipelineEditor.tsx](loom-ui/src/features/pipeline/PipelineEditor.tsx)
- [RESTAPI.md](../RESTAPI.md) §4.4

**Test Requirements:**
- API-client test: `fetchContentTypes` GETs `/pipeline/content-types`.
- Component test: a node handle whose type is only in the catalog gets the catalog color, not the fallback.
- Component test: connecting two handles the catalog marks incompatible is rejected.

## Task: Use loadPipelineRun for single-run refresh and run deep-linking

**Argumentation Summary:** Run detail is currently derived from the run **list** plus the
run-items call; the single-run endpoint `GET /api/v1/pipelines/:uuid/runs/:runUuid`
(`loadPipelineRun`) exists in the client but is never used. So an open run-detail panel only
refreshes when the whole list refetches, and there is no way to deep-link directly to a run
(e.g. from a notification or the monitoring view) without first loading and scanning the list.

**Improvement Summary:** Refresh an open run from its own endpoint and support opening a run
by id, giving live, focused status for the run the user is actually watching.

```
- When a run detail is open (openRunDetail), poll/refresh that single run via
  loadPipelineRun (throttled, e.g. while state is RUNNING) instead of relying on the
  list refetch — combine with the existing subscribePipelineEvents feed so the panel
  updates promptly on events and reconciles via loadPipelineRun.
- Support a route/param to open a pipeline + run by id: if the run is not in the loaded
  list, fetch it with loadPipelineRun and open its detail directly.
```

**References:**
- [pipelines.ts](loom-ui/src/api/pipelines.ts) — `loadPipelineRun` (unused)
- [PipelineEditor.tsx](loom-ui/src/features/pipeline/PipelineEditor.tsx) — openRunDetail (~2045), handleEvent (~2065)
- [RESTAPI.md](../RESTAPI.md) §3.4

**Test Requirements:**
- API-client test: `loadPipelineRun` GETs the single-run path with UUIDs encoded.
- Component test: opening a run id not present in the list fetches it via `loadPipelineRun` and renders its detail.
- Component test: a RUNNING run detail reconciles its status from `loadPipelineRun`.

## Task: Replace synthetic monitoring charts with real cross-pipeline run data

**Argumentation Summary:** [MonitoringArea.tsx](loom-ui/src/features/monitoring/MonitoringArea.tsx)
presents pipeline-run KPIs and success/failed bar charts, but the numbers are **synthetic** —
hardcoded deltas (`delta="+8%"`) and sample series rather than aggregated
`listPipelineRuns` results. A dashboard that shows invented figures is worse than none: it
looks authoritative while being fictional.

**Improvement Summary:** Aggregate real run outcomes across pipelines into the monitoring
KPIs and charts (success/failed/skipped counts over time), or, until an aggregation endpoint
exists, clearly label the panels as sample data.

```
- Preferred: add a lightweight aggregation source. Either a backend stats endpoint
  (e.g. GET /api/v1/pipelines/runs/stats) or a client-side roll-up that calls
  listPipelineRuns per pipeline and sums success/failure/skipped counts and recent
  activity. Feed the KPI cards and the success/failed bar chart from that.
- Remove hardcoded deltas; compute deltas from the real series or drop them.
- Interim: if real data is not yet wired, add an explicit "sample data" badge to each
  synthetic panel so it cannot be mistaken for live metrics.
```

**References:**
- [MonitoringArea.tsx](loom-ui/src/features/monitoring/MonitoringArea.tsx) (~line 139, 168–177)
- [pipelines.ts](loom-ui/src/api/pipelines.ts) — `listPipelineRuns`
- [DOMAIN.md](../DOMAIN.md) group 5 (Pipeline Run status + success/failure/skipped counts)

**Test Requirements:**
- Component test: KPI values and chart series derive from mocked run data, not constants.
- Component test: with no real data wired, each synthetic panel shows a "sample data" badge.

## Task: Add a global cross-pipeline run activity view

**Argumentation Summary:** Runs are only visible from *inside* the editor for the *currently
selected* pipeline (RunHistory in PipelineInspector). An operator watching the whole system
has no single place to see "what is running right now across all pipelines" or "which runs
failed recently" — they must click each pipeline in turn.

**Improvement Summary:** A global run activity view listing recent/active runs across all
pipelines, each row linking into the editor's run detail (reusing the deep-link task above).

```
- Add a runs list (new tab in Monitoring or a dedicated Activity view) that aggregates
  recent runs across pipelines (via the stats/roll-up from the monitoring task).
- Columns: pipeline name, run state, started, success/failed/skipped counts, duration.
- Row click deep-links to the pipeline + run detail (loadPipelineRun task).
- Live-update the list from subscribePipelineEvents so active runs advance in place.
```

**References:**
- [pipelines.ts](loom-ui/src/api/pipelines.ts) — `listPipelineRuns`
- [pipelineEvents.ts](loom-ui/src/api/pipelineEvents.ts) — `subscribePipelineEvents`
- [MonitoringArea.tsx](loom-ui/src/features/monitoring/MonitoringArea.tsx)

**Test Requirements:**
- Component test: the view lists runs from multiple pipelines and updates a run's state on a mocked pipeline event.
- e2e: starting a run makes it appear in the activity view and advance to a terminal state.

## Task: Retire or reconcile the legacy PipelineArea against the feature editor

**Argumentation Summary:** [PipelineArea.tsx](loom-ui/src/Pipeline/PipelineArea.tsx) (old
`src/Pipeline/` location, with its own `subscribePipelineEvents` usage and `flow-style.css`)
coexists with the actively-routed
[PipelineEditor.tsx](loom-ui/src/features/pipeline/PipelineEditor.tsx) — `AppShell` imports
the latter. The legacy component is dead but still maintained-looking code that can drift,
confuse contributors, and duplicate event-subscription logic.

**Improvement Summary:** Confirm the legacy area is unreferenced and remove it (and its CSS),
or, if it still holds behavior the feature editor lacks, migrate that behavior and then remove.

```
- Grep the app for imports of src/Pipeline/PipelineArea and flow-style.css; confirm
  only the features/pipeline editor is routed (AppShell.tsx).
- If PipelineArea has no unique behavior, delete src/Pipeline/ and its CSS and drop any
  now-dead helpers.
- If it has behavior the feature editor lacks (verify against §2/§3 of PIPELINE_EDITOR.md),
  port that first, then remove.
```

**References:**
- [PipelineArea.tsx](loom-ui/src/Pipeline/PipelineArea.tsx), `loom-ui/src/Pipeline/flow-style.css`
- [AppShell.tsx](loom-ui/src/layout/AppShell.tsx) (imports features/pipeline editor)

**Test Requirements:**
- Build + typecheck pass after removal (no dangling imports).
- Existing pipeline e2e tests still pass against the feature editor.
