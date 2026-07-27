# MetaLoom // Loom Backend Service

> This document describes the Loom backend service at a high level. It covers
> aspects that are **not** detailed in the other spec files under `spec/loom/`.
> Each subsystem has its own dedicated specification:
>
> - [RESTAPI.md](RESTAPI.md) - REST API endpoints, authentication, clients, OpenAPI
> - [WEBSOCKET.md](WEBSOCKET.md) - Processor and pipeline-events WebSocket protocols
> - [MCP.md](MCP.md) - Model Context Protocol server (AI tool integration)
> - [PERSISTENCE.md](PERSISTENCE.md) - Database layer, jOOQ DAOs, Flyway migrations
> - [PERMISSION.md](PERMISSION.md) - RBAC permission system, roles, groups, enforcement
> - [PIPELINE_CONTEXT.md](PIPELINE_CONTEXT.md) - Cortex pipeline execution engine
> - [EVENTBUS.md](EVENTBUS.md) - Event bus systems (pipeline events, Vert.x EventBus, WebSocket fan-out)
> - [SERVER.md](SERVER.md) - (placeholder) Server startup and lifecycle
> - [CONFIGURATIOON.md](CONFIGURATIOON.md) - (placeholder) Configuration system
> - [GRAPHQL.md](GRAPHQL.md) - (placeholder) GraphQL API
> - [GRPC.md](GRPC.md) - (placeholder) gRPC API
>
> This document focuses on: the overall architecture, module layout, startup
> lifecycle, Loom-Cortex relationship, configuration, and cross-cutting
> concerns that tie the subsystems together.

---

## 1. Overview

Loom is the **backend service** of MetaLoom, a Digital Asset Management (DAM)
system. It is responsible for:

- **Managing data** - users, groups, roles, assets, face detections, object
  detections, tags, collections, comments, annotations, tasks,
  embeddings, transcripts, chat, clusters, blacklists, and more.
- **Persisting pipeline results** - data collected by Cortex processing
  pipelines is stored in the Loom database.
- **Managing processing workers** - Cortex worker nodes connect to Loom via
  a WebSocket API, register their capabilities, and receive source/node tasks.
- **Exposing APIs** - REST, gRPC (planned), GraphQL (planned), and MCP for
  AI agent integration.

### 1.1 What is Cortex?

Cortex is the **processing layer** of MetaLoom. It runs as separate worker
processes ("cortex" nodes) that:

1. Connect to Loom via the processor WebSocket (`/api/v1/processors/ws`)
2. Register their capabilities (IO, CPU, GPU)
3. Receive source/node tasks (`SOURCE_TASK`, `NODE_TASK`, `SEGMENT_TASK`)
4. Execute pipeline node chains against media assets
5. Report pipeline tracking events back to Loom (which fans them out to UI
   clients via `/api/v1/pipelines/events/ws`)
6. Sync results back to Loom via the `LoomBulkSyncCollector` or direct REST
   API calls

Pipelines are **collections of node chains** (DAGs) that process asset data.
Each node can extract metadata (hashes, MIME types, thumbnails, transcripts,
face detections, object detections, fingerprints, etc.) from assets. The
pipeline definitions are stored in Loom's database and loaded by Cortex at
startup. See [PIPELINE_CONTEXT.md](PIPELINE_CONTEXT.md) for the full pipeline
execution engine specification.

### 1.2 High-Level Architecture

```
                    +---------------------+
                    |     Loom Server     |
                    |   (Vert.x + Dagger) |
                    +---------------------+
                    /  |  |  |  |  |  \
              REST  / gRPC| GraphQL|MCP  \  WebSocket
                  /   |  |  |  |       Processor +
                 /    |  |  |  |       Pipeline Events
                /     |  |  |  |
               v      v  v  v  v
           +------+  +--+ +--+ +--+    +-----------+
           |Client|  |  | |  | |AI|    |  Cortex   |
           |(UI)  |  |  | |  | |Agent| |  Workers  |
           +------+  +--+ +--+ +--+    +-----------+
                                         |
                                    Pipeline execution
                                    (hash, facedetect, whisper, ...)
                                         |
                                    Results synced to Loom
                                         v
                                    +----------+
                                    |PostgreSQL|
                                    +----------+
```

