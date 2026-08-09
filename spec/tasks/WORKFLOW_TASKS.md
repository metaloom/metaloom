# Workflows — Task List

> Work items for the twelve workflows under `spec/workflows/`, derived from a code audit on
> 2026-08-07 at `21e8a8cd`.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../workflows/WORKFLOWS.md](../workflows/WORKFLOWS.md) is the family index and carries
> the shared anatomy (§3) and the cross-cutting defect table (§4) these tasks reference as `X1`-`X10`.
>
> **Ordering.** **W1, W2, W3 and W4 are done** (2026-08-08). W1 was the keystone — it unblocked W4 and
> every "act on a human decision" requirement in the family, and `FilterBy.RATING`/`TAG` are the seam
> the rest of them extend. W3 is the worked example the other review modes should copy for a write
> path; W2 is the worked example for an optimistic one. W6 and W7 are independent and can run in
> parallel. W5 gates the object-detection workflow. W8 is small and improves five modes at once.
> W9-W14 are proposals whose own spec files carry their build order; the tasks here are the entry
> points, not the full plans.

---

## Task 1: Add `TAG` and `RATING` strategies to `FilterBy` — ✅ DONE (2026-08-08)

**Argumentation Summary:** Nothing in a pipeline could read a human decision. `FilterBy` offered
`LANGUAGE`, `MIME`, `SIZE` and `DATE` — all derived from the item itself. A reviewer could rate an
asset 1 and tag it `trash`, and no node, no filter and no trigger would ever act on it. That is why
[../workflows/WORKFLOW_MANUAL_SORT.md](../workflows/WORKFLOW_MANUAL_SORT.md) felt pointless and why
[../workflows/WORKFLOW_TRASH.md](../workflows/WORKFLOW_TRASH.md) had no input.

**What was built:**

1. 🟢 **The strategy seam was widened first, behaviour-free.** `classify` took a `NodeContext` and
   nothing else, so it had no asset — while `FilterNode.compute` was already holding the
   `AssetResponse` and dropping it. New record `FilterItem(ctx, asset, reactions, reactionsAvailable,
   text)`; `classify(FilterItem, FilterNodeOptions, List<FilterBucket>)`. The alternative — injecting
   a `LoomClient` into the strategy — was rejected: a strategy has no asset identity, so it would
   re-derive the SHA-512 and load the asset a *second* time per item, and it breaks the codebase's
   own idiom of keeping every Loom call in the node (`TagNode`). The four existing strategies changed
   mechanically and **no existing test changed** — nothing calls `classify` directly.
2. 🟢 **`TagFilterStrategy`** — hints are names, prefix globs (`person/*`, `*`), negations
   (`!archive`) and `untagged`. A bucket matches when at least one positive hint matches and no
   negation does; a bucket of only negations matches when none apply. `match()` falls back to the
   bucket id, like MIME. Zero round trips: tags ride on the asset the node already loaded.
   Curated-vs-machine is the **`tagSource` node option** (`ANY`/`MANUAL`/`MACHINE`), not a second
   grammar in the match column; a null `node_kind` counts as `MANUAL`, matching V2.71's default.
3. 🟢 **`RatingFilterStrategy`** — `>=8`, `<=2`, `4..7`, a bare `8`, `unrated`. Two deliberate
   divergences from `SizeFilterStrategy`, both in the javadoc: a range is inclusive at **both** ends,
   and a bare value is **exact**. The rule across all three strategies is now *a bare value is exact
   on a discrete domain and a ceiling on a continuous one* — `DateFilterStrategy` already read a bare
   date as that one day. `needsReactions()` opts into one Loom call per item, made by the node and
   memoised by its existing per-run `LocalResultCache`.
4. 🟢 **Three states kept apart**: asset unknown to Loom → `other`; known and unrated → `unrated`;
   reaction fetch **failed** → `other("reactions unavailable")`, pointedly *not* unrated. Collapsing
   the last two would route the whole un-reviewed backlog down a trash branch during a Loom outage.
   Nothing throws — a throw aborts the task, which is disproportionate for one un-ingested file.
5. 🟢 **`tagSource` mixed into `configHash`**, or two nodes differing only in it would share a
   result-cache entry and a `producerVersion`.

⚠️ **Two instructions in the original task were wrong** and are corrected here for the next reader:
there is **no hand-maintained `filterBy` enum** under `loom-shared/node-model/.../spec/` — the
descriptor's `values` array is harvested from `FilterNodeOptions.filterBy`, so adding the constants
regenerates it. And `MimeFilterStrategyTest` does not exist; the file to mirror is
`MimeFilterNodeTest`, which tests through the node.

