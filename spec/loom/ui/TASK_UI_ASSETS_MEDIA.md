# TASK_UI_ASSETS_MEDIA — Assets & Media

> Open UI work items for the Assets & Media entities (Asset, Asset Component, Attachment,
> Asset Binary, Annotation, Transcript, Blacklist, Asset Pool), derived from a code audit of
> `loom-ui/` and `loom/services/rest/.../endpoint/impl/` on 2026-08-01.
> Format follows [../../TASKS.template.md](../../tasks/TASKS.template.md).
>
> **Context:** [LOOM_UI.md](LOOM_UI.md) (UI spec — routes, api-client shape, test setup) ·
> [../RESTAPI.md](../RESTAPI.md) · [../DOMAIN.md](../DOMAIN.md) group 2
>
> **Ordering:** nothing here blocks anything else. Task 1 and Task 2 are the two real feature
> gaps and are independent. Tasks 3–5 are small/decision-shaped.
>
> **Owned elsewhere — do not duplicate here:**
> * **Search UI** — now **built**: `src/api/search.ts`, the `/search` route, `SearchContext` and the
>   global sidebar field all exist, and `AssetBrowser` routes a non-empty query to `/search/assets`.
>   See [../../features/search/SEARCH.md](../../features/search/SEARCH.md) §0. What remains is
>   [../../tasks/SEARCH_TASKS.md](../../tasks/SEARCH_TASKS.md) **Task 4** (`LibraryView` still filters
>   client-side) and **Task 9** (deleting the unreachable `src/{Content,Dashboard,User}` trees).
> * **Dedup-group review UI** (`/api/v1/dedup-groups`, `/api/v1/assets/:uuid/dedup-groups`) →
>   [TASK_UI_AI_ML.md](TASK_UI_AI_ML.md) Task 4, per
>   [NODE_DEDUP.md](../../features/nodes/dedup/NODE_DEDUP.md) §10.
> * **Reactions on assets/annotations** → [TASK_UI_COLLABORATION.md](TASK_UI_COLLABORATION.md).

---

## Closed — outcome records

| Task (as originally filed) | Outcome — where it landed |
|---|---|
| Annotation create/update/delete missing | ✅ DONE — `createAnnotation`/`updateAnnotation`/`deleteAnnotation` in `loom-ui/src/api/annotations.ts`, wired in `features/assetDetail/AssetDetail.tsx` + `AnnotationItem.tsx`; covered by `api/annotations.test.ts`, `e2e/annotations-mocked.spec.ts`, `e2e/annotations-backend.spec.ts` |
| Transcript create/load/update/delete missing | ✅ DONE — `loom-ui/src/api/transcripts.ts` (all 5 fns), UI in `features/assetDetail/TranscriptPanel.tsx`; `api/transcripts.test.ts`, `e2e/transcripts-mocked.spec.ts` |
| Binary metadata create (`POST /assets/:uuid/binary`) missing | ✅ DONE — `createAssetBinaryMeta` in `loom-ui/src/api/binaries.ts`, called from `AssetDetail.tsx`; `api/binaries.test.ts`, `e2e/binaries-mocked.spec.ts` |
| Annotation→reaction routes unreachable | ✅ DONE — five `*AnnotationReaction` fns in `loom-ui/src/api/reactions.ts` + `features/assetDetail/AnnotationReactionBar.tsx` |
| Asset↔task link fabricated from mock data | ✅ DONE — `/assets/:uuid/tasks` now exists; `listAssetTasks`/`assignTaskToAsset` in `api/tasks.ts` drive the AssetDetail task panel; `e2e/asset-tasks-mocked.spec.ts` |
| Asset PATCH/PUT helpers missing | ✅ CLOSED as a non-gap — `AssetEndpoint` routes POST, PATCH and PUT `/assets/:uuid` to the same `service.update`; PUT only adds a required-field precondition via `replaceHandler`. `updateAsset` (POST) is functionally complete. Same holds for the `sha512` POST/PATCH/PUT trio. |
| Asset Location read-only | ✅ CLOSED as a backend gap, not a UI gap — see "No REST surface" below; `e2e/asset-location-mocked.spec.ts` covers the read-only render |

---

## Task 1: Add an Asset Component API module and an AssetDetail components panel

**Argumentation Summary:** `AssetComponentEndpoint` exposes five routes at
`/api/v1/assets/:assetUuid/components` carrying the per-modality extracted metadata
(`AssetComponentType` = GEO, IMAGE, VIDEO, AUDIO, DOC, TRANSCRIPT, JSON — produced by Cortex
nodes). `loom-ui/src/api/` has **no** `assetComponents.ts` and no feature renders them, so the
single richest output of the whole Cortex pipeline is invisible in the product. The REST surface
is already exercised end-to-end by `loom-ui/e2e/components-backend.spec.ts`, which drives the raw
`fetch` calls from `page.evaluate` — i.e. the contract is proven, only the UI is absent.

