# MetaLoom — CRUD Coverage Tasks: System

> Gaps between the REST API and the Loom UI (`loom-ui/`) for the System domain:
> **Webhook, Loom (instance/system info)**.
> Derived from REST endpoints, UI api clients/features, and e2e specs.
> Format follows [../../TASKS.template.md](../../TASKS.template.md). See [../DOMAIN.md](../DOMAIN.md).

## Coverage Summary

| Element | REST endpoint? | UI client? | UI screen? | Create | Read/List | Update | Delete | E2E |
|---|---|---|---|---|---|---|---|---|
| **Webhook** | ✅ `WebhookEndpoint` `/api/v1/webhooks` (POST create, POST `:uuid` update, DELETE, GET list, GET `:uuid`) | ❌ no `webhooks.ts` | ❌ none | ❌ | ❌ | ❌ (incl. trigger select + active toggle + secret token) | ❌ | ❌ |
| **Loom / instance info** | ⚠️ `HealthEndpoint` `/api/v1/health` (status/version/database/timestamp) works; `RESTInfoEndpoint` `/api/v1/` is a `not yet implemented` stub; no DB-revision / last-used exposed | ❌ no health/info client | ⚠️ `MaintenanceView` exists but shows **hardcoded mock** data | n/a | ❌ (mock only) | n/a | n/a | ❌ |

Legend: ✅ present · ❌ absent · ⚠️ partial/stub.

**Key findings**
- The webhook REST surface is fully routed (`WebhookEndpoint`) but the UI has **zero** integration: no `loom-ui/src/api/webhooks.ts`, no screen, no e2e spec. This is the single largest gap in the System domain.
- Domain-specific webhook aspects (trigger selection via `WebhookTrigger` enum, `active` toggle, `secretToken`, `meta`) are all present in the REST models but unreachable from the UI.
- `MaintenanceView.tsx` renders **hardcoded** version/uptime/database/storage/memory values and never calls the live `/api/v1/health` endpoint.
- No REST endpoint exposes the `loom` singleton system row (DB revision, last-used); `RESTInfoEndpoint` GET `/api/v1/` explicitly returns `"not yet implemented"`.

> Note on backend fidelity: `WebhookEndpointService.create()` currently persists only `url` (ignores triggers/secretToken) and `update()` has a `// TODO update` and only sets the editor. The endpoints, routes and models nonetheless exist and define the contract the UI must integrate against; backend completion is tracked separately from these UI-coverage tasks.

---

## Task: Add a `webhooks.ts` REST API client to loom-ui

**Argumentation Summary:** The REST API exposes a complete webhook CRUD surface in [WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java) (`/api/v1/webhooks`), yet `loom-ui/src/api/` contains no `webhooks.ts` — confirmed by directory listing (clients exist for tags, roles, tokens, spaces, etc., but not webhooks). Without a client, no webhook feature can be built.
**Improvement Summary:** Add `loom-ui/src/api/webhooks.ts` mirroring the existing client pattern, covering list, load, create, update and delete plus the webhook model types.

```
Create loom-ui/src/api/webhooks.ts following the exact shape of the existing
clients such as loom-ui/src/api/tags.ts (authHeaders/handleResponse helpers,
API_BASE_URL from ./config, one exported async fn per route).

Model the TypeScript interfaces on the REST models:
- WebhookResponse — from WebhookResponse.java: url, triggers (WebhookTrigger[]),
  secretToken?, active?, plus the standard status/creator/editor block
  (AbstractCreatorEditorRestResponse) and meta?.
- WebhookListResponse — { data: WebhookResponse[]; _metainfo?: {...} }.
- WebhookCreateRequest — from WebhookCreateRequest.java: url, triggers, secretToken?, meta?.
- WebhookUpdateRequest — from WebhookUpdateRequest.java: url?, triggers?, secretToken?,
  active?, meta?.
- WebhookTrigger — string-literal union matching the enum WebhookTrigger.java
  (currently CONTENT_CREATED | ASSET_CREATED).

Routes (all under `${API_BASE_URL}/webhooks`, Bearer auth):
- listWebhooks(token)                         -> GET  /webhooks
- loadWebhook(token, uuid)                     -> GET  /webhooks/:uuid
- createWebhook(token, WebhookCreateRequest)   -> POST /webhooks
- updateWebhook(token, uuid, WebhookUpdateRequest) -> POST /webhooks/:uuid
- deleteWebhook(token, uuid)                   -> DELETE /webhooks/:uuid
```

