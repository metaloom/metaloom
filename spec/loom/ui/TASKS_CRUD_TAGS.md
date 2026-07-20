# MetaLoom — CRUD Coverage Tasks: Tags

> Gaps between the REST API and the Loom UI (`loom-ui/`) for the Tag domain.
> Derived from REST endpoints, UI api clients/features, and e2e specs.
> Format follows [../../TASKS.template.md](../../TASKS.template.md). See [../DOMAIN.md](../DOMAIN.md).

## Coverage Summary

Legend: ✅ done · ⚠️ partial · ❌ missing

| Aspect | Create | Read | Update | Delete | E2E | Note |
|--------|--------|------|--------|--------|-----|------|
| **Tag core** (name/collection) | ✅ | ✅ | ✅ | ✅ | ⚠️ | REST + UI full CRUD; e2e covers create/list/read/delete but **not update** (edit-save, drag-reparent) |
| **Tag color** | ❌ | ⚠️ | ❌ | — | ❌ | `TagResponse.color` exists & UI type carries it, but create/update requests omit color and UI never renders/edits it; REST service never calls `setColor` |
| **Tag rating** (`tag.rating`) | ❌ | ❌ | ❌ | — | ❌ | DB column exists; no REST model field, no UI |
| **Tag↔Asset (tag/untag)** | ⚠️ | ✅ | — | ⚠️ | ❌ | REST `POST/DELETE /assets/:uuid/tags[/:tagUuid]` exist; UI asset-detail tag chips mutate **local state only**, never call the API |
| **Tag↔Asset region** (time + area) | ❌ | ⚠️ | ❌ | — | ❌ | `tag_asset` has time/area cols, `AssetTag` model + `TagResponse.area` support it, but `tagAsset` service ignores region and `TagCreateRequest` has no region fields; no UI |
| **Per-user rating** (`tag_user_meta`) | ❌ | ❌ | ❌ | ❌ | ❌ | Table + jOOQ record exist; no REST endpoint, no DAO op, no UI |
| **Tag↔Cluster** (`tag_cluster`) | ❌ | ❌ | ❌ | ❌ | ❌ | Table + `JooqTagCluster` exist; no REST endpoint, no UI |
| **Tag↔Collection** (`tag_collection`) | ❌ | ❌ | ❌ | ❌ | ❌ | Table + `JooqTagCollection` exist; no REST endpoint, no UI |

---

## Task: Persist asset tagging/untagging from the UI (wire the `/assets/:uuid/tags` endpoints)

**Argumentation Summary:** The REST API exposes asset tagging via `AssetEndpoint` — `POST /assets/:uuid/tags` (`tagService.tagAsset`) and `DELETE /assets/:uuid/tags/:tagUuid` (`tagService.untagAsset`) — see [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) lines 193-205 and [TagEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/TagEndpointService.java) `tagAsset`/`untagAsset`. The UI presents an editable tag input in [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) (lines ~392-425), but the Enter/Backspace handlers only mutate `asset.tags` in local React state — they never call any API. Neither [assets.ts](../../../loom-ui/src/api/assets.ts) nor [tags.ts](../../../loom-ui/src/api/tags.ts) contains a `tagAsset`/`untagAsset` client function. Tags added or removed in the asset detail view are silently lost on reload.

**Improvement Summary:** Add API client functions for the asset-tag sub-resource and wire the asset-detail tag chips to actually persist.

```
1. In loom-ui/src/api/assets.ts (or tags.ts), add:
   - tagAsset(token, assetUuid, request: TagCreateRequest): POST `${API_BASE_URL}/assets/${uuid}/tags`
     (returns the created tag, 201). Note the request body is a TagCreateRequest
     ({ name, collection, meta }) — see TagEndpointService.tagAsset lines 92-113.
   - untagAsset(token, assetUuid, tagUuid): DELETE `${API_BASE_URL}/assets/${uuid}/tags/${tagUuid}`
     (204 No Content) — see TagEndpointService.untagAsset lines 117-131.
2. In AssetDetail.tsx, replace the local-only array mutation in the tag input's
   onKeyDown (add on Enter, remove on Backspace) and the TagChip onDelete handler
   (line ~401) with calls to the new client fns, then refresh from loadAsset so
   the persisted tag list (AssetResponse.tags: TagReference[]) is authoritative.
3. The current UI treats tags as bare strings; TagReference in assets.ts already
   carries { uuid, name, collection, color } — use uuid for untag, name+collection
   for tag creation.
```