**References:** [../workflows/WORKFLOW_MANUAL_SORT.md](../workflows/WORKFLOW_MANUAL_SORT.md) §5 ·
[../workflows/WORKFLOW_TRASH.md](../workflows/WORKFLOW_TRASH.md) §1 ·
[../features/pipeline/NODE_DATA_TYPES.md](../features/pipeline/NODE_DATA_TYPES.md) §8.6 ·
`FilterItem.java`, `TagFilterStrategy.java`, `RatingFilterStrategy.java`, `TagSource.java`
**Tests:** `RatingFilterNodeTest` (10 — grammar, and the null-client contract: an unknown asset lands
in `other` and the task still **succeeds**), `RatingFilterNodePersistenceTest` (5 — the mean across
reviewers, the persisted `rating`/`ratingMean`/`ratingCount`/`ratingSource`, and unavailable ≠
unrated), `TagFilterNodeTest` (13), `FilterOptionsValidationTest` (+1). The 62 pre-existing filter
tests are green **unchanged**, which is the proof the seam change carried no behaviour.
`mvn -pl cortex/nodes/filter/core -am test`, then
`mvn -o -pl integration-test test -Dtest=NodeSpecGoldenTest -Dloom.regenerateNodeDescriptors=true`

---

## Task 2: Persist tagging in the workflow view — ✅ DONE (2026-08-08)

**Argumentation Summary:** `TaggingMode` looked like it worked and wrote nothing.
`handleAddTag` / `handleRemoveTag` mutated React state; `tagAsset` (`loom-ui/src/api/tags.ts:150`)
and `untagAsset` (`:164`) already existed, typed and documented, with zero callers in
`features/workflow/`. A reviewer could spend an hour tagging and lose all of it on reload. The
vocabulary offered was `ALL_TAGS`, 24 hardcoded strings.

**What was built:**

1. 🟢 **`loom-ui/src/features/workflow/tagPersistence.ts`** — a pure module mirroring
   `ratingPersistence.ts`: `addAssetTag`, `removeAssetTag`, `loadTagVocabulary`, plus
   `toWorkflowTags` / `isCurated` / `isPending`. It throws, so the caller owns the rollback.
2. 🟢 **The state shape changed from names to objects.** `assetTags` was
   `Record<string, string[]>`, and `untagAsset` needs a uuid. It is now
   `Record<string, WorkflowTag[]>`, built from the **raw** `AssetResponse` rather than from
   `Asset.tags` (which `apiToWorkflowAsset` had already flattened to names). ⚠️ The TS
   `TagReference` in `api/assets.ts` was missing `nodeKind`, `confidence`, `placementUuid`,
   `attached` and `attachedBy` — the server sends them and the type dropped them. Extended first;
   without it nothing can tell a curated tag from a machine one.
3. 🟢 **Optimistic with rollback.** The chip carries a `pending:` placeholder uuid and the rollback
   removes *that* chip — not the last one, not the one with this name: at a keystroke per decision
   several writes are in flight. A removal restores the tag **at its original index**. Every failure
   now raises a toast; the five bare `.catch(() => {})` in the view are gone. `handleRate` got the
   same treatment.
4. 🟢 **Vocabulary from `listTags(token, {limit: 200})`**, `freeSolo` kept.
5. 🟢 **Provenance is visible.** A machine tag (`nodeKind` other than `manual`) renders outlined with
   a tooltip naming the node kind and its confidence, and carries **no delete affordance** — removal
   happens on the asset detail screen. A pending chip has none either, having no uuid yet.
6. 🟢 **Existing decisions are marked** — `initiallyRated` / `initiallyTagged` reflect what each asset
   *arrived* carrying, deliberately not the live state, which would light up the moment a key is
   pressed.

⚠️ **Step 3's "scoped to the collection the queue came from" is not implementable** and was dropped;
see the rewritten D3 in [../workflows/WORKFLOW_MANUAL_SORT.md](../workflows/WORKFLOW_MANUAL_SORT.md)
§2.1. Coined tags go into `DEFAULT_TAG_COLLECTION`, lifted out of `AssetDetail.tsx` into
`api/tags.ts` so both screens share one string.

**References:** [../workflows/WORKFLOW_MANUAL_SORT.md](../workflows/WORKFLOW_MANUAL_SORT.md) §6 ·
[../concept/NODE_TAG_CONCEPT.md](../concept/NODE_TAG_CONCEPT.md) §2 (why `tagAsset` resolves rather
than inserts) · migration `V2.71__tag_asset_placements.sql`
**Tests:** `tagPersistence.test.ts` (9): the POST body and namespace, that the `/tags` CRUD create is
*never* called, a 204 with no body, rejection on non-2xx, vocabulary de-duplication, and the
absent-`nodeKind`-is-curated rule. `workflow-tagging-mocked.spec.ts` (4): tag → reload → still there;
a 500 removes the chip and shows the error; remove issues the DELETE and it stays gone; a machine
chip has no delete affordance. `cd loom-ui && ./node_modules/.bin/vitest run` and
`./node_modules/.bin/playwright test e2e/workflow-*` — ⚠️ `npx` stalls in this sandbox.

---

## Task 3: Replace the dedup review mock with the built API — ✅ DONE (2026-08-08)

