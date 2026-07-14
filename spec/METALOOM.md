# MetaLoom — Project Context for AI Agents

This document is the entry point for AI coding tasks inside the
[metaloom](../) repository. It summarises the module layout, key
frameworks, and the "where do I look?" pointers that new tasks need.

> Additional living notes are kept under `/memories/repo/` (see the
> memory tool). Long-form docs live in [loom/doc/src/main/docs](../loom/doc/src/main/docs).

---

## 1. Big Picture

MetaLoom is a Digital Asset Management (DAM) platform. It consists of two
main runtime components that share a common set of libraries:

| Component | Role | Reactor location |
| --- | --- | --- |
| **Loom**   | Backend service (REST/gRPC/GraphQL API, DB, auth, storage) | [loom/](../loom/) |
| **Cortex** | Processing node (hashing, fingerprint, facedetect, LLM, …) that talks to Loom | [cortex/](../cortex/) |
| **loom-ui** | React/Vite/MUI web front end | [loom-ui/](../loom-ui/) |
| **loom-app** | Electron desktop wrapper around the UI | [loom-app/](../loom-app/) |

Top-level reactor modules (from [pom.xml](../pom.xml)):

```
bom, loom-test-env, loom-shared, loom-client,
cortex, loom, examples, integration-test, e2e-test, website
```

Loom runtime is built on **Vert.x 5** (see `vertx.version` in
[pom.xml](../pom.xml)). Dependency injection uses **Dagger 2** in both
Loom and Cortex (look for `dagger/` sub-packages and generated
`Dagger*Component` classes under `target/generated-sources/annotations`).

Build entry points:

- Full build + UI + containers: [build.sh](../build.sh)
- Integration tests:            [it.sh](../it.sh)
- End-to-end tests:             [e2e.sh](../e2e.sh)
- Local Postgres + demo:        [start-postgres.sh](../start-postgres.sh) / [start-demo.sh](../start-demo.sh)
- Fast compile check:           `mvn -T 8 test-compile -q -DskipTests`

---

## 2. Documentation Location

Canonical, human-facing documentation lives under
[loom/doc/src/main/docs](../loom/doc/src/main/docs) as AsciiDoc:

| Topic | File |
| --- | --- |
| Landing page | [_index.adoc](../loom/doc/src/main/docs/_index.adoc) |
| REST API reference | [loom/rest-api/index.adoc](../loom/doc/src/main/docs/loom/rest-api/index.adoc) |
| Java client usage | [loom/java-client/index.adoc](../loom/doc/src/main/docs/loom/java-client/index.adoc) |
| Authentication | [loom/authentication/index.adoc](../loom/doc/src/main/docs/loom/authentication/index.adoc) |
| Configuration | [loom/configuration/index.adoc](../loom/doc/src/main/docs/loom/configuration/index.adoc) |
| Features | [loom/features/index.adoc](../loom/doc/src/main/docs/loom/features/index.adoc) |
| Cortex overview | [cortex/_index.adoc](../loom/doc/src/main/docs/cortex/_index.adoc) |
| Cortex pipeline nodes | [cortex/nodes/index.adoc](../loom/doc/src/main/docs/cortex/nodes/index.adoc) |
| Cortex configuration | [cortex/configuration/index.adoc](../loom/doc/src/main/docs/cortex/configuration/index.adoc) |
| Generated OpenAPI spec | [src/main/generated/openapi.json](../loom/doc/src/main/docs/../../loom/doc/src/main/generated/openapi.json) |

The developer bootstrap notes live in
[loom/DEVELOPMENT.md](../loom/DEVELOPMENT.md) and
[loom/db/README.md](../loom/db/README.md).

---

## 3. Loom Backend Layout

Reactor modules under [loom/](../loom/):

```
common/        # shared utilities and constants
db/            # persistence layer (see §4)
services/      # service implementations (REST, gRPC, GraphQL, auth, …)
core/          # server core – wiring, lifecycle, main Vert.x verticle
fixture/       # DB fixture / PoolSetupRunner
cli/           # LoomCLI main class (loom/cli/src/main/java/io/metaloom/loom/cli/LoomCLI.java)
containers/    # Dockerfiles + build-containers.sh
doc/           # AsciiDoc documentation source
```

