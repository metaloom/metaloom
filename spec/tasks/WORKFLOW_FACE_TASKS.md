# Face Workflow — Task List

> Work items for the face identity loop (detect → embed → cluster → confirm a person), derived from a
> code audit on 2026-08-11 at `8c153347` **plus the uncommitted `PipelineConfigurable` work in the
> tree**. Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) (technical spec — read it
> for *why*; this file is only *what is left*) ·
> [../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md](../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md) (models, packs, licensing) ·
> [../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) (vector index) ·
> [../cortex/CONFIGURATION.md](../cortex/CONFIGURATION.md) §4 (how a pipeline node definition reaches a worker) ·
> [../loom/ui/TASK_UI_AI_ML.md](../loom/ui/TASK_UI_AI_ML.md) Task 4 (cluster editor / cluster detail view, not repeated here)
>
> **This file lists only what is still open.** Completed tasks are deleted rather than marked, so the
> numbering has gaps; surviving numbers are stable because other files cite them. Tasks 2 and 6 shipped
> (`V2.88`, and `V2.89`–`V2.91` respectively) and have been removed.
>
> **Ordering, by severity.** The file reads **1 · 10 · 11 · 3 · 9 · 4 · 7 · 5 · 8**.
>
> - **Task 1 is blocking.** Until it lands, changing the model pack keeps confirmed identities that were
>   decided against vectors from a different embedder — the one way this feature produces a *wrong*
>   answer rather than a missing one. It needs **no DDL**; it is pure DAO work.
> - **Task 10 is blocking for Task 1's premise.** The pipeline editor offers `embeddingModel` and
>   `inspirefacePackPath` per node and `FacedetectNode.configure(...)` drops both, so the very event
>   Task 1 gates on cannot be triggered from the editor at all. Task 11 is the same family and is a
>   30-minute fix; do them together.
> - Task 3 is what makes the review loop usable at real precision, and should precede calibration.
> - Task 9 is a prerequisite for Task 7: the backend "seam" does not exist yet.
> - Task 4 gates Task 5 — there is no point building library-wide identity on an uncalibrated radius.
> - Task 8 is documentation and is independent of everything else.
>
> **Open product question that gates Task 5:** is per-asset identity ("who is in this video") useful on
> its own, or must cross-asset identity ("who is this") ship before the feature is announced?
> `WORKFLOW_FACE.md` §2.2 is honest that phase 1 only answers the first. Decide before scheduling Task 5.

---

## Task 1: Retire face clusters when the model pack changes

**Argumentation Summary:** Nothing invalidates a cluster when the embedder changes, and the migration
that created the columns promised it would.

Verified in the working tree:
`ClusterDaoImpl.java:55` declares `REVIEW_COLUMNS = {status, person_uuid, reviewed_at, reviewer_uuid}`
and `:103` preserves all four **unconditionally** through the two-argument
`upsert(pojo, preserved, keys...)`. `deleteStalePending` (`:108`–`:116`) filters on
`asset_uuid` / `node_kind` / `PENDING` only. Neither reads `producer_version`. So after switching
`inspirefacePackPath` from Pikachu to Megatron, a cluster a human confirmed as "Anna" keeps
`status=CONFIRMED`, its `person_uuid`, **and its reviewer's name**, while its members are recomputed
from vectors of a different embedder with a different similarity geometry. The verdict and its author
are attributed to a grouping the reviewer never saw.

`V2.79`'s own header states the provenance columns exist so a pack change "retires stale proposals
through the standard sweep"; `V2.81` then solved exactly this for `detection`. The mechanism to copy is
`DetectionDaoImpl.upsertDetection` (`:83`–`:87`) plus `DetectionDaoImpl.reviewOverrides()` (`:109`),
which builds the `CASE` map for the three-argument `AbstractJooqDao.upsert` (`:163`).

**Improvement Summary:** Gate the cluster review verdict on `producer_version` exactly as
`DetectionDaoImpl` does — within one version the verdict stands, across versions it resets to `PENDING`
and the person link and reviewer pair drop — and make `deleteStalePending` also remove this producer's
`PENDING` clusters whose `producer_version` no longer matches.

