# TASK_UI_COLLABORATION — Collaboration / Social

Gap-analysis tasks between the Loom REST API and the Loom UI for the Collaboration
entities (Task, Comment, Reaction). Follows [../../TASKS.template.md](../../TASKS.template.md).

Scope note: the coverage matrix below is driven by the **REST routes as registered**
(the authoritative source per
[loom/services/rest/.../endpoint/impl/TaskEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TaskEndpoint.java),
[CommentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CommentEndpoint.java),
[ReactionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ReactionEndpoint.java),
[AnnotationEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AnnotationEndpoint.java),
[AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java)).

Several attributes that [DOMAIN.md](../DOMAIN.md) group 6 lists for these entities are **not
exposed by the REST layer** and therefore cannot be UI gaps against the REST API:

- **Task status** (`PENDING/REJECTED/ACCEPTED/REVIEW`), **due date**, and the
  `asset_task` / `annotation_task` links are in the DB/domain but absent from
  `Task` ([Task.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/task/Task.java)),
  `TaskCreateRequest`, `TaskUpdateRequest`, and `TaskResponse`
  ([rest-model/task](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/task/)).
  The REST task model carries only `title`, `description`, `priority` (`LOW/MEDIUM/HIGH/CRITICAL`), `meta`, `comments`.
- **Comment threading** (self-parent replies) is in the domain but `Comment`
  ([Comment.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/comment/Comment.java))
  and the comment REST models expose no `parentUuid`; comments link only to a task or asset.

These are flagged in the matrix as "N/A (not in REST)" and are addressed only where the UI
**fabricates** them from mock data (see Task 4).

## Coverage Matrix

