# MetaLoom — CRUD Coverage Tasks: Collaboration / Social

> Gaps between the REST API and the Loom UI (`loom-ui/`) for the Social domain:
> **Task, Comment, Reaction**.
> Derived from REST endpoints, UI api clients/features, and e2e specs.
> Format follows [../../TASKS.template.md](../../TASKS.template.md). See [../DOMAIN.md](../DOMAIN.md).

## Coverage Summary

| Element | Create | Read | Update | Delete | E2E |
|---------|--------|------|--------|--------|-----|
| **Task** | ✅ (⚠️ priority not settable in form) | ✅ list + drawer | ✅ (⚠️ title/description only) | ✅ | ✅ CRUD (⚠️ priority/reactions/comments untested) |
| **Task → Reactions** | ❌ REST exposes full CRUD, no UI/client | ❌ | ❌ | ❌ | ❌ |
| **Task → Comments** | ❌ `TaskResponse.comments` never shown, no add-comment | ⚠️ embedded in REST, dropped by UI type | ❌ | ❌ | ❌ |
| **Comment** | ⚠️ client exists, wired to no screen | ✅ list only, read-only in AssetDetail | ⚠️ client exists, unwired | ⚠️ client exists, unwired | ❌ no spec |
| **Comment → Reactions** | ❌ REST CRUD, no UI/client | ❌ | ❌ | ❌ | ❌ |
| **Reaction (asset)** | ❌ no client fn (REST has POST) | ✅ list only, read-only | ❌ no client fn (REST has POST) | ❌ no client fn (REST has DELETE) | ❌ no spec |
| **Reaction (annotation)** | ❌ REST CRUD, no UI/client | ❌ | ❌ | ❌ | ❌ |
| **Reaction (workflow ratings)** | ❌ rating captured in local state, never persisted | — | — | — | ❌ |

Legend: ✅ integrated · ⚠️ partial/at-risk · ❌ missing.

**Not gaps (REST does not model these):** the task *status workflow*
(PENDING/REJECTED/ACCEPTED/REVIEW), task *due date*, and task *assignee* are **not**
present in the REST model — `TaskModel` exposes only `title`, `description`, `priority`
([TaskModel.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/task/TaskModel.java)),
and `assignedTo`/`reactions` are commented-out placeholders in
[TaskResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/task/TaskResponse.java).
No UI gap can exist for endpoints the backend does not expose; these are backend feature
gaps, out of scope for this UI-coverage document.


---

## Task: Show and author comments on a task

**Argumentation Summary:** `TaskResponse` embeds a `List<CommentResponse> comments`
([TaskResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/task/TaskResponse.java) lines 17, 55-62), so `GET /api/v1/tasks/:uuid` already returns task comments. The
UI silently drops them: the `TaskResponse` interface in
[tasks.ts](../../../loom-ui/src/api/tasks.ts) (lines 5-17) has no `comments` field and
[TasksView.tsx](../../../loom-ui/src/features/tasks/TasksView.tsx) never renders or adds
comments. Combined with the flat `POST /api/v1/comments` create endpoint
([CommentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CommentEndpoint.java)), the discussion thread that the domain model attaches to a task is
entirely invisible.

**Improvement Summary:** Type and render the embedded comment list in the task drawer and add
a comment-composer.

```
- Add `comments?: CommentResponse[]` to the TaskResponse interface in tasks.ts (import the
  type from comments.ts).
- Load the full task via loadTask(token, uuid) when the drawer opens (list responses may not
  populate comments) and render each comment (title/text/author/created).
- Add a composer that calls createComment(...) from comments.ts and refreshes.
  NOTE: the flat CommentCreateRequest (CommentCreateRequest.java) has no task/parent field —
  confirm with CommentEndpointService how a comment is associated to a task (likely via meta
  or a dedicated service path); wire accordingly. Threaded/parent replies are NOT modelled in
  CommentModel.java (only title/text/meta), so nested replies are a backend gap, not a UI task.
```