```
1. Read DetectionDaoImpl.reviewOverrides() (loom/db/jooq/.../dao/detection/DetectionDaoImpl.java:109)
   and V2.81__detection_review_state.sql §4 FIRST. This task is that mechanism applied to "cluster".
   Do not invent a second shape for it, and do not read the stored producer_version in a separate
   SELECT — the CASE-expression overrides map is the supported way and it already exists.

2. loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/cluster/ClusterDaoImpl.java
   - upsertCluster (:102): switch from upsert(cluster, REVIEW_COLUMNS, keys...) to the three-argument
     upsert(cluster, REVIEW_COLUMNS, overrides, keys...) declared at AbstractJooqDao:163. Keep all four
     names in REVIEW_COLUMNS — dropping one there hands it back to the producer's own UPDATE, which is
     the opposite of the fix. The overrides map makes each of the four conditional on
     CLUSTER.PRODUCER_VERSION.eq(DSL.excluded(CLUSTER.PRODUCER_VERSION)): same version keeps the stored
     value, different version resets status to PENDING and nulls person_uuid, reviewed_at and
     reviewer_uuid.
   - deleteStalePending (:108): add a `String producerVersion` parameter and an OR arm, so a PENDING
     cluster for this (asset, node_kind) is also deleted when its producer_version differs from the
     incoming one, regardless of cluster_index. Update the interface javadoc in
     loom/db/api/src/main/java/io/metaloom/loom/db/model/cluster/ClusterDao.java to state the rule, and
     fix the single production caller, ClusterEndpointService.bulkCreateAssetClusters:269.
   - Do NOT delete CONFIRMED or REJECTED clusters on a version change. A reset-to-PENDING preserves the
     fact that somebody looked; a delete destroys it. That is the same distinction V2.79 makes when it
     chose ON DELETE SET NULL for person_uuid.

3. DECIDE WHAT AN EMPTY producer_version MEANS, and pin it with a test. Two places default it to "":
   ClusterEndpointService.bulkCreateAssetClusters:251 (`item.getProducerVersion() == null ? "" : ...`)
   and FacedetectNode.producerVersion():816 (returns "" when the resolved model is blank). Under plain
   equality every unstamped cluster therefore matches every other unstamped cluster and the gate never
   fires for them. Recommended: treat "" as "unknown" and let it match nothing, so an unstamped verdict
   is reset once and then stamped properly. Whatever you choose, say it in the DAO javadoc.

4. The producer side is ALREADY WIRED — verify, do not rebuild. ClusterCreateItem carries
   producerVersion, FacedetectNode sets it on every cluster (FacedetectNode.java:952) from
   producerVersion() (:816), which returns options().resolvedEmbeddingModel() — and that value already
   changes when the pack changes (FacedetectNodeOptions.resolvedEmbeddingModel():162 folds the pack
   name into the model, e.g. `inspireface-r18` + `packs/Pikachu` -> `inspireface-pikachu-r18`).
   ⚠️ But see Task 10: neither `inspirefacePackPath` nor `embeddingModel` can currently be changed from
   the pipeline editor, so today only a cortex.yml edit can trigger the event this task gates on.

5. The migration carries NO DDL — only COMMENT updates on "cluster"."status", ."reviewed_at" and
   ."reviewer_uuid", mirroring the wording V2.81 used for detection. Take the next free version at the
   time you write it and sort NUMERICALLY (V2.9 < V2.95): V2.95 is the highest today, not V2.88.

6. The embedding half is ALREADY ANSWERED — record it, do not re-investigate. The embedding upsert key
   is (asset_uuid, node_kind, type, model, frame_number, subject_index)
   (V2.75__embedding_index_contract.sql:66) and EmbeddingDaoImpl.upsertEmbedding:87 uses exactly that,
   so "model" is part of the identity: Pikachu and Megatron vectors ACCUMULATE side by side in one
   asset rather than replacing each other. Decide and implement one of: scope a clustering run to the
   embeddings matching the node's own resolvedEmbeddingModel (recommended — it is a WHERE clause, and
   it makes the two populations independently reviewable), or add a sweep that deletes superseded
   vectors. Write the answer into WORKFLOW_FACE.md §3.5 either way.

7. Spec updates, all in ../workflows/WORKFLOW_FACE.md: replace the "Task 1 ... is still open" warning
   block at the end of §3.1.1 with what the code now does, answer §7 open question 4 ("does a pack
   change auto-invalidate clusters"), and turn gotcha 5 from a warning into a statement.

8. No loom-ui change. The reset is invisible to the client: a cluster that comes back PENDING with a
   null personUuid already renders as an unreviewed proposal in ClustersPanel.tsx and in the
   FaceDetectionMode block of WorkflowView.tsx. Confirm that by reading them; do not add UI.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §3.1 item 3, §3.1.1,
§7 open question 4, gotcha 5 · migration `V2.79__cluster_review_model.sql` header ·
`V2.81__detection_review_state.sql` §4 · `DetectionDaoImpl.reviewOverrides()` ·
`AbstractJooqDao.upsert(element, preserved, overrides, keyFields...)` · Task 10 (the event this gates on)
**Test Requirements:** `ClusterDaoTest#testUpsertResetsVerdictOnProducerVersionChange` (a CONFIRMED
cluster re-upserted under a new `producer_version` comes back PENDING with a null `person_uuid`,
`reviewed_at` and `reviewer_uuid`; the `person` row itself survives) and
`#testDeleteStalePendingRemovesOtherProducerVersions`. These four existing tests must stay green
**unchanged** — they are the half the gate must not disturb, because they all upsert at the *same*
producer version: `ClusterDaoTest#testUpsertDoesNotClobberConfirmedStatus`,
`#testNodeReRunDoesNotClobberTheReviewer`, `#testRejectRecordsTheReviewer`,
`#testDeleteStalePendingKeepsDecidedClusters`. `ClusterEndpointTest#testReRunDoesNotReopenAConfirmedCluster`
unchanged, plus a new case for the version-change path through `POST /assets/:uuid/clusters/bulk`.
`mvn install -pl loom/db/flyway && loom/db/jooq/generate.sh && ./setup-pool.sh` before
`mvn -pl loom/db/jooq,loom/core test -Dtest='ClusterDaoTest,ClusterEndpointTest'`.

---

## Task 10: The pipeline editor offers facedetect options the node throws away

**Argumentation Summary:** `facedetect` advertises **thirteen** parameters in
`loom-shared/node-model/src/main/resources/node-descriptors.json`; the pipeline editor renders every
one of them as an editable field (`PipelineEditor.tsx` `NodeDetailSidebar`, the parameter loop at
L1656–L1792), and `NodeOptionValidator` accepts every one of them at save time because they are
declared. `FacedetectNode.configure(JsonObject)` — added in the uncommitted `PipelineConfigurable`
work — reads exactly **two**: `faceClusterEPS` and `faceClusterMinimum`. Every other value an author
types is silently dropped at the worker, which is verbatim the defect that work's own test class was
written to pin (`FacedetectNodePipelineConfigTest`, class javadoc).

The justification given for the split is that the other options are baked into the `InspireFacedetector`
when Dagger builds it, so accepting them per node "would advertise a knob that quietly did nothing".
For four of them that is true (`inspirefacePackPath`, `minFaceHeightFactor`, `maxFaceAngle`,
`capabilities` — all consumed in `FacedetectNodeModule.inspirefaceDetector`, `:122`–`:135`), and for
two more it is true via the `VideoFaceScanner` constructor (`videoChopRate`, `videoScaleSize`, read at
`VideoFaceScanner.java:88`–`:89`). **For two it is false.** `embeddingsEnabled` is read per item at
`FacedetectNode.java:328`, `:359`, `:415` and `:859`, and `embeddingModel` is read per item through
`options().resolvedEmbeddingModel()` at `:817`, `:891` and `:956` — precisely the "read per item"
criterion §9.1 says decides the split. So `WORKFLOW_FACE.md` §9.1's table and the new prose in
`../cortex/CONFIGURATION.md` §4 both state a rule the code does not follow.

`embeddingModel` is the expensive one: it is the vector-index space key, part of the `embedding` row's
unique key, and the value `FacedetectNode.producerVersion()` returns. An author who points a pipeline
node at a new pack and raises the model name gets neither — the run writes new vectors under the old
model's name, mixing two incompatible populations under one identity, which is the exact failure gotcha
5 exists to prevent and the exact event Task 1 gates on.

**Improvement Summary:** Make `configure(...)` consume the two options that are genuinely read per item,
and make the editor stop presenting the six that are genuinely worker-scoped as if they were authorable.

