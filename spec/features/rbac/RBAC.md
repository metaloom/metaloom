# MetaLoom // RBAC (Role-Based Access Control)

This document describes how MetaLoom authorizes API access: the permission model, the
permission taxonomy, and exactly where and how permissions are enforced in the REST and
GraphQL APIs. It also records the known enforcement gaps.

> This is a living specification consumed by AI agents and developers. It complements
> [../../loom/RESTAPI.md](../../loom/RESTAPI.md) §2.6 (which currently links to a
> not-yet-existing `PERMISSION.md`); this file is the authoritative RBAC reference.

## 1. Model

MetaLoom uses a classic **user → group → role → permission** chain, augmented with direct
user and token grants. The schema originates in
`loom/db/flyway/src/main/resources/db/migration/V2.1__add_acl.sql` and is extended by later
`V2.x` migrations (e.g. `V2.25` blacklist/person, `V2.53` agent memory, `V2.54` memory deny
rules).

Tables:

- `user`, `group`, `role` — the entity tables.
- `user_group` — M:N users ↔ groups.
- `role_group` — M:N roles ↔ groups.
- `role_permission (role_uuid, resource, permission)` — permissions granted to a role.
- `user_permission (user_uuid, resource, permission)` — permissions granted directly to a user.
- `token_permission (token_uuid, resource, permission)` — permissions granted to an API token.

**Effective permissions** for a user are computed in
`PermissionDaoImpl.loadPermissionsForUser`
(`loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/perm/PermissionDaoImpl.java`) as the
union of:

1. role permissions reached via `USER_GROUP → ROLE_GROUP → ROLE_PERMISSION`, and
2. direct `USER_PERMISSION` rows,

returning a `ResourcePermissionSet`. A parallel `loadPermissionsForToken` covers API-token auth.

> **Direct-grant pitfall:** `user_permission` is keyed such that a user carries at most one
> direct permission grant. To give a test/user several permissions, grant them to a **role** and
> attach the role to the user via a **group** (see the test setup in §5).

**Caching.** `PermissionCache`
(`loom/services/auth/auth-common/.../auth/PermissionCache.java`) is a Caffeine cache (max 10,000)
keyed by user UUID → `ResourcePermissionSet`. `LoomAuthorizationProvider` implements the Vert.x
`AuthorizationProvider`: on `getAuthorizations(user)` it reads the `uuid` claim, loads the
(cached) permission set, and converts each entry into a Vert.x `PermissionBasedAuthorization`
placed on `user.authorizations()`.

## 2. Permission taxonomy

All permissions are constants of the single enum
`io.metaloom.loom.db.model.perm.Permission`
(`loom/db/api/src/main/java/io/metaloom/loom/db/model/perm/Permission.java`). The DB mirror is
the Postgres enum `loom_permission`; the jOOQ mirror is `JooqLoomPermission`
(the DAO bridges via `JooqLoomPermission.valueOf(perm.name())`).

Naming convention: **`{VERB}_{ENTITY}`**, with the standard CRUD verbs
`CREATE_`, `READ_`, `UPDATE_`, `DELETE_` per entity — e.g. `CREATE_USER`, `READ_USER`,
`UPDATE_USER`, `DELETE_USER`. Entities include: Annotation, Asset, AssetBinary, Attachment,
User, Role, Group, Space, Cluster, Collection, Comment, Embedding, Reaction, Task, Tag, Token,
Library, Pipeline, AssetPool, Blacklist, Person, Detection, Chat, Skill, ChatSession, Memory,
MemoryDenyRule, …

Notable rules:

- **There is no dedicated `LIST` permission.** List endpoints reuse the entity's `READ_*`
  permission (e.g. `list(lrc, READ_ROLE, …)`).
- Non-CRUD verbs exist for special actions: `TAG_ASSET`, `UNTAG_ASSET`,
  `RESTORE_PIPELINE_VERSION`, `RESTORE_SKILL_VERSION`, `MANAGE_CORTEX_INSTANCE`,
  `UPDATE_PIPELINE_RUN` (governs pipeline run cancel/pause/resume).

## 3. Where permissions are checked — REST

The REST enforcement funnel:

