# MetaLoom Architecture — Task List

> Work items derived from the architecture review in
> [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md), verified against the
> code at `92bc115` on 2026-07-18. Format follows
> [../TASKS.template.md](../TASKS.template.md).
>
> **Scope note.** This list covers the **Loom ↔ Cortex interaction as it is
> built today**. Two neighbouring lists exist and are referenced rather than
> duplicated:
>
> | List | Covers |
> |---|---|
> | [../features/pipeline/PIPELINE_TASKS.md](../features/pipeline/PIPELINE_TASKS.md) | Pipeline internals — definition schema, node registration, executor lifecycle |
> | [METALOOM_ARCHITECTURE_V2_TASK.md](METALOOM_ARCHITECTURE_V2_TASK.md) | Multi-instance work — the distributed queue, tree topology, async nodes |
>
> **Ordering is deliberate.** Tasks 1–4 close the silent-failure holes that make
> the system report success for work it did not do. **Nothing in the V2 list
> should start before those land** — distributing a system that lies about its
> results multiplies the problem across machines instead of surfacing it.

---

## Progress Overview

| # | Task | Severity | V2 depends on it? |
|---|------|----------|-------------------|
| 1 | Fail loudly on unregistered node types | 🔴 Blocking | Yes |
| 2 | Flush the result buffer at end of run | 🔴 Blocking | Yes |
| 3 | Sync more than hashes | 🔴 Blocking | Yes |
| 4 | Set `syncToLoom` from the Loom definition | 🔴 Blocking | Yes |
| 5 | Graceful shutdown with drain | 🟠 Serious | Yes |
| 6 | Make worker throughput configurable | 🟠 Serious | Yes — and may cancel V2 |
| 7 | Fix the `cpuLoad` calculation and populate `ioLoad` | 🟠 Serious | Yes |
| 8 | Make `cortex.yml` actually load, or delete it | 🟡 Debt | No |
| 9 | Stable, persistent worker identity | 🟠 Serious | Yes |
| 10 | Heartbeat timeout and worker eviction | 🟠 Serious | Yes |
| 11–13 | *moved to [V2 list](METALOOM_ARCHITECTURE_V2_TASK.md) as V4–V6* | — | — |
| 14 | Secure the control channel (TLS + auth) | 🔴 Security | Yes |
| 15 | Expose Prometheus metrics | 🟡 Debt | No |
| 16 | Reconcile the stale specs | 🟡 Debt | No |

- [ ] **Phase A — stop lying** (1, 2, 3, 4)
- [ ] **Phase B — make one worker solid** (5, 6, 7, 8)
- [ ] **Phase C — make workers durable** (9, 10, 14)
- [ ] **Phase D — operability** (15, 16)
- [ ] **Then, and only then:** [the V2 list](METALOOM_ARCHITECTURE_V2_TASK.md)

Task 6 is worth singling out: it makes a single worker's throughput measurable
and tuneable, and its benchmark (V2 Task V0) may show that distribution is not
needed at all. It is the cheapest task with the largest possible consequence.

---

## Task 1: Fail loudly on unregistered node types

**Argumentation Summary:** The node descriptor registry advertises **29** node
kinds to the UI palette, but only **5** are registered with the executable
factory (`sha512`, `sha256`, `md5`, `chunk-hash`, `thumbnail`). The other 24 —
including `whisper`, `ocr`, `llm`, `facedetect`, `tika`, and every `filter-*` —
fall back to a stub that logs at **debug** level and returns `COMPLETED`. A user
can assemble a pipeline entirely out of nodes that do nothing and watch it run
green. This is the single most dangerous behaviour in the system, and it is what
makes every other defect in this list hard to notice.

**Improvement Summary:** Make an unimplemented node kind a loud, early failure
instead of a silent success, and stop advertising kinds that cannot run.