```
1. cortex/nodes/facedetect/core/src/main/java/io/metaloom/cortex/node/facedetect/FacedetectNode.java:
   extend configure(JsonObject) to accept `embeddingsEnabled` (boolean) and `embeddingModel` (non-blank
   String), holding them on the instance exactly as faceClusterEPS/faceClusterMinimum already are — the
   shared-options trap documented on the faceClusterEPS field applies identically and is the reason
   they must NOT be written back into options().
   Add package-private accessors embeddingsEnabled() / embeddingModel() beside the two that exist, and
   route every current call site through them: :328, :359, :415, :859 (embeddingsEnabled) and :817,
   :891, :956 (resolvedEmbeddingModel).
   `resolvedEmbeddingModel()` folds in the pack name, and the pack stays worker-scoped, so the
   per-instance override has to be resolved against the SAME pack — move the folding into a method that
   takes the model name as an argument rather than reading the field, and keep
   FacedetectNodeOptions.resolvedEmbeddingModel() delegating to it so nothing else changes.
   Reject a blank embeddingModel in configure() the way `positive(...)` rejects a non-positive number:
   an unnamed model makes every embedding indistinguishable from the next model's, which is what
   FacedetectNodeOptions.validate():307 already says.

2. Decide what to do about the six that ARE worker-scoped: inspirefacePackPath, minFaceHeightFactor,
   maxFaceAngle, capabilities, videoChopRate, videoScaleSize. Do NOT make them per-node — the detector
   and the scanner are constructed once by Dagger, and a per-node pack path would mean building a
   detector per pipeline node, which is a much larger change. Instead make the editor tell the truth.
   RECOMMENDED: add a `workerScoped()` boolean to the ParamDoc annotation
   (cortex/api/.../node/spec/ParamDoc.java — it already carries `hidden()`, which is the wrong tool
   here: an operator still needs to SEE the pack path, they just must not believe they can change it
   from the pipeline), have the descriptor harvest emit it, and mark those six on
   FacedetectNodeOptions. Hiding them instead is acceptable only if you also state where the value
   does come from.

3. loom-ui — REQUIRED, this task is not done without it:
   - loom-ui/src/types/nodeDescriptors.ts: add the flag to `NodeParameter` (alongside the `min`/`max`/
     `step` fields the uncommitted work just added at L83-94), optional, read with `!== undefined`.
   - loom-ui/src/features/pipeline/PipelineEditor.tsx: in the parameter loop (L1656-1792), render a
     worker-scoped parameter as disabled with its value shown and a helper text naming cortex.yml.
     Every branch needs it, not just the numeric one at L1761-1789 — `capabilities` is ENUM_SET
     (L1707-1718) and `inspirefacePackPath` is a plain string.
   - Regenerate the descriptors afterwards. Install the cortex node module BEFORE regenerating, or the
     harvest reads a stale jar.

4. Correct the two specs that state the wrong rule: ../workflows/WORKFLOW_FACE.md §9.1 (the "Which of
   these are per pipeline node" table, which currently lists only the two cluster knobs and attributes
   the split to "read per item") and ../cortex/CONFIGURATION.md §4. Both are modified in the working
   tree already — edit the working-tree version.

5. website/content/english/docs/nodes/facedetect/index.adoc: the "Tuning the grouping" section now
   claims only Cluster Radius and Min Cluster Size are set on the node. Say which fields are authored
   per node and which come from the worker, since the editor will now show both.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §9, §9.1 ·
[../cortex/CONFIGURATION.md](../cortex/CONFIGURATION.md) §4 ·
[../features/nodes/NODES.md](../features/nodes/NODES.md) §3 (`facedetection` option row) ·
`PipelineConfigurable.java`, `RegistryNodeRegistrar.adapt(...)` (:392-:400) ·
`FacedetectNodeModule.inspirefaceDetector` · Task 1 (which this unblocks), Task 11 (same family)
**Test Requirements:** `FacedetectNodePipelineConfigTest` — extend it in its existing behavioural style
(assert what the node *writes*, not what a getter returns): a node configured with a different
`embeddingModel` writes that model onto the `EmbeddingCreateRequest` and onto the cluster's
`producerVersion`, while a second node built from the same shared `FacedetectNodeOptions` still writes
the worker's; `embeddingsEnabled: false` on one node produces no embeddings and no clusters while the
other still does; a blank `embeddingModel` throws an `IllegalStateException` naming the node id.
`FacedetectNodeOptionsValidationTest` unchanged. A Playwright case in
`loom-ui/e2e/pipeline-node-params-mocked.spec.ts` (currently untracked — commit it) asserting a
worker-scoped parameter renders disabled and is not written into `definition.nodes[].options` on save.
`mvn -pl cortex/nodes/facedetect/core test`, then
`mvn -o -pl integration-test test -Dtest=NodeSpecGoldenTest -Dloom.regenerateNodeDescriptors=true`, then
`./node_modules/.bin/playwright test e2e/pipeline-node-params-mocked.spec.ts` from `loom-ui` (never `npx`).

---

## Task 11: Make the declared parameter bounds agree with the rule the node enforces

**Argumentation Summary:** Three `facedetect` parameters declare a range that admits a value every
enforcement layer then rejects, so a pipeline saves cleanly and fails at the worker.

| Parameter | Declared in `@ParamDoc` | Actually enforced |
|---|---|---|
| `faceClusterEPS` | `min = "0.0"` | `> 0` — `FacedetectNodeOptions.validate():291` and `FacedetectNode.positive(...)` |
| `minFaceHeightFactor` | `min = "0.0"` | `> 0 && <= 1` — `validate():296` |
| `faceClusterMinimum` | **no bound at all** | `> 0` — `validate():286` and `configure(...)` |

`NodeOptionValidator` compares with `actual < min` (`:108`), so a declared `0.0` admits `0`; and a
parameter with no `min` admits `-3`. The uncommitted editor work now spreads `min`/`max`/`step` onto the
input element (`PipelineEditor.tsx` L1767-1780), which inherits the same wrong bounds — and the editor
performs **no** client-side range check before save anyway (`saveDefinition` L3461-3495 delegates
entirely to `/pipelines/validate`). So `0` passes the browser, passes the server, is stored in the
pipeline definition, and throws on the worker at run time with a message no author sees until an asset
fails. The uncommitted customer doc makes the same claim: `index.adoc` now says "The editor holds
Cluster Radius to its valid range of `0` to `2`", and `0` is not in the valid range.

**Improvement Summary:** Correct the three `@ParamDoc` bounds to the values the code enforces,
regenerate the descriptors, and fix the customer doc sentence.

```
1. cortex/nodes/facedetect/core/src/main/java/io/metaloom/cortex/node/facedetect/FacedetectNodeOptions.java:
   - faceClusterEPS (:52): min = "0.05" (the declared step, and the smallest radius that is not a
     rounding artefact) rather than "0.0". Do not use an epsilon like 0.0001 — the bound is what the
     editor's spinner steps through, so it must be a number an author would type.
   - minFaceHeightFactor (:74): same correction, min = "0.01".
   - faceClusterMinimum (:46): add min = "1". It has no bound today, so nothing above the worker
     rejects 0 or a negative.
   Whichever floor you pick, it must be a value validate() accepts — check each against
   FacedetectNodeOptions.validate() (:275-:315) rather than eyeballing it.

