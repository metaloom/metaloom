# Ideas and Open Decisions

Follow-ups left open by the loom-ui end-to-end coverage audit of 2026-10-10 (commit `6718998a`,
"Close loom-ui e2e coverage gaps and fix the defects they found"). Each entry was found, and
confirmed by reading the code, during that audit, but not acted on: it is either a product
decision, a change outside `loom-ui/`, or bigger than a minimal fix.

This is a holding list, not a spec. An entry that gets picked up moves into the spec file named in
its "Home" line (or a new one), and is ticked here with a pointer.

Related: [METALOOM_NOTES.md](tasks/METALOOM_NOTES.md) (general scratch backlog),
[guidelines/CODING.md](guidelines/CODING.md) (definition of done).

## Progress Assessment

- [ ] 1. Share feedback deletes are not owner-checked on the server
- [ ] 2. Object detection review: reject deletes, confirm overwrites `meta`
- [ ] 3. LLM detection screen shows hard-coded data as if real
- [ ] 4. Unreachable legacy UI directories
- [ ] 5. FaceDetectionPanel cluster grouping never works
- [ ] 6. About 45 API calls throw plain `Error` instead of `ApiError`
- [ ] 7. `useMediaToken` builds the first URL of a new asset with the old token
- [ ] 8. No test runs in CI; backend specs are not gated
- [ ] 9. ShareDialog cancellation guard cannot be proven through the UI

## 1. Share feedback deletes are not owner-checked on the server

**Kind:** security, backend. **Home:** the share feature spec under [features/](features/).

`PublicShareEndpointService.deleteReaction` and `deleteComment` delete any reaction or comment on
the link by uuid. They check the session and the capability flag, never who wrote it. Every visitor
of a link can therefore delete every other visitor's feedback with a crafted request.

The UI no longer does this by accident: `ShareViewer.toggleReaction` now only toggles off a reaction
whose `authorName` matches the session's visitor name (test: `e2e/share-mocked.spec.ts`, "another
reviewer's reaction is added to, never deleted, by a click"). But the panel still shows a delete
button on every comment, and name matching is weak (two "Anonymous" visitors collide).

Suggested shape: record the share session id (or a visitor id) on `ShareReaction` / `ShareComment`,
return an `own: boolean` in the public responses, refuse deletes of rows the session does not own
with 403, and drive the UI from `own` instead of the name.

## 2. Object detection review: reject deletes, confirm overwrites `meta`

**Kind:** product decision. **Home:** [workflows/](workflows/) object review.

In `loom-ui/src/features/detection/ObjectDetectionManagement.tsx`:

- **Reject** sends `DELETE` on the detection row instead of calling the `/reject` route. The next
  pipeline run creates the same box again.
- **Confirm** overwrites the whole `meta` object instead of calling `/confirm`, so the review status
  columns are never set.
- The screen scans only the first 25 assets, one request per asset.

Not changed because `e2e/detection-review-mocked.spec.ts` asserts delete-on-reject, i.e. the
current behaviour is pinned as intended. Decide whether reject means "gone" or "rejected and
remembered", then align the screen and that spec.

## 3. LLM detection screen shows hard-coded data as if real

**Kind:** product decision. **Home:** a detection spec, once there is a backend.

`loom-ui/src/features/detection/LLMDetectionManagement.tsx` renders fixed prompts and results
("gpt-4o", asset ids a1-a7) as if they came from the server. Edit and Run have no handlers, created
prompts are not saved, and some strings are untranslated. Either hide the tab until a backend
exists or label it clearly as a preview.

## 4. Unreachable legacy UI directories

**Kind:** cleanup decision. **Home:** none (delete or keep).

Nothing reachable from the router (`loom-ui/src/main.tsx`, `loom-ui/src/layout/AppShell.tsx`)
imports these:

| Path | Note |
|------|------|
| `loom-ui/src/Dashboard/*` | Only importer of the folders below; itself imported by nothing |
| `loom-ui/src/Asset/*`, `src/Content/*`, `src/User/*` | Reached only via `Dashboard/Dashboard.tsx` |
| `loom-ui/src/Admin/AdminArea.tsx` | The live one is `src/features/admin/AdminArea.tsx` |
| `loom-ui/src/Pipeline/PipelineArea.tsx` | Reached only via `Dashboard` |
| `loom-ui/src/Theme.tsx`, `src/Welcome/WelcomePage.tsx` | Imported by nothing live |

