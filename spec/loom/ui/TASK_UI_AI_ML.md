# TASK_UI_AI_ML — AI / ML

Gap-analysis tasks between the Loom REST API and the Loom UI for the AI/ML entities
(Embedding, Cluster, Detection, Person, Vector Config, Chat). Follows [../../TASKS.template.md](../../TASKS.template.md).

Sources of truth:
- REST routes: [EmbeddingEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/EmbeddingEndpoint.java), [ClusterEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ClusterEndpoint.java), [PersonEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PersonEndpoint.java), [ChatEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ChatEndpoint.java), [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (detections), [DetectionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/DetectionEndpointService.java).
- REST spec: [RESTAPI.md](../RESTAPI.md). Domain group 4: [DOMAIN.md](../DOMAIN.md).
- UI spec: [LOOM_UI.md](./LOOM_UI.md).

## Coverage Matrix

| Entity | REST Operation (path · method) | UI Status | Where / Gap |
|--------|-------------------------------|-----------|-------------|
| **Embedding** | `/api/v1/embeddings` · POST (create) | Missing | No `api/*.ts` module and no feature. Only appears as an RBAC permission string in [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) and an untyped `embeddings?: unknown[]` field in [api/assets.ts](../../../loom-ui/src/api/assets.ts). |
| **Embedding** | `/api/v1/embeddings/:uuid` · POST (update) | Missing | No UI. |
| **Embedding** | `/api/v1/embeddings/:uuid` · DELETE | Missing | No UI. |
| **Embedding** | `/api/v1/embeddings` · GET (list) | Missing | No UI. |
| **Embedding** | `/api/v1/embeddings/:uuid` · GET (read) | Missing | No UI. |
| **Embedding** | `/api/v1/embeddings/:embeddingUuid/attachments` · POST (create attachment) | Missing | No UI, and per [RESTAPI.md](../RESTAPI.md) the HTTP client itself lacks embedding-attachment methods. |
| **Embedding** | `/api/v1/embeddings/:embeddingUuid/attachments` · GET (list attachments) | Missing | No UI. |
| **Embedding ↔ Cluster** | `embedding_cluster` (no REST route) | N/A (no REST surface) | Association not exposed by REST — `EmbeddingModel` has no `clusterUuid`, no `/embeddings/:uuid/clusters` route. Cannot be built in UI without backend work. |
| **Cluster** | `/api/v1/clusters` · POST (create) | Implemented | [FaceDetectionManagement.tsx](../../../loom-ui/src/features/faceDetection/FaceDetectionManagement.tsx) create-cluster dialog → `createCluster` in [api/clusters.ts](../../../loom-ui/src/api/clusters.ts). |
| **Cluster** | `/api/v1/clusters/:uuid` · POST (update) | Partial | [ClustersPanel.tsx](../../../loom-ui/src/features/faceDetection/ClustersPanel.tsx) `apiUpdateCluster` sends only `{ name }`. `type` and `meta` from `ClusterUpdateRequest` are never editable. |
| **Cluster** | `/api/v1/clusters/:uuid` · DELETE | Implemented | [ClustersPanel.tsx](../../../loom-ui/src/features/faceDetection/ClustersPanel.tsx) `apiDeleteCluster`. |
| **Cluster** | `/api/v1/clusters` · GET (list) | Implemented | [FaceDetectionManagement.tsx](../../../loom-ui/src/features/faceDetection/FaceDetectionManagement.tsx), [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx). |
| **Cluster** | `/api/v1/clusters/:uuid` · GET (read) | Partial | `loadCluster` exists in [api/clusters.ts](../../../loom-ui/src/api/clusters.ts) but has no caller; the UI relies on the list payload, so there is no single-cluster detail view. |
| **Cluster ↔ Tag** | `tag_cluster` (no REST route) | N/A (no REST surface) | No `/clusters/:uuid/tags` endpoint; cluster tagging cannot be surfaced. DOMAIN group 4 lists the relation. |
| **Cluster ↔ Collection** | `collection_cluster` (no REST route) | N/A (no REST surface) | No cluster↔collection linking endpoint exists. |
| **Detection** | `/api/v1/assets/:uuid/detections` · POST (create) | Implemented | `createDetection` in [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx). |
| **Detection** | `/api/v1/assets/:uuid/detections/bulk` · POST (bulk create) | Implemented | `bulkCreateDetections` in [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx). |
| **Detection** | `/api/v1/assets/:uuid/detections` · GET (list) | Implemented | [ObjectDetectionManagement.tsx](../../../loom-ui/src/features/detection/ObjectDetectionManagement.tsx), [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) via `listAssetDetections`. |
| **Detection** | `/api/v1/assets/:uuid/detections/:detectionUuid` · GET (read) | Missing | `loadDetection` defined in [api/detections.ts](../../../loom-ui/src/api/detections.ts) but never called. |
| **Detection** | `/api/v1/assets/:uuid/detections/:detectionUuid` · POST (update) | Implemented | Confirm-flow in [ObjectDetectionManagement.tsx](../../../loom-ui/src/features/detection/ObjectDetectionManagement.tsx) (`updateDetection` sets `meta.confirmed`) and [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx). |
| **Detection** | `/api/v1/assets/:uuid/detections/:detectionUuid` · DELETE | Implemented | [ObjectDetectionManagement.tsx](../../../loom-ui/src/features/detection/ObjectDetectionManagement.tsx) reject-flow, [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx). |
| **Detection (LLM)** | (uses same `/detections` routes, `type` = LLM) | Missing | [LLMDetectionManagement.tsx](../../../loom-ui/src/features/detection/LLMDetectionManagement.tsx) imports only `listAssets`; prompts/results are local component state and are never persisted to or read from `/detections`. |
| **Person** | `/api/v1/persons` · POST (create) | Implemented | `createPerson` in [FaceDetectionManagement.tsx](../../../loom-ui/src/features/faceDetection/FaceDetectionManagement.tsx). |
| **Person** | `/api/v1/persons/:uuid` · POST (update) | Partial | [PersonsPanel.tsx](../../../loom-ui/src/features/faceDetection/PersonsPanel.tsx) sends `{ alias, firstname, lastname }` only. `primaryImageUuid` (present in `PersonUpdateRequest`/`PersonResponse` in [api/persons.ts](../../../loom-ui/src/api/persons.ts)) is never set from the UI. |
| **Person** | `/api/v1/persons/:uuid` · DELETE | Implemented | `apiDeletePerson` in [PersonsPanel.tsx](../../../loom-ui/src/features/faceDetection/PersonsPanel.tsx). |
| **Person** | `/api/v1/persons` · GET (list) | Implemented | `listPersons` in [FaceDetectionManagement.tsx](../../../loom-ui/src/features/faceDetection/FaceDetectionManagement.tsx). |
| **Person** | `/api/v1/persons/:uuid` · GET (read) | Missing | `loadPerson` defined in [api/persons.ts](../../../loom-ui/src/api/persons.ts) but has no caller. |
| **Person images (gallery)** | `person_image` table (no REST route) | N/A (no REST surface) | DOMAIN group 4 describes "a gallery of images and a primary image", but no `/persons/:uuid/images` endpoint exists; only the scalar `primaryImageUuid` is exposed. Gallery management requires backend work. |
| **Vector Config** | `vector_config` table (no REST route) | N/A (no REST surface) | Table added by migration `V2.6__add_vector_config` ([PERSISTENCE.md](../PERSISTENCE.md)) and listed in DOMAIN group 4, but there is no `VectorConfigEndpoint` and no UI. |
| **Chat** | `/api/v1/chats` · POST (create) | Missing | [ChatWorkspace.tsx](../../../loom-ui/src/features/chat/ChatWorkspace.tsx) uses `mockChatService` from [mock/services](../../../loom-ui/src/mock/services); no `api/chat.ts` module exists. |
| **Chat** | `/api/v1/chats/:uuid` · POST (update, message history) | Missing | Mock only; `ChatModel.messages` (JsonArray) never persisted. |
| **Chat** | `/api/v1/chats/:uuid` · DELETE | Missing | Mock only. |
| **Chat** | `/api/v1/chats` · GET (list sessions) | Missing | Mock only; no session list/switcher against REST. |
| **Chat** | `/api/v1/chats/:uuid` · GET (read/load session) | Missing | `mockChatService.getHistory()` is used instead of a REST session load. |

---


---

## Task: Build an Embedding management UI (CRUD + attachments)

**Argumentation Summary:** Embedding is a first-class REST entity with full CRUD plus attachment sub-resources, an RBAC permission set (`CREATE/READ/UPDATE/DELETE_EMBEDDING` already listed in AdminArea), yet it has no `api` module and no feature at all in the UI. There is currently no way to inspect, delete, or manage embeddings or their attachments.

**Improvement Summary:** Add `api/embeddings.ts` and a management view (list + detail + delete, plus an attachments sub-panel) analogous to the cluster/person tooling.

```
Endpoints (from EmbeddingEndpoint.java, basePath /api/v1/embeddings):
  - POST   /api/v1/embeddings                                  → create (EmbeddingCreateRequest: area, vector, type, assetUuid, source)
  - GET    /api/v1/embeddings                                   → list (paged)
  - GET    /api/v1/embeddings/:uuid                             → read
  - POST   /api/v1/embeddings/:uuid                             → update
  - DELETE /api/v1/embeddings/:uuid                            → delete
  - POST   /api/v1/embeddings/:embeddingUuid/attachments       → create attachment
  - GET    /api/v1/embeddings/:embeddingUuid/attachments       → list attachments

Work:
  1. Create loom-ui/src/api/embeddings.ts with EmbeddingResponse (area, vector Float[], type,
     assetUuid, source), list/load/create/update/delete, plus listEmbeddingAttachments and
     createEmbeddingAttachment.
  2. Add a feature view (e.g. loom-ui/src/features/embedding/EmbeddingManagement.tsx, or an
     embeddings tab under an existing AI area) with a paged list, a read-only detail (vector
     is high-dimensional — show type/source/assetUuid/area and dimension count, not raw floats),
     delete, and an attachments sub-list.
  3. Link embeddings to their asset (assetUuid → AssetDetail) since embeddings are asset-scoped.

Edge cases: the vector array can be large — never render it raw; attachment create is a binary
upload (multipart) — confirm the attachment content-type handling against AttachmentEndpoint;
creating an embedding by hand is unusual (they come from Cortex) so create-from-UI may be
admin/debug-only — gate behind CREATE_EMBEDDING.
```

**References:**
- [EmbeddingEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/EmbeddingEndpoint.java)
- [EmbeddingEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/EmbeddingEndpointService.java)
- [EmbeddingModel.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/embedding/EmbeddingModel.java)
- [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) (Embedding permission set)

**Test Requirements:**
- Unit test `api/embeddings.ts` including the two attachment sub-resource calls.
- Component test: list renders, delete calls `deleteEmbedding`, attachments sub-list calls `listEmbeddingAttachments`.

---

## Task: Persist LLM detections through the Detection REST API

**Argumentation Summary:** The LLM tab of Detection Management lets users define prompts and shows results, but [LLMDetectionManagement.tsx](../../../loom-ui/src/features/detection/LLMDetectionManagement.tsx) only imports `listAssets` — every prompt and result lives in local component state and vanishes on unmount. The object-detection sibling already persists through `/assets/:uuid/detections`, so the LLM path is an inconsistent, non-persistent gap.

**Improvement Summary:** Wire LLM prompt runs and their results to the detection endpoints (or the correct detection `type`) so they load, persist, and delete like object detections.

```
Endpoints (asset sub-resource, AssetEndpoint.java):
  - GET    /api/v1/assets/:uuid/detections            → list (filter by LLM type)
  - POST   /api/v1/assets/:uuid/detections            → create
  - POST   /api/v1/assets/:uuid/detections/:detectionUuid → update
  - DELETE /api/v1/assets/:uuid/detections/:detectionUuid → delete

Work:
  1. Confirm the detection `type` string used for LLM results (object detection uses
     "objectdetection" in ObjectDetectionManagement.tsx line ~41; find the LLM equivalent
     via DetectionEndpointService / DetectionType).
  2. In LLMDetectionManagement.tsx, load existing LLM detections via listAssetDetections
     (filtering on that type), and persist prompt-run outputs via createDetection, storing
     the prompt/model/effort/result payload in detection.meta.
  3. Support confirm (updateDetection meta) and reject (deleteDetection), matching the
     ObjectDetectionManagement review UX.

Edge cases: prompt config (name/model/prompt/reasoningEffort) may not map to a single detection —
decide whether prompt definitions belong in detection.meta or need their own store; keep the
existing dialog UX; handle assets with no LLM detections gracefully.
```

**References:**
- [LLMDetectionManagement.tsx](../../../loom-ui/src/features/detection/LLMDetectionManagement.tsx)
- [ObjectDetectionManagement.tsx](../../../loom-ui/src/features/detection/ObjectDetectionManagement.tsx) (reference persistence pattern)
- [DetectionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/DetectionEndpointService.java)
- [api/detections.ts](../../../loom-ui/src/api/detections.ts)

**Test Requirements:**
- Test that a prompt run persists via `createDetection` and reloads via `listAssetDetections`.
- Test confirm/reject calling `updateDetection`/`deleteDetection`.

---

## Task: Let the Person editor set the primary image and expose a person image gallery

**Argumentation Summary:** `PersonResponse`/`PersonUpdateRequest` already carry `primaryImageUuid` and `createPerson`/`updatePerson` accept it, but the Person edit dialog in [PersonsPanel.tsx](../../../loom-ui/src/features/faceDetection/PersonsPanel.tsx) only edits alias/first/last name, so the primary image can never be chosen. DOMAIN group 4 further describes each person as having "a gallery of images and a primary image" (`person_image` table), which the UI cannot show at all. The primary-image field is a UI-only gap (REST already supports it); the gallery is a REST gap to record.

**Improvement Summary:** Add primary-image selection to the person editor now (REST already supports it); record the missing `person_image` gallery REST surface as a backend prerequisite for full gallery management.

```
Part A — UI gap (REST already supports it):
  In PersonsPanel.tsx handleUpdate (~line 57) include primaryImageUuid in the apiUpdatePerson
  request, and add a control to pick it — e.g. an asset/image picker (reuse AssetBrowser) that
  yields the chosen image UUID. Show the current primary image (persons.ts primaryImageUuid) as
  a thumbnail in the person card and edit dialog.

Part B — REST gap (record + prerequisite):
  There is NO /api/v1/persons/:uuid/images endpoint (PersonEndpoint.java exposes only CRUD),
  even though the person_image table exists. A true gallery (add/remove images, choose primary
  from the gallery) requires a new person-image sub-resource endpoint first. File that backend
  task; until it exists the UI can only manage the scalar primaryImageUuid.

Edge cases: primaryImageUuid may reference an asset binary — resolve it to a thumbnail URL the
same way faces are rendered elsewhere; handle persons with no primary image; clearing the primary
image (send null/omit).
```

**References:**
- [PersonEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PersonEndpoint.java) (CRUD only — no image sub-resource)
- [PersonResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/person/PersonResponse.java) (`primaryImageUuid`)
- [PersonsPanel.tsx](../../../loom-ui/src/features/faceDetection/PersonsPanel.tsx)
- [api/persons.ts](../../../loom-ui/src/api/persons.ts)
- [DOMAIN.md](../DOMAIN.md) group 4 (Person / `person_image`)

**Test Requirements:**
- Test that editing a person sends `primaryImageUuid` in the update body and renders the thumbnail.
- Test clearing the primary image.

---

## Task: Complete the Cluster editor (type + meta) and add a single-cluster detail view

**Argumentation Summary:** Cluster update is only partially covered: [ClustersPanel.tsx](../../../loom-ui/src/features/faceDetection/ClustersPanel.tsx) sends `{ name }` only, so `type` and `meta` (both in `ClusterUpdateRequest`) can never be edited. `loadCluster` also exists in the api module with no caller, so there is no dedicated cluster detail view — everything relies on the list payload.

**Improvement Summary:** Expose `type` and `meta` in the cluster edit dialog and add a cluster detail view backed by `loadCluster`.

```
Endpoints (ClusterEndpoint.java):
  - POST /api/v1/clusters/:uuid  → update (ClusterUpdateRequest: name, type, meta)
  - GET  /api/v1/clusters/:uuid  → read (currently unused loadCluster in api/clusters.ts)

Work:
  1. In ClustersPanel.tsx handleUpdate (~line 47) include type and meta in the apiUpdateCluster
     call; add fields for them to the edit dialog (type as a select if the allowed values are
     enumerable; meta as a JSON/key-value editor).
  2. Add a cluster detail view that calls loadCluster and shows members/metadata, rather than
     depending solely on listClusters.

Note (no REST surface — record only, do not implement): DOMAIN group 4 lists Cluster ↔ Tag
(tag_cluster) and Cluster ↔ Collection (collection_cluster) relations, and embedding↔cluster
(embedding_cluster), but NONE have REST endpoints (no /clusters/:uuid/tags, /clusters/:uuid/
collections, or cluster-membership route). Cluster tagging, collection assignment, and cluster
merge therefore cannot be built in the UI without new endpoints — flag as backend prerequisites.

Edge cases: meta is free-form JSON — validate before submit; empty type; keep delete/rename intact.
```

**References:**
- [ClusterEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ClusterEndpoint.java)
- [ClusterResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/cluster/ClusterResponse.java)
- [ClustersPanel.tsx](../../../loom-ui/src/features/faceDetection/ClustersPanel.tsx)
- [api/clusters.ts](../../../loom-ui/src/api/clusters.ts)
- [DOMAIN.md](../DOMAIN.md) group 4 (Cluster relations without REST surface)

**Test Requirements:**
- Test that updating a cluster sends `type` and `meta` in the body.
- Test the detail view calls `loadCluster` and renders it.

---

## Recorded findings (no action / backend prerequisites)

These are verified surfaces where the gap is a missing REST endpoint, not a missing UI, and are
recorded here so they are not mistaken for UI omissions:

- **Vector Config** — table `vector_config` exists (migration `V2.6__add_vector_config`, DOMAIN
  group 4) but there is **no REST endpoint** (`VectorConfigEndpoint` does not exist) and no UI.
  Any "custom vector index" configuration screen requires a backend endpoint first.
- **Person image gallery** — `person_image` table exists but no `/persons/:uuid/images` route;
  only scalar `primaryImageUuid` is exposed (see Person task).
- **Cluster ↔ Tag / Cluster ↔ Collection / Embedding ↔ Cluster** — association tables
  (`tag_cluster`, `collection_cluster`, `embedding_cluster`) exist per DOMAIN group 4 but have no
  REST routes; cluster tagging/collection-assignment/membership and cluster merge are not
  buildable in the UI without new endpoints.
- **Detection has no top-level entity endpoint** — detections are only asset sub-resources
  (`/assets/:uuid/detections…`); `DetectionEndpointService` is invoked from `AssetEndpoint`. There
  is no standalone `/api/v1/detections` list, so a global detection browser is not currently
  possible via REST.