---

## 2. Module Layout

The Loom project (`loom/`) is a multi-module Maven project:

| Module | Purpose |
|---|---|
| `loom/common` | Shared utilities, Vert.x setup, Dagger modules, `LoomOptionsLoader` |
| `loom/db` | Database layer (parent module for persistence) |
| `loom/db/api` | DAO interfaces, model interfaces, `Element`/`CRUDDao`/`DaoCollection` abstractions |
| `loom/db/jooq` | jOOQ-based DAO implementations (see [PERSISTENCE.md](PERSISTENCE.md)) |
| `loom/db/jooq-gen` | jOOQ code generation strategy (prefixes table classes with `Jooq`) |
| `loom/db/flyway` | Flyway SQL migration scripts |
| `loom/db/memory` | In-memory DAO implementation for fast tests |
| `loom/services` | Service layer (parent module) |
| `loom/services/rest` | REST API service, WebSocket endpoints, pipeline event broadcaster |
| `loom/services/grpc` | gRPC service (planned, currently commented out in parent pom) |
| `loom/services/graphql` | GraphQL service (implemented but not registered in `EndpointModule`) |
| `loom/services/mcp` | MCP (Model Context Protocol) server for AI agent integration |
| `loom/services/auth-jwt` | JWT-based authentication provider |
| `loom/services/auth-keycloak` | Keycloak-based authentication provider |
| `loom/services/auth-common` | Shared authentication utilities |
| `loom/services/auth-auth0` | Auth0 authentication provider |
| `loom/services/auth-okta` | Okta authentication provider |
| `loom/services/image` | Image processing service |
| `loom/services/video` | Video processing service |
| `loom/services/elasticsearch` | Elasticsearch integration service |
| `loom/services/lucene` | Lucene search integration service |
| `loom/services/qdrant` | Qdrant vector database integration service |
| `loom/services/tika` | Apache Tika metadata extraction service |
| `loom/services/monitoring` | Monitoring/metrics service |
| `loom/services/plugins` | Plugin system |
| `loom/services/fs` | Filesystem service |
| `loom/services/eventbus` | Event bus service (placeholder module, no source files) |
| `loom/core` | Bootstrap, server lifecycle, `LoomImpl`, `BootstrapInitializer` |
| `loom/fixture` | Test fixtures and test environment providers |
| `loom/containers` | Container definitions (server, demo) |
| `loom/doc` | Documentation module |

### 2.1 Shared Modules (outside `loom/`)

| Module | Purpose |
|---|---|
| `loom-shared/api` | Core interfaces: `Loom`, `LoomOptions`, `ServerOptions`, `DatabaseOptions`, `AuthenticationOptions` |
| `loom-shared/rest-model` | REST DTOs, request/response models, validation models |
| `loom-shared/proto` | Protobuf/gRPC model definitions |
| `loom-client/rest` | Java HTTP client (`LoomHttpClient`) |
| `loom-client/grpc` | Java gRPC client (planned) |
| `loom-client/common` | Shared client interfaces (`ClientMethods`) |

---

## 3. Server Lifecycle

### 3.1 Startup Sequence

The server is started via `LoomImpl.run()` which builds the Dagger
`LoomCoreComponent` and calls `BootstrapInitializer.init()`. The startup
sequence is:

1. **Database migration** - `Flyway.migrate()` runs pending SQL migrations
   (see [PERSISTENCE.md](PERSISTENCE.md) for migration details).
2. **Database initialization** - `DatabaseInitializer.init()` creates
   initial data (admin user, default roles, permissions).
3. **Demo data initialization** - `DemoDatabaseInitializer.init()` populates
   demo data (non-fatal on failure, logs a warning and continues).
4. **REST service start** - `RESTService.start()` sets up the Vert.x router,
   registers all endpoints, configures CORS, body handler, and auth handler.
5. **UI service start** - `UIService.start()` mounts the static UI.
6. **HTTP server listen** - `httpServer.listen()` binds to the configured
   port (default `8092` for REST).
7. **MCP service start** - `MCPService.start()` starts the MCP HTTP server
   on a separate port (default `4041`).

