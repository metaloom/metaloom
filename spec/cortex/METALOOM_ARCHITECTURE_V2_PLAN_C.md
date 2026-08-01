# Variant C — Implementation Plan

> **Status: COMPLETE.** Every phase this plan describes has been implemented and
> verified. What is left is a record of the decisions and the order of the work,
> plus the small set of refinements that were designed here and deliberately not
> built (section 3).
>
> Loom owns the pipeline graph and dispatches node tasks — or whole affinity
> segments — to Cortex workers. Run state is durable, leases reclaim work from
> dead workers, a restart resumes, and results can be batched.
>
> **For what the system does today, read
> [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) instead** — it is written
> for readers rather than as a build record. Open work is in
> [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md).
>
> The variant comparison that justified this choice has been deleted as outdated:
> Variant C is no longer one option among four, it is the architecture.

---

## 1. Decisions taken

Recorded 2026-07-18. These were the open questions blocking Phase 1 start.

| # | Question | Decision | Consequence |
|---|---|---|---|
| Q1 | Must standalone Cortex pipeline execution survive? | **No — Loom-only is acceptable** | `node-runtime` needs no local driver. Offline use is limited to the legacy `cortex process run --actions` path. Note: The README and website claims about standalone use must be updated before Phase 1 ships |
| Q4 | Push or pull dispatch? | **Push for Phase 1** | Loom sends `NODE_TASK` when a node becomes ready. Accepted risk: Phase 2 leases favour pull, so the protocol will change twice |
| Q5 | Version the definition format? | **Yes, from the start** | The format gains `syncToLoom`, filter branches, and typed options; version it before the first breaking change rather than after |

**Sequencing:** every phase was executed step by step rather than as one large
change, so each step was independently reviewable and left the build green.

### Step status

| Step | Scope | Status |
|---|---|---|
| **P1.1** | Invert the Loom→Cortex dependency; extract `loom-shared/node-model` | **done** |
| **P1.2** | `loom/pipeline` module: graph model + engine against a fake dispatcher | **done** |
| **P1.3** | Protocol: fix the envelope, add `SOURCE_*` / `NODE_TASK` messages | **done** |
| **P1.4** | `cortex/node-runtime` + source runner; Cortex answers tasks | **done** |
| **P1.5** | Test migration (10 test classes) | **done** |
| **P1.6** | Wire end to end; run driven by the engine | **done** |
| **P1.7** | Delete the old engine + rewire Dagger (deferred from P1.5) | **done** |

**P1.1 verification:** full reactor `install` green; 5 new ServiceLoader guard
tests; 23 `PipelineValidationServiceTest` cases (the real descriptor consumer)
green; 187 Cortex node/pipeline tests green.

**Note:** **Pre-existing failure, unrelated to this work.**
`NodeDescriptorEndpointTest` has 2 of 6 tests failing
(`testListAllNodeDescriptors`, `testFilterNodesHaveFilterCategory`): the
endpoint returns a JSON *object* where the test decodes a JSON *array*, so the
client times out after 10 s. **Confirmed identical on the pre-change tree**, so
P1.1 neither caused nor fixed it. It should be fixed on its own — it is the only
coverage of the descriptor REST surface the UI palette depends on.

## 2. What was built, step by step

