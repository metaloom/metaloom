# Node Schema — Task List

> Work items for the node contract surface, derived from a code audit on 2026-08-11.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../features/pipeline/NODE_SCHEMA.md](../features/pipeline/NODE_SCHEMA.md) (technical
> spec) · [../features/pipeline/NODE_DATA_TYPES.md](../features/pipeline/NODE_DATA_TYPES.md) (what the
> contract means) · [../plans/NODE_REGISTRATION_PLAN.md](../plans/NODE_REGISTRATION_PLAN.md) (how a
> worker announces one)
>
> **Ordering.** Task 1 is the only real machine gap and blocks nothing — do it first, on its own.
> Task 2 is a half-day guard against a failure mode that is already possible. Task 4 is documentation
> hygiene and can run in parallel with anything. Task 3 and Task 5 are optional and should not be
> started before Task 1 lands: both become smaller or unnecessary once the resolver is served.
>
> **No test database is needed** for `loom-shared/node-model` or `cortex/api` work — those modules have
> no DB dependency. Tasks touching `loom/core` or `loom/services/rest` need `./setup-pool.sh`.

---

## Task 1: Serve `resolvePorts` over REST and stop mirroring it in TypeScript

**Argumentation Summary:** `NodeDescriptorRegistry.resolvePorts(nodeId, options)` is what decides a
`script`, `llm`, `vlm` or `filter` node's real ports, and it has two callers: `PortGraphAnalyzer`
(save time and run start) and the MCP tool `GetNodeDescriptorTool`. It is **not reachable over REST** —
the descriptor endpoint serves the static descriptor and no route accepts `nodeId` + `options`. So
`loom-ui` re-implements all four Java resolvers in TypeScript in
[loom-ui/src/features/pipeline/portResolvers.ts](../../loom-ui/src/features/pipeline/portResolvers.ts),
pinned only by `portResolvers.test.ts` mirroring `NodePortResolverTest` **by hand**. That is two
implementations of one rule set, and the mirror has already been behind once: it was written for three
resolvers and `FilterPortResolver` had to be added to it separately. A divergence shows up as a canvas
that draws handles the server then rejects at save time — the worst possible place to learn about it.

**Improvement Summary:** Add a `POST` route that returns `ResolvedPorts` for a node id plus an options
block, add the client method, then either delete `portResolvers.ts` or demote it to an explicitly
optimistic pre-render pinned by a contract test against the route.

```
1. Add a request model `NodeResolvePortsRequest` in loom-shared/rest-model
   (io.metaloom.loom.rest.model.nodes) with a single field:
       Map<String, Object> options
   and a response model `NodeResolvePortsResponse` with the four ResolvedPorts fields:
       List<PortSpec> inputs, outputs; List<PortGroup> inputGroups, outputGroups
   Register both in Examples / Assertions per spec/guidelines/CODING.md.

2. In loom/services/rest/.../endpoint/impl/NodeDescriptorEndpoint.java add:
       POST <basePath>/:nodeId/resolve-ports
   Handler: read the body, call registry.resolvePorts(nodeId, options), 404 with the same
   message shape as the existing /:nodeId handler when the registry returns null, else 200.
   Leave it UNAUTHENTICATED, matching the sibling descriptor routes - the editor calls it
   before a token is in hand, and it exposes nothing the unauthenticated GET does not.
   Route ordering: register it AFTER the plain /:nodeId GET; they differ by method and
   suffix, but keep the order explicit so a future GET /:nodeId/... cannot shadow it.

   Do NOT make this a GET with options in the query string. A script node's `outputs`
   value is a JSON array and does not survive a query parameter cleanly.

3. Add the client method in loom-client (ClientMethods + AbstractLoomClient +
   LoomHttpClientImpl) as resolveNodePorts(String nodeId, Map<String,Object> options).
   Mirror it in clients/python/loom_client - the parity test guards the method count, so
   this is not optional (see spec/loom/PYTHON_CLIENT.md).

4. Regenerate the OpenAPI document from inside loom/doc.

5. loom-ui: call the route from PipelineEditor on a debounced options change for nodes whose
   descriptor sets dynamicPorts. Then pick one and write down which:
     (a) delete portResolvers.ts and render handles from the server response, or
     (b) keep it as an optimistic pre-render, retitle its header comment to say so, and add
         a contract test that feeds the same fixtures to both the TS mirror and the route.
   Option (a) is preferred; (b) is acceptable only if the canvas latency is measurably bad.
   BucketListEditor.tsx imports FILTER_RESERVED_BUCKET_IDS from portResolvers - that constant
   must survive either way.
```