### 3.2 Shutdown Sequence

`BootstrapInitializer.deinit()`:
1. `mcpService.stop()` - closes MCP HTTP server and SSE sessions.
2. `restService.stop()` - closes the REST HTTP server.

### 3.3 Key Classes

| Class | Package | Purpose |
|---|---|---|
| `LoomImpl` | `io.metaloom.loom.core` | Entry point; builds Dagger component, runs bootstrap |
| `BootstrapInitializer` | `io.metaloom.loom.core.boot` | Orchestrates startup/shutdown sequence |
| `DatabaseInitializer` | `io.metaloom.loom.core.boot` | Creates initial admin user, roles, permissions |
| `DemoDatabaseInitializer` | `io.metaloom.loom.core.boot` | Populates demo data for development |
| `LoomCoreComponent` | `io.metaloom.loom.core.dagger` | Dagger component that wires all services |
| `RESTService` | `io.metaloom.loom.rest` | REST API service (router, endpoints, auth) |
| `UIService` | `io.metaloom.loom.rest` | Static UI serving service |
| `MCPService` | `io.metaloom.loom.mcp` | MCP server (see [MCP.md](MCP.md)) |

### 3.4 Dagger DI

Loom uses **Dagger** for dependency injection. Key modules:

| Module | Provides |
|---|---|
| `LoomCoreComponent` | Top-level component; wires `BootstrapInitializer`, `RESTService`, `MCPService`, `Flyway`, `HttpServer` |
| `VertxModule` | Vert.x instance, `EventBus` (RxJava 3 variant) |
| `LoomModule` | `DatabaseOptions` from `LoomOptions` |
| `AuthBindModule` | Authentication provider bindings |
| `EndpointModule` | Set of `RESTEndpoint` instances (collected via `@RESTEndpoints`) |
| `MCPModule` | MCP router (named `"mcpRouter"`) |
| `MCPToolModule` | Set of `MCPTool` instances (via `@ElementsIntoSet`) |

Each REST request creates a new `RestComponent` DI scope via
`restComponentProvider.get().context(rc).build()`.

---

## 4. Configuration

### 4.1 LoomOptions

Configuration is loaded via `LoomOptionsLoader.createOrLoadOptions()` which
reads from:
1. Environment variables (override existing config values)
2. A config file (searched in standard locations)
3. Defaults (hardcoded in the option classes)

The root configuration object is `LoomOptions`:

| Section | Class | Key Settings |
|---|---|---|
| Database | `DatabaseOptions` | host, port, username, password, databaseName, pool sizes |
| Server | `ServerOptions` | restPort, grpcPort, monitoringPort, bindAddress |
| Auth | `AuthenticationOptions` | keystorePassword, initialPassword, tokenExpirationTime, oauth2 |

### 4.2 Environment Variables

All options support environment variable overrides via the
`@EnvironmentVariable` annotation and `overrideWithEnv()` method:

| Variable | Description | Default |
|---|---|---|
| `LOOM_DB_HOST` | Database host | `127.0.0.1` |
| `LOOM_DB_PORT` | Database port | `5432` |
| `LOOM_DB_USERNAME` | Database username | `postgres` |
| `LOOM_DB_PASSWORD` | Database password | `finger` |
| `LOOM_DB_NAME` | Database name | `loom` |
| `LOOM_DB_MIN_POOL_SIZE` | DB connection pool min | `5` |
| `LOOM_DB_MAX_POOL_SIZE` | DB connection pool max | `20` |
| `LOOM_SERVER_REST_PORT` | REST server port | `8092` |
| `LOOM_SERVER_GRPC_PORT` | gRPC server port | `8091` |
| `LOOM_SERVER_MON_PORT` | Monitoring port | `8989` |
| `LOOM_SERVER_GRPC_BIND_ADDRESS` | Server bind address | `0.0.0.0` |
| `LOOM_INITIAL_PASSWORD` | Initial admin password | (none) |
| `LOOM_TOKEN_EXPIRATION_TIME` | JWT token expiration (seconds) | `3600` |
| `LOOM_WS_STRICT_AUTH` | Strict WebSocket auth | `false` |

### 4.3 Database Defaults

