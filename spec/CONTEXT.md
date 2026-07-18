# MetaLoom — Project Context for AI Coding Agents

This document serves as the **entry point** for AI coding agents working on the MetaLoom project. It provides a comprehensive overview of the project structure, all specification files, and key information for each sub-component to help agents quickly find the right context when handling coding tasks.

> **Last Updated**: 2026-07-18
> **Project Root**: `/home/defaultuser/workspaces/metaloom/metaloom`

---

## 1. Project Overview

MetaLoom is a **Digital Asset Management (DAM) platform** consisting of two main runtime components that share common libraries:

| Component | Role | Location |
|-----------|------|----------|
| **Loom** | Backend service (REST/gRPC/GraphQL API, DB, auth, storage, MCP) | `loom/` |
| **Cortex** | Processing node (hashing, fingerprint, facedetect, LLM, ASR, etc.) | `cortex/` |
| **loom-ui** | React/Vite/MUI web front end | `loom-ui/` |
| **loom-app** | Electron desktop wrapper around the UI | `loom-app/` |
| **website** | Hugo-based documentation website | `website/` |

### Top-Level Reactor Modules (from `pom.xml`)

```
bom, loom-test-env, loom-shared, loom-client,
cortex, loom, examples, integration-test, e2e-test, website
```

### Key Technologies

- **Backend**: Vert.x 5, Dagger 2, jOOQ, Flyway, PostgreSQL
- **Processing**: RxJava 3, OpenCV, InspireFace, whisper.cpp, Tesseract, Ollama
- **Frontend**: React 18, Vite, TypeScript, MUI v5, React Flow
- **Build**: Maven, Docker/Podman
- **Testing**: JUnit 5, Testcontainers, Playwright (UI E2E)

---

## 2. Specification Files Tree

```
spec/
├── AGENTS.md                    # (empty - agent customization placeholder)
├── CONTEXT.md                   # THIS FILE - entry point for AI agents
├── METALOOM.md                  # Top-level project context & architecture
├── SPEC_RULES.md                # Rules for writing specifications
├── TASKS.template.md            # Required format for *_TASKS.md files
├── features/                    # Cross-cutting FEATURE specs (span Loom + Cortex + UI)
│   ├── pipeline/                # The pipeline feature — canonical, 3 files only
│   │   ├── PIPELINE.md          # Technical spec for AI agents (engine + persistence + protocol)
│   │   ├── PIPELINE_REQUIREMENTS.md  # Non-technical requirements + verified gap status
│   │   └── PIPELINE_TASKS.md    # Actionable work items (follows TASKS.template.md)
│   └── pipeline-nodes/
│       └── NODES.md             # Cortex node system: lifecycle, MetaStorage, per-node reference
├── cortex/                      # Cortex processing node specifications
│   ├── BUILD.md                 # Build, container, native dependencies
│   ├── CONFIGURATION.md         # YAML config, CLI flags, env vars, per-node options
│   └── CORTEX.md                # General architecture, module map, startup lifecycle
├── loom/                        # Loom backend service specifications
│   ├── BUILD.md                 # Loom build pipeline
│   ├── CONFIGURATION.md         # Configuration system
│   ├── EVENTBUS.md              # Event bus systems (pipeline events, Vert.x EventBus, WS fan-out)
│   ├── GRAPHQL.md               # (placeholder) GraphQL API
│   ├── GRPC.md                  # gRPC API
│   ├── LOOM.md                  # Overall architecture, module layout, startup lifecycle
│   ├── MCP.md                   # Model Context Protocol server (AI tool integration)
│   ├── PERMISSION.md            # (empty - permission model placeholder)
│   ├── PERSISTENCE.md           # Database layer, jOOQ DAOs, Flyway migrations
│   ├── RESTAPI.md               # REST API endpoints, authentication, clients, OpenAPI
│   ├── SERVER.md                # Server startup and lifecycle
│   ├── WEBSOCKET.md             # Processor & pipeline-events WebSocket protocols
│   └── ui/
│       ├── LOOM_UI.md           # Loom UI (React/Vite/MUI) specification
│       └── PIPELINE_EDITOR.md   # Pipeline Editor: React Flow canvas, CRUD, validation
└── tasks/
    └── OLD_TASKS.md             # Historical task archive (superseded)
```