| Entity | REST Operation (path · method) | UI Status | Where / Gap |
|--------|-------------------------------|-----------|-------------|
| Task | `/tasks` · POST (create) | Implemented | `createTask` in [tasks.ts](../../../loom-ui/src/api/tasks.ts); create dialog in [TasksView.tsx](../../../loom-ui/src/features/tasks/TasksView.tsx) |
| Task | `/tasks/:uuid` · POST (update) | Implemented | `updateTask`; edit drawer in TasksView (title/description/priority) |
| Task | `/tasks/:uuid` · DELETE | Implemented | `deleteTask`; delete dialog in TasksView |
| Task | `/tasks` · GET (list) | Implemented | `listTasks`; TasksView table |
| Task | `/tasks/:uuid` · GET (load) | Partial | `loadTask` defined in tasks.ts but never called; drawer reuses the list row object |
| Task | `/tasks/:taskUuid/reactions` · POST | Implemented | `createTaskReaction`; ReactionsPanel `onAdd` in TasksView drawer |
| Task | `/tasks/:taskUuid/reactions` · GET (list) | Implemented | `listTaskReactions`; TasksView drawer |
| Task | `/tasks/:taskUuid/reactions/:reactionUuid` · DELETE | Implemented | `deleteTaskReaction`; ReactionsPanel `onDelete` |
| Task | `/tasks/:taskUuid/reactions/:reactionUuid` · GET (load) | Missing | No `loadTaskReaction` in [reactions.ts](../../../loom-ui/src/api/reactions.ts) (low impact — list returns items) |
| Task | `/tasks/:taskUuid/reactions/:reactionUuid` · POST (update) | Partial | `updateTaskReaction` exists in reactions.ts but is **never called**; ReactionsPanel only adds/deletes → **Task 3** |
| Task | `/tasks/:taskUuid/comments` · POST (create) | Implemented | `createCommentForTask`; TasksView drawer composer |
| Task | `/tasks/:taskUuid/comments` · GET (list) | Implemented | `listCommentsForTask`; TasksView drawer |
| Comment | `/comments` · POST (create) | Partial | `createComment` in [comments.ts](../../../loom-ui/src/api/comments.ts) exists but unused (comments always created via task/asset sub-route) |
| Comment | `/comments/:uuid` · POST (update) | Partial | `updateComment` wired in [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) (asset comments) but **not** for task comments in TasksView → **Task 2** |
| Comment | `/comments/:uuid` · DELETE | Partial | `deleteComment` wired in AssetDetail only; task comments not deletable → **Task 2** |
| Comment | `/comments` · GET (list) | Partial | `listComments` exists in comments.ts but unused (no global comment view) |
| Comment | `/comments/:uuid` · GET (load) | Partial | `loadComment` exists but unused |
| Comment | `/comments/:commentUuid/reactions` · POST | Implemented | `createCommentReaction`; [CommentReactionBar.tsx](../../../loom-ui/src/features/assetDetail/CommentReactionBar.tsx) |
| Comment | `/comments/:commentUuid/reactions` · GET (list) | Implemented | `listCommentReactions`; CommentReactionBar |
| Comment | `/comments/:commentUuid/reactions/:reactionUuid` · DELETE | Implemented | `deleteCommentReaction`; CommentReactionBar toggle |
| Comment | `/comments/:commentUuid/reactions/:reactionUuid` · GET (load) | Missing | No `loadCommentReaction` in reactions.ts (low impact) |
| Comment | `/comments/:commentUuid/reactions/:reactionUuid` · POST (update) | Partial | `updateCommentReaction` exists but never called; CommentReactionBar only toggles THUMBSUP/THUMBSDOWN create/delete → **Task 3** |
| Comment (asset sub-resource) | `/assets/:uuid/comments` · POST | Implemented | `createCommentForAsset`; AssetDetail comment composer |
| Comment (asset sub-resource) | `/assets/:uuid/comments` · GET (list) | Implemented | `listCommentsForAsset`; AssetDetail |
| Reaction | `/reactions` · GET (list all) | Missing | No `listReactions` in reactions.ts; no global/admin reaction view → **Task 5** |
| Reaction | `/reactions/:uuid` · GET (load) | Missing | Not in reactions.ts → **Task 5** |
| Reaction | `/reactions/:uuid` · DELETE | Missing | Not in reactions.ts → **Task 5** |
| Reaction (asset, ReactionEndpoint) | `/reactions/assets/:assetUuid` · POST | N/A (alt path) | UI uses the asset-scoped path `/assets/:uuid/reactions` instead; this duplicate route is unused |
| Reaction (asset, ReactionEndpoint) | `/reactions/assets/:assetUuid` · GET (list) | N/A (alt path) | Same — covered functionally by the asset-scoped path |
| Reaction (asset sub-resource) | `/assets/:uuid/reactions` · POST | Implemented | `createAssetReaction`; AssetDetail + workflow rating |
| Reaction (asset sub-resource) | `/assets/:uuid/reactions` · GET (list) | Implemented | `listAssetReactions`; AssetDetail + [ratingPersistence.ts](../../../loom-ui/src/features/workflow/ratingPersistence.ts) |
| Reaction (asset sub-resource) | `/assets/:uuid/reactions/:reactionUuid` · DELETE | Implemented | `deleteAssetReaction`; AssetDetail |
| Reaction (asset sub-resource) | `/assets/:uuid/reactions/:reactionUuid` · GET (load) | Partial | `loadAssetReaction` exists but unused |
| Reaction (asset sub-resource) | `/assets/:uuid/reactions/:reactionUuid` · POST (update) | Implemented | `updateAssetReaction`; workflow star-rating persistence |
| Reaction (annotation sub-resource) | `/annotations/:annotationUuid/reactions` · POST | Missing | No annotation-reaction function in reactions.ts; [AnnotationItem.tsx](../../../loom-ui/src/features/assetDetail/AnnotationItem.tsx) shows no reactions → **Task 1** |
| Reaction (annotation sub-resource) | `/annotations/:annotationUuid/reactions` · GET (list) | Missing | → **Task 1** |
| Reaction (annotation sub-resource) | `/annotations/:annotationUuid/reactions/:reactionUuid` · DELETE | Missing | → **Task 1** |
| Reaction (annotation sub-resource) | `/annotations/:annotationUuid/reactions/:reactionUuid` · GET (load) | Missing | → **Task 1** |
| Reaction (annotation sub-resource) | `/annotations/:annotationUuid/reactions/:reactionUuid` · POST (update) | Missing | → **Task 1** |
| Task↔Asset / Task↔Annotation link | — | N/A (not in REST) | AssetDetail fabricates asset tasks from mock data → **Task 4** |
| Comment thread (self-parent reply) | — | N/A (not in REST) | No REST support; not a UI gap |

---


---


---

## Task: Support "change my reaction" via the reaction update endpoint

**Argumentation Summary:** Every reaction target exposes an update route
(`POST /{tasks|comments|assets}/:uuid/reactions/:reactionUuid`, and annotations after Task 1),
and the UI already ships `updateTaskReaction`, `updateCommentReaction`, `updateAssetReaction`
in [reactions.ts](../../../loom-ui/src/api/reactions.ts) (all covered by `reactions.test.ts`).
Yet only the workflow star-rating path
([ratingPersistence.ts](../../../loom-ui/src/features/workflow/ratingPersistence.ts)) calls an
update. The interactive reaction UIs do not: [ReactionsPanel.tsx](../../../loom-ui/src/features/reactions/ReactionsPanel.tsx)
(used by tasks and asset reactions) only supports add + delete, so a user re-reacting with a
different type creates a **second** reaction instead of changing theirs; and
[CommentReactionBar.tsx](../../../loom-ui/src/features/assetDetail/CommentReactionBar.tsx)
toggles delete-then-create rather than a single update. This under-uses the update endpoint and
produces duplicate/piled-up reactions per user.

