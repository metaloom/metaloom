# TASK_UI_ORGANIZATION — Organization

> Open UI work items for the Organization entities (Collection, Library, Space, Tag),
> re-verified against `loom-ui/src` on 2026-08-01.
> Format follows [../../TASKS.template.md](../../TASKS.template.md).
>
> **Context:** [LOOM_UI.md](LOOM_UI.md) · [../RESTAPI.md](../RESTAPI.md) · [../DOMAIN.md](../DOMAIN.md).
> REST routes read from `CollectionEndpoint.java`, `LibraryEndpoint.java`, `SpaceEndpoint.java`,
> `TagEndpoint.java` and `AssetEndpoint.java` (asset-scoped tag routes).
>
> **Ordering:** Task 1 is the high-impact gap (space scoping is dead app-wide); Task 2 is
> low-priority polish and does not block it.
>
> **Test conventions:** "component test" means a **mocked Playwright spec** under
> `loom-ui/e2e/*-mocked.spec.ts`; pure logic uses node-env vitest next to the module
> (e.g. `features/library/libraryAssets.test.ts`). No RTL/jsdom exists in this repo.

## Coverage Matrix

CRUD + list are implemented for all four entities; only the deviations are listed.

| Entity | REST Operation | UI Status | Where / Gap |
|--------|----------------|-----------|-------------|
| Collection | create / update / delete / list | Implemented | [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx) (update carries `name` only — the model has no other field) |
| Collection | `GET /collections/:uuid` | **Missing** | `loadCollection` ([collections.ts:62](../../../loom-ui/src/api/collections.ts)) has no caller; no detail or deep-link view → **Task 2** |
| Library | create / update / delete / list | Implemented | [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx) (`name` + merged `meta.description`) |
| Library | `GET /libraries/:uuid` | **Missing** | `loadLibrary` unused; detail comes from the list row → **Task 2** |
| Library ↔ Asset | via `asset.locations[].libraryUuid` | Implemented | Asset list, sidebar counts and header stats scoped by [libraryAssets.ts](../../../loom-ui/src/features/library/libraryAssets.ts) |
| Space | create / update / delete / list | Implemented (admin only) | `SpacesAdmin` in [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) |
| Space | `GET /spaces` app-wide | **Partial** | [SpaceContext.tsx](../../../loom-ui/src/context/SpaceContext.tsx) is a stub — `spaces` is `useState<Space[]>([])` with no setter and `listSpaces` is called nowhere outside admin → **Task 1** |
| Space | `GET /spaces/:uuid` | **Missing** | `loadSpace` unused → **Task 2** |
| Tag | create / update / delete / list | Implemented | [TagsView.tsx](../../../loom-ui/src/features/tags/TagsView.tsx) (update also via drag-and-drop re-grouping) |
| Tag | `GET /tags/:uuid` | **Missing** | `loadTag` unused; detail comes from the list → **Task 2** |
| Tag | rating set / get / clear | Implemented | `rateTag`, `loadTagRating`, `deleteTagRating` in TagsView |
| Tag ↔ Asset | `POST /assets/:uuid/tags`, `DELETE /assets/:uuid/tags/:tagUuid` | Implemented | [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) |

### REST-prerequisite gaps (not UI-fixable)

The DB schema carries links that **no REST route exposes** (absent from all four endpoint classes
and from [../RESTAPI.md](../RESTAPI.md)): collection↔asset, collection↔cluster, collection
self-parent hierarchy, `tag_collection`, library↔collection, space↔library and space↔collection.
These need backend work first and are out of scope for this file. Note the Tag `collection` field
is a free-text grouper string, unrelated to the Collection entity.

---

## Task 1: Wire `SpaceContext` to the Spaces API and add an app-wide active-space selector

**Argumentation Summary:** Space CRUD works in the admin area, but
[SpaceContext.tsx](../../../loom-ui/src/context/SpaceContext.tsx) is a stub: `spaces` is
initialised to `[]` and never populated, and `setActiveSpace` never persists. Four features read
`activeSpace` to scope their content ([LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx),
[AssetBrowser.tsx](../../../loom-ui/src/features/assets/AssetBrowser.tsx),
[ChatWorkspace.tsx](../../../loom-ui/src/features/chat/ChatWorkspace.tsx),
[PipelineEditor.tsx](../../../loom-ui/src/features/pipeline/PipelineEditor.tsx)) and there is no
selector anywhere in `src/layout/`, so `activeSpace` is permanently `null`, everything shows
"All Spaces", and the whole space-scoping model silently does nothing. This is the highest-impact
organization gap.

**Improvement Summary:** Make `SpaceProvider` load spaces from the API, expose loading state,
persist the selection, and surface a switcher in the app chrome.

