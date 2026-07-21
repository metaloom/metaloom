# TASK_UI_IDENTITY_ACCESS — Identity & Access (RBAC)

Gap-analysis tasks between the Loom REST API and the Loom UI for the RBAC entities
(User, Group, Role, Permission, Token). Derived from a scan of the REST endpoints
and the loom-ui feature/api modules. Follows [../../TASKS.template.md](../../TASKS.template.md).

## Coverage Matrix

Legend: **Implemented** = UI issues the call and a screen/action drives it. **Partial** =
call is wired but the UI surface is incomplete or diverges from the backend contract.
**Missing** = no UI/api coverage. PATCH/PUT variants call the same `service.update` as the
POST update route, so the UI's use of POST update covers them (no functional gap).

| Entity | REST Operation (path · method) | UI Status | Where / Gap |
|--------|-------------------------------|-----------|-------------|
| User | `POST /api/v1/users` · create | Implemented | [users.ts](loom-ui/src/api/users.ts) `createUser` → `UsersAdmin` create dialog in [AdminArea.tsx](loom-ui/src/features/admin/AdminArea.tsx) |
| User | `POST /api/v1/users/:uuid` · update | Implemented | `updateUser` → `UsersAdmin` edit dialog + [ProfileView.tsx](loom-ui/src/features/profile/ProfileView.tsx) |
| User | `PATCH /api/v1/users/:uuid` · partial update | Implemented | Same `service.update`; UI uses POST update (no separate PATCH call needed) |
| User | `PUT /api/v1/users/:uuid` · replace | Implemented | Same `service.update`; UI uses POST update |
| User | `DELETE /api/v1/users/:uuid` · delete | Implemented | `deleteUser` → `UsersAdmin` delete-confirm |
| User | `GET /api/v1/users` · list | Implemented | `listUsers` → `UsersAdmin` table |
| User | `GET /api/v1/users/:uuid` · read | Implemented | `loadUser` (used by [ProfileView.tsx](loom-ui/src/features/profile/ProfileView.tsx)) |
| Group | `POST /api/v1/groups` · create | Implemented | [groups.ts](loom-ui/src/api/groups.ts) `createGroup` → `GroupsAdmin` |
| Group | `POST /api/v1/groups/:uuid` · update | Implemented | `updateGroup` → `GroupsAdmin` edit dialog |
| Group | `PATCH /api/v1/groups/:uuid` · partial update | Implemented | Same `service.update`; UI uses POST update |
| Group | `PUT /api/v1/groups/:uuid` · replace | Implemented | Same `service.update`; UI uses POST update |
| Group | `DELETE /api/v1/groups/:uuid` · delete | Implemented | `deleteGroup` → `GroupsAdmin` delete-confirm |
| Group | `GET /api/v1/groups` · list | Implemented | `listGroups` → `GroupsAdmin` table |
| Group | `GET /api/v1/groups/:uuid` · read | Implemented | `loadGroup` (api present) |
| Role | `POST /api/v1/roles` · create | Implemented | [roles.ts](loom-ui/src/api/roles.ts) `createRole` → `AccessControlAdmin` |
| Role | `POST /api/v1/roles/:uuid` · update (name + permissions) | **Partial** | `updateRole` wired (`handleSaveEdit` for name, `togglePermission` for permissions), but the permission catalog offered by the UI diverges from the backend `RolePermission` enum — see Task 1 |
| Role | `DELETE /api/v1/roles/:uuid` · delete | Implemented | `deleteRole` → `AccessControlAdmin` delete-confirm |
| Role | `GET /api/v1/roles` · list | Implemented | `listRoles` → `AccessControlAdmin` |
| Role | `GET /api/v1/roles/:uuid` · read | Implemented | `loadRole` (api present) |
| Permission | (managed inline via Role `permissions[]`, no standalone endpoint) | **Partial** | Edited via role update; catalog mismatch — see Task 1 |
| Token | `POST /api/v1/tokens` · create | Implemented | [tokens.ts](loom-ui/src/api/tokens.ts) `createToken` → `ApiKeysAdmin` (shows one-time secret) |
| Token | `POST /api/v1/tokens/:uuid` · update (rename) | Implemented | `updateToken` → `ApiKeysAdmin` rename dialog |
| Token | `DELETE /api/v1/tokens/:uuid` · delete | Implemented | `deleteToken` → `ApiKeysAdmin` row menu |
| Token | `GET /api/v1/tokens` · list | Implemented | `listTokens` → `ApiKeysAdmin` table |
| Token | `GET /api/v1/tokens/:uuid` · read | Implemented | `loadToken` (api present) |
| Login | `POST /api/v1/login` · password login | Implemented | [auth.ts](loom-ui/src/api/auth.ts) `login` → [LoginPage.tsx](loom-ui/src/features/auth/LoginPage.tsx) + [AuthContext.tsx](loom-ui/src/context/AuthContext.tsx) |
| Me | `GET /api/v1/me` · current user | Implemented | [auth.ts](loom-ui/src/api/auth.ts) `getMe` → [AuthContext.tsx](loom-ui/src/context/AuthContext.tsx) |
| OAuth2 | `GET /api/v1/auth/oauth2/login` · initiate SSO | **Missing** | No api function, no "Sign in with SSO" action — see Task 2 |
| OAuth2 | `GET /api/v1/auth/oauth2/callback` · IdP callback | **Missing** | No client route handles the post-IdP redirect — see Task 2 |
| OAuth2 | `GET /api/v1/auth/oauth2/logout` · clear session cookie | **Missing** | `logout()` in [AuthContext.tsx](loom-ui/src/context/AuthContext.tsx) only clears client state — see Task 3 |