```
1. In cortex/core/.../pipeline/loader/RegistryNodeFactory.java (~line 83):
   Replace the debug-level stub fallback. A missing producer must throw with a
   clear message naming the kind and listing the registered kinds.

2. Fail at LOAD time, not run time. LoomPipelineLoader.loadAndRegister() should
   refuse to register a pipeline containing unresolvable node kinds, and report
   which ones, so the failure surfaces on `reload-pipelines` rather than
   mid-run.

3. Surface the constraint in the UI. NodeDescriptorRegistry should distinguish
   "advertised" from "executable". The palette must visibly mark the 24
   non-executable kinds as unavailable rather than offering them as normal.
   Server-side validation (PipelineValidationService) should reject them on
   save with a specific message.

4. If any stub behaviour is still wanted for local UI development, put it
   behind an explicit opt-in flag that is off by default and logs at WARN on
   every use.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §12 ·
[../features/pipeline/PIPELINE_TASKS.md](../features/pipeline/PIPELINE_TASKS.md)
Task 3

**Test Requirements:** A test asserting that loading a definition with an
unknown kind throws rather than producing a pipeline. A test asserting the
registered set is exactly the executable set. A UI test asserting
non-executable kinds are not selectable.

---

## Task 2: Flush the result buffer at end of run

**Argumentation Summary:** `DefaultLoomBulkSyncCollector` flushes on three
triggers: reaching the 100-entry batch size, the end of an `executeBatch(...)`
call, and an explicit `flush-sync` work order. The streaming `execute(...)` path
— **the one every `run-pipeline` work order actually uses** — has no flush in
its `doOnComplete` or `doFinally`. A run that ends with fewer than 100 pending
entries leaves them in memory forever. Since 100 synced items is a large run,
**the common case is that a pipeline run sends nothing back to Loom**, and it
reports success while doing so.

**Improvement Summary:** Flush when the stream completes, and when it fails.

```
1. In cortex/pipeline-core/.../executor/ReactivePipelineExecutor.java:
   Add syncCollector.flush() to the terminal path of execute(Pipeline,
   Flowable<LoomMedia>). Use doFinally, not doOnComplete, so a cancelled or
   errored run still flushes what it computed.

2. Make the flush failure-tolerant: a failing flush must not mask the run's
   real outcome. Log and let the existing re-queue-on-failure behaviour retry.

3. Report the flushed count in the PIPELINE_RUN_COMPLETED payload so Loom can
   tell "0 results because nothing was configured to sync" apart from
   "0 results because the flush was lost".
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §9

**Test Requirements:** A test running a streaming pipeline with a `syncToLoom`
node over fewer than `batchSize` items and asserting the writer received them.
A test asserting a cancelled run still flushes.

---

## Task 3: Sync more than hashes

**Argumentation Summary:** `LoomBulkSyncWriterImpl.mergeOutputs` reads exactly
four output keys — `sha512`, `sha256`, `md5`, `chunkHash` — and drops everything
else. The Javadoc concedes it: other outputs "can be plumbed here as their
corresponding update fields are exercised". The consequence is that the entire
value proposition of Cortex — transcripts, captions, OCR text, face embeddings,
fingerprints, thumbnails — is computed at real CPU/GPU cost and then **discarded
before the REST call**. It survives only in local xattrs on the worker.

**Improvement Summary:** Carry every node output that has a home in the asset
model, and make an output with no home an explicit, visible decision.

```
1. Audit NodeOutputKey usages against the AssetBulkUpdateRequest / asset model
   fields. Produce the mapping table first — some outputs (embeddings, face
   detections) may need their own endpoints rather than asset metadata.

2. In cortex/core/.../impl/loom/LoomBulkSyncWriterImpl.java, extend
   mergeOutputs beyond the four hash keys per that mapping.

3. Any output key with no mapping must be logged at WARN once per key per run —
   not silently dropped. Silent drops are what hid this for as long as it hid.

4. Coordinate with Loom: some targets do not exist yet. Where an asset field is
   missing, that is its own follow-up (relates to A-PE4 — no store for
   intermediate node results).
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §9 ·
[../features/pipeline/PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md)
R10

**Test Requirements:** Per-output-type tests asserting the value reaches the
bulk request. A test asserting an unmapped output produces a warning.

---

## Task 4: Set `syncToLoom` from the Loom definition

**Argumentation Summary:** The Loom UI's saved node format is
`{id, type, name, x, y}` — it has **no `syncToLoom` field**. The Cortex loader
reads `syncToLoom` with a default of `false`. So every node in a UI-authored
pipeline has syncing disabled, and no result is ever collected regardless of
Tasks 2 and 3. This is an independent break in the same round trip and must be
fixed alongside the definition-schema work.

**Improvement Summary:** Make sync intent expressible in the UI and carried
through the definition.

```
1. Add syncToLoom to the Loom node definition schema, the PipelineEditor node
   property panel, and PipelineValidationService.

