# TASK_UI_AI_ML — AI / ML

> Open UI work items for the AI/ML entities (Embedding, Cluster, Detection, Person, Dedup Group),
> derived from a code audit of `loom-ui/` and `loom/services/rest/.../endpoint/impl/` on
> 2026-08-01. Format follows [../../TASKS.template.md](../../tasks/TASKS.template.md).
>
> **Context:** [LOOM_UI.md](LOOM_UI.md) (UI spec) · [../RESTAPI.md](../RESTAPI.md) ·
> [../DOMAIN.md](../DOMAIN.md) group 4
>
> **Ordering:** Task 1 (Workflow mock removal) is the only user-visible correctness issue —
> the face/person workflow currently shows fabricated data against real assets. Tasks 2–5 are
> independent. Task 6 is a decision.
>
> **Owned elsewhere — do not duplicate here:**
> * **Chat / agent UI** → [TASK_UI_CHAT.md](TASK_UI_CHAT.md) and [CHAT.md](CHAT.md).
> * **Semantic / vector search UI** → [../../features/search/SEMANTIC_SEARCH.md](../../features/search/SEMANTIC_SEARCH.md).
>   Nothing can be built here yet: `embedding.vector` is a staging buffer with **no ANN index and
>   no producer**, so any similarity query is a full scan.
> * **Lexical search UI** → **built**; see [../../features/search/SEARCH.md](../../features/search/SEARCH.md) §0.
>   Remaining UI work is [../../tasks/SEARCH_TASKS.md](../../tasks/SEARCH_TASKS.md) Tasks 4, 8, 17 and 19.

---

## Closed — outcome records

| Task (as originally filed) | Outcome — where it landed |
|---|---|
| Chat is mock-only (`mockChatService`) | ✅ DONE — real `loom-ui/src/api/chat.ts` (`listChats`/`loadChat`/`createChat`/`updateChat`/`deleteChat`) and `api/chatSessions.ts`; `features/chat/ChatWorkspace.tsx` + `features/chatSessions/`. Remaining chat work is tracked in [TASK_UI_CHAT.md](TASK_UI_CHAT.md) |
| Detection CRUD partially wired | ✅ DONE — `api/detections.ts` list/create/bulk/update/delete all driven from `features/detection/ObjectDetectionManagement.tsx` and `features/assetDetail/AssetDetail.tsx`; `e2e/detections-backend.spec.ts` |
| Detection review actions untested at the UI level | ✅ DONE — `e2e/detection-review-mocked.spec.ts` covers bulk staging (one `…/detections/bulk` request, never N single creates), a failed bulk save keeping the staging, `detection-confirm`, `detection-redraw` and `objectdetection-confirm`/`objectdetection-reject` |
| Cluster / Person CRUD missing | ✅ DONE — `api/clusters.ts` + `api/persons.ts`, UI in `features/faceDetection/{FaceDetectionManagement,ClustersPanel,PersonsPanel}.tsx`; `e2e/clusters-backend.spec.ts`, `e2e/persons-backend.spec.ts`. The remaining field-level gaps are Tasks 3 and 4 |
| `ClustersPanel` / `PersonsPanel` had no spec | ✅ DONE — `e2e/face-panels-mocked.spec.ts` (panel switcher, member crops, cluster→person confirmation seen from both panels, create, rename, both empty states) plus the panel-driven assignment case in `e2e/clusters-backend.spec.ts` |
| `loadDetection` / `loadPerson` / `loadCluster` have no caller | ✅ CLOSED as a non-gap for detections and persons — the list payload is complete and a single-load call would be a redundant round-trip. Only the cluster case has a real detail-view need (Task 4) |

---

## Task 2: Persist LLM detections through the detection endpoints

**Argumentation Summary:** `features/detection/LLMDetectionManagement.tsx` imports exactly one api
module — `listAssets` from `api/assets.ts`. Every prompt definition and every result lives in local
component state and is lost on unmount. Its sibling `ObjectDetectionManagement.tsx` persists through
`/assets/:uuid/detections`, so the LLM tab silently behaves differently from a tab that looks
identical.

**Improvement Summary:** Store LLM prompt runs as detections of the LLM type so they load,
persist, confirm and reject exactly like object detections.