**Totals:** 29 REST operations across the 7 identity endpoints. 24 Implemented,
2 Partial (Role update / Permission editing — same underlying gap), 3 Missing (the
full OAuth2 BFF flow). Three tasks below.

---

## Task: Reconcile the role permission catalog with the backend RolePermission enum

**Argumentation Summary:** The role editor in `AccessControlAdmin`
([AdminArea.tsx](loom-ui/src/features/admin/AdminArea.tsx), `PERMISSION_GROUPS` around
line 894) presents an aspirational catalog of ~80 permission strings across ~20 resource
groups (`CREATE_ASSET`, `READ_PIPELINE`, `CREATE_ROLE`, `DELETE_GROUP`, `CREATE_TOKEN`, …).
The backend `RolePermission` enum
([RolePermission.java](loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/role/RolePermission.java))
currently defines only **four** values: `READ_USER`, `CREATE_USER`, `DELETE_USER`,
`UPDATE_USER`. Every checkbox outside the "User" group therefore sends a value the REST
`POST /api/v1/roles/:uuid` update cannot deserialize into the enum — the request is
rejected (Jackson enum failure) or the unknown value is dropped server-side. Admins get
the impression they are granting fine-grained asset/pipeline/token permissions when in
reality nothing is persisted. This is a silent RBAC misconfiguration and the single most
misleading gap in the identity UI.

**Improvement Summary:** Make the UI expose only the permissions the backend actually
supports so that what an admin toggles is what gets stored, and add resilience so the
editor stays honest as the enum grows.

```
Endpoints involved:
  - POST /api/v1/roles/:uuid  (role update; permissions[] is validated against RolePermission)
  - GET  /api/v1/roles/:uuid  (returns the persisted permissions[])

Backend source of truth:
  loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/role/RolePermission.java
  (enum: READ_USER, CREATE_USER, DELETE_USER, UPDATE_USER)

UI file to change:
  loom-ui/src/features/admin/AdminArea.tsx  (AccessControlAdmin component; PERMISSION_GROUPS
  constant near line 894, and the togglePermission / render loop near lines 665-845)

Work:
  1. Trim PERMISSION_GROUPS down to the permissions the REST API accepts today
     (the four User permissions), OR — preferred — restructure so the catalog is the single
     documented mirror of the RolePermission enum with a clear comment pointing at the Java
     source, so the two are kept in sync deliberately.
  2. Round-trip safety: after updateRole, reload the role and reconcile the displayed
     checkboxes against the permissions[] the server actually returns. If the server drops
     an unknown value, the UI must reflect the server state (unchecked), not the optimistic
     local state, so admins never see a "granted" permission that was silently rejected.
  3. Surface backend rejection: if POST /roles/:uuid returns 4xx for an invalid permission,
     show an error toast instead of the current silent console.error in togglePermission.
  4. (Stretch) If/when a permission-catalog endpoint is added, drive PERMISSION_GROUPS from
     it instead of a hardcoded constant. There is no such endpoint today, so document the
     hardcoded catalog as the interim contract.

Edge cases:
  - A role loaded from the API may already contain permission strings not present in the
    (trimmed) catalog. Render such unknown/legacy values as read-only "unknown permission"
    rows rather than hiding them, so nothing is silently lost on the next save.
  - Permission gating: only users whose role includes UPDATE_ROLE (once that exists) should
    be able to toggle; today the endpoint is only secured, so gate on the same capability the
    rest of AdminArea uses.
```

