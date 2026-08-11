# Loom UI — Task List

> Work items for `loom-ui/`, derived from a code audit on 2026-08-11 against the **working tree**
> at HEAD `8c153347` (which carries uncommitted changes in `PipelineEditor.tsx`,
> `types/nodeDescriptors.ts` and the new `e2e/pipeline-node-params-mocked.spec.ts`).
> Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) (shell spec) ·
> [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) ·
> [../loom/ui/LOOM_UI_PIPELINE_EDITOR.md](../loom/ui/LOOM_UI_PIPELINE_EDITOR.md) ·
> [../features/search/SEARCH.md](../features/search/SEARCH.md) ·
> [../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md)
>
> **Scope note.** This file holds *cross-cutting* UI work: shell-level gaps, error handling,
> accessibility, i18n plumbing and E2E coverage holes. Per-entity REST↔UI gaps stay in the
> `TASK_UI_*.md` files under [../loom/ui/](../loom/ui/) and are **not** restated here. Two findings
> from this audit are already owned there and were deliberately not duplicated:
> the mock-backed LLM detection tab (`features/detection/LLMDetectionManagement.tsx`, still
> entirely `MOCK_PROMPTS`/`MOCK_RESULTS` with a `handleCreatePrompt` that issues no request) is
> [TASK_UI_AI_ML.md](../loom/ui/TASK_UI_AI_ML.md) Task 2, and the unreachable legacy `src/` trees are
> [TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) Task 5 — see Task 15 below for the one part
> of that deletion which is a security issue rather than tidiness.
>
> **Ordering / blocking.** Ordered by severity, not by number (numbers are stable so other files can
> cite them; the gaps are earlier tasks that are now closed and were removed).
> **Task 15 is blocking** — it is a credential leak in shipped code and is a one-line fix.
> **Task 14 blocks Task 16 and Task 17**: the a11y sweep and the new admin specs both assert on
> feedback that does not exist until the silent catches are fixed.
> **Task 11 should land before Task 13**, because `React.lazy` without an error boundary turns a
> chunk-load failure into a blank page. Tasks 4, 9, 17, 18, 19, 20 are independent.
>
> **Test conventions.** "component test" = a **mocked Playwright spec**
> (`loom-ui/e2e/*-mocked.spec.ts`); pure logic = node-env vitest beside the module. There is no
> RTL/jsdom in this repo ([../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §8.1).
> **Never invoke the runners through `npx`** — it hangs in this environment. Call the binaries
> directly: `cd loom-ui && ./node_modules/.bin/vitest run <file>` and
> `cd loom-ui && ./node_modules/.bin/playwright test e2e/<file>`.
>
> **Audit gotcha.** `loom-ui/src/features/pipeline/PipelineEditor.tsx` (4910 lines, 234 KB) is
> classified as binary by GNU grep; searching it needs `grep -a`.

---

## Task 15: `src/Login/Login.tsx` logs the submitted password to the console

**Argumentation Summary:** [Login.tsx:31-40](../../loom-ui/src/Login/Login.tsx) is an MUI template
leftover whose submit handler does nothing but
`console.log({ email: data.get('email'), password: data.get('password') })`, with an
`// eslint-disable-next-line no-console` above it so the intent is unambiguous. It is currently
unreachable — `loom-ui/index.html` mounts only `/src/main.tsx`, and the legacy entry point
`src/index.js` that routes to it is orphaned — but it ships in the repository, and the file is one
accidental import away from being live. The owning cleanup task,
[TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) Task 5, treats the whole legacy tree as tidiness
and does not mention the credential log; it also still instructs "Keep `src/mock/` for now", which is
stale — `src/mock/` was deleted when the monitoring dashboard was de-mocked.

**Improvement Summary:** Delete the credential-logging handler now, independently of the larger
legacy-tree deletion, and correct the stale instruction in the owning task.

```
1. loom-ui/src/Login/Login.tsx: delete the handleSubmit body (lines 31-40) or delete the file
   outright. Deleting the file is preferred; verify nothing but src/index.js imports it:
     cd loom-ui && grep -rn "Login/Login" src/ index.html vite.config.ts
2. While there, remove the other console leftovers in the dead tree if the whole tree is not being
   deleted in the same change:
     src/Dashboard/BreadcrumbArea.tsx:28  console.log("Re-render")
     src/User/UserArea.tsx:70             console.info(`You clicked ...`)
     src/User/UserListItem.tsx:39         console.info(`You clicked ...`)
3. If the full legacy deletion is executed in the same change, do it per TASK_UI_PIPELINE.md Task 5
   -- but IGNORE its "Keep src/mock/ for now" step: src/mock/ no longer exists.
   The complete dead set is src/index.js, src/Login/, src/Dashboard/, src/User/, src/Content/,
   src/Asset/, src/Pipeline/, src/Admin/, src/Welcome/, src/Theme.tsx (~2100 LOC).
4. Also drop the now-unused import at loom-ui/src/layout/AppShell.tsx:16
   (`FaceDetectionManagement`) -- the route was replaced by `/detection` and the symbol is dead.
5. Note in ../loom/ui/LOOM_UI.md §11.2 that the "dead capitalised directories" row is closed once
   the tree is gone; drop the row rather than leaving it describing files that no longer exist.
```

**Backend dependency:** none.

**References:** [Login.tsx](../../loom-ui/src/Login/Login.tsx) ·
[AppShell.tsx](../../loom-ui/src/layout/AppShell.tsx) ·
[TASK_UI_PIPELINE.md](../loom/ui/TASK_UI_PIPELINE.md) Task 5 ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §3, §11.2

**Test Requirements:** No new test — this is a deletion. Prove nothing regressed:
`cd loom-ui && npm run build` (the `tsc && vite build` gate catches a dangling import) and the
mocked suite `cd loom-ui && ./node_modules/.bin/playwright test e2e/routing-mocked.spec.ts e2e/login.spec.ts`.
Add a grep guard to the a11y/ratchet vitest in Task 9 so no `console.log` of a password can return.

---

## Task 14: Mutations that fail silently — no toast, no error state, and a dialog that closes anyway

**Argumentation Summary:** Across the feature tree a failed write is indistinguishable from a
successful one. The worst case is
[FaceDetectionManagement.tsx](../../loom-ui/src/features/faceDetection/FaceDetectionManagement.tsx):
`handleCreatePerson` (~line 106) and `handleCreateCluster` (~line 126) wrap the API call in
`try { … } catch (e) { console.error(…) }` and then clear the form and close the dialog **outside**
the try/catch — so a rejected create looks exactly like an accepted one, and the user believes a
person exists that does not. The same file's initial load (line 82) and confirm-cluster (line 150)
are console-only too, as are
[PersonsPanel.tsx](../../loom-ui/src/features/faceDetection/PersonsPanel.tsx):37,68 and
[ClustersPanel.tsx](../../loom-ui/src/features/faceDetection/ClustersPanel.tsx):79,94 — eight sites
in one feature, none of which imports `useToast`.
[TagsView.tsx](../../loom-ui/src/features/tags/TagsView.tsx) is worse in a different way: four
mutations have **no `try`/`catch` at all** — `createTag` (:228), `apiDeleteTag` (:238), `updateTag`
on save (:249) and `updateTag` on drag-and-drop (:272) — so a rejection is an unhandled promise
rejection and the tree keeps rendering the pre-drop state with no message. Elsewhere the pattern is
`.catch(() => {})`: [AdminArea.tsx:1255-1260](../../loom-ui/src/features/admin/AdminArea.tsx)
swallows `deleteBlacklist` (a **mutation**),
[LibraryView.tsx:86](../../loom-ui/src/features/library/LibraryView.tsx) turns a failed load into
"no libraries", and [ProfileView.tsx:49](../../loom-ui/src/features/profile/ProfileView.tsx) leaves
the form blank with no explanation. `ToastProvider`/`useToast` already exist and are used correctly
by a dozen other views, so this is not a missing mechanism — it is unapplied.

**Improvement Summary:** Give every mutation a failure path the user can see, and make failed loads
say "could not load" instead of "empty".

```
1. features/faceDetection/FaceDetectionManagement.tsx:
     - import { useToast } from "../../context/ToastContext".
     - handleCreatePerson / handleCreateCluster / confirm-cluster: move the form reset and the
       dialog close INSIDE the try, after the await, so a rejection leaves the dialog open with the
       user's input intact. In catch: showToast(message, "error").
     - The load effect (~line 82): set an `error` state and render it; an empty screen must not be
       the rendering of a 500.
2. features/faceDetection/PersonsPanel.tsx (37, 68) and ClustersPanel.tsx (79, 94): same treatment
   for delete/update -- and roll back the optimistic list mutation on failure rather than leaving
   the row removed.
3. features/tags/TagsView.tsx: wrap all four mutations (228, 238, 249, 272). The drag-and-drop case
   (272) must restore the tag's previous `collection` in state when the PATCH fails, otherwise the
   tree lies about where the tag lives.
4. features/admin/AdminArea.tsx:1255-1260: replace the bare `.catch(() => {})` on deleteBlacklist
   with a toast and keep the row.
5. Loads that swallow: LibraryView.tsx:86, ProfileView.tsx:49, ChatWorkspace.tsx:263-265 and :737,
   AssetDetail.tsx:614, SkillManagementView.tsx:56-57,72, MemoryView.tsx:87,96,
   ChatSessionsView.tsx:70, TasksView.tsx:529, ObjectDetectionManagement.tsx:56,
   MaintenanceView.tsx:72. Each must distinguish "empty" from "failed" -- reuse the existing
   EmptyState component for empty and a red inline message for failed. Do NOT convert these to
   toasts alone: a toast disappears and the screen still reads as empty.
6. Views with no loading indicator at all (a slow load is indistinguishable from an empty result):
   ObjectDetectionManagement.tsx, LLMDetectionManagement.tsx, LoginPage.tsx,
   FaceDetectionManagement.tsx, PersonsPanel.tsx, ClustersPanel.tsx, FaceCrop.tsx,
   MaintenanceView.tsx, SkillsPanel.tsx. Add the same skeleton/spinner the asset grid uses.
7. Record the rule in ../loom/ui/LOOM_UI.md §11.2: "a catch that only console.errors is a bug --
   a mutation must toast and a load must render a distinct failed state".
```

**Backend dependency:** none — every route involved already returns a usable status and body
(`PersonEndpoint.java`, `ClusterEndpoint.java`, `TagEndpoint.java`, `BlacklistEndpoint.java` under
`loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/`). The UI simply discards it.

**References:** [ToastContext.tsx](../../loom-ui/src/context/ToastContext.tsx) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.5, §11.2

**Test Requirements:**
- `loom-ui/e2e/error-feedback-mocked.spec.ts`: for each of `/detection` (faces tab), `/tags` and
  `/admin/blacklist`, mock the mutation route to 500 and assert (a) an error toast appears,
  (b) the dialog stays open / the row stays present, (c) the optimistic state was rolled back.
- Extend `loom-ui/e2e/empty-states-mocked.spec.ts` with a "load fails" case per view asserting the
  failed message rather than the empty message.
- `cd loom-ui && ./node_modules/.bin/playwright test e2e/error-feedback-mocked.spec.ts e2e/empty-states-mocked.spec.ts`

---

## Task 11: React error boundaries and a global 401 path

**Argumentation Summary:** Two shell gaps, both still unchecked in
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §13.1, that share a failure mode: the app goes blank
and the user learns nothing. A repo-wide grep confirms there is **no `ErrorBoundary`, no
`componentDidCatch` and no router `errorElement` anywhere in `loom-ui/src`** — a render throw in any
feature blanks the whole shell, and with auth held in memory the reload that follows dumps the user
at the login form having lost their work. There is **no global 401 handling** either: `src/api/`
contains **36 separate private `handleResponse` implementations** and 99 open-coded `res.ok` checks
with no shared HTTP module, so an expired token produces a page of independently-failing widgets
rather than one "your session expired" message. `api/auth.ts` even exports `isJwtExpired`, and
**nothing in `src/` or `e2e/` calls it** — the detector was written and never wired. The `4401`
WebSocket close code ([pipelineEvents.ts:260](../../loom-ui/src/api/pipelineEvents.ts)) already
models the right behaviour on the socket side; REST has no equivalent.

**Improvement Summary:** Extract the one shared fetch wrapper the 36 copies should have been, hang a
single 401 path off it, and add a route-level error boundary with a recoverable fallback.

```
1. src/api/http.ts (new): export `authHeaders(token)` and `handleResponse<T>(res)`, lifted verbatim
   from any of the 36 copies so behaviour is unchanged. Migrate the modules to it in one mechanical
   pass -- this is the prerequisite for step 3 and must not change any request shape.
2. src/components/ErrorBoundary.tsx: class component, `componentDidCatch` logs, renders a fallback
   with the feature name, the error message and a "reload this view" action that resets the
   boundary rather than reloading the page.
3. Wrap each <Route element={...}> in layout/AppShell.tsx (lines 50-76) and the nested admin routes
   in features/admin/AdminArea.tsx (lines 1579-1588) so a throw is contained to one screen and the
   sidebar survives.
4. In the new handleResponse, on a 401: dispatch a single app-level `session-expired` event.
   AuthProvider (src/context/AuthContext.tsx) listens, calls logout() and shows one toast.
   Suppress duplicates -- ten parallel 401s must produce one message, not ten.
5. Wire the orphaned `isJwtExpired` (src/api/auth.ts:66): AuthProvider checks it on mount and on
   window focus, and expires the session proactively instead of waiting for the next 401. If it is
   judged unnecessary, DELETE it rather than leaving an exported detector nothing calls.
6. Preserve the requested route across the forced logout so signing back in returns the user there
   (AuthGate already renders LoginPage on the same URL -- LOOM_UI.md §11.2).
7. Tick the two boxes in ../loom/ui/LOOM_UI.md §13.1 and update the two matching §11.2 gotcha rows.
```

**Backend dependency:** none for the boundary. For step 4 the 401 body already comes from
`loom/services/rest/src/main/java/io/metaloom/loom/rest/LoomRoutingContext.java` /
`AuthenticationEndpointService.java`; no DTO change is required. Step 5 reads the `exp` claim the
server already puts in the JWT.

**References:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.1, §11.2, §13.1 ·
[main.tsx](../../loom-ui/src/main.tsx) · [AppShell.tsx](../../loom-ui/src/layout/AppShell.tsx) ·
[AuthContext.tsx](../../loom-ui/src/context/AuthContext.tsx) ·
[api/auth.ts](../../loom-ui/src/api/auth.ts)

**Test Requirements:**
- `loom-ui/src/api/http.test.ts` — vitest over the extracted wrapper: 2xx returns the parsed body,
  a 4xx throws with the status and body text, a 401 emits exactly one `session-expired` event for
  N concurrent calls.
- `loom-ui/e2e/error-boundary-mocked.spec.ts` — force a render throw (a mocked response of the
  wrong shape) and assert the fallback renders while `sidebar-item-/assets` stays clickable.
- `loom-ui/e2e/session-expiry-mocked.spec.ts` — every `**/api/v1/**` returns 401 → exactly one
  toast, the login form, and signing in returns to the original route.
- `cd loom-ui && ./node_modules/.bin/vitest run src/api/http.test.ts && ./node_modules/.bin/playwright test e2e/error-boundary-mocked.spec.ts e2e/session-expiry-mocked.spec.ts`

---

## Task 4: E2E for the memory screens, and the create-overwrites-existing bug it was hiding

**Argumentation Summary:** The agent memory system (`228b0f97`, `6d454bc0`) shipped
[MemoryView.tsx](../../loom-ui/src/features/memory/MemoryView.tsx) (322 lines, routed at `/memory`
from [AppShell.tsx:59](../../loom-ui/src/layout/AppShell.tsx)) and `MemoryDenylistAdmin`
([AdminArea.tsx:1363](../../loom-ui/src/features/admin/AdminArea.tsx), routed at
`/admin/memory-denylist`) with **no Playwright spec of any kind**. Re-measured against the working
tree, thirteen testids are still referenced by nothing in `e2e/`: `memory-view`, `memory-table`,
`memory-new`, `memory-editor-{id,title,body,save}`, `memory-delete-confirm`, `memory-empty`,
`memory-empty-scopes`, `memory-denylist-{admin,add,save,name,pattern,message,empty,error}`.
(`memory-search` is the one exception — [list-search-mocked.spec.ts:152](../../loom-ui/e2e/list-search-mocked.spec.ts)
drives it.) The only other mention of `/memory` in the suite is a navigation click in
[routing-mocked.spec.ts](../../loom-ui/e2e/routing-mocked.spec.ts), which asserts the URL and stops.
The denylist is a **safety control** — a rule that silently stops matching is exactly the failure a
test is for. Compounding it, the endpoints are not registered unless `LOOM_AGENT_MEMORY_ENABLED=true`
([../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.8), so the disabled path is untested too.

**Improvement Summary:** Two mocked specs covering the memory CRUD lifecycle and the denylist rule
lifecycle, plus a backend spec proving a denied pattern actually blocks a write — and the one bug
the absent coverage was already hiding: "New" overwrites an existing note instead of reporting a
conflict, because `createMemoryEntry` is never called (step 2).

```
1. loom-ui/e2e/memory-mocked.spec.ts -- route GET /memory/scopes, GET /memory, and
   GET|POST|PUT|DELETE /memory/entry:
     - the scope tabs render from /memory/scopes (testid `memory-scope-tab-${scope.scope}`);
       picking one re-requests /memory?scope=… and fills `memory-table`
     - `memory-empty-scopes` when /memory/scopes returns { scopes: [] }
     - MemoryView.tsx:253 renders ONE element whose testid is `memory-empty` when the scope has no
       entries and `memory-no-match` when the search box filtered them all away -- assert both
     - `memory-new` is disabled for a scope with `writable: false`; assert that, it is the only
       thing enforcing the human-curated shared scopes client-side
     - create: `memory-new` -> fill `memory-editor-id` / `-title` / `-body` -> `memory-editor-save`
       -> assert the request goes to `/memory/entry?scope=…&id=…` with body `{ body, title }`. The
       id is a nested path and travels as the `id` QUERY PARAM, not in the route (entryQuery(),
       api/memory.ts:75)
     - edit an existing entry, and rename one: handleSave (MemoryView.tsx:118) writes the new id
       and then DELETEs the old one, so assert both calls and their order
     - delete via `memory-delete-confirm`
     - a 4xx on save toasts and does NOT clear the editor (MemoryView.tsx:118-133 already does
       this correctly -- the spec locks it in)
     - endpoints 404 (LOOM_AGENT_MEMORY_ENABLED unset) -> an explanatory state, not a blank page
2. Fix the create path while writing the spec above. `createMemoryEntry` (POST, 409 on an existing
   id) is exported from api/memory.ts:120 and called from NOWHERE: `memory-new` runs the same
   `saveMemoryEntry` PUT as an edit, so creating a note whose id already exists silently
   OVERWRITES it instead of reporting the conflict -- and the rename branch will clobber a note it
   never showed the user. Route `memory-new` through createMemoryEntry, surface the 409 in the
   editor, and cover both in the spec. (If POST is judged wrong, delete the dead client function
   rather than leaving a 409-aware call nothing makes.)
3. loom-ui/e2e/memory-denylist-mocked.spec.ts -- route /memory-deny-rules (note the REST path is
   `memory-deny-rules`, NOT `memory-denylist`; the testids use the other spelling):
     - list renders rows as `memory-denylist-row-${r.name}`; `memory-denylist-empty` on { data: [] }
     - add a rule: `memory-denylist-add` -> name/pattern/message -> `memory-denylist-save` ->
       assert POST /memory-deny-rules with { name, pattern, message }
     - edit uses POST /memory-deny-rules/:uuid, not PUT (loom convention, api/memoryDenylist.ts:80)
     - `memory-denylist-toggle-${name}` flips `enabled` via that same POST
     - an invalid-regex 400 from the server renders `memory-denylist-error` inline
       (AdminArea.tsx:1518), not a toast alone
     - the search box at AdminArea.tsx:1426 narrows the table
     - reaching the screen: it is an admin TAB at /admin/memory-denylist (AdminArea.tsx:1549/1586)
       and a MANAGEMENT sidebar entry (Sidebar.tsx:95) -- it is NOT inside sidebar-group-acl
4. loom-ui/e2e/memory-backend.spec.ts -- against a demo server with LOOM_AGENT_MEMORY_ENABLED=true:
     create a rule, then attempt a memory write that matches the pattern, assert it is refused with
     the rule's message. This is the only test that proves the denylist is load-bearing.
```

**Backend dependency:** none new — the routes exist and are complete.
`loom/agent/memory/src/main/java/io/metaloom/loom/agent/memory/rest/MemoryEndpoint.java`,
`MemoryDenyRuleEndpoint.java` and their `*EndpointService` siblings; enforcement lives in
`loom/agent/memory/src/main/java/io/metaloom/loom/agent/memory/MemoryDenylist.java` and
`tool/PutMemoryTool.java`. The backend spec needs `LOOM_AGENT_MEMORY_ENABLED=true` on the server.

**References:** [memory.ts](../../loom-ui/src/api/memory.ts) ·
[memoryDenylist.ts](../../loom-ui/src/api/memoryDenylist.ts) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §7.8 · commits `228b0f97`, `6d454bc0`

**Test Requirements:** The three specs above.
`cd loom-ui && ./node_modules/.bin/playwright test e2e/memory-mocked.spec.ts e2e/memory-denylist-mocked.spec.ts`
(the backend spec runs only against a live server, per §8.3).

---

## Task 18: Collections and Libraries have CRUD but no way to put an asset in one

**Argumentation Summary:** The backend exposes full membership management —
`GET|POST|PUT /collections/{uuid}/assets` and `DELETE /collections/{uuid}/assets/{assetUuid}` in
[CollectionEndpoint.java](../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CollectionEndpoint.java),
and `GET|POST /libraries/{uuid}/assets` plus `DELETE /libraries/{uuid}/assets/{assetUuid}` in
[LibraryEndpoint.java](../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/LibraryEndpoint.java).
[api/collections.ts](../../loom-ui/src/api/collections.ts) and
[api/libraries.ts](../../loom-ui/src/api/libraries.ts) construct **none** of those four paths: the UI
can create, rename and delete a collection but cannot list its assets, add one, or remove one. A
collection is therefore an empty label — the feature's whole point is unreachable. Nothing in the
`TASK_UI_*.md` tree tracks this (`TASK_UI_ORGANIZATION.md` has only the SpaceContext and
deep-link tasks), which is why it lands here.

**Improvement Summary:** Add the four membership calls to both clients and surface them where assets
already are: a member grid on the collection/library screens and an "add to collection" action on the
asset grid selection.

```
1. loom-ui/src/api/collections.ts: listCollectionAssets(token, uuid, paging),
   addCollectionAssets(token, uuid, assetUuids[]), removeCollectionAsset(token, uuid, assetUuid).
   Use the shared pagingQuery() from api/paging.ts -- the list route is paged like every other
   collection route (?limit=&from=) and a bare fetch silently returns only 25 rows
   (LOOM_UI.md §11.3).
2. loom-ui/src/api/libraries.ts: the same three for /libraries/{uuid}/assets.
   Mirror the Java request models rather than guessing -- the single vs bulk distinction is
   already made for you in
   loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/collection/:
   CollectionAssetRequest.java (one asset), CollectionAssetBulkRequest.java /
   CollectionAssetBulkResponse.java (many). The library models sit in the sibling
   .../rest/model/library/ package.
3. features/collections/CollectionsView.tsx: a member panel under the selected collection using
   AssetThumbnail + ListPaging, with a remove action per row (testids `collection-members`,
   `collection-member-remove`, `collection-members-empty`).
4. features/library/LibraryView.tsx: the same panel for libraries. LibraryView already lists
   library assets from a different route -- reconcile the two rather than adding a second list.
5. features/assets/AssetBrowser.tsx: an "Add to collection" action on the existing selection,
   opening a collection picker that calls addCollectionAssets. Keep the picker unpaged
   ({ limit: PAGE_SIZE }) -- it is a picker, not a browsable screen.
6. Update ../loom/ui/LOOM_UI.md §10 and the TASK_UI_ORGANIZATION.md status once wired.
```

**Backend dependency:** none new — all four routes exist. Read the request/response models under
`loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/{collection,library}/` to get the
payload shape right, and confirm the required permissions in
`loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/CollectionEndpointService.java`
so the UI can hide the action rather than let it 403.

**References:** [collections.ts](../../loom-ui/src/api/collections.ts) ·
[libraries.ts](../../loom-ui/src/api/libraries.ts) ·
[../loom/ui/TASK_UI_ORGANIZATION.md](../loom/ui/TASK_UI_ORGANIZATION.md) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §11.3

**Test Requirements:**
- `loom-ui/src/api/listPaging.test.ts` — add the two new list clients to the table-driven cases.
- `loom-ui/e2e/collection-members-mocked.spec.ts` — members render, add posts the selected uuids,
  remove deletes and drops the row, empty membership renders `collection-members-empty`, and a
  second page loads via `ListPaging`.
- Extend `loom-ui/e2e/collections-backend.spec.ts` with a real add → list → remove round trip.
- `cd loom-ui && ./node_modules/.bin/vitest run src/api/listPaging.test.ts && ./node_modules/.bin/playwright test e2e/collection-members-mocked.spec.ts`

---

## Task 17: The ACL admin screens have backend specs only — no component tier

**Argumentation Summary:** Six of the ten admin screens in
[AdminArea.tsx](../../loom-ui/src/features/admin/AdminArea.tsx) — `SpacesAdmin` (:55),
`UsersAdmin` (:238), `GroupsAdmin` (:475), `AccessControlAdmin` (:660), `ApiKeysAdmin` (:1014) and
`BlacklistAdmin` (:1227) — are covered **only** by `*-backend.spec.ts` files
(`spaces-backend`, `users-backend`, `groups-backend`, `roles-backend`, `tokens-backend`,
`blacklist-backend`). Those need a live Loom server with demo data, so per §8.3 they run only when
someone remembers, and they cannot produce the cases that matter most on an RBAC screen: a 403 on
the list route, a rejected grant, a paged second page, a duplicate-name 409. The infra admin screens
next door (`indices`, `db-integrity`, `storage`) each got a mocked spec when they shipped; the ACL
screens predate that convention. Five testids — `admin-users-count`, `admin-groups-count`,
`admin-roles-count`, `admin-spaces-count`, `admin-blacklist-count` — are referenced by nothing, which
means the server's `_metainfo.totalCount` rendering on those screens has never been asserted at all.

**Improvement Summary:** One mocked spec per ACL screen, modelled on `search-indices-mocked.spec.ts`,
covering the paths a live server cannot cheaply produce.

```
1. loom-ui/e2e/admin-users-mocked.spec.ts, admin-groups-mocked.spec.ts,
   admin-permissions-mocked.spec.ts, admin-api-keys-mocked.spec.ts,
   admin-blacklist-mocked.spec.ts, admin-spaces-mocked.spec.ts.
   Each: register the catch-all route FIRST, then specific overrides (LOOM_UI.md §8.2 gotcha), and
   write collection matchers as /\/api\/v1\/users(\?|$)/ -- the list clients append ?limit=, so a
   matcher anchored on the bare path never fires.
2. Per spec, at minimum:
     - the row list renders and `admin-<entity>-count` shows _metainfo.totalCount, not the row count
     - create -> assert the POST body; a 409 keeps the dialog open with the input intact
     - edit -> assert the update goes out as POST (loom convention), not PUT
     - delete -> confirm dialog, then DELETE, then the row is gone
     - a 403 on the list route renders a permission message, not an empty table
     - `ListPaging` loads a second page from _metainfo.lastUuid
3. admin-permissions-mocked.spec.ts additionally guards TASK_UI_IDENTITY_ACCESS.md Task 1 (role
   permission edits were a silent no-op): assert the exact write the server accepts, so the bug
   cannot return silently.
4. Reaching these screens: they are admin TABS (AdminArea.tsx:1542-1552) and the sidebar entries sit
   inside `sidebar-group-acl`, which must be opened first (LOOM_UI.md §11.2).
```

**Backend dependency:** none for the mocked specs. The fixtures must match the real DTOs, so read
`UserEndpoint.java`, `GroupEndpoint.java`, `RoleEndpoint.java`, `TokenEndpoint.java`,
`BlacklistEndpoint.java` and `SpaceEndpoint.java` under
`loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/` (and their model classes) to
copy the response shape rather than inventing one. The permission write path this guards lives in
`loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/RoleEndpointService.java`.

**References:** [AdminArea.tsx](../../loom-ui/src/features/admin/AdminArea.tsx) ·
[search-indices-mocked.spec.ts](../../loom-ui/e2e/search-indices-mocked.spec.ts) (reference shape) ·
[../loom/ui/TASK_UI_IDENTITY_ACCESS.md](../loom/ui/TASK_UI_IDENTITY_ACCESS.md) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §8.2, §11.2

**Test Requirements:** The six specs above.
`cd loom-ui && ./node_modules/.bin/playwright test e2e/admin-users-mocked.spec.ts e2e/admin-groups-mocked.spec.ts e2e/admin-permissions-mocked.spec.ts e2e/admin-api-keys-mocked.spec.ts e2e/admin-blacklist-mocked.spec.ts e2e/admin-spaces-mocked.spec.ts`

---

## Task 16: Accessibility remediation — 60 unlabelled icon buttons and an unlabelled login form

**Argumentation Summary:** No accessibility audit has ever been run
([../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §13.1, §13.4), and a mechanical sweep of the tree
finds two systematic defects. **60 icon-only `<IconButton>`s carry no `aria-label`, no `title` and no
wrapping `<Tooltip title=…>`**, so a screen reader announces "button" and nothing else; 24 of them
are in [AdminArea.tsx](../../loom-ui/src/features/admin/AdminArea.tsx) (lines 159, 162, 187, 206,
381, 384, 408, 428, 580, 583, 609, 628, 813, 816, 915, 932, 1122, 1146, 1198, 1306, 1332, 1469, 1472,
1498), the rest spread over `WorkflowView` (736, 740, 1561, 1567), `ChatWorkspace` (296, 809, 939,
946), `AssetDetail` (771, 840, 916, 1571), `ZoomableImage` (203-205, the zoom controls), `TagsView`
(153, 465), `SkillManagementView` (283, 286), `MemoryView` (235, 238), `PersonsPanel` (114, 117),
`ClustersPanel` (155, 158) and singles elsewhere. **30 `<TextField>`s are placeholder-only** — no
`label`, no `aria-label` — which gives them no accessible name at all; the two that matter most are
[LoginPage.tsx:88](../../loom-ui/src/features/auth/LoginPage.tsx) (username) and `:101` (password),
the first form every user meets. Clean by contrast: all 13 `<img>` tags carry `alt`, and there are no
`<div onClick>` handlers. This is a bounded, mechanical fix, and it must land before the axe pass in
Task 13 or that spec starts life red.

**Improvement Summary:** Add accessible names, sourced from the existing i18n keys rather than
hardcoded English, then let Task 13's axe spec hold the line.

```
1. IconButtons: add `aria-label={t("<existing key>")}` using the key that already labels the action
   elsewhere on the screen. Where an icon has no adjacent text, wrap in <Tooltip title={t(...)}> --
   MUI forwards the title to the button, and the tooltip is a usability win too.
   Work file by file; AdminArea.tsx (24 sites) is the bulk of it.
2. TextFields: give each a `label` where the design allows one, otherwise
   `inputProps={{ "aria-label": t(...) }}`. Do NOT drop the existing `data-testid` from inputProps
   in the process -- the mocked specs locate these inputs by it (e.g. `.locator("input")` after
   getByTestId), so a careless rewrite turns green specs red.
   Start with LoginPage.tsx:88 and :101, then AdminArea.tsx (134, 329, 554, 785, 1096, 1284, 1433),
   TagsView.tsx (363, 371, 388), WorkflowView.tsx (298, 644), AssetDetail.tsx (1067, 1349).
3. Add the missing i18n keys to BOTH src/i18n/locales/en.json and de.json as you go -- a missing key
   renders as the raw key (LOOM_UI.md §11.2), and Task 19 adds the guard that catches it.
4. Keyboard: verify the three keyboard-driven screens still work after the change --
   WorkflowView's Y/N decision keys, the pipeline canvas, and the chat composer.
5. Tick the §13.1 accessibility box in ../loom/ui/LOOM_UI.md only once Task 13's axe spec is green;
   until then record the sweep in §13.4 as partial.
```

**Backend dependency:** none.

**References:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §13.1, §13.4 ·
[LoginPage.tsx](../../loom-ui/src/features/auth/LoginPage.tsx) ·
[AdminArea.tsx](../../loom-ui/src/features/admin/AdminArea.tsx) · Task 13 step 3 (the axe pass)

**Test Requirements:** The whole mocked suite must stay green — this change touches `inputProps` on
inputs that specs locate. `cd loom-ui && ./node_modules/.bin/playwright test` and
`cd loom-ui && npm run build`. The permanent guard is the axe spec in Task 13.

---

## Task 20: Unsaved pipeline edits are lost on any route change

**Argumentation Summary:** [PipelineEditor.tsx:3517](../../loom-ui/src/features/pipeline/PipelineEditor.tsx)
guards **one** path: selecting a different pipeline in the editor's own sidebar defers behind a
discard-confirm (`pendingSwitch` / `confirmSwitch`). Every other exit is unguarded — clicking
`/assets` in the app sidebar, following a notification deep link, or closing the tab discards the
canvas silently, and the editor is the one screen in the app where a user can lose twenty minutes of
work in one click. There is **no `beforeunload` handler and no router blocker anywhere in
`loom-ui/src` except `UploadContext.tsx:68`**, which already demonstrates the pattern for the upload
queue. [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §11.2 describes this as "the `dirty` flag warns
but nothing blocks navigation", which overstates the current behaviour: outside the pipeline list
there is no warning at all.

**Improvement Summary:** Reuse the upload queue's guard for the pipeline canvas — a `beforeunload`
while dirty and a route-change confirm — and correct the gotcha row.

```
1. Extract the guard so it is not written twice: src/hooks/useUnsavedChanges.ts taking
   (isDirty: boolean, message: string). It registers/removes the `beforeunload` listener exactly the
   way UploadContext.tsx:68-69 does.
2. features/pipeline/PipelineEditor.tsx: call it with the existing `dirty` state (set at :3512 and
   cleared on save at :3486). Reuse the existing discard-confirm dialog for in-app navigation rather
   than adding a second one.
3. In-app route changes: react-router's `useBlocker` requires a data router; this app uses
   <BrowserRouter> + <Routes> (src/main.tsx, layout/AppShell.tsx), so useBlocker is NOT available.
   Either migrate to createBrowserRouter or intercept at the Sidebar: have LayoutContext expose a
   `guard` callback that Sidebar.tsx consults before navigating. Pick one and say which in the spec;
   do not half-do both.
4. features/uploads/UploadContext.tsx: migrate it onto the new hook so there is one implementation.
5. Correct the ../loom/ui/LOOM_UI.md §11.2 row "Unsaved pipeline edits" -- it currently claims a
   warning that only exists for the in-editor pipeline switch.
```

**Backend dependency:** none.

**References:** [PipelineEditor.tsx](../../loom-ui/src/features/pipeline/PipelineEditor.tsx) ·
[UploadContext.tsx](../../loom-ui/src/features/uploads/UploadContext.tsx) ·
[../loom/ui/LOOM_UI_PIPELINE_EDITOR.md](../loom/ui/LOOM_UI_PIPELINE_EDITOR.md) ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §11.2

**Test Requirements:**
- `loom-ui/src/hooks/useUnsavedChanges.test.ts` — vitest over the pure add/remove-listener logic
  (the hook body must be split so the listener wiring is testable without a renderer, per §8.1).
- Extend `loom-ui/e2e/pipeline-crud-mocked.spec.ts`: edit a node parameter, click
  `sidebar-item-/assets`, assert the confirm dialog appears and Cancel keeps the edit.
- `cd loom-ui && ./node_modules/.bin/vitest run src/hooks/useUnsavedChanges.test.ts && ./node_modules/.bin/playwright test e2e/pipeline-crud-mocked.spec.ts`

---

## Task 19: i18n — hardcoded English toasts, four missing keys, and no parity guard

**Argumentation Summary:** A missing i18n key renders as the raw key
([../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §11.2), and nothing checks for one. Measured against
the working tree: `en.json` has 1551 keys, `de.json` 1542, and the nine keys missing from German are
all `collections.*` (`button.delete`, `button.save`, `dialog.editCollection`, `toast.created`,
`toast.updated`, `toast.createFailed`, `toast.updateFailed`, `toast.deleteFailed`, `toast.loadFailed`)
— a German user gets English mid-dialog. Four `t("key", "fallback")` calls name keys that exist in
**neither** file, so the fallback is the only thing rendering:
[AdminArea.tsx:1293,1294,1338,1339](../../loom-ui/src/features/admin/AdminArea.tsx)
(`admin.blacklist.table.name`, `.table.assetUuid`, `.dialog.name`, `.dialog.assetUuid`). Separately,
two chat-session views skip `t()` entirely and hardcode English into user-facing toasts:
[ChatSessionsView.tsx](../../loom-ui/src/features/chatSessions/ChatSessionsView.tsx) lines 84, 103,
109, 117, 122 ("Session created", "Failed to change publish state", …) and
[ChatSessionDetail.tsx](../../loom-ui/src/features/chatSessions/ChatSessionDetail.tsx) lines 124,
128, 136, 140, 152, 160, 164.

**Improvement Summary:** Fill the gaps, move the hardcoded toasts behind `t()`, and add a node-env
vitest that fails on the next divergence.

```
1. src/i18n/locales/de.json: add the nine missing collections.* keys.
2. src/i18n/locales/{en,de}.json: add the four admin.blacklist.* keys, then drop the inline
   fallbacks at AdminArea.tsx:1293,1294,1338,1339 so the locale file is the single source.
3. features/chatSessions/ChatSessionsView.tsx (84, 103, 109, 117, 122) and ChatSessionDetail.tsx
   (124, 128, 136, 140, 152, 160, 164): replace the literal toast strings with t("chatSessions.*")
   keys added to both locale files.
4. loom-ui/src/i18n/localeParity.test.ts (node-env vitest, no renderer needed):
     - flatten both JSON files and assert the key sets are equal, printing the diff on failure
     - scan src/**/*.tsx for t("…") literals and assert every key exists in en.json; allow an
       explicit allowlist for genuinely dynamic keys (they are built with template literals, so
       match only the literal-string form)
     - the scan reads the tree at test time; keep it fast by walking src/ once
5. Add "every user-facing string goes through t(); a t() with an inline fallback is a missing key,
   not a feature" to ../loom/ui/LOOM_UI.md §11.2, and note the parity test in §8.1.
```

**Backend dependency:** none.

**References:** [i18n.ts](../../loom-ui/src/i18n/i18n.ts) ·
`loom-ui/src/i18n/locales/{en,de}.json` ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §8.1, §11.2

**Test Requirements:** `loom-ui/src/i18n/localeParity.test.ts` as described, green.
`cd loom-ui && ./node_modules/.bin/vitest run src/i18n/localeParity.test.ts`

---

## Task 9: Close the remaining unreferenced-testid holes

**Argumentation Summary:** Re-measured against the working tree: of **330** distinct `data-testid`
values in `loom-ui/src`, **78 (24 %)** are referenced by no spec in `loom-ui/e2e/`. Thirteen of those
are the memory screens (Task 4), leaving **65** here. Each is a shipped affordance no test touches,
and they cluster in features that are otherwise well covered — which is what makes them worth naming
rather than leaving to a future sweep:

* **skills** — `skills-table`, `skill-delete-dialog`, `skill-update-available`,
  `skill-version-empty`, `skill-version-loading`
* **chat sessions** — `chat-session-create-dialog`, `chat-session-save`, `chat-session-ctx-save`,
  `chat-sessions-mine-tab`, `session-files-panel`, `session-files-empty`
* **annotations** — `annotation-composer`, `annotation-region-toggle`, `annotation-cancel`
* **comments / tasks** — `comment-reply`, `comment-cancel`, `tasks-comment-post`,
  `tasks-comment-reply-banner`, `tasks-comment-reply-cancel`
* **admin totals** — `admin-users-count`, `admin-groups-count`, `admin-roles-count`,
  `admin-spaces-count`, `admin-blacklist-count` (covered by Task 17 if that lands first)
* **workflow** — `dedup-confirm`, `dedup-reject`, `dedup-group-score`, `workflow-already-rated`,
  `workflow-already-tagged`, `workflow-cluster-reviewed-at`, `workflow-tags`.
  `workflow-dedup-mocked.spec.ts` drives the decision with the **Y/N keyboard shortcuts only**, so
  the buttons at [WorkflowView.tsx:507,513](../../loom-ui/src/features/workflow/WorkflowView.tsx)
  have never been clicked by a test
* **storage / maintenance / integrity** — `storage-backends`, `storage-categories`,
  `storage-categories-note`, `storage-thresholds`, `storage-loading`, `health-card-database`,
  `health-timestamp`, `db-integrity-loading`
* **persons** — `person-detail-name`, `person-detail-alias`, `person-detail-loading`,
  `person-image-upload`
* **profile** — `profile-loading`, `profile-saving`, `profile-avatar-busy`
* **search** — `search-view`, `search-hit-timecode`, `search-pager-range`, `search-indices-loading`
* **detection** — `facedetection-search`, `objectdetection-search`
* **transcripts** — `asset-transcript-create-menu-item`, `transcript-create-submit-button`
* **cortex** — `worker-whitelist-input`, `worker-blacklist-input`
* **pipeline** — `pipeline-version-diff-empty`, `pipeline-version-diff-error`
* **misc** — `video-timeline-bar`, `library-asset-count`, `chart-empty`

Note the shape of the list: it is dominated by **loading and error testids**. Those exist precisely
because someone anticipated the state, and they are the states a backend spec against healthy demo
data can never reach — only a mocked spec can.

**Improvement Summary:** Extend the existing sibling spec for each cluster — no new files needed
except where a feature has none — and add a ratchet so the unreferenced set cannot grow.

```
1. Work cluster by cluster, adding cases to the existing spec:
     skills-mocked / skills-version-mocked  -- delete dialog, update-available badge, the version
       loading and empty states (LOOM_UI.md §7.5: SkillManagementView renders no <Table> when
       empty -- wait on `skills-view`, not on headers).
     chat-sessions-mocked -- create dialog, save, context save, the "mine" tab, the files panel and
       its empty state.
     annotations-mocked   -- composer open/cancel, the region toggle.
     comments-mocked / tasks-comments-mocked -- reply banner, reply cancel, post.
     workflow-dedup-mocked -- click `dedup-confirm` / `dedup-reject` (not only Y/N) and assert the
       group score renders; workflow-rating-mocked / workflow-tagging-mocked -- the
       already-rated/already-tagged states and `workflow-cluster-reviewed-at`.
     storage-mocked / maintenance-mocked / db-integrity-mocked -- the loading states (delay the
       route rather than fulfilling immediately), the database health card, the timestamp, the
       storage thresholds/categories note.
     person-detail-mocked -- name, alias and the loading state; `person-image-upload`.
     profile-mocked -- the loading, saving and avatar-busy states.
     search-mocked / search-indices-mocked -- `search-view`, the pager range, a hit with a
       timecode, the indices loading state.
     transcripts-mocked   -- the create menu item and submit.
     cortex-mocked        -- whitelist/blacklist inputs and the resulting restriction call.
     pipeline-versions-mocked -- the diff empty and diff error states.
     empty-states-mocked  -- `chart-empty`, `library-asset-count`.
2. Asset detail's `video-timeline-bar` has no owning spec; add
   loom-ui/e2e/asset-timeline-mocked.spec.ts covering marker rendering and seek-on-click.
3. Add the ratchet: loom-ui/src/testidCoverage.test.ts (node-env vitest) greps src/ for literal
   data-testid values and e2e/ for references, and FAILS on a growth in the unreferenced set
   against a checked-in baseline count. Keep it a ratchet, not an absolute gate -- some ids exist
   only for scripts/capture-ui-screenshots.mjs, and the test must accept an explicit allowlist.
   Baseline: 78 unreferenced of 330 total as of this audit (65 after Task 4).
   Only literal `data-testid="…"` values count; the template-literal forms
   (`memory-row-${id}`, `search-index-row-${id}`, …) must be excluded or the count is noise.
4. Update the count in ../loom/ui/LOOM_UI.md §11.1 in the same change (see Task 10).
```

**Backend dependency:** none — every case is reachable with `page.route` fixtures.

**References:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §8.2, §11.1 · Task 4 (memory ids) ·
Task 17 (the admin-count ids)

**Test Requirements:** Extended cases in the specs named, one new `asset-timeline-mocked.spec.ts`,
and the ratchet test.
`cd loom-ui && ./node_modules/.bin/vitest run src/testidCoverage.test.ts && ./node_modules/.bin/playwright test`

---

## Task 10: Refresh the stale counts and broken links in LOOM_UI.md

**Argumentation Summary:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) states figures the tree has
outgrown, and those figures are how an agent decides whether a spec already exists before writing
one. The 2026-08-09 pass corrected §3 and §8.2 — and they have **already drifted again**, which is
the real finding: the counts are recounted by hand and go stale within days. Measured now:

| Claim | Says | Actual |
|-------|------|--------|
| §3 api/ modules · co-located tests · e2e specs | 41 · 22 · 87 | **44 · 24 · 92** |
| §8.1 vitest files | 41 | **47** |
| §8.2 mocked · backend · legacy | 53 · 31 · 3 | **58 · 31 · 3** |
| §13.3 API client modules | 30 | **44** |
| §13.4 vitest · mocked · backend | 42 · 52 · 31 | **47 · 58 · 31** |
| §12 "Admin sub-routes (all 7 screens)" | 7 | **10** (AdminArea.tsx:1579-1588) |

Three claims are not merely stale but wrong: §3's directory tree still lists `src/mock/` ("only two
live consumers left") and §12 still points at `loom-ui/src/mock/data.ts`, but **`src/mock/` was
deleted** — §13.3 of the same file says so. And §8 and §10 link to `PIPELINE_EDITOR.md` and
`CHAT.md`, **neither of which exists** in `spec/loom/ui/` (the files are `LOOM_UI_PIPELINE_EDITOR.md`
and, for chat, nothing). Per [../guidelines/CODING.md](../guidelines/CODING.md) the spec must be
corrected in the same change as the code; this is accumulated debt from several changes that were not.

**Improvement Summary:** Correct every figure and link, and replace hand-counting with the commands
that produce the numbers so the next drift is a one-liner to detect.

```
1. Fix the two broken links: PIPELINE_EDITOR.md -> LOOM_UI_PIPELINE_EDITOR.md in §8.1 and §10.
   CHAT.md does not exist at all -- either drop the link or point at the chat spec that does
   (check spec/features/ before writing a path).
2. Delete every reference to src/mock/: the §3 tree diagram line and the §12 "Remaining mock data"
   row. §13.3 already records the deletion.
3. §3: 44 api modules, 24 co-located tests, 92 e2e specs.
4. §8.1: "47 test files today", and complete the table -- it is missing assetUpload, dbIntegrity,
   metrics, notifications, pipelineBreakpoints, pipelineRunControls, searchIndices, storage from
   the api/ row, and monitoring/metricsPanels, notifications/notificationLink,
   pipeline/{bucketListEditor,generations,nodeColors,nodePicker,nodeResultDetail,resultRenderers},
   tasks/commentThread, uploads/{uploadFormat,uploadQueue}, workflow/tagPersistence from the
   feature-helper row.
5. §8.2: 92 specs -- 58 mocked, 31 backend, 3 legacy.
6. §8.3: replace the `npx playwright test …` example with `./node_modules/.bin/playwright test …`;
   npx hangs in this environment and the spec is currently teaching the failing command.
7. §10: add MemoryView, NodeResultDetail, NodeResultStrip and UploadView/UploadContext -- still
   missing. (ProfileView, StorageAdmin, DbIntegrityAdmin and SearchIndicesAdmin are present.)
8. §12: "Admin sub-routes" is 10 screens, and six of them now live in their own files
   (DbIntegrityAdmin.tsx, StorageAdmin.tsx, SearchIndicesAdmin.tsx) rather than in AdminArea.tsx.
9. §13.3: 44 API client modules. §13.4: 47 / 58 / 31.
10. §11.1/§11.2: fold in this audit's findings -- the 78/330 testid measurement (Task 9), the
    "console-error-only catch" rule (Task 14) and the corrected "unsaved pipeline edits" row
    (Task 20).
11. Add a "how these numbers are produced" note beside §8.2 so the next agent recounts instead of
    trusting, and footer per ../guidelines/SPEC_RULES.md (git revision + date).
```

**Backend dependency:** none.

**References:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §3, §8, §10, §11, §12, §13 ·
[../guidelines/SPEC_RULES.md](../guidelines/SPEC_RULES.md) ·
[../guidelines/CODING.md](../guidelines/CODING.md)

**Test Requirements:** No test. Verify by recounting, from `loom-ui/`:
```
ls src/api/*.ts | grep -vc '\.test\.'          # api modules            -> 44
ls src/api/*.test.ts | wc -l                   # co-located api tests   -> 24
find src -name '*.test.ts*' | wc -l            # vitest files           -> 47
ls e2e/*.spec.ts | wc -l                       # all specs              -> 92
ls e2e/*-mocked.spec.ts | wc -l                # mocked                 -> 58
ls e2e/*-backend.spec.ts | wc -l               # backend                -> 31
```

---

## Task 13: Shell refinement backlog

**Argumentation Summary:** Four remaining §13 boxes, each small and none blocking, grouped so they
are not lost. **Sidebar collapse is not persisted** — plain `useState` at
[AppShell.tsx:33](../../loom-ui/src/layout/AppShell.tsx) despite
[LayoutContext](../../loom-ui/src/context/LayoutContext.tsx) looking like a store, while
`ThemeContext` persists its mode two files away. **There is no route-level code splitting**: a
4910-line `PipelineEditor.tsx` (234 KB) and a 1600-line `AdminArea.tsx` are in the first bundle a
user who only opens Chat downloads; no `React.lazy` appears anywhere in `src/`. **No accessibility
test exists** and no axe dependency is installed. **There is no CI wiring at all** — `.github/`
contains only `copilot-instructions.md`, there is no `workflows/` directory anywhere in the repo, so
all 92 specs run only when someone remembers.

**Improvement Summary:** Persist the collapse, lazy-load the heavy routes, add an axe pass, and run
the mocked suite in CI.

```
1. Persist sidebarCollapsed to localStorage key `loom-ui-sidebar-collapsed`, matching the
   ThemeContext pattern (`loom-ui-theme`). Extract the read/write into a pure helper so it is
   vitest-able without a renderer (§8.1). Update ../loom/ui/LOOM_UI.md §6, §10 and §11.2.
2. React.lazy + Suspense for PipelineEditor, AdminArea, ChatWorkspace and AssetDetail in
   layout/AppShell.tsx. Keep the Suspense fallback INSIDE the shell so the sidebar never flashes.
   Land Task 11 first: without an error boundary a failed chunk fetch is a blank page, which is a
   worse failure than the bundle size it fixes.
3. Accessibility: add @axe-core/playwright (devDependency) and loom-ui/e2e/a11y-mocked.spec.ts
   walking /, /assets, /pipelines, /admin/users, /detection and /memory, asserting no
   serious/critical violations. Task 16 must land first or this starts red. Record any accepted
   violation with a reason in the spec -- an unexplained allowlist is how a11y work quietly stops.
4. CI: add .github/workflows/loom-ui.yml running, on every PR touching loom-ui/:
     npm ci
     npm run build                                  # the tsc --noEmit gate
     ./node_modules/.bin/vitest run
     ./node_modules/.bin/playwright test e2e/*-mocked.spec.ts
   Do NOT use npx in the workflow. Backend specs need a live server and stay manual until a
   compose-based job exists -- say so in a comment in the workflow file rather than letting them
   look forgotten.
5. Tick the corresponding boxes in ../loom/ui/LOOM_UI.md §13.1 and §13.4.
```

**Backend dependency:** none for steps 1-4. A future compose-based CI job for the 31
`*-backend.spec.ts` files needs a Loom server with demo data and `NodeDescriptorEndpoint` active
(`loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/NodeDescriptorEndpoint.java`),
per [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §8.3.

**References:** [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §11.3, §13.1, §13.4 ·
[AppShell.tsx](../../loom-ui/src/layout/AppShell.tsx) ·
[LayoutContext.tsx](../../loom-ui/src/context/LayoutContext.tsx) ·
[ThemeContext.tsx](../../loom-ui/src/context/ThemeContext.tsx) (persistence pattern)

**Test Requirements:**
- `loom-ui/src/layout/sidebarCollapse.test.ts` — vitest for the persistence helper.
- `loom-ui/e2e/routing-mocked.spec.ts` gains a "lazy route resolves after a deep link" case.
- `loom-ui/e2e/a11y-mocked.spec.ts` — the axe pass.
- A green CI run on the new workflow.
- `cd loom-ui && ./node_modules/.bin/vitest run src/layout/sidebarCollapse.test.ts && ./node_modules/.bin/playwright test e2e/routing-mocked.spec.ts e2e/a11y-mocked.spec.ts`

---

_Git HEAD revision: `8c153347`_
_Last updated: 2026-08-11 (code audit)_