The service sub-modules ([loom/services/pom.xml](../loom/services/pom.xml)):

```
api  auth  elasticsearch  graphql  monitoring  logger  plugins
rest grpc image video fs tika lucene qdrant eventbus webhook mcp
```

---

## 4. Database Layer ([loom/db](../loom/db/))

DB modules ([loom/db/pom.xml](../loom/db/pom.xml)):

```
api        # DAO/model interfaces – io.metaloom.loom.db.model.*
api-test   # shared abstract test cases per DAO
flyway     # SQL migrations + FlywayHelper (schema management)
memory     # in-memory DAO impl (mostly for tests / offline)
jooq-gen   # jOOQ code-generation project (produces sources into jooq/src/jooq/java)
jooq       # jOOQ-based DAO implementation (production impl)
```

### DAO Model (API)

- Interfaces and DTOs: `io.metaloom.loom.db.model.<domain>`
  (asset, group, role, blacklist, chat, reaction, attachment, perm,
  annotation, webhook, detection, person, …). See
  [loom/db/api/src/main/java/io/metaloom/loom/db/model](../loom/db/api/src/main/java/io/metaloom/loom/db/model).
- Every domain has a `*Dao` + `*Impl` pair.

### jOOQ Implementation

- Generated tables/records/routines/enums live in
  `loom/db/jooq/src/jooq/java/io/metaloom/loom/db/jooq/{tables,routines,enums}`
  (auto-generated — do **not** hand-edit).
- Hand-written DAO impls: `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/<domain>/`
- Regeneration scripts: [loom/db/jooq/generate.sh](../loom/db/jooq/generate.sh),
  [loom/db/jooq/migrate.sh](../loom/db/jooq/migrate.sh).
- Dagger wiring: `io.metaloom.loom.db.jooq.dagger.*`.
- Custom converters: `io.metaloom.loom.db.jooq.converter.*`.

### Flyway / Changelog Mechanism

- Migrations: [loom/db/flyway/src/main/resources/db/migration](../loom/db/flyway/src/main/resources/db/migration)
  (`V1__db_setup.sql`, `V2.1__add_acl.sql` … `V2.28__add_chat.sql`).
- Add a new migration by dropping a new `V<major>.<n>__<name>.sql` file
  in that directory; keep numbers monotonic within the branch.
- Runner: `io.metaloom.loom.db.flyway.FlywayHelper` and
  `FlywayLocalRunner` (for ad-hoc local runs).
- Postgres is the target DB. Local scripts:
  [loom/db/startPostgres.sh](../loom/db/startPostgres.sh),
  [start-postgres.sh](../start-postgres.sh).

### Database Setup for Development

Tests do **not** boot Flyway on demand — they lease pre-populated
databases from a `testdatabase-provider` service. See §7 below.

---

## 5. Authentication

Location: [loom/services/auth](../loom/services/auth/) with sub-modules:

- `auth-common`  — shared abstractions: `LoomAuthenticationHandler`,
  `LoomAuthorizationProvider`, `LoomUser`, `PermissionCache`,
  `KeyStoreHelper`.
- `auth-jwt`     — the production impl (JWT bearer tokens via Vert.x):
  `AuthenticationServiceImpl`, `LoomJWTAuthHandlerImpl`, `AuthModule`
  (Dagger).
- `auth-keycloak`, `auth-okta`, `auth-auth0` — external OAuth2
  providers (currently minimal).

Behaviour (see [authentication doc](../loom/doc/src/main/docs/loom/authentication/index.adoc)):

- Tokens are HMAC-signed JWTs stored in a JCEKS keystore
  (`keystore.jceks` in the working directory; password via
  `auth.keystorePassword` or `LOOM_INITIAL_PASSWORD`).
- Default token TTL: 3600 s (`LOOM_TOKEN_EXPIRATION_TIME`).
- Login endpoint: `POST /api/v1/auth/login` implemented by
  `LoginEndpoint` in [loom/services/rest](../loom/services/rest/).
- Long-lived API keys via `/api/v1/tokens` (`TokenEndpoint`).
- OAuth2 exchange: `/api/v1/auth/oauth2` (`OAuth2Endpoint`).
- Tokens can also arrive as the `__Host-loom_token` cookie.