| Setting | Default |
|---|---|
| Host | `127.0.0.1` |
| Port | `5432` |
| Username | `postgres` |
| Password | `finger` |
| Database | `loom` |
| Min pool size | `5` |
| Max pool size | `20` |
| JDBC URL | `jdbc:postgresql://{host}:{port}/{database}` |

### 4.4 Server Defaults

| Setting | Default |
|---|---|
| REST port | `8092` |
| gRPC port | `8091` |
| Monitoring port | `8989` |
| MCP port | `4041` (hardcoded in `MCPService`) |
| Bind address | `0.0.0.0` |

---

## 5. Authentication and Authorization

> Detailed auth endpoint documentation is in [RESTAPI.md](RESTAPI.md) section 2.
> This section covers the auth architecture and providers.

### 5.1 Auth Providers

Loom supports pluggable authentication providers via the `loom/services/auth-*`
modules:

| Module | Provider | Description |
|---|---|---|
| `auth-jwt` | JWT | Username/password login, JWT cookie, API tokens |
| `auth-keycloak` | Keycloak | OAuth2/OIDC via Keycloak IdP |
| `auth-auth0` | Auth0 | OAuth2 via Auth0 |
| `auth-okta` | Okta | OAuth2 via Okta |
| `auth-common` | Shared | Common auth utilities, `AuthenticationService` |

### 5.2 JWT Authentication Flow

1. Client sends `POST /api/v1/login` with `AuthLoginRequest` (username + password).
2. Server validates credentials, generates a JWT token.
3. Token is set as an `HttpOnly`, `Secure`, `SameSite=STRICT` cookie
   named `__Host-loom_token`.
4. Subsequent requests carry the cookie; the `LoomAuthenticationHandler`
   validates it and sets up `PermissionBasedAuthorization`.
5. WebSocket connections pass the token via `?token=<jwt>` query parameter
   (see [WEBSOCKET.md](WEBSOCKET.md)).

### 5.3 OAuth2 BFF Pattern

Loom implements the Backend-For-Frontend (BFF) pattern per
draft-ietf-oauth-browser-based-apps. See [RESTAPI.md](RESTAPI.md) section 2.3
for endpoint details.

### 5.4 Permission System

- Permissions are checked via `lrc.requirePerm(Permission...)` using Vert.x's
  `PermissionBasedAuthorization`.
- Each CRUD operation maps to a specific permission (e.g. `CREATE_USER`,
  `READ_USER`, `UPDATE_USER`, `DELETE_USER`).
- Roles group permissions; groups hold roles; users are assigned to groups.
  There is no direct user-to-role binding.
- Permissions are global per type - the `resource` column is stored but not
  enforced.
- The `DatabaseInitializer` creates default roles and permissions at startup.

See [PERMISSION.md](PERMISSION.md) for the full specification.

### 5.5 Entity Management

Loom manages the following entities via the REST API (all with CRUD
operations, permissions, and DAO persistence):

| Entity | REST Path | DAO |
|---|---|---|
| Users | `/api/v1/users` | `UserDao` |
| Roles | `/api/v1/roles` | `RoleDao` |
| Groups | `/api/v1/groups` | `GroupDao` |
| Persons | `/api/v1/persons` | `PersonDao` |
| Spaces | `/api/v1/spaces` | `SpaceDao` |
| Libraries | `/api/v1/libraries` | `LibraryDao` |
| Collections | `/api/v1/collections` | `CollectionDao` |
| Tags | `/api/v1/tags` | `TagDao` |
| Tasks | `/api/v1/tasks` | `TaskDao` |
| Comments | `/api/v1/comments` | `CommentDao` |
| Annotations | `/api/v1/annotations` | `AnnotationDao` |
| Reactions | `/api/v1/reactions` | `ReactionDao` |
| Blacklists | `/api/v1/blacklists` | `BlacklistDao` |
| Chats | `/api/v1/chats` | `ChatDao` |
| Clusters | `/api/v1/clusters` | `ClusterDao` |
| Embeddings | `/api/v1/embeddings` | `EmbeddingDao` |
| Pipelines | `/api/v1/pipelines` | `PipelineDao` |
| Assets | `/api/v1/assets` | `AssetDao` |
| Asset Pools | `/api/v1/pools` | `AssetPoolDao` |
| Binaries | `/api/v1/binaries` | `BinaryDao` |
| Attachments | `/api/v1/attachments` | `AttachmentDao` |
| Detections | `/api/v1/assets/:uuid/detections` | `DetectionDao` |
| Transcripts | `/api/v1/assets/:uuid/transcripts` | `TranscriptDao` |
| Processors | `/api/v1/processors` | (in-memory registry, not persisted) |

