# MetaLoom — CRUD Coverage Tasks: Organization

> Gaps between the REST API and the Loom UI (`loom-ui/`) for the Organization domain:
> **Collection, Library, Space**.
> Derived from REST endpoints, UI api clients/features, and e2e specs.
> Format follows [../../TASKS.template.md](../../TASKS.template.md). See [../DOMAIN.md](../DOMAIN.md).

## Coverage Summary

| Element | Create | Read | Update | Delete | E2E | Notes |
|---------|:------:|:----:|:------:|:------:|:---:|-------|
| **Collection** | ✅ | ⚠️ | ✅ | ✅ | ✅ | List/CRUD wired. No detail view (`loadCollection` exported but unused). No sub-resources exposed: asset link (DAO-ready), cluster membership, parent/child nesting. `meta` not editable in UI. |
| **Library** | ✅ | ⚠️ | ⚠️ | ✅ | ⚠️ | List/CRUD wired. Detail pane lists **all** assets globally, not library-scoped. Update edits name only (drops description/meta). No library↔collection. Single combined e2e test, no list assertion. |
| **Space** | ✅ | ⚠️ | ✅ | ✅ | ✅ | List/CRUD wired (admin table). No detail view (`loadSpace` unused). No space↔library / space↔collection assignment. `meta` not editable in UI. |

Legend: ✅ fully wired + tested · ⚠️ partial / incomplete · ❌ missing

**REST-level observation:** [CollectionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CollectionEndpoint.java),
[LibraryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/LibraryEndpoint.java) and
[SpaceEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/SpaceEndpoint.java) each expose **only** flat CRUD
(`POST` create, `POST /:uuid` update, `DELETE /:uuid`, `GET` list, `GET /:uuid` load). The create/update request models
([CollectionCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/collection/CollectionCreateRequest.java) etc.)
carry only `name` (+ `meta`). None of the relation tables documented in [../DOMAIN.md](../DOMAIN.md)
(`collection_asset`, `collection_cluster`, `collection.parent`, `library_collection`, `library_asset`, `project_library`, `project_collection`)
have any REST route — so several gaps below require a **REST endpoint addition first**, then UI wiring.

---

## Task: Expose Collection ↔ Asset membership via REST and wire it in the Collections UI

**Argumentation Summary:** The DB layer already supports adding/removing assets to a collection —
[CollectionDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/collection/CollectionDao.java) declares
`linkAsset(UUID collectionUuid, UUID assetUuid)` and `unlinkAsset(...)` (backed by the `collection_asset` table in [../DOMAIN.md](../DOMAIN.md)).
However [CollectionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CollectionEndpoint.java)
exposes no sub-resource route, and [CollectionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/CollectionEndpointService.java)
never calls `linkAsset`/`unlinkAsset`. The UI ([CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx))
only renders a name + date card — there is no way to view or manage a collection's assets. This is a core "folder grouping assets" feature that is entirely unreachable.

**Improvement Summary:** Add REST routes to link/unlink/list assets on a collection, add matching client functions, and add a collection detail surface that lists its assets with add/remove controls.

```
1. REST — in CollectionEndpoint.register(), add sub-resource routes under basePath():
     - POST   /collections/:uuid/assets/:assetUuid   → service.linkAsset(...)
     - DELETE /collections/:uuid/assets/:assetUuid   → service.unlinkAsset(...)
     - GET    /collections/:uuid/assets              → paged list of assets in the collection
   Guard with READ_COLLECTION / UPDATE_COLLECTION permissions consistent with CollectionEndpointService.
2. CollectionEndpointService — implement the three handlers delegating to CollectionDao.linkAsset / unlinkAsset
   and a paged asset query (mirror AbstractCRUDEndpointService.list patterns).
3. UI client — in loom-ui/src/api/collections.ts add:
     listCollectionAssets(token, uuid), addAssetToCollection(token, uuid, assetUuid), removeAssetFromCollection(token, uuid, assetUuid)
4. UI — extend CollectionsView.tsx (or add a CollectionDetailView) with a detail pane that lists the collection's
   assets (reuse the asset card pattern from LibraryView.tsx) and provides add/remove actions.
```

**References:**
- REST: [CollectionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CollectionEndpoint.java), [CollectionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/CollectionEndpointService.java), [CollectionDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/collection/CollectionDao.java)
- UI: [collections.ts](../../../loom-ui/src/api/collections.ts), [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx)

**Test Requirements:**
- Backend endpoint tests (extend the pattern in the `loom/core` endpoint tests) for link/unlink/list-assets.
- E2E in [collections-backend.spec.ts](../../../loom-ui/e2e/collections-backend.spec.ts): create a collection, add an asset, verify it appears in the detail pane, remove it, verify it is gone.

