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
| **1** | [Measure the round-trip saving](#1-measure-the-round-trip-saving) | The entire justification for Variant C is unmeasured |
| **2** | [Decide on decode-once](#2-decide-whether-decode-once-is-wanted) | A benchmark disproved the stated reason for affinity |
| **3** | [Fix the DAO test pool collisions](#3-fix-the-dao-test-pool-collisions) | 18 tests have not passed from a clean pool in some time |
| **4** | [Correct the standalone-Cortex claims](#4-correct-the-standalone-cortex-claims) | Docs describe a capability that was removed |
| **5** | [Run inspection API](#5-run-inspection-api) | State is recorded and unreachable |
| **6** | [Fix `cpuLoad`, then schedule on it](#6-fix-cpuload-then-schedule-on-it) | Blocks load-aware placement |
| **7** | [Per-kind concurrency ceiling](#7-per-kind-concurrency-ceiling) | One slow kind can occupy a whole run |
| **8** | [Settle the shared-storage model](#8-settle-the-shared-storage-model) | Determines whether any worker can run any node |
| **9** | [Sync more than hashes](#9-sync-more-than-hashes) | Most node output still never reaches an asset |
| **10** | [Stable worker identity](#10-stable-worker-identity) | Leases and attribution key on an unstable id |
| **11** | [Secure the control channel](#11-secure-the-control-channel) | Unauthenticated dispatch surface |
| **12** | [Prometheus metrics](#12-prometheus-metrics) | No operational visibility |
| **13** | [Graceful shutdown with drain](#13-graceful-shutdown-with-drain) | Scale-down relies on lease expiry |
| **14** | [Batching](#14-dispatch-and-result-batching) | Deliberately blocked on task 1 |
| **15** | [Smaller corrections](#15-smaller-corrections) | Known, bounded, low-risk |

---

## 1. Measure the round-trip saving

**The most important open item.** Variant C's cost is one network round trip per
node per item. Affinity segments exist to reduce it. **Neither the cost nor the
saving has ever been measured against a real deployment.**

`SegmentDispatchBenchmark` measures only the worker side and found it negligible
(1.01× over 155 MiB). The saving that remains plausible — fewer round trips — is
exactly what that harness cannot see, because it has no socket and no Loom.

- [ ] Stand up Loom + ≥1 Cortex against the 155 MiB test corpus
- [ ] Run one pipeline **grouped** (single affinity) and **ungrouped** (per-node)
- [ ] Record wall time, message counts, per-kind task durations
- [ ] Compare against the pre-Variant-C in-process executor if a build still exists

**Done when** there is a number for what a round trip costs, and therefore for what
affinity is worth. Tasks 7 and 14 both depend on the per-kind durations.

---

## 2. Decide whether decode-once is wanted

A benchmark disproved the stated justification for affinity. Segmenting resolves
the media *handle* once but does **not** stop each node reading the file: there is
no shared decoded state, because the node API has nowhere to put it — nodes
receive upstream *outputs*, and a frame buffer is not an output.

- [ ] Decide whether decode-once is genuinely required for video pipelines
- [ ] If yes: design a per-segment shared context nodes can publish expensive
      artifacts into, with an explicit lifecycle (who evicts, and when)
- [ ] If no: record that affinity is a round-trip optimisation only, and stop
      describing it as anything else

⚠️ This is a **node API change**, not a scheduling one. No phase of the Variant C
plan scoped it.

---

## 3. Fix the DAO test pool collisions

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

## 4. Correct the standalone-Cortex claims

Decision Q1 removed standalone pipeline execution: Cortex answers `NODE_TASK`,
`SEGMENT_TASK` and `SOURCE_TASK`, and holds no pipelines. The root README,
`cortex/README.md` and the website still describe it as able to run pipelines
alone.

- [ ] Update all three to describe Cortex as a worker
- [ ] State plainly what offline use remains (the legacy `cortex process run
      --actions` path)

Flagged as blocking the Phase 1 ship; still not done.

---

## 5. Run inspection API

`pipeline_run_item` and `pipeline_node_task` record where every item is, what
failed and what is retrying. **No REST surface exposes any of it.** The DAO queries
already exist (`loadPageByRun`, `countByRunAndState`, `loadUnfinishedByRun`).

- [ ] `GET /api/v1/pipelines/runs/:uuid/items` — paged, filterable by state
- [ ] `GET /api/v1/pipelines/runs/:uuid/tasks` — per-node detail including
      `leased_by` and attempt history
- [ ] Guard with the existing `READ_PIPELINE_RUN` permission
- [ ] Per-item opt-in event stream for debugging a single item

---

## 6. Fix `cpuLoad`, then schedule on it

`cpuLoad` is computed incorrectly and `ioLoad` is never populated, so worker
selection uses declared priority only. Scheduling on a wrong metric is worse than
not scheduling, which is why load-aware placement was deliberately not built.

- [ ] Fix the calculation; populate `ioLoad`
- [ ] Only then: prefer workers by live load in `selectProcessor` /
      `selectProcessorForKinds`
- [ ] Drain-aware placement — never place work on a worker announcing `TERMINATING`

---

## 7. Per-kind concurrency ceiling

The circuit breaker isolates a *broken* kind. It does not stop a *slow* one from
occupying every slot: the only ceiling today is the per-run `maxInFlight`, so a
whisper-heavy graph can starve hashing within the same run.

- [ ] Per-kind in-flight ceiling alongside the per-run one
- [ ] Derive defaults from the per-kind durations produced by task 1

---

## 8. Settle the shared-storage model

Every worker assumes it can open any path Loom sends. Nothing verifies that, and a
worker that cannot see the file fails every task it is given.

- [ ] Decide: shared mount for all workers, or per-worker visible roots?
- [ ] If per-worker: advertise visible roots at registration and filter dispatch by
      them, exactly as node kinds are filtered
- [ ] Fail at dispatch when no worker can see a path, rather than on the worker

Composes with the node whitelist: "can run this kind" and "can see this file" are
both placement constraints.

---

## 9. Sync more than hashes

`LoomNode` writes hashes back to the asset. Output from every other kind —
thumbnails, embeddings, OCR text, transcripts, detections — is computed, persisted
into `pipeline_node_task.outputs`, and then **never mapped onto the asset**.

- [ ] Map non-hash node outputs to asset fields, per kind
- [ ] Honour `syncToLoom` from the definition — it is parsed into the graph and
      read by nothing
- [ ] Verify the end-of-run flush drains (`flush-sync` is the only remaining
      work-order command)

---

## 10. Stable worker identity

Worker ids are not stable across restarts, yet `leased_by`, lease reclaim and
origin attribution all key on them — so a restarted worker is a different worker
as far as the system is concerned.

- [ ] Persist worker identity across restarts
- [ ] Reconcile with heartbeat eviction so a returning worker reclaims its identity
      rather than duplicating it

---

## 11. Secure the control channel

The processor WebSocket accepts registration and dispatches work with no
authentication. Under Variant C this surface is larger than it was: it carries task
payloads and results, not just status.

- [ ] Authenticate the processor connection
- [ ] Authorise per worker — a worker should not answer for a run it was never given
- [ ] Rate-limit registration

---

## 12. Prometheus metrics

- [ ] Dispatch latency, queue depth, per-kind failure rate
- [ ] Circuit breaker state per kind
- [ ] Lease reclaim rate — the clearest signal of a sick fleet
- [ ] In-flight vs ceiling per run

---

## 13. Graceful shutdown with drain

A worker stopped mid-task relies on lease expiry to have its work reassigned, which
costs a full lease interval per task.

- [ ] Announce `TERMINATING`; stop accepting new tasks
- [ ] Finish or explicitly return in-flight tasks
- [ ] Have Loom reclaim returned tasks immediately rather than waiting out the lease

---

## 14. Dispatch and result batching

**Deliberately blocked on task 1.** §7.2 of the plan requires batch sizes "derived
from observed per-task duration per kind" and warns that fixed sizes get it wrong
in both directions. Those durations do not exist yet.

⚠️ Its premise also needs re-examining: batching was justified partly by the same
re-read intuition the benchmark disproved. The real benefit is fewer messages —
the same benefit affinity delivers, and equally unmeasured.

- [ ] Re-derive the case from task 1's numbers
- [ ] If justified: `NODE_TASK_BATCH` / `NODE_TASK_RESULT_BATCH`, N items × one
      node × one worker
- [ ] **Per-item outcomes, never one status per batch** — otherwise one bad file
      fails 500 items

---

## 15. Smaller corrections

- [ ] **`activeCount` / `pendingCount` in `NODE_STATS`** — still absent; the engine
      knows both (`getInFlightCount`, deferred nodes)
- [ ] **Filter the Cortex `PIPELINE_EVENT` passthrough** — it bypasses the
      aggregator. Nothing should send them under Variant C, so it is dead weight
      rather than an active flood, but it contradicts the aggregation policy
- [ ] **Make `FilesystemMediaScanner` lazy** — it materialises the whole selection,
      defeating source backpressure
- [ ] **`NodeDescriptorEndpointTest`** — 2 failures; the endpoint returns a JSON
      object where the test decodes an array. Only coverage of the descriptor
      surface the UI palette depends on
- [ ] **13 `*ModelBuilderTest` classes** — 16 failures + 6 errors, pre-existing and
      unchanged throughout Phases 1–3
- [ ] **Hash node tests** report `SKIPPED` where `SUCCESS` is expected
- [ ] **`CombinedEndpointTest`** — 404 on `/api/v1/locations`
- [ ] **Version the pipeline definition format** — decision Q5 said to version from
      the start; it has since gained `syncToLoom`, filter branches, options and
      `affinity` with no version field
- [ ] **`loom/db/README.md`** advertises `fs`, `memory` and `hibernate`
      implementations; only `jooq` is live
- [ ] **Task state retention policy** — 1 000 000 task rows per run is real. How
      long are they kept, and at what granularity afterwards?

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
