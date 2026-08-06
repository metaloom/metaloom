# Loom UI — Task List

> Work items for `loom-ui/`, derived from a code audit on 2026-08-06 against HEAD `a63b034b`.
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) (shell spec) ·
> [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) ·
> [../loom/ui/LOOM_UI_PIPELINE_EDITOR.md](../loom/ui/LOOM_UI_PIPELINE_EDITOR.md) ·
> [../features/search/SEARCH.md](../features/search/SEARCH.md) ·
> [../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md)
>
> **Scope note.** This file holds *cross-cutting* UI work: search, list paging, E2E coverage
> holes and shell-level gaps. Per-entity REST↔UI gaps stay in the `TASK_UI_*.md` files under
> [../loom/ui/](../loom/ui/) and are not restated here.
>
> **Ordering / blocking.** Tasks 1, 2 and 3 are **done**. Task 4 blocks nothing but is the largest
> single coverage hole. Tasks 5–10 are independent E2E work and can run in parallel. Tasks 11–13
> are shell refinement.
>
> **Test conventions.** "component test" = a **mocked Playwright spec** (`loom-ui/e2e/*-mocked.spec.ts`);
> pure logic = node-env vitest beside the module. There is no RTL/jsdom in this repo
> ([../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §8.1).

---

# Fix e2e tests
For the backend suite I compared against a worktree at ce41aaf1: 16 pre-existing failures there, the same 16 now, zero regressions, plus 3 new passing tests.


## 0. Audit findings — the four questions

### 0.1 Are all listing features covered by a search bar? — RESOLVED

> **Status: fixed.** Tasks 2 and 3 shipped. The table below is the audit as written, annotated with
> what is true now. Three of the original claims were already stale when Task 1 landed and are
> corrected in place rather than repeated.

**The audit found:** every search box in the app was a client-side `Array.filter()` over data
already in `useState`, and seven listing surfaces had no box at all.

| View | Search bar | Backing |
|------|-----------|---------|
| [AssetBrowser.tsx](../../loom-ui/src/features/assets/AssetBrowser.tsx) | yes, + type filter | **server-side** — `searchAssets()`, debounced 250 ms, `?mime=` travels with the query |
| [LibraryView.tsx](../../loom-ui/src/features/library/LibraryView.tsx) · [CollectionsView.tsx](../../loom-ui/src/features/collections/CollectionsView.tsx) · [TagsView.tsx](../../loom-ui/src/features/tags/TagsView.tsx) · [AssetPoolsView.tsx](../../loom-ui/src/features/assetPools/AssetPoolsView.tsx) · [CortexView.tsx](../../loom-ui/src/features/cortex/CortexView.tsx) · detection · admin | yes | client-side, now over a **paged** list with a "load more" |
| **Admin: permissions / roles** (`AccessControlAdmin`) | **added** | client-side (`admin-roles-search`) |
| **[TasksView.tsx](../../loom-ui/src/features/tasks/TasksView.tsx)** | **added** | client-side, title + description |
| **[SkillManagementView.tsx](../../loom-ui/src/features/skills/SkillManagementView.tsx)** | **added** | client-side, one term per tab |
| **[MemoryView.tsx](../../loom-ui/src/features/memory/MemoryView.tsx)** | **added** | client-side, id + title + session |
| **[ChatSessionsView.tsx](../../loom-ui/src/features/chatSessions/ChatSessionsView.tsx)** | **added** | client-side, name + description + tags |
| [ClustersPanel.tsx](../../loom-ui/src/features/faceDetection/ClustersPanel.tsx) · [PersonsPanel.tsx](../../loom-ui/src/features/faceDetection/PersonsPanel.tsx) | **the audit was wrong** | their parent [FaceDetectionManagement.tsx:70](../../loom-ui/src/features/faceDetection/FaceDetectionManagement.tsx) already owned a query field and passed down filtered props |
| [NotificationPopover.tsx](../../loom-ui/src/features/notifications/NotificationPopover.tsx) | no | out of scope — a popover, not a listing screen |

**The load-bearing defect — fixed.** `QueryParameterKey.LIMIT` defaults to **25**
([QueryParameterKey.java:12](../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/parameter/QueryParameterKey.java)),
and no UI list call passed `?limit=` or `?from=`. The blast radius was wider than the audit stated:
`listAssets` has **six** call sites — `AssetBrowser`, `LibraryView`, `ChatWorkspace`, `WorkflowView`
and both detection screens — so the detection views were picking their target from a silent first
page too. Every collection client now takes `PagingParams`, the counts come from
`_metainfo.totalCount`, and `_metainfo.lastUuid` drives a "load more". → **Task 2**, **Task 3**.

### 0.2 Can semantic search / boolean / term search be run in the UI?

**No, for three different reasons.**

| Mode | Backend | UI |
|------|---------|-----|
| **Boolean / phrase / negation** | Built. `PostgresSearchProvider` uses `websearch_to_tsquery`, so `"quoted phrase"`, `or` and `-negation` all work today ([PostgresSearchProvider.java:118](../../loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/search/PostgresSearchProvider.java)) | **Unreachable** — no client, no input routes to `?q=` |
| **Term / typeahead** | Built — `GET /search/suggestions` (trigram, `similarity()`-ordered) | Unreachable |
| **Faceted / filtered** | Built — `?types= &mime= &library= &space= &collection= &tag= &lang= &from= &to= &facets= &highlight= &sort=` | Unreachable |
| **Semantic (`mode=SEMANTIC`)** | Partial — enum + capability + `?profile=` are built; the **ranker is not**. `mode=SEMANTIC` returns **400** naming the provider, deliberately — never a silent lexical fallback | Nothing to expose yet |

So: boolean/term search is a **UI wiring gap** (Task 1); semantic search is a **backend gap** tracked
in [../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) §5–6 and
[../concept/SEARCH_PLAN.md](../concept/SEARCH_PLAN.md) Phase 3 — **do not build UI for it until the
ranker lands**; `GET /search/status` is the honest gate (Task 1 step 6).

### 0.3 Is Upload covered by E2E?

**Yes, better than any other screen — but with named holes.**
[uploads-mocked.spec.ts](../../loom-ui/e2e/uploads-mocked.spec.ts) has 11 specs (multi-file,
`poolUuid` present/absent, `GET /pools` → 403, navigate-away-mid-upload, duplicate vs failure,
cancel, clear), backed by `uploadQueue.test.ts` (288 lines), `uploadFormat.test.ts` and
`assetUpload.test.ts`. Not covered:

- `upload-dropzone` — **drag-and-drop is never exercised**; all 11 specs go through the file input.
- `upload-origin-input`, `upload-totals`, `upload-queue-heading`, `upload-cancel-all`,
  `upload-retry-failed` — five testids with zero E2E references.
- There is **no `uploads-backend.spec.ts`** — no spec uploads real bytes to a real server and reads
  the asset back. Java-side coverage exists (`AssetUploadEndpointTest`) but never crosses the UI.

→ **Task 6**.

### 0.4 Have recent features been covered by E2E?

**Mostly yes — the pipeline work was disciplined about it; three recent changes were not.**

| Commit | Feature | E2E |
|--------|---------|-----|
| `97127ed2` | Notification system, task assignees | Covered — `notifications-mocked` (177 ln) + `task-assignees-mocked` (184 ln) |
| `8440f58c` | Pipeline node re-execute | Covered — `pipeline-node-reexecute-mocked` (347 ln) |
| `81ad0fb4` | Pipeline debug / breakpoints / inspect | Covered — `pipeline-breakpoints-mocked`, `pipeline-run-pause-mocked`, `pipeline-node-results-mocked` |
| `384fe94e` | Debug **preview** rendering + node colors | **Gap** — vitest only; `result-element-previews`, `result-image-note`, `result-media-path`, `node-result-more` have **no E2E** → **Task 7** |
| `228b0f97` · `6d454bc0` | Memory system + memory denylist | **Gap** — zero E2E. 13 testids across `/memory` and `/admin/memory-denylist`, none referenced → **Task 4** |
| `b6ee0d2e` | Face embedding persistence | **Gap** — no UI surface at all; `clusters-backend`/`persons-backend` are one API-CRUD spec each → **Task 8** |

**Repo-wide measurement:** of **172** `data-testid` values in `loom-ui/src`, **62 (36 %)** are
referenced by no spec in `loom-ui/e2e/`. [ProfileView.tsx](../../loom-ui/src/features/profile/ProfileView.tsx)
carries **no `data-testid` at all** and so cannot even appear in that count → **Task 5**.

---

## Task 1: Wire the UI to the lexical search API — DONE

> **Status: shipped.** `src/api/search.ts`, `src/context/SearchContext.tsx`,
> `src/layout/GlobalSearchField.tsx` and `src/features/search/` all exist; `/search` is registered
> in `AppShell.tsx`. Tests: 25 client + 32 helper vitest cases, `search-mocked.spec.ts` (27 passing),
> `search-backend.spec.ts` (14 passing against the demo container).
>
> ⚠️ **Step 2 below named the wrong wire fields** and is corrected in place. Tasks 2 and 3 are
> written against this task's vocabulary, so use the corrected names, not the originals.

**Argumentation Summary:** `GET /api/v1/search/{results,assets,suggestions,status}` is built, wired,
capability-gated and covered by 49 backend tests
([../features/search/SEARCH.md](../features/search/SEARCH.md) §0, §5), and **the UI does not call it
once**. `loom-ui/src/api/search.ts` does not exist. The consequence is not merely a missing screen:
the boolean/phrase/negation syntax the provider already parses, the trigram typeahead, the facets and
the `ts_headline` highlights are all dead capability. Meanwhile every screen ships a search box that
filters an in-memory array, so the app *looks* searchable while `search_document` — 12 SQL functions,
17 triggers and a full backfill — serves nobody. `SEARCH.md` §0 has recorded the loom-ui row as "nothing" since
the feature landed.

**Improvement Summary:** Add `src/api/search.ts` plus a `/search` route with a results view, and put
a global search entry point in the app shell. Gate the whole thing on `GET /search/status` so a Noop
provider hides the box instead of rendering a broken one.

```
1. Create loom-ui/src/api/search.ts, mirroring the shape of the existing clients (plain fetch +
   authHeaders, exported response interfaces). Export:
     - searchResults(token, req: SearchRequestParams): Promise<SearchResultResponse>
     - searchAssets(token, req)  -> /search/assets
     - searchSuggestions(token, prefix, types?, limit?) -> SearchSuggestion[]
     - searchStatus(token): Promise<SearchStatusResponse>
   SearchRequestParams must cover every key in SearchQueryParameterKey.java:
     q, types, mode, limit, offset, cursor, sort, highlight, mime, library, space, collection,
     tag, from, to, lang, profile, facets.
   Omit undefined keys entirely — do NOT send empty strings; `?mode=` must not appear unless the
   caller asked for a non-LEXICAL mode (a stray one earns a 400).
2. Add the matching types. CORRECTED: the shared unions go in src/types/index.ts —
   SearchEntityType (lowercase wire ids: asset transcript tag annotation person collection
   library detection segment cluster), SearchMode, SearchSortMode, SearchCapability — while the
   wire response interfaces live in src/api/search.ts beside their module, as every other client
   does (LOOM_UI.md 11.1). The hit shape is NOT (entityType, entityUuid, snippet, timeFrom); the
   real one is:
     SearchHitResponse { type, uuid, assetUuid?, score, title, subtitle?, matchedIn?,
                         highlights?: string[], timeFromMs?, mimeType?, size?, sortDate? }
   Note `highlights` is an ARRAY of ts_headline fragments, not a single `snippet`, and it carries
   raw <b> markup over UNESCAPED source text - render it via features/search/highlight.ts, never
   as HTML. Facets are Record<string, {value,count}[]> keyed by the caller's own spelling, not a
   flat SearchFacet. Instant fields (sortDate, lastSyncedAt) serialize as numeric epoch seconds.
3. Add a SearchContext (src/context/SearchContext.tsx) that calls searchStatus() once after login
   and exposes { available, provider, capabilities }. Register it inside AuthGate in main.tsx,
   below NodeRegistryProvider. Never let a failed /search/status blank the app - default to
   available:false.
4. Add a global search field to src/layout/Sidebar.tsx header (testid `global-search-input`).
   Debounce 250 ms, call searchSuggestions for the dropdown, and Enter navigates to
   /search?q=<term>. Render nothing when SearchContext.available is false.
5. Add features/search/SearchView.tsx and register `/search` in src/layout/AppShell.tsx.
   It must render: the query field, an entity-type filter row driven by the `types` param, facet
   chips from the response `facets` (only when the FACETS capability is advertised), highlighted
   snippets (`highlight=true`), an offset pager, and the shared EmptyState bound to
   "no query yet" -- NOT to a zero-result query (LOOM_UI.md §7.5 rule).
6. Honest degradation, mirroring the server:
     - status.available === false  -> the sidebar field and the /search route render an explanatory
       panel naming status.provider and status.reason. No search box.
     - a 503 mid-session -> toast + the same panel. CORRECTED: ServerFailureHandler discards
       LoomRestErrorCode, so the body is only {"message": ...} - branch on the HTTP STATUS, there
       is no SEARCH_UNAVAILABLE token on the wire.
     - Do NOT render a semantic/hybrid toggle unless capabilities contains SEMANTIC/HYBRID. The
       server answers 400 for SEMANTIC today and that must stay invisible to the user.
7. Document the boolean syntax in-place: a helper line under the field reading the same contract as
   SearchQueryParameterKey.QUERY -- "quoted phrases", or, -negation. i18n keys search.* in BOTH
   en.json and de.json.
8. Update ../features/search/SEARCH.md §0 (the loom-ui row) and ../loom/ui/LOOM_UI.md §4.2 (route
   table), §5 (client inventory), §6 (contexts) in the same change.
```

**References:** [../features/search/SEARCH.md](../features/search/SEARCH.md) §0, §2, §5 ·
[SearchEndpoint.java](../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/SearchEndpoint.java) ·
[SearchQueryParameterKey.java](../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/parameter/SearchQueryParameterKey.java) ·
[../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) §0 · blocks Tasks 2, 3

**Test Requirements:**
- `loom-ui/src/api/search.test.ts` (vitest): parameter omission (no `mode=` when unset, no empty
  `types=`), array joining for `types`/`tag`/`facets`, response mapping, 503 → typed error.
- `loom-ui/e2e/search-mocked.spec.ts`: suggestions dropdown; Enter → `/ui/search?q=`; type-filter
  narrows `types=`; facet chip adds a filter; highlight snippets render; **`available:false` hides
  the sidebar field**; 503 shows the panel, not a crash.
- `loom-ui/e2e/search-backend.spec.ts`: against demo data — a term hit, a `"quoted phrase"` hit, an
  `or` hit, a `-negation` exclusion, and `mode=SEMANTIC` surfacing as a handled 400.
- Run: `cd loom-ui && npm test && ./node_modules/.bin/playwright test e2e/search-mocked.spec.ts`
  (use `./node_modules/.bin/`, not `npx` — it hangs).

---

## Task 2: Stop searching a 25-row page — DONE

> **Status: shipped.** `src/api/paging.ts`, `src/hooks/pagedList.ts` + `usePagedList.ts`,
> `src/components/ListPaging.tsx` and `src/features/assets/assetMapping.ts` all exist; sixteen list
> clients take `PagingParams`. Tests: 87 vitest cases across `paging`, `listPaging`, `pagedList` and
> `assetMapping`, plus `paging-mocked.spec.ts` (9) and `asset-search-mocked.spec.ts` (9).
>
> ⚠️ **Step 1 named the wrong wire fields.** `PagingInfo` carries **`lastUuid`, `perPage`,
> `totalCount`** — there is no `totalElements`, `currentPage` or `pageCount` on the wire, and no
> consumer ever read the invented ones. Corrected in place below; the same correction was applied
> to the 25 client modules that had copied the wrong shape.

**Argumentation Summary:** `QueryParameterKey.LIMIT` defaults to **25**. Every list client in
`loom-ui/src/api/` fetches its collection with no paging parameters — `listAssets(token)` issues a
bare `fetch` against `/assets` ([assets.ts:234](../../loom-ui/src/api/assets.ts)), and
`listCollections`, `listLibraries`, `listTags`, `listPools`, `listUsers`, `listGroups` are identical. Each view then filters that array in a `useEffect`
([AssetBrowser.tsx:459-474](../../loom-ui/src/features/assets/AssetBrowser.tsx)). So on any real
installation: the asset grid shows 25 of N assets with no indication that N ≠ 25, the search box
searches those 25, the library sidebar counts are wrong, and "0 results" is indistinguishable from
"not on this page". `LOOM_UI.md` §11.3 records "no pagination" as a *performance* concern; it is a
**correctness** one.

**Improvement Summary:** Give the list clients `limit`/`from` (keyset seek) parameters, surface the
`PagingInfo` the server already returns, and route the asset search box at `/search/assets` instead
of a local filter.

```
1. Add an optional `paging?: { limit?: number; from?: string }` argument to listAssets,
   listCollections, listLibraries, listTags, listPools, listUsers, listGroups, listRoles,
   listTokens in src/api/. Serialize as ?limit=&from= (from is the seek UUID, not an offset --
   see PagingParameters.java). Return the full response including `_metainfo` (PagingInfo:
   CORRECTED -- lastUuid, perPage, totalCount).
2. Surface the count. Every list header that today shows `items.length` must show
   metainfo.totalCount, and a "showing X of Y" line when they differ. Silent truncation is the
   bug being fixed -- do not replace it with a silent scroll.
3. AssetBrowser: replace the client-side `query` filter with a call to searchAssets() from Task 1,
   debounced 250 ms, when the query is non-empty. Keep the client-side type filter only while the
   query is empty; once a search is active it must travel as ?mime=.
   Bind EmptyState to "collection empty", the inline `assets.empty.noMatch` hint to "search
   returned nothing" -- the LOOM_UI.md §7.5 rule.
   NOTE: searchAssets returns SearchHitResponse, not AssetResponse. A hit carries no tags,
   dimensions, duration, library or collections; hitToCard() leaves those empty rather than
   inventing them and the grid hides those affordances in search mode.
   The `?library=` half of this step was dropped: `libraryFilter` had no dropdown to drive it and
   `Asset.libraryId` was always "", so it was dead state. Removed with the Status filter (step 7).
4. Add an "load more" affordance driven by the returned seek cursor for the asset grid, library
   asset list, tags tree and the admin tables. Infinite scroll is not required; a button is.
   The button must also require a cursor: without `lastUuid` the next request repeats page one,
   so `hasMorePages()` returns false rather than offering a button that cannot do anything.
5. LibraryView sidebar counts (libraryAssets.ts) currently count the fetched page. CORRECTED:
   there is no library-scoped list or count route (LibraryEndpoint registers no /:uuid/assets), so
   the ?limit=1 option does not exist. They are labelled instead -- while the asset list is
   truncated the sidebar reads "N of the M assets loaded" (`library.count.assetsPartial`).
6. Update ../loom/ui/LOOM_UI.md §11.3 and §13.3 (the "Pagination / infinite scroll" checkbox).
7. ADDED: remove the Status dropdown. `toAsset` hardcoded `status: "ready"`, so the control could
   only ever match everything or nothing -- a filter that cannot filter, sitting next to the search
   box being fixed. Its i18n keys went with it.
8. ADDED: the secondary lists that feed pickers and side panels (chat, workflow, detection,
   uploads, asset detail, admin) pass `{ limit: PAGE_SIZE }`. They are not paged UIs; they simply
   must not silently stop at 25 -- you cannot pick an asset that is not in the first page.
```

**References:** [QueryParameterKey.java:12](../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/parameter/QueryParameterKey.java) ·
[PagingParameters.java](../../loom/services/rest/src/main/java/io/metaloom/loom/rest/parameter/PagingParameters.java) ·
[PagingInfo.java](../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/common/PagingInfo.java) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §11.3 · depends on Task 1

**Test Requirements:**
- vitest per touched client: `?limit=`/`?from=` serialization, omission when unset, `metainfo`
  passthrough. Shipped as one table-driven `src/api/listPaging.test.ts` over all sixteen clients
  rather than sixteen near-identical files.
- `loom-ui/e2e/paging-mocked.spec.ts`: a mocked 100-of-300 response renders "Showing 100 of 300";
  "load more" issues a second request carrying `from=<last uuid>`; the two pages concatenate without
  duplicates, including when the route re-sends the seek boundary row.
- `loom-ui/e2e/asset-search-mocked.spec.ts`: the box hits `/search/assets`, debounces to one
  request, sends `?mime=`, and degrades honestly on 503/403.
- `loom-ui/e2e/assets-backend.spec.ts` — the existing search test asserts the request hit
  `/search/assets`, not a local filter.

> **Gotcha for anyone adding a spec.** Appending `?limit=` broke every `$`-anchored and glob mock
> matcher for a collection route (`/\/api\/v1\/assets$/`, `"**/api/v1/libraries"`) — 22 specs went
> red at once because the calls fell through to the catch-all. Write collection matchers as
> `/\/api\/v1\/<name>(\?|$)/`.

---

## Task 3: Add the missing search/filter bars — DONE

> **Status: shipped.** Five views gained a field: `tasks-search`, `skills-mine-search` /
> `skills-library-search`, `memory-search`, `chat-sessions-search`, `admin-roles-search`. Tests:
> `list-search-mocked.spec.ts` (7 passing) plus a filter case in `roles-backend.spec.ts`.
>
> ⚠️ **The audit said seven views; it was five.** `ClustersPanel` / `PersonsPanel` were never
> searchless — see the correction in §0.1 — so step 2 below does not apply and was not built.

**Argumentation Summary:** Seven listing surfaces ship no way to narrow their contents: Tasks,
Skills (both tabs), Memory, Chat Sessions, the roles/permissions admin screen (`AccessControlAdmin`,
[AdminArea.tsx:638](../../loom-ui/src/features/admin/AdminArea.tsx)), and the Clusters/Persons
panels. Every one of them is a list that grows without bound in normal use — a chat session per
conversation, a memory entry per fact, a cluster per face grouping. The pattern is already
established seven times over in the same codebase (`SearchOutlined` adornment + `useState` query +
inline "no match" hint), so the gap is oversight, not design.

**Improvement Summary:** Add the established search-field pattern to the seven views, and for the
entities that carry a `search_document` row (memory is *not* one; clusters and persons are) prefer
`searchResults(types=[...])` over a local filter.

```
1. Local-filter bars, matching CollectionsView.tsx:210-220 exactly (TextField size="small",
   SearchOutlined startAdornment, testid `<feature>-search`):
     - TasksView.tsx        -- filter on title + description; keep the existing status grouping.
     - SkillManagementView.tsx -- one field per tab (installed / library), on name + description.
     - MemoryView.tsx       -- filter on id + title across the loaded scope. CORRECTED: the list
       response (MemoryEntrySummary) carries no body, so `sessionName` stands in for it.
       `listMemory` does accept a server-side `prefix`, but it only matches the id.
     - ChatSessionsView.tsx -- filter on name + description + tags.
     - AccessControlAdmin (AdminArea.tsx) -- filter the role list, matching the sibling
       admin.<x>.search placeholders already in en.json/de.json.
2. NOT APPLICABLE -- ClustersPanel/PersonsPanel already inherit a query field from
   FaceDetectionManagement. Nothing was built here.
3. Every field needs the inline no-match hint, NOT an EmptyState (LOOM_UI.md §7.5).
4. i18n: `<feature>.search.placeholder` and `<feature>.emptyState.noSearch` in BOTH en.json and
   de.json. CORRECTED: `<feature>.empty` is already a *string* in tasks, skills and memory, so the
   no-match key cannot nest under it -- use the `emptyState` form §7.5 documents for exactly this
   collision. `admin.roles` uses `.noMatch`, matching its siblings.
5. Note in ../loom/ui/LOOM_UI.md §7.5 that the search-field pattern is expected on every list view.
6. ADDED: `skill-library-row-<name>` and `admin-role-row-<name>` testids. Neither list was
   addressable — the library skills table had only an install button, and role names collide with
   the detail heading, so a spec could not assert on either.
```

> **ChatSessionsView has no i18n namespace.** It hardcodes `"My sessions"`, `showToast("Session
> created")` and every column header. Only the new search copy is translated (`chatSessions.search.*`,
> `chatSessions.emptyState.*`); translating the rest of that view is separate work and is not done.

**References:** [CollectionsView.tsx:210](../../loom-ui/src/features/collections/CollectionsView.tsx) (reference implementation) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.5 · depends on Task 1 for step 2

**Test Requirements:** Shipped as one `loom-ui/e2e/list-search-mocked.spec.ts` covering all five
views with a "filters the list, restores on clear" case, rather than scattering a case across five
existing specs — the contract is identical everywhere and reads better in one place. It also pins
the two rules that are easy to regress: an unmatched term shows the inline hint and *not* the
EmptyState, and the two skills tabs keep independent terms. `roles-backend.spec.ts` gains a filter
case against the demo container.

---

## Task 4: E2E for the memory screens — the largest coverage hole

**Argumentation Summary:** The agent memory system (`228b0f97`, `6d454bc0`) shipped
[MemoryView.tsx](../../loom-ui/src/features/memory/MemoryView.tsx) (285 lines, `/memory`) and
`MemoryDenylistAdmin` ([AdminArea.tsx:1274](../../loom-ui/src/features/admin/AdminArea.tsx),
`/admin/memory-denylist`) with **no Playwright spec of any kind**. Thirteen testids —
`memory-view`, `memory-table`, `memory-new`, `memory-editor-{id,title,body,save}`,
`memory-delete-confirm`, `memory-empty`, `memory-empty-scopes`, `memory-denylist-{admin,add,save,
name,pattern,message,empty,error}` — are referenced by nothing in `e2e/`. The only mention of
`/memory` anywhere in the suite is a navigation click in
[routing-mocked.spec.ts:53](../../loom-ui/e2e/routing-mocked.spec.ts), which asserts the URL and
stops. The denylist is a **safety control** — a rule that silently stops matching is exactly the
failure a test is for. Compounding it, the endpoints are not even registered unless
`LOOM_AGENT_MEMORY_ENABLED=true` ([../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.8), so the
disabled path is untested too.

**Improvement Summary:** Two mocked specs covering the memory CRUD lifecycle and the denylist rule
lifecycle, plus a backend spec proving a denied pattern actually blocks a write.

```
1. loom-ui/e2e/memory-mocked.spec.ts -- route /memory, /memory/entry, /memory/scopes:
     - scope selector lists scopes; picking one loads that scope's entries into `memory-table`
     - `memory-empty-scopes` when /memory/scopes returns []
     - `memory-empty` when the scope has no entries (and the table is present -- confirm whether
       MemoryView drops it like TasksView does; if it does, record the gotcha in LOOM_UI.md §7.5)
     - create: `memory-new` -> fill editor id/title/body -> `memory-editor-save` -> POST body shape
       asserted (the id is a nested path and travels as the `id` query param, not in the route --
       see api/memory.ts:6)
     - edit an existing entry; delete via `memory-delete-confirm`
     - a 4xx on save surfaces a toast and does NOT clear the editor
     - endpoints 404 (LOOM_AGENT_MEMORY_ENABLED unset) -> an explanatory state, not a blank page
2. loom-ui/e2e/memory-denylist-mocked.spec.ts -- route /memory-deny-rules:
     - list renders; `memory-denylist-empty` on []
     - add a rule: `memory-denylist-add` -> name/pattern/message -> save -> POST asserted
     - an invalid regex from the server renders `memory-denylist-error` inline, not a toast alone
     - the `admin.memoryDenylist.search` filter narrows the table (pairs with Task 3)
     - reaching the screen requires clicking sidebar-item-/admin/memory-denylist -- it is a
       MANAGEMENT entry, NOT inside sidebar-group-acl. Verify against Sidebar.tsx before writing.
3. loom-ui/e2e/memory-backend.spec.ts -- against a demo server with LOOM_AGENT_MEMORY_ENABLED=true:
     create a rule, then attempt a memory write that matches the pattern, assert it is refused with
     the rule's message. This is the only test that proves the denylist is load-bearing.
```

**References:** [memory.ts](../../loom-ui/src/api/memory.ts) ·
[memoryDenylist.ts](../../loom-ui/src/api/memoryDenylist.ts) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.8 · commits `228b0f97`, `6d454bc0`

**Test Requirements:** The three specs above.
`cd loom-ui && ./node_modules/.bin/playwright test e2e/memory-mocked.spec.ts e2e/memory-denylist-mocked.spec.ts`

---

## Task 5: Make ProfileView testable, then test it

**Argumentation Summary:** [ProfileView.tsx](../../loom-ui/src/features/profile/ProfileView.tsx)
(240 lines, `/profile`) carries **zero `data-testid` attributes** and has zero specs. It calls
`updateUser` — it writes to the user record — and nothing verifies that the write carries the right
fields, that a failure is surfaced, or that the form repopulates. It is also the only route reachable
solely from the sidebar avatar menu, so a regression in that menu silently orphans the screen. The
repo convention is explicit: *"`data-testid` on anything an E2E spec touches"*
([../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §11.1) — the screen currently opts itself out.

**Improvement Summary:** Add testids and one mocked spec covering load, edit, save, failure and the
avatar-menu entry point.

```
1. Add data-testid attributes to ProfileView.tsx: `profile-view`, `profile-field-<name>` for each
   editable field, `profile-save`, `profile-error`, `profile-saving`.
2. Add `sidebar-avatar-menu` and `sidebar-avatar-profile` / `sidebar-avatar-logout` testids in
   src/layout/Sidebar.tsx if absent -- the menu is the only route in.
3. loom-ui/e2e/profile-mocked.spec.ts:
     - open the avatar menu, click Profile, land on /ui/profile
     - fields populate from GET /users/:uuid (the uuid comes from useAuth().userUuid, which is
       decoded from the JWT then confirmed by /me -- mock both)
     - edit a field, save, assert the PATCH/POST body carries only changed fields
     - a 403 renders `profile-error` and leaves the form editable
     - logout from the same menu returns to the login form
```

**References:** [ProfileView.tsx](../../loom-ui/src/features/profile/ProfileView.tsx) ·
[Sidebar.tsx](../../loom-ui/src/layout/Sidebar.tsx) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §4.3, §11.1

**Test Requirements:** `loom-ui/e2e/profile-mocked.spec.ts`, five cases as above.

---

## Task 6: Close the upload E2E holes

**Argumentation Summary:** Upload is the best-tested screen in the app — 11 mocked specs plus three
vitest files ([../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §8) — which makes the
remaining holes conspicuous. **Drag-and-drop is never exercised**: `upload-dropzone` appears in no
spec, and `UploadView.tsx:193` (`e.dataTransfer.files`) is a code path only a `dataTransfer`-driven
Playwright test can reach, yet the dropzone is the screen's primary affordance. Four more testids —
`upload-origin-input`, `upload-totals`, `upload-cancel-all`, `upload-retry-failed` — are likewise
unreferenced. And there is **no `uploads-backend.spec.ts`**: no test moves real bytes through
`POST /assets/upload` and reads the asset back, so the UI's multipart shaping is only ever validated
against a mock that the same author wrote.

**Improvement Summary:** Extend the mocked spec with drag-and-drop and the four bulk/queue controls,
and add a backend spec that uploads real bytes end to end.

```
1. Extend loom-ui/e2e/uploads-mocked.spec.ts:
     - drag-and-drop: build a DataTransfer in the page context, dispatch dragenter/dragover on
       `upload-dropzone` (assert the `dragging` visual state changes), then drop and assert the
       same one-request-per-file behaviour the file-input specs assert.
     - drag a *directory* entry -> zero files enqueued, no crash. LOOM_UI_UPLOAD.md §1.2 records
       folder upload as deliberately unbuilt; pin that so it fails loudly if it silently half-works.
     - `upload-origin-input`: a custom origin travels as the `origin` form field; blank omits it
       (the server defaults to "upload").
     - `upload-totals` / `upload-queue-heading`: counts and the weighted percent track the queue.
     - `upload-cancel-all` with three in-flight items -> all three end `cancelled`, none `error`.
     - `upload-retry-failed` after two failures -> exactly two new requests, successes untouched.
2. New loom-ui/e2e/uploads-backend.spec.ts (real server, demo data):
     - upload a small generated image to a demo library, poll /assets until it appears, assert
       filename, size and sha512 match, and that AssetBrowser shows a real thumbnail (not the
       MediaPlaceholder) -- this simultaneously covers the §7.2 cookie-auth preview path.
     - upload the same bytes twice -> the second is reported `duplicate`, not `error`.
     - VITE_API_BASE_URL=/api/v1 is REQUIRED for the thumbnail assertion (LOOM_UI.md §5 gotcha).
3. Update ../loom/ui/LOOM_UI_UPLOAD.md §8.2 with the new case list and the new §8.4 backend spec.
```

**References:** [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §1.2, §1.3, §8 ·
[UploadView.tsx:193](../../loom-ui/src/features/uploads/UploadView.tsx) ·
[uploads-mocked.spec.ts](../../loom-ui/e2e/uploads-mocked.spec.ts)

**Test Requirements:** The extended mocked spec (11 → ~18 cases) and the new backend spec.
`cd loom-ui && ./node_modules/.bin/playwright test e2e/uploads-mocked.spec.ts` and, with a server up,
`VITE_API_BASE_URL=/api/v1 VITE_PROXY_TARGET=http://localhost:8092 ./node_modules/.bin/playwright test e2e/uploads-backend.spec.ts`

---

## Task 7: E2E for the debug preview renderers

**Argumentation Summary:** `384fe94e` ("Update color handling, debug preview handling") added 93
lines to [NodeResultDetail.tsx](../../loom-ui/src/features/pipeline/NodeResultDetail.tsx) and
rewrote `resultRenderers.ts`, shipping four new testids — `result-element-previews`,
`result-image-note`, `result-media-path`, `node-result-more` — **none of which any spec references**.
The commit added `nodeColors.test.ts` and `resultRenderers.test.ts` (vitest), which cover the pure
mapping functions but not the rendering: whether a MANY-port element grid actually paints, whether a
missing binary degrades to the note rather than a broken `<img>`, whether "more" expands. This is the
exact surface the [MANY-port preview gotcha](../loom/ui/LOOM_UI_PIPELINE_EDITOR.md) describes, and
the preceding commit `81ad0fb4` set the standard by shipping three specs alongside its feature.

**Improvement Summary:** Extend `pipeline-node-results-mocked.spec.ts` to render each result shape
against the fixtures already in `loom-ui/scripts/fixtures/`.

```
1. Extend loom-ui/e2e/pipeline-node-results-mocked.spec.ts, reusing the payload shapes in
   loom-ui/scripts/fixtures/manifest.json and scripts/fixtures/nodes/*/fixture.json so the spec and
   the screenshot capture stay in agreement:
     - a MANY-port result with N element previews renders `result-element-previews` with N tiles
       (use the objectdetect fixture -- 30+ detections -- and assert the collapse threshold).
     - `node-result-more` expands the collapsed remainder and the count in the label matches.
     - an element whose binary 404s renders `result-image-note`, not a broken image.
     - a non-image media result renders `result-media-path` with the path text.
     - `result-preview-skipped` (already covered) still holds -- keep the existing case.
2. If the render depends on a node descriptor kind, drive it from the NodeRegistry mock the sibling
   pipeline specs already install rather than adding a second fixture source.
3. Record in ../loom/ui/LOOM_UI_PIPELINE_EDITOR.md which result shapes now have E2E coverage.
```

**References:** commit `384fe94e` ·
[NodeResultDetail.tsx](../../loom-ui/src/features/pipeline/NodeResultDetail.tsx) ·
[resultRenderers.ts](../../loom-ui/src/features/pipeline/resultRenderers.ts) ·
[pipeline-node-results-mocked.spec.ts](../../loom-ui/e2e/pipeline-node-results-mocked.spec.ts)

**Test Requirements:** Four new cases in the existing spec.
`cd loom-ui && ./node_modules/.bin/playwright test e2e/pipeline-node-results-mocked.spec.ts`

---

## Task 8: E2E for detection review and the face panels

**Argumentation Summary:** The detection review workflow — the human-in-the-loop step that makes
detections trustworthy — is untested at the UI level. Six testids are unreferenced:
`detection-bulk-toggle`, `detection-bulk-save`, `detection-confirm`, `detection-redraw`,
`objectdetection-confirm`, `objectdetection-reject`. [detections-backend.spec.ts](../../loom-ui/e2e/detections-backend.spec.ts)
covers CRUD through the API and one edit-confidence flow, but never the bulk path, and
`clusters-backend`/`persons-backend` are **one CRUD test each** with no coverage of
[ClustersPanel.tsx](../../loom-ui/src/features/faceDetection/ClustersPanel.tsx) or
[PersonsPanel.tsx](../../loom-ui/src/features/faceDetection/PersonsPanel.tsx) — the screens a user
actually operates. `FaceDetectionManagement` and `DetectionManagement` have no spec at all. With
face embeddings now persisted and indexed (`b6ee0d2e`), the cluster→person assignment flow is about
to matter more, not less.

**Improvement Summary:** One mocked spec for the detection review actions and one for the face
panels, both driven by fixture JSON rather than a live pipeline.

```
1. loom-ui/e2e/detection-review-mocked.spec.ts:
     - `detection-bulk-toggle` selects all rows; `detection-bulk-save` issues ONE
       POST /assets/:uuid/detections/bulk carrying every selected uuid (not N single requests).
     - `detection-confirm` on a single detection persists and the row's state changes.
     - `objectdetection-confirm` / `objectdetection-reject` on the object-detection screen.
     - `detection-redraw` opens the region editor and a redrawn box persists new coordinates.
     - a failed bulk save leaves the selection intact so the user can retry.
2. loom-ui/e2e/face-panels-mocked.spec.ts:
     - DetectionManagement mounts FaceDetectionManagement, ClustersPanel and PersonsPanel as panels
       -- there is no route of their own (LOOM_UI.md §4.2). Navigate via /detection and the panel
       switcher, and assert the switcher itself.
     - a cluster lists its member faces; assigning a cluster to a person issues the expected call
       and the cluster moves out of the unassigned list.
     - creating a person from a cluster; renaming; the empty states for both panels.
3. Add data-testid values where the panels lack them, following the kebab-case feature-prefixed
   convention (LOOM_UI.md §11.1).
```

**References:** [DetectionManagement.tsx](../../loom-ui/src/features/detection/DetectionManagement.tsx) ·
[ObjectDetectionManagement.tsx](../../loom-ui/src/features/detection/ObjectDetectionManagement.tsx) ·
[faceDetection/](../../loom-ui/src/features/faceDetection/) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §4.2 · commit `b6ee0d2e`

**Test Requirements:** The two specs above, plus a case in `clusters-backend.spec.ts` that exercises
the panel rather than only the API.

---

## Task 9: Close the remaining unreferenced-testid holes

**Argumentation Summary:** After Tasks 4–8, ~30 unreferenced testids remain, clustered in features
that are otherwise well covered — which is what makes them worth naming rather than leaving to a
future sweep. Each is a shipped affordance no test touches: **skills** (`skills-table`,
`skill-delete-dialog`, `skill-update-available`, `skill-version-empty`, `skill-version-loading`);
**chat sessions** (`chat-session-create-dialog`, `chat-session-save`, `chat-session-ctx-save`,
`chat-sessions-mine-tab`, `session-files-panel`, `session-files-empty`); **annotations**
(`annotation-composer`, `annotation-region-toggle`, `annotation-cancel`); **comments/tasks**
(`comment-reply`, `comment-cancel`, `tasks-comment-post`, `tasks-comment-reply-banner`,
`tasks-comment-reply-cancel`); **transcripts** (`asset-transcript-create-menu-item`,
`transcript-create-submit-button`); **cortex** (`worker-whitelist-input`, `worker-blacklist-input`);
**pipeline** (`pipeline-version-diff-empty`, `pipeline-version-diff-error`); **asset detail**
(`video-timeline-bar`); **maintenance** (`health-card-database`, `health-timestamp`).

**Improvement Summary:** Extend the existing sibling spec for each cluster — no new files needed
except where a feature has none.

```
1. Work cluster by cluster, adding cases to the existing spec:
     skills-mocked / skills-version-mocked  -- delete dialog, update-available badge, the version
       loading and empty states (LOOM_UI.md §7.5: SkillManagementView renders no <Table> when
       empty -- wait on `skills-view`, not on headers).
     chat-sessions-mocked -- create dialog, save, context save, the "mine" tab, the files panel and
       its empty state.
     annotations-mocked   -- composer open/cancel, the region toggle.
     comments-mocked / tasks-comments-mocked -- reply banner, reply cancel, post.
     transcripts-mocked   -- the create menu item and submit.
     cortex-mocked        -- whitelist/blacklist inputs and the resulting restriction call.
     pipeline-versions-mocked -- the diff empty and diff error states.
     maintenance-mocked   -- the database health card and the timestamp.
2. Asset detail's `video-timeline-bar` has no owning spec; add
   loom-ui/e2e/asset-timeline-mocked.spec.ts covering marker rendering and seek-on-click.
3. Add a guard so this does not regress: a vitest that greps src/ for data-testid values and e2e/
   for references, failing on a *growth* in the unreferenced set (baseline the current count).
   Keep it a ratchet, not an absolute gate -- some ids exist only for screenshot scripts, and the
   test must accept an explicit allowlist for those.
```

**References:** the 62-testid audit in §0.4 above · [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §11.1

**Test Requirements:** Extended cases in the eight specs named, one new asset-timeline spec, and the
ratchet test (`loom-ui/src/testCoverage.test.ts` or similar). `cd loom-ui && npm test && ./node_modules/.bin/playwright test`

---

## Task 10: Refresh the stale counts in LOOM_UI.md

**Argumentation Summary:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) states figures that the
tree has outgrown, and those figures are how an agent decides whether a spec already exists before
writing one. §3 says "30 REST/WS client modules + 12 co-located `*.test.ts`" and "64 Playwright
specs"; §8.1 says "18 test files today"; §8.2 says 32 mocked / 29 backend. Actual: **35** API
modules, **16** co-located API tests, **32** vitest files total, **73** Playwright specs (**41**
mocked, 29 backend, 3 legacy). The vitest file table in §8.1 is also missing seven entries
(`notifications`, `pipelineBreakpoints`, `pipelineRunControls`, `assetUpload`, plus the
`pipeline/{bucketListEditor,generations,nodeColors,nodePicker,nodeResultDetail,resultRenderers}` and
`tasks/commentThread`, `notifications/notificationLink` helpers). Per
[../guidelines/CODING.md](../guidelines/CODING.md) the spec must be corrected in the same change as
the code — this is the accumulated debt from several changes that were not.

**Improvement Summary:** Recount and correct §3, §8.1, §8.2, and add the newer features to §10 and
§13.

```
1. §3 project structure -- correct the api/ and e2e/ counts.
2. §8.1 -- correct "18 test files" to the real count and complete both rows of the table.
3. §8.2 -- correct 64/32/29 to the real counts; the legacy-name row still lists three files, verify.
4. §10 key components -- add MemoryView, ProfileView, NodeResultDetail, NodeResultStrip,
   UploadView/UploadContext (only UploadContext is mentioned today, in §6).
5. §13.4 Testing -- add "search UI has no tests" once Task 1 lands, or strike it once it does.
6. Add the §0.1 finding of THIS file to §11.2 gotchas: "a list view's search box filters the first
   25 rows only" -- until Task 2 lands it is the single most surprising behaviour in the UI.
7. Footer: update the git revision and date lines per ../guidelines/SPEC_RULES.md.
```

**References:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §3, §8, §10, §13 ·
[../guidelines/SPEC_RULES.md](../guidelines/SPEC_RULES.md) · [../guidelines/CODING.md](../guidelines/CODING.md)

**Test Requirements:** No test. Verify by recounting:
`ls loom-ui/e2e | wc -l`, `find loom-ui/src -name "*.test.ts*" | wc -l`,
`ls loom-ui/src/api/*.ts | grep -vc test`.

---

## Task 11: React error boundaries and a global 401 path

**Argumentation Summary:** Two shell gaps, both already unchecked in
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §13.1, that share a failure mode: the app goes blank
and the user learns nothing. There is **no error boundary anywhere** — a render throw in any feature
blanks the whole shell, and with auth held in memory the reload that follows dumps the user at the
login form having lost their work. There is **no global 401 handling** either; each call handles its
own failure, so an expired token produces a page of independently-failing widgets rather than one
"your session expired" message. The `4401` WebSocket close code already models the right behaviour on
the socket side — REST has no equivalent.

**Improvement Summary:** A route-level error boundary with a recoverable fallback, and a single
`handleResponse` 401 path that ends the session once, loudly.

```
1. src/components/ErrorBoundary.tsx -- class component, `componentDidCatch` logs, renders a
   fallback with the feature name, the error message and a "reload this view" action that resets
   the boundary rather than the page.
2. Wrap each <Route element={...}> in AppShell.tsx (and the admin routes in AdminArea.tsx) so a
   throw is contained to one screen and the sidebar survives.
3. In the shared fetch wrapper (src/api/* `handleResponse`), on a 401: emit a single app-level
   `session-expired` event. AuthProvider listens, calls logout() and shows one toast. Suppress
   duplicates -- ten parallel 401s must produce one message, not ten.
4. Preserve the requested route across the forced logout so signing back in returns the user there
   (AuthGate already renders LoginPage on the same URL -- LOOM_UI.md §11.2).
5. Tick the two boxes in ../loom/ui/LOOM_UI.md §13.1.
```

**References:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.1, §11.2, §13.1 ·
[main.tsx](../../loom-ui/src/main.tsx) · [AppShell.tsx](../../loom-ui/src/layout/AppShell.tsx)

**Test Requirements:**
- `loom-ui/e2e/error-boundary-mocked.spec.ts`: force a render throw (a mocked response of the wrong
  shape) and assert the fallback renders while `sidebar-item-/assets` stays clickable.
- `loom-ui/e2e/session-expiry-mocked.spec.ts`: every `**/api/v1/**` returns 401 → exactly one toast,
  login form, and signing in returns to the original route.

---

## Task 12: Replace the last mock data — monitoring and workflow

**Argumentation Summary:** Two screens still render invented numbers.
[MonitoringArea.tsx](../../loom-ui/src/features/monitoring/MonitoringArea.tsx) draws its ingestion,
latency, storage, task-backlog, chat-usage and annotation charts from `src/mock/data.ts` `METRICS`
(only the pipeline-run KPI is real, via `/pipelines/runs/stats`), and
[WorkflowView.tsx](../../loom-ui/src/features/workflow/WorkflowView.tsx) seeds `FACE_CLUSTERS`,
`PERSONS` and a hardcoded VLM result string. `monitoring-mocked.spec.ts` pins that every synthetic
panel carries a sample-data badge — honest, and the right interim behaviour — but a monitoring screen
whose charts are fiction is not a monitoring screen. Metrics are catalogued in
[../features/ops/METRICS.md](../features/ops/METRICS.md) (whose §3/§5 tables are parsed at runtime by
`MetricsCatalogScrapeTest`), so the naming is settled; what is missing is an endpoint to read them.

**Improvement Summary:** Land a metrics read endpoint and point the charts at it; back the workflow
panels with the real cluster/person/VLM data that now exists.

```
1. Backend first (blocking): expose the catalogued metrics over REST. Confirm against
   ../features/ops/METRICS.md §3/§5 -- those tables are parsed by MetricsCatalogScrapeTest, so the
   endpoint's series names must match them exactly (a markdown edit there can break the Java build).
2. src/api/metrics.ts + rewire MonitoringArea panel by panel. Remove each panel's sample-data badge
   only as that panel goes real -- a half-real dashboard with no badges is worse than the current
   state.
3. WorkflowView: replace FACE_CLUSTERS/PERSONS with listClusters/listPersons (the clients exist),
   and the hardcoded VLM string with the asset's `vlm` asset_json_comp payload.
4. Delete src/mock/data.ts and src/mock/services.ts once the last consumer is gone, and strike
   ../loom/ui/LOOM_UI.md §7.7 and the two §13.3 checkboxes.
```

**References:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.7, §13.3 ·
[monitoring-mocked.spec.ts](../../loom-ui/e2e/monitoring-mocked.spec.ts) ·
[../features/ops/METRICS.md](../features/ops/METRICS.md)

**Test Requirements:** Extend `monitoring-mocked.spec.ts` — each rewired panel derives from the
endpoint, a failing endpoint degrades to a warning without breaking the page (the existing pattern),
and no panel shows a sample-data badge once real. Extend `workflow-rating-mocked.spec.ts` with a
cluster/person case.

---

## Task 13: Shell refinement backlog

**Argumentation Summary:** Four remaining §13 boxes, each small and none blocking, grouped so they
are not lost: sidebar collapse is not persisted (plain `useState` in `AppShell`, despite
`LayoutContext` looking like a store); there is no route-level code splitting, so a ~3.7k-line
`PipelineEditor` and a ~1.5k-line `AdminArea` load for a user who only opens Chat; no accessibility
audit has been done (contrast, keyboard nav, ARIA) and no a11y test exists; and there is no CI wiring
for the E2E suite — 73 specs that run only when someone remembers.

**Improvement Summary:** Persist the collapse, lazy-load the heavy routes, add an axe pass, and run
the mocked suite in CI.

```
1. Persist sidebarCollapsed to localStorage key `loom-ui-sidebar-collapsed`, matching the
   ThemeContext pattern (`loom-ui-theme`). Update ../loom/ui/LOOM_UI.md §6 and §11.2.
2. React.lazy + Suspense for PipelineEditor, AdminArea, ChatWorkspace and AssetDetail in
   AppShell.tsx. Keep the Suspense fallback inside the shell so the sidebar never flashes.
3. Accessibility: add @axe-core/playwright and one spec that walks the main routes asserting no
   serious/critical violations. Fix what it finds, or record accepted violations with a reason --
   an unexplained allowlist is how a11y work quietly stops.
4. CI: run `npm test` and the mocked Playwright suite on every PR touching loom-ui/. Backend specs
   need a live server and stay manual until a compose-based job exists -- say so in the workflow
   file rather than letting them look forgotten.
5. Tick the corresponding boxes in ../loom/ui/LOOM_UI.md §13.1 and §13.4.
```

**References:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §11.3, §13.1, §13.4 ·
[AppShell.tsx](../../loom-ui/src/layout/AppShell.tsx) ·
[LayoutContext.tsx](../../loom-ui/src/context/LayoutContext.tsx)

**Test Requirements:** A vitest for the collapse persistence helper; `routing-mocked.spec.ts` gains a
"lazy route resolves after a deep link" case; the new axe spec; a green CI run.

---

_Git HEAD revision: `a63b034b`_
_Last updated: 2026-08-06 (initial audit — search coverage, list paging, E2E gap sweep across 172 testids)_
