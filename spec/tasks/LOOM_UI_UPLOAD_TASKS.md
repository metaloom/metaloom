# Loom UI Upload — Task List

> Work items for the `/uploads` screen and the background upload queue, derived from a code audit on
> 2026-08-16 against HEAD `67000540`. Format follows [TASKS.template.md](TASKS.template.md).
>
> **Context:** [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) (technical spec — current
> state only) · [../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) (shell, test policy) ·
> [../features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md) (endpoint
> contract, storage layout) · [LOOM_UI_TASKS.md](LOOM_UI_TASKS.md) (cross-cutting UI work — no upload
> items there, deliberately)
>
> **Ordering / blocking.** Ordered by severity. **Task 1 is the only defect** — everything else is a
> missing capability. Task 5 (resumable upload) blocks Task 6 (persisted queue) and would make Task 4
> (folder upload) far more useful; Task 4 is otherwise independent. Task 5 also blocks Task 9 and the
> remaining half of Task 7. Task 3 is small and independent of everything else.
> **Tasks 2 and 7 are closed** (2026-08-16).

---

## Not planned

- **Persisted queue across browser reloads on its own.** A `File` handle cannot be revived from
  `localStorage`, so a restored queue would still have to ask the user to re-pick every file. It only
  becomes worth building on top of resumable upload (Task 5), which is why it is Task 6 there rather
  than a standalone item.
