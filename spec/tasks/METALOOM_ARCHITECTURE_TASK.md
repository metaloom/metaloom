# MetaLoom Architecture — Open Tasks (Variant C)

> Open architecture work items, re-derived from a code audit on 2026-08-11 at `8c153347`.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Scope.** Pending work only, for the architecture MetaLoom actually has: **Variant C** — Loom owns
> the pipeline graph (`loom/pipeline`, `PipelineRunEngine`) and dispatches individual node tasks or
> affinity segments to Cortex workers over the processor WebSocket. Ideas belonging to the rejected
> variants are recorded in [Dropped](#dropped) so nobody re-derives them.
>
> **Context:** [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) (what exists
> and why; §11 is the progress record) · [../cortex/CORTEX.md](../cortex/CORTEX.md) ·
> [../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) (the control protocol) ·
> [../concept/METALOOM_ARCHITECTURE_V2_PLAN_C.md](../concept/METALOOM_ARCHITECTURE_V2_PLAN_C.md)
> (**Tasks 1-6**: the deferred scheduling and batching refinements, plus the Q1/Q4/Q5 decision
> record — it stopped being a phase-by-phase build record on 2026-08-11) ·
> [PIPELINE_TASKS.md](PIPELINE_TASKS.md) (pipeline internals, definition schema, node registration)
>
> **Completed items are deleted, not ticked.** What used to be the "Landed" table is gone; the
> outcome record is [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) §11 and
> the code. Removed as implemented since the last revision: graceful drain, the run-item and
> node-task inspection routes, `itemUuid`/`elementSeq` on the event payload, `syncToLoom` gating
> `DaoAssetSink`, per-node result persistence, processor WebSocket authentication, Prometheus scrape
> endpoints, the mandatory worker id, definition versioning, lazy filesystem scanning, heartbeat
> expiry (Task 11). Task numbers are stable; the gaps are deliberate.
>
> **Do not duplicate work owned elsewhere.** Cross-reference by number instead of restating:
> [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 16** (re-baseline the workflow specs and
> `METALOOM_CONTEXT.md`), **Task 17** (`ctx.failure(...).next()` reports SUCCESS), **Task 18**
> (ledger provenance: constant `origin`, no `runUuid`/`taskUuid`) ·
> [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md) **Task 11** (the shared `src/api/http.ts` seam — the UI-side
> layering fix) · [CHAT_TASKS.md](CHAT_TASKS.md) (all chat/agent-loop items) ·
> [PIPELINE_TASKS.md](PIPELINE_TASKS.md) **Task 10** (dead surfaces, including deleting
> `LoomBulkSyncCollector` and the unbounded broadcaster queue) and **Task 13** (instrumenting the run
> engine — the five `loom/pipeline` meters this file's Task 8 deliberately excludes).
>
> **Ordering / blocking.**
>
> 1. **Task 11** (presence expiry) has landed: a worker that stops heartbeating is evicted and its
>    leases reclaimed through `LeaseReaper.reclaimWorker`. **Task 2** is the remaining half — the
>    socket-close path still waits out each lease — and the query and reclaim entry point it needs
>    (`PipelineNodeTaskDao.loadLeasedBy`, `LeaseReaper.reclaimWorker`) now exist.
> 2. **Task 6** is the security item. Flipping the strict-auth default is gated on confirming the UI
>    never opens the events socket before login (see its loom-ui note).
> 3. **Task 5** touches the same call as [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 18 — land 18
>    first, then batch, or the batching change has to be redone.
> 4. **Task 10** blocks nothing and gets more expensive with every new consumer of
>    `io.metaloom.loom.rest.service.impl`.
> 5. Tasks 1, 9 and 12 are cheap repo-truth fixes and independent of everything else.

## Progress Assessment

- [ ] 1. [Correct the Cortex README and delete the dead node-module directory](#task-1-correct-the-cortex-readme-and-delete-the-dead-node-module-directory)
- [ ] 2. [Reclaim work from a vanished worker](#task-2-reclaim-work-from-a-vanished-worker)
- [ ] 3. [Per-item pipeline event filter and the missing GraphQL mirror](#task-3-per-item-pipeline-event-filter-and-the-missing-graphql-mirror)
- [ ] 4. [Settle the shared-storage model](#task-4-settle-the-shared-storage-model)
- [ ] 5. [Batch the per-node REST writes](#task-5-batch-the-per-node-rest-writes)
- [ ] 6. [Harden the control channel](#task-6-harden-the-control-channel)
- [ ] 7. [Enforce the task-state retention policy](#task-7-enforce-the-task-state-retention-policy)
- [ ] 8. [Close the metrics gaps outside the run engine](#task-8-close-the-metrics-gaps-outside-the-run-engine)
- [ ] 9. [Stable worker identity without operator help](#task-9-stable-worker-identity-without-operator-help)
- [ ] 10. [Lift the orchestration services out of the REST module's impl package](#task-10-lift-the-orchestration-services-out-of-the-rest-modules-impl-package)
- [ ] 12. [One node-definition-to-adapter binding, not two](#task-12-one-node-definition-to-adapter-binding-not-two)

---

## Task 2: Reclaim work from a vanished worker

**Argumentation Summary:** The graceful drain path is complete; the two ungraceful paths are not.
`ProcessorRegistry#disconnect` (`loom/services/rest/.../service/impl/ProcessorRegistry.java:229`)
does `updateState(OFFLINE)` + `unregister(nodeId)` and nothing else, so a crashed worker's tasks sit
`RUNNING` until `LeaseReaper` sweeps a full lease interval later — even though the registry knows the
node id at that moment and `PipelineNodeTaskDao#countLeasedBy` (`:82`, impl
`PipelineNodeTaskDaoImpl:129`) already keys on it. That method has no production caller: it is
referenced only from `PipelineNodeTaskDaoTest` and `FakeNodeTaskDao`. An interrupted **source**
enumeration is worse — it is never recovered at all, and the gap is named in the code itself.

**Improvement Summary:** Reclaim leases at disconnect instead of waiting out the lease, and give
source enumeration either a lease or a resumable form.

```
(a) Reclaim on disconnect.
    Task 11 landed the machinery: PipelineNodeTaskDao#loadLeasedBy (impl PipelineNodeTaskDaoImpl)
    and LeaseReaper#reclaimWorker, driven from ProcessorPresenceReaper when a worker is evicted
    for silence. What is left is the socket-close path: ProcessorRegistry#evict (the shared
    eviction both paths now go through) does NOT reclaim, so a worker whose socket closes cleanly
    still waits out each lease.
    Decide the accounting before wiring it. The expiry path uses onNodeTaskLost, which is right for
    a worker that vanished; a clean close is closer to a drain, so this path should probably reuse
    PipelineRunEngine#onNodeTaskReturned instead - the attempt refund and its three-per-execution
    cap. That difference is the reason evict() takes no reclaim hook today.
    Do not double-reclaim what LeaseReaper is concurrently sweeping: both paths must be idempotent
    on the same row.

(b) Source tasks still have no reclaim path.
    PipelineTaskHandler (cortex/core/.../impl/loom/PipelineTaskHandler.java) tracks activeSources
    (:80) as "waited for, but never returned"; returnOutstanding (:241) logs and abandons them at
    :250-256 because fabricating a SOURCE_COMPLETE would record a truncated scan as a whole one.
    PipelineRunRecovery's class javadoc says the same for the crash case. Loom dispatches
    SOURCE_TASK one-shot from PipelineEndpointService (:389) with no lease row, so LeaseReaper
    cannot see it. Pick one and write down which:
      - a source-lease equivalent that lets the enumeration be re-dispatched from the start, or
      - a resumable enumeration that reports a partial scan honestly (a cursor the worker returns).
```

**References:** [../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §3.6, §3.7 ·
[../cortex/CORTEX.md](../cortex/CORTEX.md) §7.4 · [../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §3.6.1 (the landed expiry half)
**Test Requirements:** A disconnect test asserting leases held by the departing worker are re-placed
without waiting out the lease, and that a concurrent `LeaseReaper` sweep does not double-count the
attempt; a drain-mid-enumeration test asserting the run still reaches a terminal state. Run
`mvn -pl loom/services/rest,loom/db/jooq test`.
**loom-ui impact:** None on the API surface. The attempt counter that a refund changes is already
rendered — `PipelineNodeTaskRecord.attempt`/`maxAttempts` reach the canvas through
`listPipelineRunItemTasks` in `loom-ui/src/features/pipeline/PipelineEditor.tsx` (debug mode) — so
verify a re-placed task shows attempt 1, not 2, rather than adding UI.

---

## Task 3: Per-item pipeline event filter and the missing GraphQL mirror

**Argumentation Summary:** The payload half is done — `PipelineEventMessage` carries `itemUuid`
(`:54`) and `elementSeq` (`:57`) — and both REST inspection routes shipped. What is left is the live
half and the GraphQL mirror. `PipelineEventEndpoint` extracts only `?pipeline=` and `?run=`
(`handleWebSocket`, `:88`), and `PipelineEventBroadcaster#addSubscriber` has four overloads
(`:65/:75/:90/:105`) taking pipeline, run and userUuid but no item, so a client following one media
item takes the whole run's traffic and filters client-side. `PipelineWiring` (`:134-149`) registers
pipeline / version / run fetchers only — there is no `runItems` or `nodeTasks` fetcher, so the two
REST read routes have no GraphQL equivalent the way the run routes do.

**Improvement Summary:** Add the item filter to the event socket and mirror the run-item / node-task
reads in `PipelineWiring`.

```
1. PipelineEventEndpoint (/api/v1/pipelines/events/ws): read ?item=<uuid> alongside ?pipeline= and
   ?run= at :88 and pass it through. Update the javadoc at :107-108, which enumerates the two
   understood parameters.
2. PipelineEventBroadcaster: add the item argument to addSubscriber (keep the existing overloads
   delegating, as they already do) and match it against the itemUuid the message already carries.
   An absent filter must keep receiving everything - that is the current contract and the UI
   depends on it.
3. PipelineWiring: add runItems and nodeTasks data fetchers over the same services the REST routes
   use (PipelineEndpoint#listRunItems and the nested tasks route), plus the schema types. Keep the
   nesting the REST routes chose: tasks hang off the item, not off the run.
```

**References:** [../loom/RESTAPI.md](../loom/RESTAPI.md) ·
[../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §10.1 ·
[../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §4.3
**Test Requirements:** Broadcaster tests on the pattern of the existing run-filter case in
`PipelineEventBroadcasterTest` (an item subscriber receives only its item; no filter still receives
everything); GraphQL query tests for the two new fetchers. Run
`mvn -pl loom/services/rest,loom/services/graphql test`.
**loom-ui impact:** Do **not** assume the UI will use `?item=`. `loom-ui/src/api/pipelineEvents.ts`
opens a **single module-level socket shared by all subscribers** (`buildWsUrl`, `:186-191`) whose URL
carries only `?token=`; pipeline, processor, node-registry and notification frames are demultiplexed
client-side by their `channel` field. It passes no `?pipeline=` or `?run=` filter today, so a
per-item filter is for the CLI and other single-purpose clients unless the UI first grows a second,
scoped socket. Changing the shared socket is a `loom-ui` decision — coordinate with
[../loom/ui/TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) rather than editing it from a
backend change.

---

## Task 4: Settle the shared-storage model

**Argumentation Summary:** Every worker assumes it can open any path Loom sends, and nothing verifies
it, so a worker that cannot see the file fails every task it is given.
`ProcessorRegistration` (`loom-shared/rest-model/.../processor/message/`) carries `nodeId`, `name`,
`priority`, `host`, `capabilities`, `nodeWhitelist`, `nodeBlacklist` — no path or root field.
`ProcessorCapability` is only `IO`/`CPU`/`GPU`. `ProcessorRegistry.ConnectedProcessor#accepts(String
nodeKind)` (`:643`) filters by kind alone, and `selectProcessor` (`:349`, `:364`) /
`selectProcessorForKinds` (`:379`) — used by `WebSocketNodeDispatcher` and `PipelineEndpointService`
— never consider the path. The implicit model is a universally shared mount; the only escape hatch is
an `s3://` URI materialised per worker by `S3MediaMaterializer`.

**Improvement Summary:** Decide the model and write it down; if it is per-worker, make path
visibility a placement constraint alongside node kind.

```
1. Record the decision in ../cortex/METALOOM_ARCHITECTURE.md: shared mount for all workers, or
   per-worker visible roots. A one-line "shared mount is assumed" is an acceptable outcome - what
   is not acceptable is the current state, where the assumption exists only as a failure mode.
2. If per-worker:
   - advertise visible roots on ProcessorRegistration (a Set<String> of path prefixes) and carry
     them on ConnectedProcessor;
   - extend accepts(...) / select(...) so "can see this file" filters dispatch exactly as "can run
     this kind" does;
   - fail at dispatch with the existing 503-precheck shape in PipelineEndpointService when no
     worker can see a path, naming the path - rather than letting a worker fail the task and burn
     its attempts;
   - leave s3:// locators alone: they are materialised per worker and are visible to everyone.
```

**References:** [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) ·
[../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §3.6, §3.13
**Test Requirements:** Dispatch-selection tests for the no-eligible-worker case (503 naming the path)
and for root-overlap filtering, next to the existing placement tests in `loom/services/rest`.
**loom-ui impact:** Only if the per-worker model is chosen. `loom-ui/src/api/processors.ts`
(`Processor`) and the worker card in `loom-ui/src/features/cortex/CortexView.tsx` (host at `:280`,
capability chips at `:293-307`) would need the new field to explain why a worker was skipped;
otherwise the UI shows an idle ONLINE worker with no reason. File that as a UI item in
[../loom/ui/TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md), do not fold it in here.

---

## Task 5: Batch the per-node REST writes

**Argumentation Summary:** `AbstractMediaNode#recordNodeResult`
(`cortex/common/.../node/AbstractMediaNode.java:139-160`) does a **synchronous single-asset** POST to
`assets/:uuid/node-results` — `client().createAssetNodeResult(...).sync()` at `:156` — once per node
per asset, on top of each node's own typed payload write. Roughly twenty node classes call it, so an
N-node pipeline over M assets costs at least N x M blocking round-trips on the worker's compute
threads. `ResultBatcher` (`cortex/node-runtime`) already batches `NodeTaskResult`s on the control
channel; the REST persistence path has no equivalent.

**Improvement Summary:** Give the Loom client a batching write path for node results so every node
kind benefits without touching twenty node classes.

```
1. Solve it in the client layer (loom-client / cortex/common), not per node: recordNodeResult keeps
   its signature, and the batching happens below it. Model it on ResultBatcher
   (cortex/node-runtime): bounded buffer, size and time trigger, flush on drain/shutdown - Cortex
   already flushes on SIGTERM via CortexImpl.
2. Loom side: a bulk create route (or a batch body on the existing /node-results route) that writes
   N ledger rows in one statement. A partial failure must not lose the rest of the batch; report
   per-entry outcomes.
3. Keep it best-effort exactly as today: a ledger failure is logged and never fails the node
   (AbstractMediaNode:157-159), and it stays a no-op when there is no asset or no Loom client.
4. Sequencing: WORKFLOW_TASKS.md Task 18 adds runUuid/taskUuid and a real origin to
   NodeResultCreateRequest. Land that first - batching a DTO that is about to change means doing
   this twice.

Not in scope:
 - LoomBulkSyncCollector / DefaultLoomBulkSyncCollector / LoomBulkSyncWriterImpl. Still dead
   (the only collect() callers are CortexImplShutdownFlushTest and DefaultLoomBulkSyncCollectorTest;
   CortexImpl only flushes an always-empty buffer at shutdown), but deleting it is
   PIPELINE_TASKS.md Task 10 item 5. Do not open a second decision here.
 - DaoAssetSink's unmapped-output warning. It selects hash/sha512, hash/sha256 and hash/md5 by
   content type and logs the rest, which is correct while every other kind persists its own typed
   payload from inside compute(). hash/chunk is the one genuine omission.
```

**References:** [../features/nodes/NODES.md](../features/nodes/NODES.md) §2 ·
[../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §12 ·
[WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 18 · [PIPELINE_TASKS.md](PIPELINE_TASKS.md) Task 10
**Test Requirements:** A client test that N node results travel in one request; a test that a
partial failure reports per-entry outcomes and does not drop the surviving entries; an existing
node's test (e.g. `MetadataNode`) still seeing its ledger row after the flush. Run
`mvn -pl cortex/common,loom-client -am test` and one node module.
**loom-ui impact:** None — the ledger is read through `asset_node_result` queries, which are
unchanged.

---

## Task 6: Harden the control channel

**Argumentation Summary:** The processor WebSocket is authenticated but leniently, and an
authenticated worker is unconstrained. `WebSocketAuthenticator#resolveStrict` (`:53-55`) reads
`-Dloom.ws.strictAuth`, then `LOOM_WS_STRICT_AUTH`, and otherwise defaults to **false**, so a
token-less connection is accepted with a warning (`:83`). Worse, nothing checks that a worker owns
what it answers for: `ProcessorEndpoint#handleNodeTaskResult` (`:580-599`) and
`#handleNodeTaskResultBatch` (`:544-568`) use the node id only to prove the worker registered, then
hand the result straight to the engine resolved from the body's `runUuid` — any registered worker can
settle any item of any run. The lease owner is already recorded (`pipeline_node_task.leased_by`,
queryable via `countLeasedBy`). Registration is unthrottled: there is no rate limiter anywhere in
`loom/`. Duplicate node ids *are* rejected (`ProcessorEndpoint:326`, close 4409) — `WEBSOCKET.md`
§6.2 still claims otherwise.

**Improvement Summary:** Make strict the default, authorise each worker against what it was actually
given, and rate-limit registration.

```
1. Flip the default in WebSocketAuthenticator#resolveStrict (:53) so an absent token is rejected
   like an invalid one (close 4401). Update the default in ../loom/LOOM.md (:226) and
   ../loom/WEBSOCKET.md (:63, :74) and the env table in
   ../cortex/METALOOM_ARCHITECTURE.md (:172, :333, :398) in the same change. NOTE: the
   METALOOM_CONTEXT.md env row (:625) is part of the re-baseline owned by WORKFLOW_TASKS.md Task
   16 - fix the value there too if you touch it, but do not start a parallel sweep.
   Check NotificationBroadcastTest and MCPAuthStrictTest first: both document the lenient default
   and one of them relies on it.
2. Authorise per worker in ProcessorEndpoint: before engine.onNodeTaskResult, verify the claimed
   (runUuid, itemId, nodeId) was leased to this connection's node id. Reuse the leased_by column
   (add a lookup next to PipelineNodeTaskDao#countLeasedBy). Reject with a protocol error, count
   it on the metrics catalog, and keep it cheap - this is on the hot path for every result and
   every batch entry.
3. Rate-limit REGISTER per remote address and per node id, so a reconnect loop cannot pin the
   event loop. WEBSOCKET.md 6.2 lists this as an open gap.
4. Two further 6.2 gaps: no expiry check on the token of a long-lived connection, and no
   re-authentication or refresh once established. Decide both explicitly.
5. The worker has a SECOND path to the server: cortex/common depends on loom-client-rest and every
   node writes back over REST with the same LOOM_TOKEN (AbstractMediaNode, and the typed payload
   writes). LOOM_WS_STRICT_AUTH does not touch it. Whatever per-worker authorisation is chosen must
   answer for that path too, or state that REST write-back is deliberately only user-authenticated.
```

**References:** [../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §2, §6.2 ·
[../loom/LOOM.md](../loom/LOOM.md) · [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md)
§6 · [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 16 (spec re-baseline)
**Test Requirements:** Connection tests for missing / invalid / valid tokens under both modes; a test
that a worker's result for a task leased to another worker is refused and the engine is not called; a
registration-flood test asserting the limiter engages. Run
`mvn -pl loom/services/rest,loom/core test`.
**loom-ui impact:** Blocking for step 1. `loom-ui/src/api/pipelineEvents.ts` `buildWsUrl` (`:186`)
appends `?token=` **only when a token is present** and otherwise opens the socket unauthenticated;
the shared socket is opened by whichever feature subscribes first. Before flipping the default,
confirm no view subscribes before login (`AuthGate` / `AppShell`) — otherwise every logged-out
session gets a 4401 close loop. `/api/v1/pipelines/events/ws` and `/api/v1/processors/ws` share the
authenticator, so this is one check for both.

---

## Task 7: Enforce the task-state retention policy

**Argumentation Summary:** Decided, not enforced. `pipeline_run_item` and `pipeline_node_task` grow
without bound: the only cleanup in the schema is the `ON DELETE CASCADE` from `pipeline_run`, and
nothing ever deletes a `pipeline_run`. `PipelineRunItemDao` and `PipelineNodeTaskDao` (`loom/db/api`)
declare no delete of any kind; there is no retention configuration key and no reaper.

**Improvement Summary:** Build the batched sweep behind configuration.

```
Policy (already written down, do not re-derive): 7 days of per-item and per-task detail after a run
finishes, 30 days for FAILED / DEAD_LETTER, and the pipeline_run row with its counters forever - so
the granularity after the window is the run row.

Build:
 - bulk delete on PipelineRunItemDao and PipelineNodeTaskDao (loom/db/api + loom/db/jooq), batched
   with a LIMIT so a large backlog cannot lock the table;
 - a reaper scheduled the way LeaseReaper is (loom/services/rest/.../LeaseReaper.java, started from
   RESTService.start(); SandboxReaper in loom/agent/sandbox is the second example) with the same
   per-sweep upper bound so the reaper cannot itself become an outage;
 - configuration for both windows and an off switch, in the LOOM_* env table.
Respect the pipeline_run -> pipeline_run_item cascade, and never touch asset_node_result: it is the
result, not execution bookkeeping (DetectionDaoTest documents that boundary).
```

**References:** [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §10.1a ·
[DATABASE_TASKS.md](DATABASE_TASKS.md) ·
[../features/db/DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) §3.6
**Test Requirements:** DAO tests for both windows and for the batch bound; a reaper test asserting
`pipeline_run` survives while its detail rows go, and that `asset_node_result` is untouched. Run
`./setup-pool.sh` first if a migration is added, then `mvn -pl loom/db/jooq,loom/services/rest test`.
Assert relative to your own fixtures — the pooled test DB is pre-populated.
**loom-ui impact:** None on the run list or the run detail header (both read `pipeline_run`), but the
item drill-down and the node-result strip in `loom-ui/src/features/pipeline/PipelineEditor.tsx` go
empty for a swept run. Decide whether the run detail should say "detail expired" rather than showing
zero items, and if so raise it in [../loom/ui/TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md).

---

## Task 8: Close the metrics gaps outside the run engine

**Argumentation Summary:** `METRICS.md` §5 is an honest gap list, and it is still open. Three Cortex
counters are registered but never called — `cortex_results_sent_total` and
`cortex_results_batches_sent_total` (`CortexMetrics#recordResultsBatchSent`, intended site
`ResultBatcher` in `cortex/node-runtime`, which has no `CortexMetrics` dependency at all) and
`cortex_source_ack_timeouts_total` (`SourceTaskRunner` never calls it). Four more are pure
documentation: `cortex_results_pending` (`ResultBatcher.pendingFor()` is not bound),
`loom_processor_cpu_load` and `loom_processor_memory_used_bytes` (`ProcessorRegistry` stores
`SystemStatusInfo` but binds no gauge, and a per-`node_id` gauge needs an unbind that `bindGauge`
does not have), and `loom_result_store_flush_batch_size`. `loom_auth_failures_total{type}` only ever
emits `ws`. The c3p0 pool behind jOOQ is entirely unmonitored. The operational cost is that the
numbers explaining a stalled fleet are absent exactly when they are needed.

**Improvement Summary:** Record the meters that have a site, delete the ones that do not, and keep
§3/§5 of `METRICS.md` true.

```
1. Cortex: inject CortexMetrics into ResultBatcher and call recordResultsBatchSent on each flush;
   bind cortex_results_pending as a gauge over pendingFor(); call recordSourceAckTimeout from
   SourceTaskRunner. All three have a catalog method already - only the call site is missing.
2. Loom processor gauges: either implement a multi-gauge with an unbind (a Micrometer MultiGauge
   keyed by node_id, rebuilt on the registry's presence change - see NodeRegistryEventPublisher
   and ProcessorRegistry#presenceChanged) or DELETE the two rows from METRICS.md 5.2. Do not leave them documented and
   absent.
3. loom_auth_failures_total{jwt,permission}: call recordAuthFailure from the REST 401/403 path
   (LoomRestException) so the label set matches the documentation, or narrow the documented label
   set to ws.
4. c3p0: decide whether the jOOQ ComboPooledDataSource (JooqModule) gets busy/idle/pending gauges.
   Vert.x loom_pool_* does not cover it, and DB saturation currently looks like an application
   hang.
5. Update ../features/ops/METRICS.md 3 and 5 in the same change.

NOT in scope - PIPELINE_TASKS.md Task 13 owns the five loom/pipeline engine meters
(loom_node_tasks_inflight, _retried_total, _deadlettered_total,
loom_node_circuit_breaker_trips_total, loom_result_store_flush_batch_size) and the EngineMetrics
seam they need. Touch neither here.
```

**References:** [../features/ops/METRICS.md](../features/ops/METRICS.md) §5 ·
[PIPELINE_TASKS.md](PIPELINE_TASKS.md) Task 13
**Test Requirements:** One assertion per newly recorded meter over a `SimpleMeterRegistry` (the
counter moves on the triggering event), plus the existing catalogue tests. **Warning:** `METRICS.md`
§3/§5 are parsed at runtime by `MetricsCatalogScrapeTest` — a table edit can break the Java build.
Run `mvn -pl loom/core test -Dtest=MetricsCatalogScrapeTest` and `mvn -pl cortex/node-runtime test`.
**loom-ui impact:** The monitoring screen reads `GET /api/v1/metrics` through
`loom-ui/src/api/metrics.ts` and draws panels from an explicit mapping in
`loom-ui/src/features/monitoring/metricsPanels.ts` — a new meter appears in the JSON automatically
but is **not** shown until a panel row is added there. Decide per meter whether it earns a panel; if
so, raise it in [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md) rather than editing the dashboard from a backend
change. Test with `cd loom-ui && ./node_modules/.bin/vitest run src/features/monitoring`.

---

## Task 9: Stable worker identity without operator help

**Argumentation Summary:** Mostly solved by making the id mandatory rather than generated — but only
for operators who set it. `CORTEX_NODE_ID` is checked by `CortexMain#hasNodeId` (`:56`, exits
`EXIT_INVALID_CONFIGURATION`) and guarded again in `LoomControlChannel` (`:127`, throws on a blank
id); the id keys `cortex_instance` and `ProcessorRegistry#reconcilePersistedRestriction` restores
admin-set whitelist/blacklist on re-register. A worker started without a persisted id still cannot
get one, and two documents say the opposite of what the code does: `CortexOptions#getNodeId`
(`cortex/api/.../option/CortexOptions.java:245`) is javadoc'd "or null to generate one per process",
and `examples/cortex-python/README.md:108` documents `CORTEX_NODE_ID` as defaulting to "generated" —
so the reference Python worker has no stable identity unless the reader knows better.

**Improvement Summary:** Persist a generated id as the fallback, and fix the two stale contracts.

```
1. Self-persisted fallback: when no id is supplied, generate one and write it under the worker's
   meta path so the next start reuses it. Reconcile with presence eviction (WEBSOCKET.md 3.6.1) so a returning
   worker reclaims its identity instead of duplicating it - and keep the 4409 duplicate-nodeId
   rejection intact.
2. Fix the javadoc at CortexOptions.java:245 - blank now fails hard, that path does not exist.
3. Fix examples/cortex-python/README.md:108 to say the id is mandatory and must be stable, and say
   why (leases and attribution key on it).
```

**References:** [../cortex/CORTEX.md](../cortex/CORTEX.md) ·
[../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §3.2, §3.6
**Test Requirements:** A restart test asserting the same identity re-registers and reclaims its
`cortex_instance` row rather than duplicating it, and a first-start test asserting the generated id
is written and reused. Run `mvn -pl cortex/cli,cortex/core test`.
**loom-ui impact:** None. `loom-ui/src/features/cortex/CortexView.tsx` keys worker cards on `nodeId`
and already handles a worker leaving and returning under the same id.

---

## Task 10: Lift the orchestration services out of the REST module's impl package

**Argumentation Summary:** `loom/services/rest/.../service/impl` holds **79 classes** and mixes two
unrelated layers: HTTP endpoint services (`AssetEndpointService`, `PipelineEndpointService`, …) and
the orchestration machinery that has nothing to do with HTTP — `ProcessorRegistry`, `LeaseReaper`,
`PipelineRunEngineFactory`, `PipelineRunRecovery`, `PipelineRunRegistry`, `PipelineRunTracker`,
`RunStatsAggregator`, `DaoRunStateStore`, `DaoAssetSink`, `PipelineEventBroadcaster`,
`NodeAvailabilityService`, `NodeRunService`, `AdhocNodeResultWriter`. Other modules already reach
into that `impl` package across the module boundary: `loom/services/mcp` imports
`io.metaloom.loom.rest.service.impl.{NodeRunService, PipelineAuthoringService,
NodeAvailabilityService, NodeResultRenderer, WebSocketAuthenticator}`, and `loom/agent/chat` depends
on `loom-service-rest` (`loom/agent/chat/pom.xml:52`) for `AbstractEndpoint`, `EndpointDependencies`,
`LoomRoutingContext` and `AbstractCRUDEndpointService`. Meanwhile `loom-service-api` exists and
contains exactly **one** interface (`AuthenticationService`). The consequences are concrete: the MCP
tool layer and the chat agent cannot be built or tested without the whole HTTP module; an `impl`
package is de facto public API, so any refactor inside it breaks two other modules; and the
orchestrator (worker fleet, leases, run recovery) is only reachable by booting the REST service.
`loom/pipeline` shows the intended discipline — it depends on nothing but the shared models and
enforces it with a `ban-cortex-dependencies` rule (`loom/pipeline/pom.xml:91`).

**Improvement Summary:** Give cross-module consumers a contract to depend on, move the non-HTTP
orchestration out of the endpoint package, and enforce the boundary so it does not refill.

```
Staged; each step compiles and ships on its own.

1. Contracts first, no moves. For each service consumed across a module boundary - NodeRunService,
   PipelineAuthoringService, NodeAvailabilityService, NodeResultRenderer - declare an interface in
   loom/services/api (io.metaloom.loom.service.*) with exactly the methods the consumers use, and
   have the existing class implement it. Switch loom/services/mcp and loom/agent/chat to the
   interface and to a loom-service-api dependency. WebSocketAuthenticator is transport, not a
   service: MCP should get its own binding rather than importing the REST one - decide which.
2. Move the orchestration classes out of ...rest.service.impl into their own package
   (io.metaloom.loom.rest.orchestration, same module) as one mechanical rename: ProcessorRegistry,
   WebSocketNodeDispatcher, LeaseReaper, PipelineRunEngineFactory, PipelineRunRecovery,
   PipelineRunRegistry, PipelineRunTracker, PipelineRunStatusResolver, RunStatsAggregator,
   DaoRunStateStore, DaoAssetSink, PipelineEventBroadcaster, AdHocGraphBuilder, AdhocRuns,
   AdhocNodeResultWriter. Dagger wiring moves with them; no behaviour changes.
3. Add the enforcement, modelled on the ban-cortex-dependencies rule in loom/pipeline/pom.xml:91:
   a build rule (enforcer or an ArchUnit-style test) failing when a module outside
   loom/services/rest imports io.metaloom.loom.rest.service.impl.*. State in
   loom/services/README.md which package is the seam.
4. Only if steps 1-3 land cleanly: consider extracting the orchestration package into its own
   module. Do NOT start there - it drags the DAO and Vert.x wiring with it and is not needed to
   fix the coupling.
```

**References:** [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) §12 (key
classes) · [../loom/LOOM.md](../loom/LOOM.md) · `loom/pipeline/pom.xml` (the existing enforcer
pattern) · [CHAT_TASKS.md](CHAT_TASKS.md) (the agent's own items — this task only changes what it
imports)
**Test Requirements:** The full `loom` and MCP suites green with no behaviour change
(`mvn -pl loom/services/rest,loom/services/mcp,loom/agent/chat -am test`), plus the new boundary rule
failing on a deliberately added cross-module `service.impl` import. Clean-rebuild `loom/core` after
constructor-visible changes or `setup-pool` fails with `NoSuchMethodError`.
**loom-ui impact:** None — no route, DTO or WebSocket frame changes. Do not let this task grow a UI
half; the UI-side layering fix (the shared `src/api/http.ts`) is
[LOOM_UI_TASKS.md](LOOM_UI_TASKS.md) Task 11.

---

## Task 12: One node-definition-to-adapter binding, not two

**Argumentation Summary:** `examples/cortex-custom/.../dagger/PipelineNodeFactoryModule.adapt`
(`:62-100`) is a verbatim copy of `RegistryNodeRegistrar.adapt`
(`cortex/cli/.../dagger/RegistryNodeRegistrar.java:367-415`) — same `id`/`mode`/`blocking`/
`concurrency`/`syncToLoom` parsing, same timeout resolution, same validation messages — and it has
**already drifted**: the copy has no `PipelineConfigurable` branch. That branch is what hands a node
its per-instance `options` from the pipeline definition, so a custom worker built from the example
silently ignores every parameter the pipeline editor saved on a node; the node runs with its
`cortex.yml` defaults and nothing reports a problem. `examples/cortex-custom` is in the root reactor
(`pom.xml:50` → `examples/pom.xml:25`), so this is shipped example code, not a sketch.

**Improvement Summary:** Extract the binding once and have both call sites use it, so the example
cannot drift again.

```
1. Move adapt(...) into a shared, public helper next to the factory it feeds - e.g.
   io.metaloom.cortex.pipeline.loader.NodeAdapters in cortex/core (RegistryNodeFactory's package)
   - taking (FilesystemNode, JsonObject nodeDef, CortexOptions) and returning PipelineNode.
   Behaviour must be exactly RegistryNodeRegistrar's current one, PipelineConfigurable included.
2. RegistryNodeRegistrar delegates to it. Its own registration logic (the @IntoMap node collection
   and the source producers) stays where it is.
3. examples/cortex-custom's PipelineNodeFactoryModule deletes its copy and calls the helper for
   both the built-in kinds and its own hello-world node. The example then demonstrates the
   extension point instead of re-implementing the framework.
4. Check examples/cortex-custom-node for the same copy before finishing.
```

**References:** `cortex/cli/.../dagger/RegistryNodeRegistrar.java` ·
`examples/cortex-custom/.../dagger/PipelineNodeFactoryModule.java` ·
[../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) ·
[../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §9.2
**Test Requirements:** A test in `examples/cortex-custom` mirroring
`cortex/cli/.../PipelineConfigurableTest`: a definition carrying `options` reaches a
`PipelineConfigurable` node built through the example's factory. Existing `NodeRegistrarTest` and
`InstanceTest` stay green. Run `mvn -pl cortex/cli,examples/cortex-custom -am test`. Install
`cortex/processor` before a CLI build or Dagger fails with `<error>` in place of the node module.
**loom-ui impact:** None directly, but this is the far end of a UI feature: the per-node parameter
panel in `loom-ui/src/features/pipeline/PipelineEditor.tsx` (driven by
`loom-ui/src/types/nodeDescriptors.ts`) is what writes those `options` into the definition. Until
this is fixed, a worker built from the example discards them without a warning.

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
| End-of-run sync flush / `flush-sync` work order | Nothing buffers any more; superseded by per-node persistence — [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §12 |
| Node capability whitelist, node affinity, durable item queue with leases | **Built**, not dropped — see [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) |

---
_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (code audit)_
