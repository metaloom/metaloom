# TASK_UI_ASSETS_MEDIA — Assets & Media

Gap-analysis tasks between the Loom REST API and the Loom UI for the Assets & Media
entities (Asset, Asset Location, Asset Pool, Asset Component, Asset Remix, Attachment,
Blacklist, Annotation). Follows [../../TASKS.template.md](../../TASKS.template.md).

Authoritative REST routes were read from the endpoint registrations under
[loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/).
UI status was verified against [loom-ui/src/api/](../../../loom-ui/src/api/) and
[loom-ui/src/features/](../../../loom-ui/src/features/).

## Coverage Matrix

| Entity | REST Operation (path · method) | UI Status | Where / Gap |
|--------|-------------------------------|-----------|-------------|
| Asset | `/api/v1/assets` · POST (create) | Implemented | `createAsset` in [api/assets.ts](../../../loom-ui/src/api/assets.ts) |
| Asset | `/api/v1/assets` · GET (list) | Implemented | `listAssets` in api/assets.ts; [features/assets/AssetBrowser.tsx](../../../loom-ui/src/features/assets/AssetBrowser.tsx) |
| Asset | `/api/v1/assets/bulk/create` · POST | Implemented | `bulkCreateAssets` in api/assets.ts |
| Asset | `/api/v1/assets/bulk/update` · POST | Implemented | `bulkUpdateAssets` in api/assets.ts |
| Asset | `/api/v1/assets/upload` · POST | Implemented | `uploadAsset` in api/assets.ts |
| Asset | `/api/v1/assets/sha512/:sha512` · GET | **Missing** | No sha512 lookup fn in api/assets.ts |
| Asset | `/api/v1/assets/sha512/:sha512` · POST (update) | **Missing** | No sha512 fn |
| Asset | `/api/v1/assets/sha512/:sha512` · PATCH | **Missing** | No sha512 fn |
| Asset | `/api/v1/assets/sha512/:sha512` · PUT | **Missing** | No sha512 fn |
| Asset | `/api/v1/assets/sha512/:sha512` · DELETE | **Missing** | No sha512 fn |
| Asset | `/api/v1/assets/:uuid` · GET | Implemented | `loadAsset` in api/assets.ts |
| Asset | `/api/v1/assets/:uuid` · POST (update) | Implemented | `updateAsset` in api/assets.ts |
| Asset | `/api/v1/assets/:uuid` · PATCH (partial) | **Missing** | UI only calls POST; no PATCH helper |
| Asset | `/api/v1/assets/:uuid` · PUT (replace) | **Missing** | UI only calls POST; no PUT helper |
| Asset | `/api/v1/assets/:uuid` · DELETE | Implemented | `deleteAsset` in api/assets.ts |
| Asset→Tags | `/api/v1/assets/:uuid/tags` · POST | Implemented | `tagAsset` in [api/tags.ts](../../../loom-ui/src/api/tags.ts) |
| Asset→Tags | `/api/v1/assets/:uuid/tags/:tagUuid` · DELETE | Implemented | `untagAsset` in api/tags.ts |
| Asset→Reactions | `/api/v1/assets/:uuid/reactions` · POST | Implemented | `createAssetReaction` in [api/reactions.ts](../../../loom-ui/src/api/reactions.ts) |
| Asset→Reactions | `/api/v1/assets/:uuid/reactions/:reactionUuid` · DELETE | Implemented | `deleteAssetReaction` in api/reactions.ts |
| Asset→Reactions | `/api/v1/assets/:uuid/reactions` · GET | Implemented | `listAssetReactions` in api/reactions.ts |
| Asset→Reactions | `/api/v1/assets/:uuid/reactions/:reactionUuid` · GET | Implemented | `loadAssetReaction` in api/reactions.ts |
| Asset→Reactions | `/api/v1/assets/:uuid/reactions/:reactionUuid` · POST (update) | Implemented | `updateAssetReaction` in api/reactions.ts |
| Asset→Comments | `/api/v1/assets/:uuid/comments` · POST | Implemented | `createCommentForAsset` in [api/comments.ts](../../../loom-ui/src/api/comments.ts) |
| Asset→Comments | `/api/v1/assets/:uuid/comments` · GET | Implemented | `listCommentsForAsset` in api/comments.ts |
| Asset→Detections | `/api/v1/assets/:uuid/detections` · POST | Implemented | `createDetection` in [api/detections.ts](../../../loom-ui/src/api/detections.ts) |
| Asset→Detections | `/api/v1/assets/:uuid/detections/bulk` · POST | Implemented | `bulkCreateDetections` in api/detections.ts |
| Asset→Detections | `/api/v1/assets/:uuid/detections/:detectionUuid` · DELETE | Implemented | `deleteDetection` in api/detections.ts |
| Asset→Detections | `/api/v1/assets/:uuid/detections` · GET | Implemented | `listAssetDetections` in api/detections.ts |
| Asset→Detections | `/api/v1/assets/:uuid/detections/:detectionUuid` · GET | Implemented | `loadDetection` in api/detections.ts |
| Asset→Detections | `/api/v1/assets/:uuid/detections/:detectionUuid` · POST (update) | Implemented | `updateDetection` in api/detections.ts |
| Asset→Transcripts | `/api/v1/assets/:uuid/transcripts` · POST | **Missing** | Only `listAssetTranscripts` in [api/transcripts.ts](../../../loom-ui/src/api/transcripts.ts) |
| Asset→Transcripts | `/api/v1/assets/:uuid/transcripts` · GET | Implemented | `listAssetTranscripts` in api/transcripts.ts |
| Asset→Transcripts | `/api/v1/assets/:uuid/transcripts/:transcriptUuid` · GET | **Missing** | No load-single fn |
| Asset→Transcripts | `/api/v1/assets/:uuid/transcripts/:transcriptUuid` · POST (update) | **Missing** | No update fn |
| Asset→Transcripts | `/api/v1/assets/:uuid/transcripts/:transcriptUuid` · DELETE | **Missing** | No delete fn |
| Asset→Binary | `/api/v1/assets/:uuid/binary` · POST (create meta) | **Missing** | UI only posts `/binary/data`; no meta-create fn |
| Asset→Binary | `/api/v1/assets/:uuid/binary` · GET | Implemented | `loadAssetBinaryMeta` in [api/binaries.ts](../../../loom-ui/src/api/binaries.ts) |
| Asset→Binary | `/api/v1/assets/:uuid/binary` · DELETE | Implemented | `deleteAssetBinary` in api/binaries.ts |
| Asset→Binary | `/api/v1/assets/:uuid/binary/data` · POST | Implemented | `uploadAssetBinary` in api/binaries.ts |
| Asset→Binary | `/api/v1/assets/:uuid/binary/data` · GET | Implemented | `fetchAssetBinaryBlob`/`downloadAssetBinary` in api/binaries.ts |
| Asset Component | `/api/v1/assets/:assetUuid/components` · GET (list) | **Missing** | No api module, no feature |
| Asset Component | `/api/v1/assets/:assetUuid/components` · POST (create) | **Missing** | No api module |
| Asset Component | `/api/v1/assets/:assetUuid/components/:compUuid` · GET | **Missing** | No api module |
| Asset Component | `/api/v1/assets/:assetUuid/components/:compUuid` · POST (update) | **Missing** | No api module |
| Asset Component | `/api/v1/assets/:assetUuid/components/:compUuid` · DELETE | **Missing** | No api module |
| Asset Pool | `/api/v1/pools` · POST (create) | Implemented | `createPool` in [api/pools.ts](../../../loom-ui/src/api/pools.ts); [features/assetPools/AssetPoolsView.tsx](../../../loom-ui/src/features/assetPools/AssetPoolsView.tsx) |
| Asset Pool | `/api/v1/pools/:uuid` · POST (update) | Implemented | `updatePool` in api/pools.ts |
| Asset Pool | `/api/v1/pools/:uuid` · DELETE | Implemented | `deletePool` in api/pools.ts |
| Asset Pool | `/api/v1/pools` · GET (list) | Implemented | `listPools` in api/pools.ts |
| Asset Pool | `/api/v1/pools/:uuid` · GET | Implemented | `loadPool` in api/pools.ts |
| Attachment | `/api/v1/attachments` · POST (create) | **Missing** | No api module, no feature |
| Attachment | `/api/v1/attachments/:uuid` · POST (update) | **Missing** | No api module |
| Attachment | `/api/v1/attachments/:uuid` · DELETE | **Missing** | No api module |
| Attachment | `/api/v1/attachments` · GET (list) | **Missing** | No api module |
| Attachment | `/api/v1/attachments/:uuid` · GET | **Missing** | No api module |
| Blacklist | `/api/v1/blacklists` · POST (create) | Implemented | `createBlacklist` in [api/blacklist.ts](../../../loom-ui/src/api/blacklist.ts); [features/admin/AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) `BlacklistAdmin` |
| Blacklist | `/api/v1/blacklists/:uuid` · POST (update) | **Partial** | `updateBlacklist` exists in api but no edit UI wired in `BlacklistAdmin` |
| Blacklist | `/api/v1/blacklists/:uuid` · DELETE | Implemented | `deleteBlacklist` in api/blacklist.ts |
| Blacklist | `/api/v1/blacklists` · GET (list) | Implemented | `listBlacklists` in api/blacklist.ts |
| Blacklist | `/api/v1/blacklists/:uuid` · GET | **Missing** | No `loadBlacklist` wiring (used table lists all) |
| Annotation | `/api/v1/annotations` · POST (create) | **Missing** | Only `listAnnotations`/`loadAnnotation` in [api/annotations.ts](../../../loom-ui/src/api/annotations.ts) |
| Annotation | `/api/v1/annotations/:uuid` · POST (update) | **Missing** | No update fn |
| Annotation | `/api/v1/annotations/:uuid` · DELETE | **Missing** | No delete fn |
| Annotation | `/api/v1/annotations` · GET (list) | Implemented | `listAnnotations` in api/annotations.ts |
| Annotation | `/api/v1/annotations/:uuid` · GET | Implemented | `loadAnnotation` in api/annotations.ts |
| Annotation→Reactions | `/api/v1/annotations/:annotationUuid/reactions` · POST | **Missing** | api/reactions.ts covers asset/task/comment only |
| Annotation→Reactions | `/api/v1/annotations/:annotationUuid/reactions/:reactionUuid` · DELETE | **Missing** | No annotation-reaction fn |
| Annotation→Reactions | `/api/v1/annotations/:annotationUuid/reactions` · GET | **Missing** | No annotation-reaction fn |
| Annotation→Reactions | `/api/v1/annotations/:annotationUuid/reactions/:reactionUuid` · GET | **Missing** | No annotation-reaction fn |
| Annotation→Reactions | `/api/v1/annotations/:annotationUuid/reactions/:reactionUuid` · POST (update) | **Missing** | No annotation-reaction fn |
| Asset Binary (standalone) | `/api/v1/binaries` · POST (create) | **Missing** | UI uses only asset sub-resource; likely internal |
| Asset Binary (standalone) | `/api/v1/binaries/:uuid` · POST (update) | **Missing** | No `/binaries` fn |
| Asset Binary (standalone) | `/api/v1/binaries/:uuid` · DELETE | **Missing** | No `/binaries` fn |
| Asset Binary (standalone) | `/api/v1/binaries` · GET (list) | **Missing** | No `/binaries` fn |
| Asset Binary (standalone) | `/api/v1/binaries/:uuid` · GET | **Missing** | No `/binaries` fn |
| Asset Location | *(no dedicated REST endpoint — embedded in `AssetResponse`)* | Read-only | `AssetLocationInfo` displayed in [features/assetDetail/AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx); no REST surface for write/lock |
| Asset Remix | *(no REST endpoint, no UI, no UI domain type)* | No REST surface | `asset_remix` table exists in [DOMAIN.md](../DOMAIN.md) group 2 but no endpoint is registered |