2. Choose a sane default. Recommendation: default TRUE for nodes whose
   descriptor declares outputs that map to asset fields, false otherwise — a
   user who adds a hash node expects the hash to be stored. Whatever is chosen,
   make it explicit in the descriptor rather than a bare literal in the loader.

3. Ensure cortex/cli/.../PipelineNodeFactoryModule.java honours the value from
   the definition (it already calls setSyncToLoom when the flag is set — the
   flag simply never arrives).

4. Do this in the same change as PIPELINE_TASKS Task 1 (edges vs dependencies).
   Both are schema changes to the same document; splitting them means two
   migrations of the same seeded demo data.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §9 ·
[../features/pipeline/PIPELINE_TASKS.md](../features/pipeline/PIPELINE_TASKS.md)
Task 1

**Test Requirements:** A loader test asserting `syncToLoom` survives from a
Loom-format fixture into the built pipeline.

---

## Task 5: Graceful shutdown with drain

**Argumentation Summary:** There is **no shutdown hook anywhere in the
codebase**. On `SIGTERM` — which is exactly how Kubernetes, Docker, and systemd
stop a container — the JVM dies with in-flight work abandoned and the sync
buffer unflushed. Up to 100 computed results are lost with no record that they
existed. This is a correctness problem today and an absolute blocker for a grid,
where scaling down is a routine, expected event rather than an incident.

**Improvement Summary:** Handle `SIGTERM`, stop accepting work, drain, then exit.

```
1. Register a shutdown hook in CortexImpl (or CortexBootstrapInitializer.deinit)
   that: (a) marks the worker as draining and reports STATE_CHANGE to Loom so
   it stops being selected; (b) waits, with a bounded timeout, for in-flight
   media to finish; (c) calls syncCollector.flush(); (d) calls
   pipelineExecutor.shutdown() — which currently is never called at all;
   (e) closes the control channel last, so the drain is observable.

2. Make the drain timeout configurable and default it below the typical
   orchestrator grace period (30s) so the drain completes before SIGKILL.

3. Send STATE_CHANGE(TERMINATING). Cortex never sends STATE_CHANGE today even
   though Loom implements the handler — this is its first real use.

4. Anything undrainable at timeout must be logged at ERROR with the count and
   the media paths, so lost work is at least attributable.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §8

**Test Requirements:** A test asserting the hook flushes pending sync entries. A
test asserting `STATE_CHANGE` is sent before the socket closes. A container test
issuing `docker stop` and asserting clean exit within the grace period.

---

## Task 6: Make worker throughput configurable

**Argumentation Summary:** `maxConcurrentMedia` defaults to **4** and has no CLI
flag and no environment variable; the YAML file that could set it is never read
(Task 8). The container caps heap at **512 MB**. So a deployed Cortex processes
4 media items at a time on a 512 MB heap regardless of whether it is on a laptop
or a 64-core server. This is almost certainly the binding throughput constraint
today, and it makes any horizontal-scaling measurement meaningless — you cannot
tell whether adding machines helped if each machine was throttled to 4.

**Improvement Summary:** Expose the throughput knobs, and tune one worker before
adding a second.

```
1. Add --max-concurrent-media / CORTEX_MAX_CONCURRENT_MEDIA to CortexCLI and
   EnvDefaultProvider, wired into CortexOptions.

2. Default it to something derived from the machine
   (e.g. Runtime.availableProcessors()) rather than a flat 4.

3. Raise or parameterise the container heap (Containerfile JAVA_TOOL_OPTIONS);
   512 MB is low for video decode and model inference.

4. Consider Schedulers.io() replacement for the node execution path — it is
   unbounded, and per-node semaphores block its threads (see §10 of the
   architecture doc). A bounded or virtual-thread scheduler is the better fit
   for I/O-bound nodes (whisper, OCR, LLM).

5. BENCHMARK BEFORE AND AFTER, and record the numbers. There are currently no
   capacity measurements anywhere in the repo, so the grid work in Tasks 9-13
   has no baseline to justify it against.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §8, §10 ·
[../features/pipeline/PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md)
A-EX6

**Test Requirements:** A test asserting the flag and env var reach
`ReactivePipelineExecutor`. A recorded benchmark for 1 worker at concurrency
4 vs. tuned, on a fixed media corpus.

---

## Task 7: Fix the `cpuLoad` calculation and populate `ioLoad`