---

## 6. Loom-Cortex Relationship

### 6.1 How Cortex Connects to Loom

Cortex worker nodes connect to Loom via the **processor WebSocket** at
`/api/v1/processors/ws`. The connection lifecycle is:

1. Cortex starts up and loads pipeline definitions from Loom via REST
   (`GET /api/v1/pipelines`).
2. Cortex opens a WebSocket to `/api/v1/processors/ws?token=<jwt>`.
3. Cortex sends a `REGISTER` message with its node ID, name, and capabilities.
4. Loom responds with `REGISTERED` and assigns a UUID.
5. Cortex sends periodic `HEARTBEAT` (10s) and `STATUS_UPDATE` (20s) messages.
6. Cortex sends `PIPELINE_EVENT` messages as pipeline nodes execute.
7. Loom starts a run by dispatching a `SOURCE_TASK` and then individual
   `NODE_TASK` messages (driven by the loom-side `PipelineRunEngine`).
8. Cortex responds with `SOURCE_ITEMS` / `NODE_TASK_RESULT` and finally
   `PIPELINE_RUN_COMPLETED` when the run finishes.

See [WEBSOCKET.md](WEBSOCKET.md) for the full protocol specification.

### 6.2 Pipeline Definition Flow

```
Loom DB (pipeline table)  --GET /api/v1/pipelines-->  Cortex
                                                         |
                                                    LoomPipelineLoader
                                                    (deserializes JSON
                                                     into Pipeline objects)
                                                         |
                                                    PipelineManager
                                                    (registers pipelines
                                                     by priority)
                                                         |
                                                    ReactivePipelineExecutor
                                                    (executes node DAGs)
                                                         |
                                                    LoomBulkSyncCollector
                                                    (batches results for
                                                     upload to Loom)
                                                         |
                                                    LoomNode / REST API
                                                    (persists results back
                                                     to Loom DB)
```

### 6.3 Data Persistence Flow

Cortex nodes process media assets and produce results (hashes, thumbnails,
face detections, transcripts, etc.). These results flow back to Loom via:

1. **Bulk sync** - The `LoomBulkSyncCollector` batches results and uploads
   them via the REST API (see [PIPELINE_CONTEXT.md](PIPELINE_CONTEXT.md)
   section 3.11).
2. **Direct REST calls** - Some nodes (e.g. `LoomNode`) call the REST API
   directly to persist results.
3. **Pipeline events** - Tracking events (not data) are sent via the
   processor WebSocket and fanned out to UI clients via the pipeline events
   WebSocket (see [EVENTBUS.md](EVENTBUS.md)).

### 6.4 Cortex Processing Nodes

Cortex provides these processing node types (each in `cortex/nodes/`):

| Node | Purpose | Output Keys |
|---|---|---|
| `hash` (SHA-512, MD5) | File hashing | `sha512`, `md5` |
| `thumbnail` | Thumbnail generation | `image` (thumbnail bytes) |
| `fingerprint` | Video/audio fingerprinting | `embedding` (fingerprint vector) |
| `facedetect` | Face detection | `detection` (face bounding boxes) |
| `ocr` | Optical character recognition | `text` (extracted text) |
| `whisper` | Speech-to-text transcription | `transcript` |
| `tika` | Metadata extraction (Apache Tika) | metadata fields |
| `llm` | LLM-based captioning/tagging | `description`, `tags`, `answer` |
| `scene` | Scene detection (video) | scene boundaries |
| `dedup` | Duplicate detection | `filter_passed` |
| `quality` | Quality assessment | quality metrics |
| `captioning` | Image captioning | `description` |
| `consistency` | Consistency checks | consistency results |
| `loom` | Sync results back to Loom | (writes to Loom DB) |