**Totals:** 77 REST operations enumerated (75 addressable + 2 no-surface entities noted).
34 gaps (Missing/Partial) across 9 tasks below.

---

## Task: Add full Annotation authoring (create / update / delete) and annotation reactions to the UI

**Argumentation Summary:** Annotations (`FEEDBACK`, `TAG`, `CHAPTER` — time-/area-scoped
markers per [DOMAIN.md](../DOMAIN.md) group 2) are a first-class user-facing entity, yet the
UI treats them as read-only: `AnnotationItem`/`VideoTimeline` render annotations pulled from
the embedded `resp.annotations` of the asset response, and [api/annotations.ts](../../../loom-ui/src/api/annotations.ts)
exposes only `listAnnotations` and `loadAnnotation`. There is no way to create, edit, or delete
an annotation, and the entire annotation-reactions sub-resource (5 routes) is unimplemented.
This is the single largest user-facing gap in the Assets & Media surface.

**Improvement Summary:** Give reviewers the ability to author annotations directly on the media
(add a marker from the timeline / image area, edit its text/area/time, delete it) and to react
to annotations, matching the reaction UX already present for assets/tasks/comments.

```
Endpoints to wire (from AnnotationEndpoint.java, basePath /api/v1/annotations):
  POST   /api/v1/annotations                                   -> createAnnotation
  POST   /api/v1/annotations/:uuid                             -> updateAnnotation
  DELETE /api/v1/annotations/:uuid                             -> deleteAnnotation
  POST   /api/v1/annotations/:annotationUuid/reactions                  -> createAnnotationReaction
  DELETE /api/v1/annotations/:annotationUuid/reactions/:reactionUuid    -> deleteAnnotationReaction
  GET    /api/v1/annotations/:annotationUuid/reactions                  -> listAnnotationReactions
  GET    /api/v1/annotations/:annotationUuid/reactions/:reactionUuid    -> loadAnnotationReaction
  POST   /api/v1/annotations/:annotationUuid/reactions/:reactionUuid    -> updateAnnotationReaction

UI work:
  - Extend loom-ui/src/api/annotations.ts with createAnnotation / updateAnnotation /
    deleteAnnotation using the AnnotationResponseItem + AreaInfo types already defined there.
  - Add annotation-reaction fns to loom-ui/src/api/reactions.ts (mirror the existing
    createCommentReaction/listCommentReactions/updateCommentReaction/deleteCommentReaction shape).
  - In loom-ui/src/features/assetDetail/ (AnnotationItem.tsx, VideoTimeline.tsx, AssetDetail.tsx):
    * add "new annotation" affordance (drag/select a time range or image area -> AreaInfo),
      type selector (FEEDBACK/TAG/CHAPTER), text field.
    * edit + delete controls on AnnotationItem (respect UPDATE_ANNOTATION/DELETE_ANNOTATION perms).
    * reaction bar on each annotation (reuse reactions/ReactionsPanel or CommentReactionBar).
  - After create/update/delete, refresh from the annotations API instead of relying solely on
    the embedded asset-response annotations.

Edge cases:
  - Annotation with area (AreaInfo) vs. time-only vs. whole-asset scope.
  - Video (time range) vs. image (bbox area) vs. audio.
  - Optimistic add then reconcile with server uuid.
  - Permission gating: hide authoring controls without CREATE/UPDATE/DELETE_ANNOTATION.
```