**References:**
- REST: [TaskEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TaskEndpoint.java), [TaskResponse.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/task/TaskResponse.java), [CommentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CommentEndpoint.java), [CommentModel.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/comment/CommentModel.java)
- UI: [tasks.ts](../../../loom-ui/src/api/tasks.ts), [comments.ts](../../../loom-ui/src/api/comments.ts), [TasksView.tsx](../../../loom-ui/src/features/tasks/TasksView.tsx)

**Test Requirements:**
- E2E: create a task, add a comment, assert it renders in the drawer.
- Unit test that `TaskResponse` deserialisation keeps the `comments` array.

---


## Task: Integrate comment reactions in the UI

**Argumentation Summary:** Comments carry their own reaction sub-resource —
`POST/GET /api/v1/comments/:commentUuid/reactions`, `GET/POST/DELETE
/api/v1/comments/:commentUuid/reactions/:reactionUuid`
([CommentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CommentEndpoint.java) lines 96-116). Nothing in the UI touches these: no
function in [reactions.ts](../../../loom-ui/src/api/reactions.ts) and no reaction UI on any
comment list. Users cannot up/down-vote or react to comments.

**Improvement Summary:** Add comment-reaction client functions and lightweight reaction
controls on rendered comments.

```
- Add to reactions.ts: listCommentReactions / createCommentReaction / updateCommentReaction /
  deleteCommentReaction targeting /comments/:commentUuid/reactions[/:reactionUuid].
- Render a compact reaction bar (thumbs up/down count) on each comment in the AssetDetail
  comments tab and the task drawer comment list.
```

**References:**
- REST: [CommentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CommentEndpoint.java), [ReactionModel.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/reaction/ReactionModel.java)
- UI: [reactions.ts](../../../loom-ui/src/api/reactions.ts), [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx)

**Test Requirements:**
- E2E: react to a comment, assert count changes, remove the reaction.
- Unit tests for the new comment-reaction client functions.

---

## Task: Add write operations for asset reactions (create/update/delete)

**Argumentation Summary:** The asset reaction sub-resource is fully CRUD on the backend —
`POST/GET /api/v1/assets/:uuid/reactions`, `GET/POST/DELETE
/api/v1/assets/:uuid/reactions/:reactionUuid`
([AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java) lines 207-238). The UI client
[reactions.ts](../../../loom-ui/src/api/reactions.ts) only implements the two read functions
(`listAssetReactions`, `loadAssetReaction`), and
[AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) (lines 98-109)
renders reactions read-only. There is no way to react to an asset or remove a reaction from
the UI.

**Improvement Summary:** Add `createAssetReaction`/`updateAssetReaction`/`deleteAssetReaction`
to the client and a reaction control to the AssetDetail reactions tab.

```
- Add the three functions to reactions.ts (POST /assets/:uuid/reactions with {type, rating};
  POST/DELETE .../:reactionUuid).
- In AssetDetail.tsx reactions tab (around line 625) add a "React" affordance (thumbs/rating)
  and per-reaction delete for the current user.
```

**References:**
- REST: [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java), [ReactionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ReactionEndpoint.java)
- UI: [reactions.ts](../../../loom-ui/src/api/reactions.ts), [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx)

**Test Requirements:**
- E2E: on an asset, add a reaction, assert it lists, delete it.
- Unit tests for the three new client functions.

---

## Task: Persist Workflow "rating" decisions as asset reactions

**Argumentation Summary:** [WorkflowView.tsx](../../../loom-ui/src/features/workflow/WorkflowView.tsx)
provides a keyboard-driven rating mode (`handleRate`, lines 830 & 200-242) whose result is
stored only in the local `ratings` state map (line 730) and never sent to the server. The
backend models exactly this: a reaction carries an integer `rating`
([ReactionModel.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/reaction/ReactionModel.java) lines 13-15) and assets accept reactions via
`POST /api/v1/assets/:uuid/reactions`. All bulk-review work in the Workflow screen is
therefore lost on reload — a real gap between an existing REST capability and the UI.