**References:** [../features/pipeline/NODE_SCHEMA.md](../features/pipeline/NODE_SCHEMA.md) §7 ·
[../features/pipeline/NODE_DATA_TYPES.md](../features/pipeline/NODE_DATA_TYPES.md) §3.4 ·
`GetNodeDescriptorTool` javadoc (which states the gap explicitly) ·
[../loom/PYTHON_CLIENT.md](../loom/PYTHON_CLIENT.md)
**Test Requirements:** Extend `NodeDescriptorEndpointTest` rather than adding a class — a `script`
node with three declared outputs yields three ports with the mapped content types **and
cardinalities**; an `llm` node with two prompts yields one port per prompt; an `llm` node with none
yields the single fallback `result`; a `filter` node with two buckets yields two selective ports plus
`other`/`passed`/`bucket`; a fixed node id returns its static ports unchanged; an unknown node id
returns 404; empty and malformed options degrade without throwing. Mirror the case list in
`NodePortResolverTest` so the two cannot diverge. Plus the Python parity test and the loom-ui test
chosen in step 5.
`./setup-pool.sh && mvn -o -pl loom/core test -Dtest=NodeDescriptorEndpointTest`

---

## Task 2: Fail the build when the staged website snapshot is stale

**Argumentation Summary:** `NodeDescriptorGenerator` writes
`loom/doc/src/main/generated/node-descriptors.json`, and that file is staged into
`website/static/pipeline-editor/node-descriptors.json` **by a manual copy — its own javadoc says
"nothing does it automatically"**. The offline website editor has no backend and reads only that
staged file, so once it rots the editor silently serves an old contract: missing nodes, stale ports,
and validation that disagrees with the server for reasons a user cannot see. `NodeDescriptorGeneratorTest`
covers generation; nothing covers staging. The file happens to be in sync at this revision (verified:
identical for all 44 shared node ids plus `loom-fetch`, 40 content types), which is exactly when the
guard is cheap to add.

**Improvement Summary:** A test that regenerates the snapshot into a temp directory and compares it
with the staged copy, failing on drift with the regeneration command in the message.

```
1. Add NodeDescriptorSnapshotStalenessTest in loom/doc/src/test/java/io/metaloom/loom/doc/.

2. Run NodeDescriptorGenerator against a temp working directory (the generator writes a
   relative path, so either parameterise the output file or run it with a temp user.dir),
   then read website/static/pipeline-editor/node-descriptors.json.

3. Compare semantically, not byte-for-byte: parse both with Jackson and compare the
   nodeDescriptors array keyed by nodeId plus the contentTypes array. A pretty-printing or
   key-order difference must not fail the build; a contract difference must.
   Report the differing node ids, not a 160 KB diff - copy the reporting approach in
   NodeSpecGoldenTest, which already solved this.

4. Failure message must name the exact staging step so the fix needs no archaeology.

5. Locate the website checkout relative to the module root and SKIP (assumption-style, not
   fail) when it is absent, so the test does not break a partial checkout.
```

**References:** [../features/pipeline/NODE_SCHEMA.md](../features/pipeline/NODE_SCHEMA.md) §6.3 ·
`NodeDescriptorGenerator` javadoc · [../website/WEBSITE_PIPELINE_EDITOR.md](../website/WEBSITE_PIPELINE_EDITOR.md)
**Test Requirements:** The new `NodeDescriptorSnapshotStalenessTest` passes on a synced tree and fails
on a mutated staged file (verify by editing a node id locally before reverting).
`mvn -o -pl loom/doc test`

