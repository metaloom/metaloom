# MetaLoom Pipeline — Task List

> Work items for the pipeline feature, derived from a code audit on 2026-07-18.
> Format follows [../../TASKS.template.md](../../TASKS.template.md).
>
> **Context:** [PIPELINE.md](PIPELINE.md) (technical spec) ·
> [PIPELINE_REQUIREMENTS.md](PIPELINE_REQUIREMENTS.md) (requirements + gap status)
>
> Tasks are ordered by severity. **Tasks 1–3 are blocking**: until they are
> done, a pipeline authored in the Loom UI does not actually execute as drawn,
> and no run ever reports completion. Do not start on polish tasks before
> these land.

---

## Task 1: Unify the pipeline definition schema between Loom and Cortex

**Argumentation Summary:** Loom persists and validates a graph as `nodes[]` plus
a top-level `edges[]` array. `LoomPipelineLoader` on the Cortex side reads
`nodes[].dependencies[]` and never looks at `edges`. Consequently every
UI-authored pipeline loads on Cortex as a set of dependency-free nodes; the
loader picks the first one as the source and `DefaultPipeline`'s BFS discovers
only that node. The rest of the graph is silently discarded. This makes the
entire feature non-functional end-to-end while appearing to work — runs succeed,
they just do almost nothing.

**Improvement Summary:** Pick one wire format, make both sides speak it, and
lock it down with a shared fixture and a loader test.

```
The Loom-side format is authoritative — it is what the UI produces, what
PipelineValidationService validates, and what DemoDatabaseInitializer seeds:

  { "nodes": [ {id, type, name, x, y}, ... ],
    "edges": [ {id, source, target}, ... ] }

Change the CORTEX side to consume it.

1. In cortex/core/.../pipeline/loader/LoomPipelineLoader.java:
   - After parsing definition.nodes[], parse definition.edges[] and call
     parent.connectTo(child) for each {source, target} pair, resolving ids
     through the node map built in pass 1.
   - Keep reading nodes[].dependencies[] as a fallback so existing
     Cortex-serde-format definitions still load. Prefer edges[] when present.
   - Support an optional edge "type" field carrying PASS/REJECT/ANY, mapped to
     FilterBranch via connectTo(child, branch). This closes A-DI2 and makes
     R7 (filter branching) reachable from the UI at all.
   - Determine the source node from nodes[].source == true, else the node whose
     type resolves to a SourceNode, else the unique node with no inbound edge.
     Throw a clear error if that is ambiguous rather than silently picking one.

2. Check in a reference fixture — there is currently NO pipeline definition
   JSON anywhere in the repo. Add:
     cortex/core/src/test/resources/pipeline/loom-definition-example.json
   containing the exact graph DemoDatabaseInitializer seeds.

3. Add cortex/core/src/test/java/.../loader/LoomPipelineLoaderTest.java that
   loads the fixture and asserts pipeline.nodes() has ALL nodes in correct
   topological order — not just the source. This is the test whose absence
   allowed the bug.

4. Verify the reverse direction: PipelineSerializer output must still
   deserialize. Extend PipelineSerdeRoundTripTest if you touch the shape.
```

**References:** [PIPELINE.md](PIPELINE.md) §9.2, §12.4 ·
`LoomPipelineLoader.java`, `PipelineValidationService.java`,
`DemoDatabaseInitializer.java`

**Test Requirements:** New `LoomPipelineLoaderTest` covering: full graph
reconstruction from `edges[]`, backward compatibility with `dependencies[]`,
PASS/REJECT edge types, ambiguous-source error, and unknown node type. An
integration test that seeds the demo pipeline, loads it through the loader, and
asserts the node count matches.

---

## Task 2: Make pipeline runs report completion — ✅ DONE (2026-07-18)

