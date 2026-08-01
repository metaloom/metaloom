# TASK_UI_IDENTITY_ACCESS — Identity & Access (RBAC)

> Open UI work items for the RBAC entities (User, Group, Role, Permission, Token, Login/OAuth2),
> derived from a code audit of `loom-ui/` and `loom/services/rest/.../endpoint/impl/` on
> 2026-08-01. Format follows [../../TASKS.template.md](../../TASKS.template.md).
>
> **Context:** [LOOM_UI.md](LOOM_UI.md) (UI spec) · [../RESTAPI.md](../RESTAPI.md) §2.3, §3.2 ·
> [../../features/rbac/RBAC.md](../../features/rbac/RBAC.md) ·
> [../../features/permissions/PERMISSIONS.md](../../features/permissions/PERMISSIONS.md)
>
> **Ordering:** **Task 1 is blocking and is a correctness bug, not a feature** — the admin ACL
> matrix currently reports grants that the server discards. Tasks 2 and 3 are the OAuth2 pair and
> should land together (SSO login without SSO logout leaves users unable to sign out).

---

## Closed — outcome records

| Task (as originally filed) | Outcome — where it landed |
|---|---|
| User CRUD | ✅ DONE — `loom-ui/src/api/users.ts` → `UsersAdmin` in `features/admin/AdminArea.tsx` and `features/profile/ProfileView.tsx`; `e2e/users-backend.spec.ts` |
| Group CRUD | ✅ DONE — `api/groups.ts` → `GroupsAdmin`; `e2e/groups-backend.spec.ts` |
| Role create/list/load/delete | ✅ DONE — `api/roles.ts` → `AccessControlAdmin` (`/admin/permissions`); `e2e/roles-backend.spec.ts`. Role **permission** editing is Task 1 |
| Token CRUD | ✅ DONE — `api/tokens.ts` → `ApiKeysAdmin` (one-time secret + rename + revoke); `e2e/tokens-backend.spec.ts` |
| Password login + `GET /me` | ✅ DONE — `api/auth.ts` → `features/auth/LoginPage.tsx` + `context/AuthContext.tsx`; `e2e/login.spec.ts`, `e2e/login-backend.spec.ts` |
| PATCH / PUT variants for users and groups | ✅ CLOSED as a non-gap — `UserEndpoint`/`GroupEndpoint` route POST, PATCH and PUT `/:uuid` to the same `service.update`; the UI's POST update covers all three |

---

## Task 1: Role permission edits are a silent no-op — fix the write path and the catalog

**Argumentation Summary:** Two independent defects stack into one misleading screen at
`/admin/permissions`.

1. **The server discards the field.** `RoleEndpointService.update(...)`
   ([RoleEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/RoleEndpointService.java), ~line 62)
   copies only `request::getName` and `request::getMeta` onto the `Role`. `RoleUpdateRequest.permissions`
   is deserialized, validated and then **never read** — so *no* permission change made through
   `POST /api/v1/roles/:uuid` is ever persisted, not even a valid one.
2. **The catalog is fiction.** `PERMISSION_GROUPS` in
   [AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) (~line 909) lists 20 resource
   groups / ~82 strings (`CREATE_ASSET`, `READ_PIPELINE`, `TAG_ASSET`, …). The REST enum
   `RolePermission` has exactly **four** values: `READ_USER`, `CREATE_USER`, `DELETE_USER`,
   `UPDATE_USER`. (The rich enum the server actually enforces is a *different* type,
   `io.metaloom.loom.db.model.perm.Permission` in `loom/db/api` — ~139 values, each annotated with a
   `ui:yes/no` comment. The REST role model does not expose it.)

`togglePermission` writes optimistic local state and logs failures to `console.error`, so an admin
sees every checkbox stick. Nothing is granted. This is the single most dangerous screen in the UI.

**Improvement Summary:** Make the write path real (backend) and make the editor honest about what
the server accepted (UI), so a checked box means a stored grant.