**Argumentation Summary:** The dedup backend was complete but the review screen was a mock:
`buildDuplicateGroups(assets)` paired adjacent assets and never called `GET /api/v1/dedup-groups`,
and decisions lived in `dedupDecisions` React state and were never PATCHed. A reviewer could confirm
twenty groups, nothing was written, and `fingerprint-dedup-apply` moved nothing.

**Improvement Summary:** The whole loop is now connected, and the two correctness defects that would
have made the wired UI misleading were fixed first.

**What was built:**

1. 🟢 **Discovery no longer re-proposes a decided candidate set.** Server-side, in
   `DedupGroupEndpointService.createDedupGroup`: an exact member-set match against
   `DedupGroupDao.listDecidedByAssets(members, algorithm)` answers `200` with the decision and writes
   nothing (`201` for a real proposal). `FingerprintDedupNode` reads the status and reports
   `skipped`. Scoped to the *set*, not to its assets, so a new duplicate of a reviewed file still
   reaches the queue.
2. 🟢 **`GET /dedup-groups` is keyset paged** (`?limit=`/`?from=`, default 25) with one globally
   ordered query instead of three concatenated lists. `DedupGroupDao.loadPage` is bespoke, following
   `NotificationDaoImpl` — the generic path casts every sort column to `Field<UUID>` and throws on
   `created`. The endpoint is now in `LoomOpenAPI`, so the two `test_parity.py` waivers are gone.
3. 🟢 **`loom-ui/src/api/dedup.ts`** — the UI's only `PATCH` client — plus
   `features/workflow/dedupGroups.ts` for the pure logic (`keepMember`, `dupMembers`, `isComplete`,
   `formatSize`, `replaceGroup`, `decideGroup`, `reassignKeep`).
4. 🟢 **`DeduplicationMode` is wired**: real `status=PENDING` queue, per-member size / completeness /
   score, `AssetThumbnail` previews (images only — video members show `MediaPlaceholder`, decided
   explicitly), **Keep this one** per candidate, `EmptyState` on an empty queue, and one
   `applyDedupDecision` path that writes optimistically and **rolls back with a toast** on failure.
   Decisions are keyed by group uuid — in fact by `group.status` from the server, not by a local map.
5. 🟢 **Permissions**: four `ui:yes` constants, a `Deduplication` group in `PERMISSION_GROUPS`, the
   four missing `admin.roles.permission.*_DEDUP` strings in **both** locales, and
   `READ_DEDUP`/`UPDATE_DEDUP` on the demo Editor role (`READ_DEDUP` on Viewer).
6. 🟢 **Demo data**: `seedDemoDedupGroup` seeds one PENDING group over two demo videos. Never
   CONFIRMED — the demo container's media is database rows only.
7. 🟢 **Beyond the original scope**, because they block trusting the loop in production: the apply
   node now verifies the KEEP's **content** against its recorded SHA-512 (trusting the stored xattr,
   digesting only when none exists), and `HashDedupNode` logs and skips a size mismatch instead of
   blocking a headless worker on `System.in.read()`.
8. 🟢 **Customer docs**: `docs/ui/index.adoc` §Reviewing Duplicates — the first customer-facing
   workflow page in the tree, which closes X10 for this workflow.

**Still open:** ⚠️ `PATCH keepAssetUuid` does not rewrite `dedup_group_member.role`, so the pointer
and the roles diverge after a reassignment (readers prefer the pointer); per-node E2E in
`integration-test`; discovery options as descriptor parameters; the queue loads one page with no
"load more".

**References:** [../workflows/WORKFLOW_DEDUP.md](../workflows/WORKFLOW_DEDUP.md) ·
[../concept/NODE_DEDUP_PLAN.md](../concept/NODE_DEDUP_PLAN.md) §3.1-§3.3 · `V2.61`, `V2.62`
**Tests:** `DedupGroupEndpointTest` 11→**14**, `DedupGroupDaoTest` 6→**7**, cortex dedup module
11→**27** (new `FingerprintDedupApplyNodeTest`, real `HashDedupNodeTest` bodies), plus
`dedup.test.ts` (12), `dedupGroups.test.ts` (17) and `workflow-dedup-mocked.spec.ts` (6). Python
client suite green (122). ⚠️ `npx` stalls in this sandbox — use `./node_modules/.bin/`.

---

## Task 4: Build the `move` node — ✅ DONE (2026-08-08)

**Delivered as two kinds, not one.** The five destinations in the brief - filesystem, library,
storage, collection, s3 bucket - are not one operation: a collection has no path and no bytes. So
`move` handles bytes (`FOLDER`, `POOL`, `LIBRARY`, `S3_BUCKET`) and `assign` handles membership
(`COLLECTION`, `LIBRARY`), both in `cortex/nodes/relocate`.

What landed beyond the original scope, and why:

- **`cortex/fs`** — the shared move mechanics, in the module that had been an empty shell. It also
  absorbed `AtomicFiles`, which was duplicated verbatim in `watermark` and `image-manipulation`.