**References:**
- [RolePermission.java](loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/role/RolePermission.java)
- [RoleUpdateRequest.java](loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/role/RoleUpdateRequest.java) / [RoleResponse.java](loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/role/RoleResponse.java)
- [RoleEndpoint.java](loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/RoleEndpoint.java)
- [roles.ts](loom-ui/src/api/roles.ts), [AdminArea.tsx](loom-ui/src/features/admin/AdminArea.tsx)
- [spec/loom/RESTAPI.md](../RESTAPI.md) §3.2, [spec/loom/DOMAIN.md](../DOMAIN.md) group 1, [spec/loom/ui/LOOM_UI.md](LOOM_UI.md)

**Test Requirements:**
- Component test: render `AccessControlAdmin` with a mocked role that has `permissions:
  ["CREATE_USER"]`; assert only backend-supported permissions render as toggleable and that
  toggling `CREATE_USER` issues `POST /roles/:uuid` with the exact enum string.
- Component test: mock the update to return a role whose `permissions` omit a just-toggled
  value; assert the checkbox reverts to unchecked (server wins) and an error toast appears.
- Component test: a role carrying an unknown permission string renders it as a read-only
  "unknown" row and the value survives a subsequent save.

## Task: Add OAuth2 SSO sign-in (initiate + callback) to the login flow

**Argumentation Summary:** The backend ships a complete OAuth2 BFF flow with PKCE
([OAuth2Endpoint.java](loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/OAuth2Endpoint.java)):
`GET /api/v1/auth/oauth2/login` initiates the flow, `GET /api/v1/auth/oauth2/callback`
handles the IdP redirect and sets the session cookie, with auto-provisioning of unknown
users ([RESTAPI.md](../RESTAPI.md) §2.3). The UI has **no** coverage: [auth.ts](loom-ui/src/api/auth.ts)
exposes only password `login`, and [LoginPage.tsx](loom-ui/src/features/auth/LoginPage.tsx)
renders only a username/password form. Deployments configured for SSO cannot be used from
the UI at all. RESTAPI.md §"HTTP client does not have methods for OAuth2 login/callback/logout"
already flags this.

**Improvement Summary:** Give users a "Sign in with SSO" path that hands off to the IdP and
resumes an authenticated session on return, alongside the existing password login.

```
Endpoints involved:
  - GET /api/v1/auth/oauth2/login     (browser navigation; server 302-redirects to the IdP)
  - GET /api/v1/auth/oauth2/callback  (IdP redirects the browser here; server sets session cookie)

UI files to change/add:
  - loom-ui/src/api/auth.ts        add helpers: oauth2LoginUrl() returning the absolute
                                   `${API_BASE_URL}/auth/oauth2/login` URL for a full-page
                                   navigation (NOT a fetch — this is a top-level redirect so
                                   the browser follows the IdP hops and stores cookies).
  - loom-ui/src/features/auth/LoginPage.tsx   add a "Sign in with SSO" button under the
                                   password form that does window.location.assign(oauth2LoginUrl()).
  - loom-ui/src/context/AuthContext.tsx       after returning from the callback the session is
                                   cookie-based; call getMe() to hydrate isAuthenticated/username/
                                   userUuid from the cookie session instead of relying on a
                                   bearer token in memory.
  - Routing: add a post-login landing that, on app load, attempts getMe() so a user who returns
                                   from /auth/oauth2/callback (cookie already set by the server)
                                   is recognized as authenticated without a manual token.

Work:
  1. Because the flow is BFF/cookie-based, getMe() and all subsequent API calls must send
     credentials: "include" when there is no bearer token. Audit authHeaders/fetch usage: the
     current api modules attach Authorization: Bearer only. Add a cookie-session code path so
     SSO users (no bearer token) still authenticate.
  2. Only render the SSO button when SSO is configured. If no config/feature flag is available,
     render it unconditionally but degrade gracefully when the endpoint is disabled.

Edge cases:
  - Callback error/denied: server may redirect back with an error; LoginPage should show an
    auth error rather than a blank state.
  - Mixed mode: a user may have both a password and an SSO identity (auto-provisioning); both
    entry points must converge on the same authenticated app state.
  - CSRF/state and PKCE are handled server-side; the UI must not attempt to manage the state
    parameter itself.
```

