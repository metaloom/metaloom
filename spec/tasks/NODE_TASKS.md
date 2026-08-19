# Cortex Nodes — Task List

> Work items for the `cortex/nodes/*` family, derived from a code audit on 2026-08-11 that migrated
> fourteen node plans out of `spec/concept/` into `spec/features/nodes/<kind>/NODE_*.md`.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../features/nodes/NODES.md](../features/nodes/NODES.md) (the node system) ·
> [../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) (rules for adding one) ·
> [../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md) (the port model)
>
> **Scope of this file:** cross-cutting defects and the highest-severity per-node work. Each node
> spec carries its own `Progress Assessment` section with the full follow-up list for that kind —
> see the routing table in §"Per-node follow-ups" at the end. Do not duplicate those here.
>
> **Blocking / severity order:** Tasks 1-4 are correctness or trust-boundary defects that silently
> produce wrong results or expose the worker. Tasks 5-9 are wrong-output defects. Tasks 10-14 are
> contract and documentation defects a user can see. Tasks 15-20 are coverage and hygiene.

---

## Task 1: Gate `s3-sink` on rights release before bytes leave the system

**Argumentation Summary:** `s3-sink` is a byte-carrying exit from MetaLoom, and today any asset a
pipeline can reach can be uploaded to an external bucket with no rights check.
[../workflows/WORKFLOW_RIGHTS_RELEASE.md](../workflows/WORKFLOW_RIGHTS_RELEASE.md) §2.6 names this
node as the exit that "would need the check", and calls `ExportGateTest` its critical guard — that
test does not exist. The node itself is otherwise careful (it never deletes a local file on a failed
upload), which makes the missing gate the one silent-loss shape it does not cover.

**Improvement Summary:** Add a release/licence gate to `S3SinkNode` that refuses to upload an asset
whose rights state does not permit export, and cover it with the `ExportGateTest` the workflow spec
already assumes.

```
1. Read spec/workflows/WORKFLOW_RIGHTS_RELEASE.md §2.6 and the rights columns it names.
2. In cortex/nodes/s3-sink/core/src/main/java/io/metaloom/cortex/node/s3/S3SinkNode.java, before
   uploading an artifact, resolve the asset's rights state through the Loom client and skip-with-flag
   (not fail) when export is not permitted. Emit the reason on the existing `flag` port.
3. Add a node option `requireRightsRelease` (default true) in S3SinkNodeOptions with @ParamDoc so it
   reaches the descriptor; regenerate node-descriptors.json and the NodeSpecGoldenTest golden.
4. Write ExportGateTest in cortex/nodes/s3-sink/core/src/test/ covering: permitted -> uploaded,
   not permitted -> skipped + flag, option off -> uploaded regardless.
5. Update spec/features/nodes/s3-sink/NODE_S3SINK.md and the WORKFLOW_RIGHTS_RELEASE.md rows that
   currently describe this as missing.
```

**References:** [../features/nodes/s3-sink/NODE_S3SINK.md](../features/nodes/s3-sink/NODE_S3SINK.md) ·
[../workflows/WORKFLOW_RIGHTS_RELEASE.md](../workflows/WORKFLOW_RIGHTS_RELEASE.md) §2.6, §163, §215
**Test Requirements:** `ExportGateTest` (new) plus the existing `S3SinkNodeTest` suite must pass:
`mvn -pl cortex/nodes/s3-sink/core test`.

---

## Task 2: `script` advertises two security options that enforce nothing

**Argumentation Summary:** `ScriptNodeOptions.allowNetwork` and `allowFilesystem` are rendered as
checkboxes on the node form and carried through `configure()` into `ScriptLimits` — and then read by
nobody. `GraalJsCompiledScript.newContext()` never consults them and `install()` never adds an
`http`/`fs` binding. The control is inert in both directions: turning it off protects nothing, and
turning it on grants nothing. Worse, `trusted` defaults to **true**, which means `allowAllAccess(true)`
— a pipeline author's script runs with full worker privileges out of the box, and reaches network and
filesystem regardless of either checkbox. There is also no memory bound at all; only `statementLimit`
and the `script-node-watchdog` wall clock exist.

**Improvement Summary:** Either implement the two guards against the Graal context or delete them
from the options and the descriptor. Then make the default trust level an explicit, documented
decision rather than an accident.

```
1. Decide per option: implement or remove. Implementing means gating IOAccess/host lookups in
   cortex/nodes/script/core/src/main/java/io/metaloom/cortex/node/script/engine/js/
   GraalJsCompiledScript.newContext(), and refusing the combination trusted=true + allowNetwork=false
   rather than silently ignoring it.
2. If removing: drop the fields from ScriptNodeOptions.java:67-71, from ScriptLimits, from
   ScriptNode.configure(), regenerate node-descriptors.json and the golden.
3. Separately decide the default of `trusted`. If it stays true, say so in the descriptor description
   and on website/content/english/docs/nodes/script/index.adoc in one sentence a user cannot miss.
4. Document the absence of a memory cap (Truffle ResourceLimits needs the optimized runtime) in
   spec/features/nodes/script/NODE_SCRIPT.md rather than leaving it implied.
5. Add tests asserting the enforced behaviour for each surviving option.
```

**References:** [../features/nodes/script/NODE_SCRIPT.md](../features/nodes/script/NODE_SCRIPT.md) §sandbox ·
`ScriptNodeOptions.java:67-71` · `ScriptNode.java:215-220,262-266` · `engine/ScriptLimits.java`
**Test Requirements:** New cases in `ScriptOptionsValidationTest` / `ScriptNodeTest`; the descriptor
golden `NodeSpecGoldenTest` must be regenerated and pass: `mvn -pl integration-test test -Dtest=NodeSpecGoldenTest`.

