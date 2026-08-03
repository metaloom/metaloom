# MetaLoom — Metrics & Prometheus Specification

> **Audience: AI coding agents.** How Loom and Cortex expose Prometheus metrics on their
> **monitoring** ports.
>
> ⚠️ **Status: PARTIALLY IMPLEMENTED.** The transport (registry, `/metrics` routes, both catalogs) is
> built and test-covered. **12 meters listed in earlier revisions of this file did not exist or were
> never recorded** — they are now segregated into §5 "Declared but never recorded / not implemented".
> Do not treat §3/§4 tables as a wish list: only rows there are live.
>
> **The source of truth is the code** — if this contradicts it, the code wins and this file is fixed
> in the same change.
>
> Related: [MONITORING.md](MONITORING.md) (health/readiness + the monitoring servers themselves — the
> companion of this file), [../../cortex/CORTEX.md](../../cortex/CORTEX.md),
> [../../loom/LOOM.md](../../loom/LOOM.md), [../../loom/RESTAPI.md](../../loom/RESTAPI.md).

---

## 1. TL;DR

- Each component exposes a **Prometheus scrape endpoint** at `GET /metrics` on its **monitoring
  port**, never on the public REST/gRPC/MCP port.
  - **Cortex** — `MetricsEndpoint` mounted on the existing `MonitoringService` router. Port **8093**
    (`CORTEX_MONITORING_PORT`), shared with `/api/health` + `/api/ready` (see
    [MONITORING.md](MONITORING.md)).
  - **Loom** — a dedicated `MonitoringService` binds `LOOM_SERVER_MON_PORT` (default **8989**) and
    serves **only** `/metrics`. Loom's health check lives elsewhere, on the REST port.
- Backend is **Micrometer** with a single shared `PrometheusMeterRegistry` per process, created
  **before** `Vertx` and handed to it via `MicrometerMetricsFactory`, so Vert.x HTTP / event-bus /
  pool families appear alongside the custom domain meters. JVM + process binders are attached
  explicitly at registry-construction time (`setJvmMetricsEnabled(false)` on the Vert.x options
  avoids double registration).
- Each component has a **catalog interface** — `LoomMetrics` (`loom-common`) / `CortexMetrics`
  (`cortex-common`) — with a Micrometer impl bound downstream and a `Noop*` for tests and offline
  paths. Instrumentation sites inject the interface; they never touch Micrometer directly.
- `/metrics` is **unauthenticated** — it lives on the internal monitoring port. Restrict at the
  network layer.

---

## 2. Architecture

```mermaid
flowchart LR
    prom[Prometheus]

    subgraph Loom
      loomMon["MonitoringService :8989\nLOOM_SERVER_MON_PORT\nGET /metrics only"]
      loomReg[("PrometheusMeterRegistry\nVertxModule.meterRegistry()")]
      loomImpl["MicrometerLoomMetrics\n(@Binds LoomMetrics)"]
      loomSites["Sites: PipelineEndpointService, PipelineRunTracker,\nWebSocketNodeDispatcher, ProcessorEndpoint,\nProcessorRegistry, LeaseReaper,\nPipelineEventBroadcaster, WebSocketAuthenticator,\nPipelineRunRecovery, NodeResultEndpointService"]
      loomSites --> loomImpl --> loomReg --> loomMon
      loomVertx["Vertx built-ins + JVM/process binders"] --> loomReg
    end

    subgraph Cortex
      cortexMon["MonitoringService :8093\nCORTEX_MONITORING_PORT\n/metrics + /api/health + /api/ready\n(+ optional S3 webhook)"]
      cortexReg[("PrometheusMeterRegistry\nCortexBindModule.provideMeterRegistry()")]
      cortexImpl["MicrometerCortexMetrics\n(@Binds CortexMetrics)"]
      cortexSites["Sites: LoomControlChannel, PipelineTaskHandler,\nLoomBulkSyncWriterImpl, AbstractMediaNode,\n11 AI nodes"]
      cortexSites --> cortexImpl --> cortexReg --> cortexMon
      cortexVertx["Vertx built-ins + JVM/process binders"] --> cortexReg
    end

    prom -->|scrape| loomMon
    prom -->|scrape| cortexMon
```

