# MetaLoom — CRUD Coverage Tasks: Pipeline / Processing (Cortex)

> CRUD & lifecycle gaps between the REST API and the Loom UI (`loom-ui/`) for the
> Pipeline domain: **Pipeline, Pipeline Version, Pipeline Run, Run Item, Node Task, Cortex Instance**.
> Editor/affinity/node-restriction gaps live in [TASKS.md](TASKS.md) and are cross-referenced, not duplicated.
> Format follows [../../TASKS.template.md](../../TASKS.template.md). See [../DOMAIN.md](../DOMAIN.md).

## Coverage Summary

| Element | Create | Read / List | Update | Delete / Lifecycle | E2E |
|---|---|---|---|---|---|
| **Pipeline** | ✅ REST+UI | ✅ REST+UI | ✅ REST+UI | ✅ REST+UI (delete) | ✅ create/clone/delete/load mocked+backend |
| **Pipeline Version** | ✅ (via save) | ✅ list+load+diff | n/a (immutable) | ✅ restore (copy-forward) | ✅ versions mocked+backend, diff |
| **Pipeline Run** | ✅ run/trigger REST+UI | ⚠️ list only (aggregate counts); no single-run detail | ❌ no cancel/pause — `UPDATE_PIPELINE_RUN` perm unused | ❌ no cancel/abort/delete (`DELETE_PIPELINE_RUN` unused) | ⚠️ history render covered; **run-trigger POST /run never asserted** |
| **Run Item** (`pipeline_run_item`) | engine-only | ❌ **no REST at all** — `PipelineRunItemDao.loadPageByRun` unused by REST; `READ_PIPELINE_RUN` unused | — | — | ❌ none |
| **Node Task** (`pipeline_node_task`) | engine-only | ❌ **no REST at all** — `PipelineNodeTaskDao.loadPageByRun`/`loadByItem` unused by REST | — | — | ❌ none |
| **Cortex Instance** | WS self-register (by design) | ✅ list; `getProcessor` single-read client **unused** in UI | ✅ restrictions PUT REST+UI | ✅ forget DELETE REST+UI | ✅ cortex mocked+backend (restrictions, forget) |
| **Monitoring screen** | — | ❌ renders `mock/data` `METRICS`, not real run/execution data | — | — | ❌ none |

Pipeline, Pipeline Version and Cortex Instance CRUD are fully wired end-to-end and
covered by e2e. The genuine gaps below concern **Pipeline Run lifecycle
(cancel + run-trigger e2e)** and the **durable execution state** (`pipeline_run_item`,
`pipeline_node_task`) — persisted by the engine (`DaoRunStateStore`) with query-ready
DAOs but reachable by **no REST route**, so the UI can never answer "where is this item?"
or "which node failed?" — plus the **Monitoring** screen still showing mock KPIs.

---

## Task 1: Expose Pipeline Run Items over REST and add a run-detail drill-down in the UI

**Argumentation Summary:** `V2.31__add_pipeline_execution_state.sql` created
`pipeline_run_item` explicitly so a run's per-item progress "survives a restart and
becomes queryable", guarded by the existing `READ_PIPELINE_RUN` permission. The data
layer is ready — `PipelineRunItemDao.loadPageByRun(runUuid, from, pageSize, filters,
sortBy, sortOrder)` exists and its javadoc says it is "for anything user-facing" — yet
**no REST endpoint calls it**, `READ_PIPELINE_RUN` is referenced by no endpoint/service,
and `PipelineEndpoint` registers only `GET /pipelines/:uuid/runs` (an aggregate list;
`listRuns` returns `PipelineRun` counts only). The UI's `listPipelineRuns` therefore
shows only success/failure counters (`RunHistory` panel in `PipelineEditor.tsx`); a user
cannot drill into a run to see the discovered items or their state. This is distinct from
the editor/affinity work in [TASKS.md](TASKS.md), which is about *authoring* graphs, not
*inspecting* run state.