### Where feature specs live vs. component specs

- **`features/`** — a capability that spans more than one component. Read these
  first when working on that capability end-to-end.
- **`loom/` and `cortex/`** — component-scoped architecture, config, and build.

⚠️ The pipeline feature was previously documented across five overlapping files
(`cortex/PIPELINE.md`, `loom/PIPELINE.md`, `common/LOOM_PIPELINE.md`,
`features/pipeline/CORTEX_PIPELINE.md`, `features/pipeline/LOOM_PIPELINE.md`).
These were **merged and deleted on 2026-07-18**. `features/pipeline/` is now the
only source. Older documents contained multiple claims contradicted by the code
— do not restore them.

---

## 2.1 Feature Specifications (`spec/features/`)

### Pipeline (`features/pipeline/`)

The pipeline feature — authoring pipelines in the Loom UI, persisting them with
versioning on Loom, and executing them as reactive DAGs on Cortex processors.
It spans Loom REST/DB/WebSocket, Cortex's execution engine, and the UI editor,
which is why it is documented as a feature rather than per component.

| File | Read it when |
|------|--------------|
| [PIPELINE.md](features/pipeline/PIPELINE.md) | You are writing pipeline code. Architecture, Cortex engine internals, node model, JSON schemas, Loom persistence & REST, the Loom↔Cortex protocol, testing patterns, gotchas |
| [PIPELINE_REQUIREMENTS.md](features/pipeline/PIPELINE_REQUIREMENTS.md) | You need to know what the system is *supposed* to do, and which requirements are currently met, partially met, or violated |
| [PIPELINE_TASKS.md](features/pipeline/PIPELINE_TASKS.md) | You are picking up pipeline work. 11 tasks, severity-ordered, with implementation guidance and test requirements |

🔴 **Before starting any pipeline work, read the "two things to know" section at
the top of [PIPELINE.md](features/pipeline/PIPELINE.md).** As of 2026-07-18 the
feature has two defects that break it end-to-end: Loom and Cortex use
incompatible definition JSON schemas (`edges[]` vs `dependencies[]`), so a
UI-authored graph collapses to a single node on execution; and pipeline runs
never transition out of `RUNNING`. Both are unaddressed. Do not assume the
happy path works.

### Pipeline nodes (`features/pipeline-nodes/`)

[NODES.md](features/pipeline-nodes/NODES.md) — the Cortex **node** system:
two-level node hierarchy, `AbstractMediaNode` lifecycle, MetaStorage, and the
per-node reference for every concrete node (hash, facedetect, whisper, OCR, …).
Complementary to `PIPELINE.md`, which covers how nodes are *composed and run*.

---

## 3. Sub-Component Reference Guide

### 3.1 Loom Backend Service (`loom/`)

**Purpose**: Central backend service managing assets, users, pipelines, and Cortex worker coordination.

#### Key Specifications