**Ordering invariant.** `Vertx` must be built *after* the registry, so both Dagger modules express it
as a provider parameter: `Vertx provideVertx(PrometheusMeterRegistry registry)`.

---

## 3. Loom Metrics (`loom_*`) — live

Every row below has a verified registration **and** an increment/bind call site.

| Metric | Type | Labels | Call site |
|---|---|---|---|
| `loom_pipeline_runs_started_total` | counter | — | `PipelineEndpointService:388` |
| `loom_pipeline_runs_completed_total` | counter | `status` | `PipelineRunTracker:190` |
| `loom_pipeline_run_duration_seconds` | timer | `status` | `PipelineRunTracker:190` (same call) |
| `loom_pipeline_runs_rejected_total` | counter | `reason`=invalid_graph\|no_processor | `PipelineEndpointService:357,370` |
| `loom_pipeline_runs_recovered_total` | counter | — | `PipelineRunRecovery:119` |
| `loom_node_tasks_dispatched_total` | counter | `kind` (+ literal `segment`) | `WebSocketNodeDispatcher:72,98` |
| `loom_node_tasks_dispatch_failed_total` | counter | `reason`=no_processor\|socket_gone | `WebSocketNodeDispatcher:59,68,87,94` |
| `loom_node_results_received_total` | counter | `kind`, `state` | `ProcessorEndpoint:521` |
| `loom_source_items_received_total` | counter | — | `ProcessorEndpoint:413` |
| `loom_asset_node_results_written_total` | counter | `kind`, `state` | `NodeResultEndpointService:72` |
| `loom_tasks_returned_total` | counter | `node` (nodeId, `unknown` if null) | `ProcessorEndpoint:582` |
| `loom_leases_reclaimed_total` | counter | — | `LeaseReaper:138` |
| `loom_orphans_deadlettered_total` | counter | — | `LeaseReaper:185` |
| `loom_processors_connected` | gauge | — | `ProcessorRegistry:86` (`bindGauge`, `processors::size`) |
| `loom_processor_registrations_total` | counter | — | `ProcessorRegistry:141` |
| `loom_processor_disconnects_total` | counter | — | `ProcessorRegistry:194` |
| `loom_processor_heartbeats_total` | counter | — | `ProcessorRegistry:239` |
| `loom_pipeline_event_subscribers` | gauge | — | `PipelineEventBroadcaster:51` (`bindGauge`, `subscribers::size`) |
| `loom_pipeline_events_broadcast_total` | counter | — | `PipelineEventBroadcaster:136` |
| `loom_pipeline_events_dropped_total` | counter | — | `PipelineEventBroadcaster:132` (backpressure) |
| `loom_auth_failures_total` | counter | `type` — **only `ws` is ever emitted** | `WebSocketAuthenticator:79,94` |
| `loom_http_server_*`, `loom_pool_*`, `vertx_*` | auto | — | Vert.x built-ins on the shared registry |
| `jvm_*`, `process_cpu_usage`, `process_uptime_seconds` | auto | — | `ClassLoader`/`JvmMemory`/`JvmGc`/`JvmThread`/`Processor`/`Uptime` binders in `VertxModule.meterRegistry()` |

**`loom_tasks_returned_total` vs `loom_leases_reclaimed_total`** — the same recovery, but paid for at
announcement rather than after a lease interval. A fleet that scales down often and shows reclaims
instead of returns has workers dying rather than draining.

---

## 4. Cortex Metrics (`cortex_*`) — live