> **⚠️ Superseded:** the WorkOrder mechanism (`WorkOrderResultRegistry`,
> `PipelineWorkOrderHandler`, the 60 s ack watchdog) described below was
> **removed**. Runs now close through `PipelineRunEngine` → `PipelineRunTracker`,
> and an unreachable processor fails the run synchronously at dispatch. See
> PIPELINE.md §12 for the current flow. The narrative below is kept as history.
>
> **Implemented.** Cortex now threads a `PipelineRunContext` through the
> executor so every tracking event carries the run id, and `PIPELINE_COMPLETED`
> carries the real elapsed duration plus per-media counters.
> `LoomControlChannel` forwards both in `PIPELINE_RUN_COMPLETED`, and
> `ProcessorEndpoint` persists them via the new `PipelineRunTracker`, which
> enforces "first terminal verdict wins". `PipelineEndpointService.run` now
> registers a 60 s dispatch watchdog so an unacknowledged work order fails the
> run instead of stranding it.
>
> Two defects were found and fixed while implementing:
> - `PipelineRunTracker` initially used `store()`, which is **INSERT-only** on
>   the jOOQ DAOs — updating an existing row requires `update()`.
> - A work order that resolves **zero media** never executes and so never emits
>   `PIPELINE_COMPLETED`; that case is now closed out from the dispatch ack.
>
> The stats-scheduler lifecycle was also made per-run (cancellable handle rather
> than shutting the shared scheduler down) because the run-scoped counters
> require re-subscription to work. That is a partial overlap with Task 4, which
> still owns node `shutdown()`, idempotent executor shutdown, and timeout
> placement.
>
> Tests: `PipelineRunCompletionTest` (8, cortex), `PipelineRunStatusResolverTest`
> (11), `WorkOrderResultRegistryTest` (6), `PipelineRunCompletionEndpointTest`
> (8, loom).

**Argumentation Summary:** `POST /:uuid/run` creates a `pipeline_run` row at
status `RUNNING` and dispatches a work order, but nothing ever completes it.
Three independent breaks compound: (a) the run path never registers a callback
with `WorkOrderResultRegistry`, so `complete()` always logs "No registered
callback"; (b) `ProcessorEndpoint.handlePipelineRunCompleted` is a `TODO` that
only logs; (c) the `PIPELINE_RUN_COMPLETED` message Cortex sends carries no
`pipelineRunUuid` and a `durationMs` that is always 0, so even a correct handler
could not correlate it to a run. Every run in the UI shows as perpetually
`RUNNING`, and `finished`, `duration_ms`, and all four counter columns are dead.

**Improvement Summary:** Thread the run id through Cortex, populate the
completion message, and persist it on the Loom side.

```
CORTEX side:
1. PipelineWorkOrderHandler.handleRunPipeline already parses pipelineRunUuid
   but only echoes it. Store it so the executor's tracking events for this run
   carry it. Simplest approach: add a pipelineRunUuid field to
   PipelineTrackingEvent (nullable) and pass it into
   ReactivePipelineExecutor.execute(...) as run context.
2. ReactivePipelineExecutor emits PIPELINE_COMPLETED with the 4-arg constructor,
   which defaults durationMs to 0. Emit the real elapsed time and the aggregate
   counters (processed / failed / skipped / total media).
3. LoomControlChannel.forwardPipelineTrackingEvent builds PIPELINE_RUN_COMPLETED
   from {pipelineName, timestamp, durationMs, message}. Add pipelineRunUuid and
   the counters.

LOOM side:
4. Replace the TODO in ProcessorEndpoint.handlePipelineRunCompleted
   (search for "Implement pipeline run completion tracking"). Look up the run by
   pipelineRunUuid and update status, finished, duration_ms, media_count,
   success_count, failure_count, skipped_count, error_message via
   PipelineRunDao.
5. Decide status from the counters: all success -> SUCCESS; some failures ->
   PARTIAL; all failed -> FAILED. Do this behind a small helper so the mapping
   is testable in isolation.
6. In PipelineEndpointService.run, call
   workOrderResultRegistry.registerWithTimeout(workOrderId, ...) so a work order
   that never reports back transitions the run to FAILED instead of hanging at
   RUNNING forever. registerWithTimeout already exists and is unused.

Do NOT introduce a status enum here — that is Task 9. Keep using the String
vocabulary documented in V2.29__add_pipeline_run.sql for now.
```

**References:** [PIPELINE.md](PIPELINE.md) §12.3 ·
`ProcessorEndpoint.java`, `PipelineEndpointService.java`,
`WorkOrderResultRegistry.java`, `PipelineWorkOrderHandler.java`,
`LoomControlChannel.java`, `ReactivePipelineExecutor.java`

**Test Requirements:** Loom test that feeds a `PIPELINE_RUN_COMPLETED` frame and
asserts the run row transitions with correct counters. Test for the
counters→status mapping including the `PARTIAL` case. Test that the
`registerWithTimeout` path marks a run `FAILED`. Cortex test that
`PIPELINE_RUN_COMPLETED` carries a non-null run id and non-zero duration.

