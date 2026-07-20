# MetaLoom — CRUD Coverage Tasks: AI / ML

> Gaps between the REST API and the Loom UI (`loom-ui/`) for the AI/ML domain:
> **Embedding, Cluster, Detection, Person, Vector Config, Chat**.
> Derived from REST endpoints, UI api clients/features, and e2e specs.
> Format follows [../../TASKS.template.md](../../TASKS.template.md). See [../DOMAIN.md](../DOMAIN.md).

## Coverage Summary

| Element | REST endpoint? | Create | Read | Update | Delete | E2E |
|---|---|---|---|---|---|---|
| Embedding | ✅ `/embeddings` (+ attachments) | ❌ no UI client/screen | ❌ no UI client/screen | ❌ no UI client/screen | ❌ no UI client/screen | ❌ none |
| Cluster | ✅ `/clusters` | ⚠️ client exists, unused in UI; REST create ignores `name`/`type` | ✅ list wired (face detection / asset detail) | ⚠️ client exists, unused in UI | ⚠️ client exists, unused in UI | ❌ none |
| Cluster ↔ Embedding assignment | ❌ no REST route (table `embedding_cluster` exists) | ❌ | ❌ | ❌ | ❌ | ❌ |
| Detection | ✅ `/assets/:uuid/detections` (+ bulk) | ⚠️ client exists, unused in UI | ✅ list wired (read-only) | ⚠️ client exists, unused in UI | ⚠️ client exists, unused in UI | ⚠️ API-level only, no UI mutation |
| Person | ✅ `/persons` | ✅ wired | ✅ wired | ✅ wired (but `primaryImageUuid` dropped by backend) | ✅ wired | ⚠️ CRUD only, no gallery/primary image |
| Person gallery (`person_image`) | ❌ no REST route (table exists) | ❌ | ❌ | ❌ | ❌ | ❌ |
| Vector Config | ❌ no endpoint at all (table `vector_config` exists) | ❌ | ❌ | ❌ | ❌ | ❌ |
| Chat | ✅ `/chats` (message history) | ❌ UI uses mock service | ❌ UI uses mock service | ❌ UI uses mock service | ❌ UI uses mock service | ❌ none |

Legend: ✅ covered · ⚠️ partial / dead code / backend bug · ❌ missing.

---

## Task: Integrate Embedding vectors and attachments into the Loom UI

**Argumentation Summary:** The REST API fully exposes embeddings — [EmbeddingEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/EmbeddingEndpoint.java) registers `POST/GET/DELETE /api/v1/embeddings`, `GET /api/v1/embeddings/:uuid`, plus `POST`/`GET /api/v1/embeddings/:embeddingUuid/attachments`. The model carries the actual vector, area scope, type and owning asset ([EmbeddingModel.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/embedding/EmbeddingModel.java): `getVector()`, `getArea()`, `getType()`, `getAssetUuid()`). The UI has **no** embeddings API client (there is no `loom-ui/src/api/embeddings.ts`; `grep` in `loom-ui/src/api` finds only `embeddings?: unknown[]` on [assets.ts](../../../loom-ui/src/api/assets.ts)) and no screen renders embeddings. So an entire REST entity is invisible to the UI.

**Improvement Summary:** Add an embeddings API client and surface per-asset embedding vectors and their attachments in the asset detail view.

```
1. Create loom-ui/src/api/embeddings.ts mirroring the endpoint: listEmbeddings, loadEmbedding,
   createEmbedding, updateEmbedding, deleteEmbedding, plus listEmbeddingAttachments /
   createEmbeddingAttachment (base path /api/v1/embeddings, attachments at
   /embeddings/:embeddingUuid/attachments). Model EmbeddingResponse with vector: number[], area,
   type, assetUuid — see EmbeddingModel.java for the exact field set.
2. In loom-ui/src/features/assetDetail/AssetDetail.tsx (which already lists detections/clusters/
   persons) add an "Embeddings" section that lists the asset's embeddings, shows type + area scope,
   a compact vector preview (dimension count + first N values), and links to attachments.
3. Reuse the authHeaders/handleResponse pattern already used in clusters.ts / detections.ts.
```

**References:**
- REST: [EmbeddingEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/EmbeddingEndpoint.java), [EmbeddingEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/EmbeddingEndpointService.java), [EmbeddingModel.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/embedding/EmbeddingModel.java)
- UI: no client (add `loom-ui/src/api/embeddings.ts`), [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx), pattern in [detections.ts](../../../loom-ui/src/api/detections.ts)