- **Reworking the upload transport off `XMLHttpRequest`.** `fetch` exposes no upload-progress event
  and request-body streaming is not portable enough to substitute. The single XHR in `src/api/` is
  deliberate; see [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §2.1.

---

## Task 1: Clear the upload queue and its token on logout

**Argumentation Summary:** `reset()` in
[uploadQueue.ts](../../loom-ui/src/features/uploads/uploadQueue.ts) is exported but **no production
code calls it** — only `uploadQueue.test.ts` does. The queue's `items`, `handles` and `token` live at
module scope, so logging out does not touch them:

1. `AuthContext.logout()` sets `token` to `null`, `AuthGate` re-renders and returns `LoginPage`,
   which unmounts `UploadProvider` in the same render. The `useEffect(..., [token])` that calls
   `setUploadToken(token ?? null)` therefore **never runs with `null`** — the module keeps the
   previous user's bearer token, and any item left `queued` would upload with it.
2. Nothing empties `items`, so after logging in as a different user the `/uploads` screen still shows
   the previous session's filenames, sizes, target libraries and statuses.
3. In-flight `XMLHttpRequest`s in `handles` keep running against the old session instead of being
   aborted.

The spec previously claimed the provider unmount cleared the queue. It does not — an unmount only
disposes the subscriptions.

**Improvement Summary:** Call `reset()` (extended to abort in-flight handles and null the token) from
the logout path, so a session boundary leaves no queue state, no live transfer and no stale token
behind.

```
1. In loom-ui/src/features/uploads/uploadQueue.ts, make reset() a full teardown:
   - abort every handle in `handles` (handle.abort()) before clearing the map,
   - clear `items`, reset `idSeq` is NOT required (ids only need to be unique per session),
   - reset the `batch` counters,
   - set `token = null`,
   - emit() once at the end so subscribers see the empty summary.
   Aborting must not emit a batch toast: suppress batch reporting for a reset-driven teardown (e.g.
   a module-level `tearingDown` flag checked in pump() before batchListeners fire), otherwise
   logging out mid-batch raises a "cancelled" toast on the login screen.
2. In loom-ui/src/context/AuthContext.tsx, call reset() from `logout()` before `setToken(null)`.
   Import it from "../features/uploads/uploadQueue" — the queue is a plain module, not a hook, so
   this is a direct call with no provider dependency.
   If importing the upload queue into AuthContext is judged a layering violation, the alternative is
   a `useEffect` cleanup in UploadProvider that calls reset() on unmount; prefer the explicit logout
   call, because the provider also unmounts on a hot reload.
3. Keep setUploadToken() as it is — step 2 makes the null case redundant but the effect still owns
   the login→token direction.
4. Add the vitest case (see below) and one mocked Playwright case.
```

**References:** [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §2, §5, §9.2 ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §7 (auth/provider tree) ·
`loom-ui/src/main.tsx` (`AuthGate`)
**Test Requirements:**
- `loom-ui/src/features/uploads/uploadQueue.test.ts`: a new case that enqueues into a parked
  transport, calls `reset()`, and asserts the summary is empty, the parked handle was aborted, no
  batch outcome was emitted, and a subsequent `enqueue` fails with "Not authenticated" until
  `setUploadToken` is called again.
- `loom-ui/e2e/uploads-mocked.spec.ts`: enqueue two files against a parked upload, log out, log back
  in, open `/uploads` and assert `upload-empty-state` is visible (no rows survive).
- `cd loom-ui && ./node_modules/.bin/vitest run src/features/uploads` and
  `./node_modules/.bin/playwright test e2e/uploads-mocked.spec.ts`

---

## Task 2: Make the concurrency limit configurable — ✅ DONE (2026-08-16)

**Argumentation Summary:** `MAX_CONCURRENT = 3` was a hard-coded constant in `uploadQueue.ts`. Three
parallel transfers is a reasonable default for a browser over a fast link and a poor one over a
saturated uplink or into an S3-backed pool with per-request latency. There was no way to change it
without a rebuild, and no way for a deployment to lower it.

**Improvement Summary:** Read the limit from a Vite environment variable with the previous value as
the default, validate it, and document it in the spec's environment table.

```
1. In loom-ui/src/features/uploads/uploadQueue.ts, replace the constant with a resolved value:
   const MAX_CONCURRENT = clampConcurrency(import.meta.env.VITE_UPLOAD_CONCURRENCY);
   Export `clampConcurrency(raw: unknown): number` from the same module so it is unit-testable:
   parse as an integer, accept 1..8, fall back to 3 for undefined / NaN / out-of-range input.
   Never return 0 — that would wedge the queue with nothing ever starting.
2. Document VITE_UPLOAD_CONCURRENCY with its default and range.
3. Update spec/loom/ui/LOOM_UI_UPLOAD.md §7 (Configuration): move MAX_CONCURRENT out of the
   "UI-side constant, not configurable" line and into the environment variable table.
```

**Outcome (2026-08-16):** `clampConcurrency` plus `DEFAULT_CONCURRENCY` / `MIN_CONCURRENCY` /
`MAX_CONCURRENCY` live in
[uploadQueue.ts](../../loom-ui/src/features/uploads/uploadQueue.ts), and `MAX_CONCURRENT` is
resolved from `import.meta.env.VITE_UPLOAD_CONCURRENCY` at module load. Out-of-range input **falls
back to the default** rather than being clamped to the nearest bound — a build-time typo behaves as
if the setting was never made. `VITE_UPLOAD_CONCURRENCY?: string` was added to
`src/react-app-env.d.ts`, a commented default to `loom-ui/.env` and `.env.development` (both
untracked), and the variable is documented in
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §9.1 and
[../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §1.1, §5 and §7. Verified end to end:
`VITE_UPLOAD_CONCURRENCY=1` makes the default-pinning cases in `uploadQueue.test.ts` fail, which is
what proves the env read is wired rather than just the resolver.

**References:** [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §7 ·
[../loom/ui/LOOM_UI.md](../loom/ui/LOOM_UI.md) §9.1 (`VITE_*` conventions)
**Test Requirements:** ✅ `uploadQueue.test.ts` "resolves the configured concurrency, falling back to
the default" covers `undefined`, `""`, `"abc"`, `NaN`, `Infinity`, `"0"`, `"-2"`, `"99"`, `"1"`,
`"3"`, `"8"` and a numeric `5`; the existing "caps concurrency at three" case still passes at the
default. `cd loom-ui && ./node_modules/.bin/vitest run src/features/uploads` → 24 passed.

---

## Task 3: Show a server-side settle phase instead of a silent pause at 100%

**Argumentation Summary:** Progress is byte-of-request: `upload.onprogress` reports what the browser
has *sent*, so an item reaches 100% before the server has hashed the bytes, resolved the pool and
written the row. On a large file the bar sits at 100% in "uploading" blue for seconds with no
explanation, which reads as a hang. `percentOf` already clamps, so there is no room in the bar to
express the difference.

**Improvement Summary:** Add a `processing` status entered when `loaded` reaches the file size but
the response has not arrived, rendered as an indeterminate bar with its own label.

```
1. loom-ui/src/features/uploads/uploadQueue.ts:
   - add "processing" to UploadStatus,
   - in the onProgress callback of start(), flip status to "processing" once
     loaded >= file.size while the promise is still pending,
   - treat "processing" exactly like "uploading" everywhere a status is classified: summarize()'s
     `active` filter, isTerminal() (it is NOT terminal), and pump()'s stillBusy check. Missing one of
     these is the failure mode to watch for — a "processing" item that counts as idle ends the batch
     early and fires the toast before the last file is stored.
2. loom-ui/src/features/uploads/UploadView.tsx:
   - isSettled() stays false for "processing",
   - StatusIcon and barColor: keep the uploading treatment but render the LinearProgress as
     variant="indeterminate",
   - progressLabel (uploadFormat.ts) returns the processing label instead of a byte count.
3. i18n: add uploads.status.processing to BOTH src/i18n/locales/en.json and de.json
   ("Processing on the server" / "Wird auf dem Server verarbeitet").
4. The `data-status` attribute on `upload-row-<fileName>` gains the value "processing"; that is the
   e2e assertion surface.
```

**References:** [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §2.2 (lifecycle), §6
(data model), §9.2 · [../features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md)
**Test Requirements:**
- `loom-ui/src/features/uploads/uploadQueue.test.ts`: drive a parked transport's `onProgress` to the
  full file size and assert the item is `processing`, `summary.isActive` is still true and **no**
  batch outcome has been emitted; then resolve it and assert `done` plus exactly one batch outcome.
- `loom-ui/e2e/uploads-mocked.spec.ts`: park the response, assert
  `[data-status="processing"]`, release it, assert `done`.
- `cd loom-ui && ./node_modules/.bin/vitest run src/features/uploads && ./node_modules/.bin/playwright test e2e/uploads-mocked.spec.ts`

---

## Task 4: Folder upload

**Argumentation Summary:** `onDrop` in
[UploadView.tsx:192](../../loom-ui/src/features/uploads/UploadView.tsx#L192) reads
`e.dataTransfer.files` and nothing else, and the file input carries `multiple` but not
`webkitdirectory`. Dropping a folder therefore contributes **no files at all** — the drop is silently
inert, which is the worst possible answer for someone ingesting a shoot directory. The current
behaviour is pinned by a `uploads-mocked.spec.ts` case so a half-built version fails loudly.

**Improvement Summary:** Walk `DataTransferItem.webkitGetAsEntry()` recursively on drop, add a
"select folder" affordance backed by `webkitdirectory`, and carry the relative path so the queue rows
stay distinguishable.

```
1. loom-ui/src/features/uploads/UploadView.tsx:
   - in onDrop, prefer `e.dataTransfer.items`: for each item call webkitGetAsEntry(); recurse into
     FileSystemDirectoryEntry via createReader().readEntries() (which returns at most 100 entries per
     call — loop until it returns an empty array, this is the classic bug) and collect
     FileSystemFileEntry.file(). Fall back to dataTransfer.files when items/webkitGetAsEntry is
     unavailable.
   - the walk must be bounded: cap depth and total file count, and skip dot-directories.
   - add a second button next to the file picker with an input carrying `webkitdirectory` (needs a
     `// @ts-expect-error` or a declaration merge — it is not in React's InputHTMLAttributes).
2. Carry the folder path: extend UploadItem with `relativePath?: string` (from
   `File.webkitRelativePath` or the entry's fullPath) and show it under the filename in UploadRow.
   Two files named `clip.mp4` from different folders must be distinguishable in the queue — note that
   `upload-row-<fileName>` testids collide in that case, so key the row testid on item.id and keep a
   filename-based one only where it is already asserted.
3. Nothing changes server-side: each file is still its own single-shot multipart request.
4. Update the §8.2 case "ignores a drop that carries no files" — it currently pins the unbuilt state.
   It must become a case that drops a synthetic directory entry and asserts the files inside are
   enqueued; keep a no-files drop case for the genuinely empty payload.
```

**References:** [../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §8.2 (why the drop tests
build a `DataTransfer` in-page), §9.1 (testid convention)
**Test Requirements:**
- `loom-ui/e2e/uploads-mocked.spec.ts`: a drop whose `DataTransfer` carries a directory entry built
  with `page.evaluateHandle` enqueues every file beneath it, one multipart request each; a nested
  folder is walked; a drop with no files still enqueues nothing without throwing.
- vitest for the entry walker if it is extracted as a pure function (preferred — the `readEntries`
  loop is the part worth testing without a browser).
- `cd loom-ui && ./node_modules/.bin/playwright test e2e/uploads-mocked.spec.ts`

---

## Task 5: Resumable / chunked upload

**Argumentation Summary:** `POST /api/v1/assets/upload` is single-shot multipart with no resume
(gap list in [../features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md)).
A dropped connection or a page reload loses the whole transfer regardless of how many bytes made it,
and the UI can only warn via `beforeunload`. For the multi-gigabyte media this product exists to
handle, that makes ingest over an unreliable link impractical. This is a **backend-first** task: no
amount of UI work can resume against an endpoint that has no session concept.

**Improvement Summary:** Add a chunked upload session to the REST API (create session → PUT parts →
complete), then teach the queue to drive it and to resume a session it can still identify.

```
1. Backend (loom/services/rest/.../endpoint/impl/AssetEndpoint.java and a new
   AssetUploadSessionEndpointService):
   - POST /assets/uploads → create a session {fileName, size, mimeType, libraryUuid, poolUuid?,
     origin?} → returns {uuid, chunkSize, receivedBytes}. Permission set is computed the same way as
     the single-shot route (CREATE_ASSET, plus READ_ASSET_POOL when poolUuid is named).
   - PUT /assets/uploads/:uuid/parts/:index → raw bytes of one chunk, idempotent per index.
   - POST /assets/uploads/:uuid/complete → assembles, hashes, and returns the same body as the
     single-shot route, including the 201-vs-200 duplicate discrimination.
   - GET /assets/uploads/:uuid → {receivedBytes, parts} so a client can resume.
   - DELETE /assets/uploads/:uuid → abandon; a janitor must also expire stale sessions.
   - Partial bytes land in the pool's staging area and must never be visible as an asset until
     complete succeeds. BinaryStorageResolver resolves the pool once, at session creation.
2. Java client: AssetBinaryMethods gains the session methods; keep the existing single-shot overloads.
3. loom-ui/src/api/assets.ts: `uploadAssetResumable` alongside uploadAssetWithProgress, same
   UploadHandle shape (promise + abort) so the queue does not care which transport it holds.
   Choose per file: below the chunk threshold, keep the single-shot path.
4. loom-ui/src/features/uploads/uploadQueue.ts: retry() of a failed resumable item must resume from
   receivedBytes rather than restart, and the item gains `sessionUuid?`.
5. Update spec/loom/ui/LOOM_UI_UPLOAD.md §2.1/§2.2 and REST_BINARY_HANDLING.md in the same change.
```

**References:** [../features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md) ·
[../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §2.1, §3.2 ·
[../guidelines/CODING.md](../guidelines/CODING.md) (plural REST paths, endpoint + permission tests)
**Test Requirements:**
- `loom/core/src/test/java/io/metaloom/loom/core/endpoint/test/AssetUploadSessionEndpointTest.java`:
  create → two parts → complete yields the same asset and SHA-512 as a single-shot upload of the same
  bytes; re-PUTting a part is idempotent; complete with a missing part is a 400; an unknown session is
  a 404; the permission set matches the single-shot route including the `poolUuid`/`READ_ASSET_POOL`
  pairing; an abandoned session leaves no asset and no binary.
- `loom-ui/src/api/assetUpload.test.ts`: part sequencing, resume from a reported offset, abort.
- `loom-ui/e2e/uploads-backend.spec.ts`: a file large enough to chunk uploads and reads back by
  SHA-512.
- `./setup-pool.sh` then `mvn -o test -pl loom/core -Dtest=AssetUploadSessionEndpointTest`

---

## Task 6: Restore an interrupted batch after a reload

**Argumentation Summary:** Reloading the page loses the queue outright — the module-level store dies
with the document and a `File` handle cannot be persisted. Today the only mitigation is the
`beforeunload` warning. **Blocked by Task 5:** without an upload session there is nothing on the
server to reattach to, so a restored row could offer nothing but "pick this file again".

**Improvement Summary:** Persist the session descriptors (not the bytes) and, after a reload, show
the interrupted items with a resume affordance that re-picks the file and continues from the server's
`receivedBytes`.

```
1. Persist per item {sessionUuid, fileName, size, libraryUuid, poolUuid, origin, receivedBytes} to
   localStorage under a session-scoped key, written on each state change and pruned on completion.
   Never persist bytes and never persist the bearer token.
2. On mount, UploadProvider reads the store, calls GET /assets/uploads/:uuid for each entry, drops
   the ones the server no longer knows (expired/completed) and enqueues the rest in a new
   `interrupted` status.
3. An `interrupted` row renders a "resume" button opening a file picker restricted to that filename;
   verify size (and ideally a hash of the first chunk) before continuing, and refuse a mismatch
   loudly — resuming a session with different bytes would corrupt the asset.
4. Clear the store in reset() (Task 1), so a logout leaves nothing behind.
```

**References:** Task 5 (blocking) · Task 1 (`reset()` must clear the store) ·
[../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §9.2 (reload cancels everything)
**Test Requirements:**
- `loom-ui/src/features/uploads/uploadQueue.test.ts`: serialize/restore round-trip; a restored entry
  the server rejects is dropped; a size mismatch on resume is refused.
- `loom-ui/e2e/uploads-mocked.spec.ts`: park an upload, reload the page, assert an `interrupted` row,
  resume it via `setInputFiles` and see it finish.
- `cd loom-ui && ./node_modules/.bin/vitest run src/features/uploads && ./node_modules/.bin/playwright test e2e/uploads-mocked.spec.ts`

---

## Task 7: Pause / resume — ✅ DONE (2026-08-16, queue-level; bandwidth ceiling split off as Task 9)

**Argumentation Summary:** There was no backpressure control at all. A user ingesting a large batch
could not yield the uplink to a video call without losing everything already sent — cancel-and-retry
was the only lever and it discards progress.

**Improvement Summary:** Hold back what has not started yet. A `paused` status that `pump()` skips,
per-file and whole-queue controls, and batch/toast/unload semantics that account for held work.

```
1. uploadQueue.ts: add `pause(id)` / `resume(id)` and `pauseAll()` / `resumeAll()`, plus a "paused"
   status that pump() skips.
2. UploadView: pause/resume icons per row and in the bulk bar; a paused batch must not raise the
   completion toast and must not count as active for the beforeunload guard.
3. Sidebar: a paused batch shows a distinct (non-animated) progress treatment.
```

**Outcome (2026-08-16):** Shipped **file-granular**. `pause` moves a `queued` item to `paused` and
`pump()` gives the slot to the next queued file; `resume` re-queues it, keeping its position.
`pauseAll` / `resumeAll` do the batch. `pausedCount` joined `UploadSummary`, `paused` counts as busy
in `pump()`'s `stillBusy` check (so a held batch stays open and toasts once, on resume or on
cancelling the remainder), and `cancel` learned to take a paused item. `UploadView` renders
`upload-pause-<fileName>` / `upload-resume-<fileName>` on waiting rows plus `upload-pause-all` /
`upload-resume-all`, and both batch bars fall back to a neutral colour with `-paused` testids, as
does the sidebar's. New i18n under `uploads.status.paused`, `uploads.action.{pause,resume,pauseAll,
resumeAll}` and `uploads.queue.paused`, in `en.json` and `de.json`.

**What deliberately did not ship, and why:** the two halves of this task that need a seam *inside* a
transfer.

- **Pausing a running upload.** `XMLHttpRequest.send()` hands the whole body to the browser; the only
  way to stop it is `abort()`, which throws away every byte sent. That is `cancel` under a friendlier
  name, and on multi-gigabyte media it would be a trap. `pause` on an uploading item is a no-op and
  the button is not rendered for one. → the remaining half of this task, blocked by **Task 5**.
- **The bandwidth ceiling.** Same root cause, no seam to delay against. → **Task 9**, blocked by
  Task 5.

Chunked upload moves the boundary from the file to the chunk; the public API above does not have to
change when it lands. Rationale is in
[../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §2.3.

**References:** Task 5 (blocks the mid-transfer half) · Task 9 (bandwidth ceiling) ·
[../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §1.1, §2.2, §2.3, §5, §6, §9
**Test Requirements:** ✅ `uploadQueue.test.ts` gained six cases — a held item never starts, a running
one is left alone (no abort, no status change), `resume` re-queues rather than restarts, `pauseAll`
lets the in-flight three finish while nothing takes their slots, the batch stays open while anything
is paused and reports **once** on resume, and cancelling the paused remainder closes it with the
right counts. ✅ `uploads-mocked.spec.ts` gained two: whole-queue pause/resume (including the paused
bar testids and the single toast) and single-file pause/resume. `cd loom-ui &&
./node_modules/.bin/vitest run src/features/uploads` → 30 passed;
`./node_modules/.bin/playwright test e2e/uploads-mocked.spec.ts` → 20 passed.

---

## Task 8: Upload from a URL

**Argumentation Summary:** Every ingest today requires the bytes to travel through the operator's
browser. Pulling an asset from a URL the *server* can reach — a partner's delivery link, an internal
HTTP share — has no endpoint at all, so a 40 GB master has to be downloaded to a laptop and pushed
back up. This is the lowest-severity item here because a workaround exists for every case; it is also
the one with real security weight.

**Improvement Summary:** A server-side fetch endpoint that ingests from a URL into a library, with an
SSRF allow-list, and a small "from URL" tab on the upload screen.

```
1. Backend: POST /assets/fetches {url, libraryUuid, poolUuid?, origin?} → 202 with a job handle;
   the fetch runs asynchronously and the result is reported like any other node/job result.
   Permissions mirror the upload route (CREATE_ASSET, plus READ_ASSET_POOL when poolUuid is named).
2. SSRF defence is not optional: resolve the host, refuse loopback/link-local/private ranges and
   metadata endpoints unless an explicit allow-list env var permits them, cap redirects, cap size
   against LOOM_STORAGE_MAX_UPLOAD_SIZE, and enforce a timeout. Refusals must name the reason.
3. New env vars: LOOM_INGEST_URL_ENABLED (default false) and LOOM_INGEST_URL_ALLOWED_HOSTS.
   Document both in spec/loom/ui/LOOM_UI_UPLOAD.md §7 and the REST spec.
4. UI: a "From URL" tab on /uploads sharing the library/pool selectors. It does not enter the upload
   queue — there are no local bytes and no progress events; it lists submitted fetches with their job
   state instead. Hide the tab when the feature is disabled server-side.
```

**References:** [../features/rest/REST_BINARY_HANDLING.md](../features/rest/REST_BINARY_HANDLING.md) ·
[../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §3.2, §7 ·
[../guidelines/CODING.md](../guidelines/CODING.md)
**Test Requirements:**
- A new `loom/core` endpoint test: a fetch from a stubbed HTTP server creates the asset in the named
  library; a loopback/private-range URL is refused with the reason; an oversized body is refused; the
  `poolUuid`/`READ_ASSET_POOL` pairing matches the upload route; the feature disabled → 404/403 per
  the chosen convention.
- `loom-ui/e2e/uploads-mocked.spec.ts` (or a new spec): the tab submits the URL and renders the job
  state; the tab is absent when the server reports the feature off.
- `./setup-pool.sh` then `mvn -o test -pl loom/core -Dtest=Asset*EndpointTest`

---

## Task 9: Bandwidth ceiling for the upload queue

**Argumentation Summary:** Split out of Task 7, which shipped pause/resume but could not throttle:
`XMLHttpRequest.send()` hands the whole body to the browser at once, so there is no point at which
the queue can slow the stream down. Today the only way to stop saturating an uplink is to pause the
queue and let the running files finish at full speed. **Blocked by Task 5:** a per-chunk PUT is the
seam a ceiling needs.

**Improvement Summary:** With chunked upload in place, a configured bytes-per-second ceiling becomes
a computed delay before each chunk request, applied across the batch rather than per file.

```
1. uploadQueue.ts: hold the ceiling in the queue, not the transport — the cap is per batch, so N
   parallel transfers must share it, not get N times the allowance each.
2. Compute the delay from bytes already sent in the current window and await it before the next
   chunk PUT. A ceiling of 0/unset means unthrottled.
3. Expose it as a UI control (a small select: unlimited / 1 / 5 / 20 MB/s) persisted per browser,
   plus VITE_UPLOAD_MAX_BYTES_PER_SECOND as the deployment default. Document both in
   spec/loom/ui/LOOM_UI_UPLOAD.md §7 and LOOM_UI.md §9.1.
4. The ceiling must not stall a batch: with the cap set, progress still has to advance, and lowering
   it mid-batch must take effect on the next chunk rather than on the next file.
```

**References:** Task 5 (blocking) · Task 7 (shipped the pause half) ·
[../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §2.3, §7
**Test Requirements:**
- `loom-ui/src/features/uploads/uploadQueue.test.ts`: with fake timers, a ceiling spaces chunk starts
  by the expected delay; the cap is shared across concurrent transfers rather than applied per file;
  unset means no delay is awaited at all; lowering the ceiling mid-batch affects the next chunk.
- `loom-ui/e2e/uploads-mocked.spec.ts`: the control persists and a throttled batch still completes.
- `cd loom-ui && ./node_modules/.bin/vitest run src/features/uploads`

---

_Git HEAD revision: `67000540`_
_Last updated: 2026-08-16 (Task 7 implemented and closed for its queue-level scope — pause/resume
per file and for the whole queue; the two parts that need a seam inside a transfer stayed open, the
bandwidth ceiling as the new Task 9. Earlier the same day: Task 2 implemented and closed — the upload
concurrency is now `VITE_UPLOAD_CONCURRENCY`. Earlier: new file, open work moved out of
[../loom/ui/LOOM_UI_UPLOAD.md](../loom/ui/LOOM_UI_UPLOAD.md) §1.2/§1.3, which now tracks current
state only. Task 1 is a newly found defect — the audit showed `reset()` has no production caller, so
the spec's previous claim that a provider unmount clears the queue was wrong)_