| Metric | Type | Labels | Call site |
|---|---|---|---|
| `cortex_loom_connected` | gauge | — | `LoomControlChannel:230` |
| `cortex_loom_registered` | gauge | — | `LoomControlChannel:231` |
| `cortex_loom_reconnect_attempts` | gauge | — | `LoomControlChannel:232` |
| `cortex_loom_reconnects_total` | counter | — | `LoomControlChannel:372` (`scheduleReconnect`) |
| `cortex_loom_messages_received_total` | counter | `type` = lowercased `MessageType` | `LoomControlChannel:481` |
| `cortex_tasks_received_total` | counter | `type`=node\|segment\|source | `PipelineTaskHandler:145,299,365` |
| `cortex_tasks_completed_total` | counter | `type`, `state` | `PipelineTaskHandler:324,336,401` |
| `cortex_task_duration_seconds` | timer | `type` | `PipelineTaskHandler:337` |
| `cortex_tasks_returned_total` | counter | `reason`=refused\|unfinished | `PipelineTaskHandler:263,371` |
| `cortex_node_operations_total` | counter | `node_kind`, `state` | `PipelineTaskHandler:327,338` |
| `cortex_node_operation_duration_seconds` | timer | `node_kind` | same call (`recordNodeOperation`) |
| `cortex_files_missing_total` | counter | — | `AbstractMediaNode:57` (**only** site) |
| `cortex_bulk_sync_assets_total` | counter | `outcome`=synced\|failed\|dropped_offline\|skipped_no_hash | `LoomBulkSyncWriterImpl:66,85,99,103` |
| `cortex_source_items_enumerated_total` | counter | — | `PipelineTaskHandler:400` |
| `cortex_ai_calls_total` | counter | `provider`, `outcome`=success\|failure | 11 AI nodes — see below |
| `cortex_ai_call_duration_seconds` | timer | `provider` | same calls |
| `cortex_ai_cache_hits_total` | counter | `provider` | same nodes, in-heap skip caches |
| `cortex_memory_used_bytes` | gauge | — | `LoomControlChannel:233` |
| `cortex_memory_max_bytes` | gauge | — | `LoomControlChannel:234` |
| `cortex_cpu_load` | gauge | — | `LoomControlChannel:238` (`SystemLoadProbe`, 0 when unknown) |
| `cortex_io_load` | gauge | — | `LoomControlChannel:242` (busiest disk `%util`, 0 when unknown) |
| `cortex_disk_used_bytes` / `cortex_disk_total_bytes` | gauge | — | `LoomControlChannel:246,247` |
| `jvm_*`, `process_cpu_usage`, `vertx_*` | auto | — | `CortexBindModule.provideMeterRegistry()` + Vert.x built-ins |

### 4.1 `provider` label values actually emitted

| Provider | Node |
|---|---|
| `llm` | `LLMNode` |
| `smolvlm`, `video-vlm` | `CaptioningNode` |
| `vlm` | `VlmNode` (`METRICS_PROVIDER` constant) |
| `whisper` | `WhisperNode` |
| `tesseract` | `OCRNode` |
| `tts` | `TtsNode` |
| `sentiment` | `SentimentNode` |
| `imagegen` | `ImageGenNode` |
| `videogen` | `VideoGenNode` |
| `depthmap` | `DepthmapNode` |

Each node follows the same shape: `recordAiCacheHit(provider)` on the skip-cache path, then
`recordAiCall(provider, success, System.currentTimeMillis() - aiStart)` on both the success and the
failure branch. **AI-provider instrumentation stays on the Cortex node side** — the providers
(`OpenAILLMProvider`, `SmolVLMClient`, …) live in the separate **genai-utils** repo, so wrap the call
in the node rather than editing genai-utils.

---

## 5. Declared but never recorded / not implemented

**This is the gap list. Anything here must not be documented to customers or used in a dashboard.**

### 5.1 Registered in the Micrometer impl, but the catalog method has zero callers

