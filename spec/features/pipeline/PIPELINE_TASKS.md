# MetaLoom Pipeline — Task List

> Work items for the pipeline feature, re-derived from a code audit on 2026-08-01.
> Format follows [../../TASKS.template.md](../../TASKS.template.md).
>
> **Context:** [PIPELINE.md](PIPELINE.md) (technical spec) ·
> [PIPELINE_REQUIREMENTS.md](PIPELINE_REQUIREMENTS.md) (requirement → status) ·
> [NODE_DATA_TYPES.md](NODE_DATA_TYPES.md) (ports, cardinality, fan-out)
>
> **This file tracks OPEN work.** Completed and superseded tasks are collapsed to
> one-line outcome records in §A; only open tasks keep full detail. Task numbers are
> stable — [../cli/CLI_PLAN.md](../cli/CLI_PLAN.md) cites Task 7 and
> [../db/DATABASE_TASKS.md](../db/DATABASE_TASKS.md) cites Task 2.
>
> **Blocking:** **Task 3** is the only item that makes a documented capability fail at
> run time — no `filter-*` kind is runnable, so requirement R7 is unmet. **Task 12** is
> the only silent-correctness bug (a restart drops port checking and fan-out). Do those
> two before any polish.

---

## A. Implementation status

| # | Task | State |
|---|---|---|
| 1 | Unify the pipeline definition schema between Loom and Cortex | ✅ **Superseded** (2026-07-2x) — Cortex no longer parses definitions at all. `PipelineGraphParser` in `loom/pipeline/graph` is the single parser; edges are port-to-port; `nodes[].dependencies[]` is **rejected outright**; `LoomPipelineLoader` is deleted. [PIPELINE.md §4](PIPELINE.md) |
| 2 | Make pipeline runs report completion | ✅ **DONE** (2026-07-18) — `PipelineRunEngine.onCompletion(RunSummary)` → `PipelineRunTracker.complete(...)`, first-terminal-verdict-wins; `PipelineRunStatusResolver` derives `SUCCESS`/`PARTIAL`/`FAILED`. The `WorkOrderResultRegistry` + 60 s ack watchdog were removed; an unreachable processor now fails synchronously at dispatch. Tests: `PipelineRunStatusResolverTest`, `PipelineRunCompletionEndpointTest`. |
| 3 | Register the remaining node kinds; fail loudly on unknown | 🟡 **Half done — OPEN below.** Fail-loudly landed (`RegistryNodeFactory.createNode` returns `null`, the task fails). Registration did not: 10 descriptor kinds still have no producer. |
| 4 | Fix executor lifecycle (single-use scheduler, node shutdown) | ✅ **Superseded** — `ReactivePipelineExecutor` is deleted; there is no in-Cortex executor or stats scheduler. The residual — `initialize()`/`shutdown()` never called by the runners — moved into Task 10. |
| 5 | Wire result caching and fix cache type fidelity | 🔴 **OPEN below**, re-scoped: the question is now *use or delete*, not *fix*. |
| 6 | Close the Cortex/pipeline test blind spots | 🟡 **Mostly done — OPEN below.** `cortex/node-runtime` is covered (`NodeTaskRunnerTest`, `SegmentTaskRunnerTest`, `SourceTaskRunnerTest`, `ResultBatcherTest`); `loom/pipeline` has 13 engine tests + 6 graph tests. Residual blind spots listed below. |
| 7 | Complete the Java REST client for run and version operations | 🟡 **Client done** (2026-07-26) — `PipelineMethods` now has `runPipeline`, `pause/resume/cancelPipelineRun`, `listPipelineVersions`, `loadPipelineVersion`, `restorePipelineVersion`, `loadPipelineRunStats`, `listPipelineRunItems`. **The endpoint tests it was meant to unblock are still missing — OPEN below.** |
| 8 | Consolidate validation and add a validation endpoint | 🔴 **OPEN below** — closes R11. |
| 9 | Type the pipeline run status | 🔴 **OPEN below** — `status` is still a free-form `String`. |
| 10 | Remove or implement the advertised-but-absent features | 🟡 **Partly resolved — OPEN below.** Resolved: `retryFailed` is now read (`PipelineGraphNode.getMaxAttempts()`, `DEFAULT_RETRY_ATTEMPTS = 2`, `maxAttempts` override, `PipelineRunEngineRetryTest`); the reactive-operator node API, `PipelineDeserializer.NodeResolver` and `mediaUuids` resolution are all gone or implemented. Residual list below. |
| 11 | Fill remaining persistence and API gaps | 🟡 **Partly resolved — OPEN below.** Resolved: per-node result persistence landed as `pipeline_run_item` + `pipeline_node_task` (`V2.31`, `V2.60`) rather than the proposed `pipeline_node_stats`; `dispatchWorkOrder` string-concatenation is gone (typed `SourceTaskMessage`/`NodeTask`). Residual list below. |
| 12 | Restore port checking in restart recovery | 🔴 **NEW — OPEN below.** |
| 13 | Instrument the run engine | 🔴 **NEW — OPEN below.** |

---

## Task 3: Make every advertised node kind runnable — starting with the filters

