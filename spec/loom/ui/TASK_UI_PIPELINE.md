# TASK_UI_PIPELINE — Pipeline / Processing (Cortex)

> Open UI work items for the Pipeline/Processing entities (Pipeline, Pipeline Version,
> Pipeline Run, Run Item, Node Task, Cortex Instance) and the node/editor surface,
> re-verified against `loom-ui/src` and `loom-ui/e2e` on 2026-08-01.
> Format follows [../../TASKS.template.md](../../TASKS.template.md).
>
> **Context:** [PIPELINE_EDITOR.md](PIPELINE_EDITOR.md) (editor spec) · [LOOM_UI.md](LOOM_UI.md) ·
> [../RESTAPI.md](../RESTAPI.md) §3.4 / §4.4 · [../WEBSOCKET.md](../WEBSOCKET.md)
>
> **Ordering:** Task 4 depends on Task 3 (deep-linking). Task 5 is independent.
> Tasks 1 (node-task state) and 2 (pause/resume) are closed — see the outcome records.
>
> **Test conventions:** "component test" in this tree means a **mocked Playwright spec** under
> `loom-ui/e2e/*-mocked.spec.ts` (every REST call intercepted, no server); pure logic is covered by
> node-env vitest next to the module. There is no RTL/jsdom setup — see [LOOM_UI.md](LOOM_UI.md) §2.

## Coverage Matrix

| Entity / Surface | REST/WS Operation | UI Status | Where / Gap |
|------------------|-------------------|-----------|-------------|
| Pipeline | `GET /pipelines` · list | Implemented | `listPipelines` → pipeline sidebar, [PipelineEditor.tsx](../../../loom-ui/src/features/pipeline/PipelineEditor.tsx) |
| Pipeline | `GET /pipelines/:uuid` · get | Not needed | `loadPipeline` unused — the list response carries the flattened definition |
| Pipeline | `POST /pipelines` · create / `POST /:uuid` · update / `DELETE /:uuid` | Implemented | `createPipeline`, `updatePipeline`, `deletePipeline` + confirm dialog |
| Pipeline Run | `POST /pipelines/:uuid/run` · trigger | Implemented | `runPipeline`; `dispatched: false` surfaces a toast |
| Pipeline Run | `GET /pipelines/:uuid/runs` · list | Implemented | `listPipelineRuns` → run history in the inspector |
| Pipeline Run | `GET /pipelines/:uuid/runs/:runUuid` · get | **Missing** | `loadPipelineRun` ([pipelines.ts:331](../../../loom-ui/src/api/pipelines.ts)) has no caller → **Task 3** |
| Pipeline Run | `POST /…/runs/:runUuid/cancel` | Implemented | `cancelPipelineRun` |
| Pipeline Run | `POST /…/runs/:runUuid/pause`, `/resume` | Implemented | `pausePipelineRun` / `resumePipelineRun` + banner and run-row controls; reconciled from `RUN_PAUSED`/`RUN_RESUMED` |
| Run Item | `GET /…/runs/:runUuid/items` · list | Implemented | `listPipelineRunItems` → run-detail drill-down |
| Node Task | `GET /…/runs/:runUuid/items/:itemUuid/tasks` | Implemented | `listPipelineRunItemTasks` → per-node results on the canvas and in the sidebar Results tab |
| Pipeline Version | `GET /versions`, `GET /versions/:n`, `POST /versions/:n/restore` | Implemented | `listPipelineVersions` / `loadPipelineVersion` / `restorePipelineVersion` + [PipelineVersionDiff.tsx](../../../loom-ui/src/features/pipeline/PipelineVersionDiff.tsx) |
| Cortex Instance | `GET /processors`, `/:uuid`, `PUT /:uuid/restrictions`, `DELETE /:uuid` | Implemented | [CortexView.tsx](../../../loom-ui/src/features/cortex/CortexView.tsx) (instances self-register over WS — no create UI) |
| Node Descriptors | `GET /pipeline/node-descriptors` · list | Implemented | `fetchNodeDescriptors` → [NodeRegistryContext.tsx](../../../loom-ui/src/context/NodeRegistryContext.tsx); the same response also carries `contentTypes` |
| Node Descriptors | `GET /pipeline/node-descriptors/:kind` | Not needed | `fetchNodeDescriptor(kind)` unused — the bulk list covers every caller |
| Content Types | `GET /pipeline/content-types` | Not needed | `fetchContentTypes` unused **by design**: the vocabulary arrives bundled in the node-descriptors response and flows through `NodeRegistryContext` |
| Pipeline Events (WS) | `/pipelines/events/ws` | Implemented | `subscribePipelineEvents` → live run/node state on the canvas |
| Processor Events (WS) | `/processors/ws` | Implemented | `subscribeProcessorEvents` → live processor state in CortexView |
| Node CRUD (in-editor) | add / delete / move / re-parameterize / edit branch | Implemented | Local mutation, synced on save (PIPELINE_EDITOR.md §5.3) |
| Monitoring | `GET /pipelines/runs/stats` | Implemented | `loadPipelineRunStats` → run KPI + chart in [MonitoringArea.tsx](../../../loom-ui/src/features/monitoring/MonitoringArea.tsx); other panels still synthetic → [TASK_UI_SYSTEM.md](TASK_UI_SYSTEM.md) Task 2 |

