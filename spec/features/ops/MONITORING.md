# MetaLoom — Monitoring & Health Specification

> **Audience: AI coding agents.** How Loom and Cortex expose health, readiness and (future) metrics.
> Verified against the code on the revision in the footer. **Source of truth is the code** — if this
> contradicts it, the code wins and fix this file in the same change.
>
> Related: [../../cortex/CORTEX.md](../../cortex/CORTEX.md), [../../loom/RESTAPI.md](../../loom/RESTAPI.md),
> [../../loom/CONFIGURATION.md](../../loom/CONFIGURATION.md).

---

## 1. TL;DR

- **Cortex** runs a dedicated monitoring HTTP server (Vert.x) with **liveness** (`/api/health`) and
  **readiness** (`/api/ready`) endpoints. Default port **8093**, env `CORTEX_MONITORING_PORT`.
- **Loom** exposes a health check on its main REST API at **`GET /api/v1/health`** (status + version +
  DB check). There is **no** separate Loom monitoring server running yet.
- 🔴 **No Prometheus / `/metrics` endpoint exists** in either component today. The
  `loom/services/monitoring` module is an **empty placeholder** and `ServerOptions.monitoringPort`
  (`LOOM_SERVER_MON_PORT`, default 8989) is **configured but never bound** to a server.
- Marketing/older docs mention "Prometheus scrape endpoint" and a Cortex `/metrics` route — both are
  **aspirational**, not implemented.

---

## 2. Cortex Monitoring Server

Package `io.metaloom.cortex.impl.monitoring`.

### 2.1 Wiring

- `MonitoringService` (`@Singleton`) creates a Vert.x `HttpServer`, mounts a `Router`, and
  `HealthEndpoint.register(router)`.
- Started by `CortexBootstrapInitializer.init(port)` during `cortex.run()`. Default port `8093`
  (`MonitoringService.init()` → `init(8093)`); the CLI/options pass `CORTEX_MONITORING_PORT`.
- `actualMonitoringPort()` returns the bound port (useful when `0` = ephemeral in tests).
- `deinit()` closes the server on shutdown.

### 2.2 Endpoints (`HealthEndpoint`)

| Method / Path | Code | Body | Semantics |
|---|---|---|---|
| `GET /api/health` | always `200` | `{ "status": "up", "loom": <status> }` | **Liveness** — the process is running. |
| `GET /api/ready` | `200` / `503` | `{ "status": "ready"\|"not_ready", "loom": <status> }` | **Readiness** — `LoomControlChannel.isReady()`. |
| `GET /health` | = `/api/health` | — | Legacy alias (backward-compat with existing probes). |
| `GET /ready` | = `/api/ready` | — | Legacy alias. |

**Readiness definition** — `LoomControlChannel.isReady()` returns
`endpointConfigured && connected && registered`. So a worker is *ready* only once it has connected to
Loom **and** completed registration. In offline mode (`endpointConfigured == false`) liveness is `up`
but readiness stays `not_ready` (503) forever — expected.

**The `loom` status object** (`LoomControlChannel.healthStatus()`), embedded in both responses:

```json
{ "configured": true, "connected": true, "registered": true,
  "host": "loom.internal", "port": 8092, "reconnectAttempts": 0,
  "lastConnectedAt": <epochMs|null>, "lastMessageAt": <epochMs|null>,
  "lastHeartbeatAckAt": <epochMs|null>, "error": <string|null> }
```

`reconnectAttempts`, `lastHeartbeatAckAt` and `error` are the useful signals for diagnosing a worker
that is up but not processing (e.g. registered but heartbeats stalled).

---

## 3. Loom Health Check

Package `io.metaloom.loom.rest.endpoint.impl` — `HealthEndpoint` (a regular REST endpoint, **not** a
separate server).

- **`GET /api/v1/health`** → `HealthCheckResponse` (`loom-shared/rest-model/.../health/`):

| Field | Values | Notes |
|---|---|---|
| `status` | `UP` \| `DEGRADED` | `DEGRADED` when the DB check fails. |
| `version` | `LoomVersion.VERSION` | e.g. `1.0.0-SNAPSHOT`. |
| `database` | `UP` \| `DOWN` | Result of a live DB connectivity check (`checkDatabase()` via the jOOQ `DSLContext`). |
| `timestamp` | ISO-8601 | `Instant.now()`. |

It is registered like any other endpoint (`basePath = /api/v1/health`) on the normal REST router, so it
shares Loom's REST port (`8092`, `LOOM_SERVER_REST_PORT`).

⚠️ Whether `/api/v1/health` requires auth depends on the endpoint's `secure(...)` registration — verify
in `RESTModule`/`EndpointModule` before assuming it is public. (Probes may need a token if it is
secured.)

---

## 4. Ports & Env Vars