**Argumentation Summary:** `NodeDescriptorProvider` declares **41** kinds; only **33**
are runnable (32 without S3). The 10 descriptor-only kinds are `facedescription`,
`loom-fetch`, and **all eight `filter-*` kinds** — which means requirement **R7 is
unmet**: filtering, one of the four rooted capabilities, cannot execute. The eight
filter classes exist in `cortex/pipeline-core/…/node/filter/`, the definition format
carries `branch: PASS|REJECT`, and `PipelineRunEngine` honours `conditionalDependencies`
— only the registration is missing. Worse, `unsupportedNodeKinds` checks the worker's
**config whitelist/blacklist**, not what it can construct, so the run is **not** refused
with 503: it dispatches, `RegistryNodeFactory.createNode` returns `null`,
`NodeTaskRunner` NPEs, and the task fails. Two further mismatches: `filter-size` is
advertised with no class, and `SamplingFilterNode` is a class with no descriptor.

**Improvement Summary:** Register the eight filters as first-class kinds, resolve the
two descriptor mismatches, and add a boot-time consistency check so the two registries
cannot drift again.

```
1. cortex/cli/.../dagger/RegistryNodeRegistrar.registerAll():
   Filters extend AbstractPipelineNode, NOT FilesystemNode, so they cannot go
   through the `Map<String, Provider<FilesystemNode<?,?>>>` multibinding or the
   CortexNodeAdapter. Register them the same way sources are registered:

       factory.register("filter-mimetype", def -> mimeTypeFilter(def));

   Add one private static builder helper per kind, reading its options out of the
   node definition JsonObject exactly as filesystemSource(...) does. The kinds and
   their classes (cortex/pipeline-core/.../node/filter/):
       filter-mimetype         -> MimeTypeFilterNode
       filter-date             -> DateFilterNode
       filter-duplicate        -> DuplicateFilterNode
       filter-blacklist        -> BlacklistFilterNode
       filter-quality          -> QualityFilterNode
       filter-threshold        -> ThresholdFilterNode
       filter-asset-attribute  -> AssetAttributeFilterNode
   Each has a `builder(String id)` whose setters mirror the descriptor's
   parameters (e.g. MimeTypeFilterNode.Builder: allowVideo/allowImage/allowAudio/
   allowDocument/allowedExtensions). Use the node id from the definition.

2. Resolve the two mismatches in
   loom-shared/node-model/.../spec/FilterDescriptorProvider.java:
   - `filter-size` has no implementation. Either add SizeFilterNode to
     pipeline-core or drop the descriptor. Do not leave it advertised.
   - SamplingFilterNode has no descriptor. Either add one (kind
     `filter-sampling`) and register it, or delete the class.
   Whichever way each goes, update the counts in
   ../pipeline-nodes/NODES.md §5.2 and NodeDescriptorServiceLoaderTest.

3. facedescription and loom-fetch: decide and record. `facedescription`
   deliberately has no map binding today (NODES.md §5.1) — if that stays,
   the descriptor must be marked unrunnable rather than offered in the palette.

4. Add a boot-time reconciliation log in RegistryNodeRegistrar.registerAll():
   after registration, diff NodeDescriptorRegistry kinds against
   factory.registeredTypes() and log both directions at WARN. The gap must be
   visible at worker start, not at task failure.

5. Optional but cheap: have `PipelineEndpointService.unsupportedNodeKinds` also
   reject kinds no connected worker has *registered*, not just kinds its config
   blacklists. That turns Task 3-class regressions into a 503 at dispatch instead
   of a failed task mid-run. Requires the worker to report registeredTypes() in
   its REGISTER frame.
```

**References:** [PIPELINE_REQUIREMENTS.md](PIPELINE_REQUIREMENTS.md) R7 ·
[PIPELINE.md §7.2, §8](PIPELINE.md) · [../pipeline-nodes/NODES.md §3, §5.2](../pipeline-nodes/NODES.md) ·
`RegistryNodeRegistrar.java`, `FilterDescriptorProvider.java`, `cortex/pipeline-core/…/node/filter/`

**Test Requirements:** A test asserting every `NodeDescriptorRegistry` kind either has a
registered producer or appears on an explicit, commented allow-list — this is the
tripwire that stops the drift. One `AbstractNodeChainTest` subclass per filter asserting
the `passed` output **and** PASS/REJECT routing. An engine test
(`loom/pipeline`) that a `filter-*` graph routes the REJECT branch. Update
`NodeDescriptorServiceLoaderTest` and `NodePortConformanceTest` counts.
Run: `mvn -pl cortex/cli,cortex/pipeline-core,loom/pipeline test`.

---

## Task 5: Resolve the node cache layer — use it or delete it

**Argumentation Summary:** `cortex/pipeline-api`'s `NodeCacheProvider` and its five
implementations in `cortex/pipeline-common` (`HeapNodeCache`, `XAttrNodeCache`,
`SidecarFileNodeCache`, `LayeredNodeCache`, `NoOpNodeCache`) are **unreachable**:
`PipelineNode.cacheProvider()` and `AbstractPipelineNode.setCacheProvider(...)` have no
caller in any runtime path, there is no Dagger provider for a `NodeCacheProvider`, and
`cortex/pipeline-common` has **no test directory at all**. Only `FacedetectNode`
references a cache impl directly. Meanwhile the caching that *does* work is a different
thing entirely — `cortex/common/…/cache/LocalResultCache`, a bounded per-node LRU of
already-computed results used by ~20 nodes. Two cache concepts, one live, one dead, both
named "cache" is how an agent loses an afternoon.

