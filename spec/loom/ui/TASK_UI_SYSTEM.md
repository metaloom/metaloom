# TASK_UI_SYSTEM — System

> Open UI work items for the System surfaces (Loom singleton, health, OpenAPI, processors,
> monitoring), re-verified against `loom-ui/src` on 2026-08-01.
> Format follows [../../TASKS.template.md](../../TASKS.template.md).
>
> **Context:** [LOOM_UI.md](LOOM_UI.md) · [../RESTAPI.md](../RESTAPI.md) §4 (Monitoring) ·
> [../DOMAIN.md](../DOMAIN.md) group 7 · [../EVENTBUS.md](../EVENTBUS.md)
> REST routes read from `RESTInfoEndpoint.java`, `HealthEndpoint.java`, `ProcessorEndpoint.java`,
> `NodeDescriptorEndpoint.java`.
>
> **Ordering:** Task 2 is the higher-impact of the two (the monitoring dashboard is one of only
> two remaining mock-backed views in the app); Task 1 is small and independent.
>
> **Test conventions:** "component test" means a **mocked Playwright spec** under
> `loom-ui/e2e/*-mocked.spec.ts` (`maintenance-mocked.spec.ts`, `monitoring-mocked.spec.ts` are the
> existing ones); pure logic uses node-env vitest. No RTL/jsdom exists in this repo.

## Coverage Matrix

| Surface | REST Operation | UI Status | Where / Gap |
|---------|----------------|-----------|-------------|
| **Loom (singleton)** | `GET /api/v1` — version, `dbRevision`, `lastUsed` | Implemented | [api/info.ts](../../../loom-ui/src/api/info.ts) → chips in [MaintenanceView.tsx](../../../loom-ui/src/features/maintenance/MaintenanceView.tsx) (`info-db-revision`, `info-last-used`, `health-version`) |
| **Health** | `GET /api/v1/health` | Implemented (as far as REST allows) | [api/health.ts](../../../loom-ui/src/api/health.ts) → MaintenanceView. Only `database` becomes a real card; Storage/Memory/Workers are gated as "No metric available" because no REST field backs them — accurate, not a UI gap |
| **OpenAPI** | `GET /api/v1/openapi` — YAML | **Missing** | No consumer at all (`rg -i openapi loom-ui/src` is empty) → **Task 1** |
| **Processor Status** | `GET /api/v1/processors`, `/:nodeId` | Implemented | [api/processors.ts](../../../loom-ui/src/api/processors.ts) → [CortexView.tsx](../../../loom-ui/src/features/cortex/CortexView.tsx) (see [TASK_UI_PIPELINE.md](TASK_UI_PIPELINE.md)) |
| **Node Descriptors** | `GET /api/v1/pipeline/node-descriptors` (+ content types) | Implemented | [api/nodeDescriptors.ts](../../../loom-ui/src/api/nodeDescriptors.ts) → [NodeRegistryContext.tsx](../../../loom-ui/src/context/NodeRegistryContext.tsx) |
| **Metrics (dashboard)** | *(no `GET /api/v1/metrics`; only `GET /api/v1/pipelines/runs/stats`)* | **Partial** | [MonitoringArea.tsx](../../../loom-ui/src/features/monitoring/MonitoringArea.tsx) drives the pipeline-run KPI + chart from `loadPipelineRunStats`; ingestion, latency, storage, task backlog, chat usage and annotations still come from `src/mock/data.ts` (`METRICS`, 8 call sites) behind three "Sample data" badges → **Task 2** |

**Headline finding:** info/health/processors/node-descriptors are all real API. The two remaining
mock-backed views in the whole UI are `MonitoringArea` (`METRICS`) and `WorkflowView`
(`FACE_CLUSTERS`/`PERSONS`, tracked in [TASK_UI_AI_ML.md](TASK_UI_AI_ML.md)).

---

## Task 1: Add an OpenAPI spec download to the maintenance/system surface

**Argumentation Summary:** `GET /api/v1/openapi` (`RESTInfoEndpoint`) generates and serves the
full API contract as YAML, and nothing in the UI links to, downloads or mentions it. Integrators
and operators have no in-product path to the API contract, and the endpoint's output is never
exercised from the app.

**Improvement Summary:** A "Download OpenAPI (YAML)" action in the MaintenanceView system-info
block, next to the version/dbRevision/lastUsed chips.

```
- Add getOpenApiSpec(token) to loom-ui/src/api/info.ts: GET `${API_BASE_URL}/openapi` with
  Accept: text/yaml and the bearer header; return await res.text(). Do NOT route it through the
  JSON handleResponse in that file — the body is YAML.
- In MaintenanceView.tsx's system-info block add a button (data-testid="openapi-download") that
  calls it and triggers a download: new Blob([text], {type:"text/yaml"}) → object URL →
  <a download="loom-openapi.yaml"> → revokeObjectURL.
- Error path: reuse the view's existing error/toast handling; do not hold the payload in React
  state (it is large) beyond the download.
```