**Argumentation Summary:** `STATUS_UPDATE` claims to carry a CPU load
*percentage* (0–100) but computes it as `systemLoadAverage * 100`. Load average
is a count of runnable processes, not a fraction: a load of 1.0 reports as
"100%" on a 64-core machine that is 98% idle. `memoryUsed`/`memoryTotal` are JVM
heap, not system memory, despite the field naming. `gpuLoad` and `ioLoad` are
never populated at all. Nothing consumes these today, so the bug is invisible —
but Task 12 intends to schedule on them, and scheduling on a metric that pegs at
100% under trivial load would be worse than not scheduling on it.

**Improvement Summary:** Make the reported load numbers mean what their names
say, before anything depends on them.

```
1. In LoomControlChannel.collectSystemStatus():
   - cpuLoad: use OperatingSystemMXBean.getCpuLoad() (0..1 -> *100), or divide
     the load average by availableProcessors(). Do not report a value at all
     when the platform cannot supply one — null is honest, 100 is not.
   - memory: report SYSTEM memory, or rename the fields to heapUsed/heapMax.
     The current naming is actively misleading.
   - disk: report the filesystem holding the media path, not the process CWD.

2. Populate ioLoad, or delete the field. An always-null field documented as
   populated is worse than an absent one.

3. gpuLoad requires GPU capability detection — fold into Task 12 rather than
   guessing here.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §5

**Test Requirements:** A unit test asserting `cpuLoad` stays within 0–100 and
scales with core count. A test asserting unsupported platforms yield null.

---

## Task 8: Make `cortex.yml` actually load, or delete it

**Argumentation Summary:** Both [CORTEX.md](CORTEX.md) and
[CONFIGURATION.md](CONFIGURATION.md) document a precedence chain of CLI → env →
`~/.config/metaloom/cortex.yml` → defaults. In reality `CortexOptionsLoader` is
consulted only when no options object is supplied, and `CortexCLIMain` always
supplies one — so **the YAML file is never read on the server path**, and its
merge logic is commented out. Anything configured only there, including
`maxConcurrentMedia`, per-node options, and the Loom token, is silently ignored.
The container compounds this by mounting `/config` to a path the loader would
not consult even if it ran.

**Improvement Summary:** Pick one: implement the documented precedence, or
remove the file and the docs describing it.

```
Recommendation: IMPLEMENT it. Per-node options are genuinely awkward as CLI
flags, and the config file is the natural home for them.

1. In CortexCLIMain.parseOptions, load the YAML first, then overlay env, then
   overlay CLI flags. Restore and finish the commented-out merge logic in
   CortexOptionsLoader (applyEnvironmentVariables / applyCommandLineArgs).

2. Fix the container path: either point HOME/.config at the /config volume or
   change defaultConfigPath to honour an explicit --config flag. As it stands
   the volume is dead weight.

3. If instead the decision is to delete: remove CortexOptionsLoader, the
   VOLUME, and every mention from CORTEX.md and CONFIGURATION.md in the same
   change. Do not leave documented-but-dead config.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §8 ·
[CONFIGURATION.md](CONFIGURATION.md)

**Test Requirements:** A test asserting each precedence level overrides the one
below it. A container test asserting a mounted config file takes effect.

---

## Task 9: Stable, persistent worker identity

**Argumentation Summary:** `nodeId` is `"cortex-" + UUID.randomUUID()`,
generated fresh in a `final` field on every process start and persisted nowhere.
Loom's `ProcessorRegistry` is a plain in-memory `ConcurrentHashMap` with no
backing table. So a restarted worker is an entirely new entity, a restarted Loom
forgets every worker, and there is no way to address, pin, or track a specific
worker over time. Every grid feature — leases, work reassignment, per-worker
routing, capacity planning — needs an identity that outlives a process.

**Improvement Summary:** Give workers durable identity and Loom a durable
registry.