---

## Task: Expose and surface Collection parent/child nesting

**Argumentation Summary:** [../DOMAIN.md](../DOMAIN.md) documents Collection as a "Hierarchical folder grouping" with a self-parent relation.
Neither the REST model nor the UI expose this: [CollectionCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/collection/CollectionCreateRequest.java)
and `CollectionUpdateRequest` carry only `name`, [CollectionEndpointService.update()](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/CollectionEndpointService.java)
never touches a parent, and [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx) renders a flat grid.
So the advertised hierarchy cannot be created or navigated.

**Improvement Summary:** Add a `parentUuid` field to the collection create/update request + response models, persist it in the service, and render a tree/breadcrumb in the UI.

```
1. REST model — add optional parentUuid to CollectionCreateRequest / CollectionUpdateRequest / CollectionResponse.
2. CollectionEndpointService.create()/update() — read parentUuid and set the parent on the Collection entity
   (add a setParent/parent accessor on the DAO/model if not present).
3. UI — in collections.ts add parentUuid to CollectionCreateRequest/UpdateRequest/CollectionResponse types.
4. UI — CollectionsView.tsx: add a "parent" selector in the create/edit dialog and render collections as a
   tree (or filter by parent + breadcrumb navigation).
```

**References:**
- REST: [CollectionCreateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/collection/CollectionCreateRequest.java), [CollectionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/CollectionEndpointService.java)
- UI: [collections.ts](../../../loom-ui/src/api/collections.ts), [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx)

**Test Requirements:**
- Backend test: create child collection with parentUuid, verify parent is persisted and returned.
- E2E: create parent, create child under it, verify the tree/breadcrumb reflects nesting.

---

## Task: Expose Collection ↔ Cluster membership via REST and UI

**Argumentation Summary:** [../DOMAIN.md](../DOMAIN.md) lists `collection_cluster` — collections group clusters (e.g. person/visual-fingerprint groups), not only assets.
There is no REST route on [CollectionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CollectionEndpoint.java)
and no client function in [collections.ts](../../../loom-ui/src/api/collections.ts) for cluster membership, and [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx)
has no cluster UI. Users cannot add a cluster to a collection or see which clusters a collection contains.

**Improvement Summary:** Add REST link/unlink/list-cluster sub-resource routes for collections, matching client functions, and a cluster section in the collection detail view.

```
1. REST — add to CollectionEndpoint:
     - POST   /collections/:uuid/clusters/:clusterUuid  (link)
     - DELETE /collections/:uuid/clusters/:clusterUuid  (unlink)
     - GET    /collections/:uuid/clusters               (list)
   Implement in CollectionEndpointService via the collection↔cluster DAO relation (add DAO methods if missing).
2. UI client — collections.ts: listCollectionClusters / addClusterToCollection / removeClusterFromCollection.
3. UI — add a "Clusters" section/tab to the collection detail surface (reuse cluster rendering from
   loom-ui/src/api/clusters.ts consumers).
```

**References:**
- REST: [CollectionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CollectionEndpoint.java), [CollectionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/CollectionEndpointService.java)
- UI: [collections.ts](../../../loom-ui/src/api/collections.ts), [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx), [clusters.ts](../../../loom-ui/src/api/clusters.ts)

**Test Requirements:**
- Backend test for link/unlink/list clusters on a collection.
- E2E: add a cluster to a collection, verify membership, remove it.

---

## Task: Wire read-single (detail) views for Collection, Library and Space

**Argumentation Summary:** All three endpoints expose `GET /:uuid`
([CollectionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CollectionEndpoint.java),
[LibraryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/LibraryEndpoint.java),
[SpaceEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/SpaceEndpoint.java)) and the UI clients
define `loadCollection` ([collections.ts](../../../loom-ui/src/api/collections.ts)), `loadLibrary`
([libraries.ts](../../../loom-ui/src/api/libraries.ts)) and `loadSpace` ([spaces.ts](../../../loom-ui/src/api/spaces.ts)),
but none of these functions are called anywhere — every screen builds its view from the list response only.
There is no dedicated detail route (`/collections/:uuid`, `/library/:uuid`, `/admin/spaces/:uuid`) that fetches the single entity,
so full metadata (status/creator/editor audit fields, meta) is never surfaced.

**Improvement Summary:** Add detail routes/panes that call the existing `loadX` client functions and render full entity metadata (audit trail + meta).

```
1. UI routing — add detail routes and views:
     - CollectionsView: on card click, load via loadCollection(token, uuid) and show a detail panel.
     - LibraryView: replace the list-derived selectedLib with loadLibrary(token, id) for the detail pane.
     - AdminArea SpacesAdmin: add a detail/expand row using loadSpace(token, uuid).
2. Render status.creator / status.created / status.editor / status.edited and meta in the detail panels.
```