**References:** `loom/services/rest/.../endpoint/impl/RESTInfoEndpoint.java` (`/api/v1/openapi`) ·
[../RESTAPI.md](../RESTAPI.md) §4.2 · [api/info.ts](../../../loom-ui/src/api/info.ts) ·
[MaintenanceView.tsx](../../../loom-ui/src/features/maintenance/MaintenanceView.tsx)

**Test Requirements:**
- vitest: `getOpenApiSpec` GETs `/openapi`, sends the Accept header, resolves to text.
- `e2e/maintenance-mocked.spec.ts` extended: clicking the button hits the mocked route and starts
  a download (assert via Playwright's `download` event).

---

## Task 2: Finish de-mocking the Monitoring dashboard

**Argumentation Summary:** [MonitoringArea.tsx](../../../loom-ui/src/features/monitoring/MonitoringArea.tsx)
still imports `METRICS` from `src/mock/data.ts` and uses it for the ingestion, latency, storage,
task-backlog, chat-usage and annotation panels (8 call sites). Only the pipeline-run KPI and chart
are live (`loadPipelineRunStats` + [runMetrics.ts](../../../loom-ui/src/features/monitoring/runMetrics.ts)),
and only three panels carry a "Sample data" badge. There is no `MetricsEndpoint` in
`loom/services/rest`, so the general `GET /api/v1/metrics` LOOM_UI.md describes does not exist —
but two tiles *can* be backed today (health, processor/worker count) and are not, and several
synthetic panels are still unbadged.

**Improvement Summary:** Wire the tiles that have real sources now, badge every remaining
synthetic panel, and replace the mock wholesale once a metrics endpoint lands.

```
Short term (no backend work), in loom-ui/src/features/monitoring/MonitoringArea.tsx:
- Add a health tile from GET /api/v1/health (src/api/health.ts) and a worker tile from
  GET /api/v1/processors (src/api/processors.ts, listProcessors → total vs online). Use
  useAuth() for the token exactly as MaintenanceView does; reuse MaintenanceView's tone mapping
  so an unreachable health endpoint shows "Unknown" instead of crashing, and hide the worker
  tile on 403.
- Audit every remaining METRICS-backed panel and give each a <SampleDataBadge/> — today only
  three of them carry one.
- Task backlog is in fact obtainable from GET /api/v1/tasks (src/api/tasks.ts): count open tasks
  rather than reading METRICS.taskBacklog, or badge it.

Long term (needs a server endpoint): once GET /api/v1/metrics exists, add src/api/metrics.ts,
delete the METRICS import and the badges. Note in the same change whether src/mock/data.ts can be
deleted — WorkflowView is then its last consumer (TASK_UI_AI_ML.md).
```

**References:** [MonitoringArea.tsx](../../../loom-ui/src/features/monitoring/MonitoringArea.tsx) ·
[api/health.ts](../../../loom-ui/src/api/health.ts) · [api/processors.ts](../../../loom-ui/src/api/processors.ts) ·
[../RESTAPI.md](../RESTAPI.md) §4 (no metrics route) · [LOOM_UI.md](LOOM_UI.md) §14 ·
[TASK_UI_PIPELINE.md](TASK_UI_PIPELINE.md) Task 4 (activity list in the same view)

**Test Requirements:**
- `e2e/monitoring-mocked.spec.ts` extended: health and worker tiles render from mocked
  `/health` and `/processors` responses; every synthetic panel shows a sample-data badge.
- Failure path in the same spec: `/health` 500 and `/processors` 403 render Unknown / hidden
  tiles with the page still usable.

---

## Closed items (outcome records)

| Closed task | Landed in |
|---|---|
| Surface instance info (version, dbRevision, lastUsed) | [api/info.ts](../../../loom-ui/src/api/info.ts) → MaintenanceView chips; `e2e/maintenance-mocked.spec.ts` |
| Surface health status honestly (real DB card, gated unavailable cards) | [api/health.ts](../../../loom-ui/src/api/health.ts) → MaintenanceView |
| Back the pipeline-run KPI + chart with real aggregation instead of mock | `loadPipelineRunStats` + [runMetrics.ts](../../../loom-ui/src/features/monitoring/runMetrics.ts) (`runMetrics.test.ts`), deltas computed from the real series |

_Git HEAD revision: `499f71f7`_
_Last updated: 2026-08-01 (re-verified against loom-ui; two tasks remain — OpenAPI download and finishing the Monitoring de-mock)_
