# Face Workflow — Task List

> Work items for the face identity loop (detect → embed → cluster → confirm a person), derived from a
> code audit on 2026-08-10 at `8e6f4915`.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) (technical spec) ·
> [../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md](../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md) (models, packs, licensing) ·
> [../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) (vector index) ·
> [../loom/ui/TASK_UI_AI_ML.md](../loom/ui/TASK_UI_AI_ML.md) Tasks 3–4 (UI field-level gaps, not repeated here)
>
> **This file lists only what is still open.** The loop itself is built: `V2.79` made `cluster`
> machine-writable and linked it to `person`, `FacedetectNode` clusters per asset with DBSCAN,
> `/clusters/:uuid/{members,confirm,reject}` and `/persons/:uuid/clusters` serve the review, the UI
> shows real crops from this deployment, the demo seeds a PENDING face cluster, and
> `website/content/english/docs/nodes/facedetect/index.adoc` documents it for customers. Eleven of the
> twelve §6 defects are fixed. Two checkboxes still open in `WORKFLOW_FACE.md` §7 (customer docs, demo
> cluster) are **stale — both shipped**; fix that section when Task 1 lands.
>
> **Ordering.** **Task 1 is blocking and cheap** — until it lands, changing the model pack silently
> keeps confirmed identities that were decided against vectors from a different embedder, which is the
> one way this feature can produce a *wrong* answer rather than a missing one. ~~Task 2 is its natural
> companion (same migration, same DAO).~~ **Task 2 shipped first, as `V2.88`** — so Task 1 now needs no
> DDL at all, and must extend its version gate over Task 2's two columns.
> Task 3 is what makes the loop usable at real precision and
> should precede any calibration effort. Task 4 gates Task 5: there is no point building library-wide
> identity on an uncalibrated radius. Tasks 6–8 are independent.
>
> **Open product question that gates Task 5:** is per-asset identity ("who is in this video") useful on
> its own, or must cross-asset identity ("who is this") ship before the feature is announced?
> `WORKFLOW_FACE.md` §2.2 is honest that phase 1 only answers the first. Decide before scheduling Task 5.

---

## Task 1: Retire face clusters when the model pack changes

**Argumentation Summary:** Nothing invalidates a cluster when the embedder changes.
`ClusterDaoImpl.upsertCluster` preserves `REVIEW_COLUMNS = {status, person_uuid}` **unconditionally**,
and `ClusterDaoImpl.deleteStalePending(assetUuid, nodeKind, keptIndices)` filters on
`asset_uuid`/`node_kind`/`PENDING` only — neither reads `producer_version` or `model`. So after
switching `inspirefacePackPath` from Pikachu to Megatron, a cluster a human confirmed as "Anna" keeps
`status=CONFIRMED` and `person_uuid`, while its members are recomputed from vectors of a different
embedder with a different similarity geometry (0.48 vs 0.32 in the pack manifests). The verdict is
attributed to a grouping the reviewer never saw.

This is not an oversight in the design — it is an unimplemented promise. `V2.79`'s own header states
the provenance columns exist so *"a pack change retires stale proposals through the standard sweep
(WHERE node_kind = ? AND producer_version <> ?)"*, and `WORKFLOW_FACE.md` gotcha 5 says a pack switch
"invalidates every embedding and every cluster". No code performs either. `V2.81` solved exactly this
for `detection`, and its reasoning transfers verbatim: `cluster_index` is an ordinal, not an identity,
and it is not stable across model versions. `cluster.model` and `cluster.dimensions` already exist
(`V2.79` §8) and are written by `FacedetectNode`; they have no reader.

**Improvement Summary:** Gate the cluster review verdict on `producer_version`, mirroring
`DetectionDaoImpl.upsertDetection` — within one version the verdict stands, across versions it resets
to `PENDING` and the person link drops — and make `deleteStalePending` also remove this producer's
`PENDING` clusters whose `producer_version` no longer matches.