- **The Loom REST the mover needed** — collection/library membership routes (the DAO writer existed
  for collections and had one caller, a cascade test; `library_asset` had none), and
  `libraryUuid`/`poolUuid` on `AssetBinaryUpdateRequest`, without which relocating into another
  library or pool was not expressible over REST at all.
- **`V2.80`** — `collection_asset.collection_uuid` had no cascade, so deleting a collection with any
  member was a 500. `V2.73`'s own comment claimed otherwise. Found by a test written for this work.
- **The dedup supersede** — both dedup nodes now report on selective ports and move nothing;
  `dupFolder` is gone. See [../workflows/WORKFLOW_TRASH.md](../workflows/WORKFLOW_TRASH.md) §6a.

⚠️ **Release note required.** `CortexOptionsLoader` ignores unknown YAML keys, so a stale `dupFolder`
in an operator's `cortex.yml` keeps the worker booting and is silently ignored - their duplicates just
stop being moved. There is no code path left to warn from.

**Still open**, tracked here rather than closed silently:

- Demo pipeline (`source → filter(TAG:trash) → move`) and a seeded `Published` collection.
- Customer docs under `website/content/english/docs/nodes/move/` and `.../assign/`, plus the dedup
  page rewrite.
- Per-node E2E: `MoveNodeIT`, `AssignNodeIT`.
- Four of the standard node tests: persistence, pipeline-chain, singleton, and the assertj helpers.

**References:** [../workflows/WORKFLOW_TRASH.md](../workflows/WORKFLOW_TRASH.md) §3 ·
[../guidelines/NEW_NODE.md](../guidelines/NEW_NODE.md) · `cortex/nodes/relocate/` · `cortex/fs/`

---

---

## Task 6: Fix the two detection wiring defects (X1, X2)

**Argumentation Summary:** Two small mismatches make two review modes useless, independently of any
schema work. (X2) The UI's `DetectionResponse` interface (`loom-ui/src/api/detections.ts:4-20`) has
no `label` field, although the Java DTO and the `detection.label` column both do — so
`ObjectDetectionMode` reads `(d.meta)?.label`, always `undefined`, and captions every box with the
literal string `objectdetection`. (X1) `FacedetectNode.persist` writes `setType("face")` while the UI
filters `d.type === "facedetection"` and the `V2.27` column comment names `facedetection` — so the
face panel is always empty.

**Improvement Summary:** Add the missing field to the TypeScript DTOs and settle the detection type
string, in one change so the two do not drift again.

```
1. loom-ui/src/api/detections.ts: add label (and, after Task 5, status and
   correctedLabel) to DetectionResponse, DetectionCreateRequest, DetectionUpdateRequest.
2. WorkflowView.tsx:794: read d.label instead of (d.meta)?.label.
3. Settle the type string. facedetect writes "face"; the schema comment, the UI and
   scene-layout's tests all say "facedetection". Pick "facedetection" (three consumers
   against one producer), change FacedetectNode.java:510, and write a data migration
   for existing rows - an upsert keyed on (asset, node_kind, frame, index) will NOT
   fix them, because node_kind is unchanged and type is not in the key.
4. While there: WORKFLOW_FACE.md gotcha 4 records that facedetect (kind),
   facedetection (options KEY) and "face" (detection.type) are three strings for one
   feature. Leave a comment naming which is which so the next reader does not re-derive
   it.
```

**References:** [../workflows/WORKFLOWS.md](../workflows/WORKFLOWS.md) §4 (X1, X2) ·
[../workflows/WORKFLOW_OBJECT_DETECT.md](../workflows/WORKFLOW_OBJECT_DETECT.md) §1.3 ·
[../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md) §6.1
**Test Requirements:** `workflow-objects-mocked.spec.ts` asserting a mocked detection with
`label: "dog"` renders as `dog`, not `objectdetection`. A `FacedetectNodeTest` case asserting the
persisted type. `SceneLayoutNodePersistenceTest` still green (it constructs `DetectionResponse` with
`setType("face")` and must be updated in the same change).

---

## Task 7: Report the upload trigger outcome, and add a backfill path

**Argumentation Summary:** The upload workflow works and is invisible when it does not. `AssetPipelineTrigger`
matches a pipeline by mime type and dispatches; when nothing matches, or when the graph contains a
kind no online worker will run (a **503** naming the kinds), it logs `dispatched=false` and returns.
The uploader sees a successful upload and no processing, with no way to tell the two apart.
Separately, the trigger fires on `asset.created` only, so a pipeline added later never processes
assets already in the system — there is no backfill at all.

**Improvement Summary:** Surface the trigger outcome to the uploader, and add a way to run a pipeline
over an existing set.

