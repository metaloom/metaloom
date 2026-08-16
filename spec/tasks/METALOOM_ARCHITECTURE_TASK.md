# MetaLoom Architecture — Open Tasks (Variant C)

> Open architecture work items, re-derived from a code audit on 2026-08-16 at `67000540`.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Scope.** Pending work only, for the architecture MetaLoom actually has: **Variant C** — Loom owns
> the pipeline graph (`loom/pipeline`, `PipelineRunEngine`) and dispatches individual node tasks or
> affinity segments to Cortex workers over the processor WebSocket. Ideas belonging to the rejected
> variants are recorded in [Dropped](#dropped) so nobody re-derives them.
>
> **This file absorbed `METALOOM_ARCHITECTURE_V2_TASKS.md` on 2026-08-16.** That file held the
> Variant C scheduling and batching refinements (its Tasks 1-6) plus the standing decision record;
> they are now **Tasks 13-18** and [Appendix A](#appendix-a-standing-decisions) /
> [Appendix B](#appendix-b-why-batching-is-correct) below. Every one of them was re-verified against
> the code and is still open. The predecessor of that file,
> `concept/METALOOM_ARCHITECTURE_V2_PLAN_C.md`, no longer exists either — links to it are stale.
>
> **Context:** [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) (what exists
> and why; §11 is the progress record) · [../cortex/CORTEX.md](../cortex/CORTEX.md) ·
> [../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) (the control protocol) ·
> [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) (run engine, definition
> schema, caching) · [PIPELINE_TASKS.md](PIPELINE_TASKS.md) (pipeline internals, definition schema,
> node registration)
>
> **Completed items are deleted, not ticked.** What used to be the "Landed" table is gone; the
> outcome record is [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) §11 and
> the code. Removed as implemented since the last revision: graceful drain, the run-item and
> node-task inspection routes, `itemUuid`/`elementSeq` on the event payload, `syncToLoom` gating
> `DaoAssetSink`, per-node result persistence, processor WebSocket authentication, Prometheus scrape
> endpoints, the mandatory worker id, definition versioning, lazy filesystem scanning, heartbeat
> expiry (Task 11), and — from the merged file — Phases 1-3 in full (dependency inversion, graph
> model, protocol, worker runtime, durable run state, leases, retries, dead-letter, restart recovery,
> flow control, affinity segments, event aggregation, circuit breaker, result batching), drain-aware
> placement and both run-inspection routes. Task numbers are stable; the gaps are deliberate.
>
> **Do not duplicate work owned elsewhere.** Cross-reference by number instead of restating:
> [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 16** (re-baseline the workflow specs and
> `METALOOM_CONTEXT.md`), **Task 17** (`ctx.failure(...).next()` reports SUCCESS), **Task 18**
> (ledger provenance: constant `origin`, no `runUuid`/`taskUuid`) ·
> [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md) **Task 11** (the shared `src/api/http.ts` seam — the UI-side
> layering fix) · [CHAT_TASKS.md](CHAT_TASKS.md) (all chat/agent-loop items) ·
> [PIPELINE_TASKS.md](PIPELINE_TASKS.md) **Task 10** (dead surfaces, including deleting
> `LoomBulkSyncCollector` and the unbounded broadcaster queue) and **Task 13** (instrumenting the run
> engine — the five `loom/pipeline` meters this file's Task 8 deliberately excludes, and the meters
> Tasks 13-15 below need to steer on). **Beware the collision:** `PIPELINE_TASKS.md` **Task 13** and
> this file's **Task 13** are different items; always qualify which file.
>
> **Ordering / blocking.**
>
> 1. Tasks 2-12 are correctness, security and repo-truth work. Tasks 13-18 are optimisations and
>    refinements: nothing in that range blocks correct operation, and the system runs today without
>    all of them.
> 2. **Task 11** (presence expiry) has landed: a worker that stops heartbeating is evicted and its
>    leases reclaimed through `LeaseReaper.reclaimWorker`. **Task 2** is the remaining half — the
>    socket-close path still waits out each lease — and the query and reclaim entry point it needs
>    (`PipelineNodeTaskDao.loadLeasedBy`, `LeaseReaper.reclaimWorker`) now exist.
> 3. **Task 6** is the security item. Flipping the strict-auth default is gated on confirming the UI
>    never opens the events socket before login (see its loom-ui note).
> 4. **Task 5** touches the same call as [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 18 — land 18
>    first, then batch, or the batching change has to be redone.
> 5. **Task 10** blocks nothing and gets more expensive with every new consumer of
>    `io.metaloom.loom.rest.service.impl` — that package has grown from 79 to **83** classes since
>    the previous audit.
> 6. Tasks 1, 9, 12 and 18 are cheap repo-truth fixes and independent of everything else. **Task 18**
>    costs minutes: do it first so nobody reads the current dispatch javadoc and re-derives a rewrite
>    that already happened.
> 7. **Tasks 13, 14 and 15** all want the same thing first: run-engine meters.
>    [PIPELINE_TASKS.md](PIPELINE_TASKS.md) **Task 13** is that dependency. Building any of the three
>    without it means tuning blind — each was deferred *pending measurement*, not pending effort.
> 8. **Task 16** (dispatch batching) should follow **Task 13**: dispatch width is what determines
>    whether there are ever enough simultaneously-ready tasks for a dispatch batch to be worth a
>    message type.
> 9. **Task 17** is independent and worker-local.

## Progress Assessment

- [ ] 1. [Correct the Cortex README's standalone-processing claim](#task-1-correct-the-cortex-readmes-standalone-processing-claim)
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
- [ ] 13. [Derive dispatch width from live worker load](#task-13-derive-dispatch-width-from-live-worker-load)
- [ ] 14. [Age run priority so a long run cannot starve a later one](#task-14-age-run-priority-so-a-long-run-cannot-starve-a-later-one)
- [ ] 15. [Recover from a straggler without waiting out the ten-minute lease](#task-15-recover-from-a-straggler-without-waiting-out-the-ten-minute-lease)
- [ ] 16. [Batch dispatch, not just results](#task-16-batch-dispatch-not-just-results)
- [ ] 17. [Derive the result batch size from observed task duration](#task-17-derive-the-result-batch-size-from-observed-task-duration)
- [ ] 18. [Correct the stale "Phase 1" contracts in the dispatch path](#task-18-correct-the-stale-phase-1-contracts-in-the-dispatch-path)

---

## Task 1: Correct the Cortex README's standalone-processing claim

**Argumentation Summary:** [Appendix A](#appendix-a-standing-decisions) **Q1** decided that
standalone Cortex pipeline execution does not survive: Cortex holds no pipelines, needs no local
driver, and the legacy `cortex process run --actions` path went with the rest of the CLI. The code
agrees — `cortex/cli` is now `CortexMain.java` plus a `dagger/` package with no command classes at
all, and no `process run` string survives anywhere under `cortex/cli/src/main/java`. `cortex/README.md`
does not: `:5` says the analysis functions "can be performed in offline and online mode" and `:13`
claims "the ability to process media in offline mode means that it can perform bulk media processing
securely at a very large scale". A reader takes that as a supported mode and finds no command
implementing it. The **"delete the dead node-module directory"** half of this task, which the previous
revision listed without ever writing a body for, is resolved: every directory under `cortex/nodes/`
carries Java sources and is declared as a module in `cortex/nodes/pom.xml`, and no directory under
`cortex/` or `examples/` carries an unreferenced `pom.xml`. Do not go looking for it again.

**Improvement Summary:** Make `cortex/README.md` describe a Loom-driven worker rather than a
standalone batch tool.

```
1. cortex/README.md:5 and :13 - remove the "offline mode" framing. Cortex is a worker: it receives
   NODE_TASK / SEGMENT_TASK over the processor WebSocket from a Loom-owned pipeline run. Say that.
2. The "Deployment" section still describes a CLI "scheduling and integration in existing
   workflows ... run via Cron or via a Kubernetes (K8S) Job workload". A worker is a long-lived
   connection, not a cron job; correct it to the container/daemon deployment that helm/ actually
   ships.
3. Check the same claim in README.md at the repository root and under website/content/english/
   before finishing - a grep for "offline mode" found no website hit on 2026-08-16, but the
   website is regenerated from other sources.
4. Do NOT touch spec/cortex/CONFIGURATION.md, spec/concept/CLUSTERING.md or
   spec/concept/ASSET_METADATA_WRITE.md in the same change even though they also match
   "process run"; the spec re-baseline is WORKFLOW_TASKS.md Task 16.
```

**References:** [Appendix A](#appendix-a-standing-decisions) Q1 ·
[../cortex/CORTEX.md](../cortex/CORTEX.md) ·
[../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) ·
[WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 16 (the spec re-baseline — do not start a parallel sweep)
**Test Requirements:** Documentation only; no test. Confirm the build is unaffected
(`mvn -pl cortex/cli -am -q -DskipTests package`) and that no remaining `cortex/README.md` claim
names a command that does not exist.
**loom-ui impact:** None.

---

## Task 2: Reclaim work from a vanished worker

**Argumentation Summary:** The graceful drain path is complete; the two ungraceful paths are not.
`ProcessorRegistry#disconnect` (`loom/services/rest/.../service/impl/ProcessorRegistry.java:248`)
delegates to the shared `evict` (`:308`), which does `updateState(OFFLINE)` + `unregister(nodeId)`
and nothing else, so a crashed worker's tasks sit `RUNNING` until `LeaseReaper` sweeps a full lease
interval later — even though the registry knows the node id at that moment and
`PipelineNodeTaskDao#countLeasedBy` (`:96`, impl `PipelineNodeTaskDaoImpl:141`) already keys on it.
That method has no production caller: it is referenced only from `PipelineNodeTaskDaoTest` and
`FakeNodeTaskDao`. An interrupted **source** enumeration is worse — it is never recovered at all, and
the gap is named in the code itself.

**Improvement Summary:** Reclaim leases at disconnect instead of waiting out the lease, and give
source enumeration either a lease or a resumable form.

```
(a) Reclaim on disconnect.
    Task 11 landed the machinery: PipelineNodeTaskDao#loadLeasedBy (:88, impl
    PipelineNodeTaskDaoImpl:129) and LeaseReaper#reclaimWorker (:148), driven from
    ProcessorPresenceReaper (:183) when a worker is evicted for silence. What is left is the
    socket-close path: ProcessorRegistry#evict (the shared eviction both paths now go through,
    :308) does NOT reclaim, so a worker whose socket closes cleanly still waits out each lease.
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
[../cortex/CORTEX.md](../cortex/CORTEX.md) §7.4 ·
[../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §3.6.1 (the landed expiry half)
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
(`handleWebSocket`), and `PipelineEventBroadcaster#addSubscriber` has four overloads
(`:65/:75/:90/:105`) taking pipeline, run and userUuid but no item, so a client following one media
item takes the whole run's traffic and filters client-side. `PipelineWiring`
(`loom/services/graphql/.../graphql/PipelineWiring.java:134-149`) registers pipeline / version / run
fetchers only — there is no `runItems` or `nodeTasks` fetcher, so the two REST read routes have no
GraphQL equivalent the way the run routes do.

**Improvement Summary:** Add the item filter to the event socket and mirror the run-item / node-task
reads in `PipelineWiring`.

```
1. PipelineEventEndpoint (/api/v1/pipelines/events/ws): read ?item=<uuid> alongside ?pipeline= and
   ?run= in handleWebSocket and pass it through. Update the javadoc at :107-108, which enumerates
   the two understood parameters, and the class javadoc at :38.
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
`ProcessorRegistration` (`loom-shared/rest-model/.../processor/message/`) carries `nodeId` (`:18`),
`name` (`:22`), `priority` (`:26`), `host` (`:30`), `capabilities` (`:34`), `nodeWhitelist` (`:39`),
`nodeBlacklist` (`:44`) — no path or root field. `ProcessorCapability` is only `IO`/`CPU`/`GPU`.
`ProcessorRegistry.ConnectedProcessor#accepts(String nodeKind)` (`:726`) filters by kind alone, and
`selectProcessor` (`:432`, `:447`) / `selectProcessorForKinds` (`:462`) — used by
`WebSocketNodeDispatcher` (`:57`, `:85`) and `PipelineEndpointService` — never consider the path. The
implicit model is a universally shared mount; the only escape hatch is an `s3://` URI materialised
per worker by `S3MediaMaterializer`.

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
(`Processor`) and the worker card in `loom-ui/src/features/cortex/CortexView.tsx` would need the new
field to explain why a worker was skipped; otherwise the UI shows an idle ONLINE worker with no
reason. File that as a UI item in
[../loom/ui/TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md), do not fold it in here.

---

## Task 5: Batch the per-node REST writes

**Argumentation Summary:** `AbstractMediaNode#recordNodeResult`
(`cortex/common/.../node/AbstractMediaNode.java:139-160`) does a **synchronous single-asset** POST to
`assets/:uuid/node-results` — `client().createAssetNodeResult(...).sync()` at `:156` — once per node
per asset, on top of each node's own typed payload write. Roughly twenty node classes call it, so an
N-node pipeline over M assets costs at least N x M blocking round-trips on the worker's compute
threads. `AssetEndpoint` (`:554`) exposes only the single-entry POST. `ResultBatcher`
(`cortex/node-runtime`) already batches `NodeTaskResult`s on the control channel; the REST
persistence path has no equivalent.

**Improvement Summary:** Give the Loom client a batching write path for node results so every node
kind benefits without touching twenty node classes.

```
1. Solve it in the client layer (loom-client / cortex/common), not per node: recordNodeResult keeps
   its signature, and the batching happens below it. Model it on ResultBatcher
   (cortex/node-runtime): bounded buffer, size and time trigger, flush on drain/shutdown - Cortex
   already flushes on SIGTERM via CortexImpl.
2. Loom side: a bulk create route (or a batch body on the existing /node-results route at
   AssetEndpoint:554) that writes N ledger rows in one statement. A partial failure must not lose
   the rest of the batch; report per-entry outcomes.
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
authenticated worker is unconstrained. `WebSocketAuthenticator#resolveStrict` (`:52-61`) reads
`-Dloom.ws.strictAuth`, then `LOOM_WS_STRICT_AUTH`, and otherwise returns **false** at `:60`, so a
token-less connection is accepted with a warning (`:82`). Worse, nothing checks that a worker owns
what it answers for: `ProcessorEndpoint#handleNodeTaskResult` (`:580`) and
`#handleNodeTaskResultBatch` (`:544`) use the node id only to prove the worker registered, then hand
the result straight to the engine resolved from the body's `runUuid` — any registered worker can
settle any item of any run. The lease owner is already recorded (`pipeline_node_task.leased_by`,
queryable via `countLeasedBy` / `loadLeasedBy`). Registration is unthrottled: a grep for
`RateLimit`/`rateLimit` across `loom/` returns nothing. Duplicate node ids *are* rejected
(`ProcessorEndpoint`, close 4409) — `WEBSOCKET.md` §6.2 still claims otherwise.

**Improvement Summary:** Make strict the default, authorise each worker against what it was actually
given, and rate-limit registration.

```
1. Flip the default in WebSocketAuthenticator#resolveStrict (:60) so an absent token is rejected
   like an invalid one (close 4401). Fix the class javadoc at :24-25, which documents the lenient
   default as a migration affordance. Update the default in ../loom/LOOM.md and
   ../loom/WEBSOCKET.md (§2) and the env table in ../cortex/METALOOM_ARCHITECTURE.md in the same
   change. NOTE: the METALOOM_CONTEXT.md env row is part of the re-baseline owned by
   WORKFLOW_TASKS.md Task 16 - fix the value there too if you touch it, but do not start a
   parallel sweep.
   Check NotificationBroadcastTest and MCPAuthStrictTest first: both document the lenient default
   and one of them relies on it.
2. Authorise per worker in ProcessorEndpoint: before engine.onNodeTaskResult, verify the claimed
   (runUuid, itemId, nodeId) was leased to this connection's node id. Reuse the leased_by column
   (add a lookup next to PipelineNodeTaskDao#countLeasedBy at :96). Reject with a protocol error,
   count it on the metrics catalog, and keep it cheap - this is on the hot path for every result
   and every batch entry.
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
[../loom/LOOM.md](../loom/LOOM.md) ·
[../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) §6 ·
[WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 16 (spec re-baseline)
**Test Requirements:** Connection tests for missing / invalid / valid tokens under both modes; a test
that a worker's result for a task leased to another worker is refused and the engine is not called; a
registration-flood test asserting the limiter engages. Run
`mvn -pl loom/services/rest,loom/core test`.
**loom-ui impact:** Blocking for step 1. `loom-ui/src/api/pipelineEvents.ts` `buildWsUrl`
(`:186-191`) appends `?token=` **only when a token is present** and otherwise opens the socket
unauthenticated; the shared socket is opened by whichever feature subscribes first. Before flipping
the default, confirm no view subscribes before login (`AuthGate` / `AppShell` — note the login flow
was reworked in `67000540`, so re-check rather than trusting the previous audit).
`/api/v1/pipelines/events/ws` and `/api/v1/processors/ws` share the authenticator, so this is one
check for both.

---

## Task 7: Enforce the task-state retention policy

**Argumentation Summary:** Decided, not enforced. `pipeline_run_item` and `pipeline_node_task` grow
without bound: the only cleanup in the schema is the `ON DELETE CASCADE` from `pipeline_run`, and
nothing ever deletes a `pipeline_run`. `PipelineRunItemDao` and `PipelineNodeTaskDao` (`loom/db/api`)
declare no delete of any kind; there is no retention configuration key and no reaper anywhere in
`loom/services/rest/.../service/impl`.

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
`cortex_results_batches_sent_total` (`CortexMetrics#recordResultsBatchSent`, `:64`; the only
implementations are `MicrometerCortexMetrics:76`, `NoopCortexMetrics:48` and a test double, and the
intended site `ResultBatcher` in `cortex/node-runtime` has no `CortexMetrics` dependency at all) and
`cortex_source_ack_timeouts_total` (`recordSourceAckTimeout`, `:73`; `SourceTaskRunner` never calls
it). Four more are pure documentation: `cortex_results_pending` (`ResultBatcher.pendingFor()` at
`:167` is not bound), `loom_processor_cpu_load` and `loom_processor_memory_used_bytes`
(`ProcessorRegistry` stores `SystemStatusInfo` but binds no gauge, and a per-`node_id` gauge needs an
unbind that `bindGauge` does not have), and `loom_result_store_flush_batch_size` — all four are still
listed as gaps at `METRICS.md:287-290`. `loom_auth_failures_total{type}` only ever emits `ws`
(`WebSocketAuthenticator`). The c3p0 pool behind jOOQ is entirely unmonitored. The operational cost
is that the numbers explaining a stalled fleet are absent exactly when they are needed.

**Improvement Summary:** Record the meters that have a site, delete the ones that do not, and keep
§3/§5 of `METRICS.md` true.

```
1. Cortex: inject CortexMetrics into ResultBatcher and call recordResultsBatchSent on each flush;
   bind cortex_results_pending as a gauge over pendingFor() (:167); call recordSourceAckTimeout
   from SourceTaskRunner. All three have a catalog method already - only the call site is missing.
2. Loom processor gauges: either implement a multi-gauge with an unbind (a Micrometer MultiGauge
   keyed by node_id, rebuilt on the registry's presence change - see NodeRegistryEventPublisher and
   ProcessorRegistry#presenceChanged) or DELETE the two rows from METRICS.md 5.2 (:288-289) and the
   matching checklist entries at :485-488. Do not leave them documented and absent.
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
for operators who set it. `CORTEX_NODE_ID` is checked by `CortexMain#hasNodeId` (exits
`EXIT_INVALID_CONFIGURATION`) and guarded again in `LoomControlChannel` (throws on a blank id); the id
keys `cortex_instance` and `ProcessorRegistry#reconcilePersistedRestriction` restores admin-set
whitelist/blacklist on re-register. A worker started without a persisted id still cannot get one, and
two documents say the opposite of what the code does: `CortexOptions#getNodeId`
(`cortex/api/.../option/CortexOptions.java:245-247`) is javadoc'd "or null to generate one per
process", and `examples/cortex-python/README.md:108` documents `CORTEX_NODE_ID` as defaulting to
"generated" — so the reference Python worker has no stable identity unless the reader knows better.

**Improvement Summary:** Persist a generated id as the fallback, and fix the two stale contracts.

```
1. Self-persisted fallback: when no id is supplied, generate one and write it under the worker's
   meta path so the next start reuses it. Reconcile with presence eviction (WEBSOCKET.md 3.6.1) so
   a returning worker reclaims its identity instead of duplicating it - and keep the 4409
   duplicate-nodeId rejection intact.
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

**Argumentation Summary:** `loom/services/rest/.../service/impl` holds **83 classes** — up from 79 at
the previous audit, so the problem is actively growing — and mixes two unrelated layers: HTTP
endpoint services (`AssetEndpointService`, `PipelineEndpointService`, …) and the orchestration
machinery that has nothing to do with HTTP — `ProcessorRegistry`, `LeaseReaper`,
`PipelineRunEngineFactory`, `PipelineRunRecovery`, `PipelineRunRegistry`, `PipelineRunTracker`,
`RunStatsAggregator`, `DaoRunStateStore`, `DaoAssetSink`, `PipelineEventBroadcaster`,
`NodeAvailabilityService`, `NodeRunService`, `AdhocNodeResultWriter`, `ProcessorPresenceReaper`.
Other modules already reach into that `impl` package across the module boundary: `loom/services/mcp`
imports `io.metaloom.loom.rest.service.impl.{NodeRunService, PipelineAuthoringService,
NodeAvailabilityService, NodeResultRenderer, WebSocketAuthenticator}`, and `loom/agent/chat` depends
on `loom-service-rest` for `AbstractEndpoint`, `EndpointDependencies`, `LoomRoutingContext` and
`AbstractCRUDEndpointService`. Meanwhile `loom-service-api` exists and still contains exactly **one**
interface (`loom/services/api/.../auth/AuthenticationService.java`). The consequences are concrete:
the MCP tool layer and the chat agent cannot be built or tested without the whole HTTP module; an
`impl` package is de facto public API, so any refactor inside it breaks two other modules; and the
orchestrator (worker fleet, leases, run recovery) is only reachable by booting the REST service.
`loom/pipeline` shows the intended discipline — it depends on nothing but the shared models and
enforces it with a `ban-cortex-dependencies` rule (`loom/pipeline/pom.xml`).

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
   ProcessorPresenceReaper, WebSocketNodeDispatcher, LeaseReaper, PipelineRunEngineFactory,
   PipelineRunRecovery, PipelineRunRegistry, PipelineRunTracker, PipelineRunStatusResolver,
   RunStatsAggregator, DaoRunStateStore, DaoAssetSink, PipelineEventBroadcaster, AdHocGraphBuilder,
   AdhocRuns, AdhocNodeResultWriter. Dagger wiring moves with them; no behaviour changes.
3. Add the enforcement, modelled on the ban-cortex-dependencies rule in loom/pipeline/pom.xml:
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
(`:61-101`) is a verbatim copy of `RegistryNodeRegistrar.adapt`
(`cortex/cli/.../dagger/RegistryNodeRegistrar.java:367`) — same `id`/`mode`/`blocking`/
`concurrency`/`syncToLoom` parsing, same timeout resolution, same validation messages — and it has
**already drifted**: a grep for `PipelineConfigurable` across `examples/cortex-custom/src/main/java`
returns nothing. That branch is what hands a node its per-instance `options` from the pipeline
definition, so a custom worker built from the example silently ignores every parameter the pipeline
editor saved on a node; the node runs with its `cortex.yml` defaults and nothing reports a problem.
`examples/cortex-custom` is in the root reactor, so this is shipped example code, not a sketch.

**Improvement Summary:** Extract the binding once and have both call sites use it, so the example
cannot drift again.

```
1. Move adapt(...) into a shared, public helper next to the factory it feeds - e.g.
   io.metaloom.cortex.pipeline.loader.NodeAdapters in cortex/core (RegistryNodeFactory's package)
   - taking (FilesystemNode, JsonObject nodeDef, CortexOptions) and returning PipelineNode.
   Behaviour must be exactly RegistryNodeRegistrar's current one, PipelineConfigurable included.
   No such helper exists today (verified 2026-08-16).
2. RegistryNodeRegistrar delegates to it. Its own registration logic (the @IntoMap node collection
   and the source producers) stays where it is.
3. examples/cortex-custom's PipelineNodeFactoryModule deletes its copy (:61-101) and calls the
   helper for both the built-in kinds (:55) and its own hello-world node (:57). The example then
   demonstrates the extension point instead of re-implementing the framework.
4. examples/cortex-custom-node does NOT carry a second copy - it is a plain node module
   (HelloWorldNode + Module + Options + Payload + SpecSource). Verified 2026-08-16; do not re-check.
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

## Task 13: Derive dispatch width from live worker load

> Was `METALOOM_ARCHITECTURE_V2_TASKS.md` Task 1. Cite it as **METALOOM_ARCHITECTURE_TASK.md Task
> 13**, not as `PIPELINE_TASKS.md` Task 13 — those are different items.

**Argumentation Summary:** Dispatch width is not merely static, it is **unconfigurable in
production**. `PipelineRunEngine#maxInFlight` (`loom/pipeline/.../engine/PipelineRunEngine.java:112`)
initialises to `DEFAULT_MAX_IN_FLIGHT = 256` (`:85`) and `setMaxInFlight` (`:2348`) has no production
caller at all — every reference is a test (`PipelineRunEngineFlowControlTest`,
`PipelineRunEngineBackpressureTest`, …), and `PipelineRunEngineFactory#assemble` (`:109`) never sets
it. The same holds for the per-kind ceiling: `setMaxInFlightForKind` (`:2197`) is only ever called
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
   (loom/services/rest/.../PipelineRunEngineFactory.java:109), fed by a LOOM_* configuration key
   with 256 as the documented default. Add the key to the env table in
   ../loom/CONFIGURATION.md and ../cortex/METALOOM_ARCHITECTURE.md. This step alone is shippable
   and is what an operator needs today.
2. Then derive it. Expose an aggregate capacity view on ProcessorRegistry - worker count, and the
   mean/max cpuLoad and ioLoad already carried on ConnectedProcessor's SystemStatusInfo - and let
   the engine ask for the current width instead of reading a field: replace the int with a supplier
   the factory installs, so loom/pipeline keeps depending on nothing.
   Keep the accounting where it is: isAtCapacity and the capacityWaiters release path are correct
   and must not be rewritten. Only the number changes.
3. Damp it. Width must move on a timer with hysteresis, not per result, or a fleet under load
   oscillates between stalled and flooded. Never let the derived width fall below 1 - a zero
   ceiling stalls the run permanently, since nothing re-triggers dispatch except a returning
   result.
4. Segments count as one unit against the ceiling, as they do now; a segment is one dispatch and
   one lease regardless of how many nodes it carries.
```

**References:** [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §8 (flow
control) · [../loom/WEBSOCKET.md](../loom/WEBSOCKET.md) §3.13 (`STATUS_UPDATE`) ·
[PIPELINE_TASKS.md](PIPELINE_TASKS.md) **Task 13** (the `loom_node_tasks_inflight` meter that makes
the effect visible) · [Appendix A](#appendix-a-standing-decisions) Q4 (push stays push)
**Test Requirements:** Extend `PipelineRunEngineFlowControlTest` with a width that changes mid-run
(dispatch stops at the lower ceiling, resumes when it rises, and never dispatches below 1); a
`ProcessorRegistry` test for the aggregate capacity view over mixed loads; a factory test asserting
the configured value reaches the engine. Run `mvn -pl loom/pipeline,loom/services/rest test`.
**loom-ui impact:** None on the API surface. The run banner reads aggregated counters
(`RunStatsAggregator`), which are unchanged.

---

## Task 14: Age run priority so a long run cannot starve a later one

> Was `METALOOM_ARCHITECTURE_V2_TASKS.md` Task 2.

**Argumentation Summary:** Priority is carried the whole way to the graph and then dropped.
`PipelineVersion#getPriority` reaches `PipelineGraphParser#parse` (`:144`) and is stored on
`PipelineGraph` (`:32`, getter `:119`), but the only consumer of the value is `PipelineMatcher`
(`:51`), which uses it to decide **which pipeline claims a new asset** — not how work is scheduled.
There is no cross-run scheduler: each run gets its own `PipelineRunEngine`
(`PipelineRunEngineFactory#assemble:109`) registered in `PipelineRunRegistry`, and every engine
dispatches independently into the shared fleet through `WebSocketNodeDispatcher` (`:57`, `:85`) →
`ProcessorRegistry#selectProcessor` (`:432`/`:447`). Placement sorts on the **worker's** declared
priority (`ProcessorRegistry.java:486`), which is a different concept. A grep for
`agingRate`/`admission`/`effectivePriority` across `loom/` returns nothing. The consequence: a 100k-item
run that holds its in-flight ceiling occupies the fleet first-come, first-served, and a small run
started afterwards with `priority: 10` waits behind it. This was deferred because no starvation had
been observed, and that is still true — so the first deliverable is evidence, not a scheduler.

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
[PIPELINE_TASKS.md](PIPELINE_TASKS.md) **Task 13** · `PipelineMatcher` (the other, unrelated use of
the same field)
**Test Requirements:** A registry test with two live engines asserting that a starved low-priority
run is admitted once its aging term overtakes a stream of higher-priority work, and that
`agingRate = 0` reproduces the current first-come order exactly; a test that a paused or
circuit-broken run is never admitted. Run `mvn -pl loom/pipeline,loom/services/rest test`.
**loom-ui impact:** Only if step 2 lands. The run list would then need "waiting for capacity"
distinguished from "running but slow" — raise it in
[../loom/ui/TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) rather than folding UI work in here.

---

## Task 15: Recover from a straggler without waiting out the ten-minute lease

> Was `METALOOM_ARCHITECTURE_V2_TASKS.md` Task 3.

**Argumentation Summary:** Lease expiry is the only recovery from a slow worker, and it is slow by
design. `DaoRunStateStore.DEFAULT_LEASE_MS` is **10 minutes** (`:65`, applied at `:184`) and
`LeaseReaper` sweeps every 60 s (`DEFAULT_INTERVAL_MS`, bounded at `DEFAULT_SWEEP_LIMIT = 500`), so a
worker that is alive but stuck holds an item for up to ~11 minutes before anything happens; a grep for
`speculat` across `loom/` and `cortex/` returns nothing. The expensive precondition, however, is
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
     - never speculate while the run is at its in-flight ceiling (Task 13) - a speculative copy
       must consume real spare capacity, not displace unstarted work;
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

**References:** `LeaseReaper` (duplicate-work invariant) · `DaoRunStateStore:65` ·
[../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §10 ·
[../features/ops/METRICS.md](../features/ops/METRICS.md) ·
[PIPELINE_TASKS.md](PIPELINE_TASKS.md) **Task 13**
**Test Requirements:** An engine test where a task exceeds the threshold, a second copy is dispatched
to a different worker, the first result settles the node and the second is ignored without counting
an attempt; a test that speculation is suppressed at capacity, on a tripped kind, and for segments.
Run `mvn -pl loom/pipeline test` and `mvn -pl loom/services/rest test`.
**loom-ui impact:** The debug canvas shows `attempt`/`maxAttempts` per task
(`loom-ui/src/features/pipeline/PipelineEditor.tsx`, via `listPipelineRunItemTasks`). Verify a
speculated task still shows attempt 1 — if speculation inflates that counter the UI will report a
retry that never happened.

---

## Task 16: Batch dispatch, not just results

> Was `METALOOM_ARCHITECTURE_V2_TASKS.md` Task 4.

**Argumentation Summary:** Batching exists in one direction only. Worker → Loom has
`NODE_TASK_RESULT_BATCH` (`ProcessorMessageType:99`, handled by
`ProcessorEndpoint#handleNodeTaskResultBatch:544`), but Loom → worker has no batch type: the enum
offers `NODE_TASK` and `SEGMENT_TASK` and no `NODE_TASK_BATCH`, and `WebSocketNodeDispatcher#dispatch`
(`:53`) writes exactly one frame per task through `registry.send`. A wide fan-out — one item, ten
independent nodes — is ten frames to (possibly) the same worker. Note that the affinity path already
removes most of this cost for connected nodes, so the remaining case is *unconnected* nodes that
happen to be ready together; that is why this is an optimisation with a measurement gate rather than
a defect.

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

## Task 17: Derive the result batch size from observed task duration

> Was `METALOOM_ARCHITECTURE_V2_TASKS.md` Task 5.

**Argumentation Summary:** `ResultBatcher` (`cortex/node-runtime/.../runtime/ResultBatcher.java`)
takes its size from the pipeline definition and nothing else: `add(...)` (`:76`) receives a
`batchSize` carried on the `NodeTask`, which `PipelineGraphParser` (`:250`) read from the
definition's `resultBatchSize`. One number therefore has to fit both a hash node settling thousands
of items a minute and a whisper node settling one every thirty seconds — for the slow node any batch
above 1 only adds latency, and the fixed `DEFAULT_MAX_HOLD_MS = 500` (`:38`) is what quietly rescues
it. An author has to guess the number per pipeline, and the guess is wrong for any pipeline that
mixes fast and slow kinds.

**Improvement Summary:** Let the batcher raise or lower the effective size per run from the observed
inter-result interval, with the definition value as the ceiling.

```
1. Track the inter-arrival interval per RunBuffer (:46) - the batcher already stamps oldestAt
   (:49, set at :88), so this is one more field. Raise the effective size toward the configured
   value when results arrive faster than the hold window, lower it toward 1 when they do not.
2. The configured resultBatchSize becomes an upper bound, never a target. An explicit value in the
   definition must still cap the adaptive one, or an author loses the ability to bound memory.
3. Do NOT touch flushExpired (:112/:120). The timer is what makes batching CORRECT, not merely
   fast - see [Appendix B](#appendix-b-why-batching-is-correct). An adaptive size that removed the
   hold window would strand a run's tail forever.
4. Keep pendingFor (:167) meaningful; it is the intended gauge site for cortex_results_pending
   (Task 8 of this file).
5. Worker-local only. Nothing about this reaches Loom: the batch frame is unchanged and each entry
   is still assimilated singly.
```

**References:** [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §12 ·
[Appendix B](#appendix-b-why-batching-is-correct) · [Task 8](#task-8-close-the-metrics-gaps-outside-the-run-engine)
(the `cortex_results_*` meters)
**Test Requirements:** Extend `ResultBatcherTest`: a fast arrival pattern grows the effective size up
to but not beyond the configured ceiling; a slow one collapses it to 1 and each result leaves within
the hold window; the existing tail-flush case still passes unchanged. Run
`mvn -pl cortex/node-runtime test`.
**loom-ui impact:** None.

---

## Task 18: Correct the stale "Phase 1" contracts in the dispatch path

> Was `METALOOM_ARCHITECTURE_V2_TASKS.md` Task 6. Cheapest item in this file — do it first.

**Argumentation Summary:** The two javadocs a reader meets first when touching dispatch describe a
system that no longer exists. `WebSocketNodeDispatcher`'s class javadoc (`:24-31`) is headed "Phase 1
limitations, deliberately" and claims worker selection "ignores live load", "cannot yet route by node
kind", and that dispatch has "no lease, no timeout and no retry" — all three are false:
`ProcessorRegistry` uses `cpuLoad`/`ioLoad` as a tie-break (`:486`) and filters on the node-kind
whitelist (`ConnectedProcessor#accepts`, `:726`), and leases, `RetryScheduler` and dead-lettering all
shipped in Phase 2. The class's own inline comments contradict its javadoc a few lines below.
`NodeDispatcher` (`loom/pipeline/.../engine/NodeDispatcher.java:13-15`) states that "a later phase is
expected to invert this to a pull with leases" — a change that was explicitly considered and
**rejected** (Q4). The cost is concrete: an agent reading either file re-derives a rewrite that was
already decided against, or files a task for work already done.

**Improvement Summary:** Make both javadocs describe what the code does, and point at the decision
rather than at a superseded plan.

```
1. WebSocketNodeDispatcher (:24-31): replace the "Phase 1 limitations" paragraph with what is
   actually true - selection is priority-first with cpuLoad/ioLoad as tie-break (:486) and the
   node-kind whitelist as a filter (accepts, :726); dispatch is leased, retried and dead-lettered
   by the engine; the remaining limitation is that ProcessorCapability is still CPU for every kind
   (dispatch:57 and :85 both pass ProcessorCapability.CPU unconditionally, which the inline comment
   already says correctly, and which PIPELINE_TASKS.md Task 10 owns).
2. NodeDispatcher (:13-15): push is the decision, not a phase. Say so and cite Q4 - push plus
   leases plus per-worker caps gives the same backpressure without a second protocol rewrite.
3. While in there: the class javadoc's promise that returning null makes the engine "fail the node
   rather than wait forever" is correct - keep it, it is the contract Task 13's supplier must not
   break.
```

**References:** `loom/services/rest/.../WebSocketNodeDispatcher.java` ·
`loom/pipeline/.../engine/NodeDispatcher.java` · [Appendix A](#appendix-a-standing-decisions) Q4 ·
[PIPELINE_TASKS.md](PIPELINE_TASKS.md) **Task 10** (the capability gap that *is* still real)
**Test Requirements:** No behaviour change, so no new test — the existing
`mvn -pl loom/pipeline,loom/services/rest test` must stay green. This is a documentation-only change
inside Java sources.
**loom-ui impact:** None.

---

## Appendix A: Standing decisions

Recorded 2026-07-18 (Q1/Q4/Q5) and later; kept because the reasoning is not recoverable from the
code, and because Tasks 13-16 are constrained by it. Do not re-litigate these without a written
reason.

| # | Question | Decision | Consequence |
|---|---|---|---|
| Q1 | Must standalone Cortex pipeline execution survive? | **No — Loom-only is acceptable** | Cortex holds no pipelines and needs no local driver. The legacy `cortex process run --actions` path went with the rest of the CLI. `cortex/README.md` still claims otherwise — [Task 1](#task-1-correct-the-cortex-readmes-standalone-processing-claim) |
| Q4 | Push or pull dispatch? | **Push** | Loom sends `NODE_TASK`/`SEGMENT_TASK` when work becomes ready. Revisited when leases arrived and **kept**: push + leases + per-worker caps gives the same backpressure without a second protocol rewrite. Binds [Task 16](#task-16-batch-dispatch-not-just-results) and [Task 18](#task-18-correct-the-stale-phase-1-contracts-in-the-dispatch-path) |
| Q5 | Version the definition format? | **Yes** | Delivered: `PipelineGraphParser.CURRENT_DEFINITION_VERSION` (= 1); absent means 1, higher is refused by name. An additive frame ([Task 16](#task-16-batch-dispatch-not-just-results)) does not bump it |
| — | Where do intermediate results live? | In the node implementation's own cache, reached through the segment-scoped `ArtifactCache` | Shipped 2026-08-02 — `NodeInputs.artifacts()`, owned by the segment execution, opt-in per node. Affinity grouping alone saves only round trips; the *scope* is what removes the re-read. [PIPELINE.md](../features/pipeline/PIPELINE.md) §7.4 |
| — | Is a separate segmented-dispatch variant (D) needed? | **No** | Segments are what was built; single-node dispatch is the degenerate case |

## Appendix B: Why batching is correct

These two notes are the reason result batching is correct rather than merely fast.
[Task 16](#task-16-batch-dispatch-not-just-results) and
[Task 17](#task-17-derive-the-result-batch-size-from-observed-task-duration) both depend on them.

- **The size trigger alone is not sufficient.** A run's tail never reaches it — a 500-item run
  batched at 200 ends with 100 results in the buffer — so `ResultBatcher#flushExpired` (`:112`/`:120`)
  sends partial batches after a short hold. The size trigger is the optimisation; **the timer is what
  makes batching correct.**
- **Batching is a transport concern only.** Each entry is assimilated through the same single-result
  path, so retries, dead-lettering and downstream unblocking are unchanged, and there is no
  batch-level verdict that could let one bad result spoil the others. Any dispatch-side batch
  ([Task 16](#task-16-batch-dispatch-not-just-results)) must hold to the same rule.

## Test setup

The standard Loom setup, repeated so an agent picking up a task here does not have to hunt for it.

```bash
./setup-pool.sh                       # required before any DB test, and after every Flyway change
mvn test -pl loom/pipeline            # engine, graph, segmenter, circuit breaker
mvn test -pl loom/services/rest       # registry, leases, dispatch, broadcaster, endpoints
mvn test -pl cortex/node-runtime      # node/source/segment runners, ResultBatcher
mvn test -pl integration-test         # PipelineAffinitySegmentIntegrationTest and friends
mvn test -pl cortex/nodes/hash/core -Dtest=SegmentDispatchBenchmark -Dbenchmark=true
```

The benchmark is disabled by default: it needs `/opt/metaloom/loom-testdata`. It measures
**worker-side** segment cost only — no socket, no Loom — so it does not answer
[Task 16](#task-16-batch-dispatch-not-just-results)'s question.

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
| Delete the dead node-module directory (old Task 1, second half) | **Does not exist.** Verified 2026-08-16: every `cortex/nodes/*` directory carries Java sources and is declared in `cortex/nodes/pom.xml`, and no `pom.xml` under `cortex/` or `examples/` is unreferenced by its parent |
| Node capability whitelist, node affinity, durable item queue with leases | **Built**, not dropped — see [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) |

---
_Git HEAD revision: `67000540`_
_Last updated: 2026-08-16 (merged `METALOOM_ARCHITECTURE_V2_TASKS.md` in as Tasks 13-18; all 17 tasks re-verified against the code)_