**Improvement Summary:** Wire the rating (and, optionally, the LLM approve/reject) decisions
through the asset-reaction API.

```
- Depends on the createAssetReaction client from the "asset reactions write" task.
- In WorkflowView.tsx handleRate, POST a reaction with rating=value to the current asset;
  on load, hydrate `ratings` from listAssetReactions instead of starting empty.
- The current asset id (currentAsset.id) already maps to the asset UUID (apiToWorkflowAsset).
```

**References:**
- REST: [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java), [ReactionModel.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/reaction/ReactionModel.java)
- UI: [WorkflowView.tsx](../../../loom-ui/src/features/workflow/WorkflowView.tsx), [reactions.ts](../../../loom-ui/src/api/reactions.ts)

**Test Requirements:**
- E2E: rate an asset in Workflow, reload, assert the rating persists.
- Unit test that `handleRate` triggers a reaction POST.

---

## Task: Integrate annotation reactions in the UI

**Argumentation Summary:** Annotations expose a full reaction sub-resource —
`POST/GET /api/v1/annotations/:annotationUuid/reactions`, `GET/POST/DELETE
/api/v1/annotations/:annotationUuid/reactions/:reactionUuid`
([AnnotationEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AnnotationEndpoint.java) lines 99-116). The UI has no annotation-reaction client
function and no annotation-reaction surface, so reactions on annotations (feedback/tag/chapter
markers) cannot be viewed or created.

**Improvement Summary:** Add annotation-reaction client functions and surface them wherever
annotations are rendered (asset timeline markers / annotation detail).

```
- Add to reactions.ts: listAnnotationReactions / createAnnotationReaction /
  updateAnnotationReaction / deleteAnnotationReaction targeting
  /annotations/:annotationUuid/reactions[/:reactionUuid].
- Surface a reaction control on annotation markers/detail in AssetDetail.tsx.
```

**References:**
- REST: [AnnotationEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AnnotationEndpoint.java)
- UI: [reactions.ts](../../../loom-ui/src/api/reactions.ts), [AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx)

**Test Requirements:**
- E2E: react to an annotation, assert it lists, delete it.
- Unit tests for the new annotation-reaction client functions.

---

## Task: Add e2e coverage for comments and reactions

**Argumentation Summary:** The `loom-ui/e2e/` suite has `tasks-backend.spec.ts` (task CRUD)
but **no** `comments-*.spec.ts` and **no** `reactions-*.spec.ts`. Every comment and reaction
flow across Task, Comment, Asset, and Annotation is completely untested at the e2e level, so
the integrations added by the tasks above would ship without regression protection. Even the
current read-only comment/reaction rendering in
[AssetDetail.tsx](../../../loom-ui/src/features/assetDetail/AssetDetail.tsx) is unverified.

**Improvement Summary:** Introduce dedicated backend e2e specs covering comment authoring and
reaction flows once the UI wiring lands.

```
- Add comments-backend.spec.ts: create/list/edit/delete a comment on an asset (and on a task
  once task comments are wired), asserting DOM + persistence.
- Add reactions-backend.spec.ts: create/list/delete reactions on asset, task, comment, and
  annotation targets.
- Follow the login/demo-data pattern already established in tasks-backend.spec.ts
  (admin/finger, VITE_API_BASE_URL, VITE_PROXY_TARGET).
```

**References:**
- Existing pattern: [tasks-backend.spec.ts](../../../loom-ui/e2e/tasks-backend.spec.ts)
- REST: [CommentEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/CommentEndpoint.java), [ReactionEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ReactionEndpoint.java), [TaskEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TaskEndpoint.java), [AssetEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AssetEndpoint.java), [AnnotationEndpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/AnnotationEndpoint.java)
- UI: [comments.ts](../../../loom-ui/src/api/comments.ts), [reactions.ts](../../../loom-ui/src/api/reactions.ts)

**Test Requirements:**
- New `comments-backend.spec.ts` and `reactions-backend.spec.ts` as described above.