**References:**
- REST: [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java), [TagEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/TagEndpointService.java)
- UI: [assets.ts](../../../loom-ui/src/api/assets.ts), [tags.ts](../../../loom-ui/src/api/tags.ts), [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx)

**Test Requirements:**
- e2e (extend [assets-backend.spec.ts](../../../loom-ui/e2e/assets-backend.spec.ts)): open an asset, add a tag, reload, assert the tag persists; remove a tag, reload, assert it is gone.
- Unit test for the new `tagAsset`/`untagAsset` client fns (URL, method, headers, 201/204 handling).

---

## Task: Support tag color end-to-end (REST request fields + UI color picker)

**Argumentation Summary:** The `tag` table has a `color char(6)` column ([V2.2__add_tag.sql](../../../loom/db/flyway/src/main/resources/db/migration/V2.2__add_tag.sql) line 8) and `TagResponse` exposes it ([TagResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/tag/TagResponse.java) `color`), and the UI type [tags.ts](../../../loom-ui/src/api/tags.ts) `TagResponse` even declares `color?: string`. But the round-trip is broken at every write step: `TagCreateRequest`/`TagUpdateRequest` ([TagCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/tag/TagCreateRequest.java), [TagUpdateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/tag/TagUpdateRequest.java)) have no `color` field; `TagEndpointService.create`/`update` never call `tag.setColor(...)`; and [TagsView.tsx](../../../loom-ui/src/features/tags/TagsView.tsx) never renders or edits a color. Color is readable but not settable.

**Improvement Summary:** Add `color` to the create/update request models and service, and add a color control to the UI tag editor.

```
1. REST (backend): add `color` to TagCreateRequest.java and TagUpdateRequest.java
   (with a 6-hex validation in TagModelValidator). In TagEndpointService.create
   (lines 61-74) and update (lines 76-90) call update(request::getColor, tag::setColor)
   like the existing name/collection handling.
2. UI client: add `color?: string` to TagCreateRequest and TagUpdateRequest in tags.ts.
3. UI: in TagsView.tsx detail sidebar (lines ~411-441) add a color swatch/picker bound
   to a new editColor state; include it in handleCreateTag and handleSaveTag payloads.
   Render the color as a dot next to each tag leaf row (TagTreeRow, near the
   LocalOfferOutlined icon, lines ~121-138). The asset chip TagReference.color
   (assets.ts) can reuse the same rendering.
```

**References:**
- REST: [TagCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/tag/TagCreateRequest.java), [TagUpdateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/tag/TagUpdateRequest.java), [TagResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/tag/TagResponse.java), [TagEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/TagEndpointService.java)
- UI: [tags.ts](../../../loom-ui/src/api/tags.ts), [TagsView.tsx](../../../loom-ui/src/features/tags/TagsView.tsx)

**Test Requirements:**
- e2e in [tags-backend.spec.ts](../../../loom-ui/e2e/tags-backend.spec.ts): create a tag with a color, reload, assert the swatch reflects it; edit the color and verify persistence.
- Backend unit/integration test that create/update persist and return `color`.

---

## Task: Support region (time + area) tagging of assets

**Argumentation Summary:** The `tag_asset` join table carries `time_from`, `time_to`, `areaStartX/Y`, `areaWidth/Height` ([V2.8__add_asset.sql](../../../loom/db/flyway/src/main/resources/db/migration/V2.8__add_asset.sql) lines 95-110), the DB model [AssetTag.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/tag/AssetTag.java) exposes all six accessors, and [TagResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/tag/TagResponse.java) returns an `area` (`AreaInfo`). But `TagEndpointService.tagAsset` ([TagEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/TagEndpointService.java) lines 92-113) only sets name/collection/meta and never reads region data, and `TagCreateRequest` has no area/time fields — so a region tag can never be created. The UI has no region-tagging affordance either: [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) has a `VideoTimeline` and a `ZoomableImage` but tags are plain strings with no time/area binding. DOMAIN.md ([../DOMAIN.md](../DOMAIN.md), Tag row) explicitly calls out region tagging.