---

## Task 3: Use `loadPipelineRun` for single-run refresh and run deep-linking

**Argumentation Summary:** Run detail is derived from the run **list** plus the run-items call;
`loadPipelineRun` ([pipelines.ts:331](../../../loom-ui/src/api/pipelines.ts)) is defined but has
no caller anywhere in `src/`. An open run-detail panel therefore only refreshes when the whole
list refetches, and there is no way to deep-link to a run (from a notification, the monitoring
view, or a shared URL) without first loading and scanning the list.

**Improvement Summary:** Refresh the focused run from its own endpoint and support opening a run
by id.

```
- While a run detail is open and the run is non-terminal, reconcile it via loadPipelineRun on a
  throttle, in addition to the subscribePipelineEvents feed (events for promptness, the fetch
  for authority).
- Add a route/query param that opens pipeline + run by id; if the run is not in the loaded list,
  fetch it with loadPipelineRun and open its detail directly.
- 404 → render a "run no longer available" empty state rather than an endless spinner.
```

**References:** [pipelines.ts](../../../loom-ui/src/api/pipelines.ts) (`loadPipelineRun`) ·
[PipelineEditor.tsx](../../../loom-ui/src/features/pipeline/PipelineEditor.tsx) (run detail, event handler) ·
[../RESTAPI.md](../RESTAPI.md) §3.4

**Test Requirements:**
- vitest: `loadPipelineRun` GETs the single-run path with uuids encoded.
- Mocked e2e: navigating to a run id that is **not** in the mocked list still renders its detail
  (the spec asserts the single-run route was hit); a RUNNING run's detail updates from a second
  `loadPipelineRun` response.

---

## Task 4: Add a global cross-pipeline run activity view

**Argumentation Summary:** Runs are visible only from *inside* the editor for the *selected*
pipeline. An operator watching the whole system has no place to answer "what is running right
now" or "which runs failed today" without clicking every pipeline in turn. The monitoring view
already aggregates run **counts** via `loadPipelineRunStats` but lists no individual runs.

**Improvement Summary:** A run activity list spanning all pipelines, each row deep-linking into
the editor's run detail.

```
- Add a runs list (new tab in MonitoringArea or a dedicated Activity view) aggregating recent
  runs across pipelines. Columns: pipeline name, state, started, success/failed/skipped, duration.
- Row click deep-links to pipeline + run detail (needs Task 3).
- Live-update rows from subscribePipelineEvents so active runs advance in place.
- If listing across pipelines needs a server-side roll-up (today's list route is per pipeline),
  state that as a backend prerequisite before building fan-out-per-pipeline fetching.
```

