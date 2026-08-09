# Coding Rules — Definition of Done

These are **rules, not background**. They apply to *every* code change, on top of whatever the
feature spec demands. Entry point for the spec tree: [../METALOOM_CONTEXT.md](../METALOOM_CONTEXT.md) (§0.2 summarises
this file — **this file is the authority**). Rules for editing a spec: [SPEC_RULES.md](SPEC_RULES.md).
Adding a Cortex node has its own definition of done: [NEW_NODE.md](NEW_NODE.md).

**The code is the source of truth.** Where a spec and the code disagree, the code wins — and you fix
the spec in the same change (§ Spec below).

## REST

* Method-carrying **collection** paths are always **plural**: `/assets`, `/chats`, `/skills`,
  `/node-results`, `/dedup-groups`. Singular is reserved for singleton or RPC-style resources that
  are not a collection (`/me`, `/login`, `/health`, `/search`, `/graphql`). Sub-resources follow the
  same rule: `/assets/:uuid/node-results`.
* Renaming a path is a breaking change — update `loom-client/rest/.../LoomHttpClientImpl.java`,
  `loom-ui/src/api/` and the REST docs in the same commit.
* Every endpoint implementation is covered by a `*EndpointTest` in
  `loom/core/src/test/java/io/metaloom/loom/core/endpoint/test/`. Extend `AbstractCRUDEndpointTest`
  for a CRUD resource (it supplies `testRead`/`testCreate`/`testUpdate`/`testDelete`/`testReadPaged`),
  `AbstractEndpointTest` otherwise (e.g. `NodeResultEndpointTest`). An endpoint without one is unfinished.
* Add **permission test cases** asserting fine-grained permission handling — not only the admin path.
  `SkillEndpointTest` is the reference: grant the fixture user its permissions via a **group + role**,
  never a direct user grant (`user_permission` allows one direct permission per user). See
  [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md).
* Do not redeclare `@RegisterExtension LoomCoreTestExtension` in a subclass of `AbstractEndpointTest`;
  configure the inherited `loom` field.

## DAO

* Every DAO implementation is covered by tests: contract tests in `loom/db/api-test`, impl tests in
  `loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/` (standard shape: `CRUDDaoTestcases`).
* `delete` must be covered by **delete-cascade tests** asserting that *only* the targeted rows
  disappear — create a second, untouched entity with an identical row set and assert it survives.
  References: `AssetCascadeTest`, `AclCascadeTest`.
* After any Flyway change under `loom/db/flyway/.../db/migration/`: install `loom/db/flyway`, run
  `loom/db/jooq/generate.sh` (jOOQ codegen; JSON/JSONB columns need a `forcedTypes` converter entry),
  then re-run `./setup-pool.sh` — otherwise the pooled test databases stay stale.

## Docs

* New **customer-facing** features must be documented under `website/content/english/docs/`.
  - Don't mention spec files.
  - Don't include internal coding references (class names, packages, module paths).
  - Keep the tone customer-facing.
  - Don't use ASCII-art diagrams — use SVG (or the existing `ml-nodeviz` blocks for node pages).
* See [../website/WEBSITE.md](../website/WEBSITE.md) for the build (the system `hugo` is too old;
  an extended ≥ 0.158 binary is required).

## Demo

* New features must ship meaningful default demo data — `DemoDatabaseInitializer`
  (`loom/core/src/main/java/io/metaloom/loom/core/boot/`). It seeds the demo space, collections,
  libraries, pools and pipelines the demo container starts with.
* If a feature genuinely cannot run in the demo container (e.g. it needs a GPU sidecar), follow the
  existing precedent: leave it out and say so in the spec rather than seeding something that fails.

## Spec

* Changing a feature **must** also update the corresponding spec file under `spec/`, so the internal
  AI coding guides stay in sync. Format rules: [SPEC_RULES.md](SPEC_RULES.md); task files
  follow [../TASKS.template.md](../tasks/TASKS.template.md).
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_