---

## 6. REST API

Module: [loom/services/rest](../loom/services/rest/) — package root
`io.metaloom.loom.rest`.

Layout:

```
endpoint/impl/     # one *Endpoint class per resource
                   #   Asset, AssetBinary, AssetComponent, AssetPool,
                   #   Annotation, Attachment, Blacklist, Chat, Cluster,
                   #   Collection, Comment, Embedding, GraphQL, Group,
                   #   Library, Login, NodeDescriptor, OAuth2, Person,
                   #   Pipeline, PipelineEvent, Processor, Reaction,
                   #   RESTInfo, Role, Space, Tag, Task, Token, User
builder/impl/      # request/response builders
service/impl/      # service-layer wiring the endpoints to DAOs
dagger/            # Dagger modules composing the REST verticle
parameter/         # typed query/path parameter helpers
openapi/           # OpenAPI generation
model/             # (thin) local model helpers
```

Payload models live in a **shared** module:
[loom-shared/rest-model](../loom-shared/rest-model/) — package
`io.metaloom.loom.rest.model.*`. These are the DTOs shipped over the
wire (request/response classes referenced from both the server and the
Java client).

Endpoints are versioned under `/api/v1/<resource>` and follow a
predictable CRUD shape (see the [REST API doc](../loom/doc/src/main/docs/loom/rest-api/index.adoc)).
Every mutating call (except `POST /auth/login`) requires a JWT bearer.

### REST Client (Java)

- Module: [loom-client/rest](../loom-client/rest/) — package
  `io.metaloom.loom.client.http`.
- Entry point: `LoomHttpClient.builder().hostname(...).port(...).build()`.
- Every call returns a `LoomClientRequest<T>` with `.sync()` and
  `.rx()` execution.
- After `login(...).sync()` store the token via `client.setToken(...)`
  — it is auto-attached as a `Bearer` header.
- Sibling clients: `loom-client/grpc`, `loom-client/common`, and a
  reporting helper in `loom-client/report`.

---

## 7. Testing

### Layers

| Layer | Module | Purpose |
| --- | --- | --- |
| Unit / DAO tests | inside each module's `src/test` | Fast, isolated |
| Endpoint / service tests | e.g. `services/rest/src/test` | Full Vert.x + leased DB |
| Integration tests | [integration-test/](../integration-test/) | Cross-module, uses `loom-test-env` |
| End-to-end tests | [e2e-test/](../e2e-test/) | Boots the packaged container against Postgres |
| UI E2E tests | [loom-ui/e2e/](../loom-ui/e2e/) | Playwright specs (`*.spec.ts`) |

Convenience runners:
- Integration: [it.sh](../it.sh) → runs `PoolSetupRunner` then
  `mvn verify -pl integration-test`.
- E2E:         [e2e.sh](../e2e.sh) → builds the demo container, starts
  Postgres + demo, then `mvn test -Dloom.external=true` in
  `e2e-test`.

### Test Database Pool