---

## Task 3: Register the remaining node types, and fail loudly on unknown ones

**Argumentation Summary:** `NodeDescriptorRegistry` advertises **29 kinds** to
the UI palette, but `PipelineNodeFactoryModule` registers only **6** with
`RegistryNodeFactory` (`filesystem-source`, `sha512`, `sha256`, `md5`,
`chunk-hash`, `thumbnail`).
The other 23 — `whisper`, `ocr`, `llm`, `facedetect`, `tika`, every `filter-*` —
resolve to `StubPipelineNode`, which logs and **returns success**. A user can
assemble a pipeline entirely from nodes that do nothing and watch it run green.
Silent success is worse than a hard failure here.

**Improvement Summary:** Register the real nodes, and make unregistered types an
explicit error instead of a fake success.

```
1. In cortex/cli/.../dagger/PipelineNodeFactoryModule.java, extend the
   registration block (currently 5 factory.register(...) lines) to cover every
   node module listed in NodeCollectionModule: fingerprint, facedetect, ocr,
   tika, whisper, llm, captioning, quality, consistency, dedup,
   scene-detection.
   - Each needs its Dagger-provided node injected and wrapped via the existing
     adapt(...) helper.
   - OBSOLETE (kept for the record): the adapter id-override rule. LoomNode read
     ctx.upstreamOutput("md5sum", "md5") while MD5Node.name() is "md5", so the
     MD5 adapter had to be built with the explicit id "md5sum". Both
     upstreamOutput and the loom node are gone; nodes bind by typed port and a
     node id cannot affect data delivery. See NODE_DATA_TYPES.md.
   - adapt(...) must call setSyncToLoom(...) where appropriate —
     CortexNodeAdapter hardcodes syncToLoom=false in its super() call.

2. Register the 8 concrete filter nodes from pipeline-core under their
   descriptor kinds (filter-mimetype, filter-date, filter-duplicate,
   filter-blacklist, filter-quality, filter-threshold,
   filter-asset-attribute, and the sampling filter).

3. Note filter-size is an advertised descriptor kind with NO production
   implementation — cortex/processor's PipelineIntegrationTest defines a local
   SizeFilterNode with the comment "Kept local because no production
   SizeFilterNode exists yet". Either promote that class into
   pipeline-core/.../node/filter/ or remove the filter-size descriptor.
   Do not leave it advertised-but-absent.

4. Change the StubPipelineNode fallback in LoomPipelineLoader: log at ERROR and
   return NodeResult.failed(id, 0, "No implementation registered for node type
   '<type>'") instead of success. A pipeline referencing an unimplemented node
   must go red, not green. Gate this behind a loader flag if a
   permissive mode is genuinely needed for development.

5. Add a startup consistency check that logs every descriptor kind with no
   registered producer, so the gap is visible at boot rather than at run time.
```

**References:** [PIPELINE.md](PIPELINE.md) §8, §12.4 ·
`PipelineNodeFactoryModule.java`, `RegistryNodeFactory.java`,
`LoomPipelineLoader.java`, `NodeCollectionModule.java`

**Test Requirements:** A test asserting every kind in `NodeDescriptorRegistry`
has a registered producer (or is on an explicit allow-list of known-unimplemented
kinds) — this keeps the two registries from drifting again. A test that an
unknown node type produces a `FAILED` result. Extend the per-node
`*NodePipelineTest` suite to cover the newly registered nodes.

---

## Task 4: Fix executor lifecycle — single-use scheduler and missing node shutdown

**Argumentation Summary:** `ReactivePipelineExecutor.execute(...)` calls
`statsScheduler.scheduleAtFixedRate(...)` on every invocation, and
`statsScheduler.shutdown()` on the first `doOnComplete`. A **second `execute()`
on the same instance throws `RejectedExecutionException`**. Since Dagger
provides the executor as a `@Singleton`, any Cortex process that runs two
pipelines hits this. Separately, `node.initialize()` is called per execute but
`node.shutdown()` is **never called anywhere**, and `CortexNodeAdapter` does not
override it — so nodes holding native handles (facedetect, whisper, OpenCV) leak
for the process lifetime.

**Improvement Summary:** Make the executor reusable and give nodes a real
shutdown path.