2. Sweep the rest of the file the same way while you are in it: every @ParamDoc bound must be a value
   validate() accepts, and every rule validate() enforces on a numeric field should have a bound. This
   is cheap here and expensive to rediscover per node.

3. Regenerate node-descriptors.json. Install the cortex node module FIRST or the harvest reads a stale
   jar, then run the golden test in regeneration mode (command below).

4. loom-ui: NO source change is needed — PipelineEditor.tsx already spreads whatever the descriptor
   declares (L1767-1780) and correctly omits an absent bound. VERIFY that rather than assuming it, and
   note that e2e/pipeline-node-params-mocked.spec.ts deliberately uses faceClusterMinimum as its
   "declares no bounds" control case: giving it a min will break that assertion, so update the spec's
   inline descriptor fixture and pick a different unbounded parameter for the control.

5. website/content/english/docs/nodes/facedetect/index.adoc: the sentence added in the working tree,
   "The editor holds Cluster Radius to its valid range of `0` to `2`", must name the real floor.
```

**References:** `ParamDoc.java` (`min`/`max`/`step`) · `NodeOptionValidator` (:108-:114) ·
`FacedetectNodeOptions.validate()` · [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §9 ·
[../features/pipeline/](../features/pipeline/) · Task 10 (same family — do them in one sitting)
**Test Requirements:** A case per corrected bound added to the existing
`loom/services/rest/src/test/java/io/metaloom/loom/rest/validation/NodeOptionValidatorTest.java`: the
value the worker rejects is now rejected at save time, naming the key. `FacedetectNodeOptionsValidationTest` must stay
green unchanged — it tests the enforcement side, which does not move.
`FacedetectNodePipelineConfigTest#testNonPositiveValuesAreRejected` likewise unchanged.
`loom-ui/e2e/pipeline-node-params-mocked.spec.ts` updated per step 4 and green.
`mvn -pl cortex/nodes/facedetect/core,loom/services/rest test`, then
`mvn -o -pl integration-test test -Dtest=NodeSpecGoldenTest -Dloom.regenerateNodeDescriptors=true`.

---

## Task 3: Let a reviewer correct cluster membership

**Argumentation Summary:** The schema anticipates human corrections and no code path makes one.
`embedding_cluster.origin` accepts `AUTO` or `MANUAL` (`V2.79` §3.2), `ClusterMember.ORIGIN_MANUAL`
exists, and `ClusterDao.link(uuid, uuid, confidence, origin)` writes it — but nothing outside tests
passes `MANUAL`, and `ClusterDao.unlink`/`unlinkAll` have no production caller except the bulk-write
path below. `ClusterEndpoint.register()` (`:43`–`:120`) registers `create`, `update`, `delete`, `list`,
`load`, `listMembers` (GET only), `confirm`, `reject`, `listAssetClusters` and `bulkCreateAssetClusters`
— **no route adds or removes a member.** `clients/python/loom_client/methods/cluster.py` mirrors that
exactly. The UI matches: the reviewer's entire vocabulary is confirm / reject / rename / delete a whole
cluster, and the face crops are inert in every surface that draws them
(`ClustersPanel.tsx` L169-180, `WorkflowView.tsx` L618).

So the only lever on a wrong grouping is all-or-nothing. DBSCAN at an uncalibrated `eps` (Task 4) will
merge two similar-looking people and split one person across two clusters. The honest response to a
merged cluster today is to reject it, discarding every correct member with it.

**Improvement Summary:** Add member-level review routes — detach a face from a cluster, attach it to
another, and split a cluster — writing `origin='MANUAL'` so a correction is distinguishable from the
machine's grouping and survives the next re-run.