```
Routes (asset sub-resource, AssetEndpoint.java → DetectionEndpointService):
  GET/POST /api/v1/assets/:uuid/detections
  POST/DELETE /api/v1/assets/:uuid/detections/:detectionUuid

1. Determine the detection type string for LLM output from DetectionEndpointService /
   the node kind Cortex writes (object detection uses "objectdetection" in
   ObjectDetectionManagement.tsx ~line 41). Do NOT invent a new value.
   ⚠️ `detection` is unique on (asset_uuid, node_kind, frame_number, detection_index) —
   two results for one frame must carry distinct detection_index or the insert aborts
   (LOOM_UI.md §7.8 gotcha).
2. In LLMDetectionManagement.tsx: load via listAssetDetections filtered on that type; persist a
   run via createDetection with prompt/model/effort/output in detection.meta.
3. Confirm → updateDetection(meta.confirmed); reject → deleteDetection. Mirror the
   ObjectDetectionManagement review UX rather than inventing a second one.

Edge cases: a prompt *definition* is not a detection — decide whether definitions live in
detection.meta or stay client-side; assets with no LLM detections must render EmptyState.
```

**References:** [LLMDetectionManagement.tsx](../../../loom-ui/src/features/detection/LLMDetectionManagement.tsx) ·
[ObjectDetectionManagement.tsx](../../../loom-ui/src/features/detection/ObjectDetectionManagement.tsx) (reference pattern) ·
[api/detections.ts](../../../loom-ui/src/api/detections.ts) ·
[DetectionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/DetectionEndpointService.java)

**Test Requirements:** `loom-ui/e2e/llm-detections-mocked.spec.ts` — a prompt run POSTs to
`/assets/:uuid/detections`, a reload re-renders it from the list route, confirm/reject issue
update/delete. Run: `cd loom-ui && yarn e2e --grep detection`.

---

## Task 4: Complete the cluster editor (type + meta) and add a cluster detail view

**Argumentation Summary:** `features/faceDetection/ClustersPanel.tsx` `handleUpdate` (~line 47)
calls `apiUpdateCluster(token, id, { name: editName })` — `type` and `meta`, both accepted by
`ClusterUpdateRequest`, can never be edited. `loadCluster` in `api/clusters.ts` has no caller, so
there is no single-cluster view; everything is driven off the list payload.

**Improvement Summary:** Expose `type` and `meta` in the edit dialog and add a detail view backed
by `loadCluster`.

```
1. ClustersPanel.tsx handleUpdate (~line 47): include type and meta in apiUpdateCluster; add a
   type select (enumerate from ClusterResponse usage) and a JSON/key-value editor for meta with
   parse validation before submit.
2. Add a cluster detail view (own file under features/faceDetection/) that calls loadCluster and
   lists members; route it from the cluster row.
3. Keep rename and delete behaviour unchanged.
```

**References:** [ClustersPanel.tsx](../../../loom-ui/src/features/faceDetection/ClustersPanel.tsx) ·
[api/clusters.ts](../../../loom-ui/src/api/clusters.ts) ·
[ClusterEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ClusterEndpoint.java)

**Test Requirements:** extend `loom-ui/e2e/clusters-backend.spec.ts` with a type+meta update; add
a mocked spec asserting the detail view issues `GET /clusters/:uuid`.
Run: `cd loom-ui && yarn e2e --grep clusters`.

---

## Task 5: Build the dedup-group review UI

**Argumentation Summary:** `DedupGroupEndpoint` (migration `V2.61`) registers
`POST/GET /api/v1/dedup-groups`, `GET/PATCH/DELETE /api/v1/dedup-groups/:uuid` and
`GET /api/v1/assets/:uuid/dedup-groups`, with `DedupGroupResponse`/`DedupGroupMemberModel` in
`rest-model`. **No `loom-ui` code references `dedup-groups`** — a repo-wide grep finds only i18n
strings and `features/workflow/WorkflowView.tsx`'s "deduplication" mode, which reviews locally
computed candidates and persists nothing. The dedup pipeline node therefore writes PENDING groups
that no operator can confirm, and the apply node has nothing CONFIRMED to act on: the feature is
end-to-end blocked on this UI.
[NODE_DEDUP.md](../../features/nodes/dedup/NODE_DEDUP.md) names this file as the
owner of the task.

**Improvement Summary:** Add `api/dedupGroups.ts` and make the workflow deduplication mode read
PENDING groups and write CONFIRMED/REJECTED back via PATCH.

```
Routes:
  GET   /api/v1/dedup-groups?status=PENDING      -> review queue          (READ_DEDUP)
  GET   /api/v1/dedup-groups/:uuid               -> one group + members   (READ_DEDUP)
  PATCH /api/v1/dedup-groups/:uuid               -> status + KEEP member  (UPDATE_DEDUP)
  DELETE/POST /api/v1/dedup-groups[/:uuid]                                (DELETE/CREATE_DEDUP)
  GET   /api/v1/assets/:uuid/dedup-groups        -> groups for one asset  (READ_DEDUP)

1. New loom-ui/src/api/dedupGroups.ts (list with status filter, load, patch, delete).
   Members carry a `size` and `zero_chunk_count` snapshot recorded at discovery time — display
   them; do not recompute.
2. Rewire the "deduplication" mode in WorkflowView.tsx: Y = PATCH status CONFIRMED with the
   selected KEEP member, N = PATCH status REJECTED. Keep the existing keyboard map.
3. Add a "Duplicates" row to AssetDetail via GET /assets/:uuid/dedup-groups.
4. Creation belongs to the discovery node — do not expose POST in the UI.

Edge cases: a group whose members were deleted between discovery and review; a concurrent PATCH
(refetch on 409/stale); the KEEP choice is mandatory before CONFIRMED.
```