---

## Task 3: `ScriptNode` does not override `nodeId()` and collides in the ledger

**Argumentation Summary:** `ScriptNode` implements `PipelineConfigurable` but never overrides
`AbstractMediaNode.nodeId()`, which returns `""`. `AssetNodeResultDaoImpl.java:101` upserts on
`(asset_uuid, node_kind, node_id)`, so two `script` nodes in one pipeline share a single
`asset_node_result` row: the second silently overwrites the first's state, reason and
`producerVersion`. `AbstractMediaNode.nodeId()`'s own javadoc calls out exactly this case, and
`s3-sink`, `tag`, `metadata` and `relocate` all override it. The JSON component is safe (it is keyed
per `variant`), which is why `ScriptNodeIntegrationTest` does not catch the ledger collision.

**Improvement Summary:** Override `nodeId()` in `ScriptNode` to return the configured instance id, and
extend the integration test to assert two script instances produce two ledger rows.

```
1. In cortex/nodes/script/core/src/main/java/io/metaloom/cortex/node/script/ScriptNode.java, override
   nodeId() to return the existing private `nodeId` field (set from the pipeline definition).
2. Extend integration-test/.../node/ScriptNodeIntegrationTest.java (see line 138) with a two-instance
   pipeline and assert two distinct asset_node_result rows survive.
3. Audit the remaining PipelineConfigurable implementors for the same omission and fix any found.
```

**References:** [../features/nodes/script/NODE_SCRIPT.md](../features/nodes/script/NODE_SCRIPT.md) §8, §12 ·
`cortex/common/.../AbstractMediaNode.java:119` · `AssetNodeResultDaoImpl.java:101`
**Test Requirements:** `ScriptNodeIntegrationTest` extended and green: `mvn -pl integration-test test -Dtest=ScriptNodeIntegrationTest`.

---

## Task 4: Two `imagegen` instances in one graph overwrite each other's output

**Argumentation Summary:** `ImageGenNode.resolveImagePath` names the output
`metaPath/imagegen_bin/<segment>/<sha512>.png` from the **source** asset's hash with no options
digest, and `resultCache` is keyed on `media.absolutePath()` alone. Two `imagegen` nodes in one
pipeline — the obvious way to render two prompts — therefore write to the same path and serve each
other's cached result. `sam2` and `image-manipulation` both put a digest in the directory name for
exactly this reason. The node also does not implement `PipelineConfigurable`, so its options come from
the worker YAML and the `prompt` port is the only way two instances can differ — and then their
outputs collide anyway.

**Improvement Summary:** Include an options digest in both the output path and the cache key, and make
the node `PipelineConfigurable` so per-instance options work at all.

```
1. In cortex/nodes/image-generation/core/.../ImageGenNode.java:82-86,175-181, derive a digest over the
   result-affecting options (prompt, model, mode, size, steps, seed) and include it in the directory
   name, copying the pattern in Sam2Node / ImageManipulationNode.
2. Include the same digest in the LocalResultCache key.
3. Implement PipelineConfigurable on ImageGenNode (see cortex/common/.../node/PipelineConfigurable.java
   and the tag/s3-sink implementations) and override nodeId(); see Task 3 for why.
4. Add a test with two instances differing only by options and assert two distinct files and two
   ledger rows.
```

**References:** [../features/nodes/image-generation/NODE_IMAGEGEN.md](../features/nodes/image-generation/NODE_IMAGEGEN.md) ·
[IMAGEGEN_NODE.md](IMAGEGEN_NODE.md) (existing follow-ups; do not duplicate)
**Test Requirements:** New case in `cortex/nodes/image-generation/core/src/test/`; `ImageGenNodeIntegrationTest` still green.

---

## Task 5: `ctx.failure(...).next()` reports SUCCESS — fix the remaining nodes — DONE (2026-08-18)

> Completed together with [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) **Task 17**, which owned the
> cross-cutting sweep. All four sites named below (`DepthmapNode`, `SceneLayoutNode`,
> `FingerprintDedupNode` ×2) and `CaptioningNode`'s `printStackTrace()` shape are fixed; the sweep in
> step 2 found 15 chained sites in 13 classes, not nineteen, and the surviving list in
> [../features/nodes/NODES.md](../features/nodes/NODES.md) is now empty. Step 4 landed as a
> source-scanning guard (`FailurePathGuardTest`, `cortex/api`) rather than as prose in
> [../guidelines/METALOOM_STATIC_CODE_ANALYSIS.md](../guidelines/METALOOM_STATIC_CODE_ANALYSIS.md);
> that file records the outcome.

**Argumentation Summary:** `NodeContextImpl.next()` never reads `failureCause`, so
`ctx.failure(msg).next()` drops the message and reports the item successful. Only `.abort()` reads it.
This migration confirmed the pattern in `DepthmapNode.java:184`, `SceneLayoutNode.java:218` and
`FingerprintDedupNode.java:100,181` (its sibling `FingerprintDedupApplyNode` already aborts). A
related shape exists in `CaptioningNode.java:104-107`, which catches `Exception`, calls
`printStackTrace()` and returns `NodeResult.failed()` with no ledger row and no logger at all. Each of
these writes a correct `FAILED` ledger row first, which is precisely why no test catches the wrong
return state.