| Spec File | Description |
|-----------|-------------|
| [LOOM.md](loom/LOOM.md) | **Main entry point** - overall architecture, module layout, server lifecycle, Dagger DI, Loom-Cortex relationship |
| [RESTAPI.md](loom/RESTAPI.md) | REST API specification - endpoints, authentication (JWT/OAuth2), CRUD patterns, OpenAPI generation |
| [WEBSOCKET.md](loom/WEBSOCKET.md) | WebSocket protocols - Processor WS (`/api/v1/processors/ws`) and Pipeline Events WS (`/api/v1/pipelines/events/ws`) |
| [PERSISTENCE.md](loom/PERSISTENCE.md) | Database layer - jOOQ DAOs, Flyway migrations, DAO hierarchy, test infrastructure |
| [EVENTBUS.md](loom/EVENTBUS.md) | Event systems - Cortex PipelineEventBus, Vert.x EventBus (MCP only), WebSocket fan-out |
| [MCP.md](loom/MCP.md) | Model Context Protocol server - JSON-RPC 2.0 over HTTP+SSE/WebSocket, tool registry via Vert.x EventBus |
| [GRPC.md](loom/GRPC.md) | gRPC API - asset, health, reflection services (no pipeline surface) |
| [SERVER.md](loom/SERVER.md) | Server startup/lifecycle |
| [CONFIGURATION.md](loom/CONFIGURATION.md) | Configuration system, `LoomOptions` validation |
| [BUILD.md](loom/BUILD.md) | Loom build pipeline |
| [GRAPHQL.md](loom/GRAPHQL.md) | GraphQL API (placeholder) |
| [PERMISSION.md](loom/PERMISSION.md) | Permission model (empty placeholder) |
| [ui/LOOM_UI.md](loom/ui/LOOM_UI.md) | Loom UI specification |
| [ui/PIPELINE_EDITOR.md](loom/ui/PIPELINE_EDITOR.md) | Pipeline Editor - React Flow canvas, CRUD, validation |

> Pipeline execution and persistence are specified in
> [features/pipeline/PIPELINE.md](features/pipeline/PIPELINE.md), not here.

#### Module Layout (`loom/`)

```
loom/
├── common/          # Shared utilities, Vert.x setup, Dagger modules, LoomOptionsLoader
├── db/              # Database layer (parent)
│   ├── api/         # DAO/model interfaces, Element/CRUDDao abstractions
│   ├── api-test/    # Shared test infrastructure (CRUDDaoTestcases, DatabaseTest)
│   ├── jooq/        # jOOQ-based DAO implementations
│   ├── jooq-gen/    # jOOQ code generation strategy (prefixes with "Jooq")
│   ├── flyway/      # SQL migration scripts (V1__, V2.*__)
│   └── memory/      # In-memory DAO implementation for fast tests
├── services/        # Service layer (parent)
│   ├── rest/        # REST API, WebSocket endpoints, pipeline event broadcaster
│   ├── grpc/        # gRPC service (planned)
│   ├── graphql/     # GraphQL service (implemented, not registered)
│   ├── mcp/         # MCP server for AI agent integration
│   ├── auth-jwt/    # JWT authentication provider
│   ├── auth-keycloak/ # Keycloak auth provider
│   ├── auth-common/ # Shared auth utilities
│   ├── auth-auth0/  # Auth0 provider
│   ├── auth-okta/   # Okta provider
│   ├── image/       # Image processing service
│   ├── video/       # Video processing service
│   ├── elasticsearch/ # Elasticsearch integration
│   ├── lucene/      # Lucene search integration
│   ├── qdrant/      # Qdrant vector database integration
│   ├── tika/        # Apache Tika metadata extraction
│   ├── webhook/     # Webhook dispatch service
│   ├── monitoring/  # Monitoring/metrics service
│   ├── plugins/     # Plugin system
│   ├── fs/          # Filesystem service
│   └── eventbus/    # Placeholder (empty)
├── core/            # Bootstrap, server lifecycle, LoomImpl, BootstrapInitializer
├── fixture/         # Test fixtures, PoolSetupRunner, TestDBPoolManager
├── cli/             # LoomCLI command-line interface
├── containers/      # Dockerfiles + build-containers.sh
└── doc/             # AsciiDoc documentation source
```

#### Shared Modules (outside `loom/`)

| Module | Purpose |
|--------|---------|
| `loom-shared/api` | Core interfaces: `Loom`, `LoomOptions`, `ServerOptions`, `DatabaseOptions`, `AuthenticationOptions` |
| `loom-shared/rest-model` | REST DTOs, request/response models, validation models |
| `loom-shared/proto` | Protobuf/gRPC model definitions |
| `loom-client/rest` | Java HTTP client (`LoomHttpClient`) |
| `loom-client/grpc` | Java gRPC client (planned) |
| `loom-client/common` | Shared client interfaces (`ClientMethods`) |

#### Key Classes Reference (Loom)