| Setting | Default | Env | Bound? |
|---|---|---|---|
| Cortex monitoring port | `8093` | `CORTEX_MONITORING_PORT` | ✅ served by `MonitoringService` |
| Loom REST port (serves `/api/v1/health`) | `8092` | `LOOM_SERVER_REST_PORT` | ✅ |
| Loom monitoring port | `8989` | `LOOM_SERVER_MON_PORT` | 🔴 **configured but not bound to any server** |
| Loom MCP port | `4041` | `LOOM_SERVER_MCP_PORT` | ✅ (MCP, not monitoring) |

---

## 5. Key Classes Reference

| Class | Package / Module | Purpose |
|---|---|---|
| `MonitoringService` | `cortex/core` · `io.metaloom.cortex.impl.monitoring` | Starts/stops the Cortex monitoring HTTP server. |
| `HealthEndpoint` (Cortex) | `cortex/core` · `io.metaloom.cortex.impl.monitoring` | Registers `/api/health`, `/api/ready` (+ legacy). |
| `LoomControlChannel` | `cortex/core` · `io.metaloom.cortex.impl.loom` | `isReady()` and `healthStatus()` backing the endpoints. |
| `CortexBootstrapInitializer` | `cortex/core` · `io.metaloom.cortex.impl.boot` | Calls `monitoringService.init(port)` on startup. |
| `HealthEndpoint` (Loom) | `loom/services/rest` · `io.metaloom.loom.rest.endpoint.impl` | `GET /api/v1/health` + DB check. |
| `HealthCheckResponse` | `loom-shared/rest-model` · `…rest.model.health` | Loom health DTO. |
| `loom/services/monitoring` | `loom/services/monitoring` | **Empty placeholder module** (README only). |

---

## 6. Where do I find …?

| I want to … | Look at |
|---|---|
| Cortex health/ready routes | `cortex/core/.../impl/monitoring/HealthEndpoint.java` |
| Cortex monitoring server start | `cortex/core/.../impl/monitoring/MonitoringService.java`, `.../impl/boot/CortexBootstrapInitializer.java` |
| Cortex readiness logic | `LoomControlChannel.isReady()` / `healthStatus()` |
| Loom health endpoint | `loom/services/rest/.../endpoint/impl/HealthEndpoint.java` |
| Loom health DTO | `loom-shared/rest-model/.../health/HealthCheckResponse.java` |
| Loom monitoring placeholder | `loom/services/monitoring/` |
| Customer-facing doc | `website/content/english/docs/cortex/monitoring/index.adoc` |

---

## 7. Conventions & Gotchas

- **Liveness vs readiness are different endpoints on Cortex.** Point k8s `livenessProbe` at
  `/api/health` and `readinessProbe` at `/api/ready` — using `/api/health` for readiness would keep an
  unregistered worker "ready".
- **A Cortex worker can be *live* but not *ready* indefinitely** (offline mode, or Loom unreachable).
  That is by design; do not treat `not_ready` as a crash.
- **No `/metrics` yet.** Do not document or depend on a Prometheus endpoint. If you add one, wire it to
  `LOOM_SERVER_MON_PORT` on Loom and a `/metrics` route on the Cortex monitoring server, and update the
  website page + this spec together.
- **Loom health is on the REST port**, not a separate port — `LOOM_SERVER_MON_PORT` is reserved but
  inert.
- Cortex serves `up` (lowercase) for liveness; Loom serves `UP` (uppercase) for its REST health. Do not
  assume a shared schema between the two.

---

## 8. Test Setup

- Cortex: an endpoint test can boot `MonitoringService.init(0)` (ephemeral port), read
  `actualMonitoringPort()`, then assert `GET /api/health` → 200 and `GET /api/ready` → 503 when no Loom
  is configured (offline). Drive readiness to 200 by faking a connected+registered `LoomControlChannel`.
- Loom: `HealthEndpoint` is covered by the REST endpoint test infrastructure (leased DB); assert
  `status=UP`, `database=UP`, and `DEGRADED`/`DOWN` when the DB is unavailable.

---

## 9. Progress Assessment

- [x] Cortex liveness (`/api/health`) + readiness (`/api/ready`) implemented, with legacy aliases
- [x] Readiness reflects connected **and** registered state; rich `loom` diagnostic object
- [x] Loom `GET /api/v1/health` with version + DB connectivity check
- [x] Website monitoring page corrected (removed the non-existent `/metrics` claim; fixed statuses)
- [ ] **Prometheus / `/metrics` endpoint** — not implemented on either component
- [ ] **Loom monitoring server** — `loom/services/monitoring` is an empty placeholder;
      `LOOM_SERVER_MON_PORT` (8989) is configured but bound to nothing
- [ ] Per-node / pipeline **metrics** (processed/skipped/failed counters exist in the executor but are
      not exposed over HTTP)
- [ ] Confirm and document whether `/api/v1/health` is auth-exempt for probes

---

_GIT HEAD: `228b0f97274607c179b22e6d1ffa0885719d5fa1`_
_Generated: 2026-07-25 (UTC)_