**Improvement Summary:** Decide the layer's fate. Deleting it is the default; keeping it
requires wiring and tests in the same change.

```
Decide ONE of:

(a) DELETE. Remove NodeCacheProvider, the five impls, PipelineNode.cacheProvider(),
    AbstractPipelineNode.setCacheProvider(), and the pipeline-common cache package.
    Have FacedetectNode use LocalResultCache instead. Record the removal in
    PIPELINE.md §7.3 and ../pipeline-nodes/NODES.md §4.

(b) WIRE. Add a Dagger provider driven by CortexOptions
    (none|heap|xattr|sidecar|layered, default none), call setCacheProvider(...)
    from RegistryNodeRegistrar.adapt(...), and have NodeTaskRunner consult the
    cache before process(...). Before doing this, fix the two known defects:
    - XAttrNodeCache and SidecarFileNodeCache share a line-based `key=value`
      serializer, so EVERY value returns as a String — a cached boolean
      `passed` comes back as "true" and silently breaks branch routing.
      Replace with JSON plus a schema-version field.
    - clear() is an unimplemented stub in both; XAttrNodeCache.invalidate()
      writes "" instead of removing the attribute.
    Create cortex/pipeline-common/src/test and cover all five impls, plus
    DefaultPipelineEventBus and DefaultLoomBulkSyncCollector.

Note the neighbouring, genuinely-wanted feature: a segment-scoped *artifact*
cache (decode once per segment) is designed in ../../plans/TASKS.md and is NOT
this task. If (b) is chosen, align the two rather than shipping a third cache.
```

**References:** [PIPELINE.md §7.3](PIPELINE.md) · [../pipeline-nodes/NODES.md §4](../pipeline-nodes/NODES.md) ·
[../../plans/TASKS.md](../../plans/TASKS.md) · `cortex/pipeline-common/…/cache/`, `cortex/common/…/cache/LocalResultCache.java`

**Test Requirements:** For (a): a compile-clean build and a grep showing no remaining
`cacheProvider` reference. For (b): a type-fidelity round-trip test per persistent cache
(boolean, numeric and list outputs must survive `put`/`get`), `clear()`/`invalidate()`
tests including that unrelated files survive, and a runner test that a cache hit on a
filter still routes PASS correctly.

---

## Task 6: Close the residual test blind spots

**Argumentation Summary:** The engine and worker runtime are now well covered, but four
seams are not, and each one has already hidden a defect of its class.
`cortex/pipeline-common` has **no test directory**. `LoomControlChannel` (reconnect,
frame routing) and `CortexNodeAdapter` (state re-stamping, elapsed measurement) have no
direct tests. `PipelineModelValidator` is untested. And there is still **no checked-in
definition JSON fixture anywhere in the repo** — the six `DemoDatabaseInitializer`
pipelines are the de-facto reference, which means a format regression is caught only if
someone runs the demo seeder.

**Improvement Summary:** Add the missing seam tests and check in the reference fixture
the format has never had.

```
1. Check in cortex/pipeline-core/src/test/resources/pipeline/reference-definition.json
   (or loom/pipeline/src/test/resources/) holding a port-wired graph that exercises
   version, options vs the legacy `config` alias, branch edges, affinity, fan-out
   (MANY output into a PER_ELEMENT node) and gather. Add a PipelineGraphParser test
   that loads it and asserts node count, topological order, InputBindings and
   ExecutionMode per node. This is the regression fixture the format lacks.

2. CortexNodeAdapterTest — wrap a stub FilesystemNode and assert: id re-stamping via
   NodeResult.withNode(...), measured elapsed is non-zero, state/message/outputs are
   preserved, and syncToLoom/cacheProvider are post-construction setters (not ctor
   args).

3. LoomControlChannelTest — frame routing per ProcessorMessageType, reconnect
   scheduling, and that gauges/counters listed in ../ops/METRICS.md §4 are updated.

4. Cover PipelineModelValidator or delete it (see Task 8 — deletion is preferred).

5. DAO tests: PipelineDaoTest/PipelineVersionDaoTest exercise only the generic CRUD
   harness. Add cases for loadWithLatestVersion (see Task 11 item 1), loadByUuids,
   loadByPipelineAndVersion, loadLatestByPipeline.

Use the existing harnesses — do not roll new ones:
  AbstractNodeChainTest (cortex/pipeline-core test-jar) for node chains
  StubLoomMedia / CapturingNode(id, port) for fixtures and downstream assertions
  PipelineAssertions / PipelineResultAssert instead of raw assertEquals on maps
  FakeNodeDispatcher / RecordingRunStateStore (loom/pipeline test) for engine tests
```

**References:** [PIPELINE.md §13](PIPELINE.md) · `cortex/pipeline-core/src/test/…/test/`,
`loom/pipeline/src/test/…/engine/`

**Test Requirements:** This task is tests. Target the four seams above rather than a
coverage percentage. `mvn -pl cortex/pipeline-core,cortex/pipeline-common,cortex/core,loom/pipeline test`.

---

## Task 7: Java endpoint tests for versioning, dispatch and delete-cascade

**Argumentation Summary:** The client half landed on 2026-07-26 — `PipelineMethods` now
reaches every pipeline route. The tests it was added to unblock still do not exist. The
Java endpoint suite covers run items, run stats, pause, cancel, completion and events,
but **no Java test touches `/versions`, `/versions/:version`, `/versions/:version/restore`,
the `POST /run` dispatch payload, or `DELETE /:uuid`**. Versioning is covered only by
mocked Playwright specs, which never reach the server — an entire shipped API surface is
verified by mocks.