**References:** [pipelines.ts](../../../loom-ui/src/api/pipelines.ts) (`listPipelineRuns`, `loadPipelineRunStats`) ·
[pipelineEvents.ts](../../../loom-ui/src/api/pipelineEvents.ts) ·
[MonitoringArea.tsx](../../../loom-ui/src/features/monitoring/MonitoringArea.tsx) · [TASK_UI_SYSTEM.md](TASK_UI_SYSTEM.md) Task 2

**Test Requirements:**
- Mocked e2e: the view lists runs from two pipelines and advances one run's state on an injected
  pipeline event; a row click lands on that run's detail.

---

## Task 5: Delete the unreachable legacy `src/` trees

**Argumentation Summary:** `loom-ui/index.html` mounts `src/main.tsx`, and `main.tsx` →
`AppShell.tsx` routes only `src/features/*`. The pre-feature tree is still on disk and reachable
only from the dead entry point `src/index.js`: `src/Pipeline/` (`PipelineArea.tsx` +
`flow-style.css`, with its own duplicate `subscribePipelineEvents` usage), plus `src/Admin/`,
`src/Asset/`, `src/Content/`, `src/Dashboard/`, `src/User/`, `src/Welcome/`, `src/Login/`. It is
dead code that looks maintained: it drifts from the shipped editor and misleads contributors and
agents searching for the pipeline canvas.

**Improvement Summary:** Confirm unreachability and delete the legacy trees together with
`src/index.js`.

```
- Verify with `rg -n "Pipeline/PipelineArea|src/index.js" loom-ui/src loom-ui/index.html
  loom-ui/vite.config.*` that nothing but src/index.js references them.
- Diff PipelineArea.tsx against features/pipeline/PipelineEditor.tsx for unique behaviour
  (PIPELINE_EDITOR.md §2/§3 is the reference); port anything real first — expect nothing.
- Delete src/index.js, src/Pipeline/, src/Admin/, src/Asset/, src/Content/, src/Dashboard/,
  src/User/, src/Welcome/, src/Login/ and any helper left with no importer.
- Keep src/mock/ for now: MonitoringArea and WorkflowView still import from it
  (TASK_UI_SYSTEM.md Task 2, TASK_UI_AI_ML.md).
```

**References:** [main.tsx](../../../loom-ui/src/main.tsx) · [AppShell.tsx](../../../loom-ui/src/layout/AppShell.tsx) ·
`loom-ui/src/Pipeline/PipelineArea.tsx` · [LOOM_UI.md](LOOM_UI.md)

**Test Requirements:**
- `npx tsc --noEmit` and `npm run build` clean (no dangling imports).
- The full mocked Playwright suite still green — in particular the 12 `e2e/pipeline-*.spec.ts`.

---

## Closed items (outcome records)

