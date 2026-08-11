# Workflows — Task List

> Work items for the twelve workflows under `spec/workflows/`, re-audited against the code on
> 2026-08-11 at `8c153347`.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../workflows/WORKFLOWS.md](../workflows/WORKFLOWS.md) is the family index and carries
> the shared anatomy (§3) and the cross-cutting defect table (§4) these tasks reference as `X1`-`X10`.
>
> **Completed tasks are deleted, not ticked.** Tasks 1 (`FilterBy.TAG`/`RATING`), 2 (tag persistence
> in the workflow view) and 5 (review state on `detection`, `V2.81`) are gone because the audit found
> them fully built. Surviving tasks keep their original numbers, so the numeric gaps are deliberate
> and cross-file citations (`W3`, `W6`, `W9` …) still resolve. New work starts at Task 16.
>
> **Defect-table status after this audit** — X1, X2, X3, X4, X8 and X9 are **closed**; X5, X6 and X7
> are **fully open** (Task 8); X10 is **partially closed** (Task 15). §4 of `WORKFLOWS.md` still
> describes all ten as open with line references that no longer resolve — that is Task 16.
>
> **Ordering.** Tasks stay in numeric order below so a citation is findable; severity order for a
> reader picking up work is:
>
> 1. **Task 17** — 16 `ctx.failure(...).next()` call sites silently report SUCCESS. Cross-cutting
>    correctness, and it **blocks Task 13** (an ingest migration cannot be trusted while it holds).
> 2. **Task 6** — one migration comment and two DTO fields; an hour, and it stops the
>    `detection.type` string drifting a second time.
> 3. **Task 18** — the ledger's `origin` is a constant and a row cannot name its run. **Blocks Task 9**
>    and every "which worker wrote this?" question.
> 4. **Task 8** — X5/X6/X7 in one change; degrades five of the six shipped review modes.
> 5. **Tasks 3 and 4** — finish what already shipped (dedup keeper roles + queue paging; the relocate
>    module's demo pipeline and missing tests).
> 6. **Tasks 7, 9, 16, 15** — upload feedback, the AI-review record, spec/doc truth.
> 7. **Tasks 10-14** — greenfield proposals whose own spec files carry their build order; the tasks
>    here are entry points, not full plans. Task 12 depends on Task 4 (the mover) and on the
>    enforcement point Task 14 also needs — decide it once.

---

## Task 3: Finish the dedup review loop — keeper roles, queue paging, per-node E2E

**Argumentation Summary:** The loop is wired end to end (`loom-ui/src/api/dedup.ts`,
`loom-ui/src/features/workflow/dedupGroups.ts`, `DeduplicationMode` driving a real `status=PENDING`
queue), and three defects from that build are still open. (1) `DedupGroupDaoImpl.updateStatus`
(`loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/dedup/DedupGroupDaoImpl.java:163-173`)
writes `STATUS`, `EDITED`, `EDITOR_UUID` and `KEEP_ASSET_UUID` and never rewrites
`dedup_group_member.role`; `DedupGroupEndpointService.java:199` records that roles are "deliberately
ignored". Nothing is wrong today because every reader prefers the pointer — but the first consumer
that joins on `role` will move the file the reviewer chose to keep. (2) `integration-test` holds 30+
`*NodeIntegrationTest` classes and **none** for `hash-dedup`, `fingerprint-dedup` or
`fingerprint-dedup-apply`; the workflow with a destructive apply step is the one with no E2E.
(3) The queue loads exactly one page — `WorkflowView.tsx:1037` calls `listDedupGroups` once with
`limit: PAGE_SIZE` and there is no `hasMore`/cursor state, although `dedup.ts:85` already accepts
`PagingParams`. A reviewer with 200 pending groups reviews 25 and the screen goes empty.

**Improvement Summary:** Rewrite member roles in the same write as the keeper reassignment, page the
queue in the UI, and add the missing per-node integration tests.

```
1. loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/dedup/DedupGroupDaoImpl.java:163-173 -
   when keepAssetUuid changes, rewrite dedup_group_member.role in the SAME transaction:
   the named member becomes ROLE_KEEP, every other member ROLE_DUP. Do nothing when the
   pointer is unchanged. Then delete the "roles are deliberately ignored" note at
   loom/services/rest/.../impl/DedupGroupEndpointService.java:199 and the matching
   caveat in DedupGroup's javadoc - do not leave a comment describing a fixed bug.
2. Mirror it in the memory DAO impl (loom/db/memory) so the contract tests in
   loom/db/api-test cover both.
3. UI (loom-ui/src/features/workflow/WorkflowView.tsx:1037): keep the effect but hold
   {groups, nextFrom, loading} and append on demand. dedup.ts:85 already takes
   PagingParams - pass {from: nextFrom}. Advance automatically when the reviewer
   reaches the last group rather than exposing a "load more" button: this mode is
   keyboard-driven and a button is a mouse affordance.
4. integration-test/src/test/java/io/metaloom/loom/test/integration/node/: add
   HashDedupNodeIntegrationTest and FingerprintDedupNodeIntegrationTest extending
   AbstractNodeIntegrationTest, following TagNodeIntegrationTest. The apply path is the
   valuable one: propose over two identical files, PATCH the group CONFIRMED through the
   Loom container, run fingerprint-dedup-apply and assert the loser reported on the
   confirmed_dup port and that the KEEP's bytes still match its recorded SHA-512.
```

**References:** [../workflows/WORKFLOW_DEDUP.md](../workflows/WORKFLOW_DEDUP.md) §2 ·
[NODE_DEDUP.md](../features/nodes/dedup/NODE_DEDUP.md) §3-§4 · migrations `V2.61`, `V2.62`
**Test Requirements:** `DedupGroupDaoTest` extended (reassigning the keeper flips exactly two roles;
a status-only update touches none), `DedupGroupEndpointTest` extended (the PATCH response and a
re-read agree on both the pointer and the roles), the two new integration tests, and a Playwright
case in `loom-ui/e2e/workflow-dedup-mocked.spec.ts` asserting a second page is fetched when the
reviewer reaches the end of the first. Run: `mvn -pl loom/core -am test -Dtest='DedupGroup*'` after
`./setup-pool.sh`, and `cd loom-ui && ./node_modules/.bin/playwright test e2e/workflow-dedup-*`
(⚠️ `npx` stalls in this sandbox).

---

## Task 4: Finish the relocate module — demo pipeline, per-node E2E, missing standard tests

**Argumentation Summary:** `MoveNode` and `AssignNode` shipped (`cortex/nodes/relocate/`, 41 tests),
their customer docs landed (`website/content/english/docs/nodes/move/`, `.../assign/`), and both
kinds are in the descriptor set, so the pipeline editor offers them. Three items from the original
brief never landed. (a) No demo pipeline uses either node: `DemoDatabaseInitializer`'s
`reviewTriageDefinition()` (`loom/core/src/main/java/io/metaloom/loom/core/boot/DemoDatabaseInitializer.java:1782-1817`)
routes its `trash` bucket into a **`tag`** node (`pn5`, `:1795`), and the only seeded collections are
`Demo Images` and `Demo Videos` — so a demo stack shows the trash workflow half-drawn and `assign`
not at all. (b) `integration-test` has no `MoveNodeIntegrationTest` / `AssignNodeIntegrationTest`,
which for a node that **relocates bytes across filesystems** is the E2E most worth having.
(c) `cortex/nodes/relocate/core/src/test/java/io/metaloom/cortex/node/relocate/` has no persistence
test, no pipeline-chain test, no `assertj/` helper package and no `AssignOptionsValidationTest`;
every comparable module (`cortex/nodes/tag`, `cortex/nodes/whisper`, `cortex/nodes/filter`) has all
four. The singleton test named in the original brief is **not** a standard — only `tag` has one — and
is dropped here.

**Improvement Summary:** Make the demo show the trash workflow end to end and bring the module's test
set up to the house standard.

```
1. loom/core/src/main/java/io/metaloom/loom/core/boot/DemoDatabaseInitializer.java: seed a
   "Published" collection alongside Demo Images/Demo Videos, and extend
   reviewTriageDefinition() so the trash bucket feeds a `move` node (target FOLDER,
   dryRun true - the demo container's media is database rows, and a move that cannot find
   bytes must not look like a failure) and the keep bucket feeds an `assign` node
   (target COLLECTION, the new Published collection). Keep the existing `tag` node: the
   tag is what FilterBy.TAG routes on, the move is what acts.
2. integration-test/src/test/java/io/metaloom/loom/test/integration/node/: add
   MoveNodeIntegrationTest and AssignNodeIntegrationTest extending
   AbstractNodeIntegrationTest. Move: assert the file left its source path, arrived under
   targetFolder, that asset_location was rewritten, and that a cross-device target
   reports crossDevice honestly rather than silently copying. Assign: assert the
   collection_asset row exists and that a second run is an idempotent no-op.
3. cortex/nodes/relocate/core/src/test/java/io/metaloom/cortex/node/relocate/: add
   MoveNodePersistenceTest and AssignNodePersistenceTest (the asset_location /
   collection membership write plus the asset_node_result ledger row - copy
   cortex/nodes/tag/core/src/test/.../TagNodePersistenceTest), MoveNodePipelineTest
   (the node inside a two-node chain, port payloads asserted), AssignOptionsValidationTest,
   and an assertj/ package (MoveNodeAssertions, MoveOptionsAssert) mirroring
   cortex/nodes/tag/core/src/test/.../assertj/.
4. NO loom-ui change is required. loom-ui/src/types/nodeDescriptors.ts holds types only;
   node kinds and their parameters are fetched at runtime from
   GET /pipeline/node-descriptors (loom-ui/src/api/nodeDescriptors.ts), and both kinds are
   already in the checked-in offline snapshot website/static/pipeline-editor/node-descriptors.json.
   If step 1 changes any option name, regenerate the descriptors - and install the cortex
   node module FIRST or the harvest reads a stale jar.
```

**References:** [../workflows/WORKFLOW_TRASH.md](../workflows/WORKFLOW_TRASH.md) §3, §6 ·
[../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) · `cortex/nodes/relocate/` · `cortex/fs/`
**Test Requirements:** The five new test classes above plus the two integration tests; the existing
41 relocate tests stay green. `mvn -pl cortex/nodes/relocate/core -am test`, then
`mvn -o -pl integration-test test -Dtest='MoveNodeIntegrationTest,AssignNodeIntegrationTest'`.
⚠️ The demo seeding path itself has no test and `BootstrapInitializer` swallows its failures — verify
step 1 by booting `./start-demo.sh`, or add the guard described in Task 15 step 3.

---

## Task 6: Settle the `detection.type` contract and let a client write a label

**Argumentation Summary:** X1 and X2 are closed — `FacedetectNode.java:778` writes `setType("face")`,
`WorkflowView.tsx:1093` filters on `"face"` with a comment naming the three strings, and
`loom-ui/src/api/detections.ts:16` has `label`. Two smaller defects survive. (1) The **schema comment
still names a type nothing writes**: `V2.43__rework_detection_embedding.sql:65` documents
`facedetection`, the producer writes `face`, and `V2.79__cluster_review_model.sql:179` documents
`face` — so the DDL contradicts itself. The object side is pinned by a test that asserts "the type
the V2.43 schema comment names" (`ObjectDetectNodePersistenceTest.java:123`); the face side has no
such guard, which is precisely how it drifted. (2) `label` is **write-once and unwritable from the
UI**: the TypeScript `DetectionCreateRequest` / `DetectionUpdateRequest`
(`loom-ui/src/api/detections.ts:60-81`) have no `label`, and the Java `DetectionUpdateRequest`
(`loom-shared/rest-model/.../detection/DetectionUpdateRequest.java:9-21`) has none either, although
`DetectionCreateRequest` and `DetectionResponse` both do.

**Improvement Summary:** Correct the column comment in a migration, guard the face type with a test,
and make `label` writable on both sides of the wire.

```
1. New migration - take the NEXT FREE version (V2.95 is the highest at 8c153347; never
   hard-code a number from a spec). COMMENT ON COLUMN "detection"."type" naming the values
   actually written: 'face' (facedetect) and 'objectdetection' (objectdetect), and stating
   that the node's *options* key `facedetection` and the workflow mode of the same name are
   different strings. DDL-comment only - no data touched. Then ./setup-pool.sh.
2. cortex/nodes/facedetect/core/src/test/java/io/metaloom/cortex/node/facedetect/
   FacedetectNodeDetectionsTest.java: assert the persisted type is exactly "face", with the
   same "this is the contract, not an implementation detail" wording
   ObjectDetectNodePersistenceTest.java:123 uses.
3. Add `label` to loom-shared/rest-model/.../detection/DetectionUpdateRequest.java and honour
   it in the update handler, or DECIDE that a producer label is immutable after create and say
   so in the class javadoc - a reviewer's correction already has its own field
   (`correctedLabel`, V2.81). Do not leave it undecided: the response carries a field no
   request can set.
4. loom-ui/src/api/detections.ts: add `label?: string` to DetectionCreateRequest, and to
   DetectionUpdateRequest only if step 3 decided it is mutable. No other loom-ui change is
   needed - WorkflowView.tsx:1110 already reads `d.correctedLabel ?? d.label ?? d.type`.
5. Regenerate the Python client models from Java and re-run its parity test (clients/python).
```

**References:** [../workflows/WORKFLOWS.md](../workflows/WORKFLOWS.md) §4 (X1, X2 — mark closed as
part of Task 16) · [../workflows/WORKFLOW_OBJECT_DETECT.md](../workflows/WORKFLOW_OBJECT_DETECT.md) §1.3 ·
[../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §6.1 · `V2.43`, `V2.79`, `V2.81`
**Test Requirements:** The new `FacedetectNodeDetectionsTest` case; `DetectionEndpointTest` extended
if step 3 makes `label` mutable (round-trip plus a 403 without `UPDATE_DETECTION`);
`SceneLayoutNodePersistenceTest` stays green unchanged (it already builds `DetectionResponse` with
`"face"` at `:142-143`, `:232`, `:234`); the Python parity test.
`mvn -pl cortex/nodes/facedetect/core -am test` and `mvn -pl loom/core -am test -Dtest=Detection*`.

---

## Task 7: Report the upload trigger outcome, and add a backfill path

**Argumentation Summary:** The upload workflow works and is invisible when it does not.
`AssetPipelineTrigger` (`loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/AssetPipelineTrigger.java`)
matches a pipeline by mime type and dispatches; the outcome exists **only as a log line** (`:87-88`
`dispatched={}`), the no-match case is a `log.debug` and a bare `return` (`:78-79`), and the
`PipelineRunResponse` at `:86` is dropped. The uploader sees a successful upload and no processing,
with no way to tell "no pipeline matched" from "no worker can run this graph" (a 503 naming the
kinds). `loom-ui/src/features/uploads/` carries no trigger field at all. Separately the trigger fires
on `asset.created` only, so a pipeline added later never processes assets already in the system:
`POST /pipelines/:uuid/run` (`PipelineEndpoint.java:134-141`) takes explicit `mediaUuids`/`path`, and
nothing selects "everything in this library that was never triggered". Finally `meta.trigger` is
untyped — `PipelineMatcher.java:31` reads it and `PipelineValidationService` never mentions it, so a
typo'd `mimetypes` key validates cleanly and silently matches nothing. There is no
`AssetPipelineTriggerTest`.

**Improvement Summary:** Surface the trigger outcome to the uploader, add a way to run a pipeline
over an existing set, and type the trigger block.

```
1. AssetPipelineTrigger.handle already computes the outcome. Carry it out - pick ONE and
   record the choice in WORKFLOW_UPLOAD.md: either make the match synchronous in
   AssetUploadEndpointService (one batched DAO read plus in-memory filtering) and extend
   the upload response with {triggered, runUuid?, reason?}, or push it over the
   pipeline-events WebSocket the UI already holds open. The EventBus publish is
   fire-and-forget, so the response cannot simply wait for it.
2. loom-ui: surface the reason in the upload queue row -
   loom-ui/src/features/uploads/UploadView.tsx (the row), uploadQueue.ts (the item state)
   and uploadFormat.ts (the label). "no matching pipeline" and
   "unsupported kinds: [whisper]" are different problems and must not look alike. Add the
   strings to BOTH locales.
3. Backfill: POST /api/v1/pipelines/:uuid/run-over {libraryUuid | collectionUuid |
   assetUuids[]}, reusing PipelineEndpointService.dispatchRun / runForAsset
   (PipelineEndpointService.java:272-277). Guard it: a dry-run mode returning the count,
   and a cap - this can dispatch tens of thousands of items.
4. Type the trigger: a PipelineTrigger model in loom-shared/pipeline-model with a
   validation rule REJECTING unknown keys, enforced in PipelineValidationService. That
   service is the single authority on definition contents (this feature has already been
   de-duplicated once) - do not add a second validator. Mirror the rule in the Playwright
   /validate mocks, whose route ordering is the inverse of the server's.
```

**References:** [../workflows/WORKFLOW_UPLOAD.md](../workflows/WORKFLOW_UPLOAD.md) §2 ·
[../features/pipeline/PIPELINE_VALIDATION.md](../features/pipeline/PIPELINE_VALIDATION.md) ·
`AssetPipelineTrigger.java`, `PipelineMatcher.java`, `PipelineEndpointService.java:272`
**Test Requirements:** A new `AssetPipelineTriggerTest` (event → match → `runForAsset` with the
asset's creator; **no** dispatch for a duplicate upload; a 503 from dispatch does not throw and is
reported). `AssetUploadEndpointTest` extended for the outcome field. A `run-over` endpoint test with
a permission case and a dry run. A `PipelineValidationServiceTest` case for an unknown trigger key.
A mocked Playwright spec asserting the two upload-queue reasons render differently.

---

## Task 8: Persist key profiles and give every mode a real queue

**Argumentation Summary:** Three shared defects degrade five of the six review modes at once.
(X5) Key profiles live in `useState(DEFAULT_PROFILES)` (`WorkflowView.tsx:972-973`); rebinding writes
React state (`:1473`) and there is no `localStorage` and no endpoint anywhere in
`loom-ui/src/features/workflow/`, so every rebind is lost on reload. (X6) One effect
(`WorkflowView.tsx:977-980`) loads `listAssets(token, {limit: PAGE_SIZE})` sliced to 20 for **every**
mode — its dependency array does not even contain `mode` — rather than the items that need a
decision. Dedup is the exception (`:1037`, a real `status=PENDING` queue) and is the shape the
others need. Notably the ingredients now exist for detections: `GET /detections?status=PENDING&type=`
is live (`DetectionEndpoint.java:57-62`, `DetectionEndpointService.java:425-435`, index
`idx_detection_status_type`) and `listDetections` is defined at `loom-ui/src/api/detections.ts:273`
with **zero callers** — the workflow still drives off per-asset `listAssetDetections` (`:1081`).
(X7) Nothing records that an item was reviewed for the asset-driven modes, so a session cannot be
resumed and two reviewers cannot split a queue.

**Improvement Summary:** Persist profiles, replace "the first 20 assets" with a per-mode queue query,
and show progress from the server's count.

```
1. Profiles: new loom-ui/src/features/workflow/keyProfilePersistence.ts - a pure module in
   the shape of ratingPersistence.ts/tagPersistence.ts, backed by localStorage keyed by
   profile id, tolerant of a corrupt or stale value (fall back to DEFAULT_PROFILES, never
   throw into render). Wire it at WorkflowView.tsx:972-973 and :1473. A server-side
   key_profile table is the follow-up if profiles must travel between devices - DECIDE
   explicitly rather than defaulting to a migration.
2. Queue: make the loading effect mode-aware (add `mode` to its deps) and give each mode
   its own source.
     rating/tagging  -> GET /assets?unrated=true / ?untagged=true  (NEW - backend work)
     dedup           -> GET /dedup-groups?status=PENDING           (done)
     objects/faces   -> GET /detections?status=PENDING&type=       (endpoint DONE; call
                        the existing listDetections from loom-ui/src/api/detections.ts:273,
                        which currently has no callers, and load each parent asset lazily
                        the way the dedup queue loads group members at :1052)
     llm             -> GET /node-results?status=PENDING           (Task 9)
   The two asset filters are the only backend work: AssetEndpointService.list
   (loom/services/rest/.../impl/AssetEndpointService.java:99-101) reads no query params
   today, so add them there plus the DAO predicates and an index.
3. Progress: render "n of m decided" from the server's total (the paging _metainfo /
   a count query), not from local state, so a resumed session and a second reviewer both
   see the truth.
4. Paging: page forward from a cursor in every mode (see Task 3 step 3 for the dedup one).
   Deep offsets are capped - LOOM_SEARCH_MAX_OFFSET defaults to 400.
```

**References:** [../workflows/WORKFLOWS.md](../workflows/WORKFLOWS.md) §4 (X5, X6, X7) and §2.2 ·
[../workflows/WORKFLOW_MANUAL_SORT.md](../workflows/WORKFLOW_MANUAL_SORT.md) §2.1
**Test Requirements:** `keyProfilePersistence.test.ts` (save, load, corrupt value, unknown action).
A mocked Playwright spec asserting a rebind survives a reload, and one asserting the objects mode
issues `GET /detections?status=PENDING` rather than `GET /assets`. Endpoint tests for the two new
asset filters incl. paging and a permission case. ⚠️ Playwright `role`+`name` is a substring match —
use `exact: true` for any new toggle. `cd loom-ui && ./node_modules/.bin/vitest run` and
`./node_modules/.bin/playwright test e2e/workflow-*`.

---

## Task 9: AI output review — the review record, and a decision that outlives the session

**Argumentation Summary:** Half of this landed. `LLMMode` (`WorkflowView.tsx:768`) is no longer a
mock: it renders the asset's real `vlm` JSON components fetched at `:1190-1193`, chips the actual
`producerVersion` and prompt `variant`, and distinguishes "the node has not run here" from "the model
returned nothing". What it still cannot do is **record an answer**: approve/reject write
`llmDecisions` React state (`:1471-1472`) and nothing else, there is no EDIT action — the third and
most valuable verdict — and the `r` "Re-run Prompt" binding declared at `:246` dispatches to
`case "rerun_llm": e.preventDefault(); break;` (`:1510`), a bound key that does nothing. Server-side
there is no `node_result_review` table (zero hits outside spec prose), no cross-asset queue route
(`NodeResultEndpointService.java:80-86` loads by asset with no status filter and no paging), and
`loom-ui/src/api/` has no node-results client at all. So seven node kinds still write free text no
consumer distinguishes as checked or unchecked: search ranks a hallucinated caption like a curated
one, exports carry both, the agent cites both.

**Improvement Summary:** A review record hung off the `asset_node_result` ledger — one mechanism
serving this workflow, metadata repair and safety triage — plus the write path in the screen that
already shows the real text.

```
PREREQUISITE: Task 18. `result_ref` is now populated by ~20 nodes, but `origin` is the
constant COMPUTED (AbstractMediaNode.java:149) and a ledger row cannot name its run, so a
review keyed to a row still cannot say which execution produced the text.

1. node_result_review table (WORKFLOW_AI_REVIEW.md section 2.1) on the EXISTING
   review_status enum - do not create a second one; V2.81 renamed cluster_status to
   review_status precisely so it could be shared (see
   loom/db/api/src/main/java/io/metaloom/loom/db/model/review/ReviewStatus.java).
   Columns: result_uuid FK CASCADE, status, corrected_text, note, reviewed_at,
   reviewer_uuid NOT NULL, UNIQUE (result_uuid, reviewer_uuid). Take the next free
   migration version (V2.95 is the highest at 8c153347). Hanging the review off the ledger
   scopes it automatically to the producer_version reviewed - a re-run under a new version
   is correctly unreviewed again.
2. DAO + jOOQ impl + memory impl + contract tests in loom/db/api-test + a delete-cascade
   test. ⚠️ JooqLoomPermission and the jOOQ table registry files are hand-written; a new
   permission also needs its own seed migration.
3. REST: POST /assets/:uuid/node-results/:uuid/review, a bulk variant, and a cross-asset
   GET /node-results?status=PENDING queue (Task 8 step 2 consumes it). DECIDE whether this
   needs its own permission or reuses UPDATE_ASSET - say which and why in PERMISSIONS.md.
4. loom-ui: new loom-ui/src/api/nodeResults.ts (there is none) and
   loom-ui/src/features/workflow/llmReview.ts for the pure logic, mirroring
   dedupGroups.ts. Then in WorkflowView.tsx: make handleApproveLlm/handleRejectLlm
   (:1471-1472) write optimistically with rollback and a toast, add an EDIT action that
   posts corrected_text, and either implement `rerun_llm` (:1510) or REMOVE its binding at
   :246 - a bound key that does nothing is worse than an unbound one.
5. Consumers (separate, larger): exclude REJECTED text from search_document, from export,
   and from the agent's context. This touches the V2.57-V2.59 trigger set.
```

**References:** [../workflows/WORKFLOW_AI_REVIEW.md](../workflows/WORKFLOW_AI_REVIEW.md) §2.1, §2.2 ·
`V2.45__add_asset_node_result.sql`, `V2.81__detection_review_state.sql` (the shared enum) ·
[METALOOM_NOTES.md](METALOOM_NOTES.md) "Complete the node provenance record" · Task 18
**Test Requirements:** `NodeResultReviewDaoTest`, `NodeResultReviewEndpointTest` (incl. a re-run
under a new `producer_version` leaving the old review in place and the new value PENDING),
`SearchDocumentReviewTest`, `llmReview.test.ts` and `workflow-llm-mocked.spec.ts` (approve → reload →
still approved; a 500 rolls the chip back with a toast; `r` either does something observable or is
absent from the bindings list). ⚠️ The jOOQ test DB is pre-populated — assert relative to your own
fixtures, never absolute counts.

---

## Task 10: Collection curation mode

**Argumentation Summary:** Every backend piece exists — collection schema with cascades since `V2.80`,
full CRUD, permissions, and bulk membership routes (`CollectionEndpoint.java:94` POST
`/collections/:uuid/assets`, `:102` PUT, `:110` DELETE `.../assets/:assetUuid`) — and curating still
means clicking through the assets grid one at a time. The UI does not reach any of it:
`loom-ui/src/api/collections.ts` has list/load/create/update/delete and **no** `/assets` call
anywhere in `loom-ui/src`, and `WorkflowMode` (`WorkflowView.tsx:120`) has no `"curation"` member.
This is the cheapest workflow in the family: no migration, no node, no new permission.

**Improvement Summary:** A `"curation"` mode with in/out/skip keys and a live filmstrip of the target
collection.

```
1. loom-ui/src/api/collections.ts: add addAssetsToCollection, replaceCollectionAssets and
   removeAssetFromCollection against the three routes above. The bulk POST has
   partial-success semantics (CollectionEndpointService.java:122) - surface the failed
   ids, do not treat a 200 as "all of them landed".
2. loom-ui/src/features/workflow/curation.ts: the pure logic (request shaping, batching,
   optimistic add/remove with rollback), in the shape of dedupGroups.ts.
3. WorkflowView.tsx: extend WorkflowMode (:120) with "curation", add a curation KeyProfile
   to DEFAULT_PROFILES (:159) - y/-> add, n/Space skip, x remove, <- back (with undo),
   Enter switch collection - a mode toggle next to the five at :1547, and a CurationMode
   pane: the asset large, the target collection's contents as a filmstrip. The filmstrip
   is the thing a checkbox grid cannot give you.
4. Queue sources: search result, library listing, another collection, similar-assets.
   LOOM_SEARCH_ENABLED defaults to OFF and those routes answer 503 - degrade to the
   library listing with a visible message, never a blank screen. Search is a capability,
   not a dependency, everywhere else in this codebase.
5. Re-adding must be an idempotent no-op (a curator will double-tap y); undo on <- must
   hit the server, not just move the cursor.
```

**References:**
[../workflows/WORKFLOW_COLLECTION_CURATION.md](../workflows/WORKFLOW_COLLECTION_CURATION.md) ·
[../loom/ui/TASK_UI_ORGANIZATION.md](../loom/ui/TASK_UI_ORGANIZATION.md)
**Test Requirements:** `curation.test.ts` (request shaping, batching, rollback, partial success) and
`workflow-curation-mocked.spec.ts` (`y` grows the filmstrip, `n` sends nothing, back-then-`x` undoes
server-side, a 503 from search falls back with a message). ⚠️ Playwright `role`+`name` is a substring
match — a new "Curation" toggle can shadow an existing workflow spec; use `exact: true`.

---

## Task 11: Metadata repair — rules, candidates, corrections

**Argumentation Summary:** The `metadata` node extracts EXIF/GPS/IPTC/XMP and resolves conflicts by
precedence, and the losing candidates — often the correct ones — are **discarded at the point of
resolution**: `MetadataMapper.pick` / `pickAll`
(`cortex/nodes/metadata/core/src/main/java/io/metaloom/cortex/node/metadata/MetadataMapper.java:369-391`)
return on the first non-null key and record only the winner in the `provenance` map. A disagreeing
XMP-vs-EXIF value survives only by accident, when the opt-in capped `raw` blob
(`AssetMetadata.java:236-264`, `MetadataMapper.copyRaw:295-310`) happens to include the key. Nothing
detects that a resolved value is implausible: there is no `metadata-issues` route, no rule code and
no `asset_metadata_correction` table anywhere in the tree. A real archive arrives full of missing
capture dates, `1970-01-01`, Null Island coordinates and absent rights, and there is no way to find
them, let alone fix them in bulk.

**Improvement Summary:** A rule set that flags implausible metadata, candidate preservation in the
envelope, and a bulk-correction layer that never overwrites what the file said.

```
1. Rule set behind a strategy seam (copy FilterStrategy's shape in cortex/nodes/filter):
   NO_CAPTURE_DATE, IMPLAUSIBLE_DATE, NO_TIMEZONE, NULL_ISLAND, SOURCE_CONFLICT,
   NO_RIGHTS. Adding a rule must not require editing the endpoint. Every input is already
   in Postgres, so this is SQL behind a route, not a node.
2. GET /api/v1/assets/metadata-issues?rule=&severity= with keyset paging.
3. Preserve losing candidates: MetadataMapper.pick/pickAll must record every key that
   carried a value, not only the winner, into a `candidates` structure in the
   asset_json_comp envelope (WORKFLOW_METADATA_REPAIR.md section 2.2). UPDATE
   ../features/nodes/metadata/METADATA_OVERVIEW.md in the same change - it is the only
   place the envelope contract and the precedence rules are written down.
4. Correction store: reuse node_result_review from Task 9 if it has landed, otherwise a
   small asset_metadata_correction table. DECIDE BEFORE BUILDING EITHER - two "a human
   overruled a machine value" mechanisms is the outcome to avoid.
5. Correction endpoint: single AND bulk-over-a-query, with a preview and an undo. Bulk is
   the point ("this whole folder is off by seven hours"); a bulk correction applied to a
   mis-scoped query is the most damaging action in this spec family.
6. Consumers honour the correction layer: search, API responses, export.
7. loom-ui: this needs a screen, and the audit found none - no "metadata" WorkflowMode and
   no issues view. Add a mode to WorkflowView.tsx:120 (or a dedicated route under
   loom-ui/src/features/) driven by the issues route, with the preview-then-apply flow
   from step 5 and an explicit count of what a bulk correction will touch.
```

**References:**
[../workflows/WORKFLOW_METADATA_REPAIR.md](../workflows/WORKFLOW_METADATA_REPAIR.md) ·
[../features/nodes/metadata/METADATA_OVERVIEW.md](../features/nodes/metadata/METADATA_OVERVIEW.md) ·
[../concept/ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md) (the deferred file half)
**Test Requirements:** `MetadataRuleTest` per rule with boundary fixtures, `MetadataIssueEndpointTest`,
`MetadataCorrectionEndpointTest` (correction wins over the machine value; undo restores),
`MetadataEnvelopeCandidatesTest` (candidates preserved, winner still matches documented precedence),
and a mocked Playwright spec for the preview-then-apply flow. ⚠️ **Generate fixture bytes in test
code** — `/opt/metaloom/loom-testdata` is unversioned and its images carry no EXIF. ⚠️ Read EXIF with
`metadata-extractor` directly; Tika's `ImageMetadataExtractor` flattens and re-prioritises the blocks.

---

## Task 12: Safety triage — decision, consequence, and restricted-by-default

**Argumentation Summary:** `GuardNode`
(`cortex/nodes/guard/core/src/main/java/io/metaloom/cortex/node/guard/GuardNode.java`) produces a
normalised verdict across three model families (`score` is always P(unsafe)), emits five ports and
persists one `guard` JSON component plus a ledger row (`:259-282`) — and that is where it stops.
There is **no consumer at all**: grep for `guard` across `loom/**/*.java` returns nothing, no review
table exists, no quarantine graph connects the `safe` port to the `move` node that now exists, and
`WorkflowMode` has no triage member. Guardrail models are known to over-flag medical images, art,
historical photographs and news footage, so a threshold alone cannot resolve this — which is
precisely what a review queue is for. `ReviewStatus`'s javadoc already reserves the shared enum for
this verdict.

**Improvement Summary:** A review record with category correction, a quarantine path reusing the
`move` node, and — the hard part — restricted-by-default visibility for flagged assets.

```
Depends on Task 4 (the mover, now built) and Task 1's FilterBy.TAG (built). The one
undecided thing is the enforcement point, which Task 14 needs too - decide it ONCE.

1. Review record on the EXISTING review_status enum (V2.81), against the ledger row, plus
   a category correction ("this is violence, not sexual content"). Retain OVERTURNED
   verdicts - the false-positive rate is the only honest basis for changing a threshold.
   If Task 9's node_result_review has landed, extend it rather than adding a second table.
2. PENDING means RESTRICTED. Fail closed: an unreviewed flag must never be treated as safe
   because nobody got to it.
3. DECIDE the enforcement point and write it into
   spec/features/permissions/PERMISSIONS.md. Three options in
   WORKFLOW_SAFETY_TRIAGE.md section 3.3; the recommendation is moving flagged assets to a
   separate library with its own ACL, because it composes with the permission model that
   exists instead of adding the row-level authorization this codebase does not have.
   A half-enforced restriction is worse than none - it reads as protection.
4. Quarantine path as a real pipeline: guard -> filter(TAG:quarantine) -> move(FOLDER).
   Moves, never deletes: retention obligations frequently point the opposite way from a
   deletion instinct. Document it as a playbook under website/content/english/docs/playbooks/.
5. loom-ui: a triage mode in WorkflowView.tsx (mode union at :120, a KeyProfile at :159,
   a mode toggle at :1547) that blurs by default, requires a deliberate reveal, is gated on
   an explicit permission, and shows NO flagged thumbnail anywhere else in the UI - the
   asset grid, search results and the chat context all need the same filter. This is the
   only review screen that is itself a hazard to the reviewer.
```

**References:**
[../workflows/WORKFLOW_SAFETY_TRIAGE.md](../workflows/WORKFLOW_SAFETY_TRIAGE.md) §3.1-§3.3 ·
[../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md) ·
`cortex/nodes/guard/` · `loom/db/api/.../model/review/ReviewStatus.java`
**Test Requirements:** `SafetyReviewEndpointTest`, and above all `RestrictedVisibilityTest` — a
PENDING asset must not appear in asset lists, search results, thumbnails or the agent's context for a
user without the triage permission, so that a new read path added without the filter fails the suite.
⚠️ **Never ship offensive fixtures**: synthesise a verdict row against an ordinary test asset.

---

## Task 13: Ingest reconciliation

**Argumentation Summary:** Four source kinds, differential scanning, hashing, consistency checking,
fingerprinting, dedup and an S3 sink all exist, and nothing composes them into a migration — in
particular nothing answers *"did everything arrive?"*. There is no survey mode (grep `survey` across
`cortex/**/*.java`: zero hits; no `countOnly`/`dryRun` on any source node) and no disposition
reporting (`disposition`, `SIZE_MISMATCH`: zero hits in production code). The differential index the
scanners maintain is worker-local, so replacing a worker loses it. Worse,
`CORTEX_S3_MAX_OBJECT_SIZE` (`cortex/common/.../CortexEnvOptions.java:103`, enforced in
`S3MediaMaterializer.java:85-87` and `S3MediaReferenceResolver.java:67-70`) **silently excludes**
oversize objects with no record — the exact shape of the failure this workflow exists to prevent.
This is the one workflow whose failure mode is silent data loss.

**Improvement Summary:** A survey phase and a reconciliation phase that account for every source
object with a named disposition.

```
BLOCKED ON Task 17. Do not trust a real migration while 16 call sites report SUCCESS on a
failure. (The two specific hazards named in the old version of this task are FIXED:
HashDedupNode's System.in.read() halt is gone - HashDedupNode.java:132-140 now logs and
returns ctx.skipped(...) - and pipeline_run_item has carried per-item state since V2.31,
normalised by V2.77.)

1. Survey mode: scan and report (count, bytes, types, depth) without ingesting. A
   count-and-stop option on each source node, not a separate node.
2. Push the source inventory - or a summary of it - into Loom, so reconciliation survives
   a worker replacement.
3. Reconcile: for every source object emit MATCHED | MISSING | SIZE_MISMATCH | UNREADABLE
   | SKIPPED_BY_FILTER | DUPLICATE_OF. "Filtered by a MIME bucket" and "the read failed"
   must not look alike, and a CORTEX_S3_MAX_OBJECT_SIZE exclusion must report as
   SKIPPED_BY_FILTER rather than vanishing - today it throws inside the materializer and
   nothing records the object at all.
4. Content-identity fallback where key identity is unreliable: a rename is detectable on a
   cloud drive (stable file id) but NOT on S3, where it is a delete plus a create.
5. Resumable per-item progress surfaced in the UI, on pipeline_run_item. loom-ui already
   renders run items (loom-ui/e2e/pipeline-run-items-mocked.spec.ts) - extend that view
   with the disposition rather than building a second progress screen.
```

**References:**
[../workflows/WORKFLOW_INGEST_MIGRATION.md](../workflows/WORKFLOW_INGEST_MIGRATION.md) §1, §3 ·
[NODE_S3SOURCE.md](../features/nodes/s3-source/NODE_S3SOURCE.md) ·
[NODE_CLOUDSOURCE.md](../features/nodes/cloud-source/NODE_CLOUDSOURCE.md) ·
[../concept/CLUSTERING.md](../concept/CLUSTERING.md) (🔴 scale Cortex, never Loom) · Task 17
**Test Requirements:** `ReconciliationTest` covering every disposition incl. the S3-rename case
resolved by content identity and the oversize-object exclusion; `ResumeTest` (kill mid-run, the
second run completes the remainder and does not reprocess); `MigrationIT` in `integration-test/` over
a few hundred synthetic objects asserting zero unexplained gaps. Plus a documented dry run at 1% of
the real corpus — not a test, but the thing that actually de-risks it.

---

## Task 14: Rights and release — licence model and the export gate

**Argumentation Summary:** There is no concept of "cleared for use" anywhere in the tree, and two of
the five questions a release gate must answer are literally unrepresentable: there is no
`person_consent` table and no consent field on `person`, and the only licence storage is a free-text
`asset_location.license` column that `V2.10__add_asset_location.sql:12` still marks `/* unclear */`
— hung off the *location*, not the asset. SPDX support exists but is worker-side and transient
(`cortex/nodes/metadata/core/.../LicenseResolver.java`, `RightsBlock.java:10` `licenseId`); nothing
persists it. And there is no gate on any exit: `GET /assets/:uuid/binary/data`
(`AssetEndpoint.java:716-721` → `AssetBinaryEndpointService.downloadByAssetUuid:297-347`) checks
`READ_ASSET_BINARY` and streams, and `S3SinkNode.publish` checks only presence and
`maxArtifactBytes`. Meanwhile the evidence for the other three questions — ownership, safety, AI
provenance — is already produced and simply never read as clearance.

**Improvement Summary:** Start with the two independently valuable pieces (a licence model, an
AI-provenance read path), then the clearance record and the export gate. Consent waits for the face
workflow.

```
Strictly sequential. Steps 1-4 are worth building alone; 5-7 must not start early.

1. Licence model: an SPDX-style identifier plus optional custom terms, on the ASSET
   (a licence follows content, not paths), replacing asset_location.license. Feed it from
   the metadata node's existing LicenseResolver rather than inventing a second resolver.
   Small change, large blast radius (API responses, search, export) - its own migration,
   its own tests, and the Java + Python clients regenerated.
2. AI-provenance read path: "is any of this machine-made?" as a query over
   asset_node_result. DISTINGUISH generated PIXELS (imagegen, videogen) from generated
   DESCRIPTION (captioning, llm, translate, vlm). A VLM caption does not make an image
   algorithmic media, and mislabelling an archive is worse than not labelling it.
3. release_clearance (WORKFLOW_RIGHTS_RELEASE.md section 2.1): UNIQUE (asset_uuid,
   purpose), status on the shared review_status enum, expires_at, decider_uuid NOT NULL,
   and an EVIDENCE SNAPSHOT frozen at decision time. A clearance that silently re-derives
   itself is not a record of anything.
4. The export gate on EVERY byte-carrying exit: GET /assets/:uuid/binary/data, the s3-sink
   node, collection download. One ungated route makes the gate decorative. Reuse whatever
   enforcement point Task 12 picks - two half-enforced gates are worse than one enforced.
5. person_consent - only AFTER WORKFLOW_FACE reaches stage 4. Fail closed on unknowns: an
   unrecognised face is not an absent person.
6. Metadata write-back on export (licence, credit, IPTC DigitalSourceType, C2PA) - after
   spec/concept/ASSET_METADATA_WRITE.md is built.
7. Auto-clear policies - LAST, per purpose, opt-in, OFF by default, and recorded as the
   operator who enabled the policy rather than a null or a service account.
8. loom-ui, from step 1 onward: the licence is a new asset field
   (loom-ui/src/features/assetDetail/AssetDetail.tsx and the assets API types), and step 3 needs
   a clearance panel plus a visible "blocked by clearance" state on any download control -
   a 403 with no explanation reads as a bug.
```

**References:**
[../workflows/WORKFLOW_RIGHTS_RELEASE.md](../workflows/WORKFLOW_RIGHTS_RELEASE.md) §2.1 ·
[../concept/ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md) ·
[../features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md) ·
[../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) · [WORKFLOW_FACE_TASKS.md](WORKFLOW_FACE_TASKS.md)
**Test Requirements:** `ReleaseClearanceDaoTest` (one row per (asset, purpose); evidence snapshot
immutable; expiry; cascade), `ProvenanceClassificationTest` (a VLM caption does not mark the image
algorithmic), `ClearanceExpiryTest`, and above all `ExportGateTest` — every byte-carrying route
refuses an uncleared asset, so that a new exit added without a gate check fails the suite.

---

## Task 15: Finish the workflow documentation and guard the demo seeding

**Argumentation Summary:** X10 is now partly closed, not open:
`website/content/english/docs/ui/index.adoc` documents `=== Rating and Tagging at Keyboard Speed`
(`:405`), `=== Reviewing Duplicates` (`:438`), `=== Reviewing What a Model Found` (`:486`, the object
queue) and `=== Face Detection` / `Reviewing a group of faces` (`:332`, `:339`), and `move`/`assign`
have node pages. What is missing: **the LLM review mode has no page at all** although it is shipped
and sidebar-visible, `find website/content -ipath "*workflow*"` returns nothing so there is no page
that explains the six modes as one screen, and the demo seeding that makes those pages true has no
test — `DemoDatabaseInitializer` seeds ratings (`:664-666`), tags (`:295-307`), a PENDING dedup group
(`:1355`), a PENDING face cluster (`:1175`) and detections in all three review states (`:862-904`),
and `BootstrapInitializer` swallows any failure, so the day one of those seeders throws every demo
screen silently goes empty and the docs describe something the reader cannot see.

**Improvement Summary:** Document the one shipped mode that has no page, tie the modes together, and
make the demo seeding fail loudly.

```
1. website/content/english/docs/ui/index.adoc: a section for the LLM/AI-output review mode
   next to the existing four. Customer tone - no spec references, no class names. Document
   the keyboard bindings; they are the feature. ⚠️ Write it against what the mode DOES
   today (it displays real vlm output) and do NOT document approve/reject until Task 9
   persists them - a page describing a feature that silently discards the user's work is
   worse than no page.
2. Add a short "Review Workflows" overview - either a new
   website/content/english/docs/workflows/_index.adoc or an intro block above
   `=== Rating and Tagging at Keyboard Speed` - explaining that the six modes are one
   screen with one queue idea, and linking the four existing sections. Nothing in the
   customer docs currently says that.
3. A test for the demo seeding path: assert against the pooled DB that after
   DemoDatabaseInitializer runs there is at least one rated asset, one curated tag, one
   PENDING dedup group, one PENDING detection and one PENDING cluster. Today the only
   verification is booting the container by hand.
4. ⚠️ Website build: back up website/themes/meghna-hugo/yarn.lock before ./build.sh, and
   escape bare localhost URLs in prose or the build fails.
```

**References:** [../guidelines/CODING.md](../guidelines/CODING.md) (customer docs are part of done) ·
[../website/WEBSITE.md](../website/WEBSITE.md) · [../workflows/WORKFLOWS.md](../workflows/WORKFLOWS.md) §4 (X10) ·
`loom/core/src/main/java/io/metaloom/loom/core/boot/DemoDatabaseInitializer.java`
**Test Requirements:** The new demo-seeding test (step 3). `./build.sh` in `website/` succeeds and
the new sections render. Manual: `./start-demo.sh`, then `/workflow` shows non-empty content in the
rating, tagging, dedup, faces, objects and llm modes.

---

## Task 16: Re-baseline the workflow specs against the code

**Argumentation Summary:** The spec family now disagrees with the code in ways that actively mislead.
`WORKFLOWS.md` §4 (`:177-188`) still lists all ten cross-cutting defects as open, when X1, X2, X3,
X4, X8 and X9 are closed, and its line references no longer resolve (`FacedetectNode.java:510`,
`WorkflowView.tsx:780`, `ratingPersistence.ts:17` — the real ones are `:778`, `:1093`, `:19`); its
§1 catalogue still calls dedup review a mock and manual sorting's tagging broken. `METALOOM_CONTEXT.md`
carries the same stale rows (`move` "does not exist"; `FilterBy` "has no TAG/RATING strategy" — both
shipped) alongside correct ones. `WORKFLOW_TRASH.md` §6 still has "Customer docs" unchecked although
`website/content/english/docs/nodes/move/` and `.../assign/` exist. An agent routed by these files
starts by rebuilding something that is already there —
[../guidelines/CODING.md](../guidelines/CODING.md) requires the spec to move with the code, and this
is the arrears.

**Improvement Summary:** One sweep that makes the router files true again, with the evidence recorded
so the next audit is a diff rather than a re-derivation.

```
1. spec/workflows/WORKFLOWS.md section 4: mark X1, X2, X3, X4, X8, X9 closed with the
   migration or commit that closed them (X1/X2 in the workflow view + detections.ts;
   X3 = V2.81; X4 = V2.79 + V2.88; X8 = V2.78 RATING reaction type; X9 = the machine-tag
   chip in WorkflowView.tsx:282-283). Keep X5, X6, X7 open and re-point their line
   references at WorkflowView.tsx:972 and :979. Keep X10 as PARTIAL and list precisely
   which modes now have a page (see Task 15).
2. Same file, section 1 catalogue: dedup review is no longer a mock; manual sorting's
   tagging persists; the trash workflow's `move` node is built.
3. spec/METALOOM_CONTEXT.md "Where do I find ...?" rows and the Progress Assessment:
   correct the `move`-does-not-exist and FilterBy-has-no-TAG/RATING claims, and the
   "of six modes exactly one writes to the server" line - three do.
4. spec/workflows/WORKFLOW_TRASH.md section 6: tick customer docs; leave the demo pipeline,
   per-node E2E and remaining node tests unticked (Task 4 owns them).
5. Update each touched file's footer to the current HEAD and date, per
   ../guidelines/SPEC_RULES.md. Do NOT restate the evidence in more than one file - link.
```

**References:** [../workflows/WORKFLOWS.md](../workflows/WORKFLOWS.md) §1, §4 ·
[../METALOOM_CONTEXT.md](../METALOOM_CONTEXT.md) · [../workflows/WORKFLOW_TRASH.md](../workflows/WORKFLOW_TRASH.md) §6 ·
[../guidelines/SPEC_RULES.md](../guidelines/SPEC_RULES.md)
**Test Requirements:** No code tests. Verification is mechanical: every file path, class name and
line number cited in the edited sections must resolve at the recorded HEAD. ⚠️ `METRICS.md` is parsed
by `MetricsCatalogScrapeTest` at runtime — if this sweep touches it, run
`mvn -pl loom/core test -Dtest=MetricsCatalogScrapeTest`.

---

## Task 17: Stop dropping failures — 16 `ctx.failure(...).next()` call sites report SUCCESS

**Argumentation Summary:** `ctx.failure(cause).next()` returns SUCCESS and the message is discarded;
only `.abort()` reads `failureCause`. Sixteen call sites across fourteen production node classes
still do it: `FacedetectNode.java:383`, `TtsNode.java:164`, `FingerprintDedupNode.java:100` and
`:181`, `TikaNode.java:91`, `SceneLayoutNode.java:218`, `QualityNode.java:167` and `:218`,
`SentimentNode.java:152`, `ImageGenNode.java:150`, `WhisperNode.java:151`, `DepthmapNode.java:184`,
`ThumbnailNode.java:135`, `VideoGenNode.java:154`, `FingerprintNode.java:162`. Every workflow in this
family inherits the consequence: a run whose thumbnail, transcript or fingerprint silently failed is
indistinguishable from one that worked, so a review queue never fills, a dedup proposal never
appears, and on an ingest path it is undetected data loss. `DominantColorNode.java:183` was converted
and its comment explains the anti-pattern — that is the worked example.

**Improvement Summary:** Convert every site to the outcome it actually means, and add a guard so the
seventeenth cannot be written.

```
1. For each of the 16 sites decide, per case, which of three it is and convert:
     - a real error the operator must see  -> ctx.failure(cause).abort()
     - this item is not applicable/absent  -> ctx.skipped(reason).next()
     - degraded but the pipeline continues -> keep .next() but record the reason on the
       ledger row so it is visible after the fact
   Follow DominantColorNode (cortex/nodes/dominant-color, see the comment at :183).
   Do NOT do a blanket sed - "the model returned nothing" and "the file is unreadable"
   are different outcomes, which is the whole point.
2. Each converted node gets a test asserting the new terminal state (a failing dependency
   produces ABORTED or SKIPPED, and the cause string survives).
3. Add a guard so this cannot regress: either an ArchUnit-style test in cortex/api or
   cortex/common that fails on the `failure(...).next()` call shape, or - if that is
   impractical for the current test setup - a targeted grep check in the build. State
   which was chosen and why in cortex's node guidelines.
4. While in the area: delete the dead `// System.in.read();` and the
   `// TODO Auto-generated catch block` at
   cortex/nodes/facedetect/core/.../video/VideoFaceScanner.java:301-303.
```

**References:** [../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) ·
[../features/pipeline/PIPELINE_FLOW.md](../features/pipeline/PIPELINE_FLOW.md) ·
[../workflows/WORKFLOW_INGEST_MIGRATION.md](../workflows/WORKFLOW_INGEST_MIGRATION.md) (Task 13 is
blocked on this) · `cortex/nodes/dominant-color/` (the converted reference)
**Test Requirements:** One state-assertion test per converted node, the anti-regression guard from
step 3, and the full cortex suite green. `mvn -pl cortex -am test`. ⚠️ `cortex/**` modules share one
JVM per module, so per-class peak RSS in the reports is really the module's number — do not read a
memory spike as a regression in the class it is attributed to.

---

## Task 18: Complete the ledger's provenance — real `origin`, and a row that knows its run

**Argumentation Summary:** `asset_node_result` is now written by ~20 nodes with a real `result_ref`
(`AbstractMediaNode.resultRef(...)`, `cortex/common/.../node/AbstractMediaNode.java:163`), and two
fields still make it unusable as a provenance record. `origin` is a **constant**:
`AbstractMediaNode.java:149` writes `ResultOrigin.COMPUTED` unconditionally, even though nodes
compute a genuine `LOCAL` vs `COMPUTED` distinction for their own skip decision (e.g.
`MetadataNode.java:224` vs `:245`) — that per-run answer never reaches the ledger. And a ledger row
cannot name its execution: `NodeResultCreateRequest` carries only
`nodeKind, nodeId, producerVersion, state, origin, reason, durationMs, resultRef`, so although
`run_uuid` / `task_uuid` columns exist and the ad-hoc writer sets them
(`AdhocNodeResultWriter.java:110`), a cortex node's row can never be joined to the run that produced
it. Consequence: faulty data cannot be traced to a worker build, and a review keyed to a ledger row
(Task 9) cannot say which execution it reviewed.

**Improvement Summary:** Carry the node's real origin and its run/task identity across the REST
boundary, so the ledger answers "which execution, on which worker, produced this value".

```
1. loom-shared/rest-model/.../noderesult/NodeResultCreateRequest.java: add runUuid and
   taskUuid (both optional - the ad-hoc and CLI paths have no run). Persist them in
   loom/services/rest/.../impl/NodeResultEndpointService.java:66-68, into the columns that
   already exist.
2. cortex/common/.../node/AbstractMediaNode.java:149: send the origin the node actually
   determined for this item instead of the COMPUTED constant, and populate runUuid/taskUuid
   from the NodeContext. Keep COMPUTED as the fallback when a node offers nothing.
3. Also record which worker build wrote the row - cortex_instance exists as a table and is
   never joined. DECIDE between an FK and a denormalised instance name+version, and write
   the decision into ../loom/DOMAIN.md. ⚠️ result_ref is advisory by declaration
   (DB_SCHEMA_FEEDBACK.md:469) - do not build integrity checks on it while you are here.
4. Java and Python clients regenerated; the Python parity test guards the drift.
5. No loom-ui change is required for this task, but note that Task 9 step 4 adds the first
   loom-ui client for these rows - keep the response shape stable across both.
```

**References:** [METALOOM_NOTES.md](METALOOM_NOTES.md) "Complete the node provenance record" ·
`V2.45__add_asset_node_result.sql` · [../features/pipeline/PIPELINE_FLOW.md](../features/pipeline/PIPELINE_FLOW.md) ·
[../features/db/DB_SCHEMA_FEEDBACK.md](../features/db/DB_SCHEMA_FEEDBACK.md) · Task 9
**Test Requirements:** `NodeResultEndpointTest` extended (runUuid/taskUuid round-trip; a request
omitting them still succeeds), a cortex node test asserting a LOCAL-origin item records `LOCAL` and a
recomputed one records `COMPUTED`, a delete-cascade test for the run link, and the Python parity
test. ⚠️ Changing an endpoint constructor requires a clean rebuild of `loom/core` or `./setup-pool.sh`
fails with `NoSuchMethodError`.

---

_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (code audit)_