**Improvement Summary:** Extend the REST request model and service to accept time/area, then add UI to draw a box / pick a time range when tagging an asset.

```
1. REST (backend): add optional time_from/time_to + AreaInfo (or the four area ints)
   to TagCreateRequest.java. In TagEndpointService.tagAsset, after createAssetTag,
   call setTimeFrom/setTimeTo/setAreaStartX/Y/Width/Height from the request before
   dao().store(tag)/tagAsset(...).
2. UI client: extend the tagAsset request type (see the "Persist asset tagging" task)
   with optional time { from, to } and area { x, y, width, height }.
3. UI: in AssetDetail.tsx, allow creating a region tag from ZoomableImage
   (rubber-band box → area) and from VideoTimeline (selection → time_from/time_to);
   render existing region tags using TagResponse.area / AreaInfo. Non-region tags
   remain supported (all fields optional).
```

**References:**
- REST: [TagEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/TagEndpointService.java), [TagCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/tag/TagCreateRequest.java), [TagResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/tag/TagResponse.java)
- DB: [AssetTag.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/tag/AssetTag.java), [V2.8__add_asset.sql](../../../loom/db/flyway/src/main/resources/db/migration/V2.8__add_asset.sql)
- UI: [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx), [assets.ts](../../../loom-ui/src/api/assets.ts)

**Test Requirements:**
- Backend test: tagAsset with area+time persists and is returned in TagResponse.area.
- e2e: draw a region on an image asset, tag it, reload, assert the region tag is shown.

---

## Task: Expose and integrate per-user tag rating (`tag_user_meta`)

**Argumentation Summary:** The domain models per-user tag rating in `tag_user_meta` (`tag_uuid`, `user_uuid`, `rating`, `meta`) — [V2.2__add_tag.sql](../../../loom/db/flyway/src/main/resources/db/migration/V2.2__add_tag.sql) lines 24-34 — and jOOQ has generated `JooqTagUserMeta`. DOMAIN.md ([../DOMAIN.md](../DOMAIN.md)) states "per-user rating in `tag_user_meta`". Yet there is **no REST endpoint** for it: [TagEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TagEndpoint.java) has only core CRUD, [TagDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/tag/TagDao.java) has no user-meta method, no `TagResponse` field carries rating, and the UI ([tags.ts](../../../loom-ui/src/api/tags.ts), [TagsView.tsx](../../../loom-ui/src/features/tags/TagsView.tsx)) has no rating control. (The aggregate `tag.rating` column is likewise unexposed in any REST model.) This is a full-stack gap: the UI cannot rate a tag because the API does not offer it.

**Improvement Summary:** Add REST endpoints (and DAO ops) to set/read the current user's rating for a tag, expose it in the response, and add a rating control in the tags UI.

```
1. DB/DAO: add TagDao methods to upsert/load a tag_user_meta row for (tag, user).
2. REST: add routes on TagEndpoint, e.g. POST `/tags/:uuid/rating` (body { rating })
   using the authenticated user (lrc.userUuid()) and GET to read it; optionally
   surface the caller's rating (and/or aggregate tag.rating) on TagResponse.
3. UI client: add rateTag(token, uuid, rating) and a userRating field to TagResponse
   in tags.ts.
4. UI: add a star/rating widget to the TagsView detail sidebar (TagsView.tsx,
   lines ~411-441) bound to the new endpoint.
```

**References:**
- DB: [V2.2__add_tag.sql](../../../loom/db/flyway/src/main/resources/db/migration/V2.2__add_tag.sql), [TagDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/tag/TagDao.java)
- REST: [TagEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TagEndpoint.java), [TagEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/TagEndpointService.java), [TagResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/tag/TagResponse.java)
- UI: [tags.ts](../../../loom-ui/src/api/tags.ts), [TagsView.tsx](../../../loom-ui/src/features/tags/TagsView.tsx)

**Test Requirements:**
- Backend test: rate a tag as user A, verify the row and that A's read returns it while another user's does not.
- e2e: set a rating in the tags UI, reload, assert it persists.

---

## Task: Expose and integrate Tag↔Cluster associations (`tag_cluster`)