| Closed task | Landed in |
|---|---|
| Drive handle colors and connection validation from the served content-type catalog | [contentTypes.ts](../../../loom-ui/src/features/pipeline/contentTypes.ts) (`contentTypeColor`, `findContentType`, `isAssignable`) + `contentTypes.test.ts`; catalog served with the node descriptors through [NodeRegistryContext.tsx](../../../loom-ui/src/context/NodeRegistryContext.tsx) — no hardcoded type table remains |
| Typed, named ports on handles (handle id = port id) | [portResolvers.ts](../../../loom-ui/src/features/pipeline/portResolvers.ts) (mirrors the Java `NodePortResolver`s) + `portResolvers.test.ts`; `isValidConnection` at [PipelineEditor.tsx:1672](../../../loom-ui/src/features/pipeline/PipelineEditor.tsx) rejects on type, duplicate, cardinality and XOR-group violations with a reason |
| Persist ports and filter branch on save | `getGraphJson` writes `sourcePort`/`targetPort`/`branch` (PipelineEditor.tsx ~1850); fixed the old `edgeType` field nothing read and the handle loss on reload |
| Pipeline create + delete | `createPipeline` / `deletePipeline` + confirm dialog; `e2e/pipeline-crud-mocked.spec.ts` |
| Run cancel | `cancelPipelineRun`; `e2e/pipeline-run-cancel-mocked.spec.ts` |
| Node-task state and outputs (was Task 1) | New `GET /api/v1/pipelines/:uuid/runs/:runUuid/items/:itemUuid/tasks` in `PipelineEndpoint.java` returning `PipelineNodeTaskRecord` — node id/kind, state, attempt/maxAttempts, lease owner, timestamps, error **and the `outputs` payload keyed by output port id**. Unpaged by design. Mirrored into `LoomHttpClientImpl`, the Python client and `openapi.json`. UI: `listPipelineRunItemTasks` → `NodeResultStrip` on the node card and a real **Results** tab replacing the hardcoded fake log. Tests: `PipelineNodeTaskEndpointTest` (8), `resultRenderers.test.ts` (27), `e2e/pipeline-node-results-mocked.spec.ts` (5) |
| Run pause / resume (was Task 2) | `pausePipelineRun` / `resumePipelineRun` in [pipelines.ts](../../../loom-ui/src/api/pipelines.ts); banner + run-row controls that treat `PAUSED` as non-terminal (Cancel stays offered). New `RUN_PAUSED` / `RUN_RESUMED` frames were added to `PipelineEventType` and are broadcast by `PipelineEndpointService`, so a pause from the CLI or another tab flips every open editor. Tests: `e2e/pipeline-run-pause-mocked.spec.ts`, `src/api/pipelineRunControls.test.ts`, `PipelineRunPauseEndpointTest` |
| Live per-node counters | `NODE_STATS` was received and its counters discarded; they now populate `nodeStats` and render as a node footer behind the `pipeline-debug-toggle` (debug mode). `activeCount` is now authoritative for the pulse. `e2e/pipeline-events-mocked.spec.ts` |
| Run lifecycle frames actually emitted | `PIPELINE_STARTED` / `PIPELINE_COMPLETED` were declared in `PipelineEventType` but **never broadcast by Loom**, so the run banner only refreshed on a manual action. Now emitted from `dispatchRun`, `engine.onCompletion`, `cancelRun` and the undispatchable-run path |
| Run history + run-item drill-down | `listPipelineRuns` / `listPipelineRunItems`; `e2e/pipeline-run-mocked.spec.ts`, `e2e/pipeline-run-items-mocked.spec.ts` |
| Version history, diff and restore | [PipelineVersionDiff.tsx](../../../loom-ui/src/features/pipeline/PipelineVersionDiff.tsx) + `pipelineDiff.ts`; `e2e/pipeline-versions-mocked.spec.ts`, `e2e/pipeline-diff-backend.spec.ts` |
| Per-node affinity editing | `affinity` on the definition node, surfaced in the inspector; `e2e/pipeline-affinity-mocked.spec.ts` |
| Live run/node state on the canvas | `subscribePipelineEvents` → active-node + last-result painting; `e2e/pipeline-events-mocked.spec.ts` |
| Cortex instance list, restrictions and forget | [CortexView.tsx](../../../loom-ui/src/features/cortex/CortexView.tsx); `e2e/cortex-mocked.spec.ts` |
| Pipeline-run KPI + chart from the stats endpoint | `loadPipelineRunStats` + [runMetrics.ts](../../../loom-ui/src/features/monitoring/runMetrics.ts) (`runMetrics.test.ts`) |

_Git HEAD revision: `827cd2cb`_
_Last updated: 2026-08-04 (closed Tasks 1 and 2; recorded the NODE_STATS counters and the previously-unemitted run lifecycle frames)_
