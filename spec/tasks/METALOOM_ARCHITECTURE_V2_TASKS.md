# Variant C — Scheduling and Batching Task List

> The Variant C build plan, reduced to what is **not** built. Re-derived from a code audit on
> 2026-08-11 at `8c153347`. Format follows [../tasks/TASKS.template.md](../tasks/TASKS.template.md).
>
> **The build record is gone from this file.** Phases 1-3 (dependency inversion, graph model,
> protocol, worker runtime, durable run state, leases, retries, dead-letter, restart recovery, flow
> control, affinity segments, event aggregation, circuit breaker, result batching), definition format
> versioning, drain-aware placement and both run-inspection routes are implemented. The outcome
> record is [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) §11 and the code;
> the phase-by-phase table and the ticked checkboxes were deleted rather than kept as a second
> account of the same thing. What survives is this task list plus the decisions in
> [Appendix A](#appendix-a-standing-decisions) that are not recoverable from the code.
>
> **Context:** [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) (what exists
> and why) · [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) (run engine,
> definition schema, caching) · [../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) (the control protocol) ·
> [../tasks/METALOOM_ARCHITECTURE_TASK.md](../tasks/METALOOM_ARCHITECTURE_TASK.md) (the other open
> architecture work) · [../tasks/PIPELINE_TASKS.md](../tasks/PIPELINE_TASKS.md) (pipeline internals)
>
> **Owned elsewhere — do not duplicate.** Three items that used to sit in this file's "open" section
> now have owners; link them, do not re-open them here:
> [METALOOM_ARCHITECTURE_TASK.md](../tasks/METALOOM_ARCHITECTURE_TASK.md) **Task 3** (per-item opt-in
> event stream — the `?item=` filter on the event socket) · **Task 7** (task-state retention sweep) ·
> [PIPELINE_TASKS.md](../tasks/PIPELINE_TASKS.md) **Task 13** (instrumenting the run engine — the
> meters Tasks 1-3 below want to steer on).
>
> **Ordering / blocking.**
>
> 1. Nothing here blocks correct operation. Every task is an optimisation or a refinement; the system
>    runs today without all of them.
> 2. **Task 6** is a repo-truth fix, costs minutes and is independent of everything else. Do it first
>    so nobody reads the current dispatch javadoc and re-derives a rewrite that already happened.
> 3. **Tasks 1, 2 and 3** all want the same thing first: run-engine meters.
>    [PIPELINE_TASKS.md](../tasks/PIPELINE_TASKS.md) **Task 13** is that dependency. Building any of
>    the three without it means tuning blind — and each was explicitly deferred *pending measurement*,
>    not pending effort.
> 4. **Task 4** (dispatch batching) should follow **Task 1**: dispatch width is what determines
>    whether there are ever enough simultaneously-ready tasks for a dispatch batch to be worth a
>    message type.
> 5. **Task 5** is independent and worker-local.

## Progress Assessment

- [ ] 1. [Derive dispatch width from live worker load](#task-1-derive-dispatch-width-from-live-worker-load)
- [ ] 2. [Age run priority so a long run cannot starve a later one](#task-2-age-run-priority-so-a-long-run-cannot-starve-a-later-one)
- [ ] 3. [Recover from a straggler without waiting out the ten-minute lease](#task-3-recover-from-a-straggler-without-waiting-out-the-ten-minute-lease)
- [ ] 4. [Batch dispatch, not just results](#task-4-batch-dispatch-not-just-results)
- [ ] 5. [Derive the result batch size from observed task duration](#task-5-derive-the-result-batch-size-from-observed-task-duration)
- [ ] 6. [Correct the stale "Phase 1" contracts in the dispatch path](#task-6-correct-the-stale-phase-1-contracts-in-the-dispatch-path)

---

## Task 1: Derive dispatch width from live worker load

**Argumentation Summary:** Dispatch width is not merely static, it is **unconfigurable in
production**. `PipelineRunEngine#maxInFlight` (`loom/pipeline/.../engine/PipelineRunEngine.java:111`)
initialises to `DEFAULT_MAX_IN_FLIGHT = 256` (`:84`) and `setMaxInFlight` (`:2345`) has no production
caller at all — every reference is a test (`PipelineRunEngineFlowControlTest`,
`PipelineRunEngineBackpressureTest`, …), and `PipelineRunEngineFactory#assemble` (`:111`) never sets
it. The same holds for the per-kind ceiling: `setMaxInFlightForKind` (`:2194`) is only ever called
from `PipelineRunEngineBulkheadTest`. So a run against a one-worker fleet and a run against a
forty-worker fleet both push up to 256 outstanding tasks, and an operator has no knob. The blocker
that justified deferring this is gone: `SystemLoadProbe`
(`cortex/core/.../impl/loom/SystemLoadProbe.java`) produces real `cpuLoad`/`ioLoad`, and
`ProcessorRegistry` already consumes them — but only as a **tie-break between workers of equal
priority** (`ProcessorRegistry.java:486`), never to decide *how much* work to have outstanding.

**Improvement Summary:** Make the in-flight ceiling a function of the fleet — configurable at
minimum, load-derived at best — instead of a constant nobody can reach.

```
1. Make the ceiling reachable before making it clever. Set maxInFlight (and, where the definition
   asks for it, setMaxInFlightForKind) from PipelineRunEngineFactory#assemble
   (loom/services/rest/.../PipelineRunEngineFactory.java:111), fed by a LOOM_* configuration key
   with 256 as the documented default. Add the key to the env table in
   ../loom/CONFIGURATION.md and ../cortex/METALOOM_ARCHITECTURE.md. This step alone is shippable
   and is what an operator needs today.
2. Then derive it. Expose an aggregate capacity view on ProcessorRegistry - worker count, and the
   mean/max cpuLoad and ioLoad already carried on ConnectedProcessor's SystemStatusInfo - and let
   the engine ask for the current width instead of reading a field: replace the int with a supplier
   the factory installs, so loom/pipeline keeps depending on nothing.
   Keep the accounting where it is: isAtCapacity (:2238) and the capacityWaiters release path are
   correct and must not be rewritten. Only the number changes.
3. Damp it. Width must move on a timer with hysteresis, not per result, or a fleet under load
   oscillates between stalled and flooded. Never let the derived width fall below 1 - a zero
   ceiling stalls the run permanently, since nothing re-triggers dispatch except a returning
   result.
4. Segments count as one unit against the ceiling, as they do now; a segment is one dispatch and
   one lease regardless of how many nodes it carries.
```

**References:** [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §8 (flow
control) · [../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §3.13 (`STATUS_UPDATE`) ·
[PIPELINE_TASKS.md](../tasks/PIPELINE_TASKS.md) **Task 13** (the `loom_node_tasks_inflight` meter
that makes the effect visible) · [Appendix A](#appendix-a-standing-decisions) Q4 (push stays push)
**Test Requirements:** Extend `PipelineRunEngineFlowControlTest` with a width that changes mid-run
(dispatch stops at the lower ceiling, resumes when it rises, and never dispatches below 1); a
`ProcessorRegistry` test for the aggregate capacity view over mixed loads; a factory test asserting
the configured value reaches the engine. Run `mvn -pl loom/pipeline,loom/services/rest test`.
**loom-ui impact:** None on the API surface. The run banner reads aggregated counters
(`RunStatsAggregator`), which are unchanged.

---

## Task 2: Age run priority so a long run cannot starve a later one

**Argumentation Summary:** Priority is carried the whole way to the graph and then dropped.
`PipelineVersion#getPriority` reaches `PipelineGraphParser#parse` (`:144`) and is stored on
`PipelineGraph` (`:32`, getter `:119`), but the only consumer of the value is `PipelineMatcher`,
which uses it to decide **which pipeline claims a new asset** — not how work is scheduled. There is
no cross-run scheduler: each run gets its own `PipelineRunEngine` (`PipelineRunEngineFactory:111`)
registered in `PipelineRunRegistry`, and every engine dispatches independently into the shared fleet
through `WebSocketNodeDispatcher` → `ProcessorRegistry#selectProcessor`. Placement sorts on the
**worker's** declared priority (`ProcessorRegistry.java:486`), which is a different concept. The
consequence: a 100k-item run that holds its in-flight ceiling occupies the fleet first-come,
first-served, and a small run started afterwards with `priority: 10` waits behind it. This was
deferred because no starvation had been observed, and that is still true — so the first deliverable
is evidence, not a scheduler.

**Improvement Summary:** Make the starvation visible, and only then give cross-run admission an
effective priority that rises with waiting time.

```
1. Evidence first, and it may be the whole task. Record per-run time-to-first-dispatch and the
   queueing delay between a task becoming ready and being dispatched (the seam is
   PipelineRunEngine's dispatch path plus PipelineRunRegistry, which is the only place that sees
   all live runs). If no run ever waits, close this task with the measurement written down rather
   than building a scheduler for a problem that does not exist.
2. Only if the numbers show it: introduce cross-run admission in PipelineRunRegistry - the one
   component that sees every live engine - rather than inside an engine, which by construction
   knows only about itself. Order candidate runs by
       effective = declaredPriority + agingRate * secondsWaitingSinceLastDispatch
   so a priority-0 run cannot be indefinitely deprived by a stream of higher-priority ones. Both
   terms need a LOOM_* key and a documented default; agingRate = 0 must reproduce today's
   behaviour exactly.
3. Do not put run priority into ProcessorRegistry#selectProcessor. That method answers "which
   worker", and conflating it with "whose work" is what makes both untestable. Admission decides
   whether an engine may dispatch now; selection stays unchanged.
4. Pause, cancel and the circuit breaker outrank admission: a parked kind or a paused run is never
   a candidate, whatever its effective priority.
```

**References:** [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §8 ·
[PIPELINE_TASKS.md](../tasks/PIPELINE_TASKS.md) **Task 13** · `PipelineMatcher` (the other, unrelated
use of the same field)
**Test Requirements:** A registry test with two live engines asserting that a starved low-priority
run is admitted once its aging term overtakes a stream of higher-priority work, and that
`agingRate = 0` reproduces the current first-come order exactly; a test that a paused or
circuit-broken run is never admitted. Run `mvn -pl loom/pipeline,loom/services/rest test`.
**loom-ui impact:** Only if step 2 lands. The run list would then need "waiting for capacity"
distinguished from "running but slow" — raise it in
[../loom/ui/TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) rather than folding UI work in here.

---

## Task 3: Recover from a straggler without waiting out the ten-minute lease

**Argumentation Summary:** Lease expiry is the only recovery from a slow worker, and it is slow by
design. `DaoRunStateStore.DEFAULT_LEASE_MS` is **10 minutes** (`:64`, applied at `:181`) and
`LeaseReaper` sweeps every 60 s (`DEFAULT_INTERVAL_MS`, bounded at `DEFAULT_SWEEP_LIMIT = 500`), so a
worker that is alive but stuck holds an item for up to ~11 minutes before anything happens; nothing
in `loom/` or `cortex/` mentions speculation or stragglers. The expensive precondition, however, is
already paid for: `LeaseReaper`'s own javadoc states that duplicate execution is possible **and
preferred**, and `(item_uuid, node_id)` is unique so a second result is recognised as a duplicate
rather than recorded twice. Speculative re-dispatch needs exactly that invariant and no more.

**Improvement Summary:** When a task runs far longer than its kind normally does and the fleet has
spare capacity, run a second copy and take whichever result lands first.

```
1. Measure before adopting - the plan always said so, and it still holds. Per-kind task duration is
   the input; PIPELINE_TASKS.md Task 13 is where that meter lives. Without a per-kind distribution
   there is no defensible threshold and speculation degenerates into doubling the fleet's work.
2. Trigger: a RUNNING task whose age exceeds max(absoluteFloorMs, k x p95(duration for its kind)).
   Both k and the floor are configuration with documented defaults, and speculation must be
   off by default.
3. Guards, all mandatory:
     - never speculate while the run is at its in-flight ceiling (Task 1) - a speculative copy must
       consume real spare capacity, not displace unstarted work;
     - never speculate onto the worker already holding the lease;
     - at most one speculative copy per task, ever;
     - never speculate a task whose kind has a tripped circuit breaker
       (NodeKindCircuitBreaker), or a segment - a segment's cost is its whole chain.
4. Settlement: first result wins and settles the node; the loser is dropped exactly as a duplicate
   lease-reclaim result is today. The loser must NOT consume an attempt - a speculated task that
   ends up failing twice would otherwise reach maxAttempts early.
5. Record it: count speculative dispatches and speculative wins separately, or nobody can tell
   whether the feature is buying anything. Add both rows to ../features/ops/METRICS.md in the same
   change (warning: METRICS.md §3/§5 are parsed at runtime by MetricsCatalogScrapeTest).
```

**References:** `LeaseReaper` (duplicate-work invariant) · `DaoRunStateStore:64` ·
[../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §10 ·
[../features/ops/METRICS.md](../features/ops/METRICS.md) ·
[PIPELINE_TASKS.md](../tasks/PIPELINE_TASKS.md) **Task 13**
**Test Requirements:** An engine test where a task exceeds the threshold, a second copy is dispatched
to a different worker, the first result settles the node and the second is ignored without counting
an attempt; a test that speculation is suppressed at capacity, on a tripped kind, and for segments.
Run `mvn -pl loom/pipeline test` and `mvn -pl loom/services/rest test`.
**loom-ui impact:** The debug canvas shows `attempt`/`maxAttempts` per task
(`loom-ui/src/features/pipeline/PipelineEditor.tsx`, via `listPipelineRunItemTasks`). Verify a
speculated task still shows attempt 1 — if speculation inflates that counter the UI will report a
retry that never happened.

---

## Task 4: Batch dispatch, not just results

**Argumentation Summary:** Batching exists in one direction only. Worker → Loom has
`NODE_TASK_RESULT_BATCH` (`ProcessorMessageType`, handled by
`ProcessorEndpoint#handleNodeTaskResultBatch`), but Loom → worker has no batch type: the enum offers
`NODE_TASK` and `SEGMENT_TASK`, and `WebSocketNodeDispatcher#dispatch` (`:53`) writes exactly one
frame per task through `registry.send`. A wide fan-out — one item, ten independent nodes — is ten
frames to (possibly) the same worker. Note that the affinity path already removes most of this cost
for connected nodes, so the remaining case is *unconnected* nodes that happen to be ready together;
that is why this is an optimisation with a measurement gate rather than a defect.

**Improvement Summary:** Add a Loom → worker batch frame that carries several `NodeTask`s for one
run, as a pure transport saving.

```
1. Measure the frame rate first. SegmentDispatchBenchmark
   (cortex/nodes/hash/core/src/test/.../SegmentDispatchBenchmark.java) measures worker-side cost
   only - no socket, no Loom - so it does NOT answer this. What is needed is dispatch frames per
   second under a wide graph. If the win is not visible, record that and stop.
2. Protocol: add NODE_TASK_BATCH to ProcessorMessageType with the same shape discipline as
   NODE_TASK_RESULT_BATCH - a run uuid and a list of entries, no batch-level verdict. Document it
   in ../loom/WEBSOCKET.md. Definition version stays 1: this is an additive frame, and an older
   worker never receives it because the sender only batches for workers that advertised support.
3. Loom side: batch only tasks that (a) belong to one run, (b) were selected onto the SAME worker,
   and (c) are ready in the same dispatch sweep. Each entry still gets its own placement decision,
   its own pipeline_node_task row and its own lease - NodeDispatcher#dispatch returns a worker id
   per task and the engine records leased_by from it, so a batch must resolve per task or lease
   reclaim and worker attribution both break.
4. Worker side: NodeTaskRunner handles each entry exactly as a lone NODE_TASK, including the
   per-kind concurrency it already applies. One malformed entry fails that entry alone.
5. Do not turn this into pull. Q4 was decided and re-confirmed: dispatch is push
   ([Appendix A](#appendix-a-standing-decisions)).
```

**References:** [../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §3 ·
[../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) ·
[Appendix A](#appendix-a-standing-decisions) Q4 · [Appendix B](#appendix-b-why-batching-is-correct)
**Test Requirements:** A dispatcher test that N tasks for one run on one worker travel in one frame
while tasks for different workers do not; an engine test that each batched entry still gets its own
lease row and `leased_by`; a worker test that one malformed entry fails alone. Run
`mvn -pl loom/services/rest,loom/pipeline,cortex/node-runtime test`.
**loom-ui impact:** None — no route or DTO changes; the per-task rows the UI reads are unchanged.

---

## Task 5: Derive the result batch size from observed task duration

**Argumentation Summary:** `ResultBatcher` (`cortex/node-runtime/.../runtime/ResultBatcher.java`)
takes its size from the pipeline definition and nothing else: `add(...)` (`:76`) receives a
`batchSize` carried on the `NodeTask`, which `PipelineGraphParser` (`:250`) read from the
definition's `resultBatchSize` (`PipelineGraph:33/:94`). One number therefore has to fit both a hash
node settling thousands of items a minute and a whisper node settling one every thirty seconds — for
the slow node any batch above 1 only adds latency, and the fixed `DEFAULT_MAX_HOLD_MS = 500` (`:38`)
is what quietly rescues it. An author has to guess the number per pipeline, and the guess is wrong
for any pipeline that mixes fast and slow kinds.

**Improvement Summary:** Let the batcher raise or lower the effective size per run from the observed
inter-result interval, with the definition value as the ceiling.

```
1. Track the inter-arrival interval per RunBuffer (:46) - the batcher already stamps oldestAt
   (:49), so this is one more field. Raise the effective size toward the configured value when
   results arrive faster than the hold window, lower it toward 1 when they do not.
2. The configured resultBatchSize becomes an upper bound, never a target. An explicit value in the
   definition must still cap the adaptive one, or an author loses the ability to bound memory.
3. Do NOT touch flushExpired (:112/:120). The timer is what makes batching CORRECT, not merely
   fast - see [Appendix B](#appendix-b-why-batching-is-correct). An adaptive size that removed the
   hold window would strand a run's tail forever.
4. Keep pendingFor (:167) meaningful; it is the intended gauge site for cortex_results_pending
   (METALOOM_ARCHITECTURE_TASK.md Task 8).
5. Worker-local only. Nothing about this reaches Loom: the batch frame is unchanged and each entry
   is still assimilated singly.
```

**References:** [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §12 ·
[Appendix B](#appendix-b-why-batching-is-correct) ·
[METALOOM_ARCHITECTURE_TASK.md](../tasks/METALOOM_ARCHITECTURE_TASK.md) **Task 8** (the
`cortex_results_*` meters)
**Test Requirements:** Extend `ResultBatcherTest`: a fast arrival pattern grows the effective size up
to but not beyond the configured ceiling; a slow one collapses it to 1 and each result leaves within
the hold window; the existing tail-flush case still passes unchanged. Run
`mvn -pl cortex/node-runtime test`.
**loom-ui impact:** None.

---

## Task 6: Correct the stale "Phase 1" contracts in the dispatch path

**Argumentation Summary:** The two javadocs a reader meets first when touching dispatch describe a
system that no longer exists. `WebSocketNodeDispatcher`'s class javadoc (`:25-31`) is headed "Phase 1
limitations, deliberately" and claims worker selection "ignores live load", "cannot yet route by node
kind", and that dispatch has "no lease, no timeout and no retry" — all three are false:
`ProcessorRegistry` uses `cpuLoad`/`ioLoad` as a tie-break (`:486`) and filters on the node-kind
whitelist (`ConnectedProcessor#accepts`), and leases, `RetryScheduler` and dead-lettering all shipped
in Phase 2. The class's own inline comments contradict its javadoc a few lines below. `NodeDispatcher`
(`loom/pipeline/.../engine/NodeDispatcher.java:13-15`) states that "a later phase is expected to
invert this to a pull with leases" — a change that was explicitly considered and **rejected** (Q4).
The cost is concrete: an agent reading either file re-derives a rewrite that was already decided
against, or files a task for work already done.

**Improvement Summary:** Make both javadocs describe what the code does, and point at the decision
rather than at a superseded plan.

```
1. WebSocketNodeDispatcher (:25-31): replace the "Phase 1 limitations" paragraph with what is
   actually true - selection is priority-first with cpuLoad/ioLoad as tie-break and the node-kind
   whitelist as a filter; dispatch is leased, retried and dead-lettered by the engine; the
   remaining limitation is that ProcessorCapability is still CPU for every kind (which the inline
   comment at :55-57 already says correctly, and which PIPELINE_TASKS.md Task 10 owns).
2. NodeDispatcher (:13-15): push is the decision, not a phase. Say so and cite Q4 - push plus
   leases plus per-worker caps gives the same backpressure without a second protocol rewrite.
3. While in there: the class javadoc's promise that returning null makes the engine "fail the node
   rather than wait forever" is correct - keep it, it is the contract Task 1's supplier must not
   break.
```

**References:** `loom/services/rest/.../WebSocketNodeDispatcher.java` ·
`loom/pipeline/.../engine/NodeDispatcher.java` · [Appendix A](#appendix-a-standing-decisions) Q4 ·
[PIPELINE_TASKS.md](../tasks/PIPELINE_TASKS.md) **Task 10** (the capability gap that *is* still real)
**Test Requirements:** No behaviour change, so no new test — the existing
`mvn -pl loom/pipeline,loom/services/rest test` must stay green. This is a documentation-only change
inside Java sources.
**loom-ui impact:** None.

---

## Appendix A: Standing decisions

Recorded 2026-07-18 (Q1/Q4/Q5) and later; kept because the reasoning is not recoverable from the
code, and because Tasks 1-4 are constrained by it. Do not re-litigate these without a written reason.

| # | Question | Decision | Consequence |
|---|---|---|---|
| Q1 | Must standalone Cortex pipeline execution survive? | **No — Loom-only is acceptable** | Cortex holds no pipelines and needs no local driver. The legacy `cortex process run --actions` path went with the rest of the CLI. README and website still claim otherwise — [METALOOM_ARCHITECTURE_TASK.md](../tasks/METALOOM_ARCHITECTURE_TASK.md) **Task 1** |
| Q4 | Push or pull dispatch? | **Push** | Loom sends `NODE_TASK`/`SEGMENT_TASK` when work becomes ready. Revisited when leases arrived and **kept**: push + leases + per-worker caps gives the same backpressure without a second protocol rewrite. Binds Task 4 and Task 6 |
| Q5 | Version the definition format? | **Yes** | Delivered: `PipelineGraphParser.CURRENT_DEFINITION_VERSION` (= 1); absent means 1, higher is refused by name. An additive frame (Task 4) does not bump it |
| — | Where do intermediate results live? | In the node implementation's own cache, reached through the segment-scoped `ArtifactCache` | Shipped 2026-08-02 — `NodeInputs.artifacts()`, owned by the segment execution, opt-in per node. Affinity grouping alone saves only round trips; the *scope* is what removes the re-read. [PIPELINE.md](../features/pipeline/PIPELINE.md) §7.4 |
| — | Is a separate segmented-dispatch variant (D) needed? | **No** | Segments are what was built; single-node dispatch is the degenerate case |

## Appendix B: Why batching is correct

These two notes are the reason result batching is correct rather than merely fast. Tasks 4 and 5 both
depend on them.

- **The size trigger alone is not sufficient.** A run's tail never reaches it — a 500-item run
  batched at 200 ends with 100 results in the buffer — so `ResultBatcher#flushExpired` sends partial
  batches after a short hold. The size trigger is the optimisation; **the timer is what makes
  batching correct.**
- **Batching is a transport concern only.** Each entry is assimilated through the same single-result
  path, so retries, dead-lettering and downstream unblocking are unchanged, and there is no
  batch-level verdict that could let one bad result spoil the others. Any dispatch-side batch
  (Task 4) must hold to the same rule.

## Test setup

The standard Loom setup, repeated so an agent picking up a task here does not have to hunt for it.

```bash
./setup-pool.sh                       # required before any DB test, and after every Flyway change
mvn test -pl loom/pipeline            # engine, graph, segmenter, circuit breaker
mvn test -pl cortex/node-runtime      # node/source/segment runners, ResultBatcher
mvn test -pl integration-test         # PipelineAffinitySegmentIntegrationTest and friends
mvn test -pl cortex/nodes/hash/core -Dtest=SegmentDispatchBenchmark -Dbenchmark=true
```

The benchmark is disabled by default: it needs `/opt/metaloom/loom-testdata`. It measures
**worker-side** segment cost only — no socket, no Loom — so it does not answer Task 4's question.

---
_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (converted from build plan to task list; implemented phases removed)_