**References:**
- REST: [WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java)
- REST models: [WebhookResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookResponse.java), [WebhookCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookCreateRequest.java), [WebhookUpdateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookUpdateRequest.java), [WebhookTrigger.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookTrigger.java)
- UI pattern: [tags.ts](../../../loom-ui/src/api/tags.ts), [config.ts](../../../loom-ui/src/api/config.ts)

**Test Requirements:**
- Unit test (mock `fetch`) verifying each function targets the correct URL/method and forwards the Bearer token, mirroring how other api clients are exercised.

---

## Task: Add a Webhooks list/read screen to the Admin area

**Argumentation Summary:** `GET /api/v1/webhooks` (list) and `GET /api/v1/webhooks/:uuid` (read) are routed in [WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java) but nothing in `loom-ui/src` renders webhooks — grep for "webhook" in `loom-ui/src` returns only the unrelated MUI icon. The admin surface [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) already hosts tabbed CRUD tables (Spaces, Users, Groups, Roles, Tokens), making it the natural home for a Webhooks table.
**Improvement Summary:** Add a "Webhooks" tab/table in the Admin area that lists webhooks and shows their key fields (url, triggers, active state).

```
Add a WebhooksAdmin table component alongside the existing SpacesAdmin /
tokens tables in loom-ui/src/features/admin/AdminArea.tsx (or a co-located file),
and register it as a new tab in the AdminArea Tabs.

- Load via listWebhooks(token) from the new api/webhooks.ts (Task 1).
- Columns: URL, Triggers (chips from WebhookTrigger[]), Active (on/off indicator
  from WebhookResponse.active), Created/Creator (status block).
- Row click / detail loads loadWebhook(token, uuid) for full detail (secretToken, meta).
- Follow the loading/error handling and table styling already used by SpacesAdmin
  in the same file (useAuth token, useEffect reload, MUI Table).
- Add i18n keys under loom-ui/src/i18n/locales/en.json + de.json (a "webhooks"
  section) consistent with existing admin sections.
```

**References:**
- REST: [WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java) (list + read routes), [WebhookEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/WebhookEndpointService.java)
- UI: [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx), new [webhooks.ts](../../../loom-ui/src/api/webhooks.ts) (Task 1)

**Test Requirements:**
- Component/e2e coverage that a seeded webhook appears in the table with url, triggers and active state (see Task 6).

---

## Task: Add webhook creation (URL, triggers, secret token) to the UI

**Argumentation Summary:** `POST /api/v1/webhooks` is routed with `WebhookCreateRequest` (`url`, `triggers`, `secretToken`, `meta`) per [WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java) and [WebhookCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookCreateRequest.java), but the UI offers no way to create a webhook. Users cannot register an outbound hook at all.
**Improvement Summary:** Add a "Create webhook" dialog capturing URL, one or more triggers, and an optional secret token, wired to `createWebhook`.

```
Add a create dialog to the Webhooks admin tab (Task 2), modeled on the existing
create dialogs in AdminArea.tsx (e.g. the Space/User create dialogs).

Fields:
- URL (required text) — maps to WebhookCreateRequest.url.
- Triggers (required multi-select) — options from the WebhookTrigger union in
  api/webhooks.ts. IMPORTANT: source the option list from WebhookTrigger.java
  (CONTENT_CREATED, ASSET_CREATED), NOT the broader loom_events DB enum
  (JooqLoomEvents), since the request model is typed to WebhookTrigger. If the
  product intent is the full loom_events set, that is a backend model gap to
  raise separately — do not silently send values the model rejects.
- Secret token (optional text) — maps to WebhookCreateRequest.secretToken.

On submit call createWebhook(token, {...}) then reload the list. Handle and
surface API errors like the other admin dialogs.
```

**References:**
- REST: [WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java), [WebhookCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookCreateRequest.java), [WebhookTrigger.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookTrigger.java)
- UI: [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx), [webhooks.ts](../../../loom-ui/src/api/webhooks.ts) (Task 1)

**Test Requirements:**
- e2e: create a webhook with a URL + trigger, assert it appears in the list (Task 6).

---

## Task: Add webhook editing — trigger selection, active toggle, secret token