```
1. Read loom/db/flyway/src/main/resources/db/migration/V2.81__detection_review_state.sql §4 and
   DetectionDaoImpl.upsertDetection first. This task is that mechanism applied to "cluster";
   do not invent a second shape for it.
2. New migration (take the next free version at the time you write it; sort numerically, V2.9 < V2.88 —
   V2.88 is the highest today). It carries no DDL at all, only the COMMENT update in step 5: Task 2
   has shipped, so its columns already exist.
3. loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/cluster/ClusterDaoImpl.java:
   - upsertCluster: REVIEW_COLUMNS is now {status, person_uuid, reviewed_at, reviewer_uuid} and is
     preserved UNCONDITIONALLY. Make all four conditional on the incoming
     cluster.getProducerVersion() equalling the stored one; when it differs, reset status to PENDING
     and null the other three. DO NOT drop reviewed_at/reviewer_uuid from the preserved set while
     doing this — that would hand them back to the producer's own UPDATE.
     The mechanism already exists and is the one to copy verbatim: the three-argument
     upsert(pojo, preserved, overrides, keys...) helper takes a Map<Field<?>, Object> of CASE
     expressions, and DetectionDaoImpl.reviewOverrides() is exactly this problem already solved. Do
     not read the stored producer_version in a separate SELECT — an earlier draft of this task
     recommended that, and it is now the worse option.
   - deleteStalePending: add an OR arm so a PENDING cluster for this (asset, node_kind) is also
     deleted when its producer_version differs from the incoming one, regardless of cluster_index.
     Add a String producerVersion parameter; update the ClusterDao interface javadoc in
     loom/db/api/src/main/java/io/metaloom/loom/db/model/cluster/ClusterDao.java to state the rule.
   - Do NOT delete CONFIRMED or REJECTED clusters on a version change. A reset-to-PENDING preserves
     the fact that somebody looked; a delete destroys it. That is the same distinction V2.79 makes
     when it chose ON DELETE SET NULL for person_uuid.
4. cortex/nodes/facedetect/core/.../FacedetectNode.java: pass the node's producerVersion() on the
   bulk cluster write, and confirm ClusterCreateItem/ClusterBulkCreateRequest carry it through
   ClusterEndpointService.bulkCreateAssetClusters to the DAO. It is already set on the cluster POJO
   for the demo seed; verify it is set on the node path and that it changes when the pack changes
   (FacedetectNodeOptions derives "inspireface-pikachu-r18" from the pack path).
5. Update the COMMENT on "cluster"."status" to say what the detection one says: reset to PENDING by an
   upsert whose producer_version differs from the stored one. Do the same for "cluster"."reviewed_at"
   and "cluster"."reviewer_uuid", and correct V2.88's step-4 note in WORKFLOW_FACE.md §3.1.1, which
   states that this gate is still open.
6. Decide and document the embedding half. A cluster reset is pointless if the stale embeddings it
   grouped survive: check whether EmbeddingDao's upsert path replaces vectors when "model" changes,
   or whether Pikachu and Megatron vectors accumulate side by side in one asset. If they accumulate,
   either scope clustering to one model per run or add the matching sweep — and record the answer in
   WORKFLOW_FACE.md §3.5 either way.
7. Update WORKFLOW_FACE.md: answer §7 open question 4 ("does a pack change auto-invalidate clusters"),
   correct gotcha 5 from a warning into a statement of what the code now does, and tick the two stale
   §7 checkboxes (customer docs and the demo PENDING cluster) that already shipped.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §3.1 item 3, §7 open
question 4, gotcha 5 · migration `V2.79__cluster_review_model.sql` header ·
`V2.81__detection_review_state.sql` §4 · `DetectionDaoImpl.upsertDetection` · `DetectionUpsertReviewTest`
**Test Requirements:** `ClusterDaoTest#testUpsertResetsVerdictOnProducerVersionChange` (a CONFIRMED
cluster re-upserted under a new `producer_version` comes back PENDING with a null `person_uuid`,
`reviewed_at` and `reviewer_uuid`; the person row itself survives) and
`#testUpsertDoesNotClobberConfirmedStatus` must still pass unchanged — that pair is the whole contract.
`#testNodeReRunDoesNotClobberTheReviewer` and `#testRejectRecordsTheReviewer` (Task 2) must also stay
green unchanged: they upsert at the *same* producer_version, which is the half the gate must not
disturb. `ClusterDaoTest#testDeleteStalePendingRemovesOtherProducerVersions`.
`ClusterEndpointTest#testReRunDoesNotReopenAConfirmedCluster` unchanged, plus a new case for the
version-change path through `POST /assets/:uuid/clusters/bulk`.
`mvn install -pl loom/db/flyway && loom/db/jooq/generate.sh && ./setup-pool.sh` before running
`mvn -pl loom/db/jooq,loom/core test -Dtest='ClusterDaoTest,ClusterEndpointTest'`.

---

## Task 2: Record who confirmed a face cluster, and when — ✅ **DONE** (`V2.88`)

