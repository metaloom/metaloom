# MetaLoom Architecture — Open Tasks (Variant C)

_Last updated: 2026-07-19_

**Scope.** Pending work only, for the architecture MetaLoom actually has:
**Variant C** — Loom owns the pipeline graph and dispatches individual node tasks
(or affinity segments) to Cortex workers.

This list replaces the former split between this file and
`METALOOM_ARCHITECTURE_V2_TASK.md`; both are merged here. Ideas belonging to the
rejected variants — the hierarchical worker tree, multi-site federation, Cortex as
a coordinator, cross-instance delegation, recursive worker protocols — have been
**dropped**, not deferred. They were designed against a topology this system no
longer plans to have. See [Dropped](#dropped).

Deferred design work lives in [../tasks/TASKS.md](../tasks/TASKS.md).

Completed work is not listed. For what exists and why, see
[METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md); for the phase-by-phase
record, [METALOOM_ARCHITECTURE_V2_PLAN_C.md](METALOOM_ARCHITECTURE_V2_PLAN_C.md).

Related list, referenced rather than duplicated:
[../features/pipeline/PIPELINE_TASKS.md](../features/pipeline/PIPELINE_TASKS.md)
— pipeline internals, definition schema, node registration.

---

## Priority

| # | Task | Why it is where it is |
|---|---|---|
| **1** | [Fix the DAO test pool collisions](#1-fix-the-dao-test-pool-collisions) | 18 tests have not passed from a clean pool in some time |
| **2** | [Correct the standalone-Cortex claims](#2-correct-the-standalone-cortex-claims) | Docs describe a capability that was removed |
| **3** | [Run inspection API](#3-run-inspection-api) | State is recorded and unreachable |
| **4** | [Settle the shared-storage model](#4-settle-the-shared-storage-model) | Determines whether any worker can run any node |
| **5** | [Sync more than hashes](#5-sync-more-than-hashes) | Most node output still never reaches an asset |
| **6** | [Stable worker identity](#6-stable-worker-identity) | Leases and attribution key on an unstable id |
| **7** | [Secure the control channel](#7-secure-the-control-channel) | Unauthenticated dispatch surface |
| **8** | [Prometheus metrics](#8-prometheus-metrics) | No operational visibility |
| **9** | [Graceful shutdown with drain](#9-graceful-shutdown-with-drain) | Done for node and segment tasks; a source task in progress and a worker that crashes still cost a lease interval |
| **10** | [Smaller corrections](#10-smaller-corrections) | Known, bounded, low-risk |

---

## 1. Fix the DAO test pool collisions

18 tests in `loom/db/jooq` fail with `duplicate key … _pkey` because fixtures
seeded by `PoolSetupRunner` collide with UUIDs the tests insert. Confirmed
pre-existing and unrelated to Variant C work by stashing and re-running.

Affected: `PipelineDaoTest`, `BlacklistDaoTest`, `AssetLocationDaoTest`,
`TokenDaoTest`, `PipelineVersionDaoTest`, `PoolingTest`, `RxDaoTest`.

- [ ] Give fixtures and test elements disjoint UUIDs, or stop assuming empty tables
- [ ] ⚠️ Document that **the pool must be re-seeded after every migration**
      (`PoolSetupRunner` in `loom/fixture`) — every DAO test fails with
      `relation … does not exist` until it is, and this is written down nowhere near
      the migration directory

**Done when** `mvn test -pl loom/db/jooq` is green from a freshly seeded pool.

---

## 2. Correct the standalone-Cortex claims

Decision Q1 removed standalone pipeline execution: Cortex answers `NODE_TASK`,
`SEGMENT_TASK` and `SOURCE_TASK`, and holds no pipelines. The root README,
`cortex/README.md` and the website still describe it as able to run pipelines
alone.

- [ ] Update all three to describe Cortex as a worker
- [ ] State plainly what offline use remains (the legacy `cortex process run
      --actions` path)

Flagged as blocking the Phase 1 ship; still not done.

---

## 3. Run inspection API

`pipeline_run_item` and `pipeline_node_task` record where every item is, what
failed and what is retrying. **No REST surface exposes any of it.** The DAO queries
already exist (`loadPageByRun`, `countByRunAndState`, `loadUnfinishedByRun`).

- [ ] `GET /api/v1/pipelines/runs/:uuid/items` — paged, filterable by state
- [ ] `GET /api/v1/pipelines/runs/:uuid/tasks` — per-node detail including
      `leased_by` and attempt history
- [ ] Guard with the existing `READ_PIPELINE_RUN` permission
- [ ] Per-item opt-in event stream for debugging a single item

---

## 4. Settle the shared-storage model

Every worker assumes it can open any path Loom sends. Nothing verifies that, and a
worker that cannot see the file fails every task it is given.

- [ ] Decide: shared mount for all workers, or per-worker visible roots?
- [ ] If per-worker: advertise visible roots at registration and filter dispatch by
      them, exactly as node kinds are filtered
- [ ] Fail at dispatch when no worker can see a path, rather than on the worker

Composes with the node whitelist: "can run this kind" and "can see this file" are
both placement constraints.

---

## 5. Sync more than hashes

**Largely superseded.** This item was written when `LoomNode` was the sync point and
every other kind's output stopped at `pipeline_node_task.outputs`. Per-node
persistence overtook it: each result-producing node now writes its own typed payload
plus an `asset_node_result` ledger row from inside `compute()` — transcripts, faces,
fingerprints, scenes, OCR/Tika/LLM/caption/quality JSON, hashes and consistency all
reach the asset that way. `LoomNode` was deleted once it could only duplicate writes
its producers had already made ([NODES.md](../features/pipeline-nodes/NODES.md) §2).

What genuinely remains:

- [ ] Decide the fate of `syncToLoom` / `LoomBulkSyncCollector` / `DaoAssetSink`.
      `syncToLoom` is still parsed into the graph and read by nothing, and
      `DaoAssetSink` maps three port ids (`sha512`/`sha256`/`md5`) that no hash node
      emits — the descriptors name the port `hash` and distinguish algorithms by
      content type. Either select by `hash/*` subtype and finish the path, or delete
      it as `LoomNode` was deleted. Do not leave a third half-wired write path.
- [ ] Batch the per-node REST writes in the client layer. One call per node per asset
      is the real cost `LoomNode`'s batching was paying down; solving it below the
      nodes helps every kind rather than only hashes.

The end-of-run flush no longer applies — nothing buffers, and the `flush-sync`
work-order command was removed (superseded — see PIPELINE.md §12).

---

## 6. Stable worker identity

Worker ids are not stable across restarts, yet `leased_by`, lease reclaim and
origin attribution all key on them — so a restarted worker is a different worker
as far as the system is concerned.

- [ ] Persist worker identity across restarts
- [ ] Reconcile with heartbeat eviction so a returning worker reclaims its identity
      rather than duplicating it

---

## 7. Secure the control channel

The processor WebSocket accepts registration and dispatches work with no
authentication. Under Variant C this surface is larger than it was: it carries task
payloads and results, not just status.

- [ ] Authenticate the processor connection
- [ ] Authorise per worker — a worker should not answer for a run it was never given
- [ ] Rate-limit registration

---

## 8. Prometheus metrics

- [ ] Dispatch latency, queue depth, per-kind failure rate
- [ ] Circuit breaker state per kind
- [ ] Lease reclaim rate — the clearest signal of a sick fleet
- [ ] In-flight vs ceiling per run

---

## 9. Graceful shutdown with drain

A cortex worker stopped mid-task used to rely on lease expiry to have its work
reassigned, which cost a full lease interval (10 minutes) per task.

- [x] Announce `TERMINATING` — `LoomControlChannel#drain`, reached from
      `CortexBootstrapInitializer#deinit`. A JVM shutdown hook now runs the graceful
      shutdown, so `SIGTERM` reaches it at all; previously the hook only flushed the
      sync buffer
- [x] Finish or explicitly return in-flight tasks — `PipelineTaskHandler` tracks what
      it is holding, refuses dispatches that arrive after the announcement, waits out
      `--drain-timeout-ms`, then sends `TASK_RETURNED` for the rest
- [x] Have Loom reclaim returned tasks immediately rather than waiting out the lease —
      `PipelineRunEngine#onNodeTaskReturned` re-places at once and **refunds the
      attempt**, since nothing ran and nodes are not retryable by default. The refund
      is capped at three per execution so a misbehaving worker cannot circulate an item
      forever

Protocol in [../loom/WEBSOCKET.md §3.8.1](../loom/WEBSOCKET.md), worker side in
[CORTEX.md §7.4](CORTEX.md).

Remaining:

- [ ] **A source task in progress cannot be handed back.** There is no reclaim path
      for one, and fabricating a `SOURCE_COMPLETE` would record a truncated scan as a
      whole one. The drain waits for it, then logs and abandons it — the run waits for
      an enumeration that never resumes and has to be dispatched again. Needs either a
      source-lease equivalent or a resumable enumeration
- [ ] **Loom does not react to a worker vanishing.** A crash (as opposed to a drain)
      still costs a lease interval. `ProcessorRegistry#disconnect` knows which worker
      went away and `PipelineNodeTaskDao#countLeasedBy` already keys on it, so
      reclaiming that worker's tasks on disconnect is the natural companion to this

---

## 10. Smaller corrections

- [x] **Filter the Cortex `PIPELINE_EVENT` passthrough** — removed on both sides.
      Cortex no longer subscribes its tracking bus to the control channel (nothing
      published to it outside the node-chain tests), and `ProcessorEndpoint` now
      **drops** a worker-sent `PIPELINE_EVENT` instead of broadcasting it, logging
      once per processor. Under Variant C every run event is
      `RunStatsAggregator`'s to emit. `PipelineEventEndpointTest` now asserts the
      drop; fan-out moved to a direct `PipelineEventBroadcasterTest`.
      Protocol in [../loom/WEBSOCKET.md §4.6b](../loom/WEBSOCKET.md)
- [x] **Make `FilesystemMediaScanner` lazy** — added `stream(List<String>)`,
      `stream(String)` and `stream(Path)`, which walk on demand and de-duplicate as
      they go; `expand`/`walk` remain as thin materialising wrappers.
      `FilesystemSourceNode` consumes the glob path via `Flowable.fromStream`, so
      backpressure and cancellation now reach the filesystem and the count is
      logged at the end rather than up front. The differential (root) path stays
      eager — a differential scan has no result until it has compared the whole tree
- [x] **`NodeDescriptorEndpointTest`** — the object-vs-array decode had already been
      fixed; the remaining failure was stale field names. Descriptors carry
      `inputPorts` / `outputPorts` under the typed-port model, not `inputs` /
      `outputs`. `testSourceNodesHaveNoInputs` was passing vacuously on the absent
      key for every node
- [x] **13 `*ModelBuilderTest` classes** — no longer failing. All 14 concrete classes
      pass (28 tests, 0 failures, 0 errors); resolved by earlier phase work
- [x] **Hash node tests** report `SKIPPED` where `SUCCESS` is expected — three
      separate causes, one of them a production bug:
      - `SHA256Node`, `MD5Node` and `ChunkHashNode` dereferenced
        `asset.getHashes()` without a null check. It is null on every asset between
        creation and its first hash node run, so an ordinary state failed the task
      - `MD5NodeTest`, `SHA256NodeTest` and `ChunkHashNodeTest` never stubbed
        `isEnabled()`, so the mock answered false and `process()` short-circuited to
        `SKIPPED("Disabled")` before even the missing-file check. `ChunkHashNodeTest`
        also stubbed `isMD5` where `ChunkHashNode` reads `isChunkHash`
      - `AbstractBasicNodeTest` asserted the second run is `SKIPPED`. A cache hit
        returns `SUCCESS` with origin `LOCAL` — it still emits on the output port,
        and `SKIPPED` would say "produced nothing" and starve downstream nodes. Now
        asserted as `COMPUTED` then `LOCAL`
      - Separately, `testPullFromLoom` never exercised the remote path:
        `loadAsset(SHA512)` is a default method that a mock stubs to null, so the
        node quietly computed and the assertion passed anyway
- [x] **`CombinedEndpointTest`** — the `/locations` route was already migrated to
      `/binaries`. The remaining failure was its pipeline fixture: a lone `sha512`
      node whose required `media` input nothing feeds, which port validation
      correctly rejects. Now a valid `filesystem-source → sha512` graph
- [x] **Version the pipeline definition format** — top-level `version` integer.
      `PipelineGraphParser.CURRENT_DEFINITION_VERSION` is what Loom writes and the
      highest it reads; absent means 1 (definitions predate the field); newer is
      refused by name rather than half-read; malformed is refused.
      `stampVersion` runs on the REST create/update paths and in
      `DemoDatabaseInitializer`, so the untagged set only shrinks. Format and the
      bump rule in [../features/pipeline/PIPELINE.md §9.2](../features/pipeline/PIPELINE.md)
- [x] **`loom/db/README.md`** no longer advertises the removed `fs` and `hibernate`
      implementations; `jooq` is the production impl, `memory` is test-only
- [ ] **Task state retention policy** — **decided, not yet enforced.** 7 days of
      per-item and per-task detail after a run finishes, 30 days for `FAILED` /
      `DEAD_LETTER`, and the `pipeline_run` row with its counters forever — so the
      granularity afterwards is the run row. Windows, rationale and the constraints
      a sweep must respect are in
      [../features/pipeline/PIPELINE.md §10.1a](../features/pipeline/PIPELINE.md).
      What remains is the batched sweep itself: a DAO bulk delete, a reaper on the
      `SandboxReaper` pattern, and the configuration to turn it off

---

## Dropped

Recorded so nobody re-derives them and wonders why they vanished.

| Idea | Why dropped |
|---|---|
| Hierarchical worker tree (a master Cortex delegating to leaves) | Fault recovery gets *worse* with an in-memory master; the throughput premise was never established |
| Multi-site federation | No confirmed requirement |
| Recursive worker protocol | Existed only to make the tree a deployment choice |
| Cortex coordinator role, subtree aggregation, cross-instance delegation | All presuppose the tree |
| Deferred / async node SPI | Orthogonal to Variant C; revisit only against a concrete need |
| Variant D as a separate design | Segmented dispatch *is* what was built — affinity segments, with per-node dispatch as the degenerate case |
| Node capability whitelist, node affinity, durable item queue with leases | **Built**, not dropped — see the architecture doc |