**Improvement Summary:** Add REST routes for a single run and its items, a
`pipelines.ts` client, and a run-detail view reachable from the `RunHistory` panel.

```
SERVER (loom/services/rest):
1. PipelineEndpoint.register(): add
     GET /pipelines/:uuid/runs/:runUuid           -> single PipelineRun (detail)
     GET /pipelines/:uuid/runs/:runUuid/items      -> paged run items
   secure() both paths (mirror the existing runs paths at lines 51-54).
2. PipelineEndpointService: add loadRun(...) and listRunItems(...) guarded by
   READ_PIPELINE_RUN (the permission the migration names but nothing enforces),
   backed by pipelineRunItemDao.loadPageByRun(...). Add a modelBuilder.toPipelineRunItemList.

UI (loom-ui/src/api/pipelines.ts):
3. Add PipelineRunItemRecord (uuid, itemSeq, mediaPath, sha512, sizeBytes, state,
   errorMessage) + listPipelineRunItems(token, pipelineUuid, runUuid) and
   loadPipelineRun(token, pipelineUuid, runUuid). Degrade to [] on 404 like
   listPipelineRuns already does.
4. features/pipeline/PipelineEditor.tsx: make each RunHistory row (function
   RunHistory, ~line 461) open a run-detail panel/drawer listing items with a
   state chip (PENDING/RUNNING/SUCCESS/FAILED/SKIPPED) and the error message.
```

**References:**
- `loom/db/flyway/.../db/migration/V2.31__add_pipeline_execution_state.sql` (`pipeline_run_item`)
- `loom/db/api/.../db/model/pipeline/PipelineRunItemDao.java` (`loadPageByRun`, `countByRunAndState`)
- `loom/services/rest/.../endpoint/impl/PipelineEndpoint.java` (only `/runs` list route)
- `loom/services/rest/.../service/impl/PipelineEndpointService.java` (`listRuns`; `pipelineRunItemDao` injected but only used by `DaoRunStateStore`)
- [pipelines.ts](../../../loom-ui/src/api/pipelines.ts) (`listPipelineRuns`, `PipelineRunRecord`)
- [PipelineEditor.tsx](../../../loom-ui/src/features/pipeline/PipelineEditor.tsx) (`RunHistory`, `loadRuns`)

**Test Requirements:**
- Server test: `GET /runs/:runUuid/items` returns the persisted items for a run, is
  paged/filterable, and rejects a caller lacking `READ_PIPELINE_RUN` with 403.
- E2E (mocked, new `pipeline-run-items-mocked.spec.ts`): opening a run row shows its items
  with the correct state chips and error text; a run with no items shows an empty state.

---

## Task 2: Expose Node Tasks (per-node execution / retry / dead-letter state) over REST and surface them in the UI

**Argumentation Summary:** `pipeline_node_task` records "one node executed against one
item — leased, retried, dead-lettered" (attempt/max_attempts/leased_by/lease_expires_at/
duration_ms/error_message), the state that answers "which node failed and why?". The DAO
is query-ready — `PipelineNodeTaskDao.loadByItem(itemUuid)`, `loadPageByRun(...)`,
`countByRunAndState(...)` — but, like run items, **no REST endpoint reads it**; the dao is
injected into `PipelineEndpointService` yet only consumed by `DaoRunStateStore`/`LeaseReaper`
for engine bookkeeping. Consequently the UI has no way to show a per-item node breakdown or a
retry/dead-letter view. Live `NODE_STARTED/NODE_COMPLETED/NODE_FAILED` events over the
events socket ([pipelineEvents.ts](../../../loom-ui/src/api/pipelineEvents.ts)) animate the
canvas transiently, but nothing is queryable after the run. Builds on Task 1's run-detail plumbing.

**Improvement Summary:** Add a REST route for an item's node tasks, a client fn, and a
per-item task breakdown in the run-detail view, including attempt/retry and dead-letter status.