```
1. In ReactivePipelineExecutor:
   - Start the stats scheduler once, lazily, guarded so repeated execute()
     calls do not re-schedule. Track active-run count; stop emitting when it
     drops to zero but do NOT shut the scheduler down.
   - Only shut the scheduler down in shutdown(). Make shutdown() idempotent —
     it currently shuts the scheduler down a second time.
   - Add a regression test that calls execute() twice on one instance.

2. Node lifecycle:
   - Call node.shutdown() for every pipeline node from
     PipelineExecutor.shutdown().
   - Override shutdown() in CortexNodeAdapter to delegate to
     wrappedNode.shutdown() (initialize() already delegates).
   - Guard against double-initialize: initialize() runs on every execute(),
     which is wrong for stateful nodes. Track initialized state per node.

3. While in this file, fix the precision losses noted in PIPELINE.md §6.3:
   - onErrorReturn discards the failed node's duration (always 0) — capture the
     real elapsed time.
   - Timeout classification uses message.contains("timeout") — rely on the
     TimeoutException type instead.
   - Apply the timeout INSIDE the semaphore-holding region, or release the
     permit on timeout, so a hung node stops starving its peers.
```

**References:** [PIPELINE.md](PIPELINE.md) §6.2, §6.3 ·
`ReactivePipelineExecutor.java`, `CortexNodeAdapter.java`

**Test Requirements:** Test calling `execute()` twice on one executor. Test that
`shutdown()` is idempotent. Test that node `shutdown()` is invoked, including
through `CortexNodeAdapter`. Test that a timed-out node releases its semaphore
permit. Test that a failed node reports a non-zero duration.

---

## Task 5: Wire result caching in production and fix cache type fidelity

**Argumentation Summary:** The cache layer is fully implemented and entirely
unused. `AbstractPipelineNode.cacheProvider` is `null` unless
`setCacheProvider` is called, **no production code calls it**, and there is no
Dagger provider for any `NodeCacheProvider` — so caching is test-only despite
being a headline capability. Worse, both persistent caches serialise via
`XAttrNodeCache`'s line-based `key=value` format, so **all values come back as
`String`**: a cached `filter_passed` returns `"true"` rather than `true`,
silently breaking branch routing on a cache hit. `clear()` is an unimplemented
stub in both.

**Improvement Summary:** Provide a configurable cache via Dagger, and switch the
persistent caches to a format that preserves types.

```
1. Replace the key=value serializer shared by XAttrNodeCache and
   SidecarFileNodeCache with JSON (Jackson is already a pipeline-core
   dependency; add it to pipeline-common or move the serializer). This fixes
   type loss AND the fragility around values containing '=' or newlines.
   Include a schema version field so old cache entries can be detected and
   discarded rather than misread.

2. Implement clear() in both caches:
   - XAttrNodeCache: currently logs a warning and does nothing. Also fix
     invalidate(), which writes "" instead of removing the attribute.
   - SidecarFileNodeCache: recursive delete under the configured base
     directory, matching only the cache file pattern. Do not touch unrelated
     files.

3. Add a Dagger provider for NodeCacheProvider driven by CortexOptions
   (none / heap / xattr / sidecar / layered), and have the node factory call
   setCacheProvider(...) on adapted nodes. Default to none so behaviour does
   not change silently.

4. pipeline-common has NO test directory at all. Create one and cover all five
   cache impls, DefaultPipelineEventBus, and DefaultLoomBulkSyncCollector.
   Specifically assert that a NodeResult with boolean/numeric/list outputs
   survives a put/get round trip through each persistent cache — that is the
   regression this task exists to prevent.
```

**References:** [PIPELINE.md](PIPELINE.md) §4.10 ·
`cortex/pipeline-common/.../cache/`, `AbstractPipelineNode.java`,
`CortexOptions.java`

**Test Requirements:** New `pipeline-common` test module. Type-fidelity
round-trip test per persistent cache. `clear()` and `invalidate()` tests
including that unrelated files survive. Executor test that a cache hit on a
filter node still routes the PASS branch correctly.

---

## Task 6: Close the Cortex test blind spots

**Argumentation Summary:** The untested code is precisely where the defects are.
`pipeline-common` has no test directory. `LoomPipelineLoader` has no test — one
would have caught Task 1 immediately. `PipelineTaskHandler`,
`LoomControlChannel`, `RegistryNodeFactory`, and `CortexNodeAdapter` have no
direct tests. None of the 8 concrete filter nodes is tested. No test calls
`execute()` twice, which is why Task 4's defect went unnoticed.