| Class | Package | Purpose |
|-------|---------|---------|
| `LoomImpl` | `io.metaloom.loom.core` | Entry point; builds Dagger component, runs bootstrap |
| `BootstrapInitializer` | `io.metaloom.loom.core.boot` | Orchestrates startup/shutdown sequence |
| `DatabaseInitializer` | `io.metaloom.loom.core.boot` | Creates initial admin user, roles, permissions |
| `LoomCoreComponent` | `io.metaloom.loom.core.dagger` | Dagger component wiring all services |
| `RESTService` | `io.metaloom.loom.rest` | REST API service (router, endpoints, auth) |
| `MCPService` | `io.metaloom.loom.mcp` | MCP server |
| `PipelineEventBroadcaster` | `io.metaloom.loom.rest.service.impl` | Fans out pipeline events to UI WebSocket clients |

#### Build & Run Commands

```bash
# Full build (Maven + UI + containers)
./build.sh

# Fast compile check
mvn -T 8 test-compile -q -DskipTests

# Integration tests
./it.sh

# End-to-end tests
./e2e.sh

# Start local Postgres
./start-postgres.sh

# Start demo (Postgres + Loom + Cortex)
./start-demo.sh
```

---

### 3.2 Cortex Processing Node (`cortex/`)

**Purpose**: Standalone worker process that analyzes media files and optionally syncs results to Loom.

#### Key Specifications

| Spec File | Description |
|-----------|-------------|
| [CORTEX.md](cortex/CORTEX.md) | **Main entry point** - architecture, module map, startup lifecycle, CLI commands, online/offline modes |
| [CONFIGURATION.md](cortex/CONFIGURATION.md) | Configuration - YAML config file, CLI flags, environment variables, per-node options |
| [BUILD.md](cortex/BUILD.md) | Build system - Maven modules, container image, native dependencies, fast-compile recipes |
| [../features/pipeline-nodes/NODES.md](features/pipeline-nodes/NODES.md) | Node system - lifecycle, MetaStorage, two-level hierarchy, per-node reference |
| [../features/pipeline/PIPELINE.md](features/pipeline/PIPELINE.md) | Pipeline execution engine - DAG, RxJava 3, serde, caching, sync, Loom bridge |

#### Module Layout (`cortex/`)

```
cortex/
├── api/                 # Public interfaces: Cortex, CortexOptions, CortexNode, LoomMedia, NodeResult, MetaStorage
├── common/              # Shared impls: MetaStorageImpl, CortexOptionsLoader, LoomMediaLoader, media types
├── fs/                  # Filesystem scanner (Linux xattr support)
├── core-media/          # Media decorator types (HashMedia, FacedetectMedia, etc.) + AssertJ test helpers
├── nodes/               # Concrete processing nodes (parent POM)
│   ├── common-api/      # Common node API
│   ├── filter-api/      # Filter node API
│   ├── source-api/      # Source node API
│   ├── hash/            # SHA-512, SHA-256, MD5, chunk-hash
│   ├── fingerprint/     # Video fingerprinting
│   ├── facedetect/      # Face detection + embeddings (InspireFace)
│   ├── thumbnail/       # Contact-sheet thumbnail generation
│   ├── consistency/     # Zero-chunk detection
│   ├── dedup/           # SHA-512/fingerprint deduplication
│   ├── quality/         # Resolution, blurriness, bitrate metrics
│   ├── scene-detection/ # Optical-flow scene boundary detection
│   ├── ocr/             # Text extraction (Tesseract)
│   ├── tika/            # Apache Tika metadata extraction
│   ├── whisper/         # Speech-to-text (whisper.cpp)
│   ├── llm/             # Metadata extraction (Ollama LLM)
│   ├── captioning/      # Image captioning (SmolVLM)
│   └── loom/            # Loom sync node
├── processor/           # MediaProcessor + FilesystemProcessor (CLI-driven batch)
├── core/                # Runtime wiring: CortexImpl, CLI commands, Dagger modules, LoomControlChannel
├── cli/                 # CLI entry point (CortexCLIMain), Dagger component, shade plugin
├── container/           # Containerfile + build-container.sh for OCI image
├── pipeline-api/        # Pipeline, PipelineNode, PipelineExecutor, PipelineManager, events, cache SPIs
├── pipeline-core/       # DefaultPipeline, ReactivePipelineExecutor, AbstractPipelineNode, filters, JSON serde
└── pipeline-common/     # DefaultPipelineEventBus, cache impls, DefaultLoomBulkSyncCollector
```