---

## Task 3: Generate a JSON Schema for the pipeline definition (optional)

**Argumentation Summary:** An editor or an agent composing a definition JSON gets no shape checking
before `POST`ing it. Every structural mistake — a misspelled top-level key, an edge missing `toPort`,
`options` that is not an object — comes back as a server-side validation error instead of being caught
where it was typed. `NodeDescriptor` already carries `@JsonPropertyDescription` on every field, so most
of the schema is derivable.

**Improvement Summary:** Emit `pipeline-definition.schema.json` beside the node-descriptor snapshot,
covering the definition envelope and per-node `options` blocks derived from each descriptor's
`parameters`.

```
1. Add PipelineSchemaGenerator in loom/doc/src/main/java/io/metaloom/loom/doc/impl/,
   registered in ExampleGenerator alongside NodeDescriptorGenerator.

2. Emit draft 2020-12. Structure:
     - the definition envelope: nodes[], edges[] with from/fromPort/to/toPort
     - a per-nodeId oneOf over the `type` discriminator, whose `options` object is built
       from that descriptor's parameters (key, ParameterType -> JSON type, enum values
       from `values`, min/max from the bounds)

3. Stage it next to the snapshot in website/static/pipeline-editor/ and cover it with the
   Task 2 staleness guard.

4. Write the ceiling into the file's own description: JSON Schema expresses SHAPE only.
   It cannot express content-type assignability, XOR group satisfaction, fan-out
   compatibility or cycle freedom. It is a typo-catcher in front of
   PipelineValidationService, never a replacement for it.

5. Do NOT emit port lists for dynamicPorts node ids - after Task 1 the resolve-ports route
   is the answer for those, and a static list would contradict it.
```