**Improvement Summary:** Add targeted tests for the untested integration seams,
using the existing test infrastructure.

```
Use the existing base classes — do not roll new harnesses:
  AbstractPipelineNodeTest (pipeline-core test-jar) for node-level tests
  StubLoomMedia.ofBytes(tempDir, name, content) for media fixtures
  CapturingNode to assert a downstream node saw a specific upstream output
  PipelineAssertions / PipelineResultAssert for assertions (never raw
    assertEquals on maps and states)

Priority order:
1. LoomPipelineLoaderTest — covered by Task 1, listed here for completeness.
2. Filter node tests: one per concrete filter in
   pipeline-core/.../node/filter/. Assert both the filter_passed output AND
   downstream PASS/REJECT routing through the executor.
3. PipelineTaskHandlerTest — SOURCE_TASK enumeration + SOURCE_ITEMS/
   SOURCE_COMPLETE streaming, NODE_TASK execution + NODE_TASK_RESULT, and the
   failure path. (Superseded: the old WorkOrder command handler is gone — see
   PIPELINE.md §12.)
4. CortexNodeAdapterTest — direct unit test of state mapping
   (SUCCESS->COMPLETED, SKIPPED->SKIPPED, FAILED->FAILED), null-result
   handling, upstream output conversion, and id override.
5. RegistryNodeFactoryTest — registered type resolves, unregistered type
   behaves per Task 3.
6. PipelineDeserializer test that parses hand-written foreign JSON, not just
   round-trip output.
```

**References:** [PIPELINE.md](PIPELINE.md) §14 ·
`cortex/pipeline-core/src/test/.../test/`

**Test Requirements:** This task is tests. Aim for coverage of the seams listed
above rather than a coverage percentage.

---

## Task 7: Complete the Java REST client for run and version operations

> ✅ **The client methods landed on 2026-07-26** alongside the CLI (see
> [../cli/CLI_PLAN.md](../cli/CLI_PLAN.md)). `PipelineMethods` now has
> `runPipeline`, `pausePipelineRun`, `resumePipelineRun`, `cancelPipelineRun`,
> `listPipelineVersions`, `loadPipelineVersion` and `restorePipelineVersion`,
> plus a new `InfoMethods` (`restInfo`, `me`) and the previously missing
> `LoomHttpClientImpl.Builder.setPathPrefix`.
>
> **Still open:** the endpoint tests this was meant to unblock. `CliIntegrationTest`
> exercises `runPipeline` and the run-control routes end to end, and
> `PipelineRunPauseEndpointTest` covers pause/resume, but the **versioning**
> surface (create → update → update, restore, 404 on an unknown version) is still
> untested, and so is the `SOURCE_TASK` payload shape on a successful dispatch.

**Argumentation Summary:** `PipelineMethods` in `loom-client/common` exposes
only load/create/update/list/listRuns/delete. It lacks `run`, `listVersions`,
`loadVersion`, and `restoreVersion`. This is why **no Java test touches the
versioning REST surface at all** — the only coverage is mocked Playwright specs,
which do not exercise the server. A whole shipped API surface is untested
because the test client cannot reach it.

**Improvement Summary:** Add the four missing client methods and the Java tests
they unblock.

```
1. Extend loom-client/common/.../method/PipelineMethods.java with:
     runPipeline(UUID, PipelineRunRequest) -> PipelineRunResponse
     listPipelineVersions(UUID, PagingParameters) -> PipelineVersionListResponse
     loadPipelineVersion(UUID, int version) -> PipelineResponse
     restorePipelineVersion(UUID, int version, PipelineVersionRestoreRequest)
       -> PipelineResponse
   Follow the existing method conventions in that interface. Implement in
   LoomHttpClient alongside the current pipeline methods.

2. Add endpoint tests using the new methods:
   - create -> update -> update, assert version numbers 1, 2, 3 and that the
     older versions retain their original definitions (versions are immutable)
   - restore v1, assert a NEW version is created (copy-forward, HTTP 201) and
     that v1 itself is unchanged
   - loadVersion for a nonexistent version -> 404
   - POST /run with no processor registered -> 503 and assert NO pipeline_run
     row is created
   - POST /run with a registered processor -> 202, and assert the dispatched
     `SOURCE_TASK` (`SourceTaskMessage`) payload shape
   - DELETE /:uuid removes versions and runs

3. While here: PipelineDaoTest/PipelineVersionDaoTest exercise only the generic
   CRUD harness. Add cases for loadWithLatestVersion, loadByUuids,
   loadByPipelineAndVersion, and loadLatestByPipeline.
```