**Improvement Summary:** Ship `api/assetComponents.ts` and a "Metadata / Components" tab on the
asset detail view that groups components by modality and `source`.

```
Routes (loom/services/rest/.../endpoint/impl/AssetComponentEndpoint.java):
  GET    /api/v1/assets/:assetUuid/components            -> list
  POST   /api/v1/assets/:assetUuid/components            -> create
  GET    /api/v1/assets/:assetUuid/components/:compUuid  -> load
  POST   /api/v1/assets/:assetUuid/components/:compUuid  -> update
  DELETE /api/v1/assets/:assetUuid/components/:compUuid  -> delete

1. New loom-ui/src/api/assetComponents.ts following the api-module shape in LOOM_UI.md §5
   (API_BASE_URL + authHeaders + handleResponse<T>). Model the response as a discriminated
   union on the component type; `source` is editable on every modality.
2. New panel in loom-ui/src/features/assetDetail/ (own file — AssetDetail.tsx is already
   ~1.7k lines; add a tab that mounts <ComponentsPanel assetUuid=... />, do not inline it).
   Group by type, then by `source`; render payloads read-only except `source`.
3. Transcript components are already surfaced by TranscriptPanel.tsx via /transcripts —
   do not render them twice; link across instead.
4. Treat create as admin/debug only (components are Cortex output): expose delete + source
   edit; gate writes on the *_ASSET permissions the rest of AssetDetail uses.

Edge cases: heterogeneous payloads per type; several components of the same type separated
only by `source`; an asset with zero components must render the shared EmptyState (§7.5).
```

**References:** [AssetComponentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetComponentEndpoint.java) ·
[../DOMAIN.md](../DOMAIN.md) group 2 · [LOOM_UI.md](LOOM_UI.md) §5, §10 ·
[loom-ui/e2e/components-backend.spec.ts](../../../loom-ui/e2e/components-backend.spec.ts)

**Test Requirements:** `loom-ui/src/api/assetComponents.test.ts` (node-env vitest) for the five
fns; `loom-ui/e2e/components-mocked.spec.ts` (Playwright with routed mocks — this repo has no
RTL/jsdom, see [LOOM_UI.md](LOOM_UI.md) §8) asserting grouped render + delete round-trip.
Run: `cd loom-ui && yarn test` and `yarn e2e --grep components`.

---

## Task 2: Add an Attachment API module and management UI

**Argumentation Summary:** `AttachmentEndpoint` registers five routes at `/api/v1/attachments`;
create is a **multipart upload** from which the backend derives filename, size, mimeType and a
content-addressed `sha512sum` (see the header of `e2e/attachments-backend.spec.ts`). `loom-ui`
has no `api/attachments.ts`; "Attachment" appears in the UI only as a permission label in
`features/admin/AdminArea.tsx` `PERMISSION_GROUPS`. Attachments therefore cannot be listed,
uploaded or deleted from the product at all.

**Improvement Summary:** Provide attachment CRUD plus an admin table, so auxiliary binaries are
manageable.

```
Routes (AttachmentEndpoint.java): POST/GET /api/v1/attachments ;
                                  GET/POST/DELETE /api/v1/attachments/:uuid

1. New loom-ui/src/api/attachments.ts. createAttachment sends FormData and MUST NOT set
   Content-Type (the browser writes the multipart boundary) — mirror uploadAssetBinary in
   api/binaries.ts. Response carries filename/size/mimeType/sha512sum; there is no thumbnail
   field, so render previews from sha512sum + mimeType.
2. Surface as an AdminArea tab following the BlacklistAdmin pattern (list + upload + delete),
   registered in the AdminArea nested <Routes> and the ACL sub-group in layout/Sidebar.tsx.
3. Optionally link an attachment back to its owning asset/embedding.

Edge cases: multipart create vs. JSON metadata update on the same resource; large uploads need
progress or at least a disabled button; delete must refetch the list.
```

**References:** [AttachmentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AttachmentEndpoint.java) ·
[../DOMAIN.md](../DOMAIN.md) group 2 · [LOOM_UI.md](LOOM_UI.md) §4.3 (sidebar ACL sub-group gotcha) ·
[loom-ui/e2e/attachments-backend.spec.ts](../../../loom-ui/e2e/attachments-backend.spec.ts)

