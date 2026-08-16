# Node Data Types — Task List

> Work items for the typed-port model — content types, ports, cardinality, fan-out, coercion and the
> provenance that should travel with a result — derived from a code audit on 2026-08-16.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) (the
> reference for the built model; §9 is the gap table these tasks come from) ·
> [../concept/NODE_DATA_TYPES_PLAN.md](../concept/NODE_DATA_TYPES_PLAN.md) (the locked design
> decisions and the recorded divergences) · [../features/nodes/NODES.md](../features/nodes/NODES.md)
> (node lifecycle and persistence) ·
> [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) (engine and run state) ·
> [../guidelines/CODING.md](../guidelines/CODING.md) (definition of done)
>
> **The model itself is built and is not what these tasks are about.** All six refactor phases
> landed; what follows is one behavioural defect, two provenance holes, two enforcement holes, the
> tests that were never written, and three paths nothing shipped exercises.
>
> **Ordering / blocking.** Task 1 is the only one with user-visible wrong behaviour today — do it
> first. Tasks 2 and 3 are one change to the same write path and should land together. Task 4 depends
> on Task 10 for its second half (there is no `EXCLUSIVE` group to reject a write on until one
> exists). Task 11's scope depends on [NODE_SCHEMA_TASKS.md](NODE_SCHEMA_TASKS.md) Task 1, which owns
> the server-side-resolution question this file deliberately does not restate. Everything else is
> independent.
>
> **Scope boundary.** Where a node's contract *comes from* — the `@NodeSpec` annotations, the
> build-time harvest, the committed resource and what is served — belongs to
> [../features/pipeline/NODE_SCHEMA.md](../features/pipeline/NODE_SCHEMA.md) and
> [NODE_SCHEMA_TASKS.md](NODE_SCHEMA_TASKS.md). This file owns what *flows between ports* once the
> contracts exist. The two overlap at exactly one point, recorded as Task 12 below.
>
> **A note on numbers in this file.** Kind counts, id counts and line numbers rot fast — the previous
> revision of the reference document was wrong about all three within a week. Re-derive with the
> commands in [NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §14 rather than trusting a
> figure quoted here.

## Progress Assessment

- [ ] **Defect:** Task 1 — the two dedup report ports are documented as selective and are not
- [ ] **Provenance:** Task 2 (`ResultOrigin` never reaches the wire) · Task 3 (no run/task on the ledger)
- [ ] **Enforcement:** Task 4 — `ValueCoercer` has neither hard-failure arm
- [ ] **Missing tests:** Task 5 (`ValueCoercerTest`, `PortPayloadRoundTripTest`) · Task 6 (port rules from the REST side)
- [ ] **Correctness under fan-out:** Task 7 — result reuse is hard-coded to element 0
- [ ] **Observability:** Task 8 — counters count nodes, not executions
- [ ] **Reachability:** Task 9 (no shipped `PER_ELEMENT` graph) · Task 10 (no descriptor uses `EXCLUSIVE`)
- [ ] **Drift guards:** Task 11 (Java-exported TS fixtures) · Task 12 is **not owned here** — it is [NODE_SCHEMA_TASKS.md](NODE_SCHEMA_TASKS.md) Task 1

---

## Task 1: The dedup report ports are documented as selective and are not

**Argumentation Summary:** `HashDedupNode.OUT_DUPLICATE` and
`FingerprintDedupApplyNode.OUT_CONFIRMED_DUP` both carry javadoc saying silence on the port is the
"do not act" / "nothing to do" signal, exactly as a filter's bucket ports work — that is the whole
supersede design, in which the dedup nodes decide and a wired `move` node acts. **Neither
`@PortDoc` sets `selective = true`.** The harvested descriptor therefore carries `selective: false`,
`PortGraphAnalyzer.stampBindings` never marks the edge `sourceSelective`, and
`PipelineRunEngine.routedDependencyDelivered` never applies. A `move` node wired to `duplicate` is
dispatched for **every** item, with nothing on its required `media` input, rather than only for the
items that were duplicates. `PipelineSegmenter.isRoutingEdge` also fails to break the segment, so the
filter-shaped subgraph below a dedup node is packed into one segment and run unconditionally.

This is not a regression from the annotation sweep: `git grep selectiveOne` over the old hand-written
providers shows the flag was never set for these two ports either. It is a doc-vs-code gap that has
been true since the ports were introduced, and it is invisible from the node side because the
javadoc — the thing a reader checks — states the intended behaviour.

**Improvement Summary:** Set `selective = true` on the two `@PortDoc`s, regenerate the committed
descriptor resource, and add the engine test that would have caught it.

```
1. cortex/nodes/dedup/core/.../HashDedupNode.java: on OUT_DUPLICATE, change
   @PortDoc(label = "Duplicate", description = "...") to add selective = true.
   Same in FingerprintDedupApplyNode.java on OUT_CONFIRMED_DUP.
   Do NOT touch OUT_ORIGINAL / OUT_KEEP_PATH: those carry a value for every item the node ran on
   and are the "run always" escape hatch, exactly as the filter's `bucket` port is.
2. Install the two node modules, then regenerate the committed harvest - it is read from the
   INSTALLED jar, so an un-installed edit silently regenerates the old contract:
     mvn -o -pl cortex/nodes/dedup/core -DskipTests install
     mvn -o -pl integration-test test -Dtest=NodeSpecGoldenTest -Dloom.regenerateNodeDescriptors=true
   The diff to loom-shared/node-model/src/main/resources/node-descriptors.json must be exactly two
   "selective": true lines. Anything else means an unrelated annotation edit was pending.
3. Check the same question for every other output port that a node writes conditionally. Grep the
   node sources for javadoc containing "silence" / "do not act" / "when" on an OutputPort constant
   and compare against `selective` in the resource. Candidates worth a decision, not a blind edit:
   watermark's image|video pair (see Task 10 - that pair wants EXCLUSIVE, not selective),
   metadata.geo (written only for a file that carried a coordinate) and facedetect.detections
   (empty rather than absent when no face is found - MANY ports deliver an empty sequence, which is
   a different signal). Record whatever you decide in NODE_DATA_TYPES.md §4.
4. Update spec/features/nodes/NODE_DATA_TYPES.md §4.6: the 🔴 note there describes this defect and
   must become a statement of the fixed behaviour, and §8.6's "today, only the filter node's
   buckets" sentence gains the two dedup ports.
```

**References:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §4.6, §8.6 ·
[../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) ·
[../workflows/WORKFLOW_DEDUP.md](../workflows/WORKFLOW_DEDUP.md)

**Test Requirements:** A new case in `PipelineRunEnginePortRoutingTest` (the one engine test that
parses **with** a descriptor registry): a `hash-dedup` that writes no `duplicate` for an item must
leave a downstream `move` **SKIPPED**, and writing it must run `move` with the path on its `media`
port. A `PipelineSegmenterTest` case asserting the segment breaks at the `duplicate` edge.
`NodeSpecGoldenTest` must pass without `-Dloom.regenerateNodeDescriptors` afterwards. Run
`mvn test -pl loom/pipeline -Dtest='PipelineRunEnginePortRoutingTest,PipelineSegmenterTest'` and
`mvn test -pl integration-test -Dtest=NodeSpecGoldenTest`.

---

## Task 2: `ResultOrigin` never reaches the wire

**Argumentation Summary:** A dozen nodes call `ctx.origin(ResultOrigin.LOCAL)` / `.REMOTE` /
`.COMPUTED` to say where a result came from — computed here, served from this worker's disk cache, or
fetched from Loom. **The value is dropped.** `ResultOrigin` appears nowhere in
`cortex/node-runtime/src/main` or in `loom-shared/pipeline-model`: `NodeTaskResult` has no origin
field, so it is never serialised back to Loom. Worse, `AbstractMediaNode.recordNodeResult` writes the
ledger row with a hardcoded `ledger.setOrigin(ResultOrigin.COMPUTED.name())` rather than reading
`ctx.resultOrigin()`, so `asset_node_result.origin` says `COMPUTED` even for a row whose result came
straight out of a cache. The column has a CHECK constraint over three values and only ever holds one.

The cost is not cosmetic: `origin` is how an operator answers "did this run actually recompute
anything, or did it replay a cache?" — the question every re-run of a large corpus raises.

**Improvement Summary:** Carry the origin on `NodeTaskResult`, and have `recordNodeResult` read what
the node actually set.

```
1. loom-shared/pipeline-model/.../model/NodeTaskResult.java: add a `String origin` field beside
   `state`, with the same Jackson treatment. A String, not an enum: pipeline-model must not depend
   on cortex/api, exactly as PortPayload.cardinality is a String (PLAN.md divergence 2). Default it
   to null and treat null as COMPUTED on the read side, so an older worker stays wire-compatible.
2. cortex/node-runtime/.../NodeResultMapper: stamp it from the NodeResult the node returned. Follow
   how `state` is carried; the context already exposes it as ctx.resultOrigin().
3. cortex/common/.../AbstractMediaNode.recordNodeResult: replace the hardcoded
   ResultOrigin.COMPUTED.name() with the value from the context, defaulting to COMPUTED when the
   node set nothing. This is the one-line half of the task and the half that fixes the ledger.
4. Do NOT add origin to the port payloads. Origin-the-provenance and Origin-the-fan-out-tag are two
   unrelated things that already collide by name (PLAN.md divergence 15); putting provenance on
   DataElement.origin would make the collision structural instead of nominal.
```

**References:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §9 gap 2 ·
[../concept/NODE_DATA_TYPES_PLAN.md](../concept/NODE_DATA_TYPES_PLAN.md) §4.3, divergence 15 ·
[../loom/DOMAIN.md](../loom/DOMAIN.md) (`asset_node_result`, `origin` CHECK) ·
[../features/nodes/NODES.md](../features/nodes/NODES.md) §2

**Test Requirements:** A `NodeResultMapperTest` case asserting the origin survives node → result →
wire. An endpoint test that a node result posted with `origin = LOCAL` reads back as `LOCAL` — the
existing `NodeResultEndpointTest` is the place. A cortex node test that a cache hit records `LOCAL`
rather than `COMPUTED`; `FilterNode` already sets `LOCAL` on its cache path and is the cheapest
subject. Run `mvn test -pl cortex/node-runtime,cortex/nodes/filter/core` and
`./setup-pool.sh && mvn test -pl loom/core -Dtest=NodeResultEndpointTest`.

---

## Task 3: A node-result ledger row cannot be traced back to its run

**Argumentation Summary:** `asset_node_result` has `run_uuid` and `task_uuid` columns and
`AssetNodeResult` has `setRunUuid` / `setTaskUuid` for them, but `NodeResultCreateRequest` — the only
way a Cortex worker writes such a row — declares neither field. Nothing on the write path fills them.
So the ledger answers "has node X processed asset A" and cannot answer "which run did that", which is
the follow-up question in every incident: a bad model version ships, and there is no way to select the
rows a given run produced in order to invalidate them. `producer_version` narrows it to a build, not
to a run.

The task carries this information already — `NodeTask` has the run and task uuids, and the node has
the task in hand when it writes the ledger row.

**Improvement Summary:** Add the two fields to the create request and fill them from the task on the
Cortex side.

```
1. loom-shared/rest-model/.../noderesult/NodeResultCreateRequest.java: add `runUuid` and `taskUuid`
   String fields with getters/setters, following the shape of the existing `nodeId`. Both optional -
   an ad-hoc node run outside a pipeline legitimately has neither.
2. loom/services/rest/.../NodeResultEndpointService (or the builder it delegates to): pass them to
   AssetNodeResult.setRunUuid / setTaskUuid, parsing null-safely. Both columns are ON DELETE SET
   NULL, so a pruned run must not take the ledger row with it - do not add a cascade.
3. cortex/common/.../AbstractMediaNode.recordNodeResult: read the run and task uuid off the
   NodeContext and set them. Land this together with Task 2 - it is the same method, the same
   request object and the same tests.
4. Regenerate the four generated artifacts a REST model change drags with it: openapi from inside
   loom/doc, the committed website copies, and the Python client models plus its parity test.
```

**References:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §9 gap 3 ·
[../loom/DOMAIN.md](../loom/DOMAIN.md) (`asset_node_result` — `run_uuid`/`task_uuid` are SET NULL) ·
[../loom/RESTAPI.md](../loom/RESTAPI.md) · [../guidelines/CODING.md](../guidelines/CODING.md)

**Test Requirements:** Extend `NodeResultEndpointTest`: a row created with both uuids reads them
back, a row created without them is still accepted, and deleting the run leaves the ledger row with
NULLs rather than deleting it. A DAO test in `loom/db/jooq` for the SET NULL behaviour if one does
not already exist. `clients/python/test.sh` must stay green (the parity suite sees the new fields).
Run `./setup-pool.sh && mvn test -pl loom/core -Dtest=NodeResultEndpointTest`.

---

## Task 4: `ValueCoercer` has neither hard-failure arm

**Argumentation Summary:** The design called for a node emitting an **undeclared port id**, or a
**non-selected member of an `EXCLUSIVE` output group**, to fail that task with a message naming the
port or the group. Neither arm exists. `ValueCoercer` switches on the declared content type's family
and has nowhere to look up "is this port declared at all" — the check has to sit where the port
identity is known, which is `NodeContextImpl` on write and `NodeResultMapper.toPayloads` on emit.

Today an undeclared port id is written into the outputs map, serialised, stored in the
`pipeline_node_task.outputs` JSONB, and silently ignored by every consumer, because no binding can
reference a port no descriptor declares. That is the `llm_result` defect class the whole refactor
removed — reachable again through a typo'd `OutputPort` constant. `NodeSpecGoldenTest` does not catch
it: the descriptor is harvested *from* the constants, so a typo'd constant produces a descriptor that
agrees with the typo.

**Improvement Summary:** Fail the task at the emit boundary when a written port is not on the node's
resolved output ports, or is a non-selected member of an `EXCLUSIVE` group.

```
1. The check belongs where ResolvedPorts is reachable. NodeContextImpl already holds the node's
   descriptor-derived ports for `isDemanded`; use the same source. Throw ValueCoercionException (or
   a sibling with the same handling) naming the port id and listing the declared ones - the message
   is the whole value of this task, so spell the alternatives out.
2. The EXCLUSIVE half needs a group to exist first. Do it after Task 10, or write it against a test
   descriptor now and leave the shipped-descriptor case unexercised - but say which you did, and do
   not claim coverage the tree does not have.
3. Failing the task, not the run: one node emitting a stray port must not take down an item that
   otherwise succeeded, which is the same containment ValueCoercer.coerceStruct already provides
   ("fails that one task instead of clearing the whole persist batch").
4. Watch the dynamic kinds. script/llm/vlm/filter resolve their ports from options, so the check
   must run against ResolvedPorts, never against the static descriptor - otherwise every script node
   fails on its first output. NodeDescriptorRegistry.resolvePorts is the accessor.
```

**References:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §7.4, §9 gap 4 ·
[../concept/NODE_DATA_TYPES_PLAN.md](../concept/NODE_DATA_TYPES_PLAN.md) divergence 12, §4.1

**Test Requirements:** New cases in the `ValueCoercerTest` of Task 5 (or a `NodeContextImplTest`): a
node writing an undeclared port fails with a message naming it; a node writing a declared port does
not; a `script` node writing a port that its `outputs` option declares does **not** fail. Run
`mvn test -pl loom-shared/node-model,cortex/api`.

---

## Task 5: `ValueCoercer` and `PortPayload` have no direct tests

**Argumentation Summary:** `ValueCoercer` is the only thing standing between a node and a
`ClassCastException` at the port boundary, and it has **no test class**. Everything it does is
exercised incidentally, through whichever node suite happens to push a value of the right shape. Its
most load-bearing behaviour — `scalar/integer` always widening to `Long`, at both boundaries, so that
the JSON round trip's re-narrowing to `Integer` is invisible to a node — is asserted nowhere, and it
is precisely the behaviour a well-meaning simplification would remove.

`PortPayload` has the same hole from the other side: nothing asserts that
`output → JSON → JSONB → input` preserves the content type **and** the origin tags. `PortPayloads`'
decode path is deliberately lenient (a row written before this shape existed yields an empty map
rather than throwing), and that leniency is untested — so it could stop being lenient, or start
swallowing a real payload, without a failure.

**Improvement Summary:** Write the two test classes the model has been missing since it landed.

```
1. loom-shared/node-model/src/test/.../spec/ValueCoercerTest.java - one case per family arm:
   - scalar/integer: Integer/Long/short/numeric String all arrive as Long; a Double with a
     fractional part is rejected; a Double that is integral is accepted. This is the case the class
     exists for.
   - scalar/number, scalar/boolean + control/*, scalar/string + media/* + text/* + hash/* +
     artifact/*, detection/*, struct/*, and the unknown-family hard failure.
   - struct/*: a value that cannot be encoded fails with a message naming the port, and the
     exception is a ValueCoercionException rather than whatever the encoder threw.
2. loom/pipeline/src/test/.../engine/PortPayloadRoundTripTest.java:
   - a MANY payload of three elements survives PortPayloads.encode -> JsonObject -> decode with its
     contentType, its cardinality string and every element's Origin{itemId, seq, total} intact;
   - a ONE payload likewise;
   - a JsonObject that predates the shape decodes to an empty map rather than throwing;
   - one unreadable port does not cost the caller the other ports in the same map. That last one is
     the documented promise ("losing a cached result is an inconvenience, failing recovery over it
     is an outage") and is the reason to write this test at all.
3. Assert on Long specifically, not on Number - `assertEquals(5, x)` passes for an Integer and
   defeats the point.
```

**References:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §7.3, §7.4, §9 gap 5 ·
[../concept/NODE_DATA_TYPES_PLAN.md](../concept/NODE_DATA_TYPES_PLAN.md) §4.2

**Test Requirements:** The two new classes are the deliverable. Neither needs a database. Run
`mvn test -pl loom-shared/node-model -Dtest=ValueCoercerTest` and
`mvn test -pl loom/pipeline -Dtest=PortPayloadRoundTripTest`.

---

## Task 6: The port rules are almost never reached from the REST side

**Argumentation Summary:** `PipelineValidationService` deliberately **delegates** port checking to
`PipelineGraphParser` / `PortGraphAnalyzer` rather than reimplementing it — the right call, and the
reason there is one implementation instead of the three this area used to have. But
`PipelineValidationServiceTest` has 38 test methods and **one** mention of `sourcePort`: its edge
fixtures carry no ports, so the delegation itself is barely exercised. The rules are covered by
`PortGraphAnalyzerTest`; what is untested is that they are still reached through the REST path and
that a `GraphValidationException` still maps onto a 400 rather than a 500.

The second half of the gap is quieter: `PipelineValidationService.collectErrors` returns before the
port pass when a definition has no `edges` key at all. A single-node pipeline is legal, so this is
not simply a bug — but it means "saved successfully" carries no port guarantee for a whole class of
definition, and that is stated nowhere an author can see.

**Improvement Summary:** Add REST-side cases for the delegated rules and decide, in writing, what a
definition with no `edges` key is promised.

```
1. loom/services/rest/src/test/.../validation/PipelineValidationServiceTest: add one case per rule
   family, each going through the public entry point rather than the parser:
   - a type mismatch between two wired ports -> a validation error naming both ports;
   - an unknown targetPort -> an error naming the port and the kind;
   - two edges into a ONE input -> an error;
   - an unsatisfied required XOR group (media_alt with neither member wired) -> an error;
   - a valid graph -> no errors.
   Assert on the mapped HTTP status too, not only on the error list: the 400 mapping is the half
   that a refactor of the exception hierarchy would break silently.
2. Decide the no-edges case and write it down. Either run the port pass unconditionally (a
   single-node definition then gets its required-input check, which is arguably what an author
   expects), or keep the early return and state the exemption in
   spec/features/pipeline/PIPELINE_VALIDATION.md and in NODE_DATA_TYPES.md §11. Do not leave it
   undocumented a third time.
3. Do not add rules to PipelineValidationService while doing this. The delegation is the design
   (PLAN.md divergence 5) and a fourth copy is the failure mode this area already had once.
```

**References:** [../features/pipeline/PIPELINE_VALIDATION.md](../features/pipeline/PIPELINE_VALIDATION.md) ·
[../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §6.3, §9 gaps 6 and 10 ·
[../concept/NODE_DATA_TYPES_PLAN.md](../concept/NODE_DATA_TYPES_PLAN.md) divergence 5

**Test Requirements:** The extended `PipelineValidationServiceTest` is the deliverable; it must reach
every rule family listed above. Run
`./setup-pool.sh && mvn test -pl loom/services/rest -Dtest=PipelineValidationServiceTest`.

---

## Task 7: Result reuse is hard-coded to element 0

**Argumentation Summary:** `DaoRunStateStore.previousResult(media, nodeId)` — the incremental-reuse
path that lets an unchanged asset skip work it has already had done — looks the previous task up with
`taskDao.loadByItemAndNode(previous.getUuid(), nodeId, 0)`. The trailing `0` is `element_seq`.
Result reuse predates fan-out and was never extended to it, so for a `PER_ELEMENT` node the lookup
finds element 0's task and nothing else: element 0 may be reused while elements 1..n-1 are recomputed,
or the reuse is simply dropped. A per-element node therefore re-runs in full on an asset nothing about
which has changed — the exact cost incremental reuse exists to avoid, and it grows with the fan-out
width.

**Improvement Summary:** Key the reuse lookup on the element, or refuse it explicitly for
per-element nodes and say so.

```
1. Decide the shape first, because the honest cheap option is legitimate:
   (a) Extend previousResult to take the elementSeq and load per element. Correct, and it needs the
       caller to pass the seq it is about to dispatch - PipelineRunEngine has it.
   (b) Refuse reuse for PER_ELEMENT nodes outright and log it once per node per run. Costs
       throughput, cannot be wrong, and is a two-line change.
   Prefer (a); take (b) only if the previous item's element count cannot be established, and in
   either case record the choice in spec/features/pipeline/PIPELINE.md.
2. If (a): the element identity across runs is the seq, and the seq is only meaningful if the
   driver produced the same number of elements. A previous run whose driver found 3 faces and a
   current one that found 5 must NOT reuse by position - compare the previous item's element count
   for the driver node and skip reuse when it differs. Getting this wrong attaches one face's
   result to a different face, which is worse than recomputing.
3. `loadByItemAndNode(itemUuid, nodeId, elementSeq)` already takes the seq, so the DAO needs no
   change; this is a caller-side fix.
```

**References:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §9 gap 13 ·
[../concept/NODE_DATA_TYPES_PLAN.md](../concept/NODE_DATA_TYPES_PLAN.md) divergence 21 ·
[../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) · migration `V2.60`

**Test Requirements:** A `PipelineRunEngineFanOutTest` (or `DaoRunStateStoreTest`) case: an item
re-processed with an unchanged driver result reuses **every** element, not just element 0; an item
whose driver now produces a different element count reuses none. Run
`./setup-pool.sh && mvn test -pl loom/pipeline -Dtest='PipelineRunEngine*Test'`.

---

## Task 8: Counters count nodes, not executions

**Argumentation Summary:** `PipelineRunEngine.nodeProgressSnapshot()` buckets each node on
`state.isInFlight(nodeId)` / `state.isSettled(nodeId)`, both of which are whole-node predicates. A
node fanned out to 200 elements with 199 finished reports exactly what a node running once reports:
one entry, in flight. The run-item detail has no per-node `k/N elements` display either. So the one
observable the fan-out feature most needs — how far through a fan-out a run is — does not exist, and
a long fan-out is indistinguishable from a hung node.

**Improvement Summary:** Report per-element progress in the snapshot and surface it in the run
monitor.

```
1. NodeExecState already holds elementCount and the per-element results; add accessors for
   "elements settled" and "elements in flight" rather than deriving them at the call site.
2. Extend the snapshot payload with completed/total per node. Keep the existing [active, pending]
   shape working - it is consumed by the run-monitor UI and by tests - and add the element pair
   beside it rather than redefining what active means.
3. elementCount is null until the driver settles (that is the gather barrier). Report it as unknown
   rather than as 0/0, which would render as a finished node.
4. loom-ui: show `k/N` on the node row in the run monitor when N is known. This is the half an
   operator actually sees; a snapshot nothing renders is not the task.
```

**References:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §8.1, §9 gap 11 ·
[../concept/NODE_DATA_TYPES_PLAN.md](../concept/NODE_DATA_TYPES_PLAN.md) §4.4 ·
[../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) ·
[../loom/ui/LOOM_UI_PIPELINE_EDITOR.md](../loom/ui/LOOM_UI_PIPELINE_EDITOR.md)

**Test Requirements:** A `PipelineRunEngineFanOutTest` case asserting the snapshot reports `k/N` as
elements settle and `unknown` before the driver settles. A loom-ui vitest over the run-monitor row
formatter, plus a mocked Playwright assertion that the `k/N` appears. Run
`./setup-pool.sh && mvn test -pl loom/pipeline -Dtest='PipelineRunEngine*Test'` and
`cd loom-ui && ./node_modules/.bin/vitest run src/features/pipeline`.

---

## Task 9: No shipped graph exercises `PER_ELEMENT`

**Argumentation Summary:** **No shipped kind declares a `ONE`-cardinality `detection/*` input.**
`facedescription` — the node the whole fan-out design was written around — declares its `detections`
port `MANY`, which makes it a *gather*: it waits for the whole branch and runs once. Every other
detection consumer (`scene-layout`, `dominant-color`, `image-manipulation`, `sam2`) is `MANY` too. So
`ExecutionMode.PER_ELEMENT` — per-element dispatch, the `(node, seq)`-keyed retry and capacity gates,
element-level skip, the zip on `origin.seq`, element-aware recovery — is reachable in
`PipelineRunEngineFanOutTest` and `PortGraphAnalyzerTest` and **nowhere a user can get to**. Half the
engine's complexity is exercised only by its own unit tests, which is how a path rots.

**Improvement Summary:** Ship one kind with a `ONE` detection input and seed a demo pipeline that
fans out, so the path runs in the demo container and in the e2e suite.

```
1. Pick the kind by what it would genuinely do per detection rather than inventing one. The two
   honest candidates:
   (a) a per-face variant of `captioning` / `vlm` - "describe this face crop" - taking
       `detection : detection/face` ONE and emitting `text/caption` ONE;
   (b) `image-manipulation` gaining a ONE `detection` port beside its MANY one, so "crop to this
       detection" is a per-detection execution rather than a batch.
   (a) is the cleaner demo; (b) touches an existing node's contract and is the riskier of the two.
2. Whichever is chosen, remember the v1 restriction: a PER_ELEMENT node may not declare a MANY
   output (PortGraphAnalyzer rejects it on the declaration). The new kind emits ONE of everything.
3. Seed a demo pipeline in DemoDatabaseInitializer wiring facedetect.detections into it, beside the
   existing gather-shaped `Full Processing` pipeline, so both shapes ship.
4. Update the ⚠️ in NODE_DATA_TYPES.md §8.5 and §9 gap 12 - both currently say no shipped graph
   exercises this.
```

**References:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §6.4, §8.5, §9 gap 12 ·
[../concept/NODE_DATA_TYPES_PLAN.md](../concept/NODE_DATA_TYPES_PLAN.md) §4.4 ·
[../features/nodes/NODES.md](../features/nodes/NODES.md) · [../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md)

**Test Requirements:** `NodeSpecGoldenTest` regenerated and green. A demo-seed test asserting the new
pipeline classifies the node `PER_ELEMENT` (the `PortGraphAnalyzer` verdict, not just that the graph
saved). An `integration-test` end-to-end run over an image with two faces producing two per-element
tasks and two results. Run `./setup-pool.sh`, then
`mvn test -pl integration-test -Dtest=NodeSpecGoldenTest` and the new pipeline test.

---

## Task 10: No descriptor uses `EXCLUSIVE` — give `watermark` one, or delete the mode

**Argumentation Summary:** `PortGroupMode.EXCLUSIVE`, `PortGroup.exclusive(...)`,
`PortGraphAnalyzer.validateExclusiveOutputs` and the editor's client-side mirror all exist, are wired
and are unused: the only groups any descriptor declares are five `XOR` input groups. The mode's only
exercise is `PortGraphAnalyzerTest` and the editor's own greying-out logic.

`watermark` is the obvious candidate and does not use it. It declares `image : artifact/image` and
`video : artifact/video` and writes exactly one of them per item, depending on what the item is —
which is precisely "at most one member may have outgoing edges"... except that it is not, quite: an
author may legitimately wire **both**, one to each of two downstream branches, and have whichever
fires do so. That is the question this task has to answer, and answering it is worth more than the
code either way.

**Improvement Summary:** Decide whether `EXCLUSIVE` describes anything the system actually has. If
it does, declare it on a real kind; if it does not, delete the mode rather than carrying a validated,
mirrored, untested path forever.

```
1. Settle the semantics against watermark first. EXCLUSIVE constrains the AUTHOR (at most one member
   may be wired); selective constrains the ENGINE (a consumer of a port that carried nothing is
   skipped). watermark's image|video pair is the second, not the first - an author wiring both is
   doing something sensible. If that reading holds, watermark is NOT an EXCLUSIVE candidate, and
   the honest outcome of this task is deletion plus a note saying why.
2. Before deleting, look for a real one. An EXCLUSIVE group is right where two outputs are mutually
   exclusive by CONFIGURATION rather than per item - the node is set up to emit one shape or the
   other for the whole run - because then wiring both is an author error the editor should refuse
   while they are looking at it.
3. If none exists: delete PortGroupMode.EXCLUSIVE, validateExclusiveOutputs, the factory, the TS
   mirror's branch and the editor's greying-out for it, and drop the EXCLUSIVE arm from Task 4.
   Record the deletion and its reasoning in NODE_DATA_TYPES_PLAN.md §3 as a divergence - it is a
   design decision being reversed, which is exactly what that section is for.
4. If one exists: declare it, regenerate the descriptor resource, and Task 4's second arm becomes
   testable end to end.
```

**References:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §3.2, §9 gap 7 ·
[../concept/NODE_DATA_TYPES_PLAN.md](../concept/NODE_DATA_TYPES_PLAN.md) §4.1, §3 (record the decision here) ·
[../features/nodes/watermark/NODE_WATERMARK.md](../features/nodes/watermark/NODE_WATERMARK.md)

**Test Requirements:** If declared: a `PortGraphAnalyzerTest` case over the **shipped** descriptor
(not a test fixture) rejecting both members wired, and a `pipeline-ports-mocked.spec.ts` case that
the editor greys out the sibling. If deleted: `PortGraphAnalyzerTest` shrinks and the TS suites still
pass. Either way run `mvn test -pl loom-shared/node-model,loom/pipeline` and
`cd loom-ui && ./node_modules/.bin/vitest run src/features/pipeline`.

---

## Task 11: The TypeScript mirrors are pinned against hand-transcribed fixtures

**Argumentation Summary:** `contentTypes.ts` and `portResolvers.ts` re-implement the assignability
lattice and all four dynamic-port resolvers in TypeScript, because round-tripping every drag in the
editor over HTTP is not viable. Both have contract tests — and both tests carry a fixture **typed in
by hand** from the Java side. So a Java-side change to the vocabulary or to a resolver does not fail
the TypeScript build; it fails a reviewer's attention, once. The mirrors have already grown apart
once in spirit: `portResolvers.ts` gained the filter's bucket ports and is now 239 lines against the
101 of `contentTypes.ts`.

**Improvement Summary:** Export the fixture from Java as part of the build and have the vitest suites
read it, so a divergence is a red test rather than a review miss.

```
1. Export from the side that owns the truth. A small test-scoped generator in loom-shared/node-model
   writing loom-ui/src/features/pipeline/__fixtures__/contentTypes.json (the full
   ContentTypeRegistry.all() plus a table of isAssignable verdicts) and .../portResolvers.json (the
   ScriptValueType -> {contentType, cardinality} map, the prompt-port prefix and fallback, and the
   filter's reserved port ids).
2. Follow the precedent already in the tree rather than inventing a mechanism: node-descriptors.json
   is generated into a committed resource and guarded by a golden test. Do the same here - commit
   the fixtures, and fail the Java build when the regenerated form differs.
3. Rewrite contentTypes.test.ts and portResolvers.test.ts to iterate the fixture instead of
   restating it. The assertions stay; only their input moves.
4. Do NOT serve these at runtime. The vocabulary is already served by
   /api/v1/pipeline/content-types; this is a build-time drift guard for the mirrored *rules*, and
   the mirrors exist precisely so the editor need not call the server.
```

**References:** [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) §10, §9 gap 9 ·
[../concept/NODE_DATA_TYPES_PLAN.md](../concept/NODE_DATA_TYPES_PLAN.md) divergence 16, §4.2 ·
[../loom/ui/LOOM_UI_PIPELINE_EDITOR.md](../loom/ui/LOOM_UI_PIPELINE_EDITOR.md)

**Test Requirements:** A Java golden test failing when the committed fixtures are stale (model it on
`NodeSpecGoldenTest`). `contentTypes.test.ts` and `portResolvers.test.ts` reading the fixtures and
still green. Prove it works by adding a content type locally and watching both sides fail. Run
`mvn test -pl loom-shared/node-model` and
`cd loom-ui && ./node_modules/.bin/vitest run src/features/pipeline`.

---

## Task 12: Server-side port resolution — ⛔ NOT OWNED HERE

This is [NODE_SCHEMA_TASKS.md](NODE_SCHEMA_TASKS.md) **Task 1** ("Serve `resolvePorts` over REST and
stop mirroring it in TypeScript"), which states the problem better than a duplicate here would: it
notes the second caller (`GetNodeDescriptorTool`, the MCP tool) and that the TypeScript mirror has
already been behind once — it was written for three resolvers and `FilterPortResolver` had to be
added separately.

The number is kept so it is not silently reused. **Do not add a second version of this task.** Its
outcome decides Task 11 above: if the resolver mirror is deleted, only the assignability lattice
still needs a generated fixture.

---
_Git HEAD revision: `67000540`_
_Last updated: 2026-08-16 (new file — work items split out of features/nodes/NODE_DATA_TYPES.md §9,
which was re-verified against the tree on the same day. Four gaps that file listed are closed and are
not carried here; Task 1 is new, found while re-verifying §4.6)_
