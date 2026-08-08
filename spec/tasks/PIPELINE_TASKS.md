# MetaLoom Pipeline — Task List

> Open work items for the pipeline feature, re-derived from a code audit on 2026-08-06.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [PIPELINE.md](../features/pipeline/PIPELINE.md) (technical spec) ·
> [PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md) (requirement → status) ·
> [NODE_DATA_TYPES.md](../features/pipeline/NODE_DATA_TYPES.md) (ports, cardinality, fan-out)
>
> **This file tracks OPEN work only.** A task that is done is deleted, not archived — the
> code and the spec are the record of what landed. Task numbers are never reused:
> **1, 2, 3, 4, 5, 7 and 9 are retired.** [CLI_PLAN.md](../features/cli/CLI_PLAN.md) cites Task 7.
>
> **Blocking:** **Task 12** is the one silent-correctness bug — neither dispatch nor restart
> recovery parses graphs with a descriptor registry, so port checking and fan-out
> classification are absent on the live path. Do it before any polish.

---

## A. Open tasks at a glance

| # | Task | State |
|---|---|---|
| 6 | Close the residual test blind spots | 🟡 **Partly done** — adapter test landed; fixture, control channel, DAO cases open |
| 8 | Validation endpoint + de-triplicate structural validation | 🔴 **OPEN** — closes R11 |
| 10 | Retire the remaining dead surfaces | 🔴 **OPEN** — eight independent items |
| 11 | Fill the remaining persistence and API gaps | 🔴 **OPEN** — eight independent items |
| 12 | Give the production parser its descriptor registry | 🔴 **OPEN — silent correctness bug** |
| 13 | Instrument the run engine | 🔴 **OPEN** — `loom/pipeline` has no metrics at all |
| 14 | Let a programmatic definition resolve bucket ports, and give the demos a real MIME filter | 🔴 **OPEN** — the three demo filters still route via `other` |

---

---

## Task 6: Close the residual test blind spots

**Argumentation Summary:** The engine and worker runtime are now well covered, and
`CortexNodeAdapterTest` closed the adapter gap. Four seams remain, and each has already
hidden a defect of its class. `cortex/pipeline-common` has **no test directory**.
`LoomControlChannel` (reconnect, frame routing, the runnable-kind announcement) has no
direct test. `PipelineModelValidator` is untested and unwired. And there is still **no
checked-in definition JSON fixture anywhere in the repo** — the demo pipelines in
`DemoDatabaseInitializer` are the de-facto reference, so a format regression is caught only
if someone runs the demo seeder.

**Improvement Summary:** Add the missing seam tests and check in the reference fixture the
format has never had.

```
1. Check in cortex/pipeline-core/src/test/resources/pipeline/reference-definition.json
   (or loom/pipeline/src/test/resources/) holding a port-wired graph that exercises
   version, options vs the legacy `config` alias, selective ports, affinity, fan-out
   (MANY output into a PER_ELEMENT node) and gather. Add a PipelineGraphParser test
   that loads it and asserts node count, topological order, InputBindings and
   ExecutionMode per node. This is the regression fixture the format lacks.

2. LoomControlChannelTest — frame routing per ProcessorMessageType, reconnect
   scheduling, that runnableNodeIds() is what the NODE_REGISTRATION frame carries
   (whitelist/blacklist applied), and that the gauges/counters listed in
   ../features/ops/METRICS.md §4 are updated.

3. Cover PipelineModelValidator or delete it (see Task 8 — deletion is preferred).

4. DAO tests: PipelineDaoTest/PipelineVersionDaoTest exercise only the generic CRUD
   harness. Add cases for loadWithLatestVersion (see Task 11 item 1), loadByUuids,
   loadByPipelineAndVersion, loadLatestByPipeline.

Use the existing harnesses — do not roll new ones:
  AbstractNodeChainTest (cortex/pipeline-core test-jar) for node chains
  StubLoomMedia / CapturingNode(id, port) for fixtures and downstream assertions
  PipelineAssertions / PipelineResultAssert instead of raw assertEquals on maps
  FakeNodeDispatcher / RecordingRunStateStore (loom/pipeline test) for engine tests
  TestDescriptors.registry() when the graph needs port checking
```