```
1. AssetPipelineTrigger.handle already computes the outcome. Carry it out: either make
   the match synchronous in AssetUploadEndpointService (one batched DAO read plus
   in-memory filtering) and extend the upload response with
   {triggered, runUuid?, reason?}, or push it over the pipeline-events WebSocket the UI
   is already connected to. The EventBus publish is fire-and-forget, so the response
   cannot simply wait for it - pick one of the two and say which in the spec.
2. Surface the reason in the upload queue row: "no matching pipeline" and
   "unsupported kinds: [whisper]" are different problems and must not look alike.
3. Backfill: POST /api/v1/pipelines/:uuid/run-over {libraryUuid | collectionUuid |
   assetUuids[]}, reusing PipelineEndpointService.dispatchRun. Guard it - this can
   dispatch tens of thousands of items.
4. Optional follow-up: promote meta.trigger to a validated PipelineTrigger model in
   loom-shared/pipeline-model with a PipelineModelValidator rule rejecting unknown keys,
   so a typo like "mimetypes" fails loudly instead of matching nothing. Add the rule in
   ONE place and delegate - structural validation is already duplicated between
   PipelineModelValidator and PipelineValidationService; do not create a third copy.
```

**References:** [../workflows/WORKFLOW_UPLOAD.md](../workflows/WORKFLOW_UPLOAD.md) §2 ·
`AssetPipelineTrigger.java`, `PipelineMatcher.java`, `PipelineEndpointService.java:246`
**Test Requirements:** `AssetPipelineTriggerTest` (event → match → `runForAsset` with the asset's
creator; **no** publish for a duplicate upload; a 503 from dispatch does not throw and is reported).
`AssetUploadEndpointTest` extended for the outcome field. A backfill endpoint test with a permission
case and a dry-run.

---

## Task 8: Persist key profiles and give every mode a real queue

**Argumentation Summary:** Two shared defects degrade all six workflow modes at once. (X5) Key
profiles live in `useState(DEFAULT_PROFILES)` (`WorkflowView.tsx:742`) — the rebinding UI works and
every rebind is lost on reload. (X6) Every mode reviews `listAssets(token, {limit: PAGE_SIZE})`
sliced to 20 (`:749`) rather than the items that need a decision, and (X7) nothing records that an
item was reviewed, so a session cannot be resumed and two reviewers cannot split a queue.

**Improvement Summary:** Persist profiles, replace "first 20 assets" with a per-workflow queue query,
and record per-session progress.

```
1. Profiles: start with localStorage keyed by profile id - the smallest fix that makes
   rebinding real. A server-side key_profile table is the follow-up if profiles need to
   travel between devices; decide explicitly rather than defaulting to a migration.
2. Queue: each mode declares its own queue query.
     rating/tagging  -> GET /assets?unrated=true / ?untagged=true  (NEW filters)
     dedup           -> GET /dedup-groups?status=PENDING           (exists)
     objects/faces   -> GET /detections?status=PENDING             (Task 5)
     llm             -> GET /node-results?status=PENDING           (WORKFLOW_AI_REVIEW)
   The two NEW asset filters are the only backend work here.
3. Progress: show "n of m decided" from the server's count, not from local state, so a
   resumed session and a second reviewer both see the truth.
4. Paging: page forward from a cursor. Deep offsets are capped
   (LOOM_SEARCH_MAX_OFFSET -> 400).
```

**References:** [../workflows/WORKFLOWS.md](../workflows/WORKFLOWS.md) §4 (X5, X6, X7) and §2.2
**Test Requirements:** A vitest unit test for profile persistence (save, load, corrupt-value
tolerance). A mocked Playwright spec asserting a rebind survives a reload. Endpoint tests for the two
new asset filters incl. paging and a permission case.

---

## Task 9: AI output review — the review record and the real screen

**Argumentation Summary:** Seven node kinds write free text that no human has read, and no consumer
distinguishes checked from unchecked: search ranks a hallucinated caption like a curated one, exports
carry both, the agent cites both. `LLMMode` (`WorkflowView.tsx:572`) is fully mocked — a hardcoded
string keyed on three demo asset ids, with a `gpt-4o` chip that names no real model and an `r`
"re-run prompt" binding whose `case` body is empty.

**Improvement Summary:** A review record hung off the `asset_node_result` ledger — one mechanism
serving this workflow, metadata repair and safety triage — plus a screen showing real text.

```
PREREQUISITE: complete the ledger. asset_node_result.result_ref is null for several
producers and origin is hard-coded COMPUTED, so a review keyed to a ledger row has
nothing to point at. See spec/tasks/METALOOM_NOTES.md "Complete the node provenance
record".

1. node_result_review table (see WORKFLOW_AI_REVIEW.md section 2.1) on the shared
   review_status enum from Task 5: result_uuid FK CASCADE, status, corrected_text, note,
   reviewed_at, reviewer_uuid NOT NULL, UNIQUE (result_uuid, reviewer_uuid).
   Hanging it off the ledger means a review is automatically scoped to the
   producer_version that was reviewed - a re-run under a new version is correctly
   unreviewed again.
2. DAO + jOOQ impl + contract tests + cascade tests.
3. POST /assets/:uuid/node-results/:uuid/review, a bulk variant, and a cross-asset
   PENDING queue route. Decide whether this needs its own permission or reuses
   UPDATE_ASSET - say which and why.
4. LLMMode: fetch real text via GET /assets/:uuid/node-results; show the real node_kind
   and producer_version in the chip; add an EDIT action (the third and most valuable
   answer); batch writes with rollback. Implement or REMOVE the dead rerun_llm binding -
   a bound key that does nothing is worse than an unbound one.
5. Consumers (separate, larger): exclude REJECTED text from search_document, from
   export, and from the agent's context. This touches the V2.57-V2.59 trigger set.
```