**References:** [PIPELINE.md](PIPELINE.md) §11, §14.4 ·
`PipelineMethods.java`, `LoomHttpClient`, `PipelineEndpointService.java`

**Test Requirements:** As enumerated above. These tests must run in the standard
endpoint-test harness (`LoomCoreTestExtension`), not as integration tests, so
they run on every build.

---

## Task 8: Consolidate pipeline validation and add a validation endpoint

**Argumentation Summary:** The same validation rules (node id regex, uniqueness,
edge references, cycle detection via Kahn's) exist in **three** independent
implementations: `PipelineModelValidator` in loom-shared, `PipelineValidationService`
in loom rest, and `validatePipeline()` in `PipelineEditor.tsx` — each with its
own cycle-detection code. Only the middle one checks node types against the
descriptor registry, and only it is tested. They will drift. Separately, there
is no `POST /api/v1/pipelines/validate`, so a client cannot validate a draft
without persisting it (requirement R11).

**Improvement Summary:** Make the server the single authority, expose it as an
endpoint, and have the UI call it instead of duplicating the logic.

```
1. Add POST /api/v1/pipelines/validate to PipelineEndpoint:
   - Accepts a definition JsonObject, returns a structured result
     { valid: boolean, errors: [{ code, message, nodeId?, edgeId? }] }.
   - Gate on CREATE_PIPELINE (validating a draft is an authoring action).
   - Remember: secured paths in PipelineEndpoint are enumerated INDIVIDUALLY so
     the events WS escapes the auth chain. Add the new path to that list or it
     will be unauthenticated.

2. Refactor PipelineValidationService to return the structured error list
   rather than throwing on the first problem, so a user sees all errors at once.
   Keep a thin throwing wrapper for the existing create/update call sites.

3. Delete PipelineModelValidator's duplicated structural checks — it is untested
   and unwired for these rules. Keep only what rest-model genuinely needs.

4. In loom-ui, replace validatePipeline() in PipelineEditor.tsx with a debounced
   call to the new endpoint, keeping only cheap synchronous checks (empty graph,
   obviously malformed id) client-side for editor responsiveness. Removing the
   client's Kahn's implementation is the point of the task.

5. While in pipelines.ts: listPipelineVersions and listPipelineRuns return []
   on ANY non-OK response, so a server failure is indistinguishable from "no
   data". Surface real errors to the user.
```

**References:** [PIPELINE_REQUIREMENTS.md](PIPELINE_REQUIREMENTS.md) R11, A-VA1,
A-VA2 · [PIPELINE.md](PIPELINE.md) §11.2 · `PipelineValidationService.java`,
`PipelineModelValidator.java`, `PipelineEditor.tsx`, `pipelines.ts`

**Test Requirements:** Endpoint tests for valid and invalid definitions,
asserting **all** errors are returned rather than just the first. Reuse the 24
existing `PipelineValidationServiceTest` cases against the new endpoint. UI test
that a server validation error blocks save.

---

## Task 9: Type the pipeline run status

**Argumentation Summary:** `pipeline_run.status` is a free-form `String` in the
DB, the DAO model, and `PipelineRunRecord`. The vocabulary — `PENDING, RUNNING,
SUCCESS, FAILED, PARTIAL, CANCELLED` — exists **only as a comment** in
`V2.29__add_pipeline_run.sql`. Nothing prevents a typo from being persisted, and
the UI has no reliable set to switch on.

**Improvement Summary:** Introduce an enum and use it across the stack.

```
1. Add PipelineRunStatus enum to loom-shared/rest-model alongside the other
   pipeline model types, with the six documented values.
2. Use it in the PipelineRun DAO model and PipelineRunRecord. Keep the DB
   column as VARCHAR — a Postgres enum would need a migration for every new
   value — but parse/serialise through the Java enum at the boundary.
3. Reject unknown values on read with a clear error rather than silently
   passing a bad string through to the UI.
4. Do this AFTER Task 2, which is what first writes anything other than
   "RUNNING". Doing it before means changing the same code twice.

Related, same area: NodeState declares PENDING and RUNNING but no code ever
assigns them. Either emit them from the executor (a node is PENDING before its
Single subscribes, RUNNING once the semaphore is acquired) or remove them.
Leaving unreachable states in a public enum misleads every consumer.
```

**References:** [PIPELINE.md](PIPELINE.md) §10.1 ·
`V2.29__add_pipeline_run.sql`, `PipelineRunRecord.java`, `NodeState.java`

**Test Requirements:** Round-trip test for every enum value through DAO and REST.
Test that an unrecognised status string is rejected with a clear message.

---

## Task 10: Remove or implement the advertised-but-absent features

**Argumentation Summary:** Several capabilities are advertised in APIs,
descriptors, and DTOs but do nothing. Each one costs an agent or contributor
real time to discover, and misleads users of the UI. They should be either
implemented or deleted — not left in place.

**Improvement Summary:** Resolve each of these one way or the other.

```
Decide implement-or-delete for each:

1. retryFailed — advertised as a node parameter by 10 descriptor providers,
   never read by the executor. Either implement retry in
   ReactivePipelineExecutor (with backoff and a max-attempts cap) or strip the
   parameter from all 10 descriptors.

2. Reactive-operator node API — PipelineNode.apply(), isPartitioning(),
   partition(), MediaContext, PartitionedFlowable. All unreachable: the
   executor calls only process(). Two parallel execution designs coexist and
   only one runs. Recommend DELETING the dead one; if it is a planned
   direction, document that explicitly in PIPELINE.md and add a test that
   exercises it.

3. PipelineDeserializer.NodeResolver — stored via setNodeResolver, never read.
   Delete it, or wire it so PipelineDeserializer can produce executable
   pipelines. Note LoomPipelineLoader uses its own parser plus NodeFactory, so
   NodeResolver is likely redundant with NodeFactory — consolidate on one.

4. PipelineFilter / MediaFilter SPI — no production references.
   DefaultPipelineManager.resolve(LoomMedia) ignores its media argument
   (contradicting its own javadoc) and has zero callers. Delete the SPI and fix
   the javadoc, or wire it as a pipeline-level pre-filter.

5. mediaUuids — accepted by PipelineRunRequest and now resolved to stored binary
   paths in PipelineEndpointService.sourceOptions (fed into the SOURCE_TASK
   options). Largely addressed; verify UI coverage of the single-asset vs
   multi-asset paths.

6. Processor capability is hardcoded to CPU in PipelineEndpointService.run.
   Derive the required capability from the pipeline's node types so a
   GPU-dependent pipeline is dispatched to a GPU processor.

7. NODE_STATS is advertised in the events list of all 14 descriptor providers,
   but stats are emitted generically by the executor, not per node. Either emit
   per-node business metrics or remove the advertisement.
   Also: NODE_STATS.pending is hardcoded to 0 with an acknowledging comment.

8. javax.inject annotations on PipelineSerializer/PipelineDeserializer with no
   javax.inject dependency declared and no Dagger module providing them. Remove
   the annotations or add the wiring.

9. PipelineSerializer.resolveNodeType detects filters by walking the class
   hierarchy string-matching getSimpleName().contains("FilterNode"). Replace
   with an interface check or an explicit isFilter() on the node.
```

**References:** [PIPELINE.md](PIPELINE.md) §4.6, §4.8, §8, §9.1, §12.3

**Test Requirements:** For anything implemented, a test proving it works. For
anything deleted, a compile-clean build plus a check that no descriptor or DTO
still advertises it.

---

## Task 11: Fill remaining persistence and API gaps

**Argumentation Summary:** A cluster of smaller, independent gaps that do not
block the feature but limit its reach and correctness.

**Improvement Summary:** Address as capacity allows; none of these depends on
another.

```
1. PipelineDaoImpl.loadWithLatestVersion does NOT load the version — it is a
   plain selectFrom(PIPELINE).where(uuid), and every caller separately calls
   pipelineVersionDao.loadLatestByPipeline(...). Either implement the join the
   name promises or rename the method. The current name actively misleads.

2. PipelineDaoImpl.createPipeline(UUID userUuid, String name) ignores `name` —
   correct post-refactor since name lives on the version, but the parameter is
   dead weight on the interface. Remove it.

3. PipelineEndpointService.delete loops deleting versions before the pipeline
   even though the FK is ON DELETE CASCADE. Drop the loop, or document why the
   explicit delete is needed (e.g. for audit events).

4. loom/db/memory has NO pipeline DAOs, so the in-memory backend cannot serve
   pipelines. Either implement MemPipelineDao / MemPipelineVersionDao /
   MemPipelineRunDao, or document the limitation explicitly and make the
   backend fail fast with a clear message on pipeline access.

5. ~~ProcessorRegistry.dispatchWorkOrder builds its envelope by string
   concatenation.~~ Superseded: `dispatchWorkOrder` was removed with the WorkOrder
   subsystem; run dispatch now serialises typed `SourceTaskMessage` / `NodeTask`
   messages through `ProcessorRegistry.send()`. See PIPELINE.md §12.

6. ProcessorEndpoint returns a hand-built JSON 404 body for an unknown
   processor instead of the standard error model, and its lookup does a linear
   scan computing toResponse twice per candidate.

7. PipelineEventBroadcaster.Subscriber takes a queueCapacity constructor arg
   that is never stored; DEFAULT_QUEUE_CAPACITY = 1024 is dead. Either
   implement the bounded per-subscriber queue or remove both.

8. No per-node stats persistence: NODE_STATS events have nowhere to land.
   Add migration V2.31__add_pipeline_node_stats.sql
   (pipeline_uuid, node_id, ts, active, pending, processed, failed) with a
   (pipeline_uuid, node_id, ts DESC) index, handle NODE_STATS in
   ProcessorEndpoint.handlePipelineEvent, and expose
   GET /api/v1/pipelines/:uuid/nodes/:nodeId/stats?from=&to=.
   Consider retention — this table grows fast at a 500 ms emission interval.

9. No pipeline gRPC surface despite a wired and running gRPC server
   (asset, health, reflection are registered). Add pipeline.proto if gRPC is
   intended to reach parity with REST; otherwise note the intentional omission
   in PIPELINE.md.

10. Server-side default layout: when a definition's nodes lack position.x/y,
    compute a layered left-to-right layout server-side so non-editor clients
    do not each reimplement it.
```

**References:** [PIPELINE.md](PIPELINE.md) §10.2, §11, §12 ·
[PIPELINE_REQUIREMENTS.md](PIPELINE_REQUIREMENTS.md) §3.2, §3.7

**Test Requirements:** Per item. Item 1 needs a DAO test asserting the version
is actually populated. Item 4 needs the memory-backend contract tests in
`loom/db/api-test` to pass or explicitly skip. Item 8 needs a migration test and
an endpoint test.

---

## Progress Assessment

- [ ] **Task 1** — Unify the pipeline definition schema (**blocking**)
- [x] **Task 2** — Make pipeline runs report completion (done 2026-07-18)
- [ ] **Task 3** — Register remaining node types; fail loudly on unknown (**blocking**)
- [ ] **Task 4** — Fix executor lifecycle (single-use scheduler, node shutdown)
- [ ] **Task 5** — Wire result caching; fix cache type fidelity
- [ ] **Task 6** — Close Cortex test blind spots
- [ ] **Task 7** — Complete the Java REST client for run/version operations
- [ ] **Task 8** — Consolidate validation; add a validation endpoint
- [ ] **Task 9** — Type the pipeline run status
- [ ] **Task 10** — Remove or implement advertised-but-absent features
- [ ] **Task 11** — Fill remaining persistence and API gaps

### Suggested sequencing

Tasks 1 → 2 → 3 restore the feature end-to-end. **Task 2 is done**, so run
reporting works — but it currently reports on a graph that Task 1 has not yet
fixed, meaning a UI-authored pipeline still reports a successful run over just
its source node. Task 1 is now the single blocking item; Task 3 makes the nodes
real.

Task 6's loader test is really part of Task 1 and should not be deferred.

Task 4 is independent and cheap — do it alongside Task 1 since both touch the
executor path. Note Task 2 already made the stats scheduler per-run, so what
remains in Task 4 is node `shutdown()`, idempotent executor shutdown, and
moving the timeout inside the semaphore.

Tasks 7 and 8 unblock testing and should precede any further REST work.
Task 9 must follow Task 2. Tasks 5, 10, and 11 are parallelisable cleanup.