**Improvement Summary:** Add the endpoint tests in the standard harness so they run on
every build.

```
Add loom/core/src/test/java/io/metaloom/loom/core/endpoint/test/
      PipelineVersionEndpointTest.java
      PipelineRunDispatchEndpointTest.java

⚠️ Do NOT redeclare @RegisterExtension LoomCoreTestExtension in a subclass of
   AbstractEndpointTest — configure the inherited `loom` field (see the pattern in
   PipelineRunItemEndpointTest).

PipelineVersionEndpointTest:
 1. create -> update -> update: assert versionNumber 1, 2, 3 and that v1/v2 still
    carry their ORIGINAL definitions (versions are immutable, update copies forward).
 2. restore v1: assert a NEW version is created (copy-forward, HTTP 201) and v1 is
    unchanged.
 3. loadPipelineVersion for a nonexistent version -> 404.
 4. Permission coverage per ../../guidelines/CODING.md: READ_PIPELINE_VERSION on the
    two GETs, RESTORE_PIPELINE_VERSION on the restore. Grant via group+role, not a
    direct user_permission row.

PipelineRunDispatchEndpointTest:
 5. POST /run with no processor registered -> 503, and assert NO pipeline_run row
    exists afterwards.
 6. POST /run with an invalid graph -> 400, and again assert no row.
 7. POST /run with a registered processor -> 202, and assert the dispatched
    SourceTaskMessage payload shape (runUuid, source kind, resolved source options).
    Reuse the fake-processor plumbing from ProcessorEndpointTest.
 8. DELETE /:uuid removes the pipeline, its versions and its runs.
```

**References:** [PIPELINE.md §10, §13](PIPELINE.md) · [../cli/CLI_PLAN.md](../cli/CLI_PLAN.md) ·
`PipelineMethods.java`, `PipelineEndpointService.java`, `PipelineRunItemEndpointTest.java`

**Test Requirements:** The eight cases above, in the endpoint-test harness
(`LoomCoreTestExtension`), not as integration tests. Run `./setup-pool.sh` first.
⚠️ Keep each class under ~20 test methods — a larger class exhausts the test DB pool.

---

## Task 8: Add a validation endpoint and de-triplicate structural validation

