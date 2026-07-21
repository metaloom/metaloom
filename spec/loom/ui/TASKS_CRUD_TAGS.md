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