Every step landed with tests and a green reactor build. The detail of *what each
component does and why* now lives in
[METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §14–§15; this table is the
record of **when and in what order**, plus the decisions that would otherwise be
invisible.

### Phase 1 — restructuring and first delegation — COMPLETE

| Step | Scope | Notes |
|---|---|---|
| **P1.1** | Invert the Loom→Cortex dependency; extract `loom-shared/node-model` | 17 `cortex/nodes/*-api` modules deleted; ServiceLoader merge guarded by a test proven to fail on a dropped entry |
| **P1.2** | `loom/pipeline`: graph model + engine against a fake dispatcher | The `edges[]` schema defect closed **structurally**; filter branches expressible for the first time; cortex dependency banned by `maven-enforcer` |
| **P1.3** | Protocol: fix the envelope, add `SOURCE_*` / `NODE_TASK` | String-concatenated envelope replaced; 6 message types, 5 DTOs |
| **P1.4** | `cortex/node-runtime` with `NodeTaskRunner` + `SourceTaskRunner` | Added **additively** — the old engine kept running until P1.5 migrated its tests |
| **P1.5** | Test migration | `AbstractNodeChainTest` replaces the executor harness; 9 classes migrated |
| **P1.6** | Wire end to end | An unexecutable definition now returns **400** instead of a silent no-op run |
| **P1.7** | Delete the old engine; rewire Dagger | `ReactivePipelineExecutor`, `DefaultPipeline`, `PipelineManager`, `LoomPipelineLoader` gone; work-order handler reduced to `flush-sync` (superseded: WorkOrder removed entirely — see PIPELINE.md §12) |

**Deliberate deviations, both documented rather than silently reordered:** P1.4
added the new runtime alongside the old engine; P1.5 deferred the executor
deletion into a new P1.7 so the replacement could be proven first.

### Phase 2 — durability and correctness — COMPLETE

| Step | Scope | Notes |
|---|---|---|
| **P2.1** | `pipeline_run_item` + `pipeline_node_task`, DAOs, contract tests | **done** — the plan's own DB guidance was wrong on two counts and was corrected |
| **P2.2** | `RunStateStore` port; engine writes through it; batched | Item identity moved from an in-memory counter to the store |
| **P2.3** | Leases, retries with backoff, dead-letter, `LeaseReaper` | `retryFailed` finally does something |
| **P2.4** | Restart recovery | A run whose *source* had not finished cannot be resumed faithfully, and says so |
| **P2.5** | Node-kind whitelist in worker selection | Load-aware placement deliberately **not** built at the time — `cpuLoad` was broken. Both were fixed later: `SystemLoadProbe` produces real `cpuLoad`/`ioLoad`, and load breaks ties between workers of equal priority |
| **P2.6** | Per-run in-flight ceiling + source-ack backpressure | Two liveness bugs found and fixed — see below |
| **P2.7** | Worker attribution (`leased_by`) | Run inspection API **not** built; still open |

### Phase 3 — DAG, affinity, resilience — IN PROGRESS

| Step | Scope | Status |
|---|---|---|
| **P3.1** | `affinity` in the definition; segment computation; save-time validation | **done** |
| **P3.2** | `SEGMENT_TASK` protocol + Cortex segment runner | **done** |
| **P3.3** | Engine dispatches segments; filter edges bound segments | **done** |
| **P3.5** | Event aggregation — per-node counters on a timer | **done** taken **ahead of** P3.4 |
| **P3.6** | Circuit breaker per node kind **and per-kind concurrency ceiling** | **done** |
| **P3.3b** | `activeCount` / `pendingCount` in `NODE_STATS` | **done** |
| **P3.4** | Result batching, static size from the pipeline definition | **done** |

**On P3.4.** `resultBatchSize` in the pipeline definition, default 1 (send each
result as it happens, i.e. the previous behaviour). A worker accumulates results
per run and sends them together once the size is reached.

The size trigger alone is not sufficient: a run's tail never reaches it — a
500-item run batched at 200 ends with 100 results in the buffer — so a periodic
flush sends partial batches after a short hold. **The size trigger is the
optimisation; the timer is what makes batching correct.**

Batching is a transport concern only. Each entry is assimilated through the same
single-result path, so retries, dead-lettering and downstream unblocking are
unchanged, and there is no batch-level verdict that could let one bad result
spoil the others.

Deriving the size adaptively from observed durations is a later refinement, not
needed to make batching useful.

---

## 3. What remains

Everything this plan set out to build has been built. What follows was designed
here but deliberately not implemented, and is carried forward rather than lost.

### Scheduling refinements

Designed in the Phase 3 DAG-manager section, none of them required for the system
to work correctly today:

| Item | Why it was left |
|---|---|
| Adaptive dispatch width from live load | Depends on the worker load metric being fixed first |
| Priority with aging | No evidence yet of low-priority runs being starved |
| Node-kind-aware pacing | The per-kind ceiling covers the pressing case |
| Straggler handling (speculative re-dispatch) | Expensive; the plan always said measure before adopting |
| ~~Drain-aware placement~~ | **Built.** A `TERMINATING` worker is not placeable, refuses late dispatches and returns what it cannot finish — see [METALOOM_ARCHITECTURE_TASK.md §9](METALOOM_ARCHITECTURE_TASK.md) |

### Batching refinements

Result batching is implemented with a size taken from the pipeline definition.
Two extensions were designed and not built: **dispatch** batching (several items
for the same node in one message), and deriving the size adaptively from observed
per-task durations rather than configuration.

### Event streaming

Aggregated per-node counters are implemented. A **per-item opt-in stream**, for
debugging one file without turning the firehose back on, is not.

### Open questions

1. **Task state retention.** A large run produces on the order of a million
   per-node task rows. How long are they kept, and at what granularity afterwards?
   Still unanswered.
2. **Definition format versioning.** Agreed in principle at the start and not
   done. The format has since gained `syncToLoom`, filter branches, options,
   `affinity` and `resultBatchSize` with no version field. Each addition has been
   backward-compatible so far, which is why it has not yet hurt.

Answered during the work:

| Question | Answer |
|---|---|
| Must standalone Cortex pipeline execution survive? | No, not at the moment. Cortex is a worker |
| Where do intermediate results live? | In the node implementation's own cache — tracked in [../tasks/TASKS.md](../tasks/TASKS.md) |
| Push or pull dispatch? | Push. Revisited when leases arrived and kept: push plus leases plus per-worker caps gives the same backpressure without a second protocol rewrite |
| Is a separate segmented-dispatch variant needed? | No. Segments are what was built; single-node dispatch is the degenerate case |

---

_Plan verified against `92bc115` (2026-07-18). Implementation current to
2026-07-19. Remaining work is tracked in
[METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md); the system as
built is described in [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md)._
