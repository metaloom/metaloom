# MetaLoom — CRUD Coverage Tasks: Identity & Access (RBAC)

> Gaps between the REST API and the Loom UI (`loom-ui/`) for the RBAC domain:
> **User, Group, Role, Permission, Token**.
> Derived from REST endpoints, UI api clients/features, and e2e specs.
> Format follows [../../TASKS.template.md](../../TASKS.template.md). See [../DOMAIN.md](../DOMAIN.md).

## Coverage Summary

| Element | Create | Read/List | Update | Delete | E2E | Notes |
|---------|--------|-----------|--------|--------|-----|-------|
| **User** | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | UI edits username/firstname/lastname/email, but `UserEndpointService.update` persists only `meta`; edit-e2e never changes/asserts a field. No enable/disable or password op is exposed by REST. |
| **Group** | ✅ | ✅ | ✅ | ✅ | ✅ | Fully wired; edit persists `name` and e2e verifies rename. No role/member assignment exposed by REST (`GroupUpdateRequest` = name+meta only). |
| **Role** | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | Name CRUD complete. Permission-assignment tree in UI calls `updateRole({permissions})`, but `RoleEndpointService.update` ignores `getPermissions()`; no e2e toggles/persists a permission. |
| **Permission** | n/a | ✅ | ⚠️ | n/a | ❌ | Not an independent entity; granted via Role. Assignment UI exists but is not persisted end-to-end and has no e2e. Token/user permission grants are not exposed by REST at all. |
| **Token (API key)** | ✅ | ⚠️ | ❌ | ✅ | ❌ | Create + list + delete wired in `ApiKeysAdmin`. `updateToken`/`loadToken` clients exist but are **never invoked** (no rename UI, no detail view). **No `tokens-backend.spec.ts` at all.** No token permission/scope field exposed by REST. |

Legend: ✅ covered · ⚠️ partial / not persisted / weak coverage · ❌ missing

---

## Task: Add an end-to-end spec for Token (API key) management

**Argumentation Summary:** `ApiKeysAdmin` performs real create / list / delete against `GET|POST|DELETE /api/v1/tokens`, yet there is **no `tokens-backend.spec.ts`** in `loom-ui/e2e/` — every other RBAC element (users, groups, roles) has one. Token CRUD is entirely untested at the UI level.

**Improvement Summary:** Create `loom-ui/e2e/tokens-backend.spec.ts` covering the full API-key lifecycle exercised by the UI, following the structure of the existing RBAC specs.

```
Create loom-ui/e2e/tokens-backend.spec.ts modeled on loom-ui/e2e/groups-backend.spec.ts:
- loginAndGoToApiKeys(page): login as admin/finger, click the "API Keys" sidebar entry
  (route /admin/api-keys), assert the API Keys heading is visible.
- Test "create an API key": click "Create Key", fill the key-name field, submit, assert the
  one-time token value is shown in the create dialog and the new key row appears in the table.
- Test "rename an API key": once the rename action lands (previous task), verify the updated name.
- Test "delete an API key": open the row's overflow menu, click Delete, assert the row disappears.
Reuse the tab labels/routes wired in AdminArea.tsx (t("admin.tab.*"), <Route path="api-keys" ...>).
```

**References:**
- [loom-ui/src/features/admin/AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) — `ApiKeysAdmin`, `/admin/api-keys` route
- [loom-ui/e2e/groups-backend.spec.ts](../../../loom-ui/e2e/groups-backend.spec.ts) — reference structure
- `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/TokenEndpoint.java`

**Test Requirements:**
- New Playwright spec asserting create (with one-time token reveal), delete, and (after the rename task) update against a live backend with demo data.

---

## Task: Persist and e2e-cover Role → Permission assignment

**Argumentation Summary:** The core purpose of the `AccessControlAdmin` screen ([AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) ~line 634) is granting permissions to roles: `togglePermission()` calls `updateRole(token, uuid, { permissions: next })` and then `reload()`. The REST request model `RoleUpdateRequest` **does** carry `List<RolePermission> permissions`, but `RoleEndpointService.update()` only applies `getName()` and `getMeta()` — it **never reads `request.getPermissions()`**. So after `reload()` the toggles revert; permission assignment is effectively non-functional end-to-end. The `roles-backend.spec.ts` "select a role and view permissions" test only *views* the tree and never toggles or re-reads a permission, so this gap is invisible to CI.

