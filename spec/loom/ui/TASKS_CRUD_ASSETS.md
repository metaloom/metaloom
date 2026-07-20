# MetaLoom — CRUD Coverage Tasks: Assets & Media

> Gaps between the REST API and the Loom UI (`loom-ui/`) for the Assets & Media domain:
> **Asset, Asset Location, Asset Pool, Asset Component, Asset Remix, Attachment, Blacklist, Annotation**.
> Derived from REST endpoints, UI api clients/features, and e2e specs.
> Format follows [../../TASKS.template.md](../../TASKS.template.md). See [../DOMAIN.md](../DOMAIN.md).

## Coverage Summary

| Element | Create | Read | Update | Delete | E2E | Note |
|---|---|---|---|---|---|---|
| Asset | ⚠️ | ✅ | ⚠️ | ⚠️ | ⚠️ | `createAsset`/`updateAsset`/`deleteAsset` clients exist in [assets.ts](../../../loom-ui/src/api/assets.ts) but are **never invoked** — no upload/edit/delete UI. E2E is read-only. |
| Asset Binary | ❌ | ❌ | ❌ | ❌ | ❌ | REST `POST/GET/DELETE /assets/:uuid/binary` + full `/binaries` CRUD; **no UI client at all**, `thumbnailUrl` is hard-coded `""`. |
| Asset Location | — | ⚠️ | — | — | ❌ | No dedicated REST endpoint; embedded as `locations[]` in the asset response but typed `unknown[]` and never rendered. |
| Asset Pool | ✅ | ✅ | ✅ | ✅ | ✅ | Fully covered: [pools.ts](../../../loom-ui/src/api/pools.ts) + [AssetPoolsView.tsx](../../../loom-ui/src/features/assetPools/AssetPoolsView.tsx) + [pools-backend.spec.ts](../../../loom-ui/e2e/pools-backend.spec.ts). No gap. |
| Asset Component | ❌ | ⚠️ | ❌ | ❌ | ❌ | REST full CRUD at `/assets/:assetUuid/components`; **no UI client**. Only image/video/audio/document sub-info is read from the embedded asset response; geo/json never surfaced; no create/update/delete. |
| Asset Component (Transcript) | ❌ | ✅ | ❌ | ❌ | ❌ | REST full CRUD at `/assets/:uuid/transcripts`; [transcripts.ts](../../../loom-ui/src/api/transcripts.ts) only exposes `listAssetTranscripts`. Read-only. |
| Asset Remix | N/A | N/A | N/A | N/A | N/A | **Not exposed by REST at all** (only `JooqAssetRemix` DB tables). No UI gap — flagged for backend, out of scope for UI CRUD. |
| Attachment | ❌ | ❌ | ❌ | ❌ | ❌ | REST full CRUD at `/attachments`; **no UI client**, no thumbnail/attachment integration. |
| Blacklist | ✅ | ✅ | ❌ | ✅ | ❌ | [blacklist.ts](../../../loom-ui/src/api/blacklist.ts) has list/create/delete wired in [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx); **no `updateBlacklist` wired**, no "blacklist this asset" action from detail. No e2e. |
| Annotation | ❌ | ✅ | ❌ | ❌ | ❌ | REST full CRUD at `/annotations`; [annotations.ts](../../../loom-ui/src/api/annotations.ts) only exposes `listAnnotations`/`loadAnnotation`. Annotations render in detail but time/area drag edits update local state only (not persisted). No e2e. |

Legend: ✅ covered · ⚠️ partial / client present but unwired · ❌ missing · — not applicable

## Task: Add Asset Binary upload and download support