The meter exists in `MicrometerCortexMetrics` and would work — nothing ever calls it, so the series
never appears in a scrape.

| Metric | Catalog method | Notes |
|---|---|---|
| `cortex_results_sent_total` | `CortexMetrics.recordResultsBatchSent(int)` | Intended site `ResultBatcher` (`cortex/node-runtime`) has **no** `CortexMetrics` dependency. |
| `cortex_results_batches_sent_total` | `CortexMetrics.recordResultsBatchSent(int)` | Same call — both counters are dead together. |
| `cortex_source_ack_timeouts_total` | `CortexMetrics.recordSourceAckTimeout()` | Intended site `SourceTaskRunner` never calls it. |

### 5.2 No registration and no catalog method — pure documentation fiction

| Metric | Component | Reality |
|---|---|---|
| `loom_node_tasks_inflight` | Loom | `loom/pipeline` has **zero** metrics instrumentation. `PipelineRunEngine` never sees `LoomMetrics`. |
| `loom_node_tasks_retried_total` | Loom | idem |
| `loom_node_tasks_deadlettered_total` | Loom | idem |
| `loom_node_circuit_breaker_trips_total` | Loom | `NodeKindCircuitBreaker` contains no `metric` reference at all. |
| `loom_result_store_flush_batch_size` | Loom | No summary is registered; `DaoRunStateStore` is uninstrumented. |
| `loom_processors_by_state` | Loom | Only `loom_processors_connected` exists; there is no per-state gauge. |
| `loom_processor_cpu_load` | Loom | `ProcessorRegistry` stores `SystemStatusInfo` but binds no gauge from it. |
| `loom_processor_memory_used_bytes` | Loom | idem |
| `cortex_results_pending` | Cortex | No `bindGauge("cortex_results_pending", …)` anywhere; `ResultBatcher.pendingFor()` is not bound. |

### 5.3 Partially dead labels

- `loom_auth_failures_total{type}` — **only `ws`** is emitted. `jwt` and `permission` are documented
  intent; no REST 401/403 path (`LoomRestException`) calls `recordAuthFailure`.

### 5.4 Never covered

- **c3p0 DB pool.** The jOOQ layer uses a c3p0 `ComboPooledDataSource` (`JooqModule`); Vert.x pool
  metrics (`loom_pool_*`) do **not** cover it. Busy/idle/pending connections are unmonitored.

---

## 6. Ports & Environment Variables

| Setting | Default | Env | Serves `/metrics`? |
|---|---|---|---|
| Cortex monitoring port | `8093` (`CortexOptions.monitoringPort` default) | `CORTEX_MONITORING_PORT` | ✅ shared with health/ready |
| Loom monitoring port | `8989` (`ServerOptions.DEFAULT_MONITORING_PORT`) | `LOOM_SERVER_MON_PORT` | ✅ dedicated server, `/metrics` only |
| Loom REST port | `8092` | `LOOM_SERVER_REST_PORT` | ❌ app surface |
| Loom MCP port | `4041` | `LOOM_SERVER_MCP_PORT` | ❌ |
| Loom gRPC port | `8091` | `LOOM_SERVER_GRPC_PORT` | ❌ |

- No new environment variables are introduced — the monitoring ports already existed.
- `ServerOptions.validate()` calls `checkDistinct(..., "monitoringPort", …)`, so 8989 cannot silently
  collide with another Loom server.
- **Test-mode port coupling:** `MonitoringService.start()` uses
  `restPort == 0 ? 0 : monitoringPort`. Setting `LOOM_SERVER_REST_PORT=0` therefore also moves the
  metrics server to an OS-assigned port — `LOOM_SERVER_MON_PORT` is ignored in that case.
- Dependency versions (managed in `bom/pom.xml`, consumers declare them **without** a version):
  `io.micrometer:micrometer-registry-prometheus` **1.14.6**, `io.vertx:vertx-micrometer-metrics`
  **${vertx.version} = 5.0.11**.