**Improvement Summary:** Convert the remaining `.next()` failure paths to `.abort()` and give
`CaptioningNode` a real failure path, then add a guard so the pattern cannot come back.

```
1. Fix, in order: DepthmapNode.java:184, SceneLayoutNode.java:218, FingerprintDedupNode.java:100,181,
   CaptioningNode.java:104-107 (record a FAILED ledger row + ctx.failure(msg).abort(), drop
   printStackTrace).
2. Sweep the tree for the remaining occurrences: grep -rn "failure(" cortex/nodes | grep "next()".
   The known count was nineteen nodes; record the surviving list in NODES.md.
3. Add a test per fixed node asserting the returned state is a failure and the cause is preserved.
4. Consider a static check in spec/guidelines/METALOOM_STATIC_CODE_ANALYSIS.md's scope.
```

**References:** [../features/nodes/depthmap/NODE_DEPTHMAP.md](../features/nodes/depthmap/NODE_DEPTHMAP.md) ·
[../features/nodes/scene-layout/NODE_SCENE_LAYOUT.md](../features/nodes/scene-layout/NODE_SCENE_LAYOUT.md) ·
[../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) ·
[../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md](../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md)
**Test Requirements:** One failure-state test per touched node module; `mvn -pl cortex/nodes/depthmap/core,cortex/nodes/scene-layout/core,cortex/nodes/dedup/core,cortex/nodes/captioning/core test`.

---

## Task 6: Result caches keyed on the file path ignore the options that produced the result

**Argumentation Summary:** `DepthmapNode` (`compute`, ~line 131), `SceneLayoutNode.java:135-142` and
`ImageGenNode` all key `LocalResultCache` on `media.absolutePath()` alone. Re-running with a changed
`mode`, `model`, `maxDim` or prompt is served the previous result for the lifetime of the worker, and
a cache hit also skips `persist(...)`, so the schema keeps the old value. `DominantColorNode.cacheKey`
(line ~420) is the composite model to copy — though note it uses `Objects.hash(...)`, a 32-bit int,
which `NODES.md:416` incorrectly documents as `sha256(...)`.

**Improvement Summary:** Give every caching node a composite cache key over the result-affecting
options, and decide once whether a cache hit should still persist.

```
1. Introduce a shared helper (cortex/common) that builds a stable digest from a node's
   result-affecting options, and use it in DepthmapNode, SceneLayoutNode, ImageGenNode and
   DominantColorNode. Prefer a stable string digest over Objects.hash.
2. Decide and document: does a cache hit re-persist? Today it does not, so a wiped component is never
   rewritten. Record the decision in NODES.md §caching.
3. Correct NODES.md:416, which describes dominant-color's key as sha256.
4. Test: same media + changed option must miss the cache.
```

**References:** [../features/nodes/NODES.md](../features/nodes/NODES.md) §caching ·
[../features/nodes/dominant-color/NODE_DOMINANT_COLOR.md](../features/nodes/dominant-color/NODE_DOMINANT_COLOR.md)
**Test Requirements:** A cache-miss-on-changed-option test in each of the four modules.

---

## Task 7: `PATCH keepAssetUuid` leaves `dedup_group_member.role` pointing at the old keeper

**Argumentation Summary:** `DedupGroupDaoImpl.java:163-173` updates the group's `keepAssetUuid`
pointer but never rewrites the member rows' `role`, so after a reviewer reassigns the keeper the
pointer and the roles disagree. Every reader must know to prefer `keepAssetUuid` — an invariant
nothing enforces and nothing documents at the DAO boundary. `fingerprint-dedup-apply` acts on
confirmed decisions, so a wrong role is a delete-the-wrong-file shape.

**Improvement Summary:** Rewrite the member roles inside the same transaction as the pointer update,
and add a DAO test that asserts pointer and roles agree after reassignment.

```
1. In loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/dedup/DedupGroupDaoImpl.java:163-173,
   update dedup_group_member.role for all members of the group in the same transaction: the new keeper
   becomes KEEP, every other member becomes DUPLICATE.
2. Add DedupGroupDaoTest cases: reassign keeper -> roles rewritten; reassign to a non-member -> rejected.
3. Add a delete-cascade check per spec/guidelines/CODING.md while in this DAO.
```

**References:** [../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) §6 ·
migrations `V2.61__add_dedup_group.sql`, `V2.62`
**Test Requirements:** `DedupGroupDaoTest` extended; run `./setup-pool.sh` first, then
`mvn -pl loom/db/jooq test -Dtest=DedupGroupDaoTest`.

---

## Task 8: `keepExcludeFolder` is advertised on `hash-dedup` but never read

**Argumentation Summary:** The three dedup kinds share one `DedupNodeOptions`, so
`keepExcludeFolder` (`DedupNodeOptions.java:44`) appears as a parameter on the `hash-dedup`
descriptor in `node-descriptors.json` while `HashDedupNode` never reads it. A user who sets it gets
silence. The same sharing leaks other apply-only options into the discovery kinds.

**Improvement Summary:** Split the options so each kind advertises only what it reads, or hide the
parameter per kind with `@ParamOverride(hidden = true)`.

```
1. Audit DedupNodeOptions against HashDedupNode, FingerprintDedupNode and FingerprintDedupApplyNode:
   which fields does each actually read?
2. Hide or split the ones that do not apply; regenerate node-descriptors.json and the
   NodeSpecGoldenTest golden.
3. While in the module, delete the dead move-era imports in HashDedupNode.java:5-7,31 and
   FingerprintDedupApplyNode.java:5-7,36 (FileUtils, Files, Path, FileAlreadyExistsException).
```