**Improvement Summary:** When a user already has a reaction on a target and changes its type
(or rating), call the update endpoint on their existing reaction instead of creating a new one.

```
Files: loom-ui/src/features/reactions/ReactionsPanel.tsx and the callers in
       TasksView.tsx (task reactions) and AssetDetail.tsx (asset reactions);
       optionally CommentReactionBar.tsx.

1. Give ReactionsPanel an optional onUpdate(reactionUuid, type) callback (or an
   onSelect(type) that the container resolves to create-vs-update).
2. In the container, before adding: find the current user's existing reaction
   (status.creator.uuid === currentUserUuid). If present and the type differs, call
   update{Task|Asset}Reaction(token, targetUuid, existing.uuid, { type }) and replace it in
   local state; only create when the user has none.
3. CommentReactionBar: when switching from THUMBSUP to THUMBSDOWN for the same user, prefer a
   single updateCommentReaction over delete+create.

Edge cases:
  - Decide the product rule: one reaction per user per target (update-in-place) vs. many. The
    update endpoints and the workflow "no duplicates" comment imply one-per-user — apply that
    consistently.
  - Preserve `rating` when only `type` changes and vice-versa (ReactionUpdateRequest patches
    each field independently server-side).
  - Guard against unauthenticated (no token) and non-owned reactions (cannot update another
    user's reaction).
```

**References:**
- [ReactionsPanel.tsx](../../../loom-ui/src/features/reactions/ReactionsPanel.tsx), [CommentReactionBar.tsx](../../../loom-ui/src/features/assetDetail/CommentReactionBar.tsx)
- [ratingPersistence.ts](../../../loom-ui/src/features/workflow/ratingPersistence.ts) (existing create-vs-update precedent)
- [ReactionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/ReactionEndpointService.java) (`update(...)`)

**Test Requirements:**
- Test that changing an existing owned reaction's type calls `update{Task|Asset|Comment}Reaction`
  (POST to the reaction-uuid route) and does not call the create endpoint.
- Test that a first reaction still calls create, and that the local list holds at most one
  reaction per user per target.

---

---

## Task: Expose the global reaction endpoints (list / load / delete) for moderation

**Argumentation Summary:** The `ReactionEndpoint` registers three top-level operations —
`GET /reactions` (paged list), `GET /reactions/:uuid`, `DELETE /reactions/:uuid` — for
cross-target reaction browsing/moderation. The UI has no client functions for any of them
([reactions.ts](../../../loom-ui/src/api/reactions.ts) only covers the asset/task/comment
sub-resources), so there is no way to review or moderate reactions globally. Lower impact than
Tasks 1–4 (the per-target UIs already cover normal user flows), but it is a genuine unimplemented
REST capability.

**Improvement Summary:** Add API client functions for the global reaction endpoints (and,
optionally, a small admin/moderation list view) so reactions can be listed, loaded, and deleted
independent of their target.

```
Endpoints (ReactionEndpoint.java):
  GET    /api/v1/reactions            (paged list — supports paging/filter/sort params)
  GET    /api/v1/reactions/:uuid      (load)
  DELETE /api/v1/reactions/:uuid      (delete)

1. Add listReactions(token, params?), loadReaction(token, uuid), deleteReaction(token, uuid)
   to loom-ui/src/api/reactions.ts, reusing ReactionListResponse / ReactionResponseItem.
2. Optional: a moderation table (reuse the existing admin list-view pattern) that lists
   reactions and allows delete. Gate behind the DELETE_REACTION permission if the UI surfaces
   permission checks elsewhere.

Edge cases:
  - Paging: mirror how other list clients pass paging params (from/limit) if a paged view is built.
  - Deleting a reaction must refresh/optimistically remove the row.
```

**References:**
- [ReactionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ReactionEndpoint.java)
- [reactions.ts](../../../loom-ui/src/api/reactions.ts)

**Test Requirements:**
- API-client tests: `listReactions` GETs `/reactions`, `loadReaction` GETs `/reactions/:uuid`,
  `deleteReaction` DELETEs `/reactions/:uuid` (uuid URL-encoded).
- If a view is added: test it renders returned reactions and calls `deleteReaction` on remove.