```
Rewrite loom-ui/src/context/SpaceContext.tsx so SpaceProvider:
- On mount, once a token is available from AuthContext, calls listSpaces(token)
  (src/api/spaces.ts) and maps SpaceResponse → the Space type in src/types.
- Tracks `loading` across the fetch and swallows errors (empty list, never throws into the app).
- Restores the previously selected space from localStorage and persists setActiveSpace back to
  it; falls back to null ("All Spaces") when nothing is stored or the stored space is gone.
- Keeps the SpaceContextValue shape so the four existing consumers compile unchanged, and adds a
  reload() so AdminArea's SpacesAdmin can refresh the list after create/delete.
Add the selector to the app chrome (src/layout/Sidebar.tsx or the top bar): a dropdown over
`spaces` driven by useSpace(), showing activeSpace?.name, with an explicit "All Spaces" (null)
option. Note SpaceProvider mounts inside AuthGate in main.tsx, so a token is available — but
still guard against the not-yet-authenticated render.
```

**References:** [SpaceContext.tsx](../../../loom-ui/src/context/SpaceContext.tsx) ·
[api/spaces.ts](../../../loom-ui/src/api/spaces.ts) ·
[AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) (`SpacesAdmin`) ·
`loom/services/rest/.../endpoint/impl/SpaceEndpoint.java` · [LOOM_UI.md](LOOM_UI.md)

**Test Requirements:**
- vitest (node env) on the persistence helper: the stored space id round-trips through
  localStorage and an unknown id falls back to null. Keep the pure logic out of the component so
  it is testable without a DOM.
- New `e2e/spaces-scope-mocked.spec.ts`: with `/spaces` mocked, the selector lists the space
  names, selecting one scopes the library/asset view, and the choice survives a reload;
  a failing `/spaces` request leaves the app usable with only "All Spaces".

---

## Task 2: Use the single-resource GET endpoints for deep-linkable detail loading

**Argumentation Summary:** `GET /:uuid` exists for all four entities and every client wrapper is
written (`loadCollection`, `loadLibrary`, `loadSpace`, `loadTag`) — none is called anywhere in
`src/`. Every view depends on the list payload, so no organization entity can be deep-linked or
refreshed by id, and Collections have no detail affordance at all. Lowest-priority gap.

**Improvement Summary:** Wire the existing `loadXxx` wrappers into at least one addressable path,
starting with Collections.

```
- Add a /collections/:uuid route + detail panel that calls loadCollection(token, uuid) instead of
  scanning the in-memory list, so a cold load of that URL resolves.
- Apply the same pattern for Library (loadLibrary) and Tag (loadTag) where a detail surface
  already exists; loadSpace can stay unused until Task 1 gives spaces an addressable surface.
- Render a clear not-found state on 404 rather than an empty panel.
Keep scope minimal — this is wiring, not new screens.
```

**References:** [api/collections.ts](../../../loom-ui/src/api/collections.ts) ·
[api/libraries.ts](../../../loom-ui/src/api/libraries.ts) · [api/tags.ts](../../../loom-ui/src/api/tags.ts) ·
[CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx) ·
[AppShell.tsx](../../../loom-ui/src/layout/AppShell.tsx) (route table)

**Test Requirements:**
- New mocked e2e: navigating straight to `/collections/<uuid>` renders the entity from the
  single-resource route without a prior list fetch (assert the list route was not required), and
  a 404 renders the not-found state.

---

## Closed items (outcome records)

| Closed task | Landed in |
|---|---|
| Collection CRUD | [CollectionsView.tsx](../../../loom-ui/src/features/collections/CollectionsView.tsx); `e2e/collections-backend.spec.ts` |
| Library CRUD incl. `meta.description` editing | [LibraryView.tsx](../../../loom-ui/src/features/library/LibraryView.tsx); `e2e/library-edit-mocked.spec.ts`, `library-backend.spec.ts` |
| Library-scoped asset list, counts and header stats | [libraryAssets.ts](../../../loom-ui/src/features/library/libraryAssets.ts) (`libraryAssets.test.ts`); `e2e/library-scope-mocked.spec.ts`, `library-thumbnails-mocked.spec.ts` |
| Space CRUD (admin) | `SpacesAdmin` in [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx); `e2e/spaces-backend.spec.ts` |
| Tag CRUD + drag-and-drop grouping | [TagsView.tsx](../../../loom-ui/src/features/tags/TagsView.tsx); `e2e/tags-backend.spec.ts` |
| Tag rating (set / read / clear) | `rateTag` / `loadTagRating` / `deleteTagRating` in TagsView; `e2e/tag-rating-backend.spec.ts`, `workflow-rating-mocked.spec.ts` |
| Tagging and untagging assets | [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx); `e2e/region-tags-backend.spec.ts` |

_Git HEAD revision: `499f71f7`_
_Last updated: 2026-08-01 (re-verified against loom-ui; two tasks remain — SpaceContext wiring and single-resource deep links)_
