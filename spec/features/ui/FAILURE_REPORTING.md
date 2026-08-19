# Failure Reporting

How a failure becomes something a user can see, name and report, and how an operator resolves that
report back to the exact request in the server log.

> **Scope.** This file owns the *cross-cutting* failure path: the trace id, the shared HTTP layer,
> the failure surface in the UI, the report form, and the `/api/v1/failure-reports` endpoint.
> Per-screen error states stay in [../../loom/ui/LOOM_UI.md](../../loom/ui/LOOM_UI.md).
> Permissions are catalogued in [../permissions/PERMISSIONS.md](../permissions/PERMISSIONS.md).

---

## 1. The problem this solves

A failure is observed twice and understood by neither party alone.

The **server** logs a path, a status and a stack trace. It does not know what the user was trying
to do, what they expected, or whether they lost work.

The **user** knows exactly that, and nothing else. "It said something went wrong" names no request,
no time, and no instance.

Before this feature there was nothing joining the two. `src/api/` held 36 private `handleResponse`
copies, each throwing `new Error("API error 500: ...")`, so even the status arrived at the UI as a
substring of a message. Two dozen catch blocks wrote to `console.error` and stopped there; several
mutations cleared their form and closed their dialog *outside* the try/catch, so a rejected create
was indistinguishable from an accepted one.

**The trace id is the join key.** Everything else here exists to carry it from the response that
failed to the report a person submits.

```
   user                     browser                         server
    |                          |                               |
    |  presses "Create"        |   POST /api/v1/persons        |
    |------------------------->|------------------------------>|
    |                          |                               |  TraceIdHandler mints
    |                          |                               |  9f2c41ab...  (also logged)
    |                          |<------------------------------|
    |                          |  500 + X-Trace-Id: 9f2c41ab   |
    |                          |     { message, traceId }      |
    |                          |                               |
    |                     handleResponse -> ApiError           |
    |                          |                               |
    |   toast: "The server failed to handle the request."      |
    |          [ Report ]      |                               |
    |<-------------------------|                               |
    |                          |                               |
    |  writes what they expected, optionally attaches a shot   |
    |------------------------->|  POST /api/v1/failure-reports |
    |                          |------------------------------>|  row carries traceId
    |                          |                               |
                                  operator greps the log for 9f2c41ab
```

---

## 2. The trace id

| | |
|---|---|
| Header | `X-Trace-Id`, on **every** response, not only failures |
| Format | 16 random bytes, hex - the width of a W3C `trace-id`, so real distributed tracing can adopt these ids later rather than running a second scheme alongside them |
| Minted by | `TraceIdHandler`, registered **first** in the router chain |
| Also in | the body of every error response (`GenericMessageResponse.traceId`) |
| Logged by | `ServerFailureHandler`, next to the path and the stack trace |

Three decisions worth knowing:

**Registered ahead of CORS and the body handler.** A preflight that never reaches a route, and a
404 from the router's own error handler, are both failures a user may need to report - and neither
can be named after the fact if the id is minted further down.

**An inbound `X-Trace-Id` is honoured but sanitised.** A caller that already has one (the CLI,
cortex, a proxy) keeps it across the hop, so correlation does not break at a tier boundary. But the
value is written into log lines, so `TraceIdHandler.sanitize` admits only `[A-Za-z0-9._-]{1,64}`;
anything else is silently replaced with a fresh id rather than rejected, because a malformed trace
header is not a reason to fail somebody's request.

**CORS must expose it.** Without an explicit `Access-Control-Expose-Headers`, a browser hides every
response header from JS except the CORS-safelisted six. That would make the id read as `null` in
exactly the deployment the UI is developed on (loom-ui on `:3000`, Loom on `:8080`). The body copy
exists as the second belt for the same reason.
`TraceIdEndpointTest.testTheTraceHeaderIsExposedToCrossOriginCallers` is what stops that regressing.

---

## 3. The shared HTTP layer

`loom-ui/src/api/http.ts` replaced the 36 private copies. It keeps the three things they discarded:

| Kept | Why it matters |
|---|---|
| `traceId` | the only value that resolves a report against the log |
| `status` as a number | consumers can branch on `403` without matching a message string |
| "this was a 401" | an expired session becomes one message rather than one per widget |

```ts
export class ApiError extends Error {
  readonly status: number;
  readonly traceId: string | null;
  readonly serverMessage: string | null;   // unwrapped from GenericMessageResponse
  readonly action: string | null;          // "createPerson" - supplied by the call site
  readonly method: string | null;
  readonly path: string | null;
}
```

