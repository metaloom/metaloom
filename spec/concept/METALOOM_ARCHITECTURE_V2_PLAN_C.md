# Variant C — Implementation Plan

> **Status: COMPLETE.** Phases 1–3 are built and verified against the code. What
> is left in this file is the set of **decisions** that would otherwise be
> invisible, and the refinements that were designed here and **deliberately not
> built** (§3).
>
> Loom owns the pipeline graph and dispatches node tasks — or whole affinity
> segments — to Cortex workers. Run state is durable, leases reclaim work from
> dead workers, a restart resumes, and results can be batched.
>
> **For what the system does today, read
> [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md).** Open work is in
> [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md). Deferred design
> lives in [../plans/TASKS.md](../plans/TASKS.md).
>
> The variant comparison that justified this choice was deleted as outdated:
> Variant C is no longer one option among four, it is the architecture. The
> rejected ideas are recorded under
> [METALOOM_ARCHITECTURE_TASK.md § Dropped](METALOOM_ARCHITECTURE_TASK.md).

---

## 1. Already implemented

Phase-by-phase narrative removed. Each row is the thing that was built and the
file that is now the authority on it. Paths are abbreviated below the package
root (`io/metaloom/…`).

| Item | Where it lives in code |
|---|---|
| **P1.1** Loom→Cortex dependency inverted; node descriptors extracted | `loom-shared/node-model/…/nodes/spec/` (17 `cortex/nodes/*-api` modules deleted) |
| **P1.1** Cortex dependency banned from the orchestrator | `loom/pipeline/pom.xml` — `maven-enforcer` `ban-cortex-dependencies` |
| **P1.2** Graph model, parser, port analysis | `loom/pipeline/…/graph/PipelineGraph.java`, `PipelineGraphParser.java`, `PortGraphAnalyzer.java` |
| **P1.2** Run engine | `loom/pipeline/…/engine/PipelineRunEngine.java` |
| **P1.3** Protocol envelope and message set | `loom-shared/rest-model/…/processor/message/ProcessorMessageType.java` |
| **P1.4** Worker-side task runners | `cortex/node-runtime/…/runtime/NodeTaskRunner.java`, `SourceTaskRunner.java` |
| **P1.6** Unexecutable definition rejected with **400** at run start | `loom/pipeline/…/graph/GraphValidationException.java` + `PipelineEndpoint` |
| **P1.7** Old Cortex engine deleted | `ReactivePipelineExecutor`, `DefaultPipeline`, `PipelineManager`, `LoomPipelineLoader` and the whole `WorkOrder` mechanism no longer exist |
| **P2.1** Durable run state | `pipeline_run_item`, `pipeline_node_task` (+ `V2.60__pipeline_node_task_element_seq.sql`), `loom/db/jooq/…/dao/pipeline/` |
| **P2.2** Store port; item identity from the store, not a counter | `loom/pipeline/…/engine/RunStateStore.java` |
| **P2.3** Leases, backoff retries, dead-letter | `loom/services/rest/…/impl/LeaseReaper.java`, `loom/pipeline/…/engine/RetryScheduler.java` |
| **P2.4** Restart recovery (a run whose *source* had not finished is finished with what it knew, and says so) | `loom/services/rest/…/impl/PipelineRunRecovery.java` |
| **P2.5** Node-kind whitelist **and** load-aware placement | `ProcessorRegistry` (priority first, `cpuLoad`/`ioLoad` as tie-break, `TERMINATING` not placeable) + `cortex/core/…/impl/loom/SystemLoadProbe.java` |
| **P2.6** Per-run in-flight ceiling + source-ack backpressure | `PipelineRunEngine` — `inFlightCount`, `maxInFlight` |
| **P2.7** Worker attribution | `pipeline_node_task.leased_by`, `PipelineNodeTaskDao#countLeasedBy` |
| **P3.1** `affinity` in the definition; segments; save-time validation | `loom/pipeline/…/graph/AffinityValidator.java`, `PipelineSegmenter.java`, `PipelineSegment.java` |
| **P3.2** `SEGMENT_TASK` / `SEGMENT_TASK_RESULT` + worker runner | `cortex/node-runtime/…/runtime/SegmentTaskRunner.java` |
| **P3.3** Engine dispatches segments; filter edges bound them | `loom/pipeline/…/engine/NodeDispatcher.java` |
| **P3.3b** `activeCount` / `pendingCount` in `NODE_STATS` | `loom/services/rest/…/impl/RunStatsAggregator.java` |
| **P3.4** Result batching (size + timer flush) | `cortex/node-runtime/…/runtime/ResultBatcher.java`; `resultBatchSize` parsed in `PipelineGraphParser`, carried on `NodeTask` |
| **P3.5** Event aggregation — per-node counters on a timer | `RunStatsAggregator` |
| **P3.6** Per-kind circuit breaker **and** per-kind concurrency ceiling | `loom/pipeline/…/engine/NodeKindCircuitBreaker.java`; `PipelineRunEngine.inFlightByKind` |
| Drain-aware placement (added after the plan) | `cortex/core/…/impl/loom/LoomControlChannel#drain`, `PipelineTaskHandler`, `PipelineRunEngine#onNodeTaskReturned` |
| Definition format versioning — decision Q5, finally delivered | `PipelineGraphParser.CURRENT_DEFINITION_VERSION` (= 1); absent means 1, higher is refused by name |
| Segment-vs-per-node dispatch benchmark | `cortex/nodes/hash/core/src/test/…/SegmentDispatchBenchmark.java` (worker-side cost only; no socket, no Loom) |