```
1. Decide the minimum viable set and write it into WORKFLOW_FACE.md §4 before coding. Smallest first:
   a. DELETE /api/v1/clusters/:uuid/members/:embeddingUuid   — remove a wrong face (UPDATE_CLUSTER)
   b. POST   /api/v1/clusters/:uuid/members                  — attach a face, origin=MANUAL (UPDATE_CLUSTER)
   c. POST   /api/v1/clusters/:uuid/split                    — move a subset into a new PENDING cluster
   (a) and (b) together already express both failure modes; (c) is sugar over them and can wait.
   Collection paths stay plural per ../guidelines/CODING.md; /split is RPC-style and singular, like
   /confirm and /reject. Task 8 owns the rule that decides this — read it first if it has landed.

2. Implement in ClusterEndpointService over the existing ClusterDao.link/unlink. Both are already
   idempotent — link is ON CONFLICT DO UPDATE (ClusterDaoImpl:128-137), unlink a plain delete — so no
   DAO change is needed for (a) and (b).

3. THE HARD PART, and the reason this is not a trivial task: a manual correction must survive the next
   node re-run, and today it CANNOT. ClusterEndpointService.bulkCreateAssetClusters:261 calls
   dao().unlinkAll(cluster.getUuid()) and then re-links every member the node computed. That is not
   merely "a detached face gets re-attached" — it deletes every membership row for the cluster,
   MANUAL ones included, so a manual attach is destroyed and a manual detach is reverted, on every
   pipeline pass. The comment there ("Replace the membership rather than adding to it") is correct for
   AUTO rows and wrong for MANUAL ones.
   Fix the bulk path to preserve human-authored membership: scope the unlinkAll to origin='AUTO', and
   skip re-linking an embedding a reviewer explicitly detached. Detachment currently leaves no record —
   a deleted row is indistinguishable from one never written — so this needs either a tombstone
   (origin='EXCLUDED' as a third value in the CHECK constraint, cheapest, one migration) or a decision
   that corrections only bind on non-PENDING clusters. Pick one, state the reasoning in the migration,
   and pin it with a test.
   Do NOT ship (a) without this: a correction that reverts on the next run is worse than no correction,
   because the reviewer believes it took.

4. loom-ui — REQUIRED. There is no member-level affordance anywhere today; every crop is display-only.
   - loom-ui/src/api/clusters.ts: add the client functions. The file currently has no member-mutation
     surface at all — listClusterMembers is read-only.
   - loom-ui/src/features/faceDetection/ClustersPanel.tsx: give each crop in the member strip
     (L169-180) a remove affordance, with a data-testid in the existing naming style
     (cluster-member-remove).
   - loom-ui/src/features/workflow/WorkflowView.tsx: the FaceDetectionMode block (L524-659) draws the
     same crops at L618 and drives them from a keyboard profile ("Faces — Default", L206-219). A
     reviewer working the queue by keyboard needs the same action there, so add it to that profile
     rather than leaving it mouse-only in one of the two surfaces.
   - Do NOT touch loom-ui/src/features/persons/PersonDetail.tsx L290-317: those crop tiles look similar
     but import a crop as a person *image*; they have nothing to do with membership.

5. Regenerate the OpenAPI spec from inside loom/doc; add the new methods to
   clients/python/loom_client/methods/cluster.py or the parity test fails.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §3.2, §4 ·
`V2.79__cluster_review_model.sql` (the `origin` CHECK constraint) · `ClusterMember.ORIGIN_MANUAL` ·
`ClusterEndpointService.bulkCreateAssetClusters` ·
[../guidelines/CODING.md](../guidelines/CODING.md) (plural collection paths, endpoint + permission tests) ·
Task 8 (the path convention), Task 4 (why the corrections are needed)
**Test Requirements:** `ClusterEndpointTest`: detach removes exactly one member and leaves the rest;
attach writes `origin=MANUAL`; both 403 without `UPDATE_CLUSTER` — grant via group + role, never a
direct user grant, since `user_permission` allows one direct permission per user.
`ClusterDaoTest`: a re-run of `bulkCreateAssetClusters` does **not** resurrect a detached member and does
not downgrade a `MANUAL` membership to `AUTO`; `#testLinkIsIdempotent` and
`#testDeleteCascadesEmbeddingClusterLinks` stay green unchanged. A Playwright case in
`loom-ui/e2e/face-clusters-mocked.spec.ts` (which already owns the member-count and crop-provenance
invariants) plus one in `loom-ui/e2e/face-panels-mocked.spec.ts` for the panel surface.
`./setup-pool.sh` first, then `mvn -pl loom/core,loom/db/jooq test -Dtest='ClusterEndpointTest,ClusterDaoTest'`;
`./node_modules/.bin/playwright test e2e/face-clusters-mocked.spec.ts` from `loom-ui` (never `npx`).

---

## Task 9: Make the `capabilities` option mean something, or stop offering it

**Argumentation Summary:** `capabilities` is advertised as an `ENUM_SET` parameter with values
`{INSPIREFACE, DLIB}` in `node-descriptors.json`, the pipeline editor renders it, and neither value
does what the name implies.

- `FacedetectNodeModule.dlibDetector(...)` (`:107`) builds a `DLibFacedetectorImpl` when `DLIB` is
  selected — and **nothing consumes it**. `FacedetectNode`'s constructor takes an `InspireFacedetector`
  and a `VideoFaceScanner`, and `VideoFaceScanner` (`:76`, `:86`) holds an `InspireFacedetector` too.
  Selecting `DLIB` changes nothing about how faces are found.
- `FacedetectNodeModule.inspirefaceDetector(...)` (`:122`) returns **`null`** when `INSPIREFACE` is not
  selected. Dagger then injects null into a node that dereferences it unconditionally
  (`FacedetectNode.java:328`), so deselecting the one working backend produces an NPE per asset rather
  than a configuration error. `FacedetectNodeOptions.validate():311` only rejects an *empty* set, so
  `{DLIB}` alone validates cleanly and then crashes.

The enum is described in `WORKFLOW_FACE.md` §10 as "the backend seam". There is no seam: the node is
hard-wired to one concrete class. That is what makes Task 7 a real piece of work rather than one enum
value, and it is a live defect on its own.

**Improvement Summary:** Either introduce the abstraction the option claims exists, or make an
unsatisfiable capability set fail fast with a message instead of a null detector.