**References:** [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §9.2 ·
[../features/pipeline/PIPELINE_VALIDATION.md](../features/pipeline/PIPELINE_VALIDATION.md) ·
[../features/pipeline/NODE_SCHEMA.md](../features/pipeline/NODE_SCHEMA.md) §11
**Test Requirements:** `PipelineSchemaGeneratorTest` — the emitted schema is valid JSON Schema, every
definition seeded by `DemoDatabaseInitializer` validates against it, and a definition with a misspelled
edge key does not. `mvn -o -pl loom/doc test`

---

## Task 4: Reconcile the stale node-count and resolver claims across the spec tree

**Argumentation Summary:** The counts in this area have been wrong in several files at once, and each
wrong number gets copied forward. At this revision the truth is **45 advertised node ids** (44
generated plus `loom-fetch`), **2 descriptor providers**, **40 content types** and **4 port
resolvers**. Against that: [../features/pipeline/NODE_DATA_TYPES.md](../features/pipeline/NODE_DATA_TYPES.md)
§3.3 still says "41 kinds from 26 providers" and warns that PIPELINE.md says "25 providers / 39 kinds";
§3.4 lists three resolvers and omits `filter`, and its closing note describes a three-resolver
TypeScript mirror. [../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) tells an author to bump
`NodeDescriptorServiceLoaderTest` to 43 when the test asserts 45. An agent reading any of these will
either write a wrong number into a fifth file or conclude the code is broken.

**Improvement Summary:** One sweep that replaces every hardcoded count with the current value and, where
a count is genuinely volatile, with the command that recomputes it.

```
1. NODE_DATA_TYPES.md §3.3: replace the kind/provider table with 45 node ids / 2 providers,
   and rewrite the descriptor-vs-runnable bullets to match NODE_SCHEMA.md §8 - descriptor
   but not runnable is {loom-fetch}; runnable but no descriptor is {sha512-dedup,
   asset-source}. facedescription is now BOTH (it has a @StringKey binding); the current
   text lists it as unrunnable. Remove the stale "PIPELINE.md says 25/39" warning once
   PIPELINE.md itself is fixed.

2. NODE_DATA_TYPES.md §3.4: add `filter` / FilterPortResolver to the resolver table; change
   "Three kinds" to four; update §10 (TypeScript Mirror) to say four mirrored resolvers.
   Point the closing "resolvePorts has exactly one caller" note at NODE_SCHEMA.md §7 and
   correct it - there are two callers now, PortGraphAnalyzer and GetNodeDescriptorTool.

3. PIPELINE.md §8: correct the provider/kind counts; link to NODE_SCHEMA.md §9 for the
   recount commands instead of restating numbers.

4. NODES.md §5.2 and NEW_NODE.md: bump the NodeDescriptorServiceLoaderTest count to 45.
   In NEW_NODE.md, phrase it as "whatever the test currently asserts, plus one" so the next
   node author does not have to re-audit.

5. Grep the tree for other hardcoded counts before finishing:
     grep -rn "26 providers\|34 kinds\|41 kinds\|39 content types\|three resolvers" spec/
```

**References:** [../features/pipeline/NODE_SCHEMA.md](../features/pipeline/NODE_SCHEMA.md) §9 (the
authoritative counts and the recount commands) · [../guidelines/SPEC_RULES.md](../guidelines/SPEC_RULES.md)
**Test Requirements:** No code test. Verify each replacement number with the commands in NODE_SCHEMA.md
§9 rather than copying it from this task file, and re-run
`mvn -o -pl loom-shared/node-model test -Dtest=NodeDescriptorServiceLoaderTest` to confirm the asserted
count before writing it into prose.

---

## Task 5: Decide whether authored node cards are needed — measure first (optional)

**Argumentation Summary:** The predecessor concept proposed a per-node authored prose card
(`<kind>.node.md`) carrying what no generator can produce: that `hash-dedup` moves files, that
`scene-layout` must share a worker with `depthmap`, when to reach for `ocr` instead of `whisper`. Two
of the three original motivations have since been answered elsewhere — parameter scope by
`@ParamDoc(hidden = true)`, and node prose, persistence targets and side effects by
[../features/nodes/NODES.md](../features/nodes/NODES.md) and the per-node specs under
`features/nodes/`. What remains is genuinely unanswered but is also unmeasured: nobody has established
that an agent authoring a pipeline actually gets these wrong. Writing 45 cards on a hunch produces 45
files to maintain and a new drift surface; writing none may leave a real failure mode in place.

**Improvement Summary:** Run the usability check first and let its results decide. Build cards only for
what the check proves is missing, and only after Task 1 removes the port question from the picture.

```
1. THE MEASUREMENT, first and separately. Hand a fresh agent ONLY:
     - the /api/v1/pipeline/node-descriptors response
     - the resolve-ports route from Task 1
   and ask it to author three pipelines of increasing difficulty (a hash chain; a
   transcribe-then-classify graph; a graph using the scene-layout/depthmap pair).
   Record every mistake and classify it: missing prose, wrong port, wrong parameter scope,
   or a node it should not have chosen. Write the results into this task before proceeding.

2. If the mistakes are prose-shaped, add the missing prose to the EXISTING per-node spec
   under spec/features/nodes/<kind>/ first. That file already exists for many nodes and
   costs nothing new to maintain. Only if that is structurally impossible - because the
   content must be machine-readable or must ship in the jar - proceed to step 3.

3. If cards are still justified: pilot exactly ONE, on `whisper` (it has an XOR group, real
   worker-scoped parameters, a persistence target, and a documented multi-track caveat).
   Location: loom-shared/node-model/src/main/resources/io/metaloom/loom/nodes/schema/.
   Review the TEMPLATE against the step-1 mistake list before writing a second card.

4. A card must NEVER contain: port ids, content types, cardinalities, groups, parameter
   types or bounds. All of those are served and guarded; restating them in 45 files
   guarantees 45 stale copies. A card carries only what no generator can produce.

5. A card must never state a rule PortGraphAnalyzer cannot enforce. A schema that can
   express what the engine cannot check is a lie with good formatting.

6. `whenNotToUse` must NAME THE ALTERNATIVE node id. "Do not use whisper for on-screen
   text" is useless; "use `ocr`" is the entire value of the field.

7. Only if cards ship: NodeSchemaCoverageTest in loom-shared/node-model asserting set
   equality in BOTH directions between card files and registry node ids - a card for a
   deleted node id must fail as loudly as a node id with no card.
```

**References:** [../features/pipeline/NODE_SCHEMA.md](../features/pipeline/NODE_SCHEMA.md) §11 ·
[../features/nodes/NODES.md](../features/nodes/NODES.md) · `spec/features/nodes/<kind>/`
**Test Requirements:** Step 1 is not automatable and produces a written finding, not a test. If cards
ship, `NodeSchemaCoverageTest` (set equality both ways, and `typicalUpstream`/`typicalDownstream` naming
known node ids) plus `NodeSchemaExampleTest` in `loom/pipeline`: every definition fragment embedded in a
card parses and validates through `PipelineGraphParser` **with a non-null descriptor registry** —
`PortGraphAnalyzer.analyze` returns immediately on a null registry and the no-arg `PipelineGraphParser`
constructor supplies one, so a test that forgets this asserts nothing.
`mvn -o -pl loom-shared/node-model,loom/pipeline test`

---

## Task 6: Point `NodeDescriptorGenerator` at the harvest, or record why it does not

**Argumentation Summary:** `NodeDescriptorGenerator` builds its registry from
`ServiceLoader<NodeDescriptorProvider>`, which is one indirection removed from the harvest that
produces the contracts. Today that is harmless — the ServiceLoader path resolves to
`GeneratedNodeDescriptorProvider`, which reads the committed resource that `NodeSpecGoldenTest` keeps
identical to a fresh harvest — so the two agree by construction. It is listed here because
[../plans/NODE_REGISTRATION_PLAN.md](../plans/NODE_REGISTRATION_PLAN.md) Phase 1 still carries it as an
open checkbox, and an open checkbox with no stated resolution invites someone to "fix" a path that is
already correct.

**Improvement Summary:** Either wire the generator to the harvest directly, or close the checkbox with
the reason the ServiceLoader path is equivalent. The second is the recommended outcome.

```
1. Confirm the equivalence still holds: GeneratedNodeDescriptorProvider is the only
   contributor of harvested contracts, OrphanNodeDescriptorProvider adds loom-fetch, and
   NodeSpecGoldenTest fails the build on a stale resource.

2. Recommended: close the Phase 1 checkbox in NODE_REGISTRATION_PLAN.md with a one-line
   note - "the ServiceLoader path reads the harvest; NodeSpecGoldenTest guards it" - and
   drop this task.

3. Only if a real divergence is found (a provider contributing contracts the harvest does
   not produce), change NodeDescriptorGenerator to call the harvest and cover it in
   NodeDescriptorGeneratorTest.

Do not make the change without step 1 producing a concrete divergence. loom/doc must not
grow a dependency on integration-test to reach the harvester.
```

**References:** [../plans/NODE_REGISTRATION_PLAN.md](../plans/NODE_REGISTRATION_PLAN.md) Phase 1 ·
[../features/pipeline/NODE_SCHEMA.md](../features/pipeline/NODE_SCHEMA.md) §5.1, §13
**Test Requirements:** `mvn -o -pl loom/doc test` (`NodeDescriptorGeneratorTest`) and
`mvn -o -pl integration-test test -Dtest=NodeSpecGoldenTest` must both stay green. If the outcome is
step 2, no test change is required.

---
_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (created from the audit that converted concept/NODE_SCHEMA_CONCEPT.md into features/pipeline/NODE_SCHEMA.md)_
