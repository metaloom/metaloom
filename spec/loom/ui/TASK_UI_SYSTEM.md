# TASK_UI_SYSTEM — System

Gap-analysis tasks between the Loom REST API and the Loom UI for the System entities
(Loom) and the monitoring/health surfaces. Follows [../../TASKS.template.md](../../TASKS.template.md).

Sources of truth used for this analysis:

- REST routes: [RESTInfoEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/RESTInfoEndpoint.java),
  [HealthEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/HealthEndpoint.java),
  [ProcessorEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ProcessorEndpoint.java),
  [NodeDescriptorEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/NodeDescriptorEndpoint.java)
- Spec: [../RESTAPI.md](../RESTAPI.md) (§4 Monitoring), [../DOMAIN.md](../DOMAIN.md) (group 7), [../EVENTBUS.md](../EVENTBUS.md), [LOOM_UI.md](LOOM_UI.md)

---

## Coverage Matrix

| Entity / Surface | REST Operation (path · method) | UI Status | Where / Gap |
|------------------|--------------------------------|-----------|-------------|
| **Loom (singleton)** | `GET /api/v1` — version, `dbRevision`, `lastUsed` | **Implemented** | [loom-ui/src/api/info.ts](../../../loom-ui/src/api/info.ts) → surfaced as chips in [MaintenanceView.tsx](../../../loom-ui/src/features/maintenance/MaintenanceView.tsx) (`info-db-revision`, `info-last-used`, `health-version`). |
| **Health** | `GET /api/v1/health` — status, database, version, timestamp | **Partial** | [loom-ui/src/api/health.ts](../../../loom-ui/src/api/health.ts) → [MaintenanceView.tsx](../../../loom-ui/src/features/maintenance/MaintenanceView.tsx). Only `database` becomes a real card; Storage/Memory/Workers cards are hard-gated as "No metric available" (no backing REST fields — accurate, not a UI gap). |
| **OpenAPI** | `GET /api/v1/openapi` — YAML spec | **Missing** | No UI consumer; no "API spec / developer" surface links to or downloads it. |
| **Processor Status** | `GET /api/v1/processors` (list) | **Implemented** | [loom-ui/src/api/processors.ts](../../../loom-ui/src/api/processors.ts) (`listProcessors`), consumed by the cortex feature. |
| **Processor Status** | `GET /api/v1/processors/:nodeId` (load) | **Implemented** | [processors.ts](../../../loom-ui/src/api/processors.ts) (`getProcessor`). |
| **Node Descriptors** | `GET /api/v1/pipeline/node-descriptors`, `/:kind`, `/content-types` | **Implemented** | [loom-ui/src/api/nodeDescriptors.ts](../../../loom-ui/src/api/nodeDescriptors.ts). |
| **Metrics (dashboard)** | *(no general `GET /api/v1/metrics`; pipeline-run stats via `GET /api/v1/pipelines/runs/stats`)* | **Partial** | [MonitoringArea.tsx](../../../loom-ui/src/features/monitoring/MonitoringArea.tsx) feeds the pipeline-run KPI + chart from the run-stats aggregation endpoint; all other KPIs/charts still render from `src/mock/data.ts` (`METRICS`) and are badged "Sample data". |

**Headline finding:** The Loom singleton and Health are already wired into MaintenanceView.
OpenAPI has no consumer, and the Monitoring dashboard is entirely mock.

---

## Task: Add an OpenAPI spec viewer/download to a System/Developer surface

**Argumentation Summary:** `GET /api/v1/openapi` (`RESTInfoEndpoint`) dynamically generates and
serves the full API OpenAPI YAML, but nothing in the UI consumes or links to it. Integrators
and operators have no in-product path to the API contract.

**Improvement Summary:** Add a small "API specification" section (in MaintenanceView's system
panel or a new Admin › System tab) that lets a user download the OpenAPI YAML and/or open it in
a viewer.

```
Add a getOpenApiSpec(token) helper to loom-ui/src/api/info.ts:
  - GET /api/v1/openapi, Accept text/yaml; return the raw text (not res.json()).

Surface it in the system area (MaintenanceView.tsx system-info block, next to the version/
dbRevision/lastUsed chips, OR a new Admin › System tab):
  - A "Download OpenAPI (YAML)" button that fetches the text and triggers a file download
    (blob → <a download="loom-openapi.yaml">).
  - Optionally a read-only <pre> preview / copy-to-clipboard.

Edge cases: endpoint returns YAML text (not JSON) — do not run it through the JSON
handleResponse; large payloads — stream to a blob rather than into React state if previewing.
```

**References:**
- [loom/services/rest/.../RESTInfoEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/RESTInfoEndpoint.java) (`/api/v1/openapi`)
- [../RESTAPI.md](../RESTAPI.md) (§4.2 OpenAPI Spec)
- [loom-ui/src/api/info.ts](../../../loom-ui/src/api/info.ts), [loom-ui/src/features/maintenance/MaintenanceView.tsx](../../../loom-ui/src/features/maintenance/MaintenanceView.tsx)

**Test Requirements:**
- Unit test the `getOpenApiSpec` client (mock fetch, assert URL + Accept header, returns text).
- Component test: clicking "Download OpenAPI" invokes the client and creates a download blob.

---

## Task: Replace the mock Monitoring dashboard with live system data (or gate it honestly)

**Argumentation Summary:** `MonitoringArea.tsx` renders every KPI card and chart from
`src/mock/data.ts` (`METRICS`) and consumes **no** REST at all. LOOM_UI.md already documents the
intended `GET /api/v1/metrics` as "not implemented" — and it is: there is no MetricsEndpoint in
`loom/services/rest`. The dashboard thus presents fabricated numbers as if they were live system
telemetry, which is misleading in a system/monitoring context.

**Improvement Summary:** Until a metrics endpoint exists, drive the tiles that *can* be backed by
existing endpoints from live data and clearly mark the rest as sample data; when a metrics
endpoint lands, replace the mock wholesale.

```
Short term (no new backend), in loom-ui/src/features/monitoring/MonitoringArea.tsx:
  - Back the tiles that have real sources today: overall/DB health from GET /api/v1/health
    (api/health.ts) and a live processor/worker count + online state from GET /api/v1/processors
    (api/processors.ts, listProcessors). Wire via useAuth() token like MaintenanceView does.
  - Visibly badge the still-synthetic charts (ingestion, latency, storage, pipeline runs, agent
    usage) as "Sample data" so they are not mistaken for live telemetry.

Long term (requires server): once GET /api/v1/metrics is implemented, add api/metrics.ts and
replace the METRICS mock import entirely; remove the sample-data badges.

Edge cases: token missing / health fetch fails — show the health tile as Unknown (reuse the
tone mapping already in MaintenanceView); processors 403 — hide the worker tile.
```

**References:**
- [loom-ui/src/features/monitoring/MonitoringArea.tsx](../../../loom-ui/src/features/monitoring/MonitoringArea.tsx) (mock `METRICS`)
- [loom-ui/src/api/health.ts](../../../loom-ui/src/api/health.ts), [loom-ui/src/api/processors.ts](../../../loom-ui/src/api/processors.ts)
- [../RESTAPI.md](../RESTAPI.md) (§4 Monitoring — no metrics route), [LOOM_UI.md](LOOM_UI.md) (§14 "UI Features Still Mocked", `/metrics` not implemented)

**Test Requirements:**
- Component test: KPI tiles for health + worker count render from mocked `getHealth` /
  `listProcessors`; sample-data badges present on the synthetic charts.
- Failure path: health/processors errors render Unknown/hidden tiles rather than crashing.