```
1. Do the cheap half first and unconditionally, because it is correct either way:
   FacedetectNodeOptions.validate() must reject a capability set the node cannot honour — today that is
   any set not containing INSPIREFACE. The message should name what is and is not implemented rather
   than saying "invalid". This turns an NPE per asset into a worker startup error.

2. Then decide about DLIB. It is a provider with no consumer and has been since the node was written.
   Either give it a consumer (step 3) or remove the value from the enum, the @ParamDoc `values` list on
   FacedetectNodeOptions:134, and dlibDetector(...) — a third state, offered and inert, is what this
   task exists to end.

3. If a backend seam is wanted (it is a prerequisite for Task 7, so most likely yes): introduce it
   properly rather than by adding enum values. video4j already declares
   io.metaloom.video.facedetect.Facedetector, which VideoFaceScanner:395 already accepts as a
   parameter type. Make FacedetectNode and VideoFaceScanner depend on the narrowest interface that
   carries the calls they actually make — crucially detectFaces(image, withEmbeddings), which is
   InspireFace-specific today (§1.2 explains why the unfiltered detectEmbeddings must never be zipped
   with it). If that combined call is not on a shared interface, adding it to one is the real content
   of this task, and Task 7 then becomes small.

4. loom-ui: no source change. `capabilities` is rendered by the generic ENUM_SET branch
   (PipelineEditor.tsx L1707-1718) from the descriptor's `values`, so removing or adding a value is a
   descriptor regeneration, not a UI edit — install the cortex node module before regenerating.
   ⚠️ Nothing in loom-ui surfaces a node's backend requirements at all; the nearest concept,
   NodeAvailability (nodeDescriptors.ts L179-198), answers "is a worker online", which is a different
   question. Do not mistake one for the other.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §1.2, §9, §10 ·
[../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md](../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md) §6.3 ·
`FacedetectNodeModule.dlibDetector` / `.inspirefaceDetector` · `FacedetectNodeCapabilities.java` ·
`VideoFaceScanner.java` · Task 7 (blocked by this)
**Test Requirements:** `FacedetectNodeOptionsValidationTest`: a capability set the node cannot honour is
rejected with a message naming it; `{INSPIREFACE}` still validates. If DLIB is removed, the
`FacedetectNodeOptionsAssert.hasCapability(...)` helper (`assertj/FacedetectNodeOptionsAssert.java:86`)
and any test using it must be updated rather than deleted. `FacedetectNodeTest`,
`FacedetectNodeDetectionsTest`, `FacedetectNodeEmbeddingTest` and `FacedetectNodeClusterTest` must stay
green unchanged — they all run the InspireFace path and are the evidence the refactor was behaviour
preserving. `mvn -pl cortex/nodes/facedetect/core test`.

---

## Task 4: Calibrate `faceClusterEPS` against a real corpus

**Argumentation Summary:** `faceClusterEPS = 0.6` is a guess and is documented as one — in the spec
(§2.3: *"unvalidated against any real corpus"*), in the node option javadoc, and in the customer docs
(*"a starting point, not a calibrated value"*). It is a cosine **distance** radius, while InspireFace's
own pack manifests quote **similarity** thresholds — 0.48 for Pikachu, 0.32 for Megatron — so the
default is not merely uncalibrated, it is expressed in the opposite direction from the only published
guidance, and 0.6 corresponds to neither. `faceClusterMinimum = 2` is equally unverified.
`cortex/nodes/facedetect/core/src/test/java/io/metaloom/cortex/node/facedetect/cluster/` holds
`FaceClustererTest` and `VectorsTest`, both of which test the algorithm against synthetic vectors —
correct for what they are, and no evidence about the threshold.

Everything downstream inherits the error: `OUT_FACE_COUNT` claims to report distinct people, the review
queue's cost is the number of wrong groupings a human must fix (and until Task 3 lands, a wrong grouping
can only be thrown away whole), and any phase-2 cross-asset pass (Task 5) compounds a per-asset radius
across the library.

**Improvement Summary:** Assemble a labelled face corpus, measure pairwise same-person /
different-person cosine distance for each supported pack, and set the defaults from the measurement —
recording what they were calibrated against.

```
1. The corpus is the blocking sub-problem and there is no in-repo answer to it. /opt/metaloom/loom-testdata
   is unversioned, carries no identity labels, and its videos are silent and unannotated. Decide and
   record the source: a public labelled set (LFW / AgeDB-30 are the conventional choices for exactly
   this measurement) or a hand-labelled internal set. Note the licence of whatever is chosen in
   ../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md — that file owns licensing, not this one.

2. Write the calibration harness as an opt-in test or a small main under
   cortex/nodes/facedetect/core/src/test/java/io/metaloom/cortex/node/facedetect/cluster/, gated on the
   corpus being present on disk. Follow the pattern the InspireFace tests already use: the packs live
   under inspireface4j/packs/Pikachu, are gitignored, and those tests SKIP rather than fail when the
   pack is absent.

3. Emit, per pack: the distance distribution for same-person and different-person pairs, the ROC, and
   the equal-error-rate threshold. Report the chosen eps as BOTH distance and similarity so the number
   can be compared against the pack manifest without arithmetic.

4. Sweep faceClusterMinimum over the same corpus. It counts the point itself, so 2 means "needs one
   neighbour" — verify that on video, where one person yields many near-duplicate frames and the
   parameter behaves very differently than on a photo library.

5. Set the defaults in FacedetectNodeOptions from the measurement, and correct the declared @ParamDoc
   bounds at the same time (Task 11 owns the bounds; if it has already landed, keep them consistent).
   If the answer is per-pack, the default cannot be a single constant: derive it from the resolved pack,
   and say so in the option javadoc and in
   website/content/english/docs/nodes/facedetect/index.adoc ("Tuning the grouping").

6. Record the corpus, the date and the resulting numbers in WORKFLOW_FACE.md §2.3, replacing the
   "calibrated against nothing" admission, and answer §7 open question 2.

7. loom-ui: no change. The default reaches the editor through node-descriptors.json, so a changed
   constant needs a descriptor regeneration (install the cortex node module first) and nothing else —
   unless step 5 makes the default per-pack, in which case the editor's single static `defaultValue`
   becomes a lie and you must say in the parameter description that the effective default depends on
   the pack.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §2.3, §9, §7 open
question 2 · [../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md](../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md) §5 ·
`FaceClusterer.java`, `Dbscan.java`, `Vectors.java`, `FacedetectNodeOptions.java` · Task 5 (blocked by this), Task 11
**Test Requirements:** The harness itself must skip cleanly when the corpus is absent — assert that, or
CI breaks for everyone without the data. `FaceClustererTest` and `VectorsTest` stay green unchanged;
they test the algorithm, and the threshold is not their subject.
`FacedetectNodePipelineConfigTest` hard-codes the 0.6 default as its baseline
(`testWorkerDefaultGroupsThePairAsOneSubject`, and `TIGHTER_THAN_THE_PAIR = 0.05f` is chosen relative to
it) — changing the constant will move that test, so update it deliberately rather than by trial. Add one
regression asserting the defaults match the recorded calibration, so a future edit fails loudly.
`mvn -pl cortex/nodes/facedetect/core test`.

---

## Task 7: Add the OpenCV backend so the default face stack is not non-commercially licensed

**Argumentation Summary:** `FacedetectNodeCapabilities` is `{INSPIREFACE, DLIB}` — verified in the
checkout, a two-value enum and nothing else. The default and only working backend is InspireFace, which
is **non-commercially licensed**, and `WORKFLOW_FACE.md` gotcha 13 calls that "not a code defect, but a
shipping blocker". `FACEDETECTION_OVERVIEW.md` §6.3 option B is the way out: YuNet (MIT) + SFace
(Apache-2.0), both shipped inside OpenCV, which metaloom already links.