**References:** [../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) §6.1
**Test Requirements:** `NodeSpecGoldenTest` regenerated and green; `DedupNodeOptionsValidationTest` still green.

---

## Task 9: `dominant-color` scalar ports claim "the whole frame" and emit `regions[0]`

**Argumentation Summary:** `DominantColorNode.emit()` reads `regions.getJsonObject(0)` for the
`hex`, `term`, `name_en` and `name_de` scalar ports. With `includeWholeImage=false` those ports
silently describe the first configured region or the first detection box, while the pipeline editor
still labels them as the colour of the whole frame. A downstream `filter` or `tag` rule keyed on
`term` is then acting on a crop the user never asked about. Related: `RegionResolver.java:82` sorts
survivors largest-area-first when the `maxRegions` cap trims the list, so the documented emission
order holds below the cap and not above it.

**Improvement Summary:** Make the scalar ports either always describe the whole frame or say plainly
which region they describe, and document the ordering change at the cap.

```
1. Decide: emit the whole-image entry when present and skip the scalar ports otherwise, or rename
   them and correct the @PortDoc text. Prefer the former — a scalar that silently changes meaning is
   the worse contract.
2. Implement in cortex/nodes/dominant-color/core/.../DominantColorNode.java:274-288.
3. Correct the @PortDoc strings and regenerate node-descriptors.json + the golden.
4. Document the maxRegions re-ordering in the node spec's emission-order section.
5. Fix the stale @param javadoc on RegionSource.java:9 ("or the upstream node id" — every detection
   region records the constant "detections" since the typed-port migration).
```

**References:** [../features/nodes/dominant-color/NODE_DOMINANT_COLOR.md](../features/nodes/dominant-color/NODE_DOMINANT_COLOR.md)
**Test Requirements:** New `DominantColorNodeTest` cases for `includeWholeImage=false` and for the cap.

---

## Task 10: Descriptor text that lies to the pipeline editor

**Argumentation Summary:** Three descriptor strings are harvested from `@NodeSpec`/`@PortDoc` into
`node-descriptors.json` and shown in the palette and node form, and all three are wrong:
(a) `sentiment`'s `score` port is documented as polarity but emits **confidence**, which is never
negative — a user thresholding on it gets nonsense (`SentimentNode.java:78`);
(b) `captioning` is named "Image Captioning" with the description "Generate a textual caption for an
image", though it captions video through `videoStrategy` (`CaptioningNode.java:43-44`);
(c) `captioning` hides all 11 video/image options behind `@ParamDoc(hidden = true)`
(`CaptioningNodeOptions.java:22-51`), so the editor shows three flags while the website documents the
full set.

**Improvement Summary:** Correct the two strings, unhide the captioning options, regenerate the
descriptor and its golden.

```
1. Fix SentimentNode.java:78's @PortDoc to describe confidence, or change the emitted value to a
   signed polarity — decide which the workflows want first, then make code and doc agree.
2. Rename the captioning node to cover both media types and rewrite its description.
3. Remove @ParamDoc(hidden = true) from the video/image options that a pipeline author should set.
   Ports do not change, so this is descriptor-only work.
4. Regenerate: mvn ... -Dloom.regenerateNodeDescriptors=true, then update the NodeSpecGoldenTest golden.
5. Update the two website pages to match.
```

**References:** [../features/nodes/sentiment/NODE_SENTIMENT.md](../features/nodes/sentiment/NODE_SENTIMENT.md) ·
[../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md](../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md)
**Test Requirements:** `NodeSpecGoldenTest` green after regeneration; `CaptioningNodeOptionsValidationTest` unaffected.

---

## Task 11: Options that silently do nothing when set to a legal-looking value

**Argumentation Summary:** Four cases, all the same shape — a value passes `validate()` and then
produces silence rather than an error. `captioning`: `maxScenes = 0` makes the
`Math.min(scenes.size(), maxScenes)` loop never run, so the caption is empty
(`CaptioningNodeOptions.java:167-200`); `temperature` is unchecked and only fails at the server.
`script`: `ScriptOutputSpec.parse` reads `segmentType` only for `TIMEFRAMES` entries and silently
drops it elsewhere (`ScriptOutputSpec.java:106-107`). `watermark`: nothing binds `timeoutMs` to the
descriptor, so moving that field off `AbstractNodeOptions` would turn an advertised parameter into a
no-op (`WatermarkNode.java:69-71,209`). `depthmap`: a `model` override pointing at a metric checkpoint
with `mode=RELATIVE` inverts the map — `_normalize` inverts on mode while `_model_id` overrides on
model, and `validate()` checks neither against the other.

**Improvement Summary:** Extend each `validate()` to reject the incoherent combination at
configuration time rather than producing empty or inverted output at run time.

```
1. captioning: require maxScenes >= 1 and temperature in [0, 2] in CaptioningNodeOptions.validate().
2. script: make ScriptOutputSpec.parse throw on a segmentType attached to a non-TIMEFRAMES output,
   matching what the record constructor already does.
3. watermark: add a test that fails if timeoutMs stops reaching the descriptor.
4. depthmap: cross-check mode against the model family in DepthmapNodeOptions.validate(); see
   sidecars/depth/server.py::_normalize and _model_id for the two independent switches.
5. One validation test per case.
```