**Improvement Summary:** Make `RoleEndpointService.update` (and `create`) apply the submitted permission set, then add e2e coverage that toggles a permission, reloads, and asserts persistence.

```
Backend (loom/services/rest/.../service/impl/RoleEndpointService.java):
- In update(): read request.getPermissions() and, when non-null, replace the role's granted
  permissions via the RoleDao (role_permission table). Mirror the existing update(getName/getMeta)
  pattern. Do the same for create() so RoleCreateRequest.permissions is honored.
- Confirm the wire type: RoleUpdateRequest.permissions is List<RolePermission>; the UI roles.ts
  types permissions as string[]. Ensure the enum names line up (RolePermission currently only
  declares READ_USER/CREATE_USER/DELETE_USER/UPDATE_USER while the UI permission tree in
  AdminArea.tsx is built from the full Permission grouping ~line 891) — reconcile so all granted
  permission strings round-trip.

E2E (loom-ui/e2e/roles-backend.spec.ts):
- Add a test that selects a demo role, toggles a specific permission checkbox in the tree,
  navigates away/reloads the Permissions view, re-selects the role, and asserts the checkbox
  retained its new state (and the "N permissions granted" count changed).
```

**References:**
- [loom-ui/src/features/admin/AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) — `AccessControlAdmin`, `togglePermission`
- [loom-ui/src/api/roles.ts](../../../loom-ui/src/api/roles.ts) — `updateRole`, `RoleUpdateRequest.permissions`
- `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/RoleEndpointService.java` — `update()`/`create()`
- `loom-shared/rest-model/.../rest/model/role/RoleUpdateRequest.java`, `RolePermission.java`
- [loom-ui/e2e/roles-backend.spec.ts](../../../loom-ui/e2e/roles-backend.spec.ts)

**Test Requirements:**
- E2E test proving a permission grant survives a reload.
- Backend unit/endpoint test (`RoleEndpointTest`) asserting that an update request with a `permissions` list is reflected in a subsequent role read.

---

## Task: Persist and e2e-verify User profile-field edits

**Argumentation Summary:** The Users admin edit dialog and `ProfileView` both submit `username/firstname/lastname/email` via `updateUser()`, but `UserEndpointService.update()` only applies `update(request::getMeta, user::setMeta)` — it **ignores** the username and name/email fields even though `UserUpdateRequest` defines them. Consequently the "edit a user" case in [users-backend.spec.ts](../../../loom-ui/e2e/users-backend.spec.ts) merely opens the dialog and clicks Save **without changing or asserting any value**, so it cannot detect that edits are dropped. (Note: enable/disable and password reset are intentionally out of scope — REST exposes neither; `enabled` is read-only on `UserResponse` and there is no password field on the request models.)

**Improvement Summary:** Apply the profile fields in the backend update, then strengthen the user-edit e2e to change a field and assert the new value persists.

```
Backend (loom/services/rest/.../service/impl/UserEndpointService.java, update()):
- In addition to meta, apply request.getUsername()/getFirstname()/getLastname()/getEmail() to the
  User via the existing update(getter, setter) helper pattern before setEditor/save.

E2E (loom-ui/e2e/users-backend.spec.ts, "edit a user"):
- Change the firstname (or email) in the edit dialog, save, reopen the row, and assert the field
  shows the new value — replacing the current no-op open/save.
```

**References:**
- [loom-ui/src/api/users.ts](../../../loom-ui/src/api/users.ts) — `updateUser`, `UserUpdateRequest`
- [loom-ui/src/features/admin/AdminArea.tsx](../../../loom-ui/src/features/admin/AdminArea.tsx) — `UsersAdmin` edit dialog
- [loom-ui/src/features/profile/ProfileView.tsx](../../../loom-ui/src/features/profile/ProfileView.tsx) — self-service `updateUser`
- `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/UserEndpointService.java` — `update()`
- [loom-ui/e2e/users-backend.spec.ts](../../../loom-ui/e2e/users-backend.spec.ts)

**Test Requirements:**
- E2E test that edits a user field and asserts the persisted change after reload.
- Backend endpoint test (`UserEndpointTest`) asserting an updated username/email is returned on a subsequent read.