```
1. Cortex side: derive nodeId deterministically, in this precedence —
   --node-id flag / CORTEX_NODE_ID env, else a UUID persisted under the meta
   path, else a hash of hostname + MAC. It must survive a restart.

2. Loom side: add a `processor` table (Flyway migration + jOOQ regeneration +
   db/api DAO + jooq/memory impls + db/api-test contract test, per the project
   convention). Persist nodeId, name, capabilities, priority, first_seen,
   last_seen, state.

3. Keep the live ServerWebSocket in memory keyed by nodeId — the socket is not
   persistable. Reconnecting to a known nodeId must UPDATE the row, not insert
   a duplicate.

4. Add a uniqueness guard: two live sockets claiming one nodeId is currently
   unvalidated and would silently corrupt dispatch. Reject the second, or
   displace the first — but decide explicitly.

5. Make name and priority configurable while here; both are hardcoded
   constants today, so every worker is indistinguishable to the scheduler.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §3, §14

**Test Requirements:** A test asserting nodeId survives a restart. A test
asserting reconnect updates rather than duplicates. A test asserting a duplicate
nodeId is handled per the chosen policy.

---

## Task 10: Heartbeat timeout and worker eviction

**Argumentation Summary:** Cortex sends a `HEARTBEAT` every 10 s and Loom
replies — but **Loom never checks whether one stopped arriving**. `lastSeen` is
recorded and never read by `selectProcessor`. Only a cleanly closed socket
removes a worker. A worker whose network half-opens, whose host is powered off,
or whose JVM is frozen stays `ONLINE` indefinitely and keeps being selected for
work that vanishes into nothing — and since `dispatchWorkOrder` failure is not
retried or queued, that work is simply lost.

**Improvement Summary:** Treat a missed heartbeat as evidence and act on it.

```
1. Add a periodic sweep in ProcessorRegistry: any worker whose lastSeen exceeds
   a threshold (suggest 3 missed heartbeats = 30s) transitions to OFFLINE and
   becomes ineligible for selection.

2. Do NOT unregister on timeout once Task 9 lands — keep the row, mark it
   offline. Registry history is an input to scheduling and capacity planning.

3. Any run dispatched to a worker that then times out must be failed or
   requeued, not left RUNNING. Today PipelineEndpointService's 60s watchdog
   covers only the initial acknowledgement, not a worker dying mid-run — a run
   that is acked and then abandoned stays RUNNING forever.

4. Fix the dispatch route-ordering fragility while here: ProcessorEndpoint
   registers /ws before secure(basePath + "/:uuid") and depends purely on
   registration order, where PipelineEventEndpoint defends itself explicitly
   with .order(-1000). Make ProcessorEndpoint do the same.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §3, §6 ·