**Argumentation Summary:** `tag_cluster` (`tag_uuid`, `cluster_uuid`) exists — [V2.12__add_embedding.sql](../../../loom/db/flyway/src/main/resources/db/migration/V2.12__add_embedding.sql) lines 62-69 — with generated `JooqTagCluster`, and DOMAIN.md ([../DOMAIN.md](../DOMAIN.md)) lists Tag ↔ Cluster. But no REST route references it: neither [TagEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TagEndpoint.java) nor `ClusterEndpoint` has a tag sub-resource, [TagDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/tag/TagDao.java) has only asset/annotation tag ops, and the UI ([clusters.ts](../../../loom-ui/src/api/clusters.ts), cortex/faceDetection features) offers no tag-a-cluster action. The UI cannot associate a tag with a cluster because the API does not expose it.

**Improvement Summary:** Add REST endpoints (and DAO ops) to attach/detach a tag to a cluster, and surface it in the cluster/detection UI.

```
1. DAO: add TagDao.tagCluster(Tag, Cluster) / untagCluster(...) and a listing.
2. REST: add sub-resource routes, e.g. POST `/clusters/:uuid/tags` and
   DELETE `/clusters/:uuid/tags/:tagUuid` (mirroring the AssetEndpoint tag pattern,
   AssetEndpoint.java lines 193-205), returning the cluster's tags.
3. UI client: add tagCluster/untagCluster to loom-ui/src/api/clusters.ts and expose
   a tag list on ClusterResponse.
4. UI: add a tag chip editor to the cluster/person view (features/cortex or
   features/faceDetection where clusters are shown).
```

**References:**
- DB: [V2.12__add_embedding.sql](../../../loom/db/flyway/src/main/resources/db/migration/V2.12__add_embedding.sql), [TagDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/tag/TagDao.java)
- REST: [TagEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TagEndpoint.java), [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (pattern reference)
- UI: [clusters.ts](../../../loom-ui/src/api/clusters.ts)

**Test Requirements:**
- Backend test: attach/detach a tag to a cluster; verify listing.
- e2e: tag a cluster from the UI, reload, assert association persists.

---

## Task: Expose and integrate Tag↔Collection associations (`tag_collection`)

**Argumentation Summary:** `tag_collection` (`tag_uuid`, `collection_uuid`) exists — [V2.7__add_collection.sql](../../../loom/db/flyway/src/main/resources/db/migration/V2.7__add_collection.sql) lines 22-29 — with generated `JooqTagCollection`, and DOMAIN.md ([../DOMAIN.md](../DOMAIN.md)) lists both Collection and Tag as related through it. Note this is distinct from the `tag.collection` *namespace string* used by TagsView. No REST route references `tag_collection`: [TagEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TagEndpoint.java), `CollectionEndpoint`, [TagDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/tag/TagDao.java) and the UI ([collections.ts](../../../loom-ui/src/api/collections.ts), features/collections) offer nothing to attach a tag to a Collection entity.

**Improvement Summary:** Add REST endpoints (and DAO ops) to attach/detach a tag to a Collection, and surface it in the collections UI.

```
1. DAO: add TagDao.tagCollection(Tag, Collection) / untagCollection(...) + listing.
2. REST: add sub-resource routes, e.g. POST `/collections/:uuid/tags` and
   DELETE `/collections/:uuid/tags/:tagUuid` (mirror AssetEndpoint tag routes),
   returning the collection's tags. Keep clearly separated from the tag.collection
   namespace string.
3. UI client: add tagCollection/untagCollection to loom-ui/src/api/collections.ts
   and a tags field on CollectionResponse.
4. UI: add a tag editor to the collections feature (loom-ui/src/features/collections).
```

**References:**
- DB: [V2.7__add_collection.sql](../../../loom/db/flyway/src/main/resources/db/migration/V2.7__add_collection.sql), [TagDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/tag/TagDao.java)
- REST: [TagEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TagEndpoint.java), [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (pattern reference)
- UI: [collections.ts](../../../loom-ui/src/api/collections.ts)

**Test Requirements:**
- Backend test: attach/detach a tag to a collection; verify listing.
- e2e: tag a collection from the UI, reload, assert association persists.