**Argumentation Summary:** REST exposes binary handling in two places: `POST /assets/:uuid/binary` (create), `GET /assets/:uuid/binary` (load), `DELETE /assets/:uuid/binary` (delete) in [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (lines 327–349), plus a standalone [AssetBinaryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetBinaryEndpoint.java) with full CRUD/list at `/binaries`. The UI has **no binary client whatsoever** — there is no `binaries.ts`, and both [AssetBrowser.tsx](../../../loom-ui/src/features/assets/AssetBrowser.tsx) and [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) set `thumbnailUrl: ""` / rely on an `asset.url` that is never populated. A user cannot upload a binary for a registered asset nor download/preview the original.

**Improvement Summary:** Create a binary API client and wire upload (multipart POST) + download/preview into the asset detail (and optionally an upload-with-binary path in the browser).

```
1. New client loom-ui/src/api/binaries.ts:
   - uploadAssetBinary(token, assetUuid, File) → POST /assets/:uuid/binary (multipart)
   - getAssetBinaryUrl(assetUuid) / fetchAssetBinary(token, assetUuid) → GET /assets/:uuid/binary
   - deleteAssetBinary(token, assetUuid) → DELETE /assets/:uuid/binary
   - Optionally standalone list/load against /binaries (AssetBinaryEndpoint).
2. AssetDetail: use the binary GET as the media <img>/<video> src (replace the
   empty thumbnailUrl / unresolved asset.url around lines 323, 345), and add
   Upload / Replace / Remove binary controls.
3. AssetBrowser: populate tile thumbnails from the binary endpoint instead of "".
```

**References:**
- REST: [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (327–349), [AssetBinaryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetBinaryEndpoint.java)
- UI: (no client yet) [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx), [AssetBrowser.tsx](../../../loom-ui/src/features/assets/AssetBrowser.tsx)

**Test Requirements:**
- e2e: upload a binary to an asset, assert preview/thumbnail renders, then delete it.
- Unit test the multipart request construction in `binaries.ts`.

---

## Task: Add Asset Component CRUD client and per-modality UI

**Argumentation Summary:** [AssetComponentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetComponentEndpoint.java) exposes full CRUD at `/assets/:assetUuid/components` — list, create, load, update, delete — covering all modalities (`asset_geo_comp`, `asset_doc_comp`, `asset_image_comp`, `asset_video_comp`, `asset_audio_comp`, `asset_json_comp`). The UI has **no component client**. [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) only reads the flattened `imageComponents`/`videoComponents`/`audioComponents`/`documentComponents` arrays embedded in the asset response ([assets.ts](../../../loom-ui/src/api/assets.ts) lines 87–90); geo and generic JSON components are never surfaced, and there is no way to create, edit, or delete any component.

**Improvement Summary:** Add an asset-components API client and a component panel in the asset detail that lists all modality components (including geo + json), tagged by `source`, with create/update/delete.

```
1. New client loom-ui/src/api/assetComponents.ts:
   - listAssetComponents(token, assetUuid) → GET  /assets/:assetUuid/components
   - loadAssetComponent(token, assetUuid, compUuid) → GET  .../:compUuid
   - createAssetComponent(token, assetUuid, req) → POST /assets/:assetUuid/components
   - updateAssetComponent(token, assetUuid, compUuid, req) → POST .../:compUuid
   - deleteAssetComponent(token, assetUuid, compUuid) → DELETE .../:compUuid
   Type the union of modality payloads (geo/doc/image/video/audio/json).
2. AssetDetail: add a "Components" section/tab that lists components by modality
   and source, with edit + delete, and a create action per modality.
```

**References:**
- REST: [AssetComponentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetComponentEndpoint.java)
- UI: (no client yet) [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx), embedded read model in [assets.ts](../../../loom-ui/src/api/assets.ts) (87–90)

**Test Requirements:**
- e2e: create a component on an asset, verify it lists in detail, edit it, delete it.
- Unit test client request/response mapping for each modality.

---

## Task: Extend Transcript component client to create/update/delete

**Argumentation Summary:** [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (lines 287–323) exposes full transcript CRUD on an asset: `POST /assets/:uuid/transcripts` (create), `GET` (list), `GET .../:transcriptUuid` (load), `POST .../:transcriptUuid` (update), `DELETE .../:transcriptUuid`. The UI client [transcripts.ts](../../../loom-ui/src/api/transcripts.ts) only implements `listAssetTranscripts`, and [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) (line 123) / TranscriptPanel render transcripts read-only. Users cannot add, correct, or remove a transcript (e.g. fix an auto-generated transcript's text/sections).

**Improvement Summary:** Add load/create/update/delete transcript methods and expose transcript editing (text + sections/words) and deletion in the transcript panel.

```
1. transcripts.ts: add loadAssetTranscript, createAssetTranscript,
   updateAssetTranscript, deleteAssetTranscript against
   /assets/:uuid/transcripts[/:transcriptUuid].
2. TranscriptPanel (features/assetDetail/TranscriptPanel.tsx): add edit mode
   (transcriptText, sections) with Save, an Add-transcript action, and Delete.
```

**References:**
- REST: [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (287–323)
- UI client: [transcripts.ts](../../../loom-ui/src/api/transcripts.ts) (`listAssetTranscripts` 62)
- UI: [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) (123), [TranscriptPanel.tsx](../../../loom-ui/src/features/assetDetail/TranscriptPanel.tsx)

**Test Requirements:**
- e2e: create/edit/delete a transcript on an asset.
- Unit test the update request payload (sections/words) mapping.

---

## Task: Add Attachment CRUD client and thumbnail integration

**Argumentation Summary:** [AttachmentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AttachmentEndpoint.java) exposes full CRUD at `/attachments` (create, update, delete, list, load) — attachments back asset thumbnails and embedding attachments (`attachment`, `attachment_binary` tables). The UI has **no attachment client** and no thumbnail/attachment integration at all: [AssetBrowser.tsx](../../../loom-ui/src/features/assets/AssetBrowser.tsx) (line 77) and [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) (line 154) hard-code `thumbnailUrl: ""`.

**Improvement Summary:** Add an attachments API client and use it to render/manage asset thumbnails (and other attachments) in the browser tiles and detail view.

```
1. New client loom-ui/src/api/attachments.ts:
   - listAttachments / loadAttachment / createAttachment / updateAttachment /
     deleteAttachment against /attachments[/:uuid].
2. Wire thumbnail resolution: populate AssetBrowser tile thumbnails and
   AssetDetail preview from the appropriate attachment (thumbnail) instead of "".
3. Add an attachments management section in AssetDetail (upload/replace/delete).
```

**References:**
- REST: [AttachmentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AttachmentEndpoint.java)
- UI: (no client yet) [AssetBrowser.tsx](../../../loom-ui/src/features/assets/AssetBrowser.tsx) (77), [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) (154)

**Test Requirements:**
- e2e: create an attachment, assert thumbnail renders, update and delete it.
- Unit test the attachment client and thumbnail URL resolution.

---

## Task: Add Annotation create/update/delete and persist time/area edits

**Argumentation Summary:** [AnnotationEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AnnotationEndpoint.java) exposes full CRUD at `/annotations`: `POST` (create), `POST /:uuid` (update), `DELETE /:uuid`, list, load — annotations carry `type` (FEEDBACK/TAG/CHAPTER), `area` (time from/to + spatial startX/startY/width/height), asset link, tags, and tasks. The UI client [annotations.ts](../../../loom-ui/src/api/annotations.ts) only implements `listAnnotations` and `loadAnnotation`. [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) renders annotations (extracted from the asset response, line 81; overlay/timeline markers) and even lets the user **drag time/area handles (lines 363–375) — but only mutates local React state via `setAnnotations`, never persisting**. There is no create-annotation affordance and no delete. So annotation edits are silently lost.

**Improvement Summary:** Add create/update/delete methods to the annotations client and wire them so new annotations can be added (with time/area), handle-drag edits are persisted, and annotations can be deleted.

```
1. annotations.ts: add
   - createAnnotation(token, req) → POST /annotations  (req: type, assetUuid,
     title, description, area{from,to,startX,startY,width,height})
   - updateAnnotation(token, uuid, req) → POST /annotations/:uuid
   - deleteAnnotation(token, uuid) → DELETE /annotations/:uuid
2. AssetDetail:
   - Add "Add annotation" action (from timeline selection or image region) that
     calls createAnnotation with time/area.
   - On handle drag-end (lines 363–375) call updateAnnotation to persist the new
     area instead of only setAnnotations(...).
   - Add delete on AnnotationItem (features/assetDetail/AnnotationItem.tsx).
```

**References:**
- REST: [AnnotationEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AnnotationEndpoint.java) (54–95)
- UI client: [annotations.ts](../../../loom-ui/src/api/annotations.ts) (read-only)
- UI: [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) (81, 363–375, 598–610), [AnnotationItem.tsx](../../../loom-ui/src/features/assetDetail/AnnotationItem.tsx)

**Test Requirements:**
- e2e (new `annotations-backend.spec.ts`): create an annotation with time/area, edit it, delete it, assert persistence across reload.
- Unit test the create/update request payload including the `area` object.

---

## Task: Add Blacklist update and a "blacklist this asset" action

**Argumentation Summary:** [BlacklistEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/BlacklistEndpoint.java) exposes create, **update (`POST /blacklists/:uuid`)**, delete, list, and load. The UI client [blacklist.ts](../../../loom-ui/src/api/blacklist.ts) implements `updateBlacklist` and `loadBlacklist`, but [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) (lines 1086–1108) only wires `listBlacklists`, `createBlacklist`, and `deleteBlacklist` — there is **no edit UI** for an existing entry. Additionally, blacklisting is only reachable via the admin screen (typing a name + assetUuid); there is no "Blacklist this asset" action in [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx), which is the natural place to block a specific asset.

**Improvement Summary:** Wire the existing `updateBlacklist` into an edit dialog in AdminArea, and add a "Blacklist" action in the asset detail that pre-fills `assetUuid` and calls `createBlacklist`.

```
1. AdminArea blacklist section (~1080–1110): add an Edit action per row opening a
   dialog that calls updateBlacklist(token, uuid, {name, assetUuid, meta}).
2. AssetDetail: add a "Blacklist this asset" action (overflow menu) that calls
   createBlacklist(token, { name, assetUuid: <current asset uuid> }); optionally
   reflect blacklisted state in the detail header.
```

**References:**
- REST: [BlacklistEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/BlacklistEndpoint.java) (58–64 update)
- UI client: [blacklist.ts](../../../loom-ui/src/api/blacklist.ts) (`updateBlacklist` 90 — currently unused)
- UI: [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) (1086–1108), [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx)

**Test Requirements:**
- e2e (new `blacklist-backend.spec.ts`): create → edit → delete a blacklist entry via admin; blacklist an asset from detail.
- Unit test the update request mapping.

---

## Task: Display Asset Location information in the asset detail

**Argumentation Summary:** There is no dedicated Asset Location REST endpoint; locations are returned embedded in the asset response ([assets.ts](../../../loom-ui/src/api/assets.ts) line 91: `locations?: unknown[]`) and built server-side by [AssetLocationModelBuilder.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/builder/AssetLocationModelBuilder.java). The binary lifecycle (create/delete) is what effectively manages the single physical location. The UI types `locations` as `unknown[]` and never renders it, so path / pool / filekey / state / license information the API already returns is invisible to the user.

**Improvement Summary:** Type the location model and render an asset's storage location(s) (pool, path, filekey, lock/state, license) in the asset detail, linking the pool to [AssetPoolsView.tsx](../../../loom-ui/src/features/assetPools/AssetPoolsView.tsx).

```
1. assets.ts: replace `locations?: unknown[]` with a typed AssetLocationInfo[]
   matching AssetLocationModelBuilder (path, filekey, pool ref, state, lock,
   license).
2. AssetDetail: add a "Location(s)" section showing pool + path + state, with a
   link to the pool in the Asset Pools view.
```

**References:**
- REST model: [AssetLocationModelBuilder.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/builder/AssetLocationModelBuilder.java); embedded via [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) load routes
- UI: [assets.ts](../../../loom-ui/src/api/assets.ts) (91), [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx)

**Test Requirements:**
- Unit test the location model mapping.
- e2e: assert location/pool info renders for an asset that has a binary/location.

---

## Task: Add e2e coverage for Asset write operations and missing domain specs

**Argumentation Summary:** [assets-backend.spec.ts](../../../loom-ui/e2e/assets-backend.spec.ts) covers only read paths (list, navigate to detail, metadata display, search). There is **no e2e spec** for annotations, blacklist, attachments, asset components, or asset binary, and no coverage of asset create/update/delete. Only [pools-backend.spec.ts](../../../loom-ui/e2e/pools-backend.spec.ts) fully exercises write CRUD. As the tasks above add UI write capabilities, matching backend e2e specs are required to prevent regressions.

**Improvement Summary:** Add/extend e2e specs so every UI write operation in this domain has backend coverage.

```
Add or extend under loom-ui/e2e/:
- assets-backend.spec.ts: asset create → edit metadata → delete; binary upload →
  preview → delete.
- annotations-backend.spec.ts: create/edit(time+area)/delete annotation on asset.
- blacklist-backend.spec.ts: create/edit/delete entry; blacklist-from-asset.
- components-backend.spec.ts: create/edit/delete a component per modality; transcript edit.
- attachments-backend.spec.ts: attachment create → thumbnail render → delete.
Mirror the structure of pools-backend.spec.ts (create → assert → cleanup).
```

**References:**
- Existing: [assets-backend.spec.ts](../../../loom-ui/e2e/assets-backend.spec.ts), [pools-backend.spec.ts](../../../loom-ui/e2e/pools-backend.spec.ts)

**Test Requirements:**
- New specs must run against the real backend and clean up created entities.