**References:** [../workflows/WORKFLOW_AI_REVIEW.md](../workflows/WORKFLOW_AI_REVIEW.md) ·
`V2.45__add_asset_node_result.sql` · [METALOOM_NOTES.md](METALOOM_NOTES.md)
**Test Requirements:** `NodeResultReviewDaoTest`, `NodeResultReviewEndpointTest` (incl. a re-run
under a new `producer_version` leaving the old review in place and the new value PENDING),
`SearchDocumentReviewTest`, `workflow-llm-mocked.spec.ts`.

---

## Task 10: Collection curation mode

**Argumentation Summary:** Every backend piece exists — collection schema with cascades, full CRUD,
permissions, a UI API module, search and similarity as queue sources — and curating still means
clicking through the assets grid one at a time. This is the cheapest workflow in the family: no
migration, no node, no new permission.

**Improvement Summary:** A `"curation"` mode with in/out/skip keys and a live filmstrip of the target
collection.

```
1. WorkflowMode gains "curation"; a curation-default KeyProfile:
   y/-> add, n/Space skip, x remove, <- back (with undo), Enter switch collection.
2. Two panes: the asset large, the target collection's contents as a filmstrip - the
   thing a checkbox grid cannot give you.
3. Queue sources: search result, library listing, another collection, similar-assets.
   LOOM_SEARCH_ENABLED defaults to OFF and the routes answer 503 - degrade to the
   library listing with a visible message, never a blank screen. Search is a capability,
   not a dependency, everywhere else in this codebase.
4. Batched membership writes with rollback; re-adding is an idempotent no-op (a curator
   will double-tap y); undo on <- must hit the server, not just move the cursor.
```

**References:**
[../workflows/WORKFLOW_COLLECTION_CURATION.md](../workflows/WORKFLOW_COLLECTION_CURATION.md) ·
[../loom/ui/TASK_UI_ORGANIZATION.md](../loom/ui/TASK_UI_ORGANIZATION.md)
**Test Requirements:** `curation.test.ts` (request shaping, batching, rollback) and
`workflow-curation-mocked.spec.ts` (`y` grows the filmstrip, `n` sends nothing, back-then-`x` undoes,
a 503 from search falls back with a message). ⚠️ Playwright `role`+`name` is a substring match — a new
"Curation" toggle can shadow an existing spec; use `exact: true`.

---

## Task 11: Metadata repair — rules, candidates, corrections

**Argumentation Summary:** The `metadata` node extracts EXIF/GPS/IPTC/XMP and resolves conflicts by
precedence; nothing detects that a resolved value is wrong, and the losing candidates — often the
correct ones — are discarded silently. A real archive arrives full of missing capture dates,
`1970-01-01`, Null Island coordinates and absent rights, and there is no way to find them, let alone
fix them in bulk.

**Improvement Summary:** A rule set that flags implausible metadata, candidate preservation in the
envelope, and a bulk-correction layer that never overwrites what the file said.

```
1. Rule set behind a strategy seam (copy FilterStrategy's shape): NO_CAPTURE_DATE,
   IMPLAUSIBLE_DATE, NO_TIMEZONE, NULL_ISLAND, SOURCE_CONFLICT, NO_RIGHTS. Adding a
   rule must not require editing the endpoint. Every input is already in Postgres, so
   this is SQL behind a route, not a node.
2. GET /api/v1/assets/metadata-issues?rule=&severity= with paging.
3. Preserve losing candidates in the asset_json_comp envelope, and UPDATE
   spec/features/nodes/metadata/METADATA_OVERVIEW.md in the same change - it is the only
   place the envelope contract and the precedence rules are written down.
4. Correction store: reuse node_result_review from Task 9 if it has landed, otherwise a
   small asset_metadata_correction table. DECIDE BEFORE BUILDING EITHER - two
   "a human overruled a machine value" mechanisms is the outcome to avoid.
5. Correction endpoint: single AND bulk-over-a-query, with a preview and an undo. Bulk
   is the point ("this whole folder is off by seven hours"); a bulk correction applied
   to a mis-scoped query is the most damaging action in this spec family.
6. Consumers honour the correction layer: search, API responses, export.
```

**References:**
[../workflows/WORKFLOW_METADATA_REPAIR.md](../workflows/WORKFLOW_METADATA_REPAIR.md) ·
[../features/nodes/metadata/METADATA_OVERVIEW.md](../features/nodes/metadata/METADATA_OVERVIEW.md) ·
[../concept/ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md) (the deferred file half)
**Test Requirements:** `MetadataRuleTest` per rule with boundary fixtures, `MetadataIssueEndpointTest`,
`MetadataCorrectionEndpointTest` (correction wins over the machine value; undo restores),
`MetadataEnvelopeCandidatesTest` (candidates preserved, winner still matches documented precedence).
⚠️ **Generate fixture bytes in test code** — `/opt/metaloom/loom-testdata` is unversioned and its
images carry no EXIF.