**References:**
- UI: [collections.ts](../../../loom-ui/src/api/collections.ts), [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx), [libraries.ts](../../../loom-ui/src/api/libraries.ts), [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx), [spaces.ts](../../../loom-ui/src/api/spaces.ts), [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx)

**Test Requirements:**
- E2E: open an entity detail, assert creator/created and name are shown from the single-load response.

---

## Task: Allow editing `meta` in the Collection and Space create/edit dialogs, and on Library update

**Argumentation Summary:** The REST models accept a free-form `meta` blob on create and update
(`CollectionCreateRequest` extends `AbstractMetaModel`; `SpaceCreateRequest`/`SpaceUpdateRequest` and `LibraryCreateRequest`/`LibraryUpdateRequest` carry `meta`),
and the services persist it (`update(request::getMeta, ...)` in
[CollectionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/CollectionEndpointService.java) and
[SpaceEndpointService.create()](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/SpaceEndpointService.java)).
But the UI never sends meta: [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx) posts `{ name }` only,
[AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) posts `{ name }` only, and [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx)
sets a `meta.description` on **create** but its update dialog sends `{ name }` only — so a library description can never be edited after creation.

**Improvement Summary:** Add a description/meta field to the collection and space create+edit dialogs, and include meta/description in the library update payload.

```
1. CollectionsView.tsx — add a description (or generic meta) field to the create and edit dialogs;
   include it in createCollection/updateCollection payloads (extend the request types in collections.ts,
   which currently omit meta entirely).
2. AdminArea SpacesAdmin — add a description field to the New Space / Edit Space dialogs; pass meta in
   createSpace/updateSpace.
3. LibraryView.tsx handleUpdate() — include meta: { description } in the updateLibrary payload and add a
   description field to the edit dialog (create already supports it).
```

**References:**
- REST: [CollectionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/CollectionEndpointService.java), [SpaceEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/SpaceEndpointService.java), [LibraryEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/LibraryEndpointService.java)
- UI: [collections.ts](../../../loom-ui/src/api/collections.ts), [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx), [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx), [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx)

**Test Requirements:**
- E2E: create a library with a description, edit the description, verify persistence after reload.
- E2E: set a description on a collection/space, verify it round-trips.

---

## Task: Make the Library detail pane list library-scoped assets (not all assets)

**Argumentation Summary:** [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx) loads assets with a single global
`listAssets(token)` call (line ~63) and renders the **same** asset grid regardless of which library is selected; `assetCount` is hardcoded to `0`
and the sidebar shows `assets.length` for every library. There is no way to fetch the assets that actually belong to a library because
[LibraryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/LibraryEndpoint.java) exposes no
`GET /libraries/:uuid/assets` route (and [LibraryDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/library/LibraryDao.java)
declares no asset relation, though `library_asset` / `asset_location.library_uuid` exist per [../DOMAIN.md](../DOMAIN.md)). The current UI therefore misrepresents library contents.

**Improvement Summary:** Add a REST endpoint to list assets belonging to a library, then have LibraryView load and count assets per selected library instead of globally.

```
1. REST — add GET /libraries/:uuid/assets (paged) to LibraryEndpoint + LibraryEndpointService, backed by a
   library→asset query (via library_asset / asset_location.library_uuid). Add the DAO method as needed.
2. UI client — libraries.ts: listLibraryAssets(token, uuid).
3. LibraryView.tsx — replace the global listAssets() with listLibraryAssets(selectedLib.id); drive the video/image
   counts and sidebar assetCount from the scoped result.
```

**References:**
- REST: [LibraryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/LibraryEndpoint.java), [LibraryEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/LibraryEndpointService.java), [LibraryDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/library/LibraryDao.java)
- UI: [libraries.ts](../../../loom-ui/src/api/libraries.ts), [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx)

**Test Requirements:**
- Backend test: list assets for a library returns only that library's assets.
- E2E in [library-backend.spec.ts](../../../loom-ui/e2e/library-backend.spec.ts): select two libraries, verify their asset lists/counts differ.

---

## Task: Expose Library ↔ Collection membership via REST and UI

**Argumentation Summary:** [../DOMAIN.md](../DOMAIN.md) defines Library as a top-level container of assets **and** collections (`library_collection`).
[LibraryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/LibraryEndpoint.java) has no collection sub-resource route,
[libraries.ts](../../../loom-ui/src/api/libraries.ts) has no such client function, and [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx)
never shows collections. There is no way to add a collection to a library or list a library's collections.

**Improvement Summary:** Add REST link/unlink/list-collection routes on libraries, matching client functions, and a collections section in the library detail pane.