Six modules keep a typed error class of their own (`UserApiError`, `SearchApiError`,
`StorageApiError`, `SearchIndexApiError`, `MemoryApiError`, `ShareApiError`) because their callers
switch on the concrete type. They call `traceIdOf(res)` and `noteUnauthorized(res.status)` so they
are not the six places where the id is quietly dropped.

**`shares.ts` deliberately does not call `noteUnauthorized`.** It is the share *visitor* path, where
a 401 means the share session token lapsed - not that a Loom session expired. Firing the global
event there would sign out somebody who was never signed in.

---

## 4. The failure surface

`FailureProvider` / `useFailure()` is the single way a screen says that something failed.

```ts
const { reportFailure } = useFailure();
try {
  await createPerson(token, request);
  closeDialog();               // inside the try, after the await
} catch (e) {
  reportFailure("createPerson", e);
}
```

`reportFailure` normalises the thrown value (`toFailure`), raises an error toast carrying a
**Report** button, and returns the `Failure` so a caller can also render an inline state.

**A 401 raises no toast.** `AuthProvider` is already showing one "your session expired" for the
whole page; a second message per failed widget is the pile-up the global 401 path exists to prevent.

### 4.1 The rule

> A `catch` that only `console.error`s is a bug.
> A **mutation** must call `reportFailure`. A **load** must additionally render a distinct failed
> state, because a toast fades and the screen still reads as empty.

`LoadFailure` is the component for the second half. `EmptyState` is for the other case - the request
succeeded and there is genuinely nothing there. Reaching for the wrong one of the two is the bug
this pairing exists to make hard to write: "no libraries" and "the libraries could not be loaded"
are different statements, and only one of them was ever true.

---

## 5. The report

`FailureReportDialog` shows the technical facts read-only, and asks for the half only the user has.

| Field | Source |
|---|---|
| action | the call site's own vocabulary - `"createPerson"`, not a path |
| trace id | the failing response; rendered in monospace with a copy button |
| method / path / status | the failing request, as the *client* saw it |
| text | what the user types |
| screenshot | optional, see §6 |

**Nothing about a report is derived server-side except the user agent.** A client may lie about
itself, so `user_agent` is stamped from the request headers - observed provenance beats declared.
The request fields are the opposite case: they describe an *earlier* request the server cannot
observe at all, so the client is the only possible source. If the two ever disagree, that
disagreement is itself the finding.

Every request field is nullable. The failures most worth reporting include ones that produced no
response - a render throw caught by the error boundary, a socket that closed, a screen that stayed
empty. A form insisting on a status code would refuse exactly the reports that are hardest to
reproduce.

---

## 6. The screenshot

Captured with `navigator.mediaDevices.getDisplayMedia`, one frame, downscaled to 1920 on the long
edge, encoded as PNG.

**Why not a DOM-to-canvas renderer** (html2canvas and friends): those re-render the DOM from
computed styles. `<video>` frames and cross-origin previews come out blank, and - decisively - a
wrongly-rendered element is re-rendered by the same code that rendered it wrongly, so the screenshot
agrees with the bug instead of showing it.

What it costs: the browser shows a picker every time. That is a feature here - nothing is captured
without an explicit per-capture choice - but it also means the user may pick the wrong surface,
which is why the image is shown back to them, enlargeable, before anything is submitted.

`getDisplayMedia` needs a secure context. On plain HTTP `isScreenshotSupported()` answers false and
the dialog hides the button rather than offering one that can only fail.

### 6.1 Server side

