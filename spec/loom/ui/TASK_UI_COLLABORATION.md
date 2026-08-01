# TASK_UI_COLLABORATION — Collaboration / Social

> Open UI work items for the Collaboration entities (Task, Comment, Reaction), derived from a code
> audit of `loom-ui/` and `loom/services/rest/.../endpoint/impl/` on 2026-08-01.
> Format follows [../../TASKS.template.md](../../TASKS.template.md).
>
> **Context:** [LOOM_UI.md](LOOM_UI.md) (UI spec) · [../RESTAPI.md](../RESTAPI.md) ·
> [../DOMAIN.md](../DOMAIN.md) group 6
>
> **Ordering:** Task 1 first — the `/tasks` screen drops two fields the REST model and the api
> client already carry, so tasks created there are invisible to the asset-side task UI that renders
> them. Tasks 2–4 are independent and non-blocking.

---

## Closed — outcome records

| Task (as originally filed) | Outcome — where it landed |
|---|---|
| Annotation reactions unreachable (5 routes) | ✅ DONE — `listAnnotationReactions`/`load`/`create`/`update`/`deleteAnnotationReaction` in `loom-ui/src/api/reactions.ts` + `features/assetDetail/AnnotationReactionBar.tsx`; covered by `api/reactions.test.ts` |
| Task comments cannot be edited or deleted | ✅ DONE — `features/tasks/TasksView.tsx` imports `updateComment`/`deleteComment` (~lines 132, 143) and reuses the shared `CommentItem`; `e2e/tasks-comments-mocked.spec.ts` |
| Asset tasks fabricated from mock data | ✅ DONE — `AssetEndpoint` now registers `GET /assets/:uuid/tasks` and `POST/DELETE /assets/:uuid/tasks/:taskUuid`; `listAssetTasks`/`assignTaskToAsset` in `api/tasks.ts` drive the AssetDetail task panel via `features/assetDetail/TaskItem.tsx`; `api/tasks.test.ts`, `e2e/asset-tasks-mocked.spec.ts` |
| "Task status, due date and `asset_task` are not exposed by REST" | ✅ OBSOLETE — that premise is false at this HEAD. `TaskModel` exposes `getTaskStatus()` and `getDueDate()`, `api/tasks.ts` types carry `taskStatus`/`dueDate`, and `TaskItem.tsx` renders both (including an overdue highlight). What remains is the `/tasks` screen not writing them — Task 1 |
| `loadTask` / `loadComment` / `loadAssetReaction` have no caller | ✅ CLOSED as non-gaps — the list payloads are complete; a single-load call would be a redundant round-trip. Do not file these again |
| `POST /reactions/assets/:assetUuid` (ReactionEndpoint) unused | ✅ CLOSED as a non-gap — a duplicate of the asset sub-resource path the UI already uses |

---

## Task 1: Let the Tasks screen edit `taskStatus` and `dueDate`

**Argumentation Summary:** `TaskCreateRequest`/`TaskUpdateRequest` in
[api/tasks.ts](../../../loom-ui/src/api/tasks.ts) both declare `taskStatus`
(`PENDING|REJECTED|ACCEPTED|REVIEW`) and `dueDate`, matching
[TaskModel.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/task/TaskModel.java).
`features/tasks/TasksView.tsx` never references either field: `createTask` (~line 403) and
`updateTask` (~line 419) send only `{ title, description, priority }`, and the table/drawer show
only a priority chip. Meanwhile `features/assetDetail/TaskItem.tsx` **does** render `taskStatus`
and an overdue `dueDate`, so a task created on `/tasks` always shows up status-less and
never-due on the asset it is attached to. The workflow status that the whole task entity exists
for is unreachable from the task manager.

**Improvement Summary:** Add status and due-date controls to the task create dialog, edit drawer
and table so the `/tasks` screen writes the full REST task model.