---

## Task 12: Safety triage — decision, consequence, and restricted-by-default

**Argumentation Summary:** The `guard` node produces a normalised verdict across three model families
(`score` is always P(unsafe)) and it lands in a JSON component and stops. No queue, no decision, no
consequence. Guardrail models are known to over-flag medical images, art, historical photographs and
news footage, so a threshold alone cannot resolve this — which is precisely what a review queue is
for.

**Improvement Summary:** A review record with category correction, a quarantine path reusing the
`move` node, and — the hard part — restricted-by-default visibility for flagged assets.

```
Depends on Task 4 (the mover) and Task 1 (the router).

1. Review record on the shared review_status enum, against the ledger row, plus a
   category correction ("this is violence, not sexual content"). Retain OVERTURNED
   verdicts - the false-positive rate is the only honest basis for changing a threshold.
2. PENDING means RESTRICTED. Fail closed: an unreviewed flag must never be treated as
   safe because nobody got to it.
3. DECIDE the enforcement point and write it into
   spec/features/permissions/PERMISSIONS.md. Three options, in
   WORKFLOW_SAFETY_TRIAGE.md section 3.3; the recommendation is moving flagged assets to
   a separate library with its own ACL, because it composes with the permission model
   that exists instead of adding row-level authorization the codebase does not have.
   A half-enforced restriction is worse than none - it reads as protection.
4. Quarantine path: 'quarantine' tag -> filter(TAG) -> move(quarantineFolder). Moves,
   never deletes: retention obligations frequently point the opposite way from a
   deletion instinct.
5. Triage mode: blur by default, deliberate reveal, an explicit permission, no flagged
   thumbnails anywhere else in the UI. This is the only review screen that is itself a
   hazard to the reviewer.
```

**References:**
[../workflows/WORKFLOW_SAFETY_TRIAGE.md](../workflows/WORKFLOW_SAFETY_TRIAGE.md) ·
[../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md) ·
`cortex/nodes/guard/`
**Test Requirements:** `SafetyReviewEndpointTest`, and above all `RestrictedVisibilityTest` — a
PENDING asset must not appear in asset lists, search results, thumbnails or the agent's context for a
user without the triage permission. ⚠️ **Never ship offensive fixtures**: synthesise a verdict row
against an ordinary test asset.

---

## Task 13: Ingest reconciliation

**Argumentation Summary:** Four source kinds, differential scanning, hashing, consistency checking,
fingerprinting, dedup and an S3 sink all exist, and nothing composes them into a migration — in
particular nothing answers *"did everything arrive?"*. The differential index the scanners maintain is
worker-local, so replacing a worker loses it, and there is no report an operator can sign off on
before switching off the old system. This is the one workflow whose failure mode is silent data loss.

**Improvement Summary:** A survey phase and a reconciliation phase that account for every source
object with a named disposition.

```
1. Survey mode: scan and report (count, bytes, types, depth) without ingesting.
2. Push the source inventory - or a summary of it - into Loom, so reconciliation
   survives a worker replacement.
3. Reconcile: for every source object emit MATCHED | MISSING | SIZE_MISMATCH |
   UNREADABLE | SKIPPED_BY_FILTER | DUPLICATE_OF. "Filtered by a MIME bucket" and "the
   read failed" must not look alike, and CORTEX_S3_MAX_OBJECT_SIZE exclusions must
   report as SKIPPED_BY_FILTER, not as missing.
4. Content-identity fallback where key identity is unreliable: a rename is detectable on
   a cloud drive (stable file id) but NOT on S3, where it is a delete plus a create.
5. Resumable per-item progress surfaced in the UI, on pipeline_run_item (V2.77
   normalised its state).
6. BEFORE trusting any real migration: audit every node on the ingest path for
   ctx.failure(...).next(), which returns SUCCESS and drops the cause. On an ingest path
   that is silent data loss. Also fix HashDedupNode's System.in.read() halt, which will
   block a headless worker indefinitely on a size mismatch.
```

**References:**
[../workflows/WORKFLOW_INGEST_MIGRATION.md](../workflows/WORKFLOW_INGEST_MIGRATION.md) ·
[../concept/NODE_S3SOURCE_PLAN.md](../concept/NODE_S3SOURCE_PLAN.md) ·
[../concept/NODE_CLOUDSOURCE_PLAN.md](../concept/NODE_CLOUDSOURCE_PLAN.md) ·
[../concept/CLUSTERING.md](../concept/CLUSTERING.md) (🔴 scale Cortex, never Loom)
**Test Requirements:** `ReconciliationTest` covering every disposition incl. the S3-rename case
resolved by content identity; `ResumeTest` (kill mid-run, the second run completes the remainder and
does not reprocess); `MigrationIT` in `integration-test/` over a few hundred synthetic objects
asserting zero unexplained gaps. Plus a documented dry run at 1% of the real corpus — not a test, but
the thing that actually de-risks it.

