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


### 0.3 Is Upload covered by E2E?

**Yes, better than any other screen, and the named holes are now closed** (was Task 6).
[uploads-mocked.spec.ts](../../loom-ui/e2e/uploads-mocked.spec.ts) has 18 specs (multi-file,
drag-and-drop plus the `dragging` highlight, a file-less drop, custom/blank `origin`, queue heading
and size-weighted totals, cancel-all, retry-failed, `poolUuid` present/absent, `GET /pools` → 403,
navigate-away-mid-upload, duplicate vs failure, cancel, clear), backed by `uploadQueue.test.ts`
(288 lines), `uploadFormat.test.ts` and `assetUpload.test.ts`. All six previously unreferenced
testids — `upload-dropzone`, `upload-origin-input`, `upload-totals`, `upload-queue-heading`,
`upload-cancel-all`, `upload-retry-failed` — now have specs.

[uploads-backend.spec.ts](../../loom-ui/e2e/uploads-backend.spec.ts) closes the other half: a PNG
generated in the test goes through the screen into a real server, comes back via
`GET /assets/sha512/:hash` with matching filename/size/hash, re-downloads to the same bytes, and
renders as a decoded `<img>` in the asset grid rather than a `MediaPlaceholder` — which also covers
the cookie-authenticated preview path (LOOM_UI.md §7.2). Uploading the same bytes twice is asserted
to report `duplicate`, not `error`.

### 0.4 Have recent features been covered by E2E?

**Mostly yes — the pipeline work was disciplined about it; three recent changes were not.**

| Commit | Feature | E2E |
|--------|---------|-----|
| `97127ed2` | Notification system, task assignees | Covered — `notifications-mocked` (177 ln) + `task-assignees-mocked` (184 ln) |
| `8440f58c` | Pipeline node re-execute | Covered — `pipeline-node-reexecute-mocked` (347 ln) |
| `81ad0fb4` | Pipeline debug / breakpoints / inspect | Covered — `pipeline-breakpoints-mocked`, `pipeline-run-pause-mocked`, `pipeline-node-results-mocked` |
| `384fe94e` | Debug **preview** rendering + node colors | **Gap** — vitest only; `result-element-previews`, `result-image-note`, `result-media-path`, `node-result-more` have **no E2E** → **Task 7** |
| `228b0f97` · `6d454bc0` | Memory system + memory denylist | **Gap** — zero E2E. 13 testids across `/memory` and `/admin/memory-denylist`, none referenced → **Task 4** |
| `b6ee0d2e` | Face embedding persistence | Closed by **Task 8** — `detection-review-mocked` + `face-panels-mocked`, plus a panel-driven assignment case in `clusters-backend` |

**Repo-wide measurement:** of **172** `data-testid` values in `loom-ui/src`, **62 (36 %)** are
referenced by no spec in `loom-ui/e2e/`. [ProfileView.tsx](../../loom-ui/src/features/profile/ProfileView.tsx)
carried **no `data-testid` at all** and so could not even appear in that count — closed by
**Task 5**: the view and the sidebar avatar menu now carry testids, covered by
`profile-mocked.spec.ts`.

---

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


---


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

## Task 10: Refresh the stale counts in LOOM_UI.md — 🔨 partly closed

**Status:** steps 1-4 landed with the search index admin screen (2026-08-09): §3, §5, §8.2 and §10
were recounted against the tree and the new modules added. Steps 5-7 remain.

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
1. [x] §3 project structure -- api/ and e2e/ counts recounted (41 modules / 22 co-located tests /
       87 specs) and `StatusChip` added to the components line.
2. [ ] §8.1 -- correct "18 test files" to the real count and complete both rows of the table.
3. [x] §8.2 -- 87 specs / 53 mocked / 31 backend / 3 legacy.
4. [x] §10 key components -- `StatusChip` and `SearchIndicesAdmin` added. MemoryView, ProfileView,
       NodeResultDetail, NodeResultStrip and UploadView/UploadContext are still missing.
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

_Git HEAD revision: `27894151`_
_Last updated: 2026-08-09 (Task 10 partly closed — §3, §8.2 and §10 of LOOM_UI.md recounted and
extended while adding the search index admin screen at `/admin/indices`; §8.1 still owed. Earlier the
same day: Task 12 closed — `GET /api/v1/metrics`, the monitoring dashboard rebuilt on it, the workflow
face/person/VLM panes on the server, and `src/mock/` deleted. Earlier: Task 8 closed — detection review
and face panel E2E; the 172/62 testid measurement in §0.4 remains the 2026-08-06 baseline)_