```
File: loom-ui/src/features/tasks/TasksView.tsx

1. Mirror the existing PrioritySelect component (~line 42) with a StatusSelect over
   PENDING | REJECTED | ACCEPTED | REVIEW, i18n keys tasks.status.* (add to
   src/i18n/locales/{en,de}.json — both files, or the German build shows raw keys).
2. Add a date input for dueDate; send an ISO-8601 instant string (the backend field is
   java.time.Instant) and treat empty as omitted, not as "".
3. Include taskStatus and dueDate in the createTask (~line 403) and updateTask (~line 419)
   payloads, and hydrate the edit drawer state from selectedTask (~line 390).
4. Table: add a status chip and a due-date cell; reuse taskStatusColor exported from
   features/assetDetail/TaskItem.tsx rather than defining a second colour map.
5. Optional: filter/sort the table by status — do it client-side; there is no server-side
   task filter route.

Edge cases: a task loaded without taskStatus (legacy rows) must not default-write a status on
an unrelated edit; timezone — render the due date in local time but transmit UTC.
```

**References:** [TasksView.tsx](../../../loom-ui/src/features/tasks/TasksView.tsx) ·
[api/tasks.ts](../../../loom-ui/src/api/tasks.ts) ·
[TaskItem.tsx](../../../loom-ui/src/features/assetDetail/TaskItem.tsx) (`taskStatusColor`, overdue rule) ·
[TaskEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TaskEndpoint.java)

**Test Requirements:** extend `loom-ui/e2e/tasks-backend.spec.ts` — create a task with a status and
due date, reload, assert both persist. Add a mocked spec asserting the create/update request bodies
contain `taskStatus` and an ISO `dueDate`. Run: `cd loom-ui && yarn e2e --grep tasks`.

---

## Task 2: Allow detaching a task from an asset

**Argumentation Summary:** `unassignTaskFromAsset` exists in
[api/tasks.ts](../../../loom-ui/src/api/tasks.ts) and is covered by `api/tasks.test.ts`, but the
only caller anywhere is that test — `features/assetDetail/AssetDetail.tsx` imports
`listAssetTasks`, `assignTaskToAsset` and `createTask` and nothing else. A task attached to an
asset by mistake can never be detached from the UI; the only escape is deleting the task outright.

**Improvement Summary:** Add a detach action to the asset task panel that calls the existing
`DELETE /assets/:uuid/tasks/:taskUuid` client function.

```
1. In features/assetDetail/TaskItem.tsx add an optional onDetach callback rendered as a row
   action (confirm first — detaching is not deleting, say so in the copy).
2. In AssetDetail.tsx import unassignTaskFromAsset, call it, then refetch via listAssetTasks
   (~line 226 already holds the refetch shape used after assign at ~line 271).
3. i18n keys in both src/i18n/locales/en.json and de.json.
```

**References:** [api/tasks.ts](../../../loom-ui/src/api/tasks.ts) ·
[AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) ·
[TaskItem.tsx](../../../loom-ui/src/features/assetDetail/TaskItem.tsx) ·
[AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) (`/:uuid/tasks/:taskUuid` DELETE)

**Test Requirements:** extend `loom-ui/e2e/asset-tasks-mocked.spec.ts` with a detach step that
asserts the DELETE and the subsequent list refetch. Run: `cd loom-ui && yarn e2e --grep asset-tasks`.

---

## Task 3: Use the reaction update route instead of piling up reactions

**Argumentation Summary:** Every reaction target exposes an update route
(`POST /{tasks|comments|assets|annotations}/:uuid/reactions/:reactionUuid`), and all four
`update*Reaction` client functions exist and are unit-tested in `api/reactions.test.ts`. Only
`features/workflow/ratingPersistence.ts` (~line 34) actually calls one. The interactive surfaces do
not: [ReactionsPanel.tsx](../../../loom-ui/src/features/reactions/ReactionsPanel.tsx) declares only
`onAdd`/`onDelete` (lines 21-22), so re-reacting with a different type creates a **second**
reaction for the same user; `CommentReactionBar.tsx` and `AnnotationReactionBar.tsx` both do
delete-then-create (~lines 57-61 in each). The result is duplicate reactions per user and two
network round-trips where one would do.

**Improvement Summary:** When the current user already owns a reaction on a target, update it
in place rather than creating a new one.