---

## 7. Key Classes Reference

| Class | Package / Module | Purpose |
|---|---|---|
| `LoomMetrics` | `loom/common` · `io.metaloom.loom.common.metrics` | Loom catalog **interface** (18 typed helpers + `bindGauge`). No Micrometer dependency. |
| `MicrometerLoomMetrics` | `loom/services/monitoring` · `io.metaloom.loom.monitoring` | Micrometer impl; the only place `loom_*` meter names are written. |
| `NoopLoomMetrics` | `loom/common` · `…common.metrics` | No-op for tests / manual construction. |
| `MonitoringService` (Loom) | `loom/services/monitoring` · `io.metaloom.loom.monitoring` | Own `HttpServer`; registers `GET /metrics` via `PrometheusScrapingHandler`. Mirrors `MCPService`. |
| `MonitoringModule` | `loom/services/monitoring` · `…monitoring.dagger` | **Only** `@Binds LoomMetrics ← MicrometerLoomMetrics`. It does *not* provide the registry. |
| `VertxModule` | `loom/common` · `io.metaloom.loom.common.dagger` | `@Provides PrometheusMeterRegistry` + JVM/process binders, and `Vertx vertx(registry)`. |
| `BootstrapInitializer` | `loom/core` · `io.metaloom.loom.core.boot` | `monitoringService.start()` (:139) / `.stop()` (:173). |
| `CortexMetrics` | `cortex/common` · `io.metaloom.cortex.common.metrics` | Cortex catalog **interface**. |
| `MicrometerCortexMetrics` | `cortex/core` · `io.metaloom.cortex.impl.monitoring` | Micrometer impl; the only place `cortex_*` names are written. |
| `NoopCortexMetrics` | `cortex/common` · `…common.metrics` | `INSTANCE` default for offline/CLI paths and `AbstractMediaNode`. |
| `MetricsEndpoint` | `cortex/core` · `io.metaloom.cortex.impl.monitoring` | Registers `GET /metrics` on the monitoring router. |
| `CortexBindModule` | `cortex/core` · `io.metaloom.cortex.cli.dagger` | `provideMeterRegistry()` (+ binders), `provideVertx(registry)`, `@Binds CortexMetrics`. |
| `AbstractMediaNode` | `cortex/common` · `…common.node` | Field-injected `protected CortexMetrics metrics = NoopCortexMetrics.INSTANCE` (:44) — how AI nodes get the catalog without constructor churn. |
| `PipelineTaskHandler` | `cortex/core` · `io.metaloom.cortex.impl.loom` | The single choke point for task/node-op counters. |

---

## 8. Conventions & Gotchas

- **Meter names omit `_total` / `_seconds`.** Code registers `cortex_node_operations`; Prometheus
  exports `cortex_node_operations_total`. Timers named `cortex_task_duration` export
  `cortex_task_duration_seconds_*`. The tables above list the **scraped** names.
- **Metrics are on the monitoring port, never the REST port.** Conversely, Loom's monitoring server
  serves *only* `/metrics` — `GET /api/v1/health` on 8989 **404s** (asserted by
  `MonitoringServiceTest.shouldNotServeRestPaths`).
- **Registry-before-Vertx ordering.** Express it as a Dagger provider parameter. Keep
  `setJvmMetricsEnabled(false)` on `MicrometerMetricsOptions` — the JVM binders are attached
  explicitly at registry construction and would otherwise be registered twice.
- **Scrape the registry directly.** Both endpoints use `PrometheusScrapingHandler.create(registry)`,
  not the no-arg overload, so the route does not depend on Vert.x's global backend-registry lookup.
- **Bounded label cardinality.** Label by node kind, status, message type, provider, or a stable
  `node_id`. **Never** by asset UUID, file path, user, or run UUID.
  ⚠️ Two existing labels are only *conditionally* bounded: `loom_tasks_returned_total{node}` is the
  worker node id, and on the **segment** path `cortex_node_operations{node_kind}` is filled from
  `nodeResult.getNodeId()` (`PipelineTaskHandler:327`) rather than a node *kind*.