Each node is wrapped via `CortexNodeAdapter` to participate in the pipeline
DAG. See [PIPELINE_CONTEXT.md](PIPELINE_CONTEXT.md) section 7 for details.

---

## 7. Test Setup

### 7.1 Test Database

All DAO/database and integration tests require a running PostgreSQL instance
with the `loom` database. The test database is provided by the
`testdatabase-provider` project.

**Starting the test database:**

```bash
cd test-database
podman-compose up -d
```

**Setting up the test pool:**

The pool must be initially set up using `io.metaloom.loom.test.PoolSetupRunner`
from the `loom-fixture` project. This creates a named connection pool
(`loom-dev`) that tests use to obtain database connections.

### 7.2 Test Modules

| Module | Purpose |
|---|---|
| `loom/db/api-test` | Shared test infrastructure: `CRUDDaoTestcases`, `DatabaseTest`, `FixtureElementProvider` |
| `loom/fixture` | Test fixtures, `PoolSetupRunner`, test environment providers |
| `loom/containers` | Container definitions for integration tests |

### 7.3 Test Patterns

- **DAO tests** extend `CRUDDaoTestcases` and use `DatabaseTest` which
  provides a `DaoProvider` with access to all DAOs.
- **Integration tests** extend `AbstractIntegrationTest` and boot Loom
  in-process via `LoomCoreTestExtension`.
- **Pipeline tests** extend `AbstractPipelineNodeTest` (see
  [PIPELINE_CONTEXT.md](PIPELINE_CONTEXT.md) section 11).
- **MCP tests** use `MCPDirectToolCallTest` (in-process) and
  `MCPServerToolCallTest` (HTTP JSON-RPC) with a real PostgreSQL database.
- **WebSocket tests** use `PipelineEventEndpointTest` with
  `LoomCoreTestExtension` to boot Loom in-process.

### 7.4 Running Tests

```bash
# From the loom/ directory:
mvn test                    # Run unit tests only
mvn test -Dskip.unit.tests=false  # Run unit tests
mvn verify                  # Run integration tests (requires test DB)
```

The `testdatabase-provider` container must be running for any test that
touches the database. Tests that need a database use `ProviderExtension`
to lease a database from the pool.

---

## 8. Build System

### 8.1 Maven Build

The project uses Maven with a parent POM (`metaloom-parent`) that manages
dependency versions via a BOM (Bill of Materials).

```bash
cd loom
mvn clean install -DskipTests   # Build without tests
mvn clean install               # Build with tests (requires test DB)
```

### 8.2 Key Build Properties

| Property | Default | Description |
|---|---|---|
| `skip.unit.tests` | `false` | Skip unit tests |
| `skip.cluster.tests` | `false` | Skip cluster tests |
| `surefire.forkcount` | `1` | Surefire fork count |
| `jacoco.skip` | `true` | Skip JaCoCo coverage |
| `loom.cortex.version` | `1.0.0-SNAPSHOT` | Cortex version |

### 8.3 jOOQ Code Generation

jOOQ code generation is triggered by the `generate` Maven profile in
`loom-db-jooq/pom.xml`:
1. Starts a PostgreSQL Testcontainer
2. Runs Flyway migrations against it
3. Runs jOOQ codegen against the migrated schema

Generated table classes are prefixed with `Jooq` (e.g. `JooqUser`,
`JooqAsset`) via `LoomJooqStrategy`.

---

## 9. Progress Assessment

The following checkboxes track aspects of the Loom backend that need
documentation, improvement, or are incomplete. AI agents can use this list
to identify work items.

### 9.1 Architecture and Module Structure

- [x] Core module with `BootstrapInitializer` for startup/shutdown lifecycle
- [x] Dagger-based dependency injection (`LoomCoreComponent`)
- [x] Vert.x as the HTTP server framework
- [x] REST API service with all endpoints registered via `EndpointModule`
- [x] MCP service on a separate port (4041)
- [x] UI service for static file serving
- [x] Database initialization with default admin user, roles, permissions
- [x] Demo data initialization for development
- [ ] gRPC service is commented out in parent pom (planned, not active)
- [ ] GraphQL endpoint is implemented but not registered in `EndpointModule`
- [ ] `loom-service-eventbus` module is an empty placeholder (no source files)
- [ ] No health check endpoint (`/api/v1/health` or `/health`)
- [ ] No readiness probe endpoint
- [ ] No metrics endpoint (`/api/v1/metrics` or `/metrics`)