```
1. ReactionsPanel.tsx: add an optional onUpdate(reactionUuid, type) alongside onAdd/onDelete
   (or a single onSelect(type) the container resolves to create-vs-update).
2. Containers (TasksView.tsx task reactions, AssetDetail.tsx asset reactions): before creating,
   find the reaction whose status.creator.uuid === currentUserUuid. If it exists and the type
   differs, call update{Task|Asset}Reaction and replace it in local state; create only when the
   user has none.
3. CommentReactionBar.tsx / AnnotationReactionBar.tsx: switching THUMBSUP↔THUMBSDOWN becomes a
   single update{Comment|Annotation}Reaction instead of delete + create.
4. Apply one product rule consistently: one reaction per user per target (this is what the
   update routes and ratingPersistence.ts already assume).

Edge cases: preserve `rating` when only `type` changes and vice-versa (the server patches each
field independently); never attempt to update another user's reaction; no token → no-op.
```

**References:** [ReactionsPanel.tsx](../../../loom-ui/src/features/reactions/ReactionsPanel.tsx) ·
[CommentReactionBar.tsx](../../../loom-ui/src/features/assetDetail/CommentReactionBar.tsx) ·
[AnnotationReactionBar.tsx](../../../loom-ui/src/features/assetDetail/AnnotationReactionBar.tsx) ·
[ratingPersistence.ts](../../../loom-ui/src/features/workflow/ratingPersistence.ts) (existing precedent) ·
[ReactionEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/ReactionEndpointService.java)

**Test Requirements:** mocked e2e (extend `e2e/comment-reactions-mocked.spec.ts` and
`e2e/asset-reactions-backend.spec.ts`): changing an owned reaction's type issues the
reaction-uuid POST and **no** create; a first reaction still creates; the list never holds two
reactions from one user for one target. Run: `cd loom-ui && yarn e2e --grep reaction`.

---

## Task 4: Expose the global reaction routes for moderation

**Argumentation Summary:** `ReactionEndpoint` registers three cross-target operations —
`GET /api/v1/reactions` (paged), `GET /api/v1/reactions/:uuid`, `DELETE /api/v1/reactions/:uuid`.
`loom-ui/src/api/reactions.ts` covers only the asset/task/comment/annotation sub-resources, so
there is no way to review or remove reactions independently of their target. Lowest priority here:
the per-target UIs cover every normal user flow, but this is a real unimplemented capability.

**Improvement Summary:** Add the three global client functions and, optionally, a small
moderation table.

```
1. Add listReactions(token, params?), loadReaction(token, uuid), deleteReaction(token, uuid) to
   loom-ui/src/api/reactions.ts, reusing ReactionListResponse / ReactionResponseItem.
2. Optional: a moderation table following the AdminArea list-view pattern, gated on
   DELETE_REACTION and registered in the AdminArea nested routes.
Edge cases: paging params must match the other list clients; delete optimistically removes the
row and refetches.
```

**References:** [ReactionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ReactionEndpoint.java) ·
[api/reactions.ts](../../../loom-ui/src/api/reactions.ts) · [LOOM_UI.md](LOOM_UI.md) §5

**Test Requirements:** add cases to `loom-ui/src/api/reactions.test.ts` for the three routes with a
URL-encoded uuid; if a view is added, a mocked spec that renders returned reactions and calls
`deleteReaction` on remove. Run: `cd loom-ui && yarn test`.

---

## No REST surface — backend prerequisites, not UI gaps

* **Comment threading** — `CommentModel` exposes only `title` and `text`; there is no `parentUuid`
  on the comment REST models, so self-parent replies described in [../DOMAIN.md](../DOMAIN.md)
  group 6 cannot be built in the UI.
* **`createComment` / `listComments`** in [api/comments.ts](../../../loom-ui/src/api/comments.ts)
  are unused by design — comments are always created through the task or asset sub-route, and
  there is no global comment view. Not a gap; do not file it again.

_Git HEAD revision: `499f71f7`_
_Last updated: 2026-08-01 (closed annotation reactions, task-comment editing and the asset-task link, retired the obsolete "status/dueDate not in REST" premise, and filed the TasksView status/dueDate gap)_