**References:** the four node specs under [../features/nodes/](../features/nodes/)
**Test Requirements:** New cases in each module's `*OptionsValidationTest`.

---

## Task 12: `scene-layout` reports `truncated.relations` as zero even when it truncated

**Argumentation Summary:** `SceneLayoutNode.java:405` hardcodes `.put("relations", 0)` while
`RelationSolver.solve` (line ~87) genuinely truncates at `maxRelations`. A consumer reading the
`truncated` block to decide whether the layout is complete is told it always is. The neighbouring
`objects` counter is real, which makes the zero look deliberate.

**Improvement Summary:** Return the real truncation count from `RelationSolver` and record it.

```
1. Have RelationSolver.solve report how many relations it dropped (return a small record or an
   out-parameter rather than recomputing).
2. Write it into the truncated block in SceneLayoutNode.java:405.
3. Add a RelationSolverTest case that exceeds maxRelations and asserts the reported count.
```

**References:** [../features/nodes/scene-layout/NODE_SCENE_LAYOUT.md](../features/nodes/scene-layout/NODE_SCENE_LAYOUT.md) §9
**Test Requirements:** `RelationSolverTest` + `SceneLayoutNodePersistenceTest`.

---

## Task 13: `looksBase64` operator-precedence bug in the script output collector

**Argumentation Summary:** `engine/ScriptOutputCollector.java:248` reads
`text.length() > 256 && text.indexOf('/') < 0 || text.endsWith("==")`, which parses as
`(a && b) || c`. Any path-like string ending in `==` is therefore base64-decoded instead of passed
through, and any short base64 blob without `==` is not. The failure is data-dependent and silent.

**Improvement Summary:** Parenthesise the intent, and pin it with cases on both sides of the boundary.

```
1. Fix the expression in cortex/nodes/script/core/.../engine/ScriptOutputCollector.java:248 to the
   intended grouping (decide first: is a trailing "==" sufficient on its own?).
2. Add ScriptNodeTest cases: a long path ending in "==", a short base64 string, a genuine blob.
```

**References:** [../features/nodes/script/NODE_SCRIPT.md](../features/nodes/script/NODE_SCRIPT.md)
**Test Requirements:** `ScriptNodeTest` extended; `mvn -pl cortex/nodes/script/core test`.

---

## Task 14: Customer-facing node pages describe behaviour the code no longer has

**Argumentation Summary:** Five published pages under `website/content/english/docs/nodes/` are stale,
and per [../guidelines/CODING.md](../guidelines/CODING.md) a shipped feature's page is part of the
change. `scene-layout/index.adoc:144` still says the node "relates faces to faces; once object
detection is available…" — `objectdetect` shipped, and the node binds `detection/*`, so it relates any
detector's boxes today; the same page advertises the `INSIDE` predicate, which `RelationSolver` never
emits. `sentiment/index.adoc` and `sidecars/sentiment/README.md` still promise "one row per wired text
source" and show a `source` block the node stopped writing at the typed-port migration.
`captioning/index.adoc:54` repeats a `frameCount` javadoc that `VideoCaptioner.java:70` contradicts
(the value is clamped to 2-4 per scene). `script/index.adoc:40` omits `out.string` from the binding
table. `loom/doc/src/main/docs/cortex/nodes/index.adoc:109-124` lists only two of the three dedup
kinds — `fingerprint-dedup-apply` is missing.

**Improvement Summary:** Correct the five pages against the code, and take the `INSIDE` predicate out
of the docs or implement it.

```
1. Work page by page against the matching spec under spec/features/nodes/<kind>/NODE_*.md.
2. For scene-layout, additionally decide the fate of RelationPredicate.INSIDE: implement it in
   RelationSolver or remove it from the enum and the page.
3. Keep customer tone: no class names, no spec references (spec/website/WEBSITE.md).
4. Rebuild the site to confirm the adoc still renders: website/build.sh.
```

**References:** [../website/WEBSITE.md](../website/WEBSITE.md) · [../guidelines/CODING.md](../guidelines/CODING.md)
**Test Requirements:** `website/build.sh` completes; no test harness covers adoc content, so review the rendered pages.

---

## Task 15: `NODES.md` still describes the pre-supersede dedup family

**Argumentation Summary:** [../features/nodes/NODES.md](../features/nodes/NODES.md) contradicts the
code in four places for one node family: `:224` and `:226` say the dedup nodes have the side effect of
moving files (they emit ports; a downstream `move` node acts), `:602` lists `dupFolder` as the options
key (deleted, replaced by `keepExcludeFolder`), and `:801-802` claims `HashDedupNodeTest` is "a
5-line empty stub" and that `FingerprintDedupApplyNode` "has no unit test at all" — the real counts
are 4 and 12. `NODES.md:597,616` likewise still marks the `s3-sink` `artifacts` option and
`autoDiscover` as "still live" when both are deleted, which
[../features/nodes/NODE_DATA_TYPES.md](../features/nodes/NODE_DATA_TYPES.md):1041 already
records correctly. Because `NODES.md` is the file agents are told to start from, each wrong line
propagates.

**Improvement Summary:** Correct the six lines against the code and the two new node specs.

```
1. Fix NODES.md:224, :226, :602, :801-802 against spec/features/nodes/dedup/NODE_DEDUP.md.
2. Fix NODES.md:597, :616 against spec/features/nodes/s3-sink/NODE_S3SINK.md and NODE_DATA_TYPES.md:1041.
3. Fix NODES.md:416 (dominant-color's cache key is Objects.hash, not sha256) — see Task 6.
4. Re-check the per-node test counts in NODES.md §10 against the modules while there.
```