The `video4j-facedetect-opencv` module exists (`video4j/facedetect/opencv/`, class `CVFacedetector` /
`CVFacedetectorImpl`). **But the shape needed is not there.** `CVFacedetectorImpl` offers
`detectFaces(VideoFrame)` (`:80`), `detectFaces(BufferedImage)` (`:88`), `detectFaces(Mat)` (`:97`),
`detectEmbeddings(VideoFrame)` (`:133`) and `extractEmbeddings(FaceVideoFrame)` (`:138`) — there is
**no** combined `detectFaces(img, withEmbeddings)`, which is the single filtered pass the node relies
on. Pairing the separate calls is the silent-corruption trap §1.2 documents. So this task is: one call
added to video4j, one seam in metaloom (Task 9), and one enum value — not one enum value.

**Improvement Summary:** Add the combined detect-and-embed call to the existing OpenCV backend and wire
it into the node's backend selection, so a commercially licensed face stack is selectable.

```
1. Do Task 9 first. The node's constructor takes a concrete InspireFacedetector, and so does
   VideoFaceScanner; without the seam there is nowhere for a second backend to plug in.

2. In the video4j checkout, add the combined call to CVFacedetectorImpl
   (video4j/facedetect/opencv/src/main/java/io/metaloom/video/facedetect/opencv/impl/CVFacedetectorImpl.java),
   mirroring InspireFacedetectorImpl's detectFaces(img, withEmbeddings): detect, apply the size and
   confidence gates, THEN embed the surviving faces.
   🔴 DO NOT zip detectFaces with detectEmbeddings. detectEmbeddings runs detection unfiltered, so its
   ordinals do not line up with the filtered ones and every vector attaches to the wrong face —
   silently, with entirely plausible output (WORKFLOW_FACE.md §1.2).
   video4j is a separate checkout; it must be installed before metaloom can consume the new method.

3. Add OPENCV to FacedetectNodeCapabilities, to the @ParamDoc values list on
   FacedetectNodeOptions:134 (which duplicates the enum by hand — both must change), a provider in
   FacedetectNodeModule, and the backend selection from Task 9.

4. Check the OpenCV major matches BEFORE blaming any new code. There is a known ABI split between
   inspireface4j and video4j that has produced SIGSEGVs, and the two specs disagree about the versions:
   FACEDETECTION_OVERVIEW.md §11 says inspireface4j links 4.10 while video4j loads 5.1, while metaloom
   pins video4j and opencv-ffm at 4.10.0-SNAPSHOT. Establish which is true in this checkout and correct
   whichever spec is wrong.

5. Embeddings from the two backends are NOT interchangeable — SFace is 128-d, the InspireFace packs are
   not. The model name must differ, since (type, model, dimensions) is both the embedding row's unique
   key and the vector-index space key, and that is what stops the two populations from ever being
   compared. Task 1's version gate then retires clusters correctly when a deployment switches backend,
   which is the same event as a pack change.

6. Document the licence and the accuracy difference in FACEDETECTION_OVERVIEW.md (that file owns
   licensing — tick its §6.3 decision checkbox), and list the new capability in
   website/content/english/docs/nodes/facedetect/index.adoc.

7. loom-ui: no source change — the capability list reaches the ENUM_SET field from the descriptor. It
   DOES need a descriptor regeneration: install the cortex node module BEFORE regenerating, or the
   harvest reads a stale jar and the new value never appears in the editor.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §1.2, §6 stale-claim note,
gotcha 13, §9 (`capabilities`) ·
[../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md](../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md) §6.3 (option B), §11 ·
`FacedetectNodeCapabilities.java` · `video4j/facedetect/opencv/` (`CVFacedetectorImpl`) · Task 9 (blocking), Task 1
**Test Requirements:** A video4j-side test that the combined call returns exactly as many vectors as it
returns faces, and that they correspond — that is the property the zip-trap violates.
`FacedetectNodeTest` parameterised over the capability, skipping cleanly when the backend's models are
absent (the InspireFace pack under `inspireface4j/packs/Pikachu` is gitignored and its tests already
skip — follow that pattern). `mvn -pl cortex/nodes/facedetect/core -am test`, then
`mvn -o -pl integration-test test -Dtest=NodeSpecGoldenTest -Dloom.regenerateNodeDescriptors=true`.

---

## Task 5: Cross-asset identity — the same person across the library

**Argumentation Summary:** This is the one stage of the workflow that was never built, and the spec is
blunt about the cost: clustering runs inside `FacedetectNode` scoped to **one asset**, so "confirm this
cluster is Anna" means "Anna appears in *this* video". The same person in a second video produces a
second, unrelated `PENDING` cluster with no memory of the first, and the reviewer confirms her again,
once per asset. The feature answers *"who is in this video"* and not *"who is this"*, which is the
question a media library is actually asked.

The schema was shaped for the fix: `cluster.asset_uuid` is **nullable** precisely so a library-wide
cluster fits the same table distinguished by `node_kind` (`V2.79` §4/5, whose upsert key exempts
NULL-asset rows by SQL NULL semantics), and `cluster.centroid` + `model` + `dimensions` are already
written by `FacedetectNode` (`FacedetectNode.java:955`–`:957`) so a new face can be matched against an
existing cluster without re-reading its members. The `VectorIndex` SPI exists with a
`List<VectorHit> query(VectorQuery)` (`VectorIndex.java:68`), face embeddings appear as their own space
keyed by `(type, model, dimensions)` (`EmbeddingDaoImpl.listSpaces`, `SearchIndexRegistry.vectorSpaces`),
and `/admin/indices` already lists that space with per-space reindex and drop.

**Improvement Summary:** A library-wide pass that matches per-asset clusters against confirmed persons
and against each other via the vector index, proposing cross-asset identity without a second migration.

```
1. Do Task 4 first. A library-wide radius built on an uncalibrated per-asset one multiplies the error
   across every asset instead of confining it to one.

2. Design decision to settle before coding, and to write into WORKFLOW_FACE.md §2.2 as the phase-2
   design: does the pass (a) match each new per-asset cluster centroid against the centroids of
   clusters already CONFIRMED onto a person — cheap, incremental, and it makes confirmation cumulative;
   or (b) re-cluster the whole library's embeddings periodically — expensive, and it fights the human
   verdicts already recorded. Recommend (a): it reuses cluster.centroid, needs only a k-NN query per
   new cluster, and never contradicts an existing decision. Reserve (b) for an explicit admin action.

3. Where it runs is the second decision. It is not a Cortex node — it has no single asset to key on and
   ../guidelines/NEW_NODE.md does not apply. A Loom-side service triggered on confirmation (and on
   demand from /admin) fits the existing shape; say why in the spec.