```
*EndpointService (maps each op → Permission: CREATE_/READ_/UPDATE_/DELETE_X)
  → AbstractCRUDEndpointService.{create, load, list, update, delete}
    → AbstractEndpointService.checkPerm(lrc, permission, action)
      → LoomRoutingContext.requirePerm(perm...)
        → Vert.x PermissionBasedAuthorization.create(perm.name()).match(user)
```

- `AbstractCRUDEndpointService`
  (`loom/services/rest/.../rest/service/AbstractCRUDEndpointService.java`) provides generic
  `create/load/list/update/delete` helpers, each taking the required `Permission` and wrapping
  the DB work inside `checkPerm(...)`.
- `AbstractEndpointService.checkPerm`
  (`loom/services/rest/.../rest/service/AbstractEndpointService.java`) resolves the caller's
  authorizations and, on failure, throws `LoomRestException(403, MISSING_PERM, "Invalid permissions")`.
- **Result of a missing permission: HTTP `403` with error code `MISSING_PERM`.**

> **Ordering note:** `checkPerm` runs **before** the DAO loader. A caller lacking the permission
> receives `403` even when the target UUID does not exist — the `403` takes precedence over the
> `404`. (Conversely, for a permitted caller, element-scoped resources that belong to another user
> surface as `404`, keeping foreign objects indistinguishable from missing ones.)

Each concrete `*EndpointService` under
`loom/services/rest/.../rest/service/impl/` maps its operations to permissions, e.g.
`RoleEndpointService`:

```java
public void delete(...) { delete(lrc, DELETE_ROLE, uuid); }
public void list(...)   { list(lrc, READ_ROLE, modelBuilder::toRoleList); }
public void load(...)   { load(lrc, READ_ROLE, () -> dao().load(uuid), modelBuilder::toResponse); }
public void create(...) { create(lrc, CREATE_ROLE, () -> {...}, modelBuilder::toResponse); }
public void update(...) { update(lrc, UPDATE_ROLE, () -> {...}, modelBuilder::toResponse); }
```

Authentication (identifying the caller before authorization) is handled separately by
`LoomAuthenticationHandler`
(`loom/services/auth/auth-common/.../auth/LoomAuthenticationHandler.java`); an unauthenticated
request is rejected with `401` before it reaches the permission check.

## 4. Where permissions are checked — GraphQL

The GraphQL API enforces permissions at the **field level**, decoupled from the graphql module
via an injected checker.

- `GraphQLPermissionChecker`
  (`loom/services/graphql/.../graphql/GraphQLPermissionChecker.java`) — a `@FunctionalInterface`
  with `boolean hasPermission(Permission)` and `CONTEXT_KEY = "loom.permissionChecker"`.
- `AbstractDomainWiring.requirePermission(env, permission)`
  (`loom/services/graphql/.../graphql/AbstractDomainWiring.java`) is called at the top of every
  data fetcher. It pulls the checker from `env.getGraphQlContext()`:
  - missing checker → `GraphqlErrorException` with code **`UNAUTHENTICATED`**;
  - missing permission → code **`FORBIDDEN`** (with a `permission` extension naming the required perm).