**Test Requirements:** `loom-ui/src/api/attachments.test.ts` asserting the multipart create omits
a Content-Type header; `loom-ui/e2e/attachments-mocked.spec.ts` for list → upload → delete.
Run: `cd loom-ui && yarn test && yarn e2e --grep attachments`.

---

## Task 3: Finish the Blacklist admin screen (edit + single load)

**Argumentation Summary:** `BlacklistAdmin` in `features/admin/AdminArea.tsx` imports only
`listBlacklists`, `createBlacklist` and `deleteBlacklist`, although `api/blacklist.ts` already
exports `updateBlacklist` and `loadBlacklist`. A blacklist entry can be created and deleted but
never corrected — the name/assetUuid must be retyped from scratch.

**Improvement Summary:** Wire the existing `updateBlacklist`/`loadBlacklist` client functions into
an edit dialog on the blacklist table.

```
1. In features/admin/AdminArea.tsx BlacklistAdmin (~line 1144): add an edit action per row that
   opens a dialog prefilled from loadBlacklist(token, uuid) and submits updateBlacklist.
2. Reuse the rename-dialog shape already present in ApiKeysAdmin in the same file.
```

**References:** [loom-ui/src/api/blacklist.ts](../../../loom-ui/src/api/blacklist.ts) ·
[loom-ui/src/features/admin/AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) ·
[BlacklistEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/BlacklistEndpoint.java)

**Test Requirements:** extend `loom-ui/e2e/blacklist-backend.spec.ts` with an edit step, and add
the mocked equivalent. Run: `cd loom-ui && yarn e2e --grep blacklist`.

---

## Task 4: Add hash-addressed asset lookup to the api client

**Argumentation Summary:** `/api/v1/assets/sha512/:sha512` supports GET/POST/PATCH/PUT/DELETE;
`loom-ui/src/api/assets.ts` treats `sha512` purely as a payload field and offers no hash-addressed
function. Note the value is **navigation, not upload dedup**: `POST /assets/upload` already
answers 200 (instead of 201) when an asset with the same SHA-512 exists, so the upload flow needs
no pre-check. The remaining use is "open the asset for this hash" — e.g. from a Cortex log, a
dedup group member or an external tool.

**Improvement Summary:** Add `loadAssetBySha512` (and, only if a caller appears,
`deleteAssetBySha512`) plus a hash-resolving route.

```
1. Add loadAssetBySha512(token, sha512) to loom-ui/src/api/assets.ts; lowercase/trim the hash
   and treat 404 as "no such asset" rather than an error.
2. Optional: an /assets/by-hash/:sha512 route in layout/AppShell.tsx that resolves and
   <Navigate>s to /assets/:uuid.
Do NOT add patch/replace variants — they hit the same service.update as POST (see Closed table).
```

**References:** [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (sha512 block) ·
[loom-ui/src/api/assets.ts](../../../loom-ui/src/api/assets.ts) · [../RESTAPI.md](../RESTAPI.md)

**Test Requirements:** unit test in `loom-ui/src/api/assets.test.ts` (create the file) that the
hash is URL-encoded and 404 resolves to `null`. Run: `cd loom-ui && yarn test`.

---

## Task 5: Decide the audience of the standalone `/api/v1/binaries` endpoint

**Argumentation Summary:** `AssetBinaryEndpoint` registers five routes at `/api/v1/binaries`
entirely separate from the `/assets/:uuid/binary` sub-resource the UI uses. No `loom-ui` code
references `/binaries`. It is probably internal, but the ambiguity keeps re-surfacing in every
gap audit.

**Improvement Summary:** Record the decision once — either a thin admin listing, or an explicit
"no UI by design" note in the REST spec.

```
1. Confirm the intended audience with the backend owner.
2. If user-facing: api/assetBinaries.ts + an AdminArea table (orphaned-binary browse/delete).
3. If internal: add "no UI by design" to ../RESTAPI.md next to the /binaries rows and delete
   this task. No code change.
```

**References:** [AssetBinaryEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetBinaryEndpoint.java) ·
[../RESTAPI.md](../RESTAPI.md)

**Test Requirements:** if exposed, api-client tests for the five fns plus a mocked listing spec;
if internal, spec note only and no test.

---

## No REST surface — backend prerequisites, not UI gaps

* **Asset Location** — no endpoint; path/filekey/pool/`lockedBy` are embedded read-only in
  `AssetResponse` and rendered by `AssetLocationInfo` in `AssetDetail.tsx`. Lock/unlock or
  relocate needs a backend route first.
* **Asset Remix** — the `asset_remix` table exists ([../DOMAIN.md](../DOMAIN.md) group 2) but no
  endpoint and no UI type. Asset-to-asset derivation browsing is a backend task.
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_