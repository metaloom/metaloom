# MetaLoom — Task Queue

Captured, not-yet-scheduled work. Every entry follows [../TASKS.template.md](../TASKS.template.md).
Completed entries are collapsed to a one-line outcome record; only open work keeps full detail.

> Note: [../CONTEXT.md](../CONTEXT.md) and
> [../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md](../cortex/METALOOM_ARCHITECTURE_V2_PLAN_C.md) §3
> still link this file as `spec/tasks/TASKS.md`. The real path is `spec/plans/TASKS.md`.

## Progress Assessment

- [x] 🔴 Persist role permissions — shipped
- [ ] Cache expensive intermediate artifacts inside node implementations — **open**, verified
      unimplemented at `2e5981cb` (see below)
- [x] `imagegen` node + Ideogram sidecar ([imagegen-node.md](imagegen-node.md)) — shipped

## Completed

| Task | Outcome |
|---|---|
| Persist role permissions (admin ACL matrix was a silent no-op) | Shipped. `RolePermission` (rest-model) now mirrors all 129 constants of `Permission` (db-api), guarded by `loom/services/rest/…/perm/RolePermissionParityTest.java`. `V2.64__fix_role_permission_key.sql` drops `role_permission.resource` and its redundant unique index — nothing on the authorization path ever read the column, so the key now *is* the intended grain. `RoleDao.loadPermissions` / `setPermissions` (replace semantics, one transaction) back `RoleEndpointService.create`/`update`/`delete` and `RoleModelBuilder.toResponse`; absent = unchanged, `[]` = revoke all. `PermissionCache` gained `invalidate`/`invalidateAll`, called on every grant write — without it the rows would persist and still change nothing. Covered by `RoleDaoTest`, the permission cases in `RoleEndpointTest` and `RolePermissionEnforcementTest` (grant over REST → asset becomes readable → revoke → 403). The UI matrix's stale `*_PROJECT` entries were corrected to `*_SPACE`. Documented in [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md) §4.4, [../features/rbac/RBAC.md](../features/rbac/RBAC.md) and [../loom/RESTAPI.md](../loom/RESTAPI.md). |
| `imagegen` node + Ideogram 4.0 Python sidecar ([imagegen-node.md](imagegen-node.md)) | Shipped. Node in `cortex/nodes/image-generation/core/…/imagegen/` (`ImageGenNode`, `ImageGenClient`, `ImageGenNodeOptions`, `ImageGenMode` for `GENERATE`/`REMIX`, `ImageGenNodeModule`), wired via `cortex/cli/…/dagger/NodeCollectionModule.java`; unit/pipeline/persistence/options tests plus `integration-test/…/node/ImageGenNodeIntegrationTest.java`; sidecar in `sidecars/ideogram-sidecar/`; documented in [../features/pipeline-nodes/NODES.md](../features/pipeline-nodes/NODES.md) and `website/content/english/docs/nodes/imagegen/`. The plan file's own status header (“proposed”) is stale. Its “Loom binary upload” follow-up is now tracked in [../features/rest/REST_CORTEX_METADATA_BINARY_HANDLING_PLAN.md](../features/rest/REST_CORTEX_METADATA_BINARY_HANDLING_PLAN.md) — not here. |

---


---

---

_Git HEAD revision: `d930e222`_
_Last updated: 2026-08-02 (role-permission persistence shipped and moved to Completed)_
