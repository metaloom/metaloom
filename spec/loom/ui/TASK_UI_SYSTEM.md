# TASK_UI_SYSTEM — System

Gap-analysis tasks between the Loom REST API and the Loom UI for the System entities
(Webhook, Loom) and the monitoring/health surfaces. Follows [../../TASKS.template.md](../../TASKS.template.md).

Sources of truth used for this analysis:

- REST routes: [loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java),
  [RESTInfoEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/RESTInfoEndpoint.java),
  [HealthEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/HealthEndpoint.java),
  [ProcessorEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ProcessorEndpoint.java),
  [NodeDescriptorEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/NodeDescriptorEndpoint.java)
- REST service: [service/impl/WebhookEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/WebhookEndpointService.java)
- REST models: [loom-shared/rest-model/.../webhook/](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/)
- Spec: [../RESTAPI.md](../RESTAPI.md) (§4 Monitoring), [../DOMAIN.md](../DOMAIN.md) (group 7), [../EVENTBUS.md](../EVENTBUS.md), [LOOM_UI.md](LOOM_UI.md)

---

## Coverage Matrix

| Entity / Surface | REST Operation (path · method) | UI Status | Where / Gap |
|------------------|--------------------------------|-----------|-------------|
| **Webhook** | `POST /api/v1/webhooks` (create) | **Missing** | No `webhooks.ts` API client, no admin view. Only reference is the permission list in [loom-ui/src/features/admin/AdminArea.tsx:909](../../../loom-ui/src/features/admin/AdminArea.tsx). |
| **Webhook** | `GET /api/v1/webhooks` (list, paged) | **Missing** | No list UI anywhere in `loom-ui/src`. |
| **Webhook** | `GET /api/v1/webhooks/:uuid` (load) | **Missing** | No detail/read UI. |
| **Webhook** | `POST /api/v1/webhooks/:uuid` (update) | **Missing** | No edit UI. |
| **Webhook** | `DELETE /api/v1/webhooks/:uuid` (delete) | **Missing** | No delete UI. |
| **Loom (singleton)** | `GET /api/v1` — version, `dbRevision`, `lastUsed` | **Implemented** | [loom-ui/src/api/info.ts](../../../loom-ui/src/api/info.ts) → surfaced as chips in [MaintenanceView.tsx](../../../loom-ui/src/features/maintenance/MaintenanceView.tsx) (`info-db-revision`, `info-last-used`, `health-version`). |
| **Health** | `GET /api/v1/health` — status, database, version, timestamp | **Partial** | [loom-ui/src/api/health.ts](../../../loom-ui/src/api/health.ts) → [MaintenanceView.tsx](../../../loom-ui/src/features/maintenance/MaintenanceView.tsx). Only `database` becomes a real card; Storage/Memory/Workers cards are hard-gated as "No metric available" (no backing REST fields — accurate, not a UI gap). |
| **OpenAPI** | `GET /api/v1/openapi` — YAML spec | **Missing** | No UI consumer; no "API spec / developer" surface links to or downloads it. |
| **Processor Status** | `GET /api/v1/processors` (list) | **Implemented** | [loom-ui/src/api/processors.ts](../../../loom-ui/src/api/processors.ts) (`listProcessors`), consumed by the cortex feature. |
| **Processor Status** | `GET /api/v1/processors/:nodeId` (load) | **Implemented** | [processors.ts](../../../loom-ui/src/api/processors.ts) (`getProcessor`). |
| **Node Descriptors** | `GET /api/v1/pipeline/node-descriptors`, `/:kind`, `/content-types` | **Implemented** | [loom-ui/src/api/nodeDescriptors.ts](../../../loom-ui/src/api/nodeDescriptors.ts). |
| **Metrics (dashboard)** | *(no general `GET /api/v1/metrics`; pipeline-run stats via `GET /api/v1/pipelines/runs/stats`)* | **Partial** | [MonitoringArea.tsx](../../../loom-ui/src/features/monitoring/MonitoringArea.tsx) feeds the pipeline-run KPI + chart from the run-stats aggregation endpoint; all other KPIs/charts still render from `src/mock/data.ts` (`METRICS`) and are badged "Sample data". |

**Headline finding:** Webhook has a full REST CRUD surface (5 operations, permissions
`CREATE/READ/UPDATE/DELETE_WEBHOOK`) but **zero UI** — no API client, no admin tab, no
forms. The Loom singleton and Health are already wired into MaintenanceView. OpenAPI has no
consumer, and the Monitoring dashboard is entirely mock.

---

## Task: Add a Webhook management admin panel (full CRUD, no UI exists today)

**Argumentation Summary:** The Webhook entity exposes a complete REST CRUD surface
(`WebhookEndpoint`: create, list, load, update, delete, all guarded by the
`CREATE/READ/UPDATE/DELETE_WEBHOOK` permissions) so operators can register outbound HTTP
hooks that fire on `loom_events` (user/group/role/asset/tag/webhook lifecycle) with a secret
verification token. The UI has none of this: a repo-wide grep for "webhook" in `loom-ui/src`
returns only the permission-label array in `AdminArea.tsx:909`. There is no `webhooks.ts` API
client and no admin view. Webhooks are therefore completely unmanageable from the product.

**Improvement Summary:** Add a new "Webhooks" tab under the Admin area providing list, create,
edit, and delete, with a multi-select for trigger event types and a secret-token field.