---

## Task 14: Rights and release — licence model and the export gate

**Argumentation Summary:** There is no concept of "cleared for use" anywhere in the tree, and two of
the five questions a release gate must answer are literally unrepresentable: `person` has no consent
field, and the only licence storage is a free-text `asset_location.license` column that `V2.10` marks
`/* unclear */`. Meanwhile the evidence for the other three questions — ownership, safety, AI
provenance — is already produced and simply never read as clearance.

**Improvement Summary:** Start with the two independently valuable pieces (a licence model, an
AI-provenance read path), then the clearance record and the export gate. Consent waits for the face
workflow.

```
Strictly sequential. Steps 1-4 are worth building alone; 5-7 must not start early.

1. Licence model: an SPDX-style identifier plus optional custom terms, on the ASSET
   (a licence follows content, not paths), replacing asset_location.license. Small
   change, large blast radius (API responses, search, export) - its own migration and
   its own tests.
2. AI-provenance read path: "is any of this machine-made?" as a query over
   asset_node_result. DISTINGUISH generated PIXELS (imagegen, videogen) from generated
   DESCRIPTION (captioning, llm, translate). A VLM caption does not make an image
   algorithmic media, and mislabelling an archive is worse than not labelling it.
3. release_clearance (see WORKFLOW_RIGHTS_RELEASE.md section 2.1): UNIQUE
   (asset_uuid, purpose), status on the shared review_status enum, expires_at,
   decider_uuid NOT NULL, and an EVIDENCE SNAPSHOT frozen at decision time. A clearance
   that silently re-derives itself is not a record of anything.
4. The export gate on EVERY byte-carrying exit: GET /assets/:uuid/binary/data, the
   s3-sink node, collection download. One ungated route makes the gate decorative.
   Reuse whatever enforcement point Task 12 picks - two half-enforced gates are worse
   than one enforced gate.
5. person_consent - only AFTER WORKFLOW_FACE reaches stage 4. Fail closed on unknowns:
   an unrecognised face is not an absent person.
6. Metadata write-back on export (licence, credit, IPTC DigitalSourceType, C2PA) -
   after spec/concept/ASSET_METADATA_WRITE.md is built.
7. Auto-clear policies - LAST, per purpose, opt-in, OFF by default, and recorded as the
   operator who enabled the policy rather than a null or a service account.
```

**References:**
[../workflows/WORKFLOW_RIGHTS_RELEASE.md](../workflows/WORKFLOW_RIGHTS_RELEASE.md) ·
[../concept/ASSET_METADATA_WRITE.md](../concept/ASSET_METADATA_WRITE.md) ·
[../features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md) ·
[../workflows/WORKFLOW_FACE.md](../workflows/WORKFLOW_FACE.md)
**Test Requirements:** `ReleaseClearanceDaoTest` (one row per (asset, purpose); evidence snapshot
immutable; expiry; cascade), `ProvenanceClassificationTest` (a VLM caption does not mark the image
algorithmic), `ClearanceExpiryTest`, and above all `ExportGateTest` — every byte-carrying route
refuses an uncleared asset, so that a new exit added without a gate check fails the suite.

---

## Task 15: Workflow documentation and demo data

**Argumentation Summary:** `find website/content -ipath "*workflow*"` returns nothing. The workflow
screen is a shipped, sidebar-visible feature with six modes and no customer-facing page, which
[../guidelines/CODING.md](../guidelines/CODING.md) requires. `DemoDatabaseInitializer` seeds no dedup
group, no PENDING detection and no rated or curated asset, so every mode opens empty on a demo stack
and looks broken rather than unused.

**Improvement Summary:** One customer docs page per shipped workflow, and demo data that makes each
mode show something on first open.

```
1. website/content/english/docs/workflows/: an index plus a page per SHIPPED workflow.
   Customer tone - no spec references, no class names. Document the keyboard bindings;
   they are the feature.
2. DemoDatabaseInitializer: a rated asset, a curated tag, a PENDING dedup group, an
   asset with PENDING detections carrying real labels. Enough that each mode shows
   something.
3. Do NOT document a workflow whose write path is a mock. Ship the docs with the wiring
   (Tasks 2, 3, 5) - a docs page describing a feature that silently discards the user's
   work is worse than no page.
```

**References:** [../guidelines/CODING.md](../guidelines/CODING.md) ·
[../website/WEBSITE.md](../website/WEBSITE.md) · `loom/core/.../boot/DemoDatabaseInitializer.java`
**Test Requirements:** The demo stack (`./start-demo.sh`) opens `/workflow` with non-empty content in
every mode. ⚠️ The website build: back up `yarn.lock` and escape bare `localhost` URLs in prose.

---

_Git HEAD revision: `43ada5a8`_
_Last updated: 2026-08-08 (Task 3 completed — dedup review loop wired end to end, plus the two
correctness defects that gated it)_