**Argumentation Summary:** `POST /api/v1/webhooks/:uuid` accepts `WebhookUpdateRequest` with `url`, `triggers`, `secretToken` and the domain-specific `active` flag (see [WebhookUpdateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookUpdateRequest.java)), and `WebhookResponse` returns `active` ([WebhookResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookResponse.java)). The UI exposes none of these — a webhook cannot be edited, re-targeted, enabled or disabled.
**Improvement Summary:** Add an edit dialog and an inline active/enabled toggle wired to `updateWebhook`.

```
Add an edit dialog to the Webhooks admin tab (Task 2), pre-filled from
loadWebhook(token, uuid), plus an inline enable/disable Switch on each row.

Editable fields -> WebhookUpdateRequest:
- URL (url), Triggers (triggers, multi-select as in Task 3), Secret token (secretToken).
- Active toggle (active) — a MUI Switch; toggling calls updateWebhook(token, uuid,
  { active: next }) and optimistically reflects the state, mirroring how enabled
  toggles are handled elsewhere in the admin/pipeline UI.

On save call updateWebhook(token, uuid, {...}) and reload.

Backend caveat to verify during integration: WebhookEndpointService.update() has a
`// TODO update` and currently only sets the editor without applying url/triggers/
secretToken/active. Confirm the persisted round-trip; if the backend still drops
these fields, file a backend task so the UI edit is not a silent no-op.
```

**References:**
- REST: [WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java) (update route), [WebhookEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/WebhookEndpointService.java) (update TODO), [WebhookUpdateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookUpdateRequest.java), [WebhookResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/webhook/WebhookResponse.java)
- UI: [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx), [webhooks.ts](../../../loom-ui/src/api/webhooks.ts) (Task 1)

**Test Requirements:**
- e2e: edit a webhook's URL/triggers and toggle active off/on; assert persisted state on reload (Task 6).

---

## Task: Add webhook deletion to the UI

**Argumentation Summary:** `DELETE /api/v1/webhooks/:uuid` is routed in [WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java) (guarded by `DELETE_WEBHOOK` in [WebhookEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/WebhookEndpointService.java)), but the UI has no delete affordance for webhooks.
**Improvement Summary:** Add a delete action with a confirmation dialog wired to `deleteWebhook`, matching the existing admin delete pattern.

```
Add a delete IconButton per webhook row in the Webhooks admin tab (Task 2), opening
a confirmation dialog like the deleteConfirm dialogs already used for Spaces/Users
in AdminArea.tsx. On confirm, call deleteWebhook(token, uuid) and reload the list.
Surface API errors consistently with the other admin delete flows.
```

**References:**
- REST: [WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java), [WebhookEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/WebhookEndpointService.java)
- UI: [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx), [webhooks.ts](../../../loom-ui/src/api/webhooks.ts) (Task 1)

**Test Requirements:**
- e2e: delete a webhook and assert it disappears from the list (Task 6).

---

## Task: Add e2e coverage for webhook CRUD

**Argumentation Summary:** `loom-ui/e2e/` contains backend specs for assets, collections, pipelines, roles, spaces, tags, tasks, tokens, users, etc., but no `webhooks-*.spec.ts` — webhooks have zero e2e coverage to match their zero UI coverage. Once Tasks 1–5 land they must be regression-protected.
**Improvement Summary:** Add a `webhooks-backend.spec.ts` covering the full create → list/read → update (triggers + active toggle) → delete flow.

```
Add loom-ui/e2e/webhooks-backend.spec.ts modeled on an existing backend spec such
as roles-backend.spec.ts or tags-backend.spec.ts (login, navigate to the admin
Webhooks tab, drive the UI, assert against the real REST backend).