```
1. REST — LibraryEndpoint + LibraryEndpointService:
     - POST   /libraries/:uuid/collections/:collectionUuid  (link)
     - DELETE /libraries/:uuid/collections/:collectionUuid  (unlink)
     - GET    /libraries/:uuid/collections                  (list)
   Add the corresponding LibraryDao relation methods.
2. UI client — libraries.ts: listLibraryCollections / addCollectionToLibrary / removeCollectionFromLibrary.
3. LibraryView.tsx — add a "Collections" section listing the library's collections with add/remove controls
   (reuse the collection list from collections.ts).
```

**References:**
- REST: [LibraryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/LibraryEndpoint.java), [LibraryEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/LibraryEndpointService.java), [LibraryDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/library/LibraryDao.java)
- UI: [libraries.ts](../../../loom-ui/src/api/libraries.ts), [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx), [collections.ts](../../../loom-ui/src/api/collections.ts)

**Test Requirements:**
- Backend test for link/unlink/list collections on a library.
- E2E: add a collection to a library, verify it appears, remove it.

---

## Task: Expose Space ↔ Library and Space ↔ Collection assignment via REST and UI

**Argumentation Summary:** [../DOMAIN.md](../DOMAIN.md) defines Space (DB `project`) as a workspace grouping libraries + collections
(`project_library`, `project_collection`). [SpaceEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/SpaceEndpoint.java)
exposes only flat CRUD, [spaces.ts](../../../loom-ui/src/api/spaces.ts) has no membership functions, and the admin UI
([AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) `SpacesAdmin`) is a name/UUID table with no way to assign libraries or collections.
Notably [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx) reads `activeSpace` from `SpaceContext` but only displays its **name** —
libraries are never actually filtered by the active space. So the central "workspace" concept has no functional grouping behind it.

**Improvement Summary:** Add REST routes to assign/unassign and list a space's libraries and collections, add client functions, and add management UI in the space admin surface (plus make LibraryView filter by active space).

```
1. REST — SpaceEndpoint + SpaceEndpointService:
     - POST   /spaces/:uuid/libraries/:libraryUuid    / DELETE (assign/unassign)
     - GET    /spaces/:uuid/libraries                 (list)
     - POST   /spaces/:uuid/collections/:collectionUuid / DELETE (assign/unassign)
     - GET    /spaces/:uuid/collections               (list)
   Add SpaceDao relation methods for project_library / project_collection.
2. UI client — spaces.ts: listSpaceLibraries / addLibraryToSpace / removeLibraryFromSpace and the collection variants.
3. UI — AdminArea SpacesAdmin: add a space detail view to manage assigned libraries/collections.
4. UI — LibraryView.tsx: when activeSpace is set, load libraries scoped to that space (listSpaceLibraries)
   instead of the global listLibraries, so the displayed space name matches the shown libraries.
```

**References:**
- REST: [SpaceEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/SpaceEndpoint.java), [SpaceEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/SpaceEndpointService.java), [SpaceDao.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/space/SpaceDao.java)
- UI: [spaces.ts](../../../loom-ui/src/api/spaces.ts), [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx), [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx)

**Test Requirements:**
- Backend tests for assign/unassign/list libraries and collections on a space.
- E2E in [spaces-backend.spec.ts](../../../loom-ui/e2e/spaces-backend.spec.ts): create a space, assign a library and a collection, verify listing, unassign.

---

## Task: Strengthen Library e2e coverage to match Collections/Spaces

**Argumentation Summary:** [collections-backend.spec.ts](../../../loom-ui/e2e/collections-backend.spec.ts) (list + CRUD + search) and
[spaces-backend.spec.ts](../../../loom-ui/e2e/spaces-backend.spec.ts) (list + create + update + delete as separate tests) are thorough, but
[library-backend.spec.ts](../../../loom-ui/e2e/library-backend.spec.ts) contains a **single** test that chains create/update/delete and has
no assertion that the library list loads from the backend, no search coverage, and no detail/asset assertion. Library is the least-tested Organization entity.

**Improvement Summary:** Add a list-load test and a search-filter test for libraries, mirroring the collections spec.

```
1. Add a "library list loads from backend" test asserting the sidebar renders (heading + at least the demo libraries).
2. Add a "search filters libraries/assets" test using the search box in LibraryView.tsx.
3. Once the library-scoped asset listing task lands, add an assertion that switching libraries changes the asset grid.
```

**References:**
- UI: [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx)
- E2E: [library-backend.spec.ts](../../../loom-ui/e2e/library-backend.spec.ts) (compare with [collections-backend.spec.ts](../../../loom-ui/e2e/collections-backend.spec.ts))

**Test Requirements:**
- New Playwright tests as above, following the existing login helper pattern.