- **Go through the catalog.** Inject `LoomMetrics` / `CortexMetrics`; do not scatter
  `Counter.builder(...)` calls. Adding a meter means: interface method → Micrometer impl → `Noop`
  impl → call site → row in §3/§4 here.
- **A meter is not "implemented" until something calls it.** §5 exists because three catalog methods
  were shipped with no caller. When adding a helper, add the call site in the same change or list it
  in §5.
- **Node operations are counted once, in `PipelineTaskHandler`** (the online task-driven choke
  point). `AbstractMediaNode.process()` records only `cortex_files_missing`. The pure offline CLI
  path is **not** node-op-counted — the primary deployment is the online daemon.
- **`bindGauge` is idempotent per name** and uses `strongReference(true)`, so the supplier's captured
  state is not collected. Bind gauges to existing live state rather than mirroring it into a counter.
- **Interface upstream, impl downstream.** The catalog interface sits in `*-common` (no Micrometer
  dependency), reachable by every layer; the Micrometer impl and its dependency stay in the service /
  core module and are wired with `@Binds`.
- **`/metrics` is unauthenticated by design.** Front the monitoring port with network policy; do not
  add per-request auth here.

---

## 9. Test Setup

| Test | Module | Covers |
|---|---|---|
| `MetricsEndpointTest` | `cortex/core` (`…impl.monitoring`) | Boots a router with `MetricsEndpoint` on port 0, records `cortex_node_operations` + `cortex_files_missing`, asserts a 200 scrape containing both plus `jvm_memory_used_bytes`. |
| `MonitoringServiceTest` | `loom/services/monitoring` | Starts `MonitoringService` with `restPort=0`; asserts `/metrics` → 200 containing `loom_pipeline_runs_completed_total` + `jvm_memory_used_bytes`, and `/api/v1/health` on the monitoring port → **404**. |

Neither test needs a database, so `./setup-pool.sh` is not required for them (it *is* required for
any Loom REST/DAO test you add alongside). No Flyway migration is involved, so no jOOQ regeneration.

**Gaps in test coverage:**
- No test asserts the **REST** port 404s on `/metrics` (only the mirror-image assertion exists).
- No unit test per catalog helper — most `loom_*` / `cortex_*` names are only exercised through the
  two smoke tests above. A `registry.scrape()`-contains assertion per helper would have caught §5.

---

## 10. Where do I find …?

| I want to … | Look at |
|---|---|
| The list of Loom meter names | `loom/services/monitoring/src/main/java/io/metaloom/loom/monitoring/MicrometerLoomMetrics.java` |
| The list of Cortex meter names | `cortex/core/src/main/java/io/metaloom/cortex/impl/monitoring/MicrometerCortexMetrics.java` |
| The Loom catalog contract | `loom/common/src/main/java/io/metaloom/loom/common/metrics/LoomMetrics.java` |
| The Cortex catalog contract | `cortex/common/src/main/java/io/metaloom/cortex/common/metrics/CortexMetrics.java` |
| Where the registry + JVM binders are created (Loom) | `loom/common/src/main/java/io/metaloom/loom/common/dagger/VertxModule.java` |
| Where the registry + JVM binders are created (Cortex) | `cortex/core/src/main/java/io/metaloom/cortex/cli/dagger/CortexBindModule.java` |
| The Loom `/metrics` route | `loom/services/monitoring/src/main/java/io/metaloom/loom/monitoring/MonitoringService.java` |
| The Cortex `/metrics` route | `cortex/core/src/main/java/io/metaloom/cortex/impl/monitoring/MetricsEndpoint.java` |
| Most Cortex task/node-op instrumentation | `cortex/core/src/main/java/io/metaloom/cortex/impl/loom/PipelineTaskHandler.java` |
| Cortex resource gauges | `cortex/core/src/main/java/io/metaloom/cortex/impl/loom/LoomControlChannel.java` (:230-247) |
| How an AI node instruments a provider call | `cortex/nodes/llm/core/.../LLMNode.java` (:106-130) — the reference shape |
| Port defaults / validation | `loom-shared/api/.../options/ServerOptions.java`, `cortex/api/.../option/CortexOptions.java` |
| Health & readiness (not metrics) | [MONITORING.md](MONITORING.md) |
| Customer-facing metric catalogs | `website/content/english/docs/loom/metrics/index.adoc`, `website/content/english/docs/cortex/metrics/index.adoc` |