| | |
|---|---|
| Table | `failure_report_screenshot`, 1:1, PK = report uuid |
| Cap | 5 MB decoded; over that is a **413** naming the limit |
| Type | sniffed from the **bytes** (PNG / JPEG / WebP magic), never from the client's data URL |
| Served by | `GET /api/v1/failure-reports/{uuid}/screenshot`, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff` |

The type is sniffed rather than trusted because the stored value is echoed back as the response
`Content-Type`; letting the uploader choose it would let them serve arbitrary bytes under a type of
their choosing from the API origin.

**A table of its own, not a column, and not binary storage.** Not a column because the inbox lists
reports and `AbstractJooqDao` issues `SELECT *` - a megabyte-wide column would make every page of
the inbox a multi-megabyte read for data nobody asked for. Not binary storage because *a failure
report must be storable when storage is the thing that is broken*: an unreachable bucket is one of
the failures a user most needs to report, and a report path that writes to the bucket first would
fail exactly then.

---

## 7. Error boundaries and the 401 path

**`ErrorBoundary`** wraps the routed area in `AppShell`, keyed by pathname. One boundary, not thirty:
the keying makes them equivalent (a navigation remounts it, so a screen that threw does not stay
broken), and thirty wrappers would be thirty places for the next route to forget one. The sidebar is
outside it and survives. The fallback **resets the boundary** rather than reloading the page -
`location.reload()` would throw away the in-memory session, which is the failure it exists to
prevent.

**The 401 path.** `handleResponse` dispatches `loom:session-expired` on the `window`.
`AuthProvider` listens, calls `logout()` and raises exactly one toast; ten parallel 401s produce ten
events and one message, guarded by a ref so the suppression holds within a single tick.
`AuthProvider` also calls `isJwtExpired` on mount and on window focus - the case that matters is a
laptop closed over a weekend, which comes back looking signed in and answers 401 to everything the
user touches.

Note the provider order in `main.tsx`, which is the reverse of what it used to be:

```
BrowserRouter > ToastProvider > AuthProvider > FailureProvider > Routes
```

`AuthProvider` consumes the toast context (it owns the single session-expired message), so it must
sit below it. `FailureProvider` needs the toast, the auth token and the router.

---

## 8. REST API

Base path `/api/v1/failure-reports`.

| Method | Path | Permission | Notes |
|---|---|---|---|
| `POST` | `/failure-reports` | **none** (authentication only) | only `action` is required |
| `GET` | `/failure-reports` | `READ_FAILURE_REPORT` | paged; never inlines a screenshot |
| `GET` | `/failure-reports/{uuid}` | `READ_FAILURE_REPORT` | |
| `GET` | `/failure-reports/{uuid}/screenshot` | `READ_FAILURE_REPORT` | raw bytes |
| `POST` | `/failure-reports/{uuid}` | `UPDATE_FAILURE_REPORT` | triage only |
| `DELETE` | `/failure-reports/{uuid}` | `DELETE_FAILURE_REPORT` | cascades to the screenshot |

**Create takes no permission** - see [../permissions/PERMISSIONS.md](../permissions/PERMISSIONS.md)
and the reasoning in `V2.106`. The short version: a permission to report a failure would, wherever
it went ungranted, turn the product's only response to a breakage into a 403.

The service is forgiving by design. An out-of-range status code is **dropped**, an over-long field
is **truncated** - neither is a 400, because the submission is the user's only channel and losing
the tail of a stack trace is a smaller harm than losing the report. An oversized or non-image
screenshot *is* refused, before the row is written, so a report never lands with an attachment that
silently vanished.

---

## 9. Schema

`V2.106` (permissions) -> `V2.107` (tables) -> `V2.108` (grant). Enum additions live in their own
migration because `ALTER TYPE ... ADD VALUE` cannot run inside a transaction block on older
Postgres, and a value added in one transaction is not usable in it.

`failure_report.triage_status` is **not** called `status`: `AbstractCreatorEditorRestResponse`
already owns that property name for the creator/editor audit block, so a field of that name could
not be expressed in the API at all. The column is named after the name the API can use.

`creator_uuid` is nullable, `ON DELETE SET NULL` - deleting the person who reported a bug must not
delete the bug. The reporter becomes anonymous, the finding survives, and the trace id still
resolves.

---

## 10. Key Classes Reference

| Class / module | Location | Purpose |
|---|---|---|
| `TraceIdHandler` | `io.metaloom.loom.rest` | mints and sanitises `X-Trace-Id` |
| `ServerFailureHandler` | `io.metaloom.loom.rest` | logs the id and returns it in the error body |
| `FailureReportEndpoint` | `io.metaloom.loom.rest.endpoint.impl` | route table |
| `FailureReportEndpointService` | `io.metaloom.loom.rest.service.impl` | validation, screenshot decode and sniff, triage |
| `FailureReportDao` / `FailureReportDaoImpl` | `io.metaloom.loom.db.model.failure` / `...jooq.dao.failure` | rows and screenshots; `screenshotUuids` answers a whole page in one query |
| `http.ts` | `loom-ui/src/api/` | `ApiError`, `handleResponse`, `authHeaders`, the 401 event |
| `failure.ts` | `loom-ui/src/failure/` | `toFailure`, message wording; pure, node-testable |
| `screenshot.ts` | `loom-ui/src/failure/` | `getDisplayMedia` capture and downscale |
| `FailureContext.tsx` | `loom-ui/src/context/` | `useFailure().reportFailure` |
| `FailureReportDialog.tsx` | `loom-ui/src/components/` | the form, the preview and the lightbox |
| `LoadFailure.tsx` | `loom-ui/src/components/` | the inline "could not load" state |
| `ErrorBoundary.tsx` | `loom-ui/src/components/` | contains a render throw to one screen |
| `FailureReportsAdmin.tsx` | `loom-ui/src/features/admin/` | the inbox |

---

## 11. Test Setup

| Test | Covers |
|---|---|
| `FailureReportEndpointTest` (`loom/core`) | 18 cases: create without permission, absent trace id, dropped nonsense status, observed user agent, screenshot round trip, type sniffed from bytes, 413, triage, cascade, and the four RBAC 403s |
| `TraceIdEndpointTest` (`loom/core`) | 6 cases over raw HTTP: header present on success, ids differ, body matches header, inbound id honoured, forged id replaced, CORS exposes the header |
| `FailureReportDaoTest` (`loom/db/jooq`) | 10 cases: CRUD, screenshot upsert, batch `screenshotUuids`, cascade, report outlives its reporter |
| `RolePermissionParityTest` | the three constants exist in both enums |
| `src/api/http.test.ts` (vitest, node) | 14 cases over the shared handler, including the session-expired event |
| `src/failure/failure.test.ts` (vitest, node) | normalisation and message wording |
| `e2e/error-feedback-mocked.spec.ts` (Playwright) | 5 cases: rejected create keeps its input, the report carries the trace id, a failed load is not an empty one, a rejected delete keeps its row, one message for an expired session |

Run them:

```bash
./mvnw -pl loom/core test -Dtest='FailureReportEndpointTest,TraceIdEndpointTest'
./mvnw -pl loom/db/jooq test -Dtest=FailureReportDaoTest
cd loom-ui && ./node_modules/.bin/vitest run src/api/http.test.ts src/failure/failure.test.ts
cd loom-ui && ./node_modules/.bin/playwright test e2e/error-feedback-mocked.spec.ts
```

Never invoke the loom-ui runners through `npx` - it hangs in this environment.

---

## 12. Conventions and Gotchas

| Gotcha | Detail |
|---|---|
| CORS hides the header | a response header is invisible to JS cross-origin unless `Access-Control-Expose-Headers` names it. The body copy of `traceId` is the fallback |
| `triageStatus`, not `status` | the response base class already owns `status` for the audit block |
| The screenshot `<img>` carries no token | an `<img src>` issues a plain GET with no `Authorization` header, so the inbox preview works only where the browser also holds a session cookie |
| Do not report the report | `FailureReportDialog` renders its own submit failure inline. Routing it through `reportFailure` would raise a toast offering to report the failure of a report |
| `console.error` is not always a bug | `ErrorBoundary.componentDidCatch` keeps one: the component stack is named nowhere else, and the user is being shown a fallback and offered a report |
| Message strings are not an API | consumers must branch on `ApiError.status`. `DbIntegrityAdmin` used to test `message.startsWith("API error 403")` and broke the moment the message improved |

---

## 13. Progress Assessment

- [x] `X-Trace-Id` on every response, sanitised inbound, exposed through CORS
- [x] trace id in the error body and in the server log line
- [x] shared `http.ts`; 39 modules migrated; 6 typed error classes carry the id
- [x] global 401 path, deduplicated, with the proactive `isJwtExpired` check wired
- [x] route-level error boundary with a reset that keeps the session
- [x] `FailureProvider` / `reportFailure`, toast with a Report action
- [x] report form with trace id, copy, free text
- [x] screenshot capture, preview and enlarge
- [x] `failure_report` + `failure_report_screenshot`, three permissions, admin inbox
- [x] Java and Python clients, OpenAPI regenerated
- [x] Task 14 sites converted: face detection (8), tags (4), blacklist (2), libraries (1)
- [ ] the remaining swallowing loads listed in
      [../../tasks/LOOM_UI_TASKS.md](../../tasks/LOOM_UI_TASKS.md) Task 14 step 5
      (`ChatWorkspace`, `AssetDetail`, `SkillManagementView`, `MemoryView`, `ChatSessionsView`,
      `TasksView`, `ObjectDetectionManagement`, `MaintenanceView`, `ProfileView`)
- [ ] loading indicators for the views listed in Task 14 step 6
- [ ] a notification when a report is submitted, so an operator learns without opening the inbox
- [ ] retention: reports are kept forever today, screenshots included

---

_Git HEAD revision: `d4e9134f`_
_Last updated: 2026-08-18 (initial version - the feature landed in one change)_