### 9.2 Configuration

- [x] `LoomOptions` with database, server, and auth sections
- [x] Environment variable overrides via `@EnvironmentVariable` annotation
- [x] Config file loading from standard locations
- [x] `LoomOptionsLoader` for loading/creating options
- [ ] MCP port is not configurable via `LoomOptions` (hardcoded to 4041)
- [ ] No configuration validation beyond `LoomOptions.validate()` (which is empty)
- [ ] No dynamic configuration reload (requires restart)
- [ ] No configuration documentation beyond env var annotations

### 9.3 Authentication

- [x] JWT-based authentication with cookie-based session
- [x] OAuth2 BFF pattern with PKCE (Keycloak, Auth0, Okta providers)
- [x] API token management (`/api/v1/tokens`)
- [x] Permission-based authorization per endpoint
- [x] WebSocket authentication via token query parameter
- [ ] No rate limiting on authentication endpoints
- [ ] No account lockout policy on login endpoint
- [ ] No token refresh mechanism
- [ ] No token revocation/invalidation endpoint
- [ ] MCP server has no authentication (see [MCP.md](MCP.md) section 7)

### 9.4 Entity Management

- [x] Full CRUD for all primary entities (users, roles, groups, assets, etc.)
- [x] Sub-resource pattern (tags, reactions, detections, transcripts on assets)
- [x] Bulk operations for assets (bulk create/update)
- [x] SHA-512 hash-based asset lookup
- [x] Pipeline CRUD and execution dispatch
- [x] Processor registration and monitoring
- [ ] No soft-delete support across all entities (only `UserDao` filters `deleted = false`)
- [ ] No audit log for entity changes
- [ ] No bulk delete operations
- [ ] No export/import functionality for entities

### 9.5 Loom-Cortex Integration

- [x] Processor WebSocket protocol with registration, heartbeat, source/node tasks
- [x] Pipeline event broadcasting to UI clients
- [x] Pipeline definition loading from Loom DB to Cortex
- [x] Bulk sync of pipeline results back to Loom
- [x] Run dispatch via `SOURCE_TASK` + `NODE_TASK` driven by `PipelineRunEngine`
- [ ] No automatic reconnection protocol for processors after server restart
- [ ] No heartbeat timeout / idle detection on processor connections
- [ ] No dead-letter mechanism for dropped pipeline events
- [ ] No processor load balancing (only priority-based selection)

### 9.6 Testing Infrastructure

- [x] Test database provider with connection pooling
- [x] `PoolSetupRunner` for initial pool setup
- [x] `CRUDDaoTestcases` for DAO testing
- [x] `AbstractIntegrationTest` for integration tests
- [x] `LoomCoreTestExtension` for in-process Loom boot
- [x] `AbstractPipelineNodeTest` for pipeline node tests
- [x] MCP tests with real PostgreSQL and Ollama LLM integration
- [ ] No WebSocket endpoint integration tests
- [ ] No OAuth2 flow tests
- [ ] No rate limiting tests
- [ ] No end-to-end test covering full Loom-Cortex pipeline (Loom boot -> Cortex connect -> pipeline run -> results persisted)

### 9.7 Documentation Gaps

- [ ] `SERVER.md` is empty (server lifecycle, startup/shutdown details)
- [ ] `CONFIGURATIOON.md` is empty (configuration system details)
- [ ] `GRAPHQL.md` is empty (GraphQL API specification)
- [ ] `GRPC.md` is empty (gRPC API specification)
- [ ] No architecture diagram in the codebase
- [ ] No OpenAPI schema definitions for request/response models
- [ ] No client SDK documentation beyond Java client examples

---

## 10. Additional Specification Aspects Not Yet Covered

The following aspects are not yet covered by any spec file under `spec/loom/`
and should be documented in future iterations:

### 10.1 Server Lifecycle and Startup (`SERVER.md`)