#### Key Classes Reference (Cortex)

| Class | Package | Purpose |
|-------|---------|---------|
| `Cortex` / `CortexImpl` | `io.metaloom.cortex` / `io.metaloom.cortex.impl` | Top-level interface & implementation; manages lifecycle |
| `CortexCLIMain` | `io.metaloom.cortex.cli` | `main()` entry point; builds Dagger component, runs CLI |
| `CortexComponent` | `io.metaloom.cortex.cli.dagger` | Dagger component wiring all modules |
| `CortexBindModule` | `io.metaloom.cortex.cli.dagger` | Dagger bindings: Cortex, MediaProcessor, PipelineExecutor, etc. |
| `CortexBootstrapInitializer` | `io.metaloom.cortex.impl.boot` | Starts monitoring HTTP + Loom control channel |
| `LoomControlChannel` | `io.metaloom.cortex.impl.loom` | WebSocket client to Loom; registration, heartbeat, work orders |
| `PipelineWorkOrderHandler` | `io.metaloom.cortex.impl.loom` | Handles work orders from Loom |
| `RegistryNodeFactory` | `io.metaloom.cortex.pipeline.loader` | Maps JSON node definitions to concrete PipelineNode impls |

#### Online vs Offline Mode

| Mode | Condition | Behaviour |
|------|-----------|-----------|
| **Online** | Loom host + port configured | Connects to Loom via WebSocket, registers capabilities, receives work orders, syncs results |
| **Offline** | No Loom host configured | Runs standalone; driven by `cortex process run` CLI command |

#### Build Commands

```bash
# Build all cortex modules
mvn -T 8 clean package -DskipTests -pl cortex -am

# Build only CLI JAR
mvn -T 8 clean package -DskipTests -pl cortex/cli -am

# Run tests
mvn -T 8 test -pl cortex

# Build container
cortex/container/build-container.sh
```

---

### 3.3 Loom UI (`loom-ui/`)

**Purpose**: React/Vite/TypeScript/MUI web front end for the MetaLoom DAM platform.

#### Technology Stack

- **Framework**: React 18 + Vite + TypeScript
- **UI Library**: MUI v5 (Material UI)
- **Graph Visualization**: React Flow (pipeline graph editor)
- **Charts**: Recharts (dashboards)
- **Internationalization**: i18next
- **Routing**: React Router DOM v6

#### Source Layout (`loom-ui/src/`)

```
src/
├── Admin/           # Admin panel components
├── Asset/           # Asset management views
├── Content/         # Content views
├── Dashboard/       # Dashboard components
├── Login/           # Authentication views
├── Pipeline/        # Pipeline graph editor
├── User/            # User management
├── Welcome/         # Landing page
├── components/      # Shared UI components
├── features/        # Feature-specific components
├── context/         # React context providers
├── api/             # API client layer
├── layout/          # Layout components
├── theme/           # MUI theme configuration
├── i18n/            # Internationalization
├── mock/            # Mock data for development
├── img/             # Static images
├── types/           # TypeScript type definitions
└── main.tsx         # Application entry point
```

#### Commands

```bash
# Development server
npm run dev

# Production build
npm run build

# Run UI E2E tests (Playwright)
npm run test:e2e
```

#### Key Configuration Files

| File | Purpose |
|------|---------|
| `package.json` | Dependencies and scripts |
| `vite.config.ts` | Vite configuration |
| `tsconfig.json` | TypeScript configuration |
| `playwright.config.ts` | Playwright E2E test configuration |

---

### 3.4 Website (`website/`)

**Purpose**: Hugo-based documentation website for MetaLoom.