**References:**
- [OAuth2Endpoint.java](loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/OAuth2Endpoint.java)
- [spec/loom/RESTAPI.md](../RESTAPI.md) §2.3 (OAuth2 BFF Pattern), §3.2
- [auth.ts](loom-ui/src/api/auth.ts), [LoginPage.tsx](loom-ui/src/features/auth/LoginPage.tsx), [AuthContext.tsx](loom-ui/src/context/AuthContext.tsx)
- [spec/loom/ui/LOOM_UI.md](LOOM_UI.md)

**Test Requirements:**
- Component test: `LoginPage` renders a "Sign in with SSO" button that triggers a full-page
  navigation to `${API_BASE_URL}/auth/oauth2/login`.
- e2e (Playwright): with a stubbed IdP + callback that sets the session cookie, clicking SSO
  ends with the app authenticated and `GET /me` succeeding via the cookie session (no bearer).
- Unit test: `getMe()`/api calls send `credentials: "include"` when no bearer token is present.

## Task: Wire OAuth2 session logout so cookie sessions are actually terminated

**Argumentation Summary:** `logout()` in [AuthContext.tsx](loom-ui/src/context/AuthContext.tsx)
(invoked from the user menu in [Sidebar.tsx](loom-ui/src/layout/Sidebar.tsx)) only clears
client-side state. For OAuth2/BFF sessions the authentication lives in a server-set session
cookie, so clearing local state leaves the user still logged in server-side — the next
`GET /me` (or a page reload) re-authenticates them from the cookie. The backend provides
`GET /api/v1/auth/oauth2/logout` to clear that cookie, but the UI never calls it.

**Improvement Summary:** Make logout terminate the server session for cookie-based (SSO)
users while continuing to work for bearer-token password users.

```
Endpoint involved:
  - GET /api/v1/auth/oauth2/logout  (clears the session cookie server-side)

UI files to change:
  - loom-ui/src/api/auth.ts        add logout() that calls
                                   `${API_BASE_URL}/auth/oauth2/logout` with
                                   credentials: "include".
  - loom-ui/src/context/AuthContext.tsx   in logout(): if the session is cookie-based (no
                                   in-memory bearer token), await the api logout() before
                                   clearing local state; for bearer-token sessions, clearing
                                   local state remains sufficient. Always clear local state
                                   afterwards, even if the network call fails.

Edge cases:
  - Logout endpoint failure (network/5xx) must still clear local state so the user is not
    stuck in a half-logged-in UI; surface a non-blocking warning.
  - Idempotency: calling logout when already logged out should be a no-op.
  - Note (documented backend limitation, RESTAPI.md): the endpoint clears the cookie but does
    not revoke IdP tokens — out of scope for the UI, but call it out in the logout UX copy if
    relevant.
```

**References:**
- [OAuth2Endpoint.java](loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/OAuth2Endpoint.java) (`/logout`)
- [spec/loom/RESTAPI.md](../RESTAPI.md) §2.3
- [auth.ts](loom-ui/src/api/auth.ts), [AuthContext.tsx](loom-ui/src/context/AuthContext.tsx), [Sidebar.tsx](loom-ui/src/layout/Sidebar.tsx)

**Test Requirements:**
- Unit test: for a cookie-session user, `logout()` calls `GET /auth/oauth2/logout` with
  credentials included, then clears auth state.
- Unit test: for a bearer-token user, `logout()` clears state without requiring the network
  call, and still clears state if the call rejects.
- e2e (Playwright): after SSO login + logout, a reload does NOT restore the session (`GET /me`
  is unauthenticated).
