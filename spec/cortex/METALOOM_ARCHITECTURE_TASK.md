# MetaLoom Architecture — Open Tasks (Variant C)

**Scope.** Pending work only, for the architecture MetaLoom actually has:
**Variant C** — Loom owns the pipeline graph and dispatches individual node tasks
(or affinity segments) to Cortex workers.

Ideas belonging to the rejected variants — the hierarchical worker tree,
multi-site federation, Cortex as a coordinator, cross-instance delegation,
recursive worker protocols — have been **dropped**, not deferred. See
[Dropped](#dropped).

For what exists and why see [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md);
for the phase-by-phase record
[METALOOM_ARCHITECTURE_V2_PLAN_C.md](METALOOM_ARCHITECTURE_V2_PLAN_C.md).
Pipeline internals, definition schema and node registration live in
[../features/pipeline/PIPELINE_TASKS.md](../features/pipeline/PIPELINE_TASKS.md);
deferred design work in [../tasks/TASKS.md](../tasks/TASKS.md).

---

## Progress Assessment

- [ ] 1. [Correct the Cortex README](#task-correct-the-cortex-readme)
- [ ] 2. [Reclaim work from a vanished worker](#task-reclaim-work-from-a-vanished-worker)
- [ ] 3. [Node-task inspection API](#task-node-task-inspection-api)
- [ ] 4. [Settle the shared-storage model](#task-settle-the-shared-storage-model)
- [ ] 5. [Finish or delete the bulk-sync path, and batch the per-node writes](#task-finish-or-delete-the-bulk-sync-path-and-batch-the-per-node-writes)
- [ ] 6. [Harden the control channel](#task-harden-the-control-channel)
- [ ] 7. [Enforce the task-state retention policy](#task-enforce-the-task-state-retention-policy)
- [ ] 8. [Close the metrics gaps](#task-close-the-metrics-gaps)
- [ ] 9. [Stable worker identity without operator help](#task-stable-worker-identity-without-operator-help)
- [ ] 10. [DAO test pool collisions](#task-dao-test-pool-collisions)

Completed work is recorded in [Landed](#landed) as one-line outcomes.

---

## Task: Correct the Cortex README

**Argumentation Summary:** Decision Q1 removed standalone pipeline execution —
Cortex answers `NODE_TASK`, `SEGMENT_TASK` and `SOURCE_TASK` and holds no
pipelines. The website and root README were corrected; `cortex/README.md` was not
and still sells the opposite product.
**Improvement Summary:** One file. Reframe Cortex as a long-running worker and
state plainly what offline use remains.

```
Fix /home/defaultuser/workspaces/metaloom/metaloom/cortex/README.md. Four stale claims:
  - line 5: functions "can be performed in offline and online mode"
  - line 13: "process media in offline mode ... bulk media processing at a very large scale"
  - line 23 "Online Mode": frames Loom as an optional metadata sink rather than the owner
    of the graph
  - lines 31-32 "Deployment": "can be run via Cron or via a Kubernetes (K8S) Job workload"
Match the framing already used in
website/content/english/docs/cortex/_index.adoc ("long-running worker daemon, not a
one-shot CLI batch tool"; "the pipeline graph lives on Loom, not on Cortex") and
website/content/english/docs/cortex/containers/index.adoc ("deploy it as a Deployment").
The only offline path that survives is the legacy `cortex process run --actions` CLI;
name it as legacy rather than as the headline mode.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md),
[CORTEX.md](CORTEX.md)
**Test Requirements:** Docs only — no tests. Flagged as blocking the Phase 1 ship.

---

## Task: Reclaim work from a vanished worker

**Argumentation Summary:** The graceful drain path is complete, but the two
ungraceful paths are not. A crashed worker's tasks sit `RUNNING` until
`LeaseReaper` notices (a full lease interval), and an interrupted source
enumeration is never recovered at all — the run waits for a `SOURCE_COMPLETE`
that never arrives, or is silently truncated by recovery.
**Improvement Summary:** Reclaim leases on disconnect; give source enumeration a
lease or make it resumable.

```
(a) Reclaim on disconnect.
    ProcessorRegistry#disconnect (loom/services/rest/.../service/impl/ProcessorRegistry.java,
    ~line 211) currently only does updateState(OFFLINE) + unregister(nodeId). It knows the
    nodeId; PipelineNodeTaskDao#countLeasedBy already keys on it but is dead in production
    (referenced only from PipelineNodeTaskDaoTest and FakeNodeTaskDao). Add a
    reclaim-by-worker query and re-place those tasks at once, reusing the accounting
    PipelineRunEngine#onNodeTaskReturned already has — including the attempt refund and its
    three-per-execution cap. Do not double-reclaim tasks LeaseReaper is already sweeping.

(b) Source tasks have no reclaim path — the gap is named in the code itself.
    PipelineTaskHandler (cortex/core/.../impl/loom/PipelineTaskHandler.java) tracks
    `activeSources` as "waited for, but never returned"; returnOutstanding() logs and
    abandons them because fabricating a SOURCE_COMPLETE would record a truncated scan as a
    whole one. PipelineRunRecovery says the same for the crash case. Loom dispatches
    SOURCE_TASK one-shot from PipelineEndpointService (~line 437) with no lease row, so
    LeaseReaper cannot see it. Pick one: a source-lease equivalent that lets the run be
    re-dispatched, or a resumable enumeration that can report a partial scan honestly.
```

**References:** [../loom/WEBSOCKET.md §3.8.1, §3.10](../loom/WEBSOCKET.md),
[CORTEX.md §7.4](CORTEX.md)
**Test Requirements:** A disconnect test asserting leases held by the departing
worker are re-placed without waiting out the lease; a drain-mid-enumeration test
asserting the run still reaches a terminal state.

---

## Task: Node-task inspection API

**Argumentation Summary:** Run *items* are now exposed, but the per-node task
detail — attempt history, `leased_by`, failure messages — is still recorded and
unreachable. `PipelineNodeTaskDao#loadPageByRun` is paged and filterable and was
plainly built for an endpoint that nobody wrote.
**Improvement Summary:** Add the tasks route and the per-item stream; the items
route already exists.

```
Existing: GET /api/v1/pipelines/:uuid/runs/:runUuid/items
  — PipelineEndpoint#listRunItems, PipelineEndpointService#listRunItems, guarded by
    READ_PIPELINE_RUN, models PipelineRunItemListResponse / PipelineRunItemRecord,
    covered by PipelineRunItemEndpointTest. Note the route is nested under the pipeline
    uuid, not the flat /pipelines/runs/:uuid/items this file used to propose.

Missing:
  - GET /api/v1/pipelines/:uuid/runs/:runUuid/tasks — per-node detail including leased_by
    and attempt history. Back it with PipelineNodeTaskDao#loadPageByRun
    (loom/db/api/.../pipeline/PipelineNodeTaskDao.java:28); guard with READ_PIPELINE_RUN.
    There is no PipelineNodeTask* class in loom-shared/rest-model yet — add the record and
    list response alongside the PipelineRunItem* pair, and mirror the route in GraphQL
    (PipelineWiring) as the run routes are.
  - Per-item opt-in event stream. PipelineEventEndpoint (/api/v1/pipelines/events/ws)
    filters only by ?pipeline= and ?run=. PipelineEventMessage carries mediaPath but no
    item uuid, and PipelineEventType has no ITEM_* members, so a per-item subscription
    needs both a filter and a payload change.
```

**References:** [../loom/RESTAPI.md](../loom/RESTAPI.md),
[../features/pipeline/PIPELINE.md §10.1](../features/pipeline/PIPELINE.md),
[../loom/WEBSOCKET.md §4.3](../loom/WEBSOCKET.md)
**Test Requirements:** Endpoint + permission tests on the pattern of
`PipelineRunItemEndpointTest` (list, paged, filtered, unknown run 404,
cross-pipeline 404, forbidden without `READ_PIPELINE_RUN`).

---

## Task: Settle the shared-storage model

**Argumentation Summary:** Every worker assumes it can open any path Loom sends.
Nothing verifies that, so a worker that cannot see the file fails every task it is
given. Placement filters on node kind and capability only.
**Improvement Summary:** Decide the model; if per-worker, make path visibility a
placement constraint alongside node kind.

```
Today: ProcessorRegistration (loom-shared/rest-model/.../processor/message/) carries
nodeId, name, priority, host, capabilities, nodeWhitelist, nodeBlacklist — no path or
root field. ProcessorCapability is only IO/CPU/GPU. ProcessorRegistry.ConnectedProcessor
#accepts(String nodeKind) filters by kind alone, and selectProcessor / selectProcessorForKinds
(used by WebSocketNodeDispatcher and PipelineEndpointService) never consider the path.
The implicit model is a universally shared mount; the only escape hatch is an s3:// URI
materialised per worker.

Decide: shared mount for all workers, or per-worker visible roots?
If per-worker: advertise visible roots on ProcessorRegistration, carry them on
ConnectedProcessor, and extend the selection predicates so "can see this file" filters
dispatch exactly as "can run this kind" does. Fail at dispatch when no worker can see a
path, rather than letting the worker fail the task.
```

**References:** [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md),
[../loom/WEBSOCKET.md §3.6, §3.13](../loom/WEBSOCKET.md)
**Test Requirements:** Dispatch-selection tests for the no-eligible-worker case and
for root-overlap filtering.

---

## Task: Finish or delete the bulk-sync path, and batch the per-node writes

**Argumentation Summary:** `syncToLoom` is now wired end to end, so the original
"most node output never reaches an asset" framing is obsolete. What remains is one
dead write path that must not stay half-wired, and the per-call cost that
`LoomNode`'s batching used to pay down.
**Improvement Summary:** Delete or complete `LoomBulkSyncCollector`; batch the
per-node REST writes below the nodes.

```
(a) The bulk-sync path is dead code. LoomBulkSyncCollector (cortex/pipeline-api),
    DefaultLoomBulkSyncCollector (cortex/pipeline-common) and LoomBulkSyncWriterImpl
    (cortex/core/.../impl/loom/) are all wired in CortexBindModule, but the only collect()
    call in the repo is in CortexImplShutdownFlushTest — CortexImpl only ever calls
    flush() on shutdown, over an always-empty buffer. Likewise PipelineNode#syncToLoom on
    the cortex side (AbstractPipelineNode, set from JSON by RegistryNodeRegistrar) has no
    production reader. Delete them as LoomNode was deleted, or feed them; do not leave a
    third half-wired write path.

(b) Batch the per-node REST writes. AbstractMediaNode#recordNodeResult
    (cortex/common/.../node/AbstractMediaNode.java:139) does a synchronous single-asset
    POST to assets/:uuid/node-results per node per asset, plus each node's own typed
    payload write — an N-node pipeline over M assets is >= N x M round-trips. ResultBatcher
    (cortex/node-runtime) already batches NodeTaskResults on the control channel; the REST
    persistence has no equivalent. Solve it in the client layer so every node kind benefits.

Not in scope: DaoAssetSink's unmapped-output warning. It maps hash/sha512, hash/sha256 and
hash/md5 and logs everything else; that is correct under the current model, since every
other kind persists its own typed payload from inside compute(). hash/chunk is the one
genuine omission (the cortex-side writer handles HASH_CHUNK, DaoAssetSink does not).
```

**References:** [../features/pipeline-nodes/NODES.md §2](../features/pipeline-nodes/NODES.md),
[../features/pipeline/PIPELINE.md §12](../features/pipeline/PIPELINE.md)
**Test Requirements:** If deleted, assert nothing references the collector. If
batched, assert one request covers N node results and that a partial failure does
not lose the rest.

---

## Task: Harden the control channel

**Argumentation Summary:** The processor WebSocket is authenticated, but leniently:
a *missing* token is accepted with a warning unless `LOOM_WS_STRICT_AUTH=true`. An
authenticated worker is also unconstrained — it can answer for a run it was never
given — and registration is unthrottled.
**Improvement Summary:** Make strict the default, authorise per worker, rate-limit
registration.

```
Existing: ProcessorEndpoint registers /api/v1/processors/ws outside secure(...) and gates
handleWebSocket on WebSocketAuthenticator#authenticate, which reads ?token=<jwt> and closes
4401 on an invalid one. LoomControlChannel#resolveToken sends LoomClientOptions.getToken()
or env LOOM_TOKEN. Duplicate nodeIds are rejected with close 4409.

Remaining:
  - Flip LOOM_WS_STRICT_AUTH to default true (WebSocketAuthenticator#resolveStrict, ~line
    61) so an absent token is rejected like an invalid one. Update the default in
    ../loom/LOOM.md and ../loom/WEBSOCKET.md §2.2 in the same change.
  - Authorise per worker: a worker should not be able to answer for a run it was never
    dispatched. Check the claimed run/task against what ProcessorRegistry handed it.
  - Rate-limit registration. WEBSOCKET.md §6.2 already lists this and the lenient default as
    open gaps; note that its neighbouring claim that nodeId uniqueness is unenforced is
    itself stale — ProcessorEndpoint#handleRegister closes duplicates with 4409.
  - Two further gaps named in §6.2 and unaddressed: no token expiry check on a long-lived
    connection, and no re-authentication/refresh once established.
```

**References:** [../loom/WEBSOCKET.md §2, §6.2](../loom/WEBSOCKET.md),
[../loom/LOOM.md](../loom/LOOM.md),
[METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md)
**Test Requirements:** Connection tests for missing/invalid/valid tokens under both
modes; a test that a worker's result for an unowned task is refused.

---

## Task: Enforce the task-state retention policy

**Argumentation Summary:** Decided, not enforced. `pipeline_run_item` and
`pipeline_node_task` grow without bound — the only cleanup in the schema is the
`ON DELETE CASCADE` from `pipeline_run`, and nothing ever deletes a `pipeline_run`.
**Improvement Summary:** Build the batched sweep behind configuration.

```
Policy (already written down, do not re-derive): 7 days of per-item and per-task detail
after a run finishes, 30 days for FAILED / DEAD_LETTER, and the pipeline_run row with its
counters forever — so the granularity afterwards is the run row.

Missing entirely: PipelineRunItemDao and PipelineNodeTaskDao declare no bulk delete; there
is no retention config key; there is no reaper. Build:
  - DAO bulk delete, batched with a LIMIT so a large backlog does not lock the table
  - a reaper on the SandboxReaper pattern (loom/agent/sandbox/.../SandboxReaper.java) —
    LeaseReaper (loom/services/rest/.../LeaseReaper.java, started from RESTService.start())
    is the closer example for scheduling inside Loom
  - configuration to tune the windows and to turn it off
Respect the pipeline_run -> pipeline_run_item cascade, and never touch asset_node_result.
```

**References:** [../features/pipeline/PIPELINE.md §10.1a](../features/pipeline/PIPELINE.md),
[../features/db/DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md),
[../features/DB_SCHEMA_FEEDBACK.md §3.6](../features/DB_SCHEMA_FEEDBACK.md)
**Test Requirements:** DAO tests for the windows and the batch bound; a reaper test
asserting `pipeline_run` survives while its detail rows go.

---

## Task: Close the metrics gaps

**Argumentation Summary:** Prometheus is wired on both sides and several meters
exist, but the four signals that actually describe fleet health are among the
missing ones — and `METRICS.md` claims meters that have no call site, which is
worse than documenting nothing.
**Improvement Summary:** Add the missing meters; correct the spec's over-claims.

```
Existing: MonitoringService exposes GET /metrics on LOOM_SERVER_MON_PORT (8989); cortex
MetricsEndpoint on 8093. Catalogue LoomMetrics / MicrometerLoomMetrics, CortexMetrics /
MicrometerCortexMetrics. Already emitted: loom_node_results_received{kind,state} (per-kind
failure rate is derivable), loom_leases_reclaimed_total, loom_orphans_deadlettered_total,
loom_tasks_returned{node}, loom_processors_connected, loom_pipeline_event_subscribers,
loom_pipeline_run_duration.

Missing:
  - Dispatch latency. Dispatch is counter-only (recordNodeTaskDispatched /
    recordNodeTaskDispatchFailed in WebSocketNodeDispatcher). Add a timer dispatch->result.
  - Queue depth / in-flight vs ceiling per run. METRICS.md documents
    loom_node_tasks_inflight but no bindGauge exists.
  - Circuit breaker state per kind. NodeKindCircuitBreaker
    (loom/pipeline/.../engine/NodeKindCircuitBreaker.java) contains zero metrics
    references; METRICS.md documents loom_node_circuit_breaker_trips_total with no call
    site.
  - Also documented-but-absent: loom_node_tasks_retried_total,
    loom_node_tasks_deadlettered_total, loom_processors_by_state, loom_processor_cpu_load,
    loom_processor_memory_used_bytes, loom_result_store_flush_batch_size. Either implement
    or remove; METRICS.md currently self-describes as "IMPLEMENTED".
```

**References:** [../features/ops/METRICS.md](../features/ops/METRICS.md),
[../features/ops/MONITORING.md](../features/ops/MONITORING.md)
**Test Requirements:** Assert each new meter is registered and moves; a scrape test
that every name in `METRICS.md` is present.

---

## Task: Stable worker identity without operator help

**Argumentation Summary:** Mostly solved by making the id mandatory rather than
generated — but only for operators who set it. A worker started without a
persisted id still cannot get one, and the API doc says the opposite of what the
code does.
**Improvement Summary:** Persist a generated id as the fallback; fix the stale
contract.

```
Existing: --node-id / CORTEX_NODE_ID (CortexCLI, EnvDefaultProvider) is mandatory at server
startup — AbstractLoomWorkerCommand#requireNodeId, called from ServerCommand, with a second
guard in LoomControlChannel that throws on a blank id. The id keys cortex_instance, and
ProcessorRegistry#reconcilePersistedRestriction restores admin-set whitelist/blacklist on
re-register, so leases and attribution survive a restart when the same id is supplied.

Remaining:
  - No self-persisted fallback: nothing generates and writes an id (e.g. under --meta-path)
    when the operator supplies none. Reconcile whatever is chosen with heartbeat eviction so
    a returning worker reclaims its identity rather than duplicating it.
  - CortexOptions#getNodeId javadoc still says "or null to generate one per process". That
    path no longer exists — blank now fails hard. Fix the javadoc.
  - examples/cortex-python/README.md documents CORTEX_NODE_ID as defaulting to "generated",
    so the reference Python worker has no stable identity unless set. Align it.
```

**References:** [CORTEX.md](CORTEX.md),
[../loom/WEBSOCKET.md §3.2, §3.6](../loom/WEBSOCKET.md)
**Test Requirements:** Restart test asserting the same identity re-registers and
reclaims rather than duplicating.

---

## Task: DAO test pool collisions

**Argumentation Summary:** Tests in `loom/db/jooq` were failing with
`duplicate key … _pkey` because fixtures seeded by `PoolSetupRunner` collided with
UUIDs the tests insert. Not re-verified in this pass (no test run), and the module
has been reworked since — `PipelineFixtures` now centralises the
pipeline → version → run scaffolding.
**Improvement Summary:** Re-verify, then give fixtures and tests disjoint UUIDs.

```
Re-run first — several of these may already be green:
  mvn test -pl loom/db/jooq
Previously affected: PipelineDaoTest, BlacklistDaoTest, AssetLocationDaoTest, TokenDaoTest,
PipelineVersionDaoTest (all under loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/),
plus PoolingTest and RxDaoTest (under .../jooq/user/).
Fix by giving fixtures and test elements disjoint UUIDs, or by not assuming empty tables.

The companion documentation item is DONE: .claude/CLAUDE.md now states, prominently, that
./setup-pool.sh must be re-run after every Flyway migration change.
```

**References:** [../features/db/DATABASE.md](../features/db/DATABASE.md)
**Test Requirements:** `mvn test -pl loom/db/jooq` green from a freshly seeded pool.

---

## Landed

One-line outcome records. Detail lives in the code and in the linked specs.

| Task | Where it landed |
|---|---|
| Graceful shutdown with drain (announce, finish/return in-flight, immediate reclaim) | `LoomControlChannel#drain` from `CortexBootstrapInitializer#deinit` via a JVM shutdown hook; `PipelineTaskHandler` refuses late dispatches and sends `TASK_RETURNED`; `PipelineRunEngine#onNodeTaskReturned` re-places and refunds the attempt, capped at three per execution — [../loom/WEBSOCKET.md §3.8.1](../loom/WEBSOCKET.md), [CORTEX.md §7.4](CORTEX.md) |
| Run *item* inspection endpoint | `GET /api/v1/pipelines/:uuid/runs/:runUuid/items` — `PipelineEndpoint#listRunItems`, `READ_PIPELINE_RUN`, `PipelineRunItemListResponse`, `PipelineRunItemEndpointTest`; also in `LoomHttpClientImpl`, the CLI `run items` command and `loom-ui/src/api/pipelines.ts` |
| `syncToLoom` finally means something | `PipelineRunEngine#syncToLoom` gates `assetSink.persist`; `DaoAssetSink` selects hashes by *content type* (`hash/sha512|sha256|md5`), not port name, and is installed by `PipelineEndpointService` |
| Per-node result persistence replaced `LoomNode` | Each result-producing node writes its typed payload plus an `asset_node_result` ledger row from inside `compute()`; `LoomNode` deleted — [../features/pipeline-nodes/NODES.md §2](../features/pipeline-nodes/NODES.md) |
| Processor WebSocket authentication | `WebSocketAuthenticator` validates `?token=<jwt>`, closes 4401; strictness via `LOOM_WS_STRICT_AUTH` (still lenient by default — see task 6) |
| Prometheus scrape endpoints | Loom `GET /metrics` on `LOOM_SERVER_MON_PORT` (8989) via `MonitoringService`; Cortex `MetricsEndpoint` on 8093; `LoomMetrics` / `CortexMetrics` catalogues |
| Worker id made mandatory | `--node-id` / `CORTEX_NODE_ID` required by `AbstractLoomWorkerCommand#requireNodeId`; keys `cortex_instance`, restrictions reconciled on re-register |
| Cortex `PIPELINE_EVENT` passthrough filtered | Cortex no longer subscribes its tracking bus to the control channel; `ProcessorEndpoint` drops worker-sent `PIPELINE_EVENT` and logs once per processor — [../loom/WEBSOCKET.md §4.6b](../loom/WEBSOCKET.md) |
| `FilesystemMediaScanner` made lazy | `stream(...)` overloads walk on demand and de-duplicate as they go; `FilesystemSourceNode` consumes via `Flowable.fromStream` so backpressure reaches the filesystem. The differential (root) path stays eager by design |
| Pipeline definition format versioned | Top-level `version` integer, `PipelineGraphParser.CURRENT_DEFINITION_VERSION`; absent means 1, newer refused by name, malformed refused; `stampVersion` on REST create/update and in `DemoDatabaseInitializer` — [../features/pipeline/PIPELINE.md §9.2](../features/pipeline/PIPELINE.md) |
| Website + root README corrected to worker framing | `website/content/english/docs/cortex/_index.adoc` and `.../containers/index.adoc`; `cortex/README.md` still stale — see task 1 |
| Test fixes | `NodeDescriptorEndpointTest` (stale `inputs`/`outputs` field names vs typed-port `inputPorts`/`outputPorts`); 14 `*ModelBuilderTest` classes; `CombinedEndpointTest` pipeline fixture; hash-node `SKIPPED` failures — including the production bug where `SHA256Node`/`MD5Node`/`ChunkHashNode` dereferenced a null `asset.getHashes()`, and `AbstractBasicNodeTest` now asserting `COMPUTED` then `LOCAL` rather than `SKIPPED` on a cache hit |
| `loom/db/README.md` | No longer advertises the removed `fs` and `hibernate` impls; `jooq` is production, `memory` is test-only |

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
| End-of-run sync flush / `flush-sync` work order | Nothing buffers any more; superseded by per-node persistence — [../features/pipeline/PIPELINE.md §12](../features/pipeline/PIPELINE.md) |
| Node capability whitelist, node affinity, durable item queue with leases | **Built**, not dropped — see [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) |

---

_Git HEAD revision: `2e5981cb`_
_Last updated: 2026-08-01 (verified every item against the code; collapsed what has landed into outcome records and rewrote the ten still-open tasks in TASKS.template format)_
