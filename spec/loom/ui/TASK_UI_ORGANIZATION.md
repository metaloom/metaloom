# TASK_UI_ORGANIZATION — Organization

Gap-analysis tasks between the Loom REST API and the Loom UI for the Organization
entities (Collection, Library, Space, Tag). Follows [../../TASKS.template.md](../../TASKS.template.md).

Authoritative REST routes were read from
[CollectionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CollectionEndpoint.java),
[LibraryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/LibraryEndpoint.java),
[SpaceEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/SpaceEndpoint.java),
[TagEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TagEndpoint.java) and
[AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java)
(asset-scoped tag routes), and cross-checked against [../RESTAPI.md](../RESTAPI.md).

## Coverage Matrix

| Entity | REST Operation (path · method) | UI Status | Where / Gap |
|--------|-------------------------------|-----------|-------------|
| Collection | `/api/v1/collections` · POST (create) | Implemented | [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx) `handleCreate` → `createCollection` |
| Collection | `/api/v1/collections/:uuid` · POST (update) | Implemented | `handleEdit` → `updateCollection` (name only; that is the only field the model has) |
| Collection | `/api/v1/collections/:uuid` · DELETE | Implemented | `handleDelete` → `deleteCollection` |
| Collection | `/api/v1/collections` · GET (list) | Implemented | `useEffect` → `listCollections` |
| Collection | `/api/v1/collections/:uuid` · GET (read one) | Missing | `loadCollection` exists in [collections.ts](../../../loom-ui/src/api/collections.ts) but is never called; no detail/deep-link view (see Task 4) |
| Library | `/api/v1/libraries` · POST (create) | Implemented | [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx) `handleCreate` → `createLibrary` (sends `name` + `meta.description`) |
| Library | `/api/v1/libraries/:uuid` · POST (update) | Implemented | `handleUpdate` → `updateLibrary` (sends `name` + merged `meta` with `description`; edit dialog pre-fills and edits the description) |
| Library | `/api/v1/libraries/:uuid` · DELETE | Implemented | `handleDelete` → `deleteLibrary` |
| Library | `/api/v1/libraries` · GET (list) | Implemented | `useEffect` → `listLibraries` |
| Library | `/api/v1/libraries/:uuid` · GET (read one) | Missing | `loadLibrary` unused; detail comes from the list row |
| Space | `/api/v1/spaces` · POST (create) | Implemented | [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) `SpacesAdmin` → `createSpace` |
| Space | `/api/v1/spaces/:uuid` · POST (update) | Implemented | `SpacesAdmin` edit dialog → `updateSpace` |
| Space | `/api/v1/spaces/:uuid` · DELETE | Implemented | `SpacesAdmin` → `deleteSpace` |
| Space | `/api/v1/spaces` · GET (list) | **Partial** | Consumed only in `SpacesAdmin`; the app-wide [SpaceContext.tsx](../../../loom-ui/src/context/SpaceContext.tsx) is a **stub** that never calls `listSpaces`, so `activeSpace`/space-scoping is dead everywhere (Task 1) |
| Space | `/api/v1/spaces/:uuid` · GET (read one) | Missing | `loadSpace` unused |
| Tag | `/api/v1/tags` · POST (create) | Implemented | [TagsView.tsx](../../../loom-ui/src/features/tags/TagsView.tsx) `handleCreateTag` → `createTag` |
| Tag | `/api/v1/tags/:uuid` · POST (update) | Implemented | `handleSaveTag` + drag-and-drop `handleDrop` → `updateTag` (name + collection grouper) |
| Tag | `/api/v1/tags/:uuid` · DELETE | Implemented | `handleDeleteTag` → `deleteTag` |
| Tag | `/api/v1/tags` · GET (list) | Implemented | `reload` → `listTags` |
| Tag | `/api/v1/tags/:uuid` · GET (read one) | Missing | `loadTag` unused; detail comes from the list |
| Tag | `/api/v1/tags/:uuid/rating` · POST (set rating) | Implemented | `handleRate` → `rateTag` |
| Tag | `/api/v1/tags/:uuid/rating` · GET (read rating) | Implemented | `handleSelectNode` → `loadTagRating` |
| Tag | `/api/v1/tags/:uuid/rating` · DELETE (clear rating) | Implemented | `handleRate(null)` → `deleteTagRating` |
| Tag↔Asset | `/api/v1/assets/:uuid/tags` · POST (tag asset) | Implemented | [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) `apiTagAsset` |
| Tag↔Asset | `/api/v1/assets/:uuid/tags/:tagUuid` · DELETE (untag) | Implemented | AssetDetail `apiUntagAsset` |

### REST-prerequisite gaps (not UI-fixable on their own)

The aspect brief lists several sub-element links that exist in the DB schema but are
**not exposed by any REST route** (confirmed absent from all four endpoint classes and from
[RESTAPI.md](../RESTAPI.md)): collection↔asset, collection↔cluster, collection self-parent
hierarchy, tag_collection (the Collection entity — note the Tag `collection` field is only a
free-text grouper string, unrelated to the Collection entity), library↔collection, and
space↔library / space↔collection. Because the server offers no endpoint, these cannot be
turned into UI-only tasks; they require REST work first and are out of scope here. The one
exception with usable data is library↔asset — implemented: [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx)
scopes the asset list, sidebar counts and header stats to the selected library via
`asset.locations[].libraryUuid` ([libraryAssets.ts](../../../loom-ui/src/features/library/libraryAssets.ts)).