> **Shipped.** `V2.88__cluster_review_author.sql` adds `reviewed_at` / `reviewer_uuid` with the
> `V2.81` comments and no `ON DELETE`; `ClusterDaoImpl.confirm` and `.updateStatus` write both, and
> `REVIEW_COLUMNS` preserves them across a node re-run while `editor_uuid` keeps moving — which is the
> whole point. `ClusterResponse` publishes `reviewedAt` / `reviewerUuid`, the confirm and reject routes
> document the reviewed shape, and both review surfaces render it. See
> [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §3.1.1.
>
> **Two deviations from the plan below, both forced by ordering:**
> 1. Step 1 said to fold this into Task 1's migration. Task 1 had not landed, so this is its own
>    migration. Task 1 now needs no DDL of its own — its version gate is pure DAO work.
> 2. Step 4 (the version-gate reset must null both new columns) is therefore **carried into Task 1**,
>    where it is now restated. Until Task 1 lands, a pack change carries the verdict *and* its author
>    onto a regrouping the reviewer never saw.
>
> Tests: `ClusterEndpointTest#testConfirmRecordsTheReviewer` / `#testRejectRecordsTheReviewer`,
> `ClusterDaoTest#testNodeReRunDoesNotClobberTheReviewer` / `#testRejectRecordsTheReviewer` /
> `#testAnUnreviewedClusterHasNoReviewer`, and a Playwright case in
> `loom-ui/e2e/face-panels-mocked.spec.ts` asserting a decided card names its reviewer and a pending
> one does not. Python client regenerated, parity green.

**Argumentation Summary:** `cluster` has no `reviewed_at` and no `reviewer_uuid`.
`ClusterEndpointService.confirm`/`reject` pass `lrc.userUuid()` into `ClusterDao.confirm`/`updateStatus`,
which can only land it in `editor_uuid` — and `editor_uuid`/`edited` are machine-written provenance that
`FacedetectNode` overwrites on **every re-run** (`V2.47`). So the record of which human attributed a face
to a named person is destroyed by the next pipeline pass. `V2.81` added both columns to `detection` for
precisely this reason, with the comment *"reviewer_uuid: the user who decided. Distinct from editor_uuid,
which is machine-written provenance"*. Face data is biometric (`WORKFLOW_FACE.md` gotcha 12); an
attribution decision with no durable author is the weakest audit trail in the repository.

**Improvement Summary:** Add `reviewed_at` / `reviewer_uuid` to `cluster`, populate them from the
confirm and reject paths, expose them on `ClusterResponse`, and include them in the Task 1 version-gate
reset.

```
1. Fold this into Task 1's migration — one migration, one jOOQ regen, one setup-pool run.
   ALTER TABLE "cluster" ADD COLUMN "reviewed_at" timestamp;
   ALTER TABLE "cluster" ADD COLUMN "reviewer_uuid" uuid;
   ALTER TABLE "cluster" ADD CONSTRAINT ... FOREIGN KEY ("reviewer_uuid") REFERENCES "user" ("uuid");
   Copy the two COMMENT lines from V2.81 verbatim in intent — they explain the editor_uuid distinction.
   No ON DELETE action, matching what V2.81 did for detection.
2. Cluster POJO + ClusterImpl + ClusterDao: getters/setters, and set both fields inside
   ClusterDaoImpl.confirm and ClusterDaoImpl.updateStatus rather than at the service layer, so every
   caller (including a future bulk-review endpoint) gets them.
3. ClusterResponse gains reviewedAt / reviewerUuid. Mind the existing naming trap: the review verdict
   is published as reviewStatus, NOT status, because AbstractCreatorEditorRestResponse already owns
   "status" for the audit block (WORKFLOW_FACE.md §4).
4. Task 1's version-gate reset must null both new columns alongside status and person_uuid.
5. Regenerate the OpenAPI spec from inside loom/doc and re-run the Python client parity test —
   a new response field changes both (see clients/python).
6. Surface it in the UI where the verdict is shown: loom-ui/src/features/faceDetection/ClustersPanel.tsx
   and the FaceDetectionMode block in loom-ui/src/features/workflow/WorkflowView.tsx.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §4, gotcha 12 ·
`V2.81__detection_review_state.sql` (`reviewed_at`, `reviewer_uuid`, and their COMMENTs) ·
`V2.47__machine_written_audit_columns.sql`
**Test Requirements:** `ClusterEndpointTest#testConfirmRecordsTheReviewer` and
`#testRejectRecordsTheReviewer` (both fields set, `reviewerUuid` = the calling user);
`ClusterDaoTest#testNodeReRunDoesNotClobberTheReviewer` — an upsert at the **same** `producer_version`
leaves `reviewer_uuid`/`reviewed_at` intact while `editor_uuid` moves, which is the whole point of the
column. Python client parity test green.
`mvn -pl loom/core test -Dtest=ClusterEndpointTest` after `./setup-pool.sh`.

---

## Task 3: Let a reviewer correct cluster membership

**Argumentation Summary:** The schema anticipates human corrections and no code path makes one.
`embedding_cluster.origin` accepts `AUTO` or `MANUAL` (`V2.79` §3.2), `ClusterMember.ORIGIN_MANUAL`
exists, and `ClusterDao.link(uuid, uuid, confidence, origin)` writes it — but the only caller passing
`MANUAL` is `ClusterDaoTest:185`. `ClusterEndpoint` registers `delete`, `list`, `load`, `create`,
`update`, `listMembers`, `confirm`, `reject`, `listAssetClusters` and `bulkCreateAssetClusters`; there
is **no route to add or remove a member**, and `ClusterDao.unlink`/`unlinkAll` have no caller outside
tests.

The consequence is that the reviewer's only lever is all-or-nothing on a whole group. DBSCAN at an
uncalibrated `eps` of 0.6 (Task 4) will routinely merge two similar-looking people into one cluster and
split one person across two. Today the honest response to a merged cluster is to reject it, which
discards every correct member with it, and to a split person is to confirm both onto the same person
and hope. Neither correction is representable.

**Improvement Summary:** Add member-level review routes — detach a face from a cluster, attach it to
another, and split a cluster — writing `origin='MANUAL'` so a correction is distinguishable from the
machine's own grouping and survives the next re-run.

```
1. Decide the minimum viable set first and write it into WORKFLOW_FACE.md §4 before coding. The
   recommendation, smallest to largest:
   a. DELETE /api/v1/clusters/:uuid/members/:embeddingUuid   — remove a wrong face (UPDATE_CLUSTER)
   b. POST   /api/v1/clusters/:uuid/members                  — attach a face, origin=MANUAL (UPDATE_CLUSTER)
   c. POST   /api/v1/clusters/:uuid/split                    — move a subset into a new PENDING cluster
   (a) and (b) together already express both failure modes; (c) is sugar over them and can wait.
   Collection paths stay plural per ../guidelines/CODING.md; /split is RPC-style and singular, like
   /confirm and /reject.
2. Implement in ClusterEndpointService over the existing ClusterDao.link/unlink. Both are already
   idempotent — link ON CONFLICT DO UPDATE, unlink a plain delete — so no DAO change is needed for
   (a) and (b).
3. THE HARD PART, and the reason this is not a trivial task: a manual correction must survive the next
   node re-run. FacedetectNode currently re-links every member it computes, so a face a reviewer
   detached is silently re-attached on the next pass. Make the node's bulk link path skip any
   (embedding, cluster) pair already recorded with origin='MANUAL', and skip re-attaching an embedding
   that a reviewer explicitly detached. Detachment currently leaves no record — a deleted row is
   indistinguishable from one that was never written — so this needs either a tombstone
   (origin='EXCLUDED' as a third value, cheapest) or a decision that corrections only bind on
   non-PENDING clusters. Pick one, state the reasoning in the migration, and pin it with a test.
   Do not ship (a) without this: a correction that reverts on the next pipeline run is worse than no
   correction, because the reviewer believes it took.
4. UI: member-level actions in loom-ui/src/features/faceDetection/ClustersPanel.tsx (each crop gets a
   remove affordance) and in the FaceDetectionMode block of WorkflowView.tsx.
5. Regenerate the OpenAPI spec from inside loom/doc; add the new methods to clients/python
   (clients/python/loom_client/methods/cluster.py) or the parity test fails.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §3.2, §4 ·
`V2.79__cluster_review_model.sql` (the `origin` CHECK constraint) · `ClusterMember.ORIGIN_MANUAL` ·
[../guidelines/CODING.md](../guidelines/CODING.md) (plural collection paths, endpoint + permission tests)
**Test Requirements:** `ClusterEndpointTest`: detach removes exactly one member and leaves the rest;
attach writes `origin=MANUAL`; both 403 without `UPDATE_CLUSTER` (grant via group + role, never a direct
user grant — `user_permission` allows one direct permission per user). `ClusterDaoTest`: a re-run of
`bulkCreateAssetClusters` does **not** resurrect a detached member and does not downgrade a `MANUAL`
membership to `AUTO`. A Playwright case in `loom-ui/e2e/face-clusters-mocked.spec.ts`.
`mvn -pl loom/core,loom/db/jooq test -Dtest='ClusterEndpointTest,ClusterDaoTest'` after `./setup-pool.sh`;
`./node_modules/.bin/playwright test e2e/face-clusters-mocked.spec.ts` from `loom-ui` (never `npx`).

---

## Task 4: Calibrate `faceClusterEPS` against a real corpus

**Argumentation Summary:** `faceClusterEPS = 0.6` is a guess and is documented as one — in the spec
(§2.3: *"unvalidated against any real corpus"*), in the node option javadoc, and in the customer docs
(*"a starting point, not a calibrated value"*). It is a cosine **distance** radius, while InspireFace's
own pack manifests quote **similarity** thresholds — 0.48 for Pikachu, 0.32 for Megatron — so the
default is not merely uncalibrated, it is expressed in the opposite direction from the only published
guidance, and 0.6 does not correspond to either. `faceClusterMinimum = 2` is equally unverified.
`cortex/nodes/facedetect/core/src/test/.../cluster/` contains `FaceClustererTest` and `VectorsTest`,
both of which test the algorithm against synthetic vectors — correct for what they are, and no evidence
about the threshold.

Everything downstream inherits the error: `OUT_FACE_COUNT` claims to report distinct people, the review
queue's cost is the number of wrong groupings a human must fix, and any phase-2 cross-asset pass
(Task 5) compounds a per-asset radius across the library.

**Improvement Summary:** Assemble a labelled face corpus, measure pairwise same-person / different-person
cosine distance for each supported pack, and set the defaults from the measurement — recording what they
were calibrated against.

```
1. The corpus is the blocking sub-problem and there is no in-repo answer to it. /opt/metaloom/loom-testdata
   is unversioned, has no identity labels, and every video in it is silent and unannotated. Decide and
   record the source: a public labelled set (LFW / AgeDB-30 are the conventional choices for exactly
   this measurement) or a hand-labelled internal set. Note the licence of whatever is chosen in
   ../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md — that file owns licensing, not this one.
2. Write a calibration harness as an opt-in test or a small main under
   cortex/nodes/facedetect/core/src/test/java/.../cluster/, gated on the corpus being present on disk
   (the same pattern the InspireFace pack tests already use: packs live in inspireface4j/packs/Pikachu
   and are gitignored, so the tests must skip rather than fail when it is absent).
3. Emit, per pack: the distance distribution for same-person and different-person pairs, the ROC, and
   the equal-error-rate threshold. Report the chosen eps as BOTH distance and similarity so the number
   can be compared against the pack manifest without arithmetic.
4. Sweep faceClusterMinimum over the same corpus. It counts the point itself, so 2 means "needs one
   neighbour" — verify that on video, where one person yields many near-duplicate frames and the
   parameter behaves very differently than on a photo library.
5. Set the defaults in FacedetectNodeOptions from the measurement. If the answer is per-pack, the
   default cannot be a single constant: derive it from the resolved pack, and say so in the option
   javadoc and in website/content/english/docs/nodes/facedetect/index.adoc ("Tuning the grouping").
6. Record the corpus, the date and the resulting numbers in WORKFLOW_FACE.md §2.3, replacing the
   "calibrated against nothing" admission, and answer §7 open question 2.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §2.3, §9, §7 open
question 2 · [../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md](../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md) §5 ·
`FaceClusterer.java`, `Dbscan.java`, `FacedetectNodeOptions.java`
**Test Requirements:** The harness itself skips cleanly when the corpus is absent — assert that, or CI
breaks for everyone without the data. `FaceClustererTest` and `VectorsTest` must stay green unchanged;
they test the algorithm, and the threshold is not their subject. Add one regression asserting the
defaults match the recorded calibration, so a future edit to the constant fails loudly.
`mvn -pl cortex/nodes/facedetect/core test`.

---

## Task 5: Cross-asset identity — the same person across the library

**Argumentation Summary:** This is the one stage of the workflow that was never built, and the spec is
blunt about the cost: clustering runs inside `FacedetectNode` scoped to **one asset**, so "confirm this
cluster is Anna" means "Anna appears in *this* video". The same person in a second video produces a
second, unrelated `PENDING` cluster with no memory of the first, and the reviewer confirms her again,
and again, once per asset. The feature answers *"who is in this video"* and not *"who is this"*, which
is the question a media library is actually asked.

The schema was shaped for the fix and is waiting: `cluster.asset_uuid` is **nullable** precisely so a
library-wide cluster fits the same table distinguished by `node_kind` (`V2.79` §4/5, whose upsert key
exempts NULL-asset rows by SQL NULL semantics), and `cluster.centroid` + `model` + `dimensions` are
already written by `FacedetectNode` so a new face can be matched against an existing cluster without
re-reading its members. The `VectorIndex` SPI exists, face embeddings are indexed as their own space
keyed by `(type, model, dimensions)`, and `/admin/indices` already lists that space with per-space
reindex and drop.

**Improvement Summary:** A library-wide pass that matches per-asset clusters (or their centroids)
against confirmed persons and against each other via the vector index, proposing cross-asset identity
without a second migration.

```
1. Do Task 4 first. A library-wide radius built on an uncalibrated per-asset one multiplies the error
   across every asset instead of confining it to one.
2. Design decision to settle before coding, and to write into WORKFLOW_FACE.md §2.2 as the phase-2
   design: does the pass (a) match each new per-asset cluster centroid against the centroids of
   clusters already CONFIRMED onto a person — cheap, incremental, and it makes confirmation cumulative;
   or (b) re-cluster the whole library's embeddings periodically — expensive, and it fights the human
   verdicts already recorded. Recommend (a): it reuses cluster.centroid, needs only a k-NN query per
   new cluster, and never contradicts an existing decision. Reserve (b) for an explicit admin action.
3. Where it runs is the second decision. It is not a Cortex node — it has no single asset to key on
   and NEW_NODE.md does not apply. A Loom-side service triggered on confirmation (and on demand from
   /admin) fits the existing shape; say why in the spec.
4. Auto-attribution vs proposal: when a new cluster matches a confirmed person above threshold, does it
   become CONFIRMED automatically or PENDING-with-a-suggestion? Default to the latter — a wrong
   automatic attribution of biometric identity is the expensive failure, and the review queue already
   exists. Carry the suggestion on the response so the UI can pre-select the person.
5. Only then implement, against the VectorIndex SPI. Note the trap recorded in
   ../features/search/SEARCH_INDEX_ADMIN.md: one Lucene directory holds every space, so per-space
   operations must use drop(space), never rebuild().
6. UI: the person detail view should show every asset the person appears in — /persons/:uuid/clusters
   already returns exactly that and is already called from FaceDetectionManagement.tsx:85.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §2.2, §3.1 items 4 and 8,
§7 "Not built — the honest gap" · [../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) ·
[../features/search/SEARCH_INDEX_ADMIN.md](../features/search/SEARCH_INDEX_ADMIN.md) ·
`V2.79__cluster_review_model.sql` §4/5/8
**Test Requirements:** A DAO test that a NULL-`asset_uuid` library-wide cluster coexists with per-asset
ones under the same `cluster_producer_key` (the constraint's NULL exemption is load-bearing and
currently untested). An endpoint test that a second asset containing an already-confirmed person yields
a cluster carrying that person as a suggestion, and that the suggestion does **not** set `person_uuid`
until a human confirms. An `integration-test` case running two assets through the loop end to end.
`./setup-pool.sh` first; `mvn -pl loom/db/jooq,loom/core test` then `mvn -pl integration-test test`.

---

## Task 6: Give a person a real avatar, and decide `person_image`'s fate — ✅ **DONE** (`V2.89`-`V2.91`)

> **Outcome:** neither of the two options as written. A person owns their pictures outright —
> `PERSON_IMAGE` attachments carrying `person_uuid`, one of them designated by
> `person.avatar_attachment_uuid` — so the gallery `person_image` named finally has a writer, a REST
> surface and a UI, on storage that no asset deletion can reach. `person_image` and
> `primary_image_uuid` are both dropped. `POST /clusters/:uuid/confirm` deliberately did **not**
> change: it records who attributed a face to whom, and choosing what somebody looks like is a
> separate act, done from the person's own page. The recorded decision is
> [WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §3.4.

**Argumentation Summary:** Two related defects, one of them §6.12 — the last unfixed entry in the
spec's defect table.

`person_image` (`V2.26`) has **no writer**: no DAO, no endpoint, no UI. Only `AssetCascadeTest` touches
it, deliberately, so *"the table cannot grow a writer and an orphan problem at the same time"*. It has
been dead since it was created.

`person.primary_image_uuid` FKs to **`asset`** (`V2.26:33`), and the UI renders it as
`assetBinaryUrl(primaryImageUuid)` in `PersonsPanel.tsx:70`, `FaceDetectionManagement.tsx:48` and
`AssetDetail.tsx:260`. For the population this feature creates — people discovered in video — that
avatar is the entire video file, which is not an avatar. The right image already exists and is already
served: the `FACE_CROP` attachment written by the node per detection, at
`GET /assets/:uuid/detections/:detectionUuid/crop`. Nothing points a person at one.

(The UI half of setting a primary image is [../loom/ui/TASK_UI_AI_ML.md](../loom/ui/TASK_UI_AI_ML.md)
Task 3, still open. This task is the model underneath it — do them together.)

```
1. Decide, and record the decision in WORKFLOW_FACE.md §3.4 either way. Two options:
   a. RECOMMENDED — point a person's avatar at a face crop. The confirmation flow already knows the
      cluster's members and their detections, so POST /clusters/:uuid/confirm can pick the highest
      -confidence member's FACE_CROP as the person's avatar with no extra reviewer input. This needs a
      nullable person.avatar_detection_uuid (or attachment_uuid) rather than overloading
      primary_image_uuid, which means "the primary gallery image" and FKs to asset.
   b. Drop person_image and leave the avatar as an asset pointer. Legitimate if the gallery concept is
      abandoned; then say so and delete the table in a migration rather than leaving a dead table that
      every schema reader has to rule out.
   Do not do neither. The table has been unwritten since V2.26 and the ambiguity is itself the cost.
2. For (a): migration adding the column and FK (ON DELETE SET NULL — losing a crop must not delete the
   person). Set it in ClusterDaoImpl.confirm when the person is created, leave it alone when linking an
   existing person who already has one.
3. PersonResponse exposes the crop URL, not a raw uuid — the UI should not have to know how a crop is
   addressed. Update the three UI call sites listed above.
4. If person_image survives (a), give it a writer and a REST surface, or fold it into this task's
   avatar model and delete it. A third state — kept, documented, still unwritten — is not an outcome.
5. Regenerate the OpenAPI spec from inside loom/doc; update clients/python for the changed
   PersonResponse or the parity test fails.
```

**Improvement Summary:** Point a confirmed person at the face crop that identifies them, and either
give `person_image` a writer or remove it.

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §6.12, §3.3, §3.4, §1.4 ·
[../loom/ui/TASK_UI_AI_ML.md](../loom/ui/TASK_UI_AI_ML.md) Task 3 · `V2.26__add_person.sql` ·
`V2.79__cluster_review_model.sql` §3.3 (`FACE_CROP`, `attachment.detection_uuid`)
**Test Requirements:** `PersonEndpointTest`: the `primaryImageUuid` regression from §6.9 stays green;
a person created by confirming a cluster comes back with a usable avatar reference. `ClusterDaoTest`
cascade coverage for the new FK — deleting the attachment or detection **nulls** the pointer and does
not delete the person. `AssetCascadeTest` updated if `person_image` is dropped. Playwright:
`loom-ui/e2e/face-panels-mocked.spec.ts` renders the avatar.
`./setup-pool.sh` after the migration; `mvn -pl loom/core,loom/db/jooq test`.

---

## Task 7: Add the OpenCV backend so the default face stack is not non-commercially licensed

**Argumentation Summary:** `FacedetectNodeCapabilities` is `{INSPIREFACE, DLIB}` — verified in the
checkout, it is a two-value enum and nothing else. The default and only working backend is InspireFace,
which is **non-commercially licensed**, and `WORKFLOW_FACE.md` gotcha 13 calls that "not a code defect,
but a shipping blocker". The alternative already exists on the other side of the seam: `video4j` ships
`video4j-facedetect-opencv` with `detectEmbeddings`/`extractEmbeddings` implemented. The spec's own
"stale claim corrected" note says the module exists and *"what is still missing is the metaloom side"*.
So the blocker is one enum value and a binding, not a research problem.

**Improvement Summary:** Wire the existing `video4j-facedetect-opencv` backend into
`FacedetectNodeCapabilities` and the node's backend selection, so a commercially licensed face stack is
selectable.

```
1. Confirm first, in the video4j checkout, that the OpenCV backend implements the same
   detectFaces(img, withEmbeddings) single-filtered-pass call the node relies on. If it only offers
   detectFaces + detectEmbeddings separately, DO NOT zip the two lists: detectEmbeddings runs detection
   unfiltered, so its ordinals do not line up with the filtered ones and every vector attaches to the
   wrong face — silently, with plausible output (WORKFLOW_FACE.md §1.2). Add the combined call to
   video4j instead, mirroring InspireFacedetectorImpl.
2. Add OPENCV to FacedetectNodeCapabilities and the dependency + backend construction in
   cortex/nodes/facedetect/core.
3. Check the OpenCV major matches. video4j and opencv-ffm are pinned to 4.10.0-SNAPSHOT (= OpenCV 4.10)
   and there is a known OpenCV ABI split between inspireface4j and video4j that has produced SIGSEGVs.
   Verify before blaming new code — FACEDETECTION_OVERVIEW.md §11.
4. Embeddings from the two backends are NOT interchangeable. The model name must differ, since
   (type, model, dimensions) is both the embedding row's unique key and the vector-index space key —
   this is what stops the two populations from ever being compared. Task 1's version gate then retires
   clusters correctly when a deployment switches backend, which is the same event as a pack change.
5. Document the licence and the accuracy difference in FACEDETECTION_OVERVIEW.md (that file owns
   licensing), and list the new capability in
   website/content/english/docs/nodes/facedetect/index.adoc.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §6 stale-claim note,
gotcha 13, §9 (`capabilities`) · [../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md](../features/nodes/facedetect/FACEDETECTION_OVERVIEW.md) §6.3, §11 ·
`FacedetectNodeCapabilities.java`, `video4j/facedetect/opencv/`
**Test Requirements:** `FacedetectNodeTest` parameterised over the capability, skipping cleanly when the
backend's models are absent (the InspireFace pack under `inspireface4j/packs/Pikachu` is gitignored and
its tests already skip — follow that pattern). A node-descriptor regeneration: install the cortex node
module **before** regenerating `node-descriptors.json`, or the harvest reads a stale jar.
`mvn -pl cortex/nodes/facedetect/core -am test`, then
`mvn -o -pl integration-test test -Dtest=NodeSpecGoldenTest -Dloom.regenerateNodeDescriptors=true`.