**References:** [PIPELINE.md §13](../features/pipeline/PIPELINE.md) ·
`cortex/pipeline-core/src/test/…/test/`, `loom/pipeline/src/test/…/engine/`

**Test Requirements:** This task is tests. Target the four seams above rather than a
coverage percentage.
`mvn -pl cortex/pipeline-core,cortex/pipeline-common,cortex/core,loom/pipeline test`.

---


## Task 8: Add a validation endpoint and de-triplicate structural validation

**Argumentation Summary:** Requirement **R11 is unmet**: there is no
`POST /api/v1/pipelines/validate`, so a client cannot validate a draft without persisting
it. Separately, the *structural* rules (node id format, uniqueness, edge references,
Kahn's cycle detection, reachable-from-source) exist in **three** independent
implementations — `PipelineValidationService` (loom rest, the wired and tested one, and
the only caller that builds a registry-backed `PipelineGraphParser`),
`PipelineModelValidator` (`loom-shared/rest-model`, untested and unwired), and
`validatePipeline()` in `PipelineEditor.tsx` (its own TypeScript Kahn's). They will drift.
**Port** rules are correctly single-sourced in `PipelineGraphParser` — do not add a second
copy of those.

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

**References:** [PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md) R11 ·
[PIPELINE.md §5.1, §10](../features/pipeline/PIPELINE.md) ·
[LOOM_UI_PIPELINE_EDITOR.md](../loom/ui/LOOM_UI_PIPELINE_EDITOR.md) ·
`PipelineValidationService.java`, `PipelineModelValidator.java`, `PipelineEditor.tsx`

**Test Requirements:** Endpoint tests for a valid and several invalid definitions,
asserting **all** errors come back, not just the first. Re-point the existing
`PipelineValidationServiceTest` cases at the collecting API. A permission test for
`CREATE_PIPELINE`. A Playwright spec that a server validation error blocks save.
Per [CODING.md](../guidelines/CODING.md) a new endpoint also needs website docs.

---

---

## Task 10: Retire the remaining dead surfaces

**Argumentation Summary:** Each item below is advertised in an API, descriptor or config
and does nothing. Every one costs a contributor or agent real time to discover, and two of
them mislead users of the UI.

**Improvement Summary:** Decide implement-or-delete for each, one PR per item.

```
1. Processor capability is hardcoded to CPU in WebSocketNodeDispatcher
   (selectProcessor / selectProcessorForKinds) and in
   PipelineEndpointService.unsupportedNodeKinds. Derive the required capability from the
   graph's node kinds so a GPU-dependent pipeline is not sent to a CPU-only worker.
   The descriptor is the natural place to declare it.

2. Node lifecycle: nothing in cortex/node-runtime calls PipelineNode.initialize() or
   shutdown(). Nodes holding native handles (facedetect, whisper, OpenCV) are
   constructed lazily per kind and never released. Either call both around the node's
   lifetime in the worker, or delete the two methods from the interface. Do not leave
   them as decoration.

3. NODE_STATS is in NodeSpecHarvester.STANDARD_EVENTS, so every descriptor advertises
   it, but stats are emitted by RunStatsAggregator on the Loom side, not per node.
   Remove the advertisement or make it mean something.

4. PipelineFilter / MediaFilter SPI (cortex/pipeline-api/…/api/filter/) — no production
   references anywhere. Delete.

5. LoomBulkSyncCollector / DefaultLoomBulkSyncCollector / LoomBulkSyncWriterImpl — wired
   in CortexBindModule and flushed at shutdown by CortexImpl, but nothing calls
   collect(...). Asset write-back happens on the Loom side via DaoAssetSink. Delete the
   Cortex path, or document why it is kept (this is also the only REST result path,
   see R2).

6. PipelineEventBroadcaster.Subscriber takes a queueCapacity constructor arg that is
   never stored; DEFAULT_QUEUE_CAPACITY = 1024 is dead. Backpressure is purely
   writeQueueFull(). Remove both or implement the bounded queue.

7. CortexOptions.maxConcurrentMedia (default 4) is dead config — its only caller was
   the deleted ReactivePipelineExecutor. Remove it and its CLI/env surface.

8. RegistryNodeFactory javadoc and its debug log still say it falls back to a stub. It
   returns null; StubPipelineNode is deleted. Fix both (lines 25 and 83) — it is the
   first thing an agent reads when a task fails with an NPE.
```

**References:** [PIPELINE.md §7.3, §11.1, §12](../features/pipeline/PIPELINE.md) ·
[PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md) R2 ·
`WebSocketNodeDispatcher.java`, `PipelineEndpointService.java`, `PipelineEventBroadcaster.java`,
`CortexOptions.java`, `cortex/core/…/pipeline/loader/RegistryNodeFactory.java`

**Test Requirements:** For anything implemented, a test proving it works — item 1 needs a
`PipelineRunCapabilityTest` case that a GPU-only graph is not placed on a CPU-only worker;
item 2 needs a runner test that `shutdown()` is invoked. For anything deleted, a
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
   obscurely. See ../features/db/DATABASE_TASKS.md.

5. ProcessorEndpoint hand-builds JSON error bodies ({"message":"Processor not found"}
   at 404, and the 409 on forget) instead of the standard error model, and its lookup
   is a linear scan that computes toResponse twice per candidate.

6. loom-ui/src/api/pipelines.ts: listPipelineVersions and listPipelineRuns (and two
   further call sites) return [] on ANY non-OK response, so a server failure is
   indistinguishable from "no data". Surface real errors. Tracked for the UI side in
   ../loom/ui/TASK_UI_PIPELINE.md — coordinate rather than duplicate.

7. Server-side default layout: when a definition's nodes lack x/y, compute a layered
   left-to-right layout server-side so non-editor clients do not each reimplement it.
   The chat card (loom-ui/src/features/chat/pipelineGraphLayout.ts) is the second
   implementation already.

8. No pipeline gRPC surface (asset, health, reflection are registered). This is an
   accepted omission — record it as such in ../loom/GRPC.md rather than leaving it
   as an implied gap, or add pipeline.proto.
```

**References:** [PIPELINE.md §9.3, §10](../features/pipeline/PIPELINE.md) ·
[DATABASE_TASKS.md](../features/db/DATABASE_TASKS.md) ·
[TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) ·
`loom/db/jooq/…/dao/pipeline/PipelineDaoImpl.java`,
`loom/services/rest/…/endpoint/impl/ProcessorEndpoint.java`, `loom-ui/src/api/pipelines.ts`

**Test Requirements:** Per item. Item 1 needs a DAO test asserting the version is actually
populated. Item 4 needs the `loom/db/api-test` contract tests to pass against the memory
backend or to skip explicitly. Items 5 and 6 need an error-path test each.

---

## Task 12: Give the production parser its descriptor registry

**Argumentation Summary:** Two production call sites build `new PipelineGraphParser()` —
the **no-arg** constructor, which passes a `null` descriptor registry:

- `PipelineEndpointService:122` — the parser used to **dispatch** a run
- `PipelineRunRecovery:68` — the parser used to rebuild a run after a restart

`PortGraphAnalyzer.analyze` returns immediately when the registry is null (line 70), and
`PipelineGraphParser` skips its kind-exists check (line 223) and its descriptor lookup
(line 336). So a dispatched or recovered run gets **no port validation and every node
classified `ExecutionMode.SINGLE`**. That is not cosmetic: a graph whose
`facedetect → facedescription` edge is `PER_ELEMENT` is classified per-element when
`PipelineValidationService` parses it on save — the one call site that *does* pass the
registry — and once-per-item when the run is actually dispatched. The fan-out results are
lost, and a graph with an invalid port reaches the workers instead of being rejected.
The no-arg constructor exists for test convenience; production must not use it.

**Improvement Summary:** Inject one registry-backed parser per process and use it
everywhere on the live path.

```
1. Provide the registry-backed PipelineGraphParser in the Dagger graph (RESTModule
   already provides the @Singleton NodeDescriptorRegistry it needs), and
   constructor-inject it into BOTH PipelineEndpointService and PipelineRunRecovery in
   place of their `new PipelineGraphParser()` fields. One instance, so dispatch,
   recovery and validation cannot disagree.
   ⚠️ Endpoint/service constructor changes need a clean rebuild of loom/core, or
      setup-pool and the tests fail with NoSuchMethodError.
2. Re-audit after the change:
       grep -rn "new PipelineGraphParser()" --include=*.java loom | grep -v /test/
   must come back empty. Any hit outside src/test is the same bug.
3. Consider making the no-arg constructor package-private or @VisibleForTesting so a
   future caller cannot reintroduce this silently. Note that a large number of tests
   use it deliberately (only the six that need port checking pass
   TestDescriptors.registry()), so @VisibleForTesting is the realistic option.
```

**References:** [PIPELINE.md §5, §6.5, §16](../features/pipeline/PIPELINE.md) ·
[NODE_DATA_TYPES.md §6.3](../features/pipeline/NODE_DATA_TYPES.md) ·
`PipelineEndpointService.java:122`, `PipelineRunRecovery.java:68`,
`PipelineGraphParser.java:79`, `PortGraphAnalyzer.java:70`

**Test Requirements:** Extend `PipelineRunEngineRecoveryTest` (or add a
`PipelineRunRecoveryTest` in `loom/services/rest`) with a fan-out graph: recover it and
assert the `PER_ELEMENT` node is still classified `PER_ELEMENT` with the correct
`fanOutDriver`. A `PipelineRunEndToEndTest` case that a dispatched fan-out graph is
classified `PER_ELEMENT` rather than `SINGLE`. A test that dispatching or recovering a
graph with an invalid port fails loudly rather than running a degraded run.

---

## Task 13: Instrument the run engine

**Argumentation Summary:** Prometheus `/metrics` is live on both components and the Loom
meters have verified call sites — but **`loom/pipeline` contains not a single `LoomMetrics`
or `MeterRegistry` reference**. `PipelineRunEngine`, `NodeKindCircuitBreaker` and
`DaoRunStateStore` are entirely uninstrumented, so the five meters documented in
[METRICS.md §5.2](../features/ops/METRICS.md) are pure fiction:
`loom_node_tasks_inflight`, `loom_node_tasks_retried_total`,
`loom_node_tasks_deadlettered_total`, `loom_node_circuit_breaker_trips_total`,
`loom_result_store_flush_batch_size`. The consequence is operational: in-flight depth,
retry rate and breaker trips — the three numbers that explain a stalled run — are
invisible.

**Improvement Summary:** Give `loom/pipeline` a metrics seam and record the five meters at
their natural sites.

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
   delete the row from ../features/ops/METRICS.md §5.2. Do not leave it documented and
   absent.
5. Update ../features/ops/METRICS.md §3 and §5.2 in the same change — that file's value
   is that every row has a verified call site.
```

**References:** [METRICS.md §3, §5.2](../features/ops/METRICS.md) ·
[PIPELINE.md §6.2, §12](../features/pipeline/PIPELINE.md) ·
`PipelineRunEngine.java`, `NodeKindCircuitBreaker.java`, `RetryScheduler.java`,
`DaoRunStateStore.java`, `LoomMetrics.java`

**Test Requirements:** A test per meter asserting the counter moves on the triggering event
(use a recording `EngineMetrics` in `loom/pipeline`, and a `SimpleMeterRegistry` assertion
in `loom/services/rest`). A test that the default no-op seam leaves engine behaviour
unchanged.

---

## Task 14: Let a programmatic definition resolve bucket ports — 🟡 parser fixed, demo rewiring open

**Argumentation Summary:** `FilterPortResolver.asList` accepts a `java.util.List` and each entry as a
`java.util.Map`; `DemoDatabaseInitializer` builds its definitions programmatically, so `buckets`
arrived as a Vert.x `JsonArray` of `JsonObject` and was dropped. No bucket port resolved, and an edge
drawn to one failed validation at boot. Only a definition **parsed from a JSON string** — the
production path — resolved buckets, which is why the demos were rewired to `other` rather than fixed.

**Step 1 — ✅ DONE (2026-08-08).** Normalised at the boundary rather than teaching the resolver about
Vert.x (`loom-shared/node-model` has no vertx dependency and should not gain one):
`PipelineGraphParser.readOptions` now returns `new JsonObject(options.encode())`, so `getMap()` yields
the plain `Map`/`List` tree whichever way the definition arrived. Guarded by a `PipelineGraphParserTest`
case asserting a built definition resolves the same ports as its string-parsed equivalent, and proved
end to end by the new `Review Triage` demo pipeline — the first seeded graph with configured buckets,
which `DemoPipelineDefinitionTest` parses through the real registry.

**Step 2 — still open.**

```
Rewire the 'medium', 'complex' and 'transcription' demos: buckets for images and video, edges off
those ports, and the fingerprint branch off the video bucket rather than off 'other'. Now unblocked;
reviewTriageDefinition() is the worked example of the shape.
```

**References:** `loom-shared/node-model/…/spec/FilterPortResolver.java` (`asList`) ·
`loom/pipeline/…/graph/PipelineGraphParser.java` (`readOptions`) ·
`loom/core/…/boot/DemoDatabaseInitializer.java` ·
[NODE_REGISTRATION_PLAN.md](../plans/NODE_REGISTRATION_PLAN.md) (where the trap was first found)

**Test Requirements:** A `PipelineGraphParserTest` case that a definition built from live
`JsonObject`/`JsonArray` instances resolves the same bucket ports as the string-parsed equivalent,
and the existing demo-definition validation still passing. Run
`mvn -pl loom/pipeline,loom/core test`.

---

## B. Tracked elsewhere — do not duplicate here

These are real open items that touch the pipeline but are **owned by another spec file**.
Link them; do not open a parallel task.

| Item | Owner |
|---|---|
| Task-state retention sweep (decided, not built) | [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) §"Enforce the task-state retention policy" · [PIPELINE.md §9.2](../features/pipeline/PIPELINE.md) |
| Per-node task inspection API (`leased_by`, attempts, dead-letter reason) | [METALOOM_ARCHITECTURE_TASK.md](METALOOM_ARCHITECTURE_TASK.md) · [TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) |
| Adaptive dispatch width from live load; priority with aging; straggler / speculative re-dispatch | [PLAN_C](../concept/METALOOM_ARCHITECTURE_V2_PLAN_C.md) §3.1 |
| Dispatch batching; adaptive `resultBatchSize` | [PLAN_C](../concept/METALOOM_ARCHITECTURE_V2_PLAN_C.md) §3.2 |
| Per-item opt-in event stream | [PLAN_C](../concept/METALOOM_ARCHITECTURE_V2_PLAN_C.md) §3.3 |
| UI gaps: node-task drill-down, server-driven handle colours, run deep-linking, `PipelineArea` retirement | [TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) |
| Node-level gaps: `asset-source` descriptor, per-node docs | [NODES.md §10](../features/nodes/NODES.md) |

---

## C. Suggested sequencing

- [ ] **Task 12** first — small, self-contained, and it is currently changing run
      semantics between save and dispatch, and again across a restart, without anyone
      noticing.
- [ ] **Task 8** touches the REST surface; the endpoint-test harness Task 7 left behind
      (`PipelineVersionEndpointTest`, `PipelineRunDispatchEndpointTest`) is what its own
      endpoint tests should reuse.
- [ ] **Tasks 6, 10, 11, 13** are independent and parallelisable. Task 6's
      reference-fixture item is worth pulling forward regardless — the definition format
      has never had a checked-in regression fixture.

---

## D. Conventions and Gotchas

Task-file discipline for this area. Code-level conventions live in
[PIPELINE.md §16](../features/pipeline/PIPELINE.md).

| Area | Convention / Gotcha |
|---|---|
| **Done ⇒ deleted** | A completed task is removed from this file entirely. The code, its tests and the spec are the record of what landed; a task file that keeps its history is how this one reached 700 lines. |
| **Numbers are never reused** | Other files cite tasks by number. **1, 2, 3, 4, 5, 7 and 9 are retired.** Never renumber an open task, and never hand a retired number to new work. |
| **One owner per gap** | If §B lists it, link it. A gap argued in two task files gets fixed in neither. |
| **The spec is part of the change** | Closing a task means editing [PIPELINE.md](../features/pipeline/PIPELINE.md), [PIPELINE_REQUIREMENTS.md](../features/pipeline/PIPELINE_REQUIREMENTS.md) and this file in the same commit ([SPEC_RULES.md](../guidelines/SPEC_RULES.md), [CODING.md](../guidelines/CODING.md)). |
| **A descriptor is not a registration** | Structurally closed on the worker: `NodeSpecCatalog.harvestRunnable(runnableNodeIds())` derives the announced contracts from `NodeFactory.registeredTypes()`, so an unrunnable kind cannot be announced, and `NodeAvailabilityService` greys out a kind no online worker offers. When adding a kind, still update the counts in [NODES.md §5.2](../features/nodes/NODES.md) and `NodeDescriptorServiceLoaderTest` (currently **42**). |
| **Test DB pool** | Run `./setup-pool.sh` before any DB-touching test, and again after any Flyway change. Keep endpoint-test classes under ~20 methods or the provider pool is exhausted and the last methods error in `ProviderExtension.beforeEach`. |
| **Endpoint constructor changes** | Clean-rebuild `loom/core` afterwards, or `setup-pool` and the suite fail with `NoSuchMethodError`. |
| **New REST route checklist** | Add it to the individually-enumerated `secure(...)` list, register literals before the `:uuid` wildcard, add endpoint + permission tests, add the Java client method, regenerate the Python client, and add website docs. |
| **New DB field checklist** | Flyway migration → `loom/db/jooq/generate.sh` → `db/api` change → jooq + memory impls → `db/api-test` contract test → `./setup-pool.sh`. |
| **Don't reintroduce deleted concepts** | `Pipeline`, `PipelineExecutor`, `ReactivePipelineExecutor`, `LoomPipelineLoader`, `StubPipelineNode`, `PipelineSerializer`/`Deserializer`, `MediaContext`, `WorkOrderResultRegistry`, `NodeCacheProvider` and the eight `filter-*` kinds are all gone on purpose. |

---

## E. Where do I find …?

| Need | Path |
|---|---|
| The one runnable filter kind + its four `FilterStrategy` implementations | `cortex/nodes/filter/core/…/node/filter/` |
| Where a kind becomes runnable | `cortex/cli/…/dagger/RegistryNodeRegistrar.java` (impl of `cortex/core/…/pipeline/loader/NodeRegistrar.java`) |
| Descriptor harvesting from the runnable set | `cortex/api/…/api/node/spec/NodeSpecCatalog.harvestRunnable` · `NodeSpecHarvester.java` |
| Descriptor ↔ presence split for the palette | `loom/services/rest/…/service/impl/NodeAvailabilityService.java` |
| The result cache — a node's finished result, across items | `cortex/common/…/common/cache/LocalResultCache.java` |
| The artifact scope — an intermediate, one segment | `cortex/api/…/api/node/artifact/`, `cortex/common/…/common/artifact/MediaArtifacts.java` |
| Endpoint-test harness + pattern to copy | `loom/core/src/test/…/endpoint/test/PipelineRunItemEndpointTest.java` |
| Java client methods | `loom-client/common/…/method/PipelineMethods.java` |
| The three validators (Task 8) | `loom/services/rest/…/validation/PipelineValidationService.java` · `loom-shared/rest-model/…/validation/PipelineModelValidator.java` · `loom-ui/src/features/pipeline/PipelineEditor.tsx` |
| The three status/state vocabularies | `loom-shared/api/…/api/pipeline/{PipelineRunStatus,NodeTaskState,RunItemState}.java` · the jOOQ converters in `loom/db/jooq/…/converter/` and their `forcedTypes` entries in `loom/db/jooq/pom.xml` |
| The registry-less production parsers (Task 12) | `PipelineEndpointService.java:122` · `PipelineRunRecovery.java:68` |
| Metric catalog + the gap list (Task 13) | `loom/common/…/metrics/LoomMetrics.java` · [METRICS.md](../features/ops/METRICS.md) |
| Engine test harnesses | `loom/pipeline/src/test/…/engine/{FakeNodeDispatcher,RecordingRunStateStore,Payloads}.java` · `loom/pipeline/src/test/…/TestDescriptors.java` |
| Node chain test harness | `cortex/pipeline-core/src/test/…/test/AbstractNodeChainTest.java` |
| Definition of done for a code change | [CODING.md](../guidelines/CODING.md) |

---

_Git HEAD revision: `716953c0`_
_Last updated: 2026-08-07 (Task 9 deleted as done — the three status/state vocabularies are
typed enums parsed at the jOOQ boundary, and `V2.77` normalises the `FAILURE`/`FAILED`
mismatch it exposed; Task 7's stale row removed, its endpoint tests are in the tree. Earlier:
Task 3 deleted as done, Tasks 1/2/4/5 deleted, Task 6 item 2 closed by `CortexNodeAdapterTest`,
Task 12 widened to cover `PipelineEndpointService`)_