```
SERVER:
1. PipelineEndpoint.register(): add
     GET /pipelines/:uuid/runs/:runUuid/items/:itemUuid/tasks  -> node tasks for an item
   (and optionally GET .../runs/:runUuid/tasks paged for a run-wide failure view).
   Guard with READ_PIPELINE_RUN in PipelineEndpointService via
   pipelineNodeTaskDao.loadByItem(...) / loadPageByRun(...).

UI:
2. pipelines.ts: PipelineNodeTaskRecord (nodeId, nodeKind, state, attempt, maxAttempts,
   durationMs, errorMessage, finished) + listRunItemTasks(...).
3. PipelineEditor.tsx run-detail (Task 1): expand an item to list its node tasks with
   state, attempt "n/max", duration and error; flag dead-lettered tasks
   (state=FAILED && attempt>=maxAttempts) distinctly.
```

**References:**
- `loom/db/flyway/.../V2.31__add_pipeline_execution_state.sql` (`pipeline_node_task`)
- `loom/db/api/.../db/model/pipeline/PipelineNodeTaskDao.java` (`loadByItem`, `loadPageByRun`, `countByRunAndState`)
- `loom/services/rest/.../service/impl/PipelineEndpointService.java` (`pipelineNodeTaskDao` unused by any route)
- [pipelineEvents.ts](../../../loom-ui/src/api/pipelineEvents.ts) (transient live events only)
- [PipelineEditor.tsx](../../../loom-ui/src/features/pipeline/PipelineEditor.tsx)

**Test Requirements:**
- Server test: node-task route returns tasks for an item ordered/paged, surfaces
  attempt/lease/error columns, and enforces `READ_PIPELINE_RUN`.
- E2E (mocked): a failed item expands to show the failing node task with its error and
  retry count; a dead-lettered task is visually distinguished.

---


## Task 4: Cover the pipeline run-trigger (`POST /run`) path with e2e

**Argumentation Summary:** The run-trigger client `runPipeline` and `handleRun`
(`PipelineEditor.tsx` line ~2217, dispatching `POST /pipelines/:uuid/run`) are fully wired,
but **no e2e exercises them**: a grep across `loom-ui/e2e` finds route mocks only for
`GET .../runs` (history) and assertions only for the run *banner* driven by live
`PIPELINE_*` events (`pipeline-events-mocked.spec.ts`); nothing clicks Run and asserts the
`POST /run` request or the "run dispatched" toast. Run trigger is the primary Pipeline Run
create action, so this is a real coverage gap (adjacent to the unit-test gap in
[TASKS.md](TASKS.md) Task 13, but that is Vitest of pure functions, not this flow).

**Improvement Summary:** Add an e2e that mocks `POST /run` and verifies the dispatch
request, the success toast, and the follow-up history refresh.

```
1. New loom-ui/e2e/pipeline-run-mocked.spec.ts (pattern per pipeline-crud-mocked.spec.ts):
   - route POST **/api/v1/pipelines/*/run -> PipelineRunResponse { dispatched:true,
     workOrderId, processorNodeId }.
   - click the Run control; assert the POST fired with the expected dryRun body,
     the runDispatched toast shows, and loadRuns re-fetches GET /runs afterwards.
2. Add a negative case: dispatched:false / a 4xx surfaces an error toast, not a silent no-op.
```

**References:**
- [PipelineEditor.tsx](../../../loom-ui/src/features/pipeline/PipelineEditor.tsx) (`handleRun` ~2217, `runPipeline`)
- [pipelines.ts](../../../loom-ui/src/api/pipelines.ts) (`runPipeline`, `PipelineRunResponse`)
- `loom-ui/e2e/pipeline-crud-mocked.spec.ts`, `loom-ui/e2e/pipeline-events-mocked.spec.ts` (existing patterns; neither asserts POST /run)

**Test Requirements:**
- The new spec asserts the `POST /run` request body and both success and failure UX paths.

---

## Task 5: Wire the Monitoring area to real pipeline run / execution data