**References:** [../features/nodes/NODES.md](../features/nodes/NODES.md) · the two node specs above
**Test Requirements:** None automated; verify each claim against the named file before changing it.

---

## Task 16: Java, SQL and README comments still cite `spec/features/pipeline-nodes/NODE_*_PLAN.md`

**Argumentation Summary:** Roughly fifteen source files carry javadoc or header comments pointing at
`spec/features/pipeline-nodes/NODE_*_PLAN.md` — a directory that never existed at that path. The
migration of 2026-08-11 makes them doubly dead: the documents are now at
`spec/features/nodes/<kind>/NODE_*.md`. Known: `DedupNodeModule.java:31`, `FingerprintDedupNode.java:42`,
`DedupGroupMethods.java:13`, `DedupGroupEndpoint.java:20`, `DedupGroupEndpointService.java:34`,
`DedupGroupDao.java:11`, `DedupGroup.java:11`, `V2.61__add_dedup_group.sql:1`,
`cortex/s3-common/.../AwsS3ObjectStore.java:42`, `sidecars/depth/README.md:124`.

**Improvement Summary:** Repoint every one of them at the node spec that now owns the content.

```
1. grep -rn "pipeline-nodes/NODE_" --include=*.java --include=*.sql --include=*.md . | grep -v node_modules
2. Rewrite each to the matching spec/features/nodes/<kind>/NODE_*.md path.
3. While there, drop the two stale test names that no longer exist anywhere in the tree:
   NodePortConformanceTest (subsumed by NodeSpecGoldenTest) and ScriptNodeSingletonTest (never
   existed) — ScriptValueType.java:27, ScriptNode.java:74, ScriptNodeModule.java:23,
   SceneLayoutNodeOptions.java:45 (which also cites a SceneLayoutDescriptorProvider that does not exist).
4. Also stale in-module: SceneLayoutNode.java:303-305 (says DetectionResponse omits label and that
   fixing it is a prerequisite "before object detection lands" — it landed) and
   cortex/nodes/scene-layout/core/pom.xml (claims depth arrives by key, not by type — the opposite of
   the struct/depthmap binding).
```

**References:** every file under [../features/nodes/](../features/nodes/)
**Test Requirements:** Comment-only change; `mvn -q compile` on the touched modules is sufficient.

---

## Task 17: The integration tests each new spec identifies as missing

**Argumentation Summary:** Five coverage gaps were found during the migration, each guarding
something no unit test can reach. `S3PipelineContainerExecutionIntegrationTest` does not exist, and it
is the only test that would prove the lazy-materialization architecture (two workers, no shared media
volume) and catch an AWS-SDK shading regression in the `metaloom/cortex-server` image. Nothing in
`integration-test/` or `e2e-test/` exercises the dedup runtime at all. `sidecars/depth/server.py` has
no Python test of any kind — every Java test stubs `DepthmapClient`, so `_normalize`, the `max_dim`
clamp and the error codes are covered by nothing. `CaptioningNodeIntegrationTest.java:82` pins
`WHOLE`, so the `SCENE` strategy's persisted `scenes` array is unverified. `s3-sink` and `captioning`
have no `*NodePipelineTest`.

**Improvement Summary:** Write the five, in that order.

```
1. S3PipelineContainerExecutionIntegrationTest — model on
   integration-test/.../PipelineContainerExecutionIntegrationTest.java; needs a MinIO service in the
   shared test context (integration-test/.../container/MinioContainer.java exists).
2. A dedup runtime IT covering discover -> confirm -> apply.
3. A TestClient-based Python test for sidecars/depth/server.py with a stub pipeline (no GPU needed).
4. Extend CaptioningNodeIntegrationTest with a SCENE-strategy case.
5. S3SinkNodePipelineTest and CaptioningNodePipelineTest.
Remember ./setup-pool.sh before running the Java integration tests.
```

**References:** [../features/nodes/s3-source/NODE_S3SOURCE.md](../features/nodes/s3-source/NODE_S3SOURCE.md) ·
[../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) ·
[../features/nodes/depthmap/NODE_DEPTHMAP.md](../features/nodes/depthmap/NODE_DEPTHMAP.md) ·
[../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md](../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md)
**Test Requirements:** The five new tests, green: `./setup-pool.sh && mvn -pl integration-test test`.

---

## Task 18: `s3-sink` reports green when the asset row was never created

**Argumentation Summary:** `S3SinkNode.createArtifactAsset` swallows its exception and returns null,
so a failed `createAsset` leaves the artifact `UPLOADED`/`PRESENT` with no `assetUuid` and the node
reports success. That contradicts `UploadedArtifact.State.FAILED`'s own javadoc
(`UploadedArtifact.java:18-23`). The bytes are in the bucket and nothing in MetaLoom knows about them
— the silent-loss shape this node otherwise avoids carefully.

**Improvement Summary:** Either surface the failure in the artifact state or correct the contract.

```
1. Decide: fail the artifact (preferred) or document that asset creation is best-effort.
2. If failing: set the FAILED state, include the cause on the flag port, and keep the uploaded object
   (do not delete — that is the node's existing, correct policy).
3. Add an S3SinkNodeTest case with a Loom client that refuses createAsset.
```

**References:** [../features/nodes/s3-sink/NODE_S3SINK.md](../features/nodes/s3-sink/NODE_S3SINK.md)
**Test Requirements:** New `S3SinkNodeTest` case; existing 89 must stay green.