**Test Requirements:**
- Unit test for `embeddings.ts` (list/load/create/delete + attachments) against mocked fetch.
- New e2e spec `loom-ui/e2e/embeddings-backend.spec.ts`: create an embedding via API, assert it appears in the asset detail embeddings section, delete it.

---

## Task: Wire Cluster create/update/delete into the face-detection UI

**Argumentation Summary:** [clusters.ts](../../../loom-ui/src/api/clusters.ts) implements the full CRUD surface of [ClusterEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ClusterEndpoint.java) (`createCluster`, `updateCluster`, `deleteCluster`, `loadCluster`, `listClusters`). But only `listClusters` is actually invoked anywhere — `grep` shows usages solely in [FaceDetectionManagement.tsx](../../../loom-ui/src/features/faceDetection/FaceDetectionManagement.tsx) and [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx), both read-only. `createCluster`/`updateCluster`/`deleteCluster` are dead code: the UI cannot create, rename, or delete a cluster even though the backend supports it.

**Improvement Summary:** Add cluster create/rename/delete controls to the face-detection clusters panel, wiring the existing client functions.

```
1. In loom-ui/src/features/faceDetection/ClustersPanel.tsx (currently only renders the listed
   clusters) add: a "Create cluster" action, per-cluster rename (calls updateCluster), and delete
   (calls deleteCluster) — mirror the pattern already used for persons in PersonsPanel.tsx
   (apiUpdatePerson/apiDeletePerson).
2. Import createCluster/updateCluster/deleteCluster from ../../api/clusters and refresh the list
   held in FaceDetectionManagement.tsx after each mutation.
```

**References:**
- REST: [ClusterEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ClusterEndpoint.java)
- UI: [clusters.ts](../../../loom-ui/src/api/clusters.ts), [ClustersPanel.tsx](../../../loom-ui/src/features/faceDetection/ClustersPanel.tsx), [FaceDetectionManagement.tsx](../../../loom-ui/src/features/faceDetection/FaceDetectionManagement.tsx), reference [PersonsPanel.tsx](../../../loom-ui/src/features/faceDetection/PersonsPanel.tsx)

**Test Requirements:**
- New e2e spec `loom-ui/e2e/clusters-backend.spec.ts`: create a cluster from the UI, rename it, delete it, asserting each state via the clusters panel (no clusters spec exists today).

---

## Task: Fix cluster naming and expose embedding-to-cluster assignment

**Argumentation Summary:** Two related gaps block the core cluster domain function ("group of embeddings by similarity, e.g. a person"). (1) [ClusterEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/ClusterEndpointService.java) `create()` hardcodes `String name = null; String type = null;` and calls `dao().createCluster(userUuid, null, null)` — the `name`/`type` from [ClusterCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/cluster/ClusterCreateRequest.java) are silently discarded, so a cluster can never be *created* with a name (naming it e.g. after a person only partly works via `update`). (2) The `embedding_cluster` join table (DOMAIN.md: Embedding ↔ Cluster) has **no REST route** — grepping `loom/services/rest` for assign/remove-embedding-to-cluster yields nothing, and [EmbeddingModel.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/embedding/EmbeddingModel.java) has no cluster field. There is no way, via REST or UI, to add or remove an embedding from a cluster — the fundamental aggregation operation is unimplemented.

**Improvement Summary:** Honor `name`/`type` on cluster create, and add REST routes plus UI wiring to assign/remove embeddings to/from a cluster.

```
1. Backend: in ClusterEndpointService.create() read request.getName()/getType() and pass them to
   dao().createCluster(...) instead of null/null.
2. Backend: add sub-resource routes to ClusterEndpoint.java, e.g.
   POST   /api/v1/clusters/:uuid/embeddings/:embeddingUuid   (assign)
   DELETE /api/v1/clusters/:uuid/embeddings/:embeddingUuid   (remove)
   GET    /api/v1/clusters/:uuid/embeddings                  (list members)
   backed by the embedding_cluster join in the cluster/embedding DAOs.
3. UI: extend loom-ui/src/api/clusters.ts with assignEmbedding/removeEmbedding/listClusterEmbeddings
   and let ClustersPanel.tsx show cluster members and support moving embeddings between clusters
   (the face-detection workflow of naming a cluster as a person).
```

**References:**
- REST: [ClusterEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/ClusterEndpointService.java), [ClusterEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ClusterEndpoint.java), [ClusterCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/cluster/ClusterCreateRequest.java)
- UI: [clusters.ts](../../../loom-ui/src/api/clusters.ts), [ClustersPanel.tsx](../../../loom-ui/src/features/faceDetection/ClustersPanel.tsx)