**Argumentation Summary:** The `/monitoring` route renders `MonitoringArea.tsx`, whose
KPIs come from `import { METRICS } from "../../mock/data"` — static mock numbers, not the
live system. Yet the data to populate it now exists durably: `pipeline_run` aggregate
counts (already reachable via `listPipelineRuns`) plus the per-item/per-node state from
Tasks 1–2, and processor health from `listProcessors`. So the monitoring screen presents
fabricated figures while real execution metrics sit unqueried. (The pipeline canvas's live
metrics come from the events socket; monitoring is a separate durable-aggregate surface.)

**Improvement Summary:** Replace the mock feed with real aggregates from the runs/items/
tasks and processor APIs, keyed off the run-state endpoints added in Tasks 1–2.

```
1. MonitoringArea.tsx: drop the METRICS mock import. Derive KPIs from real sources:
   - throughput / success-failure from pipeline runs (listPipelineRuns across pipelines,
     or a new aggregate endpoint) and run-item state counts (Task 1
     countByRunAndState-backed summary).
   - node failure / dead-letter counts from node-task state (Task 2).
   - fleet health (online workers, CPU/GPU load) from listProcessors + processor events.
2. Add loading/empty/error states; keep the existing KPICard layout. If a server-side
   aggregate endpoint is preferable to client fan-out, add GET /pipelines/runs/summary
   (guarded by READ_PIPELINE_RUN) rather than N per-pipeline calls.
```

**References:**
- [MonitoringArea.tsx](../../../loom-ui/src/features/monitoring/MonitoringArea.tsx) (`METRICS` mock import, `KPICard`)
- `loom-ui/src/mock/data.ts` (`METRICS`)
- [pipelines.ts](../../../loom-ui/src/api/pipelines.ts) (`listPipelineRuns`), [processors.ts](../../../loom-ui/src/api/processors.ts) (`listProcessors`)
- Tasks 1–2 (run-item / node-task aggregates)

**Test Requirements:**
- E2E (mocked): monitoring KPIs reflect mocked run/processor responses (not the constant
  mock values) and show loading/empty states; a failed fetch renders an error, not stale mock data.

---

## Task 6: Use (or remove) the unused single-processor read client

**Argumentation Summary:** `processors.ts` exports `getProcessor(token, nodeId)` for
`GET /processors/:uuid` (which the backend serves, including persisted-but-offline
instances), but no component imports it — `CortexView.tsx` only uses `listProcessors`,
`updateProcessorRestrictions` and `forgetProcessor`. So the REST single-read is exposed
but the UI never offers a per-worker detail view, and a dead client fn accrues. Minor, but
a verified REST-op/UI mismatch for the Cortex Instance element. (Restriction editing and
forget — the substantive Cortex CRUD — are already covered end-to-end; see also
[TASKS.md](TASKS.md) for the node-restriction authoring work.)

**Improvement Summary:** Either add a Cortex worker detail view backed by `getProcessor`
(fuller status/history/restriction editor), or delete the unused client to avoid drift.

```
1. Preferred: CortexView.tsx — clicking a worker card opens a detail drawer that calls
   getProcessor(nodeId) and shows full SystemStatusInfo, lastSeen, persisted badge, and
   the whitelist/blacklist editor, refreshing from the single-read endpoint.
2. Otherwise: remove getProcessor from processors.ts so the client surface matches what
   the UI actually calls.
```

**References:**
- [processors.ts](../../../loom-ui/src/api/processors.ts) (`getProcessor`, unused)
- [CortexView.tsx](../../../loom-ui/src/features/cortex/CortexView.tsx) (imports list/update/forget only)
- `loom/services/rest/.../endpoint/impl/ProcessorEndpoint.java` (`GET /:uuid` served)

**Test Requirements:**
- If a detail view is added: e2e (mocked) opening a worker calls `GET /processors/:uuid`
  and renders its status/restrictions. If removed: no dead export remains (unit/lint).
</content>