**References:** [DedupGroupEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/DedupGroupEndpoint.java) ·
[NODE_DEDUP.md](../../features/nodes/dedup/NODE_DEDUP.md) §6 (route/permission table) ·
[WorkflowView.tsx](../../../loom-ui/src/features/workflow/WorkflowView.tsx) · migration `V2.61`

**Test Requirements:** `loom-ui/src/api/dedupGroups.test.ts`; `loom-ui/e2e/dedup-mocked.spec.ts`
(confirm/reject issue PATCH with the right status and KEEP) and a `dedup-backend.spec.ts` once
demo data seeds a PENDING group. Run: `cd loom-ui && yarn test && yarn e2e --grep dedup`.

---

## Task 6: Decide whether Embeddings get a management UI

**Argumentation Summary:** `EmbeddingEndpoint` has full CRUD plus `/embeddings/:embeddingUuid/attachments`,
and `CREATE/READ/UPDATE/DELETE_EMBEDDING` are already listed in `AdminArea.tsx` `PERMISSION_GROUPS`
with i18n descriptions. `loom-ui` has no `api/embeddings.ts`; the only trace is an untyped
`embeddings?: unknown[]` field in `api/assets.ts`. Before building a screen, note the context:
`embedding.vector` has no ANN index and no producer
([SEMANTIC_SEARCH.md](../../features/search/SEMANTIC_SEARCH.md)), so an embeddings browser today
would list rows nothing writes and nothing queries. This is a product decision, not a coding gap.

**Improvement Summary:** Either defer the UI until the semantic-search producer exists, or ship a
minimal read-only admin/debug list.

```
If deferring (recommended today): record "no UI until a vector producer exists" in
../RESTAPI.md next to the /embeddings rows and close this task.

If building the minimal version:
  1. loom-ui/src/api/embeddings.ts — list/load/delete + listEmbeddingAttachments.
     Never render the raw vector: show area/type/source/assetUuid and the dimension count.
  2. A read-only admin tab gated on READ_EMBEDDING; link assetUuid → /assets/:uuid.
  3. Skip create/update — embeddings are Cortex output.

Not buildable either way: embedding ↔ cluster membership has no REST route (see below).
```

**References:** [EmbeddingEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/EmbeddingEndpoint.java) ·
[../../features/search/SEMANTIC_SEARCH.md](../../features/search/SEMANTIC_SEARCH.md) §"no ANN index" ·
[AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) `PERMISSION_GROUPS`

**Test Requirements:** if deferred, spec note only. If built, `loom-ui/src/api/embeddings.test.ts`
plus a mocked list spec asserting the raw vector is never rendered.

---

## No REST surface — backend prerequisites, not UI gaps

* **`vector_config`** — table exists (`V2.6`), no `VectorConfigEndpoint`, no UI.
* **`person_image`** — table exists, `PersonEndpoint` is CRUD-only; only the scalar
  `primaryImageUuid` is reachable (Task 3).
* **`embedding_cluster`, `tag_cluster`, `collection_cluster`** — association tables in
  [../DOMAIN.md](../DOMAIN.md) group 4 with no routes: cluster membership, cluster tagging,
  cluster→collection assignment and cluster merge are all unbuildable in the UI today.
* **No top-level `/api/v1/detections`** — detections exist only as an asset sub-resource, so a
  global detection browser is not possible via REST.
* ~~**`POST /api/v1/similarity-index/rebuild`** has no UI consumer.~~ **Closed** — it landed on
  `/admin/indices` rather than the maintenance screen, alongside the lexical and embedding indices,
  because all three fail the same three ways and an operator reasons about them together. The
  fingerprint route itself is now a deprecated delegate; the UI calls `/api/v1/search-indices`.
  See [../../features/search/SEARCH_INDEX_ADMIN.md](../../features/search/SEARCH_INDEX_ADMIN.md).
_Git HEAD revision: `566a2cf3`_
_Last updated: 2026-08-09 (Task 1 closed — the workflow face/person seed and the hardcoded VLM string are gone; `src/mock/` deleted. Earlier the same day: detection review + face panel E2E coverage recorded as closed)_