```
Backend (prerequisite — file against loom/services/rest):
  1. RoleEndpointService.update: map request.getPermissions() onto the Role, mirroring how
     create() handles it, and add a RoleEndpointTest case that updates permissions and asserts
     they survive a reload. Without this step the UI work is untestable.
  2. Decide the contract for RolePermission: either grow it to mirror the db Permission values
     the UI offers, or keep it narrow and have the UI offer only those. Record the decision in
     ../../features/permissions/PERMISSIONS.md — the two enums must not drift silently again.

UI (loom-ui/src/features/admin/AdminArea.tsx, AccessControlAdmin ~lines 665-845 and the
PERMISSION_GROUPS constant ~line 909):
  3. Derive the rendered catalog from a single exported constant with a comment naming the Java
     source of truth; drop groups the REST enum cannot represent.
  4. Server wins: after updateRole, re-read the returned role and set the checkbox state from
     response.permissions — never from optimistic local state. A dropped value must show as
     unchecked.
  5. Replace the console.error in togglePermission with the ToastContext error toast used
     elsewhere in AdminArea.
  6. A role carrying an unrecognised permission string renders as a read-only "unknown"
     row and is preserved on the next save rather than silently stripped.

Edge cases: role list and role detail disagree after a rejected write (refetch the list);
concurrent edits by two admins (last write wins — refetch, do not merge).
```

**References:** [RoleEndpointService.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/RoleEndpointService.java) ·
[RolePermission.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/role/RolePermission.java) ·
[RoleUpdateRequest.java](../../../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/role/RoleUpdateRequest.java) ·
[Permission.java](../../../loom/db/api/src/main/java/io/metaloom/loom/db/model/perm/Permission.java) ·
[AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) · [api/roles.ts](../../../loom-ui/src/api/roles.ts) ·
[../../features/rbac/RBAC.md](../../features/rbac/RBAC.md)

**Test Requirements:**
- Backend: a `RoleEndpointTest` case asserting `POST /roles/:uuid` with `permissions` persists them
  (this test does not exist today and is what proves the bug fixed). Run:
  `mvn -pl loom/services/rest test -Dtest=RoleEndpointTest` after `./setup-pool.sh`.
- UI: extend `loom-ui/e2e/roles-backend.spec.ts` — toggle a permission, reload the page, assert it
  is still checked. Add `loom-ui/e2e/roles-permissions-mocked.spec.ts` routing `POST /roles/:uuid`
  to a response that omits the toggled value and asserting the checkbox reverts **and** an error
  toast appears. Run: `cd loom-ui && yarn e2e --grep roles`.

---

## Task 2: Add OAuth2 SSO sign-in (initiate + callback) to the login flow

**Argumentation Summary:** `OAuth2Endpoint` ships a complete BFF/PKCE flow —
`GET /api/v1/auth/oauth2/login` (302 to the IdP), `GET /api/v1/auth/oauth2/callback` (sets the
session cookie, auto-provisions unknown users). A repo-wide grep of `loom-ui/src` and `loom-ui/e2e`
finds **no** occurrence of `oauth` or `sso`: `api/auth.ts` exports only `login`, `getMe`,
`decodeJwt`, `isJwtExpired`, and `LoginPage.tsx` renders a username/password form only. Any
deployment configured for SSO cannot be signed into from the UI.

**Improvement Summary:** Add an SSO entry point that hands off to the IdP and resumes an
authenticated, cookie-backed session on return, alongside password login.

```
  - loom-ui/src/api/auth.ts: oauth2LoginUrl() returning `${API_BASE_URL}/auth/oauth2/login`.
    This is a full-page navigation, NOT a fetch — the browser must follow the IdP hops and store
    the cookie. Also add a status/feature probe if one exists; otherwise render unconditionally
    and degrade when the endpoint is disabled.
  - features/auth/LoginPage.tsx: "Sign in with SSO" button → window.location.assign(oauth2LoginUrl()).
  - context/AuthContext.tsx: on app load, attempt getMe() so a user returning from the callback
    (cookie already set) is recognised without a bearer token in memory.
  - ⚠️ Cross-cutting: every api module currently sends `Authorization: Bearer` only. Cookie
    sessions need `credentials: "include"` when no bearer token is present. Add that to the
    shared authHeaders/fetch shape described in LOOM_UI.md §5 — patching only auth.ts will make
    login work and every subsequent call 401.

Edge cases: callback returns an error/denied → LoginPage must show an auth error, not a blank
screen; a user with both a password and an auto-provisioned SSO identity must converge on the
same app state; state/PKCE are server-side — the UI must not touch them.
```