[../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §6.3

**Test Requirements:** A test asserting a silent worker goes OFFLINE after the
threshold. A test asserting a run on a timed-out worker reaches a terminal
state. A test asserting `/ws` is not captured by the wildcard auth route.

---

## Tasks 11-13: moved to the V2 design

The grid/distribution work items — the durable item-grained work queue with
leases, capability- and load-aware scheduling, and worker-visible path
advertisement — have moved to
[METALOOM_ARCHITECTURE_V2_TASK.md](METALOOM_ARCHITECTURE_V2_TASK.md) (Tasks V4,
V5, V6), alongside the tree-topology and async-node work they belong with.

They are **not** prerequisites for anything in this file. The reverse is true:
every task over there depends on tasks in here. Nothing in the V2 list should
start before Tasks 1-4 land.

Tasks 9 and 10 above (stable identity, heartbeat timeout) deliberately stayed
here — they are genuine defects in the system as built, worth fixing whether or
not the fleet ever grows.

---


## Task 14: Secure the control channel

**Argumentation Summary:** Three independent weaknesses compound here.
`LoomControlChannel` builds a `ws://` URL unconditionally — there is **no TLS
path at all**. WebSocket auth is **lenient by default**: a connection with no
token is accepted with a warning unless `LOOM_WS_STRICT_AUTH=true`. And the REST
client inside Cortex is constructed with only hostname and port — **no token is
ever set** — so all result sync and pipeline fetching is unauthenticated plain
HTTP. A grid multiplies the exposure: more workers, more links, and a worker
that can register freely can also receive work orders and submit results.

**Improvement Summary:** Encrypt the transport, authenticate both channels, and
make strict mode the default.

```
1. Support wss:// — add an ssl/tls option to LoomClientOptions and set
   setSsl(true) on WebSocketConnectOptions. Default to TLS when the port
   implies it, and allow explicit override.

2. Set the token on the REST client in CortexClientModule.restClient(). The
   request layer already sends Authorization: Bearer when a token is present —
   nothing populates it. Resolve it the same way the WS channel does
   (LoomClientOptions.token, else LOOM_TOKEN).

3. Flip LOOM_WS_STRICT_AUTH to default TRUE. Accepting unauthenticated
   processors by default is not a defensible posture; the backward-compat
   window it was added for has served its purpose.

4. Add a --token CLI flag; today the token is env-only with no flag.

5. Validate ProcessorRegistration beyond null/blank nodeId, and add origin
   validation on the WS upgrade.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §4 ·
[../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §6.9

**Test Requirements:** Tests for a wss:// connection, for rejection of an
untokened connection under the new default, and for the REST client attaching a
bearer token.

---

## Task 15: Expose Prometheus metrics

**Argumentation Summary:** Cortex exposes `/api/health` and `/api/ready` and
nothing else — no Prometheus endpoint, no Micrometer. Per-node throughput *is*
computed every 500 ms, but it is formatted into a **display string**, emitted as
a WebSocket event, and never stored. So it cannot be scraped, cannot be graphed,
and is **lost entirely when Loom is unreachable** — precisely when you most want
it. Operating a pool of workers without scrapeable metrics is impractical.

**Improvement Summary:** Publish real metrics from the worker itself.

```
1. Add a /metrics endpoint to MonitoringService (Micrometer + Prometheus
   registry).

2. Publish: per-node processed/failed/active counts, media throughput, sync
   buffer depth and flush outcomes, control-channel connection state and
   reconnect count, JVM basics.

3. Emit NODE_STATS as structured fields rather than a formatted string, so the
   same numbers serve both the UI and the metrics endpoint. Note pending is
   currently hardcoded to 0 because queue depth is not observable — with Task
   11 it becomes observable, so wire it then.

4. Metrics must not depend on the Loom connection. This is the whole point.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §7

**Test Requirements:** A test asserting `/metrics` serves Prometheus format and
that counters move during a run.

---

## Task 16: Reconcile the stale specs

**Argumentation Summary:** Several specification documents contradict the code,
and at least one contradicts another. An AI agent or new contributor following
them will write wrong code. Documented-but-false statements are worse than
missing documentation because they are trusted.

**Improvement Summary:** Correct each divergence found in this review.

```
Known divergences, all verified against the code at 92bc115:

1. spec/METALOOM.md §9 claims the pipeline engine is built on CompletableFuture
   with "no Reactor / RxJava". It is RxJava 3. spec/CONTEXT.md is correct.

2. spec/cortex/CORTEX.md:286 describes the reconnect backoff as EXPONENTIAL.
   The code is linear (BASE * attempt, capped).

3. spec/loom/WEBSOCKET.md §4.6 and PipelineEventBroadcaster's own Javadoc claim
   a bounded 1024-entry queue dropping the OLDEST event. There is no queue —
   the capacity constant is passed to the Subscriber constructor and discarded;
   the NEWEST event is dropped on writeQueueFull(). The spec checklist even
   marks this implemented.

4. spec/cortex/CONFIGURATION.md documents a CLI > env > YAML precedence chain.
   The YAML file is never read on the server path (see Task 8).

5. spec/loom/WEBSOCKET.md documents GPU capability and gpuLoad/ioLoad metrics;
   none are ever emitted.

6. CORTEX.md describes the REGISTER payload without noting that name, priority
   and capabilities are compile-time constants and nodeId is random per process.

7. spec/cortex/CORTEX.md states the Loom port default is 7733; the shipped
   Containerfile and start-cortex.sh both use 8092.

Fix each at the source, and add the correction to the relevant Progress
Assessment section rather than silently editing.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) §15

**Test Requirements:** None (documentation). Where a spec claim is cheap to
assert in code, prefer adding the test over trusting the prose.

---

## Progress Assessment

- [x] Silent-failure defects identified and prioritised ahead of scaling work
- [x] Result round-trip gaps enumerated (flush, hash-only writer, syncToLoom)
- [x] Worker lifecycle gaps enumerated (identity, heartbeat, drain)
- [x] Security gaps consolidated into a single task
- [x] Stale specification claims catalogued with evidence
- [x] Multi-instance work separated out into
      [METALOOM_ARCHITECTURE_V2_TASK.md](METALOOM_ARCHITECTURE_V2_TASK.md), with
      its prerequisites here made explicit
- [ ] Baseline throughput benchmark recorded (Task 6) — **without it, no
      distribution work can be justified or measured**; tracked as V2 Task V0
- [ ] Asset-model mapping for non-hash node outputs (Task 3 step 1)
- [ ] Shared-storage decision — tracked as V2 Task V1

---

_Git HEAD revision: `92bc1153e50c43efb65e4d78874823c9ec1f4408`_
_Last updated: 2026-07-18 19:10 UTC_