**References:**
- [loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AnnotationEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AnnotationEndpoint.java)
- [loom-ui/src/api/annotations.ts](../../../loom-ui/src/api/annotations.ts)
- [loom-ui/src/api/reactions.ts](../../../loom-ui/src/api/reactions.ts)
- [loom-ui/src/features/assetDetail/AnnotationItem.tsx](../../../loom-ui/src/features/assetDetail/AnnotationItem.tsx)
- [loom-ui/src/features/assetDetail/VideoTimeline.tsx](../../../loom-ui/src/features/assetDetail/VideoTimeline.tsx)
- [spec/loom/DOMAIN.md](../DOMAIN.md) (group 2, Annotation)

**Test Requirements:**
- api unit tests for the 3 annotation CRUD fns + 5 reaction fns (mirror annotations/reactions test patterns).
- Component test: creating an annotation from the timeline calls `createAnnotation` and renders the new marker.
- Component test: edit and delete round-trip; reaction add/remove updates the count.
- Permission test: authoring controls hidden without the relevant permission.

---

## Task: Add transcript editing sub-resource operations (create / load-single / update / delete)

**Argumentation Summary:** The transcript sub-resource exposes 5 routes, but the UI only
implements `listAssetTranscripts` (GET). `TranscriptPanel` displays sections and even has an
`onSectionsChange` callback, yet there is no api function to persist a create, update, or delete,
and no way to load a single transcript. Transcript correction/authoring is a core media workflow
that currently cannot be saved.