#### Technology Stack

- **Static Site Generator**: Hugo
- **Theme**: meghna-hugo
- **CSS/JS Plugins**: FontAwesome, Bootstrap, Swagger UI, TOC, etc.

#### Structure

```
website/
├── config.toml          # Hugo configuration
├── content/             # Main content (markdown)
├── content-off/         # Disabled/archived content
├── data/                # Data files (JSON, YAML, TOML)
├── i18n/                # Internationalization
├── static/              # Static assets
├── themes/              # Hugo themes
├── resources/           # Hugo resource cache
├── dist/                # Build output (publishDir)
├── build.sh             # Build script
├── watch.sh             # Watch mode script
└── pom.xml              # Maven wrapper (for CI)
```

#### Commands

```bash
# Build website
./build.sh

# Watch mode (live reload)
./watch.sh
```

#### Key Configuration (`config.toml`)

- **baseURL**: `https://metaloom.io`
- **theme**: `meghna-hugo`
- **publishDir**: `dist`
- **Plugins**: Swagger UI, Bootstrap, FontAwesome, TOC, etc.

---

### 3.5 Integration Testing (`integration-test/`)

**Purpose**: Cross-module integration tests that boot the Loom stack in-process and drive it via `LoomHttpClient`.

#### Structure

```
integration-test/
├── pom.xml
└── src/test/java/io/metaloom/loom/test/integration/
    ├── AbstractIntegrationTest.java      # Base class with LoomHttpClient setup
    ├── BasicIntegrationTest.java         # Basic integration test cases
    ├── LoomExtensionHelper.java          # JUnit 5 extension helpers
    └── PipelinePersistenceIntegrationTest.java  # Pipeline persistence tests
```

#### Key Dependencies

- `loom-container-server` - Loom server container for testing
- `loom-client-rest` - REST client
- `cortex-cli` - Cortex CLI for processor simulation
- `cortex-pipeline-core` - Pipeline execution
- `loom-test-env` - Test environment (DB pool, JUnit extensions)
- `loom-fixture` - Test fixtures

#### Running Integration Tests

```bash
# Via convenience script (starts PoolSetupRunner then runs tests)
./it.sh

# Direct Maven
mvn verify -pl integration-test
```

#### Test Database Pool

Tests lease pre-populated PostgreSQL databases from the external `testdatabase-provider` service. See `loom/DEVELOPMENT.md` for setup.

---

### 3.6 End-to-End Testing (`e2e-test/`)

**Purpose**: Full end-to-end tests that run against a packaged container deployment.

#### Structure

```
e2e-test/
├── pom.xml
├── run-e2e.sh
├── config/
└── src/test/java/io/metaloom/loom/studio/test/
    └── E2ETest.java
```

#### Running E2E Tests

```bash
# Via convenience script (builds demo container, starts Postgres + demo, runs tests)
./e2e.sh

# Direct Maven (against external container)
mvn test -Dloom.external=true -pl e2e-test
```

---

### 3.7 Examples (`examples/`)

**Purpose**: Example projects demonstrating how to extend Cortex with custom nodes and CLI commands.

#### Projects

| Example | Description |
|---------|-------------|
| `cortex-custom-cli/` | Demonstrates adding custom CLI commands to Cortex |
| `cortex-custom-node/` | Demonstrates implementing a custom Cortex processing node |

#### Structure (each example)

```
example/
├── pom.xml
├── src/
│   ├── main/java/...     # Implementation
│   └── test/...          # Tests
└── target/               # Build output
```

---

## 4. Cross-Cutting Concerns

### 4.1 Authentication & Authorization

| Component | Mechanism |
|-----------|-----------|
| REST API | JWT bearer tokens (HMAC-signed), `__Host-loom_token` cookie |
| WebSocket | `?token=<jwt>` query parameter, strict/lenient mode |
| OAuth2 | BFF pattern with PKCE (Keycloak, Auth0, Okta) |
| API Tokens | CRUD at `/api/v1/tokens` with specific permissions |
| Permissions | Vert.x `PermissionBasedAuthorization` (e.g., `CREATE_USER`, `READ_ASSET`) |