```
Create loom-ui/src/api/webhooks.ts, following the shape of loom-ui/src/api/tokens.ts:
  - WebhookResponse { uuid, url, triggers: WebhookTrigger[], secretToken?, active?, creator, editor, created, edited, ... }
  - WebhookCreateRequest { url, triggers, secretToken? }
  - WebhookUpdateRequest { url?, triggers?, secretToken?, active? }
  - WebhookTrigger union — mirror the Java enum io.metaloom.loom.rest.model.webhook.WebhookTrigger,
    which currently only defines CONTENT_CREATED and ASSET_CREATED. Derive the option list from
    that enum; do not invent values.
  - listWebhooks(token) -> GET /api/v1/webhooks  (paged; read _metainfo like other list clients)
  - getWebhook(token, uuid) -> GET /api/v1/webhooks/:uuid
  - createWebhook(token, req) -> POST /api/v1/webhooks
  - updateWebhook(token, uuid, req) -> POST /api/v1/webhooks/:uuid   (NOTE: update uses POST, not PUT)
  - deleteWebhook(token, uuid) -> DELETE /api/v1/webhooks/:uuid
  Reuse the authHeaders/handleResponse helpers used across the api layer.

Add a WebhooksAdmin sub-view in loom-ui/src/features/admin/AdminArea.tsx (mirror
BlacklistAdmin / ApiKeysAdmin):
  - Register a route <Route path="webhooks" element={<WebhooksAdmin />} /> alongside the
    existing spaces/users/groups/permissions/api-keys/blacklist routes (~line 1285).
  - Add a nav tab { label: t("admin.tab.webhooks"), path: "/admin/webhooks" } to the tab
    array (~line 1253) and matching i18n keys.
  - List: table of url, active flag, trigger chips, creator, created date, with row delete.
  - Create/Edit dialog: url text field (required, validate http/https), a multi-select or
    checkbox group of WebhookTrigger event types (required — at least one), and a secret-token
    field (optional; render the value masked with a reveal/copy affordance since the REST
    response returns secretToken in plaintext). Edit dialog additionally exposes the `active`
    toggle (update-only field).
  - Delete: confirmation dialog consistent with the other admin panels.
  - Gate the whole tab and its actions on the CREATE/READ/UPDATE/DELETE_WEBHOOK permissions
    already enumerated for "WebHook" in AdminArea.tsx:909.

Edge cases:
  - Empty list state.
  - 403 when the user lacks the relevant *_WEBHOOK permission — hide/disable rather than error.
  - Backend caveat (must be called out in the PR, and ideally fixed server-side first):
    WebhookEndpointService.create() currently persists ONLY the url (it calls
    dao().createWebhook(userUuid, url) and ignores triggers/secretToken), and update() has a
    "TODO update" — it loads the webhook, stamps the editor, and saves without applying
    url/triggers/secretToken/active. So round-tripping triggers/secret/active will silently
    no-op until the service is wired. The UI task should be built against the model, and the
    server gap flagged (or fixed) so the forms are not dead controls.
```

**References:**
- [loom/services/rest/.../WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java)
- [loom/services/rest/.../service/impl/WebhookEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/WebhookEndpointService.java) (create/update caveat)
- [loom-shared/rest-model/.../webhook/WebhookCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookCreateRequest.java), [WebhookUpdateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookUpdateRequest.java), [WebhookResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookResponse.java), [WebhookTrigger.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookTrigger.java)
- [loom-ui/src/features/admin/AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) (pattern + permission list)
- [loom-ui/src/api/tokens.ts](../../../loom-ui/src/api/tokens.ts) (API-client pattern)
- [../DOMAIN.md](../DOMAIN.md) (group 7 · Webhook), [../RESTAPI.md](../RESTAPI.md) (Webhook `/api/v1/webhooks`)

**Test Requirements:**
- Unit test `webhooks.ts` (Vitest, mirroring `tokens`/`binaries` `.test.ts` style): mock fetch
  and assert method/URL/headers/body for list, load, create, update (POST to `/:uuid`), delete.
- Component/E2E: render WebhooksAdmin with mocked API — list renders rows; create dialog
  validates url + requires ≥1 trigger; edit exposes the active toggle; delete confirmation
  calls the delete client; tab/actions hidden without the corresponding permission.

---

## Task: Surface a "Test-fire webhook" action (blocked — REST endpoint missing)

**Argumentation Summary:** Operators registering a webhook need to confirm the endpoint is
reachable and that their receiver validates the secret token, without waiting for a real
`loom_event`. The REST API currently exposes **no** test-fire / ping route on
`WebhookEndpoint` (only create/list/load/update/delete), so this cannot be built UI-only.

**Improvement Summary:** Add a "Send test event" affordance per webhook row/detail once a
backing endpoint exists; until then this is a tracked, backend-blocked gap.

```
Prerequisite (server): add e.g. POST /api/v1/webhooks/:uuid/test to WebhookEndpoint +
WebhookEndpointService that dispatches a synthetic event to the configured url with the
secret token and returns the delivery result (HTTP status, latency, error).

UI (once available): in the WebhooksAdmin panel (see previous task), add a "Send test event"
button on each webhook that calls the new endpoint and shows delivery outcome (success/status
code/error) in a toast or inline result row. Choose which trigger type to simulate from the
webhook's configured triggers.

Do NOT ship a UI test-fire button wired to a non-existent route; keep this task blocked on the
server endpoint to avoid a dead control.
```

**References:**
- [loom/services/rest/.../WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java) (no test route today)
- [../EVENTBUS.md](../EVENTBUS.md) (event dispatch context)

**Test Requirements:**
- Blocked until the REST endpoint lands. When implemented: unit-test the client call and a
  component test asserting the delivery-result rendering for both success and failure.

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