**References:** [OAuth2Endpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/OAuth2Endpoint.java) ·
[../RESTAPI.md](../RESTAPI.md) §2.3 · [api/auth.ts](../../../loom-ui/src/api/auth.ts) ·
[LoginPage.tsx](../../../loom-ui/src/features/auth/LoginPage.tsx) ·
[AuthContext.tsx](../../../loom-ui/src/context/AuthContext.tsx) · [LOOM_UI.md](LOOM_UI.md) §5, §7.1

**Test Requirements:** `loom-ui/src/api/auth.test.ts` — `oauth2LoginUrl()` shape, and api calls
send `credentials: "include"` when no bearer token is set. `loom-ui/e2e/sso-mocked.spec.ts` —
route the callback to set a cookie, assert the app lands authenticated with `GET /me` succeeding
without an Authorization header. Run: `cd loom-ui && yarn test && yarn e2e --grep sso`.

---

## Task 3: Wire OAuth2 logout so cookie sessions are actually terminated

**Argumentation Summary:** `logout()` in `context/AuthContext.tsx` (invoked from the avatar menu
in `layout/Sidebar.tsx`) clears client state only. For a cookie-backed SSO session the server
session survives, so a reload re-authenticates the user from the cookie — logout appears to work
and does not. `GET /api/v1/auth/oauth2/logout` exists and is never called.

**Improvement Summary:** Terminate the server session on logout for cookie sessions while keeping
bearer-token logout as-is.

```
  - api/auth.ts: logout() → GET `${API_BASE_URL}/auth/oauth2/logout` with credentials: "include".
  - context/AuthContext.tsx logout(): if there is no in-memory bearer token, await the api call
    first; always clear local state afterwards even when the call fails.
Edge cases: network/5xx must still clear local state (never a half-logged-in shell); calling
logout when already logged out is a no-op; the endpoint clears the cookie but does not revoke IdP
tokens (documented backend limitation — reflect it in the logout copy if relevant).
```

**References:** [OAuth2Endpoint.java](../../../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/OAuth2Endpoint.java) (`/logout`) ·
[../RESTAPI.md](../RESTAPI.md) §2.3 · [AuthContext.tsx](../../../loom-ui/src/context/AuthContext.tsx) ·
[Sidebar.tsx](../../../loom-ui/src/layout/Sidebar.tsx)

**Test Requirements:** `loom-ui/src/api/auth.test.ts` — cookie session calls the logout route with
credentials included, then clears state; bearer session clears state without the network call and
still clears it if the call rejects. Extend `e2e/sso-mocked.spec.ts`: after logout a reload does
not restore the session. Run: `cd loom-ui && yarn test && yarn e2e --grep sso`.

---

## No REST surface — backend prerequisites, not UI gaps

* **Group membership does not exist at REST.** `GroupModel` exposes only `name`; `UserModel` only
  `username`/`firstname`/`lastname`/`email`; `GroupEndpoint` and `UserEndpoint` register plain CRUD
  with no `/groups/:uuid/users`, `/groups/:uuid/roles` or `/users/:uuid/groups` sub-resources.
  The user → group → role graph that RBAC actually evaluates is therefore **unmanageable from any
  client**, and the admin screens can only rename empty containers. This blocks any "assign role"
  or "add member" UI and needs endpoints first — see
  [../../features/rbac/RBAC.md](../../features/rbac/RBAC.md).
* **No permission-catalog endpoint.** There is no route that enumerates valid permissions, so the
  UI catalog is a hand-maintained mirror (Task 1 step 3). If such a route is added, drive
  `PERMISSION_GROUPS` from it and delete the constant.

_Git HEAD revision: `499f71f7`_
_Last updated: 2026-08-01 (recorded the RoleEndpointService permission no-op as the blocking task, collapsed the delivered CRUD to outcome records, and noted the missing group-membership REST surface)_