### 4.2 Database & Persistence

| Layer | Technology |
|-------|------------|
| Primary DB | PostgreSQL |
| ORM | jOOQ (code-generated) |
| Migrations | Flyway (SQL files in `loom/db/flyway/src/main/resources/db/migration/`) |
| DAO Pattern | Interface in `loom-db-api`, impl in `loom-db-jooq` and `loom-db-memory` |
| Test DB | Leased from `testdatabase-provider` service |

### 4.3 Event Systems

| System | Scope | Transport | Purpose |
|--------|-------|-----------|---------|
| Cortex PipelineEventBus | In-process (Cortex) | Java pub/sub | Internal pipeline coordination, sync, caching |
| Vert.x EventBus | Vert.x instance (Loom) | Vert.x EventBus | MCP tool dispatch only (`mcp.tool.<name>`) |
| WebSocket Fan-out | Loom REST server | Raw `ServerWebSocket` | Forward pipeline events to UI clients |

### 4.4 Configuration Priority (Cortex)

1. **CLI flags** (highest)
2. **Environment variables**
3. **YAML config file** (`~/.config/metaloom/cortex.yml`)
4. **Code defaults** (lowest)

### 4.5 Dagger Dependency Injection

Both Loom and Cortex use **Dagger 2** extensively:
- Generated components under `target/generated-sources/annotations`
- Multibindings for extensibility (e.g., `Set<MCPTool>`, `Set<LoomMetaTypeHandler>`)
- Subcomponents for request-scoped DI (e.g., `RestComponent` per REST request)

---

## 5. Where Do I Find...? (Cheat Sheet)

| Need | Look Here |
|------|-----------|
| REST endpoint implementations | `loom/services/rest/.../endpoint/impl/` |
| REST request/response DTOs | `loom-shared/rest-model/` |
| Java REST client | `loom-client/rest/` (`LoomHttpClient`) |
| Custom AssertJ assertions | `loom-shared/rest-model-test/.../assertj/` and `cortex/**/test/**/assertj/` |
| JWT / login / OAuth2 | `loom/services/auth/` |
| DAO interfaces | `loom/db/api/` |
| jOOQ DAO implementations | `loom/db/jooq/` |
| Generated jOOQ tables | `loom/db/jooq/src/jooq/java/...` |
| SQL migrations | `loom/db/flyway/src/main/resources/db/migration/` |
| Test DB pool setup | `loom-test-env/`, `loom/fixture/`, `loom/DEVELOPMENT.md` |
| Cortex processing nodes | `cortex/nodes/` |
| Pipeline engine (API / impl / shared) | `cortex/pipeline-api/`, `cortex/pipeline-core/`, `cortex/pipeline-common/` |
| Pipeline loading + node type registration | `cortex/core/.../pipeline/loader/`, `cortex/cli/.../dagger/PipelineNodeFactoryModule.java` |
| Loom↔Cortex control channel & work orders | `cortex/core/.../impl/loom/` |
| Pipeline REST endpoints & services | `loom/services/rest/.../endpoint/impl/Pipeline*.java`, `.../service/impl/` |
| Pipeline DB migrations | `loom/db/flyway/.../V2.19__add_pipeline.sql`, `V2.29__add_pipeline_run.sql`, `V2.30__add_pipeline_version.sql` |
| Pipeline UI editor | `loom-ui/src/features/pipeline/PipelineEditor.tsx` |
| Documentation source (AsciiDoc) | `loom/doc/src/main/docs/` |
| Container builds | `loom/containers/` + `cortex/container/` |
| UI source | `loom-ui/src/` |
| Website content | `website/content/` |
| Integration tests | `integration-test/src/test/java/io/metaloom/loom/test/integration/` |
| E2E tests | `e2e-test/src/test/java/io/metaloom/loom/studio/test/` |
| Examples | `examples/cortex-custom-cli/`, `examples/cortex-custom-node/` |

---

## 6. Conventions & Gotchas