Cover:
- create a webhook (URL + trigger[, secret token]) and assert it lists.
- read/detail shows url, triggers, active.
- update url/triggers; toggle active off then on; assert persisted on reload.
- delete and assert removal.
```

**References:**
- e2e pattern: [roles-backend.spec.ts](../../../loom-ui/e2e/roles-backend.spec.ts), [tags-backend.spec.ts](../../../loom-ui/e2e/tags-backend.spec.ts)
- REST: [WebhookEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/WebhookEndpoint.java)

**Test Requirements:**
- The spec itself is the deliverable; it must pass against a seeded backend and cover every CRUD op plus the active toggle and trigger selection.

---

## Task: Wire the Maintenance view to the live `/health` endpoint (replace mocked instance info)

**Argumentation Summary:** [HealthEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/HealthEndpoint.java) serves `GET /api/v1/health` with a real DB connectivity check, returning `status`, `version`, `database`, `timestamp` ([HealthCheckResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/health/HealthCheckResponse.java)). But [MaintenanceView.tsx](../../../loom-ui/src/features/maintenance/MaintenanceView.tsx) renders entirely **hardcoded** values — `Version 0.9.4-alpha`, `Uptime: 48h 12m`, `Last restart: 2 days ago`, and a static `statusItems` array (Database/S3/Memory/Pipeline "Healthy" with fixed progress). No health client exists in `loom-ui/src/api/`. The maintenance screen is therefore misleading: it never reflects real service state.
**Improvement Summary:** Add a health API client and drive MaintenanceView from live `/health` data (real version + database status + timestamp), removing the fabricated version/uptime/status placeholders.

```
1. Add loom-ui/src/api/health.ts: getHealth(token?) -> GET `${API_BASE_URL}/health`,
   returning a HealthCheckResponse type { status; version?; database?; timestamp? }.
   (The route is registered without an auth guard in HealthEndpoint; confirm whether
   a token is required in the running config and pass it if so.)
2. In MaintenanceView.tsx replace the hardcoded Chips/statusItems that map to real
   fields:
   - "Version ..." chip  -> HealthCheckResponse.version.
   - Database status card -> HealthCheckResponse.database (UP/DOWN) and overall status.
   - Show the health timestamp instead of the fabricated "Last restart"/"Uptime".
3. For values the endpoint does NOT provide (uptime, S3/memory/worker utilization,
   progress bars), either drop them or clearly gate them until a backend metric
   exists — do not keep presenting fabricated numbers as live telemetry.
```

**References:**
- REST: [HealthEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/HealthEndpoint.java), [HealthCheckResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/health/HealthCheckResponse.java)
- UI: [MaintenanceView.tsx](../../../loom-ui/src/features/maintenance/MaintenanceView.tsx), [config.ts](../../../loom-ui/src/api/config.ts)

**Test Requirements:**
- e2e/component test asserting MaintenanceView renders the version + database status returned by a mocked/real `/health` response (and degrades gracefully when the DB is DOWN).

---

## Task: Expose and surface Loom instance/system info (DB revision, last-used)

**Argumentation Summary:** The DOMAIN model defines a **Loom** singleton system row carrying DB revision + last-used timestamp ([DOMAIN.md](../DOMAIN.md) §7), but no REST endpoint exposes it: [RESTInfoEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/RESTInfoEndpoint.java) `GET /api/v1/` is a stub returning `"not yet implemented"` (only its `/openapi` sub-route works), and [HealthEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/HealthEndpoint.java) returns only status/version/database/timestamp — no DB revision, no last-used. Consequently the UI cannot display authoritative instance info; MaintenanceView fabricates it.
**Improvement Summary:** Implement the RESTInfo endpoint to return instance/system info (DB schema revision, last-used, version) sourced from the `loom` row, then surface it in the Maintenance view.

```
Backend (prerequisite):
- Implement the RESTInfoEndpoint GET /api/v1/ handler to return an info model
  populated from the loom singleton row (DB revision / last-used) plus LoomVersion.
  Add a corresponding rest-model response type next to HealthCheckResponse.

UI:
- Add a getInfo(token) call to a loom-ui/src/api client (extend health.ts or add
  info.ts) and render DB revision + last-used + version in MaintenanceView.tsx
  (Task 7), replacing/augmenting the mocked chips.

If the backend info endpoint is out of scope for the UI effort, this task is the
place to record it as a hard REST-side gap blocking authentic instance info in the UI.
```

**References:**
- REST: [RESTInfoEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/RESTInfoEndpoint.java) (unimplemented `/` route), [HealthEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/HealthEndpoint.java)
- Domain: [DOMAIN.md](../DOMAIN.md) (§7 System — Loom row)
- UI: [MaintenanceView.tsx](../../../loom-ui/src/features/maintenance/MaintenanceView.tsx)

**Test Requirements:**
- Backend unit/integration test for the info endpoint returning DB revision/last-used.
- UI test asserting the instance info renders from the endpoint response.
