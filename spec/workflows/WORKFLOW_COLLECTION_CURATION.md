# Workflow: Collection Curation — Build a Set at Keyboard Speed

> **Status**: 🔵 **Proposal.** Every backend piece exists — `collection`, `collection_asset`,
> `library_collection`, `project_collection`, full CRUD, permissions, a search provider and a UI
> feature module. What is missing is a **review mode**: a way to say in/out/skip over a queue instead
> of dragging assets one at a time.
> **Complexity**: **simple.** No migration, no new node, no new permission. One `WorkflowView` mode
> and one API call.
> **Scope**: turning a search result or a folder into a curated collection, one keystroke per asset.
> **Audience**: AI coding agents working on `loom-ui/src/features/workflow/` and
> `loom-ui/src/features/collections/`.

Family index and shared anatomy: [WORKFLOWS.md](WORKFLOWS.md). Status legend: 🟢 built · 🟡 partly
built · 🔵 plan · 🔴 defect · ⚪ stub.

**Out of scope, and where it lives instead:**

| Not here | There |
|---|---|
| Collection CRUD screens, the library tree | [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md), [../loom/ui/TASK_UI_ORGANIZATION.md](../loom/ui/TASK_UI_ORGANIZATION.md) |
| Rating and tagging the same assets | [WORKFLOW_MANUAL_SORT.md](WORKFLOW_MANUAL_SORT.md) |
| The search that produces the queue | [../features/search/SEARCH.md](../features/search/SEARCH.md) |
| Perceptual similarity ("more like this") | [../concept/LUCENE_PLAN.md](../concept/LUCENE_PLAN.md) |
| Publishing a finished collection | [WORKFLOW_RIGHTS_RELEASE.md](WORKFLOW_RIGHTS_RELEASE.md) |

---

## 1. Why this is the cheapest workflow in the family

Everything it needs is built:

| Piece | State |
|---|---|
| `collection`, `collection_asset`, `collection_cluster`, `tag_collection` | 🟢 `V2.x`, with cascades |
| `library_collection`, `project_collection` | 🟢 Collections belong to libraries and projects |
| Collection REST + `loom-ui/src/api/collections.ts` | 🟢 |
| `CREATE/READ/UPDATE/DELETE_COLLECTION` permissions | 🟢 |
| Lexical search with highlighting and paging | 🟢 (`LOOM_SEARCH_ENABLED`) |
| Fingerprint similarity (`/assets/:uuid/similar-assets`) | 🟢 (`LOOM_SIMILARITY_ENABLED`, off by default) |
| The keyboard-driven review shell | 🟢 `WorkflowView` |

There is no schema work and no node work. The gap is purely that curation currently means clicking
through the assets grid.

---

## 2. Design

### 2.1 The mode

Add `"curation"` to `WorkflowMode` with a `curation-default` key profile:

| Key | Action | Effect |
|---|---|---|
| `y` / `→` | `add_to_collection` | `POST` the membership, advance |
| `n` / `Space` | `skip_asset` | Advance without writing |
| `x` | `remove_from_collection` | Remove membership (for reviewing an existing collection) |
| `←` | `prev_asset` | Back; the previous decision stays visible and is undoable |
| `Enter` | `focus_collection` | Switch the target collection mid-session |
| `f` | fullscreen | Already global |

Two panes: the asset large on the left, the target collection's current contents as a filmstrip on the
right, so a curator sees the set taking shape. That filmstrip is the whole point — it is what a grid
with checkboxes cannot give you.

### 2.2 Where the queue comes from

This is the one design decision worth making carefully. The queue should be a **saved query**, not a
snapshot, so a session can be resumed and split:

| Source | Query | State |
|---|---|---|
| A search result | `GET /api/v1/search?q=...` | 🟢 built |
| A library or folder | `GET /api/v1/libraries/:uuid/assets` | 🟢 built |
| Another collection (re-curation) | `GET /api/v1/collections/:uuid/assets` | 🟢 built |
| "More like this" | `GET /api/v1/assets/:uuid/similar-assets` | 🟢 built, off by default |
| Everything untagged / unrated | 🔴 no filter exists | shared defect X6 |

⚠️ `LOOM_SEARCH_ENABLED` defaults to **off**, and the search routes answer 503 when it is. The mode
must degrade to the library listing rather than showing an empty screen with no explanation — search
is a *capability*, not a dependency, everywhere else in this codebase.

### 2.3 What curation is worth downstream

A collection is already a first-class scope elsewhere, which is what makes this more than a UI nicety:

- **Tags are scoped by collection** — `tag` is `UNIQUE (name, collection)`. A curated collection is a
  vocabulary boundary.
- **Clusters attach to collections** — `collection_cluster` exists, so a person's appearances can be
  gathered into a collection ([WORKFLOW_FACE.md](WORKFLOW_FACE.md)).
- **Projects group collections** — `project_collection`.
- 🔵 **A pipeline could target a collection.** `PipelineMatcher` matches on mime type only today
  ([WORKFLOW_UPLOAD.md](WORKFLOW_UPLOAD.md) U5); collection-scoped triggering is the natural extension
  and would make "curate, then process this set" a single motion.

---

## 3. Progress Assessment