The `SERVER.md` file is currently empty. It should cover:
- Detailed startup sequence (Flyway migration, DB init, service start order)
- Shutdown hooks and graceful shutdown
- HTTP server configuration (Vert.x options, SSL/TLS, body limits)
- Monitoring service (port 8989, what metrics are exposed)
- UI service (static file serving, SPA routing)
- gRPC service (port 8091, currently commented out)
- Cluster mode (Vert.x cluster, Hazelcast, currently disabled)

### 10.2 Configuration System (`CONFIGURATIOON.md`)

The `CONFIGURATOON.md` file (note: filename has a typo) is empty. It should
cover:
- `LoomOptions` structure and all sub-options
- Environment variable overrides and precedence
- Config file locations and formats
- `LoomOptionsLoader` loading logic
- `DatabaseOptions` (pool sizes, JDBC URL construction)
- `ServerOptions` (ports, bind address)
- `AuthenticationOptions` (keystore, token expiration, OAuth2)
- `OAuth2Options` (IdP configuration, PKCE, callback URLs)

### 10.3 GraphQL API (`GRAPHQL.md`)

The `GRAPHQL.md` file is empty. It should cover:
- GraphQL endpoint registration (currently commented out in `EndpointModule`)
- Schema definition and resolvers
- Query/mutation types
- Authentication for GraphQL
- Relationship with the REST API (shared DAOs)

### 10.4 gRPC API (`GRPC.md`)

The `GRPC.md` file is empty. It should cover:
- gRPC service definition (currently commented out in parent pom)
- Proto definitions in `loom-shared/proto`
- gRPC client implementation (`loom-client/grpc`)
- Service methods and streaming
- Authentication via `ClientJWTInterceptor`

### 10.5 Search and Indexing — ✅ now specified

Covered by [../features/search/SEARCH.md](../features/search/SEARCH.md) (lexical search: current
state, the `SearchProvider` SPI, the `search_document` table, REST surface, permissions),
[../features/search/SEARCH_PLAN.md](../features/search/SEARCH_PLAN.md) (phased build order) and
[../features/search/SEMANTIC_SEARCH.md](../features/search/SEMANTIC_SEARCH.md) (embeddings, pgvector,
hybrid ranking).

🔴 **Those are design documents, not descriptions of running code — no search exists.** There is no
search endpoint, no free-text query parameter, and no `LIKE`/`ILIKE` in any DAO.
`loom/services/{elasticsearch,lucene,qdrant}` are **empty placeholder modules**: `pom.xml` only, no
`src/` directory. The design keeps Postgres full-text search first and Elasticsearch/OpenSearch second
behind one SPI, and rejects Lucene (an embedded index is per-replica local state).

### 10.6 Image and Video Processing Services

No spec file covers the `loom/services/image` and `loom/services/video`
modules:
- Image processing capabilities (thumbnail generation, format conversion)
- Video processing capabilities (transcoding, frame extraction)
- How these services integrate with the REST API
- How they relate to Cortex processing nodes

### 10.7 Plugin System

No spec file covers the `loom/services/plugins` module:
- Plugin registration and lifecycle
- Plugin extension points
- How plugins extend Loom's functionality

### 10.8 Tika Metadata Extraction

No spec file covers the `loom/services/tika` module:
- Apache Tika integration for metadata extraction
- Supported file types and metadata fields
- How Tika results are stored and exposed

### 10.9 Filesystem Service

No spec file covers the `loom/services/fs` module:
- Filesystem abstraction for asset storage
- Local filesystem, S3, and other storage backends
- How assets are stored and retrieved

### 10.10 CLI

`loom/cli` was deleted (it was a `System.out.println("TBD")` stub that nothing depended
on). The command-line client now lives in the top-level `cli/` module — see
[../features/cli/CLI_PLAN.md](../features/cli/CLI_PLAN.md).

### 10.11 Containers

No spec file covers the `loom/containers` module:
- Server container definition
- Demo container with pre-populated data
- Container build and deployment

### 10.12 Monitoring

No spec file covers the `loom/services/monitoring` module:
- What metrics are exposed
- Monitoring endpoint configuration
- Integration with the monitoring port (8989)