---

## Task 21: A swallowed `persist(...)` leaves a green node whose result was never stored

**Argumentation Summary:** Found while closing Task 5 / [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 17,
and deliberately left out of that change because the shape is different. **24 `persist(...)` catch
blocks across 22 node classes** catch their own exception, log a warning, record a `FAILED`
`asset_node_result` row and then fall through, so the node returns SUCCESS. The consequence is the one
Task 17 existed to eliminate — a green node whose result exists nowhere durable — reached by a
different route, and it is invisible to `next()`, to `abort()` and to `FailurePathGuardTest` alike,
because no failure cause is ever recorded on the context. `SceneLayoutNodePersistenceTest.testRecordsFailedLedgerWhenComponentWriteFails`
pins the current behaviour and says so; a first attempt to assert FAILED there is what surfaced this.

The sites (`grep` for a `catch` recording `ResultState.FAILED` with no `return` or `throw`):
`MetadataNode:295`, `FacedescriptionNode:271`, `TranslateNode:219`, `GuardNode:276`,
`SceneLayoutNode:427`, `TikaNode:111`, `QualityNode:242`, `SentimentNode:191`, `S3SinkNode:479`,
`SceneDetectionNode:128`, `ConsistencyNode:112`, `CaptioningNode:193` and `:225`, `VlmNode:205`,
`ObjectDetectNode:582`, `OCRNode:106`, `ScriptNode:493`, `WhisperNode:136`, `ChunkHashNode:98`,
`SHA256Node:98`, `MD5Node:103`, `DominantColorNode:397`, `FingerprintNode:183`, `LLMNode:171`.

**Improvement Summary:** Decide per node whether the durable write is part of the result, then make
the terminal state say so — this is a **judgement call per node, not a sweep**, which is why it is its
own task.

```
1. Split the list in two. A node whose value survives only in Loom (scene-layout, tika, sentiment,
   captioning, ocr, vlm, llm, objectdetect, dominant-color) loses the result outright when the
   component write fails - that is a failure. A node whose value also lives on local disk or on a
   port (the hash nodes, fingerprint, whisper's ledger marker) can defensibly continue.
2. For the first group: propagate, so the existing outer catch aborts with the cause. Do NOT add a
   second failure vocabulary.
3. For the second group: document the choice in the node's spec file next to its persist(), so the
   next reader does not have to re-derive it. Several already do (SENTIMENT_SIDECAR, SERVICE_TIKA).
4. Decide once whether a best-effort ledger write that itself fails should ever fail the node.
   AbstractMediaNode.recordNodeResult is best-effort by design and must stay that way - the
   distinction is between "the ledger row failed" and "the value failed to persist".
5. One test per converted node: a Loom client that refuses the component write must produce FAILED
   carrying the cause.
```

**References:** [WORKFLOW_TASKS.md](WORKFLOW_TASKS.md) Task 17 (the sibling defect, done) ·
[../features/nodes/NODES.md](../features/nodes/NODES.md) §9 ·
[../features/nodes/scene-layout/NODE_SCENE_LAYOUT.md](../features/nodes/scene-layout/NODE_SCENE_LAYOUT.md)
**Test Requirements:** One failure-state test per converted node; the full cortex suite green.
`mvn -o -f cortex/pom.xml test`. ⚠️ `cortex/**` modules share one JVM per module, so per-class peak
RSS in the reports is really the module's number.

---

## Task 19: S3 and cloud sources ignore the `asset_pool` Loom already models

**Argumentation Summary:** Loom models S3-backed pools (`asset_pool`, `library.pool_uuid`,
`BinaryStorageResolver`), but `s3-source` takes its bucket and prefix from the pipeline definition and
`s3-sink` uploads to its own bucket with worker credentials. A configured pool row is bypassed by
both, and the two nodes ask the same unanswered question about endpoint precedence.

**Improvement Summary:** Add an optional `poolUuid`/`poolName` option to both nodes that resolves
bucket, prefix and credentials from the pool, and define which wins when both are set.

```
1. Define the precedence rule once (pool vs explicit option vs worker env) and write it into
   spec/features/rest/REST_BINARY_HANDLING.md.
2. Add the option to S3SourceNodeOptions and S3SinkNodeOptions with @ParamDoc; resolve through the
   Loom client at configure() time.
3. Regenerate node-descriptors.json + golden. Add tests for each precedence branch.
```

**References:** [../features/nodes/s3-source/NODE_S3SOURCE.md](../features/nodes/s3-source/NODE_S3SOURCE.md) ·
[../features/nodes/s3-sink/NODE_S3SINK.md](../features/nodes/s3-sink/NODE_S3SINK.md) ·
[../features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md)
**Test Requirements:** New option-resolution tests in both modules; `NodeSpecGoldenTest` regenerated.

---

## Task 20: Deployment and demo gaps the node family shares

**Argumentation Summary:** Four small gaps, each cheap and each visible to a user or an operator.
`sidecars/depth/` has no `Dockerfile`, no compose entry and no Helm wiring, unlike
`sidecars/ideogram-sidecar/` — so the depth backend cannot be deployed the way every other sidecar
can. The model-licence page `website/content/english/docs/legal/model-licenses/index.adoc` carries
only a `sam2` row (line ~162) while the depthmap docs page links to it: Depth-Anything-V2-Small
(Apache-2.0) and ZoeDepth (MIT) are missing. `watermark` has no demo data at all, so the node is
invisible in a fresh demo instance, which [../guidelines/CODING.md](../guidelines/CODING.md)
requires. `NodeDescriptor.icon` is declared in `loom-ui/src/types/nodeDescriptors.ts:128` and read by
nothing, so all 44 descriptors' icons are dead weight.

**Improvement Summary:** Add the Dockerfile, the two licence rows and the watermark demo pipeline;
either render the descriptor icon in the palette or drop the field.

```
1. sidecars/depth/Dockerfile modelled on sidecars/ideogram-sidecar/Dockerfile (nvidia/cuda runtime,
   EXPOSE 9120), plus the compose/Helm entries the other sidecars have.
2. Two rows in website/content/english/docs/legal/model-licenses/index.adoc.
3. A watermark demo pipeline in DemoDatabaseInitializer (loom/core/.../boot/).
4. Decide the fate of NodeDescriptor.icon in loom-ui; if it stays, render it in the palette.
```

**References:** [../sidecars/SIDECARS.md](../sidecars/SIDECARS.md) ·
[../features/nodes/depthmap/NODE_DEPTHMAP.md](../features/nodes/depthmap/NODE_DEPTHMAP.md) ·
[../features/nodes/watermark/NODE_WATERMARK.md](../features/nodes/watermark/NODE_WATERMARK.md)
**Test Requirements:** Demo boot must still succeed (`DemoDatabaseInitializer` runs on a fresh demo container).

---

## Per-node follow-ups

Everything not listed above lives in the `Progress Assessment` section of the node's own spec. Start
there before opening a task here.

| Node kind(s) | Spec | Notable open items kept there |
|---|---|---|
| `captioning` | [../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md](../features/nodes/captioning/NODE_VIDEO_CAPTIONING.md) | `CAPTION` segment type + migration; `NATIVE` token/frame cap; audio fusion; dead `MultiModalCaption` |
| `gdrive-source`, `onedrive-source` | [../features/nodes/cloud-source/NODE_CLOUDSOURCE.md](../features/nodes/cloud-source/NODE_CLOUDSOURCE.md) | resumable download; per-JVM `lastStates`; hard-coded `PARENT_LOOKUP_DEPTH`; OneDrive `@ParamOverride` pairing |
| `fingerprint-dedup`, `fingerprint-dedup-apply`, `hash-dedup` | [../features/nodes/dedup/NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) | N+1 in `toResponse` |
| `depthmap` | [../features/nodes/depthmap/NODE_DEPTHMAP.md](../features/nodes/depthmap/NODE_DEPTHMAP.md) | only `flag` emitted on failure; flat-map degeneracy indistinguishable from success; video/keyframe depth; depth not queryable |
| `dominant-color` | [../features/nodes/dominant-color/NODE_DOMINANT_COLOR.md](../features/nodes/dominant-color/NODE_DOMINANT_COLOR.md) | not queryable; no colour filter node; no metrics; missing `turquoise` term; unvalidated naming thresholds |
| `imagegen` | [../features/nodes/image-generation/NODE_IMAGEGEN.md](../features/nodes/image-generation/NODE_IMAGEGEN.md) + [IMAGEGEN_NODE.md](IMAGEGEN_NODE.md) | no `producerVersion` on the ledger row; per-POST `HttpClient`; sidecar repo hygiene |
| `s3-sink` | [../features/nodes/s3-sink/NODE_S3SINK.md](../features/nodes/s3-sink/NODE_S3SINK.md) | `IF_DIFFERENT` compares key+size only |
| `s3-source` | [../features/nodes/s3-source/NODE_S3SOURCE.md](../features/nodes/s3-source/NODE_S3SOURCE.md) | versioned buckets / delete markers; per-instance `maxObjectSize`; watch mode |
| `sam2` | [../features/nodes/sam2/NODE_SAM2.md](../features/nodes/sam2/NODE_SAM2.md) | queryable masks; inert `stabilityScoreThresh`; `NodeResultStrip` hides `masks`/`overlay` |
| `scene-layout` | [../features/nodes/scene-layout/NODE_SCENE_LAYOUT.md](../features/nodes/scene-layout/NODE_SCENE_LAYOUT.md) | `phrases[]` not searchable (`V2.65`); video per-frame layout; `DetectionResponse.nodeKind`/`detectionIndex` |
| `script` | [../features/nodes/script/NODE_SCRIPT.md](../features/nodes/script/NODE_SCRIPT.md) | `maxOutputBytes` measures only the JSON subset; three output-type tables drift unguarded; no metrics or previews |
| `sentiment` | [../features/nodes/sentiment/NODE_SENTIMENT.md](../features/nodes/sentiment/NODE_SENTIMENT.md) | EN + fallback checkpoints never loaded; chunking unproven; `truncated` persisted but never acted on; whisper transcript is type-legal but scores JSON |
| `tag` | [../features/nodes/tag/NODE_TAG.md](../features/nodes/tag/NODE_TAG.md) | retire the client-side read-back; rule editor UI; region tags; dead `TagsPayload`; statement-level search trigger |
| `watermark` | [../features/nodes/watermark/NODE_WATERMARK.md](../features/nodes/watermark/NODE_WATERMARK.md) | rotation/SAR unhandled (`FfmpegRunner.probe` returns coded dimensions) |

---

_Git HEAD revision: `d4e9134f`_
_Last updated: 2026-08-18 (Task 5 done — the `ctx.failure(...).next()` sweep landed tree-wide with a build guard; Task 21 added for the swallowed-`persist` sibling defect it surfaced)_