| Area | Convention / Gotcha |
|------|---------------------|
| **Java packages** | Backend: `io.metaloom.loom.*`; Processing: `io.metaloom.cortex.*` — do not mix |
| **Dagger** | After touching generic types on nodes/services, do a clean build — stale generated code causes confusing compile errors |
| **jOOQ generated sources** | Live inside `src/jooq/java`; never edit by hand — rerun `loom/db/jooq/generate.sh` after schema changes |
| **New DB fields** | Need: (a) Flyway `V*.sql`, (b) jOOQ regeneration, (c) DAO API changes in `loom/db/api`, (d) impl updates in `loom/db/jooq` and `loom/db/memory`, (e) contract tests in `loom/db/api-test` |
| **REST updates** | Loom uses `POST` for both create AND update (not PUT/PATCH) |
| **WebSocket auth** | Token via `?token=<jwt>` query param (browsers can't send custom headers on WS upgrade) |
| **Test assertions** | Use domain-specific `AbstractAssert` subclasses — don't roll your own equality checks |
| **Cortex nodes** | Two hierarchies: Cortex-level (CLI) and Pipeline-level (DAG) — bridged by `CortexNodeAdapter`. Never extend both bases |
| **Pipeline nodes** | Must have exactly one source node; IDs must match `^[a-z0-9]([a-z0-9\-]{0,62}[a-z0-9])?$` |
| **Pipeline definition JSON** | 🔴 Loom writes `nodes[]` + `edges[]`; the Cortex loader reads `nodes[].dependencies[]` and ignores `edges`. Authored pipelines do not execute as drawn — see [PIPELINE.md](features/pipeline/PIPELINE.md) §9.2 |
| **Pipeline runs** | 🔴 Never transition out of `RUNNING`; all counter columns are dead |
| **Pipeline node types** | Only 5 of 29 advertised kinds are executable; the rest silently stub out as *successes* |
| **Pipeline validation** | Logic is triplicated (loom-shared, loom-rest, UI). Only the loom-rest copy checks node types and is tested |
| **MCP tools** | Registered via Dagger multibinding (`Set<MCPTool>`), dispatched via Vert.x EventBus (`mcp.tool.<name>`) |

---

## 7. Progress Assessment

- [x] Project overview and architecture documented
- [x] All specification files cataloged with descriptions
- [x] Loom backend service specifications covered
- [x] Cortex processing node specifications covered
- [x] Loom UI specifications covered
- [x] Website specifications covered
- [x] Integration testing specifications covered
- [x] E2E testing specifications covered
- [x] Examples specifications covered
- [x] Cross-cutting concerns documented
- [x] Cheat sheet for quick navigation
- [x] Conventions and gotchas highlighted
- [x] Pipeline feature specs unified into `features/pipeline/` (2026-07-18) and
      verified against the code
- [ ] Remaining feature areas (assets, auth, search) not yet extracted into
      `features/` — they are still documented per component
- [ ] `loom/PERMISSION.md` and `AGENTS.md` are empty placeholders

---

## 8. Related Memory Files

Additional living notes are kept under `/memories/repo/`:

| File | Description |
|------|-------------|
| `metaloom-cortex-architecture.md` | Detailed Cortex architecture notes |
| `cortex-pipeline-detailed.md` | Pipeline execution engine deep dive |
| `cortex-vertx5-websocket-client-note.md` | Vert.x 5 WebSocket client patterns |
| `yolo4j-opencv-ffm-migration-note.md` | YOLO4J OpenCV FFM migration notes |
| `loom-graalvm-native-build-note.md` | GraalVM native build notes |
| `metaloom-website-reactor-module-note.md` | Website reactor module notes |
| `metaloom-website-docs-symlink-note.md` | Website docs symlink notes |
| `asset-pool-delete-pk-note.md` | Asset pool delete primary key notes |
| `cortex-opencv-ffm-facedetect-scene-note.md` | OpenCV FFM face detect scene notes |

---

*This document is maintained as the primary entry point for AI coding agents. When in doubt, start here and follow the cross-references to the detailed specification files.*