Almost every DAO/endpoint test **leases a pre-populated Postgres
database** from an external
[`testdatabase-provider`](https://github.com/metaloom/testdatabase-provider)
service. Setup steps live in [loom/DEVELOPMENT.md](../loom/DEVELOPMENT.md):

1. Start the provider container-compose stack (`test-database/`).
2. Run `io.metaloom.loom.test.PoolSetupRunner` (from `loom/fixture`)
   to build the initial template DB.
3. Tests obtain a fresh DB per class via
   `io.metaloom.loom.test.LoomProviderExtension` /
   `TestDBPoolManager` in [loom-test-env](../loom-test-env/).
4. Sanity check: run `UserEndpointTest`.

Shared test scaffolding:
- [loom-test-env/](../loom-test-env/) — JUnit 5 extensions, container
  wrappers, sample data, `LocalTestData`.
- [loom/fixture/](../loom/fixture/) — `AbstractFixtureProvider`,
  `TestFixtureProvider`, `TestDBPoolManager`, `PoolSetupRunner`.
- [loom/db/api-test/](../loom/db/api-test/) — DAO test contracts each
  backend impl (jooq, memory, …) must pass.

### Test Setup — Custom AssertJ

Do **not** roll your own equality checks — use the domain-specific
`AbstractAssert` subclasses provided by the project:

- REST payload models:
  [loom-shared/rest-model-test/src/main/java/io/metaloom/loom/rest/model/assertj](../loom-shared/rest-model-test/src/main/java/io/metaloom/loom/rest/model/assertj/)
  — `AssetModelAssert`, `UserModelAssert`, `GroupModelAssert`,
  `RoleModelAssert`, `TagModelAssert`, `ListResponseModelAssert`,
  `AbstractModelAssert`, etc. Usage: `assertThat(response).hasUuid(...).hasName(...)`.
- REST client responses:
  [`LoomHttpClientAssert`](../loom-client/rest/src/test/java/io/metaloom/loom/client/http/test/LoomHttpClientAssert.java).
- Cortex pipeline:
  [`PipelineResultAssert`](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/assertj/PipelineResultAssert.java),
  [`PipelineNodeResultAssert`](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/assertj/PipelineNodeResultAssert.java).
- Cortex media / nodes:
  [`NodeResultAssert`](../cortex/core-media/src/test/java/io/metaloom/cortex/media/test/assertj/NodeResultAssert.java),
  [`AbstractProcessableMediaAssert`](../cortex/core-media/src/test/java/io/metaloom/cortex/media/test/assertj/AbstractProcessableMediaAssert.java),
  [`FaceAssert`](../cortex/nodes/facedetect/core/src/test/java/io/metaloom/loom/cortex/node/facedetect/assertj/FaceAssert.java).

When adding a new REST model, add the matching `*ModelAssert` in
`loom-shared/rest-model-test`.

### Integration Tests

- [integration-test/src/test/java/io/metaloom/loom/test/integration](../integration-test/src/test/java/io/metaloom/loom/test/integration/):
  `AbstractIntegrationTest`, `LoomExtensionHelper`, `BasicIntegrationTest`.
- Boots the Loom stack in-process and drives it via `LoomHttpClient`.

### End-to-End Tests (Java)

- [e2e-test/src/test/java/io/metaloom/loom/studio/test](../e2e-test/src/test/java/io/metaloom/loom/studio/test/):
  `E2ETest`. Runs against an *external* container
  (`-Dloom.external=true`).

### UI E2E Tests

- [loom-ui/e2e](../loom-ui/e2e/) — Playwright (`playwright.config.ts`).
  Backend variants have the `-backend` suffix (e.g.
  `assets-backend.spec.ts`). Run: `npm run test:e2e` inside
  `loom-ui/`.

---

## 8. UI

- Framework: React 18 + Vite + TypeScript + MUI v5 (see
  [loom-ui/package.json](../loom-ui/package.json)).
- Additional deps of note: `reactflow` (pipeline graph editor),
  `recharts` (dashboards), `i18next` (translations), `react-router-dom` v6.
- Source layout ([loom-ui/src](../loom-ui/src/)):
  `Admin, Asset, Content, Dashboard, Login, Pipeline, User, Welcome,
  components, features, context, api, layout, theme, i18n, mock, img,
  types, main.tsx`.
- Dev: `npm run dev`; Prod build: `npm run build`.
- Desktop wrapper: [loom-app/](../loom-app/) — Electron Forge project
  that packages the same UI (see `loom-app/main.js`, `forge.config.js`).

---

## 9. Cortex

Reactor children ([cortex/pom.xml](../cortex/pom.xml)):

```
api  common  fs  core-media  nodes  processor  core  cli  container
pipeline-api  pipeline-common  pipeline-core
```

### Cortex Actions & Nodes

Two parallel trees exist:

- [cortex/actions/](../cortex/actions/): higher-level CLI action
  handlers (`captioning, consistency, dedup, facedetect, fingerprint,
  hash, llm, loom, ocr, scene-detection, thumbnail, tika, whisper`).
  These map to CLI `--actions` flags.
- [cortex/nodes/](../cortex/nodes/): the corresponding
  `AbstractMediaNode`/`AbstractPipelineNode` implementations that run
  inside a pipeline (same list + `quality`, `filter-api`, `common-api`,
  `source-api`).

Each node follows the lifecycle `enabled? → exists? → processable? →
compute()` (see [cortex nodes doc](../loom/doc/src/main/docs/cortex/nodes/index.adoc)).
The `loom` node forwards results back to the Loom REST API — this is
what makes Cortex "online mode" work.

### Pipeline Engine

`pipeline-api` / `pipeline-core` / `pipeline-common` implement a DAG
executor built on `CompletableFuture` (no Reactor / RxJava). Detailed
reference notes are in `/memories/repo/cortex-pipeline-detailed.md`.

Key entry points:
- `CortexCLIMain` in [cortex/cli/src/main/java/io/metaloom/cortex/cli/CortexCLIMain.java](../cortex/cli/src/main/java/io/metaloom/cortex/cli/CortexCLIMain.java)
- Dagger component: `io.metaloom.cortex.cli.dagger.CortexComponent`
  (generated `DaggerCortexComponent` under `target/generated-sources`).

### Media Decorator Pattern

`MediaType<T>.wrap(LoomMedia, MetaStorage)` produces typed views.
`LoomMedia` is a pure file handle; typed getters/setters live on the
decorator interfaces (`HashMedia`, `FacedetectMedia`, …). See the
`/memories/repo/metaloom-cortex-architecture.md` note.

---

## 10. Cheat Sheet — Where do I find …?

| Need | Look here |
| --- | --- |
| REST endpoint impls | [loom/services/rest/…/endpoint/impl](../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/) |
| REST request/response DTOs | [loom-shared/rest-model](../loom-shared/rest-model/) |
| Java REST client | [loom-client/rest](../loom-client/rest/) (`LoomHttpClient`) |
| Custom assertj asserts | `loom-shared/rest-model-test/.../assertj/` and `cortex/**/test/**/assertj/` |
| JWT / login / OAuth2 | [loom/services/auth](../loom/services/auth/) |
| DAO interfaces | [loom/db/api](../loom/db/api/) |
| jOOQ DAO impls | [loom/db/jooq](../loom/db/jooq/) |
| Generated jOOQ tables | `loom/db/jooq/src/jooq/java/…` |
| SQL migrations | [loom/db/flyway/src/main/resources/db/migration](../loom/db/flyway/src/main/resources/db/migration/) |
| Test DB pool setup | [loom-test-env](../loom-test-env/), [loom/fixture](../loom/fixture/), [loom/DEVELOPMENT.md](../loom/DEVELOPMENT.md) |
| Cortex pipeline nodes | [cortex/nodes](../cortex/nodes/) |
| Cortex CLI actions | [cortex/actions](../cortex/actions/) |
| Documentation source | [loom/doc/src/main/docs](../loom/doc/src/main/docs/) |
| Container builds | [loom/containers](../loom/containers/) + [cortex/container](../cortex/container/) |

---

## 11. Conventions & Gotchas

- **Java package root** for backend code: `io.metaloom.loom.*`; for
  processing code: `io.metaloom.cortex.*`. Do not mix.
- **Dagger**: after touching generic types on nodes/services, do a
  clean build — stale generated code causes confusing compile errors.
- **jOOQ generated sources** live *inside* `src/jooq/java`; never edit
  by hand — rerun `loom/db/jooq/generate.sh` after schema changes.
- **New DB fields** need: (a) a Flyway `V*.sql`, (b) jOOQ regeneration,
  (c) DAO API changes in `loom/db/api`, (d) impl updates in
  `loom/db/jooq` and `loom/db/memory`, (e) contract tests in
  `loom/db/api-test`.
- **REST changes** must ship: DTOs in `loom-shared/rest-model`, an
  `*Endpoint` impl in `loom/services/rest`, a matching `*Assert` in
  `loom-shared/rest-model-test`, and client methods in
  `loom-client/rest`.
- **Tokens / secrets**: never commit `keystore.jceks`; use
  `LOOM_INITIAL_PASSWORD` locally.
- **Tests**: expect a running `testdatabase-provider` on port `7543`.
  If nothing works, that is almost always the first thing to check.
- **UI dev server** talks to a running backend — use
  [start-server.sh](../start-server.sh) (or the demo container from
  [e2e.sh](../e2e.sh)) alongside `npm run dev`.