**Argumentation Summary:** Requirement **R11 is unmet**: there is no
`POST /api/v1/pipelines/validate`, so a client cannot validate a draft without
persisting it. Separately, the *structural* rules (node id format, uniqueness, edge
references, Kahn's cycle detection, reachable-from-source) exist in **three** independent
implementations — `PipelineValidationService` (loom rest, the wired and tested one),
`PipelineModelValidator` (`loom-shared/rest-model`, untested and unwired), and
`validatePipeline()` in `PipelineEditor.tsx` (its own TypeScript Kahn's). They will
drift. **Port** rules are correctly single-sourced in `PipelineGraphParser` — do not add a
second copy of those.

**Improvement Summary:** Make the server the single authority, expose it, and reduce the
client to cheap synchronous checks.

```
1. Add POST /api/v1/pipelines/validate to PipelineEndpoint:
   - body: the definition JsonObject; response:
     { valid: boolean, errors: [{ code, message, nodeId?, edgeId? }] }
   - gate on CREATE_PIPELINE (validating a draft is an authoring action)
   ⚠️ Secured paths in PipelineEndpoint are enumerated INDIVIDUALLY (so the events WS
      escapes the auth chain). Add secure(basePath() + "/validate") or the route ships
      unauthenticated.
   ⚠️ Register it BEFORE the ":uuid" wildcard, like "/runs/stats", or it is shadowed.

2. Refactor PipelineValidationService to collect a structured error list instead of
   throwing on the first problem, so a user sees every error at once. Keep a thin
   throwing wrapper for the existing create/update call sites so their behaviour and
   tests are unchanged.

3. Delete PipelineModelValidator's structural checks. It is untested and unwired for
   these rules; keep only what rest-model genuinely needs, or delete the class.

4. loom-ui: replace validatePipeline() in PipelineEditor.tsx with a debounced call to
   the new endpoint. Keep only cheap synchronous checks (empty graph, malformed id)
   and the live isValidConnection port checks for editor responsiveness. Removing the
   TypeScript Kahn's implementation is the point of the task.
```

**References:** [PIPELINE_REQUIREMENTS.md](PIPELINE_REQUIREMENTS.md) R11 ·
[PIPELINE.md §5.1, §10](PIPELINE.md) · [../../loom/ui/PIPELINE_EDITOR.md](../../loom/ui/PIPELINE_EDITOR.md) ·
`PipelineValidationService.java`, `PipelineModelValidator.java`, `PipelineEditor.tsx`

**Test Requirements:** Endpoint tests for a valid and several invalid definitions,
asserting **all** errors come back, not just the first. Re-point the existing
`PipelineValidationServiceTest` cases at the collecting API. A permission test for
`CREATE_PIPELINE`. A Playwright spec that a server validation error blocks save.
Per [../../guidelines/CODING.md](../../guidelines/CODING.md) a new endpoint also needs
website docs.

---

## Task 9: Type the pipeline run status

**Argumentation Summary:** `pipeline_run.status` is a free-form `String` in the DB, the
DAO model and `PipelineRunRecord`. The vocabulary — `PENDING, RUNNING, PAUSED, SUCCESS,
FAILED, PARTIAL, CANCELLED` — exists **only as a SQL comment** (`V2.29`, extended by
`V2.56`). Nothing stops a typo being persisted, and the UI has no reliable set to switch
on. The same applies to `pipeline_node_task.state` (`PENDING, RUNNING, COMPLETED, FAILED,
SKIPPED, DEAD_LETTER`) and `pipeline_run_item.state`, which are compared as string
literals in `PipelineRunRecovery` and `LeaseReaper`.

**Improvement Summary:** Introduce enums and parse at the boundary; keep the columns
`VARCHAR`.

```
1. Add PipelineRunStatus to loom-shared/rest-model alongside the other pipeline model
   types, with the seven documented values. Add NodeTaskState and RunItemState in
   loom/db/api (or reuse the existing NodeState where the vocabularies match — they do
   NOT: NodeState has no DEAD_LETTER).
2. Use them in the DAO models, PipelineRunRecord, PipelineRunTracker,
   PipelineRunStatusResolver, PipelineRunRecovery and LeaseReaper. Keep the DB column
   VARCHAR — a Postgres enum needs a migration for every new value — and parse/serialise
   through the Java enum at the boundary.
3. Reject an unknown value on read with a clear message naming the column and the value,
   rather than passing a bad string to the UI.
4. PipelineRunStatusResolver.isTerminal must keep PAUSED non-terminal.
```

**References:** [PIPELINE.md §9.1, §10.2](PIPELINE.md) ·
`V2.29__add_pipeline_run.sql`, `V2.31__add_pipeline_execution_state.sql`,
`V2.56__pipeline_run_paused_status.sql`, `PipelineRunRecord.java`, `LeaseReaper.java`

**Test Requirements:** Round-trip test for every enum value through DAO and REST. A test
that an unrecognised status string is rejected with a message naming the value. Re-run
`PipelineRunStatusResolverTest` and `PipelineRunEngineRecoveryTest` unchanged.

---

## Task 10: Retire the remaining dead surfaces

**Argumentation Summary:** Each item below is advertised in an API, descriptor or config
and does nothing. Every one costs a contributor or agent real time to discover, and two
of them mislead users of the UI. `retryFailed` — the headline example — was finally
implemented, which is the model: pick one side and commit.

**Improvement Summary:** Decide implement-or-delete for each, one PR per item.

```
1. Processor capability is hardcoded to CPU in PipelineEndpointService
   (selectProcessorForKinds(ProcessorCapability.CPU, ...) at both the
   unsupportedNodeKinds check and dispatch). Derive the required capability from the
   graph's node kinds so a GPU-dependent pipeline is not sent to a CPU-only worker.
   The descriptor is the natural place to declare it.

2. Node lifecycle: neither NodeTaskRunner nor SegmentTaskRunner calls
   PipelineNode.initialize() or shutdown(). Nodes holding native handles (facedetect,
   whisper, OpenCV) are constructed lazily per kind and never released. Either call
   both around the node's lifetime in the worker, or delete the two methods from the
   interface. Do not leave them as decoration.

3. NODE_STATS is advertised in the events list of every descriptor, but stats are
   emitted by RunStatsAggregator on the Loom side, not per node. Remove the
   advertisement or make it mean something.

4. PipelineFilter / MediaFilter SPI — no production references anywhere. Delete.

5. LoomBulkSyncCollector / LoomBulkSyncWriterImpl — wired in CortexBindModule and
   flushed at shutdown by CortexImpl, but nothing calls collect(...). Asset write-back
   happens on the Loom side via DaoAssetSink. Delete the Cortex path, or document why
   it is kept (this is also the only REST result path, see R2).

6. PipelineEventBroadcaster.Subscriber takes a queueCapacity constructor arg that is
   never stored; DEFAULT_QUEUE_CAPACITY = 1024 is dead. Backpressure is purely
   writeQueueFull(). Remove both or implement the bounded queue.

7. CortexOptions.maxConcurrentMedia (default 4) is dead config — its only caller was
   the deleted ReactivePipelineExecutor. Remove it and its CLI/env surface.

8. RegistryNodeFactory.createNode javadoc still says it falls back to a stub. It
   returns null; StubPipelineNode is deleted. Fix the comment — it is the first thing
   an agent reads when a task fails with an NPE.
```

**References:** [PIPELINE.md §7.3, §11.1, §12](PIPELINE.md) ·
[PIPELINE_REQUIREMENTS.md](PIPELINE_REQUIREMENTS.md) R2 ·
`PipelineEndpointService.java`, `NodeTaskRunner.java`, `PipelineEventBroadcaster.java`,
`CortexOptions.java`, `RegistryNodeFactory.java`

**Test Requirements:** For anything implemented, a test proving it works — item 1 needs a
`PipelineRunCapabilityTest` case that a GPU-only graph is not placed on a CPU-only
worker; item 2 needs a runner test that `shutdown()` is invoked. For anything deleted, a
compile-clean build plus a grep showing no descriptor, DTO or config still advertises it.

---

## Task 11: Fill the remaining persistence and API gaps

**Argumentation Summary:** A cluster of small, independent gaps. None blocks the feature;
each is a correctness or clarity defect that costs time.

**Improvement Summary:** Address as capacity allows; no ordering between them.

```
1. PipelineDaoImpl.loadWithLatestVersion does NOT load the version — it is a plain
   selectFrom(PIPELINE).where(uuid), and every caller separately calls
   pipelineVersionDao.loadLatestByPipeline(...). Implement the join the name promises
   or rename the method. The name actively misleads.

2. PipelineDaoImpl.createPipeline(UUID userUuid, String name) ignores `name` — correct
   post-V2.30 (name lives on the version) but dead weight on the interface. Remove it.

3. PipelineEndpointService.delete loops deleting versions before the pipeline even
   though the FK is ON DELETE CASCADE. Drop the loop, or document why the explicit
   delete is needed (audit events?).

4. loom/db/memory has NO pipeline DAOs, so the in-memory backend cannot serve
   pipelines at all. Either implement MemPipeline{,Version,Run,RunItem,NodeTask}Dao,
   or make the backend fail fast with a message naming the limitation. Today it fails
   obscurely. See ../db/DATABASE.md.

5. ProcessorEndpoint returns a hand-built JSON 404 body for an unknown processor
   instead of the standard error model, and its lookup is a linear scan that computes
   toResponse twice per candidate.

6. loom-ui/src/api/pipelines.ts: listPipelineVersions and listPipelineRuns return []
   on ANY non-OK response, so a server failure is indistinguishable from "no data".
   Surface real errors. Tracked for the UI side in
   ../../loom/ui/TASK_UI_PIPELINE.md — coordinate rather than duplicate.

7. Server-side default layout: when a definition's nodes lack x/y, compute a layered
   left-to-right layout server-side so non-editor clients do not each reimplement it.
   The chat card (loom-ui/src/features/chat/pipelineGraphLayout.ts) is the second
   implementation already.

8. No pipeline gRPC surface (asset, health, reflection are registered). This is an
   accepted omission — record it as such in ../../loom/GRPC.md rather than leaving it
   as an implied gap, or add pipeline.proto.
```

**References:** [PIPELINE.md §9.3, §10](PIPELINE.md) ·
[../db/DATABASE_TASKS.md](../db/DATABASE_TASKS.md) ·
[../../loom/ui/TASK_UI_PIPELINE.md](../../loom/ui/TASK_UI_PIPELINE.md) ·
`PipelineDaoImpl.java`, `ProcessorEndpoint.java`, `loom-ui/src/api/pipelines.ts`

**Test Requirements:** Per item. Item 1 needs a DAO test asserting the version is
actually populated. Item 4 needs the `loom/db/api-test` contract tests to pass against
the memory backend or to skip explicitly. Items 5 and 6 need an error-path test each.

---

## Task 12: Restore port checking in restart recovery

**Argumentation Summary:** `PipelineRunRecovery` builds its parser with
`new PipelineGraphParser()` (line 68) — the **no-arg** constructor, which passes a
`null` descriptor registry. `PortGraphAnalyzer.analyze` then returns immediately, so a
recovered run gets **no port validation and every node classified `ExecutionMode.SINGLE`**.
That is not cosmetic: a graph whose `facedetect → facedescription` edge is `PER_ELEMENT`
runs per-element before a Loom restart and once-per-item after it. The run silently
changes behaviour across a restart, and the fan-out results are lost. The no-arg
constructor exists for test convenience; production must not use it.

**Improvement Summary:** Inject the descriptor registry into `PipelineRunRecovery` so
recovered graphs are parsed identically to freshly dispatched ones.

```
1. In loom/services/rest/.../service/impl/PipelineRunRecovery.java, replace the
   `private final PipelineGraphParser parser = new PipelineGraphParser();` field with
   a constructor-injected parser — the SAME instance PipelineEndpointService uses, so
   there is one registry-backed parser per process. Add it to the Dagger graph.
   ⚠️ Endpoint/service constructor changes need a clean rebuild of loom/core, or
      setup-pool and the tests fail with NoSuchMethodError.
2. Audit for other production uses of the no-arg constructor:
       grep -rn "new PipelineGraphParser()" --include=*.java loom | grep -v /test/
   Any hit outside src/test is the same bug.
3. Consider making the no-arg constructor package-private or @VisibleForTesting so a
   future caller cannot reintroduce this silently.
```

**References:** [PIPELINE.md §5, §6.5, §16](PIPELINE.md) ·
[NODE_DATA_TYPES.md §6.3](NODE_DATA_TYPES.md) ·
`PipelineRunRecovery.java`, `PipelineGraphParser.java`, `PortGraphAnalyzer.java`

**Test Requirements:** Extend `PipelineRunEngineRecoveryTest` (or add a
`PipelineRunRecoveryTest` in `loom/services/rest`) with a fan-out graph: recover it and
assert the `PER_ELEMENT` node is still classified `PER_ELEMENT` with the correct
`fanOutDriver`. A test that recovering a graph with an invalid port fails loudly rather
than recovering a degraded run.

---

## Task 13: Instrument the run engine

**Argumentation Summary:** Prometheus `/metrics` is live on both components and 22 Loom
meters have verified call sites — but **`loom/pipeline` contains not a single
`LoomMetrics` reference**. `PipelineRunEngine`, `NodeKindCircuitBreaker` and
`DaoRunStateStore` are entirely uninstrumented, so the five meters documented in
[../ops/METRICS.md §5.2](../ops/METRICS.md) are pure fiction: `loom_node_tasks_inflight`,
`loom_node_tasks_retried_total`, `loom_node_tasks_deadlettered_total`,
`loom_node_circuit_breaker_trips_total`, `loom_result_store_flush_batch_size`. The
consequence is operational: in-flight depth, retry rate and breaker trips — the three
numbers that explain a stalled run — are invisible.

**Improvement Summary:** Give `loom/pipeline` a metrics seam and record the five meters
at their natural sites.

```
⚠️ Constraint: loom/pipeline deliberately has NO dependency on Loom internals — dispatch,
   state and asset persistence are injected as interfaces (NodeDispatcher, RunStateStore,
   AssetSink). Do NOT import LoomMetrics there. Instead:

1. Define a small metrics seam in loom/pipeline/engine (e.g. `EngineMetrics` with
   default no-op methods: onDispatch/onSettle, onRetryScheduled, onDeadLetter,
   onCircuitTrip), defaulting to a no-op so every existing engine test is unaffected.
2. Record at the natural sites:
   - inflight gauge     -> PipelineRunEngine's in-flight counter (bindGauge)
   - retried_total      -> RetryScheduler / PipelineRunEngine retry path
   - deadlettered_total -> the give-up path (also emitted by LeaseReaper for orphans;
                           keep the two distinguishable by label, not by double count)
   - breaker trips      -> NodeKindCircuitBreaker on open
3. Implement the seam in loom/services/rest over LoomMetrics and inject it where the
   engine is built (PipelineEndpointService step 5).
4. DaoRunStateStore: register loom_result_store_flush_batch_size as a summary, or
   delete the row from ../ops/METRICS.md §5.2. Do not leave it documented and absent.
5. Update ../ops/METRICS.md §3 and §5.2 in the same change — that file's value is that
   every row has a verified call site.
```

**References:** [../ops/METRICS.md §3, §5.2](../ops/METRICS.md) ·
[PIPELINE.md §6.2, §12](PIPELINE.md) ·
`PipelineRunEngine.java`, `NodeKindCircuitBreaker.java`, `RetryScheduler.java`,
`DaoRunStateStore.java`, `LoomMetrics.java`

**Test Requirements:** A test per meter asserting the counter moves on the triggering
event (use a recording `EngineMetrics` in `loom/pipeline`, and a `SimpleMeterRegistry`
assertion in `loom/services/rest`). A test that the default no-op seam leaves engine
behaviour unchanged.

---

## B. Tracked elsewhere — do not duplicate here

These are real open items that touch the pipeline but are **owned by another spec file**.
Link them; do not open a parallel task.

| Item | Owner |
|---|---|
| Task-state retention sweep (decided, not built) | [../../cortex/METALOOM_ARCHITECTURE_TASK.md](../../cortex/METALOOM_ARCHITECTURE_TASK.md) §"Enforce the task-state retention policy" · [PIPELINE.md §9.2](PIPELINE.md) |
| Per-node task inspection API (`leased_by`, attempts, dead-letter reason) | [../../cortex/METALOOM_ARCHITECTURE_TASK.md](../../cortex/METALOOM_ARCHITECTURE_TASK.md) · [../../loom/ui/TASK_UI_PIPELINE.md](../../loom/ui/TASK_UI_PIPELINE.md) |
| Adaptive dispatch width from live load; priority with aging; straggler / speculative re-dispatch | [../../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md](../../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md) §3.1 |
| Dispatch batching; adaptive `resultBatchSize` | [PLAN_C](../../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md) §3.2 |
| Per-item opt-in event stream | [PLAN_C](../../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md) §3.3 |
| Segment-scoped intermediate **artifact** cache (decode once per segment) | [../../plans/TASKS.md](../../plans/TASKS.md) — related to but distinct from Task 5 |
| UI gaps: node-task drill-down, server-driven handle colours, run deep-linking, `PipelineArea` retirement | [../../loom/ui/TASK_UI_PIPELINE.md](../../loom/ui/TASK_UI_PIPELINE.md) |
| Node-level gaps: `facedescription` binding, `asset-source` descriptor, per-node docs | [../pipeline-nodes/NODES.md §10](../pipeline-nodes/NODES.md) |

---

## C. Progress Assessment

- [x] **Task 1** — Definition schema unified (superseded: one parser, ports, `dependencies[]` rejected)
- [x] **Task 2** — Runs report completion (2026-07-18)
- [ ] **Task 3** — Make every advertised kind runnable, starting with the filters (**blocking, R7**)
- [x] **Task 4** — Executor lifecycle (superseded: the Cortex executor is deleted; residual folded into Task 10)
- [ ] **Task 5** — Resolve the node cache layer: use it or delete it
- [ ] **Task 6** — Close the residual test blind spots (fixture, adapter, control channel, DAOs)
- [ ] **Task 7** — Java endpoint tests for versioning, dispatch and delete-cascade
- [ ] **Task 8** — Validation endpoint + de-triplicated structural validation (**closes R11**)
- [ ] **Task 9** — Type the pipeline run status
- [ ] **Task 10** — Retire the remaining dead surfaces
- [ ] **Task 11** — Fill the remaining persistence and API gaps
- [ ] **Task 12** — Restore port checking in restart recovery (**silent correctness bug**)
- [ ] **Task 13** — Instrument the run engine

### Suggested sequencing

**Task 3 first** — it is the only unmet rooted requirement that fails at run time, and
its boot-time reconciliation check prevents the class of regression recurring.
**Task 12 second** — small, self-contained, and it is currently changing run semantics
across a restart without anyone noticing.

Then **Task 7** and **Task 8** together: both touch the REST surface, and Task 7's
harness is what Task 8's endpoint tests will reuse. **Task 9** should follow Task 8 so
the typed status is introduced once, not twice.

**Tasks 5, 6, 10, 11, 13** are independent and parallelisable. Task 6's reference-fixture
item is worth pulling forward regardless — the definition format has never had a
checked-in regression fixture.

---

## D. Conventions and Gotchas

Task-file discipline for this area. Code-level conventions live in
[PIPELINE.md §16](PIPELINE.md).

| Area | Convention / Gotcha |
|---|---|
| **Numbers are stable** | Other files cite Task 2 and Task 7 by number. Never renumber; mark a task done or superseded in place. |
| **Done ⇒ one line** | A completed task collapses to a row in §A naming *where it landed* (classes, migration, tests). Keeping the old narrative is how this file reached 700 lines of history. |
| **One owner per gap** | If §B lists it, link it. A gap argued in two task files gets fixed in neither. |
| **The spec is part of the change** | Closing a task means editing [PIPELINE.md](PIPELINE.md), [PIPELINE_REQUIREMENTS.md](PIPELINE_REQUIREMENTS.md) and this file in the same commit ([SPEC_RULES.md](../../SPEC_RULES.md), [../../guidelines/CODING.md](../../guidelines/CODING.md)). |
| **A descriptor is not a registration** | Task 3 exists because these were treated as the same thing. When adding a kind, do both, and update the counts in [NODES.md §5.2](../pipeline-nodes/NODES.md). |
| **Test DB pool** | Run `./setup-pool.sh` before any DB-touching test, and again after any Flyway change. Keep endpoint-test classes under ~20 methods or the provider pool is exhausted and the last methods error in `ProviderExtension.beforeEach`. |
| **Endpoint constructor changes** | Clean-rebuild `loom/core` afterwards, or `setup-pool` and the suite fail with `NoSuchMethodError`. |
| **New REST route checklist** | Add it to the individually-enumerated `secure(...)` list, register literals before the `:uuid` wildcard, add endpoint + permission tests, add the Java client method, and add website docs. |
| **New DB field checklist** | Flyway migration → `loom/db/jooq/generate.sh` → `db/api` change → jooq + memory impls → `db/api-test` contract test → `./setup-pool.sh`. |
| **Don't reintroduce deleted concepts** | `Pipeline`, `PipelineExecutor`, `ReactivePipelineExecutor`, `LoomPipelineLoader`, `StubPipelineNode`, `PipelineSerializer`/`Deserializer`, `MediaContext`, `WorkOrderResultRegistry` are all gone on purpose. |

---

## E. Where do I find …?

| Need | Path |
|---|---|
| Where a kind becomes runnable (Task 3) | `cortex/cli/…/dagger/RegistryNodeRegistrar.java` |
| Filter implementations + their builders (Task 3) | `cortex/pipeline-core/…/node/filter/` |
| Filter descriptors + kind names (Task 3) | `loom-shared/node-model/…/spec/FilterDescriptorProvider.java` |
| Descriptor ↔ runnable reconciliation | [../pipeline-nodes/NODES.md §5.2](../pipeline-nodes/NODES.md) |
| The dead cache layer (Task 5) | `cortex/pipeline-api/…/api/cache/`, `cortex/pipeline-common/…/cache/` |
| The *live* result cache (Task 5, contrast) | `cortex/common/…/common/cache/LocalResultCache.java` |
| Endpoint-test harness + pattern to copy (Task 7) | `loom/core/src/test/…/endpoint/test/PipelineRunItemEndpointTest.java` |
| Java client methods (Task 7) | `loom-client/common/…/method/PipelineMethods.java` |
| The three validators (Task 8) | `loom/services/rest/…/validation/PipelineValidationService.java` · `loom-shared/rest-model/…/validation/PipelineModelValidator.java` · `loom-ui/src/features/pipeline/PipelineEditor.tsx` |
| Status vocabularies (Task 9) | `V2.29`, `V2.31`, `V2.56` migration comments · `PipelineRunStatusResolver.java` |
| Recovery + the no-arg parser bug (Task 12) | `loom/services/rest/…/service/impl/PipelineRunRecovery.java:68` |
| Metric catalog + the gap list (Task 13) | `loom/common/…/metrics/LoomMetrics.java` · [../ops/METRICS.md](../ops/METRICS.md) |
| Engine test harnesses | `loom/pipeline/src/test/…/engine/{FakeNodeDispatcher,RecordingRunStateStore,Payloads}.java` |
| Node chain test harness | `cortex/pipeline-core/src/test/…/test/AbstractNodeChainTest.java` |
| Definition of done for a code change | [../../guidelines/CODING.md](../../guidelines/CODING.md) |

---

_Git HEAD revision: `499f71f7`_
_Last updated: 2026-08-01 (rebuilt against the current runtime: Tasks 1/2/4 collapsed to outcome records, Tasks 3/5/6/7/10/11 re-scoped to what is actually still open, new Tasks 12–13 for the recovery parser bug and the uninstrumented engine, and items owned by other spec files moved to a cross-reference table)_