- [x] Collection schema, cascades, REST, permissions, UI API module, feature screens
- [x] Search and similarity as queue sources
- [x] The keyboard review shell
- [ ] 🔵 `"curation"` mode + `curation-default` profile in `WorkflowView`
- [ ] 🔵 Target-collection picker and the live filmstrip pane
- [ ] 🔵 Queue sources: search result, library, collection, similar-assets, with a 503-tolerant fallback
- [ ] 🔵 Undo on `←`
- [ ] 🔵 Batched membership writes with rollback
- [ ] Resumable progress and two curators splitting one queue (shared defect X7)
- [ ] Mocked Playwright e2e; demo collection with a partially curated queue
- [ ] Customer docs
- [ ] 🔵 Stretch: collection-scoped pipeline triggering (§2.3)

---

## 4. Test Setup

| Test | Covers |
|---|---|
| `curation.test.ts` 🔵 | Membership add/remove request shaping, batching, rollback on failure |
| `workflow-curation-mocked.spec.ts` 🔵 | Mock a search result; `y` adds and the filmstrip grows; `n` skips without a request; `←` then `x` undoes; a 503 from search falls back to the library listing with a visible message |
| `CollectionEndpointTest` 🟢 → extend | Bulk membership add, idempotent re-add, 403 without `UPDATE_COLLECTION` |

```bash
cd loom-ui && ./node_modules/.bin/vitest run src/features/workflow/curation.test.ts
cd loom-ui && ./node_modules/.bin/playwright test e2e/workflow-curation-mocked.spec.ts
mvn -pl loom/core test -Dtest=CollectionEndpointTest
```

⚠️ `npx` stalls — use `./node_modules/.bin/`. ⚠️ Playwright `role`+`name` is a substring match; a new
"Curation" toggle can shadow an existing spec's match — use `exact: true`.

---

## 5. Configuration

| Variable | Default | Effect |
|---|---|---|
| `LOOM_SEARCH_ENABLED` / `_PROVIDER` | off / — | Search-backed queues. Off ⇒ 503; fall back to library listing |
| `LOOM_SEARCH_DEFAULT_LIMIT` / `_MAX_LIMIT` / `_MAX_OFFSET` | — | Queue paging; over `_MAX_OFFSET` ⇒ 400, so deep queues must page forward, not seek |
| `LOOM_SIMILARITY_ENABLED` | off | The "more like this" queue source |

No new variable.

---

## 6. Key Classes Reference

| Class / file | Package or path | Purpose |
|---|---|---|
| `WorkflowView` | `loom-ui/src/features/workflow/WorkflowView.tsx` | Where the mode is added (`WorkflowMode` at `:63`, profiles at `:109`) |
| `collections.ts` | `loom-ui/src/api/collections.ts` | Collection + membership client |
| `search.ts` | `loom-ui/src/api/search.ts` | Queue source |
| `CollectionEndpoint` | `io.metaloom.loom.rest.endpoint.impl` | Membership routes |
| `PostgresSearchProvider` | `io.metaloom.loom.db.jooq.search` | Lexical search behind the queue |
| `LuceneSimilarityIndex` | `io.metaloom.loom.similarity.lucene` | "More like this" |
| `pagedList` hook | `loom-ui/src/hooks/pagedList.ts` | `PAGE_SIZE`, the paging convention the queue should follow |

---

## 7. Conventions and Gotchas

| Area | Gotcha |
|---|---|
| **Search is a capability, not a dependency** | ⚠️ `SearchModule` never fails boot; the routes answer 503. Degrade, do not blank the screen |
| **Deep paging is capped** | ⚠️ Over `LOOM_SEARCH_MAX_OFFSET` the request is a 400. Page forward from a cursor rather than seeking |
| **Tags are scoped per collection** | ⚠️ `UNIQUE (name, collection)`. Moving an asset between collections does not move its tags |
| **Re-adding is a no-op, not an error** | ⚠️ Keep membership writes idempotent — a curator will double-tap `y` |
| **Undo must hit the server** | ⚠️ `←` after a `y` has to remove the membership, not just move the cursor back |
| **`POST` creates and updates** | ⚠️ Everywhere in this API |

---

## 8. Where do I find …?

| Need | Look here |
|---|---|
| The review shell to extend | `loom-ui/src/features/workflow/WorkflowView.tsx` |
| Collection client + screens | `loom-ui/src/api/collections.ts`, `loom-ui/src/features/collections/` |
| Collection schema and cascades | `loom/db/flyway/.../V2.*` (`collection`, `collection_asset`, `library_collection`, `project_collection`) |
| Search | [../features/search/SEARCH.md](../features/search/SEARCH.md) |
| Similarity | [../concept/LUCENE_PLAN.md](../concept/LUCENE_PLAN.md) |
| UI organisation gap tasks | [../loom/ui/TASK_UI_ORGANIZATION.md](../loom/ui/TASK_UI_ORGANIZATION.md) |
| Open tasks | [../tasks/WORKFLOW_TASKS.md](../tasks/WORKFLOW_TASKS.md) W10 |

---

_Git HEAD revision: `21e8a8cd`_
_Last updated: 2026-08-07 (new file — proposal; no schema or node work required)_