4. Auto-attribution vs proposal: when a new cluster matches a confirmed person above threshold, does it
   become CONFIRMED automatically or PENDING-with-a-suggestion? Default to the latter — a wrong
   automatic attribution of biometric identity is the expensive failure, and the review queue already
   exists. Carry the suggestion on ClusterResponse as a NEW field; do NOT overload personUuid, which
   the UI reads as a decided link (loom-ui/src/api/clusters.ts documents it that way) and which
   Task 1's version gate nulls on a reset.

5. Only then implement, against the VectorIndex SPI (query(VectorQuery), VectorIndex.java:68). Note the
   trap recorded in ../features/search/SEARCH_INDEX_ADMIN.md: one Lucene directory holds every space,
   so per-space operations must use drop(space), never rebuild().

6. loom-ui — REQUIRED, and the previous version of this task was wrong about what exists. Two pieces:
   - The person page does NOT show where a person appears. loom-ui/src/features/persons/PersonDetail.tsx
     DOES call listPersonClusters at L68, but it discards the cluster list entirely: L74-87 fan out to
     listClusterMembers and flatten the result into CropCandidate[] used only by the "import a face
     crop as a person image" picker at L290-317. Add the appearances list — cluster cards or asset
     links — from the data already being fetched. The API client exists and is unused for display
     (loom-ui/src/api/persons.ts L132-138).
   - Render the suggestion from step 4. There is no suggestion concept anywhere in loom-ui today:
     ClusterResponse (api/clusters.ts L12-54) and the UI FaceCluster type (src/types/index.ts
     L367-392) carry only a decided personId, and the person Autocomplete in WorkflowView.tsx L636-646
     lists every person alphabetically with no ranking. Add the field to both types, pre-select the
     suggested person in that Autocomplete, and show it on the card in
     loom-ui/src/features/faceDetection/ClustersPanel.tsx.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §2.2, §3.1 items 4 and 8,
§7 "Not built — the honest gap" · [../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) ·
[../features/search/SEARCH_INDEX_ADMIN.md](../features/search/SEARCH_INDEX_ADMIN.md) ·
`V2.79__cluster_review_model.sql` §4/5/8 · `VectorIndex.java` · Task 4 (blocking)
**Test Requirements:** A DAO test that a NULL-`asset_uuid` library-wide cluster coexists with per-asset
ones under the same upsert key (the constraint's NULL exemption is load-bearing and currently
untested). An endpoint test that a second asset containing an already-confirmed person yields a cluster
carrying that person as a **suggestion**, and that the suggestion does **not** set `person_uuid` until a
human confirms. `FacedetectNodeIntegrationTest` (integration-test) currently runs one asset
(`testFacedetectPersistsDetections`); add a two-asset case running the loop end to end. A Playwright
case in `loom-ui/e2e/person-detail-mocked.spec.ts` for the appearances list and in
`loom-ui/e2e/face-panels-mocked.spec.ts` for the suggestion.
`./setup-pool.sh` first; `mvn -pl loom/db/jooq,loom/core test` then `mvn -pl integration-test test`.

---

## Task 8: Settle the confirm/reject REST convention across review workflows

**Argumentation Summary:** Three review workflows have shipped with two different conventions for
recording a human verdict. Dedup decides with `PATCH /dedup-groups/:uuid`; faces decide with
`POST /clusters/:uuid/confirm` and `/reject`; and `ClusterEndpoint` *also* still exposes
`POST /clusters/:uuid` for a generic update (`ClusterEndpoint.java:58`). `WORKFLOW_FACE.md` §4.2 raises
this as an open question and argues the RPC-style sub-resource is right for faces — the operation
creates a person and mutates two tables atomically, which is not a field write — but the argument was
never generalised, and `WORKFLOW_OBJECT_DETECT.md` now has the same decision to make on the same
`detection` table. `CODING.md` states only the plural/singular half of the rule (`:13`-`:14`), not when a
verdict earns a sub-resource. Left alone, each workflow picks its own shape and the API teaches nothing.

Small and non-blocking, but it gets more expensive with every review surface that ships — and Task 3
adds three more routes to this same resource.

**Improvement Summary:** Write the rule down once — when a verdict is a field write and when it is an
RPC sub-resource — and align the existing endpoints with it.

```
1. State the rule in ../guidelines/CODING.md, extending the existing plural/singular paragraph: a
   verdict that only sets a status field is a PATCH; a verdict that creates or links another entity, or
   mutates more than one table atomically, is a POST sub-resource. Under that rule
   /clusters/:uuid/confirm is correct (it creates a person) and /reject is arguably a PATCH — decide
   whether symmetry between the two beats strict consistency with the rule, and say which and why.
2. Apply it to Task 3's member routes before they are written; that is the cheapest moment.
3. Apply it to the object-detection review before that workflow's endpoints ship, since it shares the
   detection table (WORKFLOW_OBJECT_DETECT.md §2.1).
4. Answer WORKFLOW_FACE.md §7 open question 3, and note the outcome in WORKFLOW_DEDUP.md so the
   PATCH-style precedent is not read as an accident.
5. Do NOT change the existing /confirm and /reject paths unless the rule says they are wrong. They are
   in the shipped Java client, clients/python/loom_client/methods/cluster.py (confirm_cluster,
   reject_cluster) and the UI (loom-ui/src/api/clusters.ts, WorkflowView.tsx L1388-1398); churn on a
   settled route to satisfy a convention written afterwards is the wrong trade.
6. loom-ui: no change, provided step 5 holds. If any route does move, loom-ui/src/api/clusters.ts is the
   single place the UI names these paths — the components call through it and never build a URL.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §4.2, §7 open question 3 ·
[../workflows/WORKFLOW_DEDUP.md](../workflows/WORKFLOW_DEDUP.md) ·
[../workflows/WORKFLOW_OBJECT_DETECT.md](../workflows/WORKFLOW_OBJECT_DETECT.md) §2.1 ·
[../guidelines/CODING.md](../guidelines/CODING.md) · Task 3 (the next routes on this resource)
**Test Requirements:** No behaviour change if step 5 holds, so the existing `ClusterEndpointTest` and
`DedupGroupEndpointTest` suites must pass **unchanged** — that is the evidence this was a documentation
task and not a silent API break. If any route does move, the old path keeps working and gets a test
pinning it, plus updated `clients/python` and OpenAPI regeneration from inside `loom/doc`.

---

_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (code audit)_