**Sequencing note.** Every phase was executed step by step rather than as one
large change, so each step was independently reviewable and left the build
green. Two deliberate deviations were documented rather than silently reordered:
P1.4 added the new runtime *alongside* the old engine, and P1.5 deferred the
executor deletion into a new P1.7 so the replacement could be proven first.

---

## 2. Decisions taken

Recorded 2026-07-18; these were the open questions blocking Phase 1 start. They
are kept because the reasoning is not recoverable from the code.

| # | Question | Decision | Consequence |
|---|---|---|---|
| Q1 | Must standalone Cortex pipeline execution survive? | **No — Loom-only is acceptable** | Cortex holds no pipelines and needs no local driver. Offline use had been limited to the legacy `cortex process run --actions` path, which has since been removed with the rest of the CLI. README/website still claim otherwise — [METALOOM_ARCHITECTURE_TASK.md §2](METALOOM_ARCHITECTURE_TASK.md) |
| Q4 | Push or pull dispatch? | **Push** | Loom sends `NODE_TASK`/`SEGMENT_TASK` when work becomes ready. Revisited when leases arrived and **kept**: push + leases + per-worker caps gives the same backpressure without a second protocol rewrite |
| Q5 | Version the definition format? | **Yes** | Delivered late (see §1) after the format had already gained `syncToLoom`, filter branches, options, `affinity` and `resultBatchSize` |
| — | Where do intermediate results live? | In the node implementation's own cache, reached through the segment-scoped `ArtifactCache` | **Shipped** 2026-08-02 — `NodeInputs.artifacts()`, owned by the segment execution, opt-in per node. Affinity grouping on its own still saves only round trips; the scope is what removes the re-read. [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §7.4 |
| — | Is a separate segmented-dispatch variant (D) needed? | **No** | Segments are what was built; single-node dispatch is the degenerate case |

---

## 3. Open — designed here, deliberately not built

This is the only part of this file that is not a historical record. Nothing here
is required for the system to work correctly today.

### 3.1 Scheduling refinements

| Item | Why it was left | Still open? |
|---|---|---|
| Adaptive dispatch width from live load | Waited on the worker load metric | **Yes.** `SystemLoadProbe` now produces real `cpuLoad`/`ioLoad` and `ProcessorRegistry` uses them as a tie-break, so the blocker is gone — width itself is still static |
| Priority with aging | No evidence yet of low-priority runs being starved | **Yes.** `ProcessorRegistry` sorts on declared `priority` with no aging term |
| Node-kind-aware pacing | Superseded — the per-kind ceiling (`inFlightByKind`) covers the pressing case | No |
| Straggler handling (speculative re-dispatch) | Expensive; the plan always said measure before adopting | **Yes.** No `speculative`/straggler path exists; lease expiry is the only recovery from a slow worker |