---

## 11. Progress Assessment

**Transport — done**

- [x] Shared `PrometheusMeterRegistry` + JVM/process binders (Loom `VertxModule`, Cortex `CortexBindModule`)
- [x] Vert.x built-in metrics on both `Vertx` instances via `MicrometerMetricsFactory`
- [x] Cortex `GET /metrics` (`MetricsEndpoint`) on the monitoring server (8093)
- [x] Loom `MonitoringService` binding `LOOM_SERVER_MON_PORT` (8989), wired via `MonitoringModule` + `BootstrapInitializer`
- [x] `LoomMetrics` / `CortexMetrics` catalogs with Micrometer + Noop implementations
- [x] Smoke tests: `MetricsEndpointTest`, `MonitoringServiceTest`
- [x] Customer-facing metric catalogs on the website (`docs/loom/metrics/`, `docs/cortex/metrics/`)

**Instrumentation — partial**

- [x] Loom: pipeline runs, dispatch, results ingestion, workers, leases, event broadcast, recovery
- [x] Cortex: control channel, tasks, node ops, missing files, bulk sync, source enumeration, AI calls (11 nodes), resource gauges

**Open work items (each corresponds to a row in §5)**

- [ ] Wire `recordResultsBatchSent` into `ResultBatcher` (`cortex/node-runtime`) — unblocks
      `cortex_results_sent_total` + `cortex_results_batches_sent_total`
- [ ] Wire `recordSourceAckTimeout` into `SourceTaskRunner` — unblocks `cortex_source_ack_timeouts_total`
- [ ] Bind `cortex_results_pending` to `ResultBatcher.pendingFor()`
- [ ] Instrument `loom/pipeline`: in-flight gauge, retries, dead-letters, circuit-breaker trips
      (`PipelineRunEngine`, `NodeKindCircuitBreaker` currently have no `LoomMetrics` reference at all)
- [ ] `loom_result_store_flush_batch_size` summary in `DaoRunStateStore`
- [ ] `loom_processors_by_state`, `loom_processor_cpu_load`, `loom_processor_memory_used_bytes` from
      `ProcessorRegistry`'s stored `SystemStatusInfo`
- [ ] Emit `loom_auth_failures_total{type=jwt|permission}` from the REST 401/403 path
- [ ] c3p0 DB-pool gauges (`JooqModule`) — Vert.x pool metrics do not cover it
- [ ] Per-helper unit tests asserting `registry.scrape()` output, so a helper cannot ship without a caller
- [ ] **Customer-facing doc leak:** `website/content/english/docs/cortex/metrics/index.adoc` documents
      three §5.1 meters as if live — `cortex_results_sent_total` (:83), `cortex_results_batches_sent_total`
      (:84), `cortex_source_ack_timeouts_total` (:87) — and its PromQL example (:90) compares
      `rate(cortex_results_sent_total)` against `rate(cortex_node_operations_total)`, which always
      reads zero. Remove them or land the wiring above. `docs/loom/metrics/index.adoc` is clean.

---

_Git HEAD revision: `4dc0390a`_
_Last updated: 2026-08-03 (the `cortex_ai_calls_total` provider label for `LLMNode` is now `llm`)_