**Test Requirements:**
- Backend unit/integration test: create cluster with a name and assert it is persisted; assign then remove an embedding and assert membership via the list route.
- e2e in `clusters-backend.spec.ts`: assign an embedding to a cluster and verify the member appears.

---

## Task: Add Detection create / edit / delete UI (bbox + confidence)

**Argumentation Summary:** Detections are exposed as an asset sub-resource in [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (`POST/GET /assets/:uuid/detections`, `POST /assets/:uuid/detections/bulk`, `GET/POST/DELETE /assets/:uuid/detections/:detectionUuid`) and [detections.ts](../../../loom-ui/src/api/detections.ts) implements all of them (`createDetection`, `updateDetection`, `deleteDetection`, `bulkCreateDetections`, `loadDetection`, `listAssetDetections`). But `grep` shows only `listAssetDetections` is ever called — in [ObjectDetectionManagement.tsx](../../../loom-ui/src/features/detection/ObjectDetectionManagement.tsx), [WorkflowView.tsx](../../../loom-ui/src/features/workflow/WorkflowView.tsx) and [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx), all read-only. The object-detection screen's confirm/reject buttons only mutate local React state (`setDecisions`), never persisting via `updateDetection`/`deleteDetection`. The user cannot create, adjust the bounding box/confidence of, or delete a detection.

**Improvement Summary:** Wire the existing detection mutation client functions into the detection UI so bbox/confidence edits and deletes persist, and allow manual detection creation.

```
1. In loom-ui/src/features/detection/ObjectDetectionManagement.tsx replace the local-only
   confirm/reject (setDecisions) with real calls: reject -> deleteDetection, confirm/adjust ->
   updateDetection (bboxX/Y/Width/Height, confidence, type).
2. Add a "New detection" affordance (draw/enter a bbox) calling createDetection, and optionally
   bulkCreateDetections for importing multiple.
3. Refresh via listAssetDetections after each mutation.
```

**References:**
- REST: [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (detection routes ~lines 241-282), [DetectionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/DetectionEndpointService.java)
- UI: [detections.ts](../../../loom-ui/src/api/detections.ts), [ObjectDetectionManagement.tsx](../../../loom-ui/src/features/detection/ObjectDetectionManagement.tsx)

**Test Requirements:**
- Extend [detections-backend.spec.ts](../../../loom-ui/e2e/detections-backend.spec.ts) with a UI-driven test (currently CRUD is exercised only via raw `fetch`): create/edit/delete a detection through the object-detection screen and assert the change persists on reload.

---

## Task: Persist person primary image and expose the person gallery (`person_image`)

**Argumentation Summary:** The Person domain is "a named identity with a gallery of images and a primary image" (DOMAIN.md, tables `person`, `person_image`). Two gaps: (1) [PersonEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PersonEndpointService.java) `create()`/`update()` handle only `alias`/`firstname`/`lastname`/`meta` — they never read `getPrimaryImageUuid()`, even though [PersonUpdateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/person/PersonUpdateRequest.java) defines `primaryImageUuid` and [persons.ts](../../../loom-ui/src/api/persons.ts) sends it. So the UI's primary-image field is silently dropped by the backend. (2) The `person_image` gallery table (`V2.26__add_person.sql`) has **no REST route** — there is no way to add/list/remove images in a person's gallery, and no UI for it.

**Improvement Summary:** Persist `primaryImageUuid` in the person service and add gallery sub-resource routes plus UI.

```
1. Backend: in PersonEndpointService create()/update() call update(request::getPrimaryImageUuid,
   person::setPrimaryImageUuid) so the primary image is stored.
2. Backend: add gallery routes to PersonEndpoint.java, e.g.
   GET    /api/v1/persons/:uuid/images                 (list gallery)
   POST   /api/v1/persons/:uuid/images/:assetUuid      (add image)
   DELETE /api/v1/persons/:uuid/images/:assetUuid      (remove image)
   backed by person_image.
3. UI: extend loom-ui/src/api/persons.ts with listPersonImages/addPersonImage/removePersonImage,
   and render the gallery + primary-image selection in PersonsPanel.tsx.
```

**References:**
- REST: [PersonEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PersonEndpointService.java), [PersonEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PersonEndpoint.java), [PersonUpdateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/person/PersonUpdateRequest.java), migration `loom/db/flyway/src/main/resources/db/migration/V2.26__add_person.sql`
- UI: [persons.ts](../../../loom-ui/src/api/persons.ts), [PersonsPanel.tsx](../../../loom-ui/src/features/faceDetection/PersonsPanel.tsx)

**Test Requirements:**
- Backend test: set `primaryImageUuid` on update and assert it round-trips; add/remove a gallery image and assert via the list route.
- Extend [persons-backend.spec.ts](../../../loom-ui/e2e/persons-backend.spec.ts) (today covers alias create/update/delete only) with a primary-image and gallery add/remove flow.

---

## Task: Implement Vector Config (no REST endpoint, no UI at all)

**Argumentation Summary:** `vector_config` is a first-class AI/ML entity (DOMAIN.md: "Named weight definition for building custom vector indices"). The table exists — `loom/db/flyway/src/main/resources/db/migration/V2.6__add_vector_config.sql` with a `weights` column — and jOOQ generated `JooqVectorConfig`. But grepping `loom/services/rest` and `loom-shared/rest-model` finds **no endpoint, no service, no rest-model** for it, and `loom-ui/src/api` has no client. The entity is completely unreachable from both API and UI.

**Improvement Summary:** Add the full REST surface for vector configs and a management UI for their weight definitions.

```
1. Backend: add rest-model (VectorConfigModel/CreateRequest/UpdateRequest/Response/ListResponse
   with name + weights), a VectorConfigEndpointService (CRUD, following ClusterEndpointService's
   AbstractCRUDEndpointService pattern), and a VectorConfigEndpoint at /api/v1/vectorconfigs.
2. Wire the DAO to the existing vector_config table (jOOQ JooqVectorConfig).
3. UI: add loom-ui/src/api/vectorConfigs.ts and an admin/settings screen to create/edit the named
   weight definitions.
```

**References:**
- DB: migration `loom/db/flyway/src/main/resources/db/migration/V2.6__add_vector_config.sql`, `loom/db/jooq/src/jooq/java/io/metaloom/loom/db/jooq/tables/JooqVectorConfig.java`
- REST: none exists (add under `loom/services/rest/.../endpoint/impl/` + `.../service/impl/` + `loom-shared/rest-model/.../vectorconfig/`)
- UI: none exists (add `loom-ui/src/api/vectorConfigs.ts`)

**Test Requirements:**
- Backend integration test for vector-config CRUD.
- Unit test for the new UI client and an e2e spec `loom-ui/e2e/vector-config-backend.spec.ts` covering create/edit/delete.

---

## Task: Back the Chat workspace with the REST `/chats` API

**Argumentation Summary:** The REST API provides full chat-session CRUD with persisted message history — [ChatEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ChatEndpoint.java) registers `POST/GET/DELETE /api/v1/chats` and `GET/POST /api/v1/chats/:uuid`, and [ChatResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/chat/ChatResponse.java) carries `title` + a `messages` JSON array (migration `V2.28__add_chat.sql`: "role, content, metadata, asset references"). The UI's [ChatWorkspace.tsx](../../../loom-ui/src/features/chat/ChatWorkspace.tsx) imports `mockChatService` from `src/mock/services` and never calls the REST API — there is **no** `loom-ui/src/api/chat.ts` (grep of `loom-ui/src/api` for "chat" finds nothing). Chat sessions are not listed, not persisted, and not loadable across reloads despite full backend support.

**Improvement Summary:** Add a chat API client and switch the chat workspace from the mock service to REST-backed sessions with persisted history.

```
1. Create loom-ui/src/api/chat.ts: listChats, loadChat, createChat, updateChat (append messages),
   deleteChat — base path /api/v1/chats, ChatResponse { uuid, title, messages: ChatMessage[] }.
2. In ChatWorkspace.tsx replace mockChatService with the client: load the session list, create a
   session on first message, and persist each turn via updateChat (messages JSON array). Keep the
   asset/collection/task reference chips (RefChip) but sourced from the persisted messages.
3. Add a session-history sidebar to select/rename/delete past chats.
```

**References:**
- REST: [ChatEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ChatEndpoint.java), [ChatEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/ChatEndpointService.java), [ChatResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/chat/ChatResponse.java)
- UI: no client (add `loom-ui/src/api/chat.ts`), [ChatWorkspace.tsx](../../../loom-ui/src/features/chat/ChatWorkspace.tsx), currently `src/mock/services.ts` (`mockChatService`)

**Test Requirements:**
- Unit test for `chat.ts` (list/create/update/delete, messages round-trip) against mocked fetch.
- New e2e spec `loom-ui/e2e/chat-backend.spec.ts`: create a chat session in the UI, send a message, reload, and assert the message history persisted via the REST API.