### 3.2 Batching refinements

Result batching is implemented with a size taken from the pipeline definition.
Two extensions were designed and **not** built:

- **Dispatch batching** — several items for the same node in one message. There is
  no `NODE_TASK` batch message type; dispatch is one task per frame.
- **Adaptive batch size** — deriving `resultBatchSize` from observed per-task
  durations rather than from configuration.

Notes that must survive, because they are the reason batching is correct rather
than merely fast:

- The size trigger alone is **not** sufficient. A run's tail never reaches it — a
  500-item run batched at 200 ends with 100 results in the buffer — so
  `ResultBatcher#flushExpired` sends partial batches after a short hold. **The
  size trigger is the optimisation; the timer is what makes batching correct.**
- Batching is a **transport concern only**. Each entry is assimilated through the
  same single-result path, so retries, dead-lettering and downstream unblocking
  are unchanged, and there is no batch-level verdict that could let one bad
  result spoil the others.

### 3.3 Event streaming

Aggregated per-node counters are implemented (`RunStatsAggregator`). A **per-item
opt-in stream**, for debugging one file without turning the firehose back on, is
not — no per-item event type or subscription exists. Also listed in
[METALOOM_ARCHITECTURE_TASK.md §3](METALOOM_ARCHITECTURE_TASK.md).

### 3.4 Run inspection

`GET /api/v1/pipelines/:uuid/runs/:runUuid/items` exists and is paged
(`PipelineEndpoint`, covered by `PipelineRunItemEndpointTest`). The **per-node
task** view — `leased_by`, attempt history, dead-letter reason — has no route.
Owned by [METALOOM_ARCHITECTURE_TASK.md §3](METALOOM_ARCHITECTURE_TASK.md).

### 3.5 Task state retention

**Decided, not enforced.** A large run produces on the order of a million
per-node task rows. The windows (7 days of detail, 30 days for
`FAILED`/`DEAD_LETTER`, the `pipeline_run` row forever) are settled in
[../features/pipeline/PIPELINE.md §10.1a](../features/pipeline/PIPELINE.md). No
sweep exists yet — tracked in
[METALOOM_ARCHITECTURE_TASK.md §10](METALOOM_ARCHITECTURE_TASK.md).

---

## 4. Test setup

Nothing here is specific to this plan; it is the standard Loom setup, repeated so
an agent picking this file up does not have to hunt for it.

```bash
./setup-pool.sh                       # required before any DB test, and after every Flyway change
mvn test -pl loom/pipeline            # engine, graph, segmenter, circuit breaker
mvn test -pl cortex/node-runtime      # node/source/segment runners, ResultBatcher
mvn test -pl integration-test         # PipelineAffinitySegmentIntegrationTest and friends
mvn test -pl cortex/nodes/hash/core -Dtest=SegmentDispatchBenchmark -Dbenchmark=true
```

The benchmark is disabled by default: it needs `/opt/metaloom/loom-testdata`.

---

## 5. Progress Assessment

- [x] Phase 1 — dependency inversion, graph model, protocol, worker runtime, old engine deleted
- [x] Phase 2 — durable run state, leases, retries, dead-letter, restart recovery, flow control
- [x] Phase 3 — affinity segments, event aggregation, circuit breaker, result batching
- [x] Definition format versioning (decision Q5)
- [x] Drain-aware placement
- [x] Run item inspection endpoint
- [ ] Adaptive dispatch width from live load (§3.1)
- [ ] Priority with aging (§3.1)
- [ ] Straggler handling / speculative re-dispatch (§3.1)
- [ ] Dispatch batching and adaptive batch size (§3.2)
- [ ] Per-item opt-in event stream (§3.3)
- [ ] Per-node task inspection endpoint (§3.4)
- [ ] Task retention sweep (§3.5)

---

_Git HEAD revision: `2e5981cb`_
_Last updated: 2026-08-01 (reduced to an implemented-items table plus the open refinements; corrected stale claims about versioning, load-aware placement, retention and run inspection)_