---

## Task: Wire SpaceContext to the Spaces API and add an app-wide active-space selector

**Argumentation Summary:** Space CRUD is fully implemented in the admin area, but the
app-wide [SpaceContext.tsx](../../../loom-ui/src/context/SpaceContext.tsx) is a stub —
`spaces` is hard-coded to `[]` and `setActiveSpace` never persists. Multiple features read
`activeSpace` to scope their content ([LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx),
[AssetBrowser.tsx](../../../loom-ui/src/features/assets/AssetBrowser.tsx),
[ChatWorkspace.tsx](../../../loom-ui/src/features/chat/ChatWorkspace.tsx),
[PipelineEditor.tsx](../../../loom-ui/src/features/pipeline/PipelineEditor.tsx)), so the
`GET /api/v1/spaces` list operation is effectively unused outside admin and the whole
space-scoping model silently does nothing (`activeSpace` is always `null`, everything shows
"All Spaces"). This is the highest-impact organization gap.

**Improvement Summary:** Make `SpaceProvider` load spaces from the API, expose loading state,
persist the active-space selection, and surface an active-space switcher in the app chrome.

```
Rewrite loom-ui/src/context/SpaceContext.tsx so SpaceProvider:
  - On mount (once a token is available from AuthContext) calls listSpaces(token) from
    loom-ui/src/api/spaces.ts and stores the result in `spaces`, mapping SpaceResponse →
    the Space type in loom-ui/src/types.
  - Sets `loading` true during the fetch and false on success/failure; tolerate errors
    (empty list, no throw to the app).
  - Restores the previously selected active space from localStorage on load and persists
    setActiveSpace(space) back to localStorage so the choice survives reloads. Fall back to
    the first space (or null) when none is stored.
  - Keeps the existing SpaceContextValue shape so current consumers compile unchanged.
Add an active-space selector to the app chrome (loom-ui/src/layout/Sidebar.tsx or the top
bar) driven by useSpace(): a dropdown listing `spaces`, showing `activeSpace?.name`, calling
setActiveSpace on change, with an "All Spaces" (null) option.
Edge cases: no spaces returned (selector shows only "All Spaces"); token not yet present
(defer the fetch); an active space that was deleted in admin (fall back to null/first).
Keep AdminArea's SpacesAdmin as the CRUD surface; after create/delete there, the context list
should be refreshable (expose a `reload()` on the context or re-fetch on navigation).
```

**References:**
- [loom-ui/src/context/SpaceContext.tsx](../../../loom-ui/src/context/SpaceContext.tsx)
- [loom-ui/src/api/spaces.ts](../../../loom-ui/src/api/spaces.ts)
- [loom-ui/src/features/admin/AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) (`SpacesAdmin`)
- [SpaceEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/SpaceEndpoint.java)

**Test Requirements:**
- Unit test SpaceProvider: mock `listSpaces`, assert `spaces` populates and `loading`
  transitions; assert `setActiveSpace` writes to and reads back from localStorage.
- Test fallback when the fetch rejects (spaces stays `[]`, no throw).
- Component test the selector: renders space names, changing it updates `activeSpace`.

---

## Task: Use the single-resource GET endpoints for deep-linkable detail loading

**Argumentation Summary:** `GET /:uuid` exists for all four entities
(collections, libraries, spaces, tags) and each has a client wrapper (`loadCollection`,
`loadLibrary`, `loadSpace`, `loadTag`), but none are called anywhere in the UI — every view
relies on the list payload. Collections in particular have no detail affordance at all, and
none of these views can be deep-linked or refreshed by id (a `/collections/:uuid` reload would
show nothing without first loading the whole list). This is the lowest-priority organization
gap.

**Improvement Summary:** Add id-addressable loading so an entity can be opened/refreshed
directly, using the already-present single-read endpoints.

```
Pick the highest-value entity first (Collection, which has no detail view):
  - Add a route/panel that reads a :uuid and calls loadCollection(token, uuid) to populate a
    detail view, rather than only reading from the in-memory list.
  - Apply the same pattern where useful for Library (loadLibrary) and Tag (loadTag) so a
    deep-link / refresh resolves the entity independently of the list fetch.
  - Handle not-found (404) with a clear empty/error state.
Keep scope minimal: this is about wiring the existing loadXxx wrappers into at least one
addressable path, not building full new screens.
```

**References:**
- [loom-ui/src/api/collections.ts](../../../loom-ui/src/api/collections.ts) (`loadCollection`)
- [loom-ui/src/api/libraries.ts](../../../loom-ui/src/api/libraries.ts) (`loadLibrary`)
- [loom-ui/src/api/tags.ts](../../../loom-ui/src/api/tags.ts) (`loadTag`)
- [loom-ui/src/features/collections/CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx)

**Test Requirements:**
- Test that navigating to an entity by id triggers the corresponding `loadXxx` call and
  renders it without a prior list fetch.
- Test the 404 / not-found path renders an error state.