**Improvement Summary:** Let users create, correct, and remove transcripts from the asset detail
view with changes persisted to the backend.

```
Endpoints (AssetEndpoint.java, basePath /api/v1/assets):
  POST   /api/v1/assets/:uuid/transcripts                       -> createTranscript
  GET    /api/v1/assets/:uuid/transcripts/:transcriptUuid       -> loadTranscript
  POST   /api/v1/assets/:uuid/transcripts/:transcriptUuid       -> updateTranscript
  DELETE /api/v1/assets/:uuid/transcripts/:transcriptUuid       -> deleteTranscript

UI work:
  - Extend loom-ui/src/api/transcripts.ts (reuse TranscriptResponse/TranscriptSectionResponse/
    TranscriptWordResponse types already defined) with create/load/update/delete fns.
  - Wire loom-ui/src/features/assetDetail/TranscriptPanel.tsx onSectionsChange to updateTranscript,
    add "add transcript" and per-transcript delete affordances in AssetDetail.tsx.

Edge cases:
  - Section/word timing edits vs. text-only edits.
  - Multiple transcripts per asset (different source/language) — pick which to edit.
  - Empty transcript creation vs. attaching to an asset with no audio.
```

**References:**
- [loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (transcript routes ~320-350)
- [loom-ui/src/api/transcripts.ts](../../../loom-ui/src/api/transcripts.ts)
- [loom-ui/src/features/assetDetail/TranscriptPanel.tsx](../../../loom-ui/src/features/assetDetail/TranscriptPanel.tsx)

**Test Requirements:**
- api unit tests for create/load/update/delete transcript.
- Component test: editing a section and blurring persists via `updateTranscript`.
- Component test: delete removes the transcript from the panel.

---

## Task: Add an Asset Component (per-modality metadata) API module and viewer

**Argumentation Summary:** The Asset Component endpoint (`/api/v1/assets/:assetUuid/components`,
5 routes) exposes the per-modality extracted metadata (`asset_*_comp` — geo, doc, image, video,
audio, transcript, json; produced by Cortex nodes per [DOMAIN.md](../DOMAIN.md)). The UI has no
api module and no feature that lists or displays these components; users cannot see or manage the
extracted metadata attached to an asset.

**Improvement Summary:** Surface the extracted per-modality metadata on the asset detail view and
allow authorized users to manage components.

```
Endpoints (AssetComponentEndpoint.java, basePath /api/v1/assets/:assetUuid/components):
  GET    /api/v1/assets/:assetUuid/components               -> listComponents
  POST   /api/v1/assets/:assetUuid/components               -> createComponent
  GET    /api/v1/assets/:assetUuid/components/:compUuid     -> loadComponent
  POST   /api/v1/assets/:assetUuid/components/:compUuid     -> updateComponent
  DELETE /api/v1/assets/:assetUuid/components/:compUuid     -> deleteComponent

UI work:
  - New loom-ui/src/api/assetComponents.ts with the 5 fns + response types (component `source`
    tag and per-modality payload).
  - New "Components / Metadata" panel in loom-ui/src/features/assetDetail/AssetDetail.tsx listing
    components grouped by modality/source, with view (and, gated by permissions, edit/delete).

Edge cases:
  - Heterogeneous payloads per component kind (geo vs. json vs. transcript).
  - Multiple components of the same kind distinguished by `source`.
  - Read-heavy: most components are Cortex-produced; confirm whether create/update should be
    exposed in UI or read-only.
```

**References:**
- [loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetComponentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetComponentEndpoint.java)
- [spec/loom/DOMAIN.md](../DOMAIN.md) (group 2, Asset Component)
- [loom-ui/src/features/assetDetail/AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx)

**Test Requirements:**
- api unit tests for the 5 component fns.
- Component test: components panel renders a list grouped by source/modality.
- Component test (if write exposed): delete removes a component and re-fetches.

---

## Task: Add an Attachment API module and management UI

**Argumentation Summary:** The Attachment endpoint (`/api/v1/attachments`, 5 CRUD routes) manages
auxiliary binaries (asset thumbnails, embedding attachments per [DOMAIN.md](../DOMAIN.md)). The UI
has no api module for it; `Attachment` appears only as a permission-label string in
[AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx). No listing, upload, or deletion
of attachments is possible.

**Improvement Summary:** Provide attachment CRUD so users/admins can view and manage asset
thumbnails and other auxiliary binaries.

```
Endpoints (AttachmentEndpoint.java, basePath /api/v1/attachments):
  POST   /api/v1/attachments               -> createAttachment
  GET    /api/v1/attachments               -> listAttachments (paged)
  GET    /api/v1/attachments/:uuid         -> loadAttachment
  POST   /api/v1/attachments/:uuid         -> updateAttachment
  DELETE /api/v1/attachments/:uuid         -> deleteAttachment

UI work:
  - New loom-ui/src/api/attachments.ts with 5 fns + response types.
  - Surface attachments where relevant: an attachments list/section on the asset detail view
    and/or an admin table (mirror the existing BlacklistAdmin pattern in AdminArea.tsx).
  - Gate with CREATE/READ/UPDATE/DELETE_ATTACHMENT permissions (already referenced in AdminArea).

Edge cases:
  - Binary payload upload (attachment_binary) vs. metadata-only update.
  - Attachment linked to Asset vs. Embedding target.
```

**References:**
- [loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AttachmentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AttachmentEndpoint.java)
- [spec/loom/DOMAIN.md](../DOMAIN.md) (group 2, Attachment)
- [loom-ui/src/features/admin/AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx)

**Test Requirements:**
- api unit tests for the 5 attachment fns.
- Component test: list renders, create uploads, delete removes an attachment.
- Permission gating test.

---

## Task: Add SHA-512 hash-based asset operations to the UI

**Argumentation Summary:** The asset endpoint exposes a full hash-addressed operation set at
`/api/v1/assets/sha512/:sha512` (GET, POST, PATCH, PUT, DELETE — 5 routes; content-addressed
lookup is a defining feature of the Asset entity per [RESTAPI.md](../RESTAPI.md) §3.3). The UI
references `sha512` only as a response/request field ([api/assets.ts](../../../loom-ui/src/api/assets.ts))
and has no function to look up, update, or delete an asset by its hash — preventing dedup checks
and hash-based navigation.

**Improvement Summary:** Enable hash-based lookup (e.g. "does this file already exist?" during
upload) and hash-addressed load/update/delete.

```
Endpoints (AssetEndpoint.java, basePath /api/v1/assets):
  GET    /api/v1/assets/sha512/:sha512      -> loadAssetBySha512
  POST   /api/v1/assets/sha512/:sha512      -> updateAssetBySha512
  PATCH  /api/v1/assets/sha512/:sha512      -> patchAssetBySha512
  PUT    /api/v1/assets/sha512/:sha512      -> replaceAssetBySha512
  DELETE /api/v1/assets/sha512/:sha512      -> deleteAssetBySha512

UI work:
  - Add the 5 fns to loom-ui/src/api/assets.ts alongside the existing uuid-based fns.
  - Use loadAssetBySha512 for a pre-upload duplicate check in the upload flow
    (features/assets/ upload path / AssetBrowser) — the uploadAsset comment already notes sha512
    is required for create.

Edge cases:
  - 404 (hash not found) as the "not a duplicate" signal.
  - Hash normalization (case, length) before request.
```

**References:**
- [loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (sha512 routes ~121-158)
- [spec/loom/RESTAPI.md](../RESTAPI.md) (Asset SHA-512 row, §3.3)
- [loom-ui/src/api/assets.ts](../../../loom-ui/src/api/assets.ts)

**Test Requirements:**
- api unit tests for the 5 sha512 fns.
- Test: pre-upload lookup returns existing asset on hash match and treats 404 as new.

---

## Task: Support PATCH (partial update) and PUT (replace) semantics for assets

**Argumentation Summary:** `/api/v1/assets/:uuid` supports POST, PATCH (partial — only fields
present are modified), and PUT (replace — all replaceable fields required). The UI only calls POST
via `updateAsset` ([api/assets.ts](../../../loom-ui/src/api/assets.ts)); PATCH and PUT are
unavailable, so the UI cannot do a true partial merge or a full replace where those differ from
the POST update semantics.

**Improvement Summary:** Expose partial-update and replace helpers so edit flows can pick the
correct semantics (e.g. single-field inline edits via PATCH).

```
Endpoints (AssetEndpoint.java):
  PATCH  /api/v1/assets/:uuid   -> patchAsset (only present fields modified)
  PUT    /api/v1/assets/:uuid   -> replaceAsset (all replaceable fields required)

UI work:
  - Add patchAsset and replaceAsset to loom-ui/src/api/assets.ts (reuse AssetUpdateRequest;
    PATCH may take a Partial<>).
  - Use PATCH for inline single-field edits in AssetDetail/AssetMetadata to avoid clobbering.

Edge cases:
  - PUT requires all replaceable fields — validate before sending.
  - Confirm server-side difference between POST-update and PATCH before choosing per edit.
```

**References:**
- [loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (~184-193)
- [loom-ui/src/api/assets.ts](../../../loom-ui/src/api/assets.ts)

**Test Requirements:**
- api unit tests for patchAsset and replaceAsset.
- Test: PATCH sends only changed fields; PUT rejects when a required field is missing.

---

## Task: Add Blacklist edit (update) and single-entry load to the admin UI

**Argumentation Summary:** `BlacklistAdmin` in [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx)
wires `listBlacklists`, `createBlacklist`, and `deleteBlacklist`, but `updateBlacklist` (POST
`/api/v1/blacklists/:uuid`) exists in [api/blacklist.ts](../../../loom-ui/src/api/blacklist.ts) yet
is never called — there is no edit affordance — and the single-entry GET is unused. Blacklist
entries carry an editable review count/name that cannot currently be modified.

**Improvement Summary:** Allow editing an existing blacklist entry (e.g. name / review count)
rather than only create+delete.

```
Endpoints (BlacklistEndpoint.java, basePath /api/v1/blacklists):
  POST   /api/v1/blacklists/:uuid   -> updateBlacklist  (api fn exists, UI not wired)
  GET    /api/v1/blacklists/:uuid   -> loadBlacklist    (add if a detail/edit dialog needs it)

UI work:
  - Add an edit row/dialog in BlacklistAdmin that calls the existing updateBlacklist.
  - Optionally use loadBlacklist to populate the edit dialog.
```

**References:**
- [loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/BlacklistEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/BlacklistEndpoint.java)
- [loom-ui/src/api/blacklist.ts](../../../loom-ui/src/api/blacklist.ts)
- [loom-ui/src/features/admin/AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) (`BlacklistAdmin`, ~1128)

**Test Requirements:**
- Component test: editing a blacklist entry calls `updateBlacklist` and refreshes the table.

---

## Task: Support explicit binary-metadata creation (POST /assets/:uuid/binary)

**Argumentation Summary:** The binary sub-resource has a distinct route to create the binary
*metadata* record — POST `/api/v1/assets/:uuid/binary` — separate from POST `/binary/data`
(raw-bytes upload). The UI ([api/binaries.ts](../../../loom-ui/src/api/binaries.ts)) implements
`/binary/data`, GET `/binary`, and DELETE `/binary`, but not the metadata-create route, so a
workflow that registers binary metadata (filesystem path / S3 pointer) without streaming bytes has
no UI path.

**Improvement Summary:** Add a helper to create/register binary metadata directly (e.g. pointing an
asset at an already-present filesystem/S3 object).

```
Endpoint (AssetEndpoint.java): POST /api/v1/assets/:uuid/binary -> createBinaryMeta
UI work:
  - Add createAssetBinaryMeta to loom-ui/src/api/binaries.ts (payload: filesystem/S3 location).
  - Optional UI: "register existing binary" action in AssetDetail binary section.
Edge cases:
  - Asset that already has a binary (replace vs. reject).
  - libraryUuid requirement when no binary exists yet (mirrors /binary/data contract).
```

**References:**
- [loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (~360)
- [loom-ui/src/api/binaries.ts](../../../loom-ui/src/api/binaries.ts)

**Test Requirements:**
- api unit test for createAssetBinaryMeta.

---

## Task: Decide on / expose the standalone Asset Binary endpoint (`/api/v1/binaries`)

**Argumentation Summary:** A standalone [AssetBinaryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetBinaryEndpoint.java)
registers 5 routes at `/api/v1/binaries` (create, list, load, update, delete) that are entirely
separate from the asset `:uuid/binary` sub-resource the UI uses. No UI code references `/binaries`.
This is likely an internal/administrative surface, but it is a real, unaddressed REST surface and
should be either exposed or explicitly documented as internal.

**Improvement Summary:** Either add a thin admin listing over `/api/v1/binaries` (browse orphaned /
all binary records) or record it as an intentionally internal endpoint so the gap is closed by
decision.

```
Endpoints (AssetBinaryEndpoint.java, basePath /api/v1/binaries):
  POST /api/v1/binaries ; GET /api/v1/binaries ; GET/POST/DELETE /api/v1/binaries/:uuid

Action:
  - Confirm intended audience with backend owners.
  - If user-facing: new loom-ui/src/api/binariesAdmin.ts + admin table.
  - If internal-only: note in spec (RESTAPI.md / LOOM_UI.md) as "no UI by design".
```

**References:**
- [loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetBinaryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetBinaryEndpoint.java)
- [spec/loom/RESTAPI.md](../RESTAPI.md)

**Test Requirements:**
- If exposed: api unit tests for the 5 `/binaries` fns and a listing component test.
- If internal: spec note added; no UI test.

---

## Notes on no-REST-surface entities

- **Asset Location** (`asset_location`): no dedicated REST endpoint is registered; the location
  data (path, filekey, pool, lock, state) is embedded in `AssetResponse` and rendered read-only via
  `AssetLocationInfo` in [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx).
  The response carries a `lockedBy` field but there is no lock/unlock or relocate operation on either
  side. If lock management or relocation becomes a requirement, it needs a backend endpoint first —
  record as a backend gap, not a UI gap.
- **Asset Remix** (`asset_remix`, "derivation/relation link between two assets" per
  [DOMAIN.md](../DOMAIN.md)): no REST endpoint and no UI type exist. If asset-to-asset
  derivation/relation browsing is desired, it requires a backend endpoint first. No UI gap can be
  written against a non-existent surface — flagged here for product/backend decision.
