# MetaLoom Architecture V2 — Task List

> Work items for the multi-instance design in
> [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md).
> Format follows [../TASKS.template.md](../TASKS.template.md).
>
> 🔴 **Do not start any *dispatch* task in this file yet.** All of them depend
> on prerequisites tracked in
> [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) — most
> importantly Tasks 1–4 (stop reporting success for work not done) and Task 6
> (tune and benchmark a single worker). Distributing a system that silently
> lies about its results produces a fleet of quiet failures.
>
> ✅ **One exception: [Task V11](#task-v11-node-capability-whitelist), the node
> capability whitelist.** It has no preconditions, delivers least-privilege
> deployment on its own, makes the "24 kinds silently no-op" gap queryable, and
> is required by every variant except the flat pool. Build it first; it is not
> wasted under any outcome.
>
> Tasks V0, V1, V2, and V14 are **decisions**, not code. They gate everything
> after them, and three of them may cancel large parts of this workstream.

---

## Progress Overview

Tasks are listed in **recommended execution order**, not numeric order — the
numbers are stable identifiers, added as the design grew. Variant letters refer
to [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §7.

| Order | # | Task | Type | Variant |
|---|---|------|------|---------|
| 1 | **V11** | **Node capability whitelist** | Foundation | **all — build first** |
| 2 | V0 | Benchmark a single tuned worker | Decision | gates everything |
| 3 | V1 | Settle the shared-storage model | Decision | A, C, D |
| 4 | V14 | Confirm fleet heterogeneity | Decision | gates D over A |
| 5 | V2 | Confirm or rule out multi-site | Decision | gates B |
| 6 | V3 | Make the worker protocol recursive | Foundation | B, D |
| 7 | V4 | Durable, item-grained queue with leases | Core | A, D |
| 8 | V5 | Capability- and load-aware scheduling | Core | A, C, D |
| 9 | V6 | Worker-visible path advertisement | Core | A, C, D |
| 10 | V7 | Deferred (async) node SPI | Independent | C, D — pays off alone |
| 11 | **V12** | **Node affinity in the pipeline definition** | Core | C, D |
| 12 | **V13** | **Segmented dispatch** | Core | **D — the recommendation** |
| 13 | V8 | Cortex coordinator role | Tree | B |
| 14 | V9 | Subtree aggregation and event relay | Tree | B |
| 15 | V10 | Cross-instance node delegation | Tree | C |

- [ ] **Phase 0 — build the whitelist** (V11) ← *no variant depends on the
      outcome of anything else; it is useful on its own*
- [ ] **Phase 1 — decide** (V0, V1, V14, V2) ← *be willing to stop here*
- [ ] **Phase 2 — flat pool** (V3, V4, V5, V6) → **Variant A**
- [ ] **Phase 3 — async nodes** (V7) *— parallel with Phase 2; pays off alone*
- [ ] **Phase 4 — segmentation** (V12, V13) → **Variant D, the recommendation**
- [ ] **Phase 5 — tree** (V8, V9) *— only if V2 says yes*
- [ ] **Phase 6 — per-node dispatch** (V10) *— only if V13 proves insufficient*

---

## Task V0: Benchmark a single tuned worker

**Argumentation Summary:** There is **no throughput measurement anywhere in the
repo**. A deployed Cortex is pinned to 4 concurrent media items on a 512 MB
heap, neither configurable in a container. Every argument for distributing work
rests on an unmeasured assumption that one machine is the ceiling. It is
entirely plausible that a properly tuned single worker delivers several times
today's throughput at zero architectural cost — in which case this entire
workstream should be cancelled, or at least deferred a long way.

**Improvement Summary:** Measure before building. Establish a baseline that
every later change is judged against.

```
Depends on METALOOM_ARCHITECTURE_TASK.md Task 6 (making the knobs exist).

1. Assemble a fixed, checked-in media corpus with a documented mix
   (images, short video, long video, audio) and a fixed pipeline.

2. Measure, on one machine, items/minute at:
   - the current default (concurrency 4, 512 MB heap)
   - concurrency scaled to core count, heap raised appropriately
   - with per-node concurrency tuned for the slow nodes

3. Identify the actual bottleneck: CPU, IO, heap, native library contention,
   or the flatMap width. Do not assume — profile.

4. Record results in this file. State plainly whether distribution is
   justified, and cancel or defer Phases 1-4 if it is not.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §1,
§14 Q4 · [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) Task 6

**Test Requirements:** A repeatable benchmark harness, checked in, that any
contributor can run to reproduce the numbers.

---

## Task V1: Settle the shared-storage model

**Argumentation Summary:** Cortex reads media by **local filesystem path** and
writes results into **extended attributes on the file itself**. Two workers
processing one folder must therefore both *see* that folder. This is a hard
constraint that no amount of scheduler sophistication removes, and it is
currently unaddressed. It also determines which topology makes sense: per-site
storage is precisely the case a tree serves.

**Improvement Summary:** Choose the storage model explicitly and write it down,
before any distribution code exists.

```
This is a decision task. Produce a short written decision, not code.

Options to evaluate:
  (a) Shared mount (NFS / CIFS / CSI volume) — all workers see the same tree.
      Simplest; keeps xattr result storage working unchanged. Note xattr
      support varies by filesystem — VERIFY on the target mount, as several
      network filesystems do not support user xattrs at all.
  (b) Per-site storage with site-local workers — motivates the tree (V2, V8).
  (c) Object storage — requires changing BOTH how media is read AND how
      results are stored, since xattrs do not exist. Much larger change; do
      not adopt implicitly.

Deliverables:
  - the chosen model, recorded here and in METALOOM_ARCHITECTURE_V2.md §12
  - confirmation that xattr (or the chosen result storage) works on it
  - a statement of what happens when a worker cannot see a path
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §12,
§14 Q2

**Test Requirements:** An integration test with two workers against one shared
mount, asserting both can read media and persist results.

---

## Task V2: Confirm or rule out multi-site

**Argumentation Summary:** The tree topology's genuine justification is
**locality** — media at several sites that cannot practically be moved. Its
other claimed benefits (throughput, fault recovery) are either unproven or
achievable more cheaply with a flat pool, and a naive tree actively *degrades*
fault recovery by adding a per-subtree single point of failure. If every
deployment is single-site, Tasks V8–V10 are speculative work on a substantial
new protocol surface.

**Improvement Summary:** Get a product answer before committing to the
expensive topology.

```
This is a decision task, and it is a product question, not an engineering one.

Answer:
  1. Do real or planned deployments span sites/regions/edge locations?
  2. If so, is the constraint network (bandwidth/latency to a central Loom)
     or data residency/regulatory?
  3. Must a disconnected site keep processing autonomously? (See V2.md §14 Q7 —
     this is the tree's best feature and its hardest correctness problem;
     reconciling results computed during a partition is not trivial.)
  4. What is the target fleet size? 5 workers changes nothing
     architecturally; 500 changes everything.

If the answer to (1) is no: mark V8-V10 as WON'T DO and keep the flat pool.
Record the decision and its date here either way.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §4,
§14 Q3/Q5/Q7

**Test Requirements:** None (decision).

---

## Task V3: Make the worker protocol recursive

**Argumentation Summary:** Today the protocol is asymmetric — Loom implements
"coordinator", Cortex implements "worker", in different codebases with
different classes. If the tree is ever built, a master Cortex must implement
the coordinator side too, and doing that without a shared abstraction means
**implementing dispatch, registration, and lease tracking twice**, with two
sets of bugs. Making the protocol uniform and recursive turns topology into a
deployment choice rather than an architectural one, and makes the flat pool the
one-level case of the tree.

**Improvement Summary:** Define the protocol once, independent of who is above
you.

```
Do this BEFORE V4, not after. It is far cheaper as a design constraint than as
a refactor of working dispatch code.

1. Extract the worker protocol into a shared module (suggest
   loom-shared/processor-protocol): message envelope, registration, lease
   request/grant/expiry, result reporting, event forwarding. No assumption
   that the peer above is Loom.

2. Define two role interfaces, WorkerRole and CoordinatorRole. Loom implements
   CoordinatorRole. Cortex implements WorkerRole today and may implement
   CoordinatorRole later (V8) with no protocol change.

3. Version the protocol explicitly. There is no version negotiation today; a
   mixed-version fleet is a certainty once instances talk to each other.

4. Keep the current ProcessorMessage wire format compatible, or ship a
   migration. Note that ProcessorRegistry.dispatchWorkOrder currently builds
   its envelope by STRING CONCATENATION - replace that with real
   serialisation as part of this task.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §7

**Test Requirements:** A protocol conformance test suite runnable against any
`CoordinatorRole` implementation, so Loom and a future master Cortex are held
to the same contract.

---

## Task V4: Durable, item-grained queue with leases

**Argumentation Summary:** The architectural core. Today the unit of
distribution is an **entire pipeline run**: one work order to one worker, which
expands the globs itself. Ten workers cannot share one folder. There is no
queue — no free worker returns **503**, refusing work rather than deferring it.
Dispatch state is in-memory, so a Loom restart loses all knowledge of what was
in flight.

**Improvement Summary:** Move the unit of work from "a run" to "one media
item", make it durable, and have workers lease rather than receive.

```
Depends on V3. Substantial - sequence as its own milestone.

1. Split discovery from execution. One instance (or Loom) expands the
   selection and ENQUEUES one row per media item instead of processing them.
   This is the "source node as a separate role" idea and it is the right shape.

2. Add a work_item table: uuid, pipeline_run_uuid, media_path, state
   (PENDING/LEASED/DONE/FAILED/DEAD), leased_by, lease_expires_at,
   attempt_count, last_error. Follow the project DB convention: Flyway
   migration + jOOQ regeneration + db/api DAO + jooq AND memory impls +
   db/api-test contract test.

3. Invert the flow: workers PULL. A worker with spare capacity requests N
   items and receives time-bounded leases. This is what makes backpressure
   work across the pool - a busy worker stops asking - and it replaces
   today's push model.

4. Lease expiry returns an item to PENDING. This single mechanism covers
   worker death, hangs, partitions, and scale-down. Cap attempt_count and
   route exhausted items to DEAD.

5. Wire retries here. There is NO retry anywhere in the system today, and
   `retryFailed` is advertised by 10 node descriptors while being read by
   nothing. This is where that finally becomes real.

6. Keep whole-run dispatch working during migration; run both behind a flag
   until the queue path is proven.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §3,
§12 ·
[../features/pipeline/PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md)
A-EH1

**Test Requirements:** Lease expiry and reassignment; concurrent workers never
double-processing one item; a killed worker's items reclaimed; dead-letter
after N attempts; queue survival across a Loom restart.

---

## Task V5: Capability- and load-aware scheduling

**Argumentation Summary:** `selectProcessor` filters by `ONLINE` plus a
capability hardcoded to `CPU`, sorts by a priority that is identical on every
worker, and takes the first. The `systemStatus` collected every 20 seconds is
never consulted. A saturated worker is chosen over an idle one whenever it
sorts first.

**Improvement Summary:** Route work by what it needs and where there is room.

```
Depends on METALOOM_ARCHITECTURE_TASK.md Task 7 (the load numbers are WRONG
today - cpuLoad multiplies load average by 100, so any machine with load >= 1
reports 100%). Scheduling on a broken metric is worse than not scheduling.
Also depends on Task 9 (workers must be distinguishable).

1. Derive required capability from the pipeline's nodes rather than
   hardcoding CPU. Requires NodeDescriptor to declare its requirement.

2. Advertise GPU from Cortex when a GPU is actually present - detect it,
   do not make it a flag someone sets wrongly.

3. Score candidates on live load and in-flight lease count. Keep priority as
   a tiebreak and pinning mechanism.

4. With V4 in place, "scheduling" becomes lease admission: a worker asks and
   the coordinator decides how much and which kind to grant. Prefer that to
   push-based selection.

5. Placement must be an OUTPUT of the scheduler, never an input from a
   pipeline definition. Pipelines declare requirements; they must not name
   workers. See METALOOM_ARCHITECTURE_V2.md §11.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §11 ·
[METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) Tasks 7, 9

**Test Requirements:** GPU pipeline reaching only a GPU worker; load-based
preference between two eligible workers; no-eligible-worker queues rather than
503s.

---

## Task V6: Worker-visible path advertisement

**Argumentation Summary:** `pathGlobs` resolve **on the worker, relative to its
own working directory**, and a non-existent root yields an empty list
*silently*. Loom has no idea what any worker can see. Today that produces a
confusing green empty run; in a multi-worker pool it becomes a correctness
problem, since work may be handed to a worker that cannot see the media and
will report success having processed nothing.

**Improvement Summary:** Make filesystem visibility explicit and checkable.

```
1. Extend registration with the media roots a worker can access - CONFIGURED,
   not discovered. A worker scanning for mounts is a security and correctness
   hazard (see METALOOM_ARCHITECTURE_V2.md §11).

2. Validate a selection against the target worker's roots BEFORE dispatch,
   and return a specific error when nothing matches. This is a real error,
   not an empty success.

3. Distinguish "path not visible to this worker" from "path visible and
   genuinely empty". These are indistinguishable today and both look green.

4. With V4, this becomes an input to lease admission: only offer an item to a
   worker whose roots contain it.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §11,
§12

**Test Requirements:** A run against an invisible path fails clearly rather
than succeeding empty; roots reach the coordinator in the registration payload.

---

## Task V7: Deferred (async) node SPI

**Argumentation Summary:** `PipelineNode.process(...)` returns a `NodeResult`
synchronously, so a node that waits on anything — a remote peer, Ollama, an ASR
endpoint — **parks a thread for the entire call**. This blocks cross-instance
node delegation (V10) entirely, and it wastes capacity today on I/O-bound nodes
even on a single worker. That second point matters: **this task pays for itself
before any distribution exists**, which is why it can proceed in parallel.

**Improvement Summary:** Let a node return a promise instead of a value.

```
1. Add to PipelineNode, with a default that keeps every existing node working:

     default Single<NodeResult> processAsync(LoomMedia media,
             Map<String, NodeResult> upstream) {
         return Single.fromCallable(() -> process(media, upstream));
     }

   Have ReactivePipelineExecutor subscribe to processAsync(...) instead of
   wrapping process(...) in Single.fromCallable itself. Because the executor
   is already Single-based, this is a small change at the seam.

2. DO NOT revive apply()/partition()/MediaContext. That is a second, unfinished
   execution design that the executor never calls. Delete it or leave it; do
   not build on it.

3. Fix the semaphore release. Per-node concurrency is a blocking acquire()
   released in a finally. With no callable to return from, the permit must be
   released on the Single's terminal event (doFinally) or permits leak and the
   node wedges permanently after N deferred executions.

4. Fix timeout placement FIRST. The per-node timeout is already applied
   outside the permit-holding body, so a hung node keeps its permit; with
   deferred results that window is unbounded. See PIPELINE_TASKS Task 4.

5. Handle late results. PIPELINE_COMPLETED fires from doOnComplete on the
   outer stream; a deferred result resolving afterwards would be reported
   against a finished run AND would miss the syncToLoom buffer (collection
   happens on COMPLETED). Either await outstanding deferred results before
   completing, or explicitly reject late ones - decide and document which.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §10 ·
[../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §4.8, §6.2

**Test Requirements:** An async node completing after its caller returns;
permits released on success, failure, and timeout; a late-resolving result
handled per the chosen policy; existing synchronous nodes unaffected.

---

## Task V8: Cortex coordinator role

**Argumentation Summary:** For a tree, a master Cortex must accept connections
from child instances — which Cortex cannot do at all today. It is purely a
WebSocket *client* with a small health HTTP server. A master needs the whole
peer side: accepting upgrades, authenticating children, tracking registrations,
granting leases, collecting results. **Gated on V2** — build this only if
multi-site is real.

**Improvement Summary:** Let a Cortex act as a coordinator for children, using
the same protocol it speaks upward.

```
Depends on V2 (confirmed need), V3 (recursive protocol), V4 (lease semantics).

1. Implement CoordinatorRole (from V3) inside Cortex, behind an explicit
   config flag. Reuse the protocol module - do not fork Loom's implementation.

2. DECIDE STATELESS VS DURABLE, and record it. This is the crux:
   - Stateless relay: forwards leases from Loom, holds nothing. Death costs
     only the in-flight relay. Simple, and preserves fault recovery.
   - Durable master: owns a real queue, survives disconnection from Loom,
     enables autonomous site operation. Needs storage and reconciliation.
   - In-memory master holding lease state (i.e. copying today's
     ProcessorRegistry) has the costs of BOTH and the benefits of NEITHER.
     Do not do this by default.

3. A master must advertise its subtree's AGGREGATE capability and capacity
   upward, so its parent treats it as one large worker.

4. Two roles means two auth paths and two reconnect strategies. Both need
   TLS and real authentication (METALOOM_ARCHITECTURE_TASK.md Task 14).

5. Depth limit. Support exactly two levels initially and reject deeper
   nesting explicitly - arbitrary depth multiplies the failure modes for no
   demonstrated benefit.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §4,
§7, §12

**Test Requirements:** A master accepting and tracking children; subtree
capability aggregated upward; master death behaving per the chosen
stateless/durable model; depth limit enforced.

---

## Task V9: Subtree aggregation and event relay

**Argumentation Summary:** In a tree, every progress event from a leaf must
traverse master → Loom → UI. That adds latency and, more seriously, **a second
place to drop events** — the existing broadcaster already drops on
`writeQueueFull()` with no replay and no dead-letter. Without care, live
progress in a tree becomes materially less trustworthy than it is today.

**Improvement Summary:** Relay events without losing attribution or silently
dropping them.

```
Depends on V8.

1. Every relayed event must carry the ORIGINATING instance id, not the
   relaying one. Losing this makes "which leaf processed this file?"
   unanswerable, which is a debugging disaster in a fleet.

2. Aggregate NODE_STATS at the master rather than forwarding every leaf's
   500ms tick upward - that is the one place event volume genuinely justifies
   a tier.

3. Do NOT aggregate lifecycle events (NODE_FAILED, PIPELINE_COMPLETED).
   Failures must reach Loom individually and attributably.

4. Count and expose drops at every hop. A silently lossy relay is worse than
   no relay. Ties into METALOOM_ARCHITECTURE_TASK.md Task 15 (metrics).

5. Cap relay buffering explicitly and document the policy. Do not repeat the
   broadcaster's mistake of documenting a bounded queue that does not exist.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §4,
§7

**Test Requirements:** Origin attribution survives two hops; stats aggregate
while failures do not; drops are counted and exposed at each hop.

---

## Task V10: Cross-instance node delegation

**Argumentation Summary:** The most ambitious form of the proposal: an
individual *node* delegates its work to another instance, rather than
distributing whole media items. This is what would let a CPU worker hand a
single `facedetect` step to a GPU peer mid-pipeline. It is also the piece with
the worst cost/benefit ratio, because it requires either shared storage or
media movement per node invocation.

**Improvement Summary:** A delegating node whose `processAsync` resolves from a
peer.

```
Depends on V7 (deferred results) and V8 (coordinator role). Gated on V1 -
without shared storage this requires moving media per node, which is likely
prohibitive.

1. Implement a DelegatingNode: processAsync sends a request to a peer and
   returns a Single completed by the reply. Needs a correlation id, a
   timeout, and a reassignment policy on peer death.

2. Media access is the crux. Either the peer already sees the file (shared
   storage, V1) or the media must be shipped. Measure before assuming
   shipping is viable - for video it almost certainly is not.

3. Upstream results must travel with the request; the peer needs the
   upstream NodeResult map to execute the node.

4. Peer failure must degrade to local execution or reassignment, never to a
   silent skip. Given the system's history of silent success, be explicit
   and loud here.

5. Evaluate honestly against per-item distribution (V4) first. Per-item is
   much simpler and probably sufficient. Only pursue per-node delegation if
   there is a demonstrated case - e.g. one scarce GPU serving many CPU
   workers - that per-item distribution cannot serve.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §10,
§14 Q6

**Test Requirements:** A node executing on a peer with results returning
correctly; peer death falling back rather than skipping; a measured comparison
against per-item distribution on the same corpus.

---

## Task V11: Node capability whitelist

**Argumentation Summary:** A Cortex instance today will attempt any node kind it
is handed, and Loom has no idea what any instance can actually run. That blocks
heterogeneous fleets (one GPU box among CPU boxes), blocks least-privilege
deployment (an exposed worker must not receive LLM prompts or Loom-sync
credentials), and leaves the "6 of 29 kinds are real" gap invisible. This is the
**single highest-value task in this file**: it is small, it is useful on its own
regardless of which variant is ever built, and Variants C and D are impossible
without it.

**Improvement Summary:** Let an instance declare which node kinds it may
execute; let Loom record and enforce it.

```
Build this FIRST. It does not depend on V0/V1 and is not wasted under any
outcome.

1. Cortex config: an allow-list of node kinds.
     nodes:
       allowed: [filesystem-source, sha512, sha256, md5, chunk-hash]
   Plus a CLI flag and env var. DEFAULT = every kind the instance can actually
   execute, so existing deployments are unchanged.

2. Extend ProcessorRegistration (loom-shared/rest-model/.../message/) with
   supportedNodeKinds: Set<String>. It currently carries only nodeId, name,
   priority, host, capabilities. Persist it with the registry (depends on
   METALOOM_ARCHITECTURE_TASK.md Task 9).

3. Keep it DISTINCT from ProcessorCapability. Capability describes hardware
   (CPU/IO/GPU); the whitelist describes policy. A worker may have a GPU and
   still be policy-restricted to hashing. Do not conflate them.

4. Enforce on dispatch: never send a node kind outside the target's list.

5. Fail loudly when a pipeline needs a kind no online worker offers - at save
   time if determinable, at dispatch time otherwise. Given this system's
   history of silent success, an unsatisfiable pipeline must NEVER present as
   an empty green run.

6. Report the fleet-wide union of supported kinds via REST so the UI can show
   which palette entries are actually runnable. This complements
   METALOOM_ARCHITECTURE_TASK.md Task 1 - together they turn "23 kinds
   silently no-op" into a queryable fact.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §8 ·
[METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) Tasks 1, 9

**Test Requirements:** A worker advertising a restricted list never receives an
excluded kind. A pipeline requiring an unavailable kind fails with a specific
message rather than succeeding empty. Default registration is unchanged from
today's behaviour.

---

## Task V12: Node affinity in the pipeline definition

**Argumentation Summary:** If Loom is to place work per node, something must
prevent every graph edge from becoming a network round trip. Affinity marks
nodes that execute together on one instance. It is also more than an
optimisation: upstream results live in an **in-memory map** on the executing
JVM, so splitting a group that shares results is a *correctness* failure unless
those results are shipped. Affinity is the dial between whole-graph dispatch
(Variant A) and per-node dispatch (Variant C).

**Improvement Summary:** Add affinity groups to the definition, with a safe
default and validation that catches unsatisfiable groupings.

```
Depends on V11 (whitelist) and PIPELINE_TASKS Task 1 (the edges/dependencies
schema fix - do not add a field to a schema that is being changed).

1. Add an optional "affinity": "<group-name>" to each node in the Loom
   definition format, the PipelineEditor property panel, and
   PipelineValidationService.

2. DEFAULT TO ONE GROUP PER PIPELINE. This is the important decision. A default
   of "each node its own group" silently converts every existing pipeline into
   per-node dispatch with a hop per edge. Distribution must be the deliberate
   act, not the default.

3. Treat a violated affinity as an ERROR, not a performance regression -
   nodes in one group share an in-memory result map.

4. Validate satisfiability at save time: a group whose members require
   conflicting capabilities, or which no single worker's whitelist can serve,
   must be rejected with a message naming the conflict. This is precisely the
   kind of constraint that would otherwise surface as a silent empty run.

5. Affinity groups nodes; it must NOT pin to a named worker. Placement stays
   the scheduler's output - see METALOOM_ARCHITECTURE_V2.md §11.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §9,
§11 ·
[../features/pipeline/PIPELINE_TASKS.md](../features/pipeline/PIPELINE_TASKS.md)
Task 1

**Test Requirements:** Affinity survives a definition round trip; the default
groups a whole pipeline together; an unsatisfiable group is rejected at save
with a precise message; a group never spans two instances at runtime.

---

## Task V13: Segmented dispatch (Variant D)

**Argumentation Summary:** The recommended target architecture. Loom partitions
the pipeline into segments at capability/whitelist boundaries and dispatches a
whole **segment** to one worker, rather than a whole run (too coarse for
heterogeneous fleets) or a single node (too chatty — a 10-node pipeline over
100 000 files would become ~1 000 000 dispatch decisions). Each segment runs
in-process on the existing reactive engine, so intermediate results stay in
memory and only segment boundaries cross the network.

**Improvement Summary:** Let Loom decide placement per segment while leaving
execution distributed.

```
Depends on V4 (queue + leases), V5 (scheduling), V11 (whitelist),
V12 (affinity), and V7 (deferred nodes, for the handoff).
Gated on V14 - if the fleet is homogeneous this degenerates to Variant A and
adds nothing.

1. Segment the graph on Loom: partition nodes by required capability and
   whitelist, honouring affinity groups as indivisible units. Loom already
   walks the graph (PipelineValidationService implements Kahn's algorithm for
   cycle detection), so the graph-reasoning foundation exists.

2. A segment is dispatched and leased EXACTLY like a media item in V4 - same
   queue, same lease expiry, same retry. Do not build a second mechanism.

3. Decide and document the intermediate-result handoff at segment boundaries
   (open question Q6): shipped via Loom, written to shared storage, or
   re-derived. Measure before choosing - embeddings and thumbnails are large.

4. A homogeneous fleet must produce exactly ONE segment, i.e. Variant A
   behaviour, with no special-casing. This is the correctness check that the
   design degrades gracefully.

5. Emit per-segment tracking events so the UI can show where an item is
   without needing per-node dispatch.

6. Measure against Variant A on the same corpus. If segmentation does not beat
   the flat pool on a heterogeneous fleet, stop - the added complexity is not
   earning its keep.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §6,
§7

**Test Requirements:** A homogeneous fleet yields one segment; a GPU-requiring
node lands on a GPU worker while its neighbours do not; a failed segment
retries without redoing prior segments; affinity groups are never split; a
measured comparison against Variant A.

---

## Task V14: Confirm fleet heterogeneity

**Argumentation Summary:** Variant D earns its cost only when workers differ —
one GPU box among CPU boxes, or workers with different node whitelists. On a
fleet where every worker can run every node, segmentation produces a single
segment and adds complexity for nothing. This is a cheap question with an
expensive consequence, so it belongs before the work, not after.

**Improvement Summary:** Answer it explicitly and record the answer.

```
This is a decision task.

Answer:
  1. Do real or planned deployments mix hardware classes (GPU vs CPU-only)?
  2. Are there workers that must be POLICY-restricted even though capable -
     DMZ, tenant-shared, cost-controlled? (Note: this alone justifies V11
     regardless of the answer to (1).)
  3. Which node kinds actually require a GPU in practice? facedetect,
     captioning, and whisper are the candidates - confirm against real
     deployments rather than assuming.
  4. What fraction of a typical pipeline's runtime is spent in those nodes?
     If it is small, segmentation buys little.

If the fleet is homogeneous and no policy restriction is needed: mark V13 as
WON'T DO and stop at Variant A. Record the decision and its date here.
```

**References:** [METALOOM_ARCHITECTURE_V2.md](METALOOM_ARCHITECTURE_V2.md) §7,
§14 Q1

**Test Requirements:** None (decision).

---

## Progress Assessment

- [x] Decision tasks separated from implementation tasks and placed first
- [x] Dependencies on the as-built task list made explicit
- [x] Flat-pool tasks specified (V4, V5, V6)
- [x] Tree tasks specified and gated on a confirmed multi-site need (V8–V10)
- [x] Async node work specified as independently valuable (V7)
- [x] Recursive-protocol foundation placed before dispatch work (V3)
- [x] Node capability whitelist specified as build-first and
      topology-independent (V11)
- [x] Affinity specified with a safe default and satisfiability validation (V12)
- [x] Segmented dispatch specified as the recommended target (V13)
- [ ] **V11 whitelist built** — the one task with no preconditions
- [ ] **V0 benchmark run** — may cancel this entire workstream
- [ ] **V1 storage decision** — blocks V4, V10, V13
- [ ] **V14 heterogeneity answer** — blocks V13 (Variant D over A)
- [ ] **V2 multi-site answer** — blocks V8–V10
- [ ] Intermediate-result handoff mechanism chosen (V13 step 3)
- [ ] Target fleet size established
- [ ] Multi-tenancy requirements established — determines whether the whitelist
      is a security boundary or merely advisory

---

_Git HEAD revision: `92bc1153e50c43efb65e4d78874823c9ec1f4408`_
_Last updated: 2026-07-18 19:30 UTC_
