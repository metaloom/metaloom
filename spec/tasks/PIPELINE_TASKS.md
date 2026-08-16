# MetaLoom Pipeline — Task List

> Open work items for the pipeline feature, re-audited against the code on 2026-08-11 at
> `8c153347`. Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [PIPELINE.md](../features/pipeline/PIPELINE.md) (technical spec) ·
> [PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md) (requirement → status) ·
> [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) (ports, cardinality, fan-out) ·
> [PIPELINE_VALIDATION.md](../features/pipeline/PIPELINE_VALIDATION.md) (the one validator) ·
> [PIPELINE_FLOW.md](../features/pipeline/PIPELINE_FLOW.md) (item lifecycle)
>
> **This file tracks OPEN work only.** A task that is done is deleted, not archived — the code and
> the spec are the record of what landed. Task numbers are never reused: **1, 2, 3, 4, 5, 6, 7, 8, 9
> and 12 are retired.** [CLI_PLAN.md](../plans/CLI_PLAN.md) still cites Task 7 and
> [WORKFLOW_TRASH.md](../workflows/WORKFLOW_TRASH.md) cites Task 14; both citations resolve here.
>
> **Closed by this audit** — Task 6 (all four test blind spots landed:
> `loom/pipeline/src/test/resources/pipeline/reference-definition.json` +
> `PipelineGraphParserReferenceDefinitionTest`, `LoomControlChannelTest`, a test tree under
> `cortex/pipeline-common`, and the `PipelineDaoTest` / `PipelineVersionDaoTest` cases) and Task 12
> (every production `PipelineGraphParser` is now constructed with the descriptor registry —
> `PipelineEndpointService:128`, `PipelineRunRecovery:87`, `NodeRunService:136`,
> `PipelineAuthoringService:258`, `PipelineValidationService:300`).
>
> **Ordering.** None of these block a release; severity order for a reader picking up work is:
>
> 1. **Task 15** — armed breakpoints are lost on restart, silently. The only correctness defect here
>    that a user can hit without editing JSON.
> 2. **Task 16** — a renamed-port edge inside an affinity segment is not carried locally; wrong data
>    reaches a node rather than no data.
> 3. **Task 10** — ten advertised-but-inert surfaces; each costs a contributor real time, two mislead
>    users of the editor.
> 4. **Task 11** — eight independent persistence/API defects.
> 5. **Task 14** — three demo pipelines still route everything through `other`; **unblocked**, the
>    parser half landed.
> 6. **Tasks 13, 17** — one missing meter, and the spec arrears.
>
> **Owned elsewhere — do not restate.** The `ctx.failure(...).next()` defect (16 sites, 14 node
> classes) is [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 17**. The `asset_node_result` provenance
> record (`origin` hard-coded `COMPUTED`, no `runUuid`/`taskUuid` on `NodeResultCreateRequest`) is
> **Task 18** there. Re-baselining `WORKFLOWS.md` and `METALOOM_CONTEXT.md` is **Task 16** there —
> Task 17 below covers only the `spec/features/pipeline/` files, which that sweep does not touch.

---

## A. Open tasks at a glance

| # | Task | State | loom-ui work? |
|---|---|---|---|
| 15 | Restore armed breakpoints on run recovery | 🔴 **OPEN** — silent loss on restart | no (server-side) |
| 16 | Carry input bindings into `SegmentNode` | 🔴 **OPEN** — segment-internal edges match by port id | no |
| 10 | Retire the remaining dead surfaces | 🔴 **OPEN** — ten independent items | **yes** (items 1, 3) |
| 11 | Fill the remaining persistence and API gaps | 🔴 **OPEN** — eight independent items | **yes** (items 6, 7) |
| 14 | Give the demos a real MIME filter | 🔴 **OPEN** — parser fixed, three demos still route via `other` | no |
| 13 | Register the last engine meter | 🟡 **Partly done** — four of five meters landed | no |
| 17 | Re-baseline the pipeline specs against the code | 🔴 **OPEN** — §17 lists closed items as open | no |

---

## Task 15: Restore armed breakpoints when a run is recovered

**Argumentation Summary:** Breakpoints are run state that lives **only in the engine**.
`PipelineEndpointService:744` says so explicitly ("Answered from the live engine, which is the only
thing that knows"), and `PipelineRunEngineFactory:152` arms them from `EngineConfig.breakpoints()`
before the engine starts. `PipelineRunRecovery`
(`loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineRunRecovery.java`)
rebuilds items, tasks and the graph after a restart and never mentions breakpoints — the string does
not appear in the file. A `RUNNING` or `PAUSED` run recovered after a Loom restart therefore comes
back with **none armed**, the UI's `loadPipelineRunBreakpoints` reports an empty set, and the items
that were being held stream straight through. Nothing logs it. This is the debugging feature's one
durability hole, and [PIPELINE.md §17](../features/pipeline/PIPELINE.md) already lists it as
serious-and-open.

**Improvement Summary:** Persist the armed set in `pipeline_run.meta` — JSONB since `V2.29`, and
already used for `meta.definition` by `ADHOC` runs — and re-arm from it on recovery.

```
1. loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEndpointService.java:
   every write to the armed set (the PUT /breakpoints handler around :764 and the initial set
   passed at run start) also writes the node ids to pipeline_run.meta under a `breakpoints` key.
   No Flyway migration: the column exists (V2.29__add_pipeline_run.sql:21) and PipelineRun
   already exposes getMeta()/setMeta(). Follow how ADHOC stores meta.definition (V2.83).
   Do NOT persist the *held* executions - those are per-item engine state and are correctly
   rebuilt from pipeline_node_task.
2. PipelineRunRecovery.java: after the graph is parsed and before the engine is started, read
   meta.breakpoints, validate each id against the recovered graph the same way
   PipelineEndpointService:893 does, drop ids the definition no longer contains (a restored
   version can have renamed a node), and pass the survivors through
   PipelineRunEngineFactory.EngineConfig.forRun(...) so they are armed before the first item
   moves - the ordering PipelineRunEngineFactory:150 depends on.
3. Log at INFO how many breakpoints were re-armed and at WARN each id dropped as unknown, so a
   silently emptied set becomes visible in the log.
4. No loom-ui change is required: loom-ui/src/features/pipeline/PipelineEditor.tsx:3819 already
   refetches the set with loadPipelineRunBreakpoints when a live run is selected, so a
   server-side restore surfaces on its own. Verify that end with the existing mocked spec
   rather than adding UI code.
```

**References:** [PIPELINE.md §6.4a, §17](../features/pipeline/PIPELINE.md) ·
`V2.29__add_pipeline_run.sql` (the `meta` JSONB column) · `V2.83__adhoc_pipeline_run.sql` (the
`meta.definition` precedent) · `PipelineRunRecovery.java`, `PipelineRunEngineFactory.java`,
`PipelineEndpointService.java`
**Test Requirements:** A `loom/core` endpoint test in the shape of
`PipelineRunBreakpointEndpointTest`: arm two breakpoints, drop and rebuild the engine through
`PipelineRunRecovery`, assert `GET /breakpoints` still reports both and that a held item is still
held. A second case where the recovered definition no longer contains one of the ids asserts it is
dropped with a WARN rather than failing recovery. `./setup-pool.sh` first, then
`mvn -pl loom/core test -Dtest=PipelineRunBreakpointEndpointTest`. UI regression check from
`loom-ui/`: `./node_modules/.bin/playwright test e2e/pipeline-breakpoints-mocked.spec.ts`.

---

## Task 16: Carry input bindings into `SegmentNode` so a renamed-port edge survives segmentation

**Argumentation Summary:** `loom-shared/pipeline-model/src/main/java/io/metaloom/loom/pipeline/model/SegmentNode.java`
carries `nodeId, nodeKind, blocking, options, dependencies` — and **no `InputBinding`**. The worker
therefore resolves a segment-internal edge by port *name*:
`cortex/node-runtime/src/main/java/io/metaloom/cortex/runtime/SegmentTaskRunner.java:156-167`
merges each declared dependency's outputs into the dependent's visible inputs and says so in its own
javadoc — "Port ids still do the matching within a declared edge, so an edge whose two ends are named
differently is not carried locally." The engine has `InputBinding`
(`loom/pipeline/src/main/java/io/metaloom/loom/pipeline/engine/InputBinding.java`) and honours
`sourcePort`→`targetPort` exactly; a segment does not. Consequence: two nodes that the editor let you
wire `image_out` → `media_in` run correctly when dispatched separately and receive **nothing** on that
port once affinity puts them in one segment — or worse, silently pick up a same-named port from
another dependency. The affinity path is supposed to be "engine-identical local skip semantics"
([PIPELINE.md §7.3](../features/pipeline/PIPELINE.md)); this is the one place it is not.

**Improvement Summary:** Ship the bindings the engine already computed down to the worker and resolve
by binding, not by name.

```
1. loom-shared/pipeline-model/.../SegmentNode.java: add a bindings field carrying, per
   dependency, the sourcePort -> targetPort pairs (the same shape as
   loom/pipeline/.../engine/InputBinding). Keep it Jackson-optional and default it to an empty
   list so an older worker still deserialises a newer segment task.
2. loom/pipeline/src/main/java/io/metaloom/loom/pipeline/engine/PipelineRunEngine.java:1539 is
   the one production site that builds SegmentNode instances - populate the bindings there from
   the graph edges that are internal to the segment. The engine already has them; this is
   plumbing, not new logic.
3. cortex/node-runtime/.../SegmentTaskRunner.java visibleInputs(...) (:161): when a node
   carries bindings, resolve each declared dependency's outputs THROUGH them - read the
   source port, write the target port - instead of putAll'ing the producer's map. Fall back
   to today's name matching only when the list is empty, so a segment task from an older
   engine keeps working. Delete the javadoc caveat at :156-160 in the same change.
4. No loom-ui change: segmentation is invisible to the editor, which already writes
   sourcePort/targetPort on every edge.
```

**References:** [PIPELINE.md §7.3](../features/pipeline/PIPELINE.md) (affinity segmentation) ·
[../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) (port model) ·
`SegmentNode.java`, `SegmentTaskRunner.java`, `InputBinding.java`
**Test Requirements:** A `loom/pipeline` test that a two-node segment wired `a.image_out ->
b.media_in` produces a `SegmentTask` whose second `SegmentNode` carries that binding, and a
`cortex/node-runtime` test that `SegmentTaskRunner` delivers the payload on `media_in` (today it
arrives on `image_out` or not at all). Add a same-name-different-meaning case — two dependencies both
emitting `is_complete` — asserting the binding picks the right one. Extend
`loom/services/rest/src/test/.../SegmentProtocolSerdeTest` with a round trip proving the new field is
optional and backward compatible. `mvn -pl loom/pipeline,cortex/node-runtime,loom/services/rest test`.

---

## Task 10: Retire the remaining dead surfaces

**Argumentation Summary:** Each item below is advertised in an API, descriptor or config and does
nothing. Every one costs a contributor or agent real time to discover, and two of them mislead users
of the editor. All ten were re-confirmed against the code at `8c153347`.

**Improvement Summary:** Decide implement-or-delete for each, one PR per item.

```
 1. Processor capability is hardcoded to CPU. WebSocketNodeDispatcher.java:57 and :85 pass
    ProcessorCapability.CPU; so do PipelineEndpointService.unsupportedNodeKinds (:298) and
    PipelineAuthoringService.java:281. NodeSpec (cortex/api/.../node/spec/NodeSpec.java) has no
    capability field at all, so nothing could derive it today. Add one, harvest it into the
    descriptor, and pass it through the three call sites so a GPU-dependent graph is not placed
    on a CPU-only worker.
    ⟶ loom-ui step: add the new field to the NodeDescriptor interface in
      loom-ui/src/types/nodeDescriptors.ts, and show it in the palette entry built by
      loom-ui/src/features/pipeline/nodePicker.ts (it already models availability; a GPU kind
      with no GPU worker must read as unavailable, not merely offline). Regenerate
      loom-shared/node-model/src/main/resources/node-descriptors.json and the two copies under
      website/static/ and website/dist/ - install the cortex node modules BEFORE the harvest or
      it reads a stale jar.

 2. Node lifecycle: PipelineNode.initialize()/shutdown()
    (cortex/pipeline-api/.../api/node/PipelineNode.java:171,177) have no production caller.
    CortexNodeAdapter.java:85 overrides initialize() and delegates, but only tests invoke it,
    and shutdown() is invoked by nothing anywhere. Nodes holding native handles (facedetect,
    whisper, OpenCV) are constructed per kind and never released. Either call both around the
    node's lifetime in cortex/node-runtime, or delete the two methods. Do not leave them as
    decoration.

 3. NODE_STATS is in NodeSpecHarvester.STANDARD_EVENTS (:59), so every descriptor advertises
    it, but stats are emitted by RunStatsAggregator on the Loom side, not per node. Remove the
    advertisement or make it mean something.
    ⟶ loom-ui note: NodeDescriptor.events (loom-ui/src/types/nodeDescriptors.ts:152) is read
      by no UI code today - if the field is being removed, remove it there too rather than
      leaving a type that describes a field the server stopped sending.

 4. PipelineFilter and MediaFilter (cortex/pipeline-api/.../api/filter/) have zero references
    outside their own package. Delete both. ⚠️ FilterBranch lives in the same package and is
    LIVE (PipelineNode:93,130 and AbstractPipelineNode:35,102,106,120) - do not delete the
    directory wholesale.

 5. LoomBulkSyncCollector / DefaultLoomBulkSyncCollector / LoomBulkSyncWriterImpl - wired in
    CortexBindModule and flushed at shutdown by CortexImpl, but collect(...) is called only
    from DefaultLoomBulkSyncCollectorTest and CortexImplShutdownFlushTest. Asset write-back
    happens on the Loom side via DaoAssetSink. Delete the Cortex path, or document why it is
    kept - it is the only REST result path, which is the whole of the deviation recorded
    against R2 in ../features/pipeline/PIPELINE_REQUIREMENTS.md.

 6. PipelineEventBroadcaster.Subscriber (:276) takes a queueCapacity constructor arg that is
    never stored; DEFAULT_QUEUE_CAPACITY = 1024 (:45) is passed at :106 and dropped.
    Backpressure is purely writeQueueFull(). Remove both or implement the bounded queue.

 7. CortexOptions.maxConcurrentMedia (:103, default 4) is dead config - its only caller was the
    deleted ReactivePipelineExecutor. Remove it and its cortex.yml surface, and drop the row
    from ../cortex/CONFIGURATION.md (:200, :340, :468, :524) in the same change.

 8. RegistryNodeFactory javadoc (:25) and its debug log (:83) both still say it falls back to a
    stub node. It returns null; StubPipelineNode is deleted. Fix both - it is the first thing
    an agent reads when a task fails with an NPE.

 9. PipelineEventBus (cortex/pipeline-api/.../api/event/) is provided by
    CortexBindModule.providePipelineEventBus() (:96) and injected nowhere. Delete the binding
    and the SPI, or wire it.

10. PipelineNode.connectTo(...) / children() (:130, :137) are the last of the pre-graph
    programmatic wiring API. AbstractPipelineNode implements them (:120, :130) and no
    production code calls either - pipelines are built by PipelineGraphParser from JSON. Delete
    both, plus the connectTo examples in the PipelineNode javadoc (:25-27) which teach a
    contributor an API that does nothing.

11. retryFailed is honoured by the engine (PipelineGraphNode.isRetryFailed(), :167, feeding
    getMaxAttempts()) but ten nodes hide it from their form with
    @ParamOverride(key = "retryFailed", hidden = true) - FilesystemSourceNode, MoveNode,
    AssignNode, HashDedupNode, FingerprintDedupNode, FingerprintDedupApplyNode, S3SourceNode,
    S3SinkNode, OneDriveSourceNode, GDriveSourceNode. Hiding is cosmetic: a hand-written
    definition that sets the option on one of those kinds still gets retries, including on the
    destructive apply node. Decide whether "hidden" should also mean "non-retryable" and
    enforce it in PipelineGraphNode, or stop hiding it. Note that
    AbstractNodeOptions.isRetryFailed() (cortex/api/.../option/node/AbstractNodeOptions.java:61)
    is read by no node - that accessor is genuinely dead either way.
```

**References:** [PIPELINE.md §7.3, §11.1, §16, §17](../features/pipeline/PIPELINE.md) ·
[PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md) R2 ·
[CONFIGURATION.md](../cortex/CONFIGURATION.md) (item 7) ·
`WebSocketNodeDispatcher.java`, `PipelineEndpointService.java`, `PipelineEventBroadcaster.java`,
`CortexOptions.java`, `cortex/core/.../pipeline/loader/RegistryNodeFactory.java`,
`CortexBindModule.java`
**Test Requirements:** For anything implemented, a test proving it works — item 1 extends the
existing `loom/services/rest/src/test/.../PipelineRunCapabilityTest` with a case that a GPU-only
graph is not placed on a CPU-only worker (the class exists; it currently only exercises node kinds);
item 2 needs a `cortex/node-runtime` test that `shutdown()` is invoked; item 11 needs an engine test
that a hidden-retry kind gets `maxAttempts == 1` regardless of the option. For anything deleted, a
compile-clean build plus a grep showing no descriptor, DTO or config still advertises it. Item 1 and
item 3 also need the loom-ui checks: from `loom-ui/`, `./node_modules/.bin/vitest run
src/features/pipeline` and `./node_modules/.bin/playwright test
e2e/pipeline-node-availability-mocked.spec.ts` (never `npx` — it hangs in this repo).

---

## Task 11: Fill the remaining persistence and API gaps

**Argumentation Summary:** A cluster of small, independent gaps, all re-confirmed at `8c153347`.
None blocks the feature; each is a correctness or clarity defect that costs time.

**Improvement Summary:** Address as capacity allows; no ordering between them.

```
1. PipelineDaoImpl.loadWithLatestVersion (loom/db/jooq/.../dao/pipeline/PipelineDaoImpl.java:54)
   does NOT load the version - it is a plain selectFrom(PIPELINE).where(uuid), and all six
   callers separately ask pipelineVersionDao.loadLatestByPipeline(...). Implement the join the
   name promises or rename the method. ⚠️ PipelineDaoTest.testLoadWithLatestVersionReturnsThe
   PipelineRowOnly deliberately asserts today's behaviour and documents this task by number -
   fixing the DAO means rewriting that test, which is the point of how it was written.

2. PipelineDaoImpl.createPipeline(UUID userUuid, String name) (:47) ignores `name` - correct
   post-V2.30 (the name lives on the version) but dead weight on the interface. Remove the
   parameter from PipelineDao (loom/db/api/.../model/pipeline/PipelineDao.java:10-14) and its
   two overloads.

3. PipelineEndpointService.delete (:132-138) loops deleting versions before the pipeline even
   though the FK is ON DELETE CASCADE. Drop the loop, or record why the explicit delete is
   needed (audit events?) in a comment.

4. loom/db/memory has NO pipeline DAOs - the module holds only MemTokenDaoImpl and
   MemUsersDaoImpl - so the in-memory backend cannot serve pipelines at all. Either implement
   Mem{Pipeline,PipelineVersion,PipelineRun,PipelineRunItem,PipelineNodeTask}Dao, or make the
   backend fail fast with a message naming the limitation. Today it fails obscurely.
   DATABASE_TASKS.md does not own this - it has no task for the memory backend.

5. ProcessorEndpoint hand-builds JSON error bodies - lines 182 and 215
   ({"message":"Processor not found"}, 404) and 211 (the 409 on forget) - instead of the
   standard error model, and its lookup is a linear scan that computes toResponse twice per
   candidate.

6. loom-ui/src/api/pipelines.ts returns an empty result on ANY non-OK response, so a 500 is
   indistinguishable from "no data", in five places: listPipelineVersions (:241),
   listPipelineRuns (:342), listPipelineRunItemTasks (:555), listPipelineRunItems (:586) and
   loadPipelineRunBreakpoints (:689, returning EMPTY_BREAKPOINTS). Every other function in the
   file already reads res.text() and throws - make these five match.
   ⟶ loom-ui step: change the five functions in loom-ui/src/api/pipelines.ts, then handle the
     thrown error at the call sites in loom-ui/src/features/pipeline/PipelineEditor.tsx (the
     version list, run list and breakpoint fetch around :3819) so a failed load shows an error
     rather than an empty panel. Coordinate with ../loom/ui/TASK_UI_PIPELINE.md rather than
     opening a parallel UI task.

7. Server-side default layout: when a definition's nodes lack x/y, compute a layered
   left-to-right layout server-side so non-editor clients do not each reimplement it.
   loom-ui/src/features/chat/pipelineGraphLayout.ts (used by PipelineGraphCard.tsx) is the
   second implementation already.
   ⟶ loom-ui step: once the server sends coordinates, delete pipelineGraphLayout.ts and its
     pipelineGraphLayout.test.ts and read x/y in PipelineGraphCard.tsx.

8. No pipeline gRPC surface - loom-shared/proto/src/main/proto/ holds asset, health and
   reflection only. This is an accepted omission: record it as such in ../loom/GRPC.md rather
   than leaving it as an implied gap, or add pipeline.proto.
```

**References:** [PIPELINE.md §9.3, §10, §17](../features/pipeline/PIPELINE.md) ·
[DATABASE_TASKS.md](DATABASE_TASKS.md) · [TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) ·
[GRPC.md](../loom/GRPC.md) · `loom/db/jooq/.../dao/pipeline/PipelineDaoImpl.java`,
`loom/services/rest/.../endpoint/impl/ProcessorEndpoint.java`, `loom-ui/src/api/pipelines.ts`
**Test Requirements:** Per item. Item 1 rewrites the two `PipelineDaoTest` cases named above to
assert the version is actually populated. Item 4 needs the `loom/db/api-test` contract tests to pass
against the memory backend or to skip explicitly. Item 5 needs an error-path endpoint test. Item 6
needs a vitest case per function asserting a 500 rejects: from `loom-ui/`,
`./node_modules/.bin/vitest run src/api` plus `./node_modules/.bin/playwright test
e2e/pipeline-versions-mocked.spec.ts e2e/pipeline-run-items-mocked.spec.ts`. Item 7 needs a server
test that a definition without
coordinates comes back with them, and `./node_modules/.bin/vitest run src/features/chat` after the
UI copy is deleted. Run `./setup-pool.sh` before any DB-touching test.

---

## Task 14: Give the three remaining demo pipelines a real MIME filter

**Argumentation Summary:** The parser half of this task landed: `PipelineGraphParser.readOptions`
normalises through `new JsonObject(options.encode())`, so `FilterPortResolver.asList` sees a plain
`Map`/`List` tree whichever way the definition arrived, and `reviewTriageDefinition()` in
`DemoDatabaseInitializer` is the worked example of a seeded graph with configured buckets. Three
demos were rewired to `other` while the bug was open and were never rewired back:
`mediumDefinition()` (`:1646-1647`), `complexDefinition()` (`:1685-1689`) and
`transcriptionDefinition()` (`:1751`) all draw every edge off the filter's `other` port, and their
own comments say so. The demo database is what a new user and every agent sees first, so the filter
node ships looking like a pass-through.

**Improvement Summary:** Rewire the three demos onto real buckets, and retire the now-false
cross-references to the parser bug.

```
1. loom/core/src/main/java/io/metaloom/loom/core/boot/DemoDatabaseInitializer.java: give
   mediumDefinition(), complexDefinition() and transcriptionDefinition() configured buckets for
   images and video, move their edges onto those ports, and put the fingerprint branch on the
   video bucket rather than on `other`. Copy the shape from reviewTriageDefinition() (:1782).
   Delete the "with no buckets configured every item lands there" comments as you go.
2. transcriptionDefinition() specifically: whisper declares an XOR over its audio and video
   inputs (see the javadoc at :1729-1731), so the audio and video buckets must feed the two
   different ports - that is the case the demo exists to show.
3. Retire the stale cross-references left by the parser fix:
   ../workflows/WORKFLOW_TRASH.md:396 still says FilterPortResolver.asList "rejects a Vert.x
   JsonArray", and ../METALOOM_CONTEXT.md:138 and :800 still route readers here for it.
   ⚠️ METALOOM_CONTEXT.md is also touched by WORKFLOW_TASKS.md Task 16 - make one edit, not two.
4. No loom-ui change: the editor already renders configured buckets as ports
   (loom-ui/src/features/pipeline/BucketListEditor.tsx, portResolvers.ts).
```

**References:** `loom/core/.../boot/DemoDatabaseInitializer.java` ·
`loom-shared/node-model/.../spec/FilterPortResolver.java` ·
`loom/pipeline/.../graph/PipelineGraphParser.java` (`readOptions`) ·
[NODE_REGISTRATION_PLAN.md](../plans/NODE_REGISTRATION_PLAN.md) (where the trap was first found) ·
[WORKFLOW_MANUAL_SORT.md](../workflows/WORKFLOW_MANUAL_SORT.md)
**Test Requirements:** `DemoPipelineDefinitionTest` (loom/core) already parses every seeded
definition through the real registry — extend it to assert that the three rewired demos resolve
their bucket ports and that no edge in them still leaves `other`. `./setup-pool.sh`, then
`mvn -pl loom/pipeline,loom/core test`.

---

## Task 13: Register the last engine meter

**Argumentation Summary:** The structural blocker this task was written for is **gone**. `loom/pipeline`
now depends on `loom-common` for the catalog interface only; `PipelineRunEngine` takes a `LoomMetrics`
through `setMetrics(...)` (:2043) defaulting to `NoopLoomMetrics`, and records
`recordNodeTaskDeadlettered` (:954), `recordNodeTaskRetried` (:1050) and `recordNodeTaskLatency`
(:1951); `NodeKindCircuitBreaker` records its trips; `PipelineRunRegistry:47` binds
`loom_node_tasks_inflight`. `PipelineRunEngineMetricsTest` and `NodeKindCircuitBreakerMetricsTest`
guard them. One row of [METRICS.md §5.2](../features/ops/METRICS.md) is still fiction:
`loom_result_store_flush_batch_size` — `DaoRunStateStore` is uninstrumented and `LoomMetrics` has no
distribution-summary helper, so there is nothing to call.

**Improvement Summary:** Add the missing helper and record the batch size, or delete the row.

```
1. DECIDE first: is the distribution worth a new primitive? LoomMetrics
   (loom/common/.../metrics/LoomMetrics.java) has counters, timers and gauges only, and
   DaoRunStateStore already flushes on a fixed threshold (flushIfFull(), :373-375), so the
   distribution is mostly a spike detector for the shutdown flush. If the answer is no, delete
   the row from ../features/ops/METRICS.md §5.2 and its checkbox at §"Open work items" and stop
   - do not leave it documented and absent.
2. If yes: add recordResultStoreFlushBatch(int) to LoomMetrics + NoopLoomMetrics, backed by a
   Micrometer DistributionSummary in the Micrometer-backed impl.
3. Call it from DaoRunStateStore.flush()
   (loom/services/rest/.../service/impl/DaoRunStateStore.java:315) with items + tasks, on both
   the threshold path and the final flush at :298.
4. Update ../features/ops/METRICS.md §3 and §5.2 in the same change - that file's value is that
   every row has a verified call site.
```

**References:** [METRICS.md §5.2 and "Open work items"](../features/ops/METRICS.md) ·
[PIPELINE.md §6.2](../features/pipeline/PIPELINE.md) · `DaoRunStateStore.java`, `LoomMetrics.java`
**Test Requirements:** If implemented, a `SimpleMeterRegistry` assertion in `loom/services/rest` that
the summary records one observation per flush with the right count. ⚠️ `METRICS.md` §3 and §5 are
parsed at runtime by `MetricsCatalogScrapeTest`, so editing those tables can break the Java build —
run `mvn -pl loom/services/rest test -Dtest=MetricsCatalogScrapeTest` whichever branch is chosen.

---

## Task 17: Re-baseline the pipeline specs against the code

**Argumentation Summary:** [PIPELINE.md §17](../features/pipeline/PIPELINE.md) is the progress
assessment agents route by, and four of its rows are now wrong, all in the direction that costs the
most: they describe finished work as open. `PipelineRunRecovery` **does** use the registry parser
(`:87`) — §17 says it uses the no-arg one. `retryFailed` **is** read, by
`PipelineGraphNode.isRetryFailed()` — §17 says "read by nothing". The §16 "Dead but present" row
lists `retryFailed` alongside genuinely dead surfaces, and its `NodeDescriptorServiceLoaderTest`
count is out of date (the test asserts **45** advertised kinds; NODES.md §5.2 says 42 runnable).
`PIPELINE_REQUIREMENTS.md` R2 still describes `LoomBulkSyncWriterImpl` as the deviation without
naming Task 10 item 5, which now proposes deleting it — a requirement whose stated deviation is
scheduled for removal should say so. An agent starting from these files rebuilds something that is
already there; [CODING.md](../guidelines/CODING.md) requires the spec to move with the code, and this
is the arrears.

**Improvement Summary:** One sweep over the `spec/features/pipeline/` files, with the evidence
recorded so the next audit is a diff rather than a re-derivation.

```
1. spec/features/pipeline/PIPELINE.md section 17 "Open": strike the PipelineRunRecovery
   no-arg-parser bullet (fixed - the constructor at PipelineRunRecovery.java:87 takes
   nodeDescriptorRegistry) and the "retryFailed advertised by every descriptor, read by
   nothing" bullet (PipelineGraphNode.java:167 reads it; the real residue is the ten
   @ParamOverride(hidden = true) kinds - restate it as Task 10 item 11 and link, do not
   re-argue). Keep the breakpoint-recovery bullet open and point it at Task 15, and the
   SegmentNode bullet at Task 16.
2. Same file, section 16 "Dead but present": remove `retryFailed`; keep PipelineEventBus,
   DefaultLoomBulkSyncCollector.collect, connectTo/children, CortexOptions.maxConcurrentMedia
   and Subscriber.queueCapacity, all re-confirmed at 8c153347, and link them to Task 10.
3. Same file, section 13 / any descriptor-count row, and this file's section D: the count is
   45 advertised kinds (NodeDescriptorServiceLoaderTest:51) and 42 runnable with S3 configured
   (../features/nodes/NODES.md:473). Say which number is which.
4. spec/features/pipeline/PIPELINE_REQUIREMENTS.md R2: keep the deviation, and add that the
   REST write-back path it names is proposed for deletion in Task 10 item 5, so the deviation
   is a decision waiting to be made rather than a permanent property.
5. Update each touched file's footer to the current HEAD and date per ../guidelines/SPEC_RULES.md.
   ⚠️ Do NOT touch spec/workflows/WORKFLOWS.md or spec/METALOOM_CONTEXT.md - WORKFLOW_TASKS.md
   Task 16 owns those, and Task 14 step 3 above owns the two METALOOM_CONTEXT rows about
   FilterPortResolver.
```

**References:** [PIPELINE.md §13, §16, §17](../features/pipeline/PIPELINE.md) ·
[PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md) R2 ·
[NODES.md §5.2](../features/nodes/NODES.md) · [SPEC_RULES.md](../guidelines/SPEC_RULES.md) ·
[WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 16 (the sibling sweep — do not overlap)
**Test Requirements:** No code tests. Verification is mechanical: every file path, class name and
line number cited in the edited sections must resolve at the recorded HEAD. ⚠️ If the sweep reaches
`METRICS.md`, run `mvn -pl loom/services/rest test -Dtest=MetricsCatalogScrapeTest` — that file's
tables are parsed at runtime.

---

## B. Tracked elsewhere — do not duplicate here

These are real open items that touch the pipeline but are **owned by another file**. Link them; do
not open a parallel task.

| Item | Owner |
|---|---|
| `ctx.failure(...).next()` returns SUCCESS — 16 call sites in 14 node classes | [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 17** |
| `asset_node_result` provenance — `origin` hard-coded `COMPUTED`, no `runUuid`/`taskUuid` on `NodeResultCreateRequest` | [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 18** |
| Re-baselining `WORKFLOWS.md` and `METALOOM_CONTEXT.md` | [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 16** (Task 17 above covers only `spec/features/pipeline/`) |
| Ad-hoc ("pipelineless") node execution — `pipeline_run.kind = ADHOC`, the `/api/v1/node-runs` routes | [AGENTIC_NODE_EXECUTION.md](../chat/AGENTIC_NODE_EXECUTION.md). It reuses `PipelineGraphParser`, `PipelineRunEngine` and `WebSocketNodeDispatcher` unchanged |
| Task-state retention sweep (decided, not built) | [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) **Task 7** · [PIPELINE.md §9.2](../features/pipeline/PIPELINE.md) |
| Per-node task inspection API (`leased_by`, attempts, dead-letter reason) | [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) · [TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) |
| Adaptive dispatch width; priority with aging; straggler re-dispatch; dispatch batching; adaptive result batch size | [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) **Tasks 13-17** (merged there from `METALOOM_ARCHITECTURE_V2_TASKS.md` on 2026-08-16 — its Task 13 is *not* this file's Task 13) |
| Per-item opt-in event stream | [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) **Task 3** |
| UI: run deep-linking (Task 3), a cross-pipeline run activity view (Task 4), deleting the unreachable legacy `src/` trees (Task 5) | [TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) |
| Node-level gaps: `asset-source` descriptor, per-node docs, kinds with no runtime producer | [NODES.md §10](../features/nodes/NODES.md) |

---

## C. Suggested sequencing

- [ ] **Task 15** and **Task 16** first — they are the two behavioural defects, and neither depends
      on anything else.
- [ ] **Tasks 10, 11, 14** are independent and parallelisable, item by item.
- [ ] **Task 17** last, or at least after 10 and 11 land, so the sweep records one state rather than
      two.
- [ ] Nothing here blocks [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 17 or Task 18, and neither
      blocks anything here.

---

## D. Conventions and Gotchas

Task-file discipline for this area. Code-level conventions live in
[PIPELINE.md §16](../features/pipeline/PIPELINE.md).

| Area | Convention / Gotcha |
|---|---|
| **Done ⇒ deleted** | A completed task is removed from this file entirely. The code, its tests and the spec are the record of what landed; a task file that keeps its history is how this one reached 700 lines. |
| **Numbers are never reused** | Other files cite tasks by number. **1-9 and 12 are retired.** Never renumber an open task, and never hand a retired number to new work. |
| **One owner per gap** | If §B lists it, link it. A gap argued in two task files gets fixed in neither. |
| **The spec is part of the change** | Closing a task means editing [PIPELINE.md](../features/pipeline/PIPELINE.md), [PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md) and this file in the same commit ([SPEC_RULES.md](../guidelines/SPEC_RULES.md), [CODING.md](../guidelines/CODING.md)). |
| **A descriptor is not a registration** | Structurally closed on the worker: `NodeSpecCatalog.harvestRunnable(runnableNodeIds())` derives the announced contracts from `NodeFactory.registeredTypes()`, so an unrunnable kind cannot be announced, and `NodeAvailabilityService` greys out a kind no online worker offers. When adding a kind, update [NODES.md §5.2](../features/nodes/NODES.md) (**42 runnable** with S3 configured) and `NodeDescriptorServiceLoaderTest:51` (**45 advertised kinds**, 2 ServiceLoader providers). |
| **Regenerate descriptors in the right order** | Install the cortex node module **before** regenerating `loom-shared/node-model/src/main/resources/node-descriptors.json`, or the harvest reads a stale jar. Copies also live at `website/static/pipeline-editor/` and `website/dist/pipeline-editor/`. |
| **Test DB pool** | Run `./setup-pool.sh` before any DB-touching test, and again after any Flyway change. Keep endpoint-test classes under ~20 methods or the provider pool is exhausted and the last methods error in `ProviderExtension.beforeEach`. |
| **Endpoint constructor changes** | Clean-rebuild `loom/core` afterwards, or `setup-pool` and the suite fail with `NoSuchMethodError`. |
| **New REST route checklist** | Add it to the individually-enumerated `secure(...)` list, register literals before the `:uuid` wildcard, add endpoint + permission tests, add the Java client method, regenerate the Python client, and add website docs. |
| **New DB field checklist** | Flyway migration → `loom/db/jooq/generate.sh` → `db/api` change → jooq + memory impls → `db/api-test` contract test → `./setup-pool.sh`. |
| **loom-ui: never `npx`** | `npx vitest` / `npx playwright` hang in this repo. Run `./node_modules/.bin/vitest run …` and `./node_modules/.bin/playwright test …` from `loom-ui/`. |
| **`grep` sees `PipelineEditor.tsx` as binary** | The file contains a byte that makes GNU/u-grep skip it silently — a search for a string that is definitely there returns nothing. Use `grep -a` when searching `loom-ui/src/features/pipeline/PipelineEditor.tsx`. |
| **Don't reintroduce deleted concepts** | `Pipeline`, `PipelineExecutor`, `ReactivePipelineExecutor`, `LoomPipelineLoader`, `StubPipelineNode`, `PipelineSerializer`/`Deserializer`, `MediaContext`, `WorkOrderResultRegistry`, `NodeCacheProvider` and the eight `filter-*` kinds are all gone on purpose. |

---

## E. Where do I find …?

| Need | Path |
|---|---|
| The one runnable filter kind + its six `FilterStrategy` implementations (date, language, mime, rating, size, tag) | `cortex/nodes/filter/core/…/node/filter/` |
| Where a kind becomes runnable | `cortex/cli/…/dagger/RegistryNodeRegistrar.java` (impl of `cortex/core/…/pipeline/loader/NodeRegistrar.java`) |
| Descriptor harvesting from the runnable set | `cortex/api/…/api/node/spec/NodeSpecCatalog.harvestRunnable` · `NodeSpecHarvester.java` |
| Descriptor ↔ presence split for the palette | `loom/services/rest/…/service/impl/NodeAvailabilityService.java` · `loom-ui/src/features/pipeline/nodePicker.ts` |
| The result cache — a node's finished result, across items | `cortex/common/…/common/cache/LocalResultCache.java` |
| The artifact scope — an intermediate, one segment | `cortex/api/…/api/node/artifact/`, `cortex/common/…/common/artifact/MediaArtifacts.java` |
| Endpoint-test harness + pattern to copy | `loom/core/src/test/…/endpoint/test/PipelineRunItemEndpointTest.java` |
| Breakpoint/step endpoint tests | `loom/core/src/test/…/endpoint/test/PipelineRunBreakpointEndpointTest.java` · `loom-ui/e2e/pipeline-breakpoints-mocked.spec.ts` |
| Java client methods | `loom-client/common/…/method/PipelineMethods.java` |
| The one structural validator | `loom/services/rest/…/validation/PipelineValidationService.java` (see [PIPELINE_VALIDATION.md](../features/pipeline/PIPELINE_VALIDATION.md)). `PipelineModelValidator` still exists but is now **model-shape only** — it deliberately says nothing about a definition's contents. Do not put structural rules back into it |
| The three status/state vocabularies | `loom-shared/api/…/api/pipeline/{PipelineRunStatus,NodeTaskState,RunItemState}.java` · the jOOQ converters in `loom/db/jooq/…/converter/` and their `forcedTypes` entries in `loom/db/jooq/pom.xml` |
| Metric catalog + the one remaining gap (Task 13) | `loom/common/…/metrics/LoomMetrics.java` · [METRICS.md](../features/ops/METRICS.md) |
| Engine test harnesses | `loom/pipeline/src/test/…/engine/{FakeNodeDispatcher,RecordingRunStateStore,RecordingLoomMetrics,Payloads}.java` · `loom/pipeline/src/test/…/TestDescriptors.java` |
| The reference definition fixture (format regression guard) | `loom/pipeline/src/test/resources/pipeline/reference-definition.json` + `PipelineGraphParserReferenceDefinitionTest` |
| Node chain test harness | `cortex/pipeline-core/src/test/…/test/AbstractNodeChainTest.java` |
| Pipeline editor + its mocked e2e specs | `loom-ui/src/features/pipeline/PipelineEditor.tsx` · `loom-ui/e2e/pipeline-*-mocked.spec.ts` |
| Definition of done for a code change | [CODING.md](../guidelines/CODING.md) |

---

_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (code audit; §B owner rows repointed after PLAN_C became a task list)_