No tests were written for them. Before deleting, check with `/usr/bin/grep -a -rn` rather than the
shell's `grep`, which skipped `PipelineEditor.tsx` during the audit and nearly produced a false
"unused" verdict.

## 5. FaceDetectionPanel cluster grouping never works

**Kind:** feature gap. **Home:** [workflows/](workflows/) face identity loop.

In `loom-ui/src/features/assetDetail/FaceDetectionPanel.tsx`, `cluster.faceIds` is always empty and
the backend never writes `meta.clusterId`, so the summary always reads "0 clusters" even when
`listAssetClusters` returns some. If `clusterId` were ever set, those faces would vanish from both
lists. A fix needs each cluster's members fetched, which is a feature rather than a patch.

## 6. About 45 API calls throw plain `Error` instead of `ApiError`

**Kind:** consistency, UX. **Home:** loom-ui API layer.

`ensureOk()` in `loom-ui/src/api/http.ts` was introduced for the detection, person, cluster,
collection, tag and reaction modules. About 45 other inline `throw new Error(...)` sites remain
(agent, binaries, chatSessions, dedup, memoryDenylist, blacklist, remixes, annotations, tasks,
chat, comments, users, pipelines, metrics, assets, shareLinks, pools, roles, groups,
chatAttachments, tokens, libraries, notifications, processors, transcripts, spaces, skills). For
those, a 401 never fires the session-expired event and the trace id is lost from failure reports.
Mechanical: replace each with `ensureOk`, adding a vitest per module touched.

## 7. `useMediaToken` builds the first URL of a new asset with the old token

**Kind:** bug. **Home:** loom-ui media playback.

`loom-ui/src/hooks/useMediaToken.ts` keeps the previous asset's token until the new one is minted,
so the first media URL for a newly selected asset carries the wrong `?mt=` credential. Reset the
token when the asset changes, and add a mocked spec that switches assets and asserts the first
media request's token.

## 8. No test runs in CI; backend specs are not gated

**Kind:** infrastructure. **Home:** [test/](test/).

- The only workflow touching the UI (`.github/workflows/helm-chart-test.yml`) runs `npm ci` and
  `npm run build`. Neither vitest nor Playwright runs anywhere in CI.
- The 34 backend-dependent specs (`*-backend.spec.ts` plus `pipeline-loading.spec.ts` and
  `pipeline-versions.spec.ts`) are not tagged or skipped. A plain `npm run test:e2e` without a
  backend fails them.

Suggested: a CI job running `vitest run` and the mocked suite
(`playwright test --grep-invert backend`, after renaming the two unsuffixed backend specs), and a
skip-unless-reachable guard in the backend specs.

## 9. ShareDialog cancellation guard cannot be proven through the UI

**Kind:** note. **Home:** none.

`ShareDialog` cancels a late link-creation answer after a close and reopen. Both call sites
(`CollectionsView`, `AssetDetail`) unmount the dialog on close, which already drops that answer, so
the race test in `e2e/share-dialog-mocked.spec.ts` passes with or without the guard. It pins the
user-visible guarantee only. If a call site ever keeps the dialog mounted, add a test that does so.

## Test Setup

All loom-ui checks run from `loom-ui/` with the local binaries (`npx` hangs in this environment):

```bash
./node_modules/.bin/tsc --noEmit -p .
./node_modules/.bin/vitest run
VITE_PORT=3110 ./node_modules/.bin/playwright test --grep-invert backend --reporter=line
```

Mocked specs follow the conventions in `e2e/library-edit-mocked.spec.ts`: catch-all
`**/api/v1/**` route registered first, specific routes after, `exact: true` on role names.

## Conventions and Gotchas

- If the first Playwright test after a source edit times out on the login form, rerun once: Vite
  re-optimises dependencies on cold start.
- Toasts are mounted in their own portal so they stay announced while a drawer or dialog is open;
  do not move them back inside the app root.

_Git HEAD revision: `6718998a`_
_Last updated: 2026-10-11_