---

## Task 8: Settle the confirm/reject REST convention across review workflows

**Argumentation Summary:** Three review workflows have now shipped with two different conventions for
recording a human verdict. Dedup decides with `PATCH /dedup-groups/:uuid`; faces decide with
`POST /clusters/:uuid/confirm` and `/reject`; `ClusterEndpoint` *also* still exposes
`POST /clusters/:uuid` for a generic update. `WORKFLOW_FACE.md` §4.2 raises this as an open question and
argues the RPC-style sub-resource is right for faces — the operation creates a person and mutates two
tables atomically, which is not a field write — but the argument was never generalised, and
`WORKFLOW_OBJECT_DETECT.md` now has the same decision to make on the same `detection` table. Left alone,
each workflow picks its own shape and the API teaches nothing.

Small and non-blocking, but it gets more expensive with every review surface that ships.

**Improvement Summary:** Write the rule down once — when a verdict is a field write and when it is an
RPC sub-resource — and align the existing endpoints with it.

```
1. State the rule in ../guidelines/CODING.md, which already reserves singular paths for RPC-style
   resources: a verdict that only sets a status field is a PATCH; a verdict that creates or links
   another entity, or mutates more than one table atomically, is a POST sub-resource. Under that rule
   /clusters/:uuid/confirm is correct (it creates a person) and /reject is arguably a PATCH — decide
   whether symmetry between the two beats strict consistency with the rule, and say which and why.
2. Apply it to the object-detection review before that workflow's endpoints ship, since it shares the
   detection table (WORKFLOW_OBJECT_DETECT.md §2.1).
3. Answer WORKFLOW_FACE.md §7 open question 3, and note the outcome in WORKFLOW_DEDUP.md so the
   PATCH-style precedent is not read as an accident.
4. Do NOT change the existing /confirm and /reject paths unless the rule says they are wrong. They are
   in the shipped Java client, the Python client and the UI; churn on a settled route to satisfy a
   convention written afterwards is the wrong trade.
```

**References:** [../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §4.2, §7 open question 3 ·
[../workflows/WORKFLOW_DEDUP.md](../workflows/WORKFLOW_DEDUP.md) ·
[../workflows/WORKFLOW_OBJECT_DETECT.md](../workflows/WORKFLOW_OBJECT_DETECT.md) §2.1 ·
[../guidelines/CODING.md](../guidelines/CODING.md)
**Test Requirements:** No behaviour change if step 4 holds, so the existing `ClusterEndpointTest` and
`DedupGroupEndpointTest` suites must pass **unchanged** — that is the evidence this was a documentation
task and not a silent API break. If any route does move, the old path keeps working and gets a test
pinning it, plus updated `clients/python` and OpenAPI regeneration from inside `loom/doc`.

---

_Git HEAD revision: `8e6f4915`_
_Last updated: 2026-08-10 (Task 2 shipped as `V2.88`; Task 1 restated — no DDL left, gate extended over
`reviewed_at`/`reviewer_uuid`)_