- Injection point: `GraphQLEndpoint`
  (`loom/services/rest/.../rest/endpoint/impl/GraphQLEndpoint.java`) first resolves
  `lrc.permissionChecker()` (loading the user's authorizations once, asynchronously), then places
  it into `ExecutionInput.graphQLContext(...)`.

The checker (`LoomRoutingContext.permissionChecker()`) delegates to the same
`PermissionBasedAuthorization.create(perm.name()).match(user)` evaluation used by REST — so **REST
and GraphQL share identical underlying authorization logic**. The GraphQL API is currently
**read-only**: every fetcher requires a `READ_*` permission (wirings: `AclWiring` for
users/roles/groups, `AssetWiring`, `MemoryWiring`, `PipelineWiring`, `SkillWiring`).

## 5. Known gaps

- **Permissions are global per type; the `resource` column is ignored.** The ACL tables carry a
  `resource` column and the grant APIs accept it, but `LoomAuthorizationProvider` builds each
  Vert.x authorization from the permission **name only** (`PermissionBasedAuthorization.create(perm.getPermission())`).
  Both REST `requirePerm` and GraphQL `requirePermission` match on `perm.name()` alone. There is
  **no object/element-level (per-UUID) enforcement** — the `resource` string is a forward-looking
  hook that is not yet wired into the check path. True object-level enforcement would require
  threading `resource` through `LoomAuthorizationProvider`.
- **MCP server bypasses auth entirely.** The MCP server (separate port, RESTAPI.md §4.5/§7.9)
  accesses DAOs directly and does not apply the authentication or permission layers.
- **Unsecured auxiliary endpoints.** `NodeDescriptorEndpoint` and the ContentTypes endpoint are
  not secured; the Processor and PipelineEvent WebSocket routes use post-upgrade auth rather than
  the standard handler (strict WS auth is opt-in via `LOOM_WS_STRICT_AUTH=true`).
- **Dangling doc link.** RESTAPI.md §2.6 references `PERMISSION.md`, which does not exist; treat
  this file as the RBAC reference until that link is reconciled.

## 6. Test coverage

RBAC enforcement is verified end-to-end against a live server + pooled database.

- **REST CRUD** — the CRUD endpoint contract now bakes in permission-denied cases. The interface
  `CRUDEndpointTestcases`
  (`loom/core/src/test/java/io/metaloom/loom/core/endpoint/CRUDEndpointTestcases.java`) declares
  `testCreateRequiresPermission`, `testReadRequiresPermission`, `testListRequiresPermission`, and
  `testDeleteRequiresPermission`. `AbstractCRUDEndpointTest` implements them generically: it logs
  in as a freshly provisioned user holding **no** permissions
  (`AbstractEndpointTest.loginPermissionlessClient()`) and asserts each operation returns `403`
  (`expect(403, "Forbidden", …)`). Every concrete `*EndpointTest` supplies the four entity-specific
  request builders (`createRequest`/`loadRequest`/`listRequest`/`deleteRequest`), so the compiler
  forces permission coverage on all CRUD entities (User, Role, Group, Asset, Tag, Task, Library,
  Space, Cluster, Person, Annotation, Detection, Embedding, Attachment, AssetBinary, AssetPool,
  Chat, Skill).
- **GraphQL** — the analogous contract is `GraphQLSecurityTestcases`
  (`.../endpoint/graphql/GraphQLSecurityTestcases.java`): every domain GraphQL test must implement
  `testIndividualRetrievalRequiresPermission` and `testListRetrievalRequiresPermission`, using
  `AbstractGraphQLTest.loginPermissionlessClient()` + `assertRetrievalForbidden(client, READ_X, query)`.
- **Bespoke negatives** — additional permission tests exist for non-CRUD flows, e.g. Pipeline run
  control (`PipelineRun*EndpointTest`, `403` without `READ_/UPDATE_PIPELINE_RUN`) and the memory
  deny-list (`MemoryDenyRuleEndpointTest`, asserting `*_MEMORY` does not grant `*_MEMORY_DENY_RULE`).

### RBAC test setup pattern

To grant a non-admin test user a specific permission set, create a role, grant it the
permissions, and attach it via a group (direct user grants are limited to one permission — see
§1). Example from `SkillEndpointTest`/`MemoryEndpointTest`:

```java
DaoCollection daos = loom.internal().daos();
User joedoe = daos.userDao().load(USER_UUID);
Role role = daos.roleDao().createRole(ADMIN_UUID, "test-role");
daos.roleDao().store(role);
for (Permission perm : List.of(Permission.CREATE_SKILL, Permission.READ_SKILL, /* … */)) {
    daos.permissionDao().grantRolePermission(role.getUuid(), perm, "test");
}
Group group = daos.groupDao().create(joedoe, "test-group");
daos.groupDao().store(group);
daos.groupDao().addRoleToGroup(group, role);
daos.groupDao().addUserToGroup(group, joedoe);
client.setToken(client.login("joedoe", "finger").sync().body().getToken());
```

For the inverse (a caller that must be denied), `loginPermissionlessClient()` provisions a fresh
enabled user with **no** grants — authentication succeeds (valid token), but every permission
check fails.
