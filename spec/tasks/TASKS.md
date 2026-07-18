# Loom Specification Tasks

This file tracks tasks for missing implementations, gaps, and improvements identified from the spec files in `spec/loom/`. Each task includes a headline and a detailed prompt for AI coding agents.

---

## Task Format

Each task follows this format:

```
## Task: <Headline>

**Argumentation Summary:** Why this task is needed
**Improvement Summary:** What the improvement entails

```
<Task prompt with detailed description, file references, and implementation guidance>
```

**References:** Links to relevant spec files
**Test Requirements:** Testing expectations
```

---

## REST API Tasks

### Task: Implement PUT/PATCH Support for REST Endpoints

**Argumentation Summary:** The REST API currently uses POST for both create and update operations, which deviates from REST conventions. PUT/PATCH support would improve API consistency and interoperability.

**Improvement Summary:** Add PUT and PATCH method handlers to the REST routing infrastructure, update endpoint registration to support these methods, and maintain backward compatibility with existing POST-based updates.

```
Implement PUT and PATCH HTTP method support for REST endpoints in the Loom server.

**Current State (from RESTAPI.md):**
- Loom uses POST for both create and update operations
- PUT and PATCH are listed as "Not used by the server" in the HTTP Methods table
- CORS configuration supports PUT/PATCH but no endpoints implement them

**Files to Modify:**
1. `loom/services/rest/src/main/java/io/metaloom/loom/rest/ApiRouter.java` - Add PUT/PATCH route registration methods
2. `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/AbstractEndpoint.java` - Add securePut/securePatch helpers
3. Individual endpoint implementations (UserEndpoint, AssetEndpoint, etc.) - Add PUT/PATCH handlers
4. `loom-shared/rest-model` - Ensure DTOs support partial updates for PATCH

**Implementation Steps:**
1. Add `addPutRoute()` and `addPatchRoute()` methods to `ApiRouter` similar to `addRoute()`/`addPostRoute()`
2. Update `AbstractEndpoint` with `securePut()` and `securePatch()` helper methods
3. For each CRUD endpoint, add PUT handler (full replacement) and PATCH handler (partial update)
4. Maintain backward compatibility - keep POST update handlers working
5. Update OpenAPI spec generation to document PUT/PATCH endpoints
6. Add validation for PUT (full object required) vs PATCH (partial object allowed)

**References:** 
- RESTAPI.md sections 1.2, 1.7, 3.1
- LOOM.md section 2 (EndpointModule registration)

**Test Requirements:**
- Unit tests for PUT/PATCH route registration in ApiRouter
- Integration tests for each endpoint supporting PUT/PATCH
- Verify backward compatibility with existing POST update calls
- OpenAPI spec includes PUT/PATCH operations
```

**References:** RESTAPI.md, LOOM.md
**Test Requirements:** Unit tests for route registration, integration tests for each endpoint, OpenAPI verification

---

### Task: Add Request Body Size Limits for Security

**Argumentation Summary:** The REST API currently has no body size limit (`BodyHandler.create().setBodyLimit(-1)`), which poses a security risk for DoS attacks via large payloads.

**Improvement Summary:** Configure reasonable body size limits for different endpoint categories, with higher limits for binary upload endpoints and lower limits for JSON API endpoints.

```
Add configurable request body size limits to the REST API for security hardening.

**Current State (from RESTAPI.md section 1.9):**
- `BodyHandler.create().setBodyLimit(-1)` configured globally (no limit)
- Intentional for large binary uploads but noted as security consideration

**Files to Modify:**
1. `loom/services/rest/src/main/java/io/metaloom/loom/rest/RESTService.java` - Configure body handler with limits
2. `loom/api/options/ServerOptions.java` - Add body limit configuration properties
3. `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/*` - Per-endpoint limit overrides

**Implementation Steps:**
1. Add `bodyLimit` configuration to `ServerOptions` (default: 10MB for JSON, 100MB for uploads)
2. Modify `RESTService.setupRouter()` to use a default body limit
3. Allow per-route body limit overrides via `ApiRouter` (e.g., `addRoute(..., bodyLimit)`)
4. Set higher limits for binary upload endpoints (assets, attachments)
5. Set lower limits for standard JSON CRUD endpoints
6. Add environment variable override: `LOOM_SERVER_BODY_LIMIT`

**References:**
- RESTAPI.md section 1.9
- CONFIGURATION.md (ServerOptions structure)

**Test Requirements:**
- Test that requests exceeding limit return 413 Payload Too Large
- Test that binary upload endpoints accept larger payloads
- Test configuration via environment variable
- Verify existing functionality still works with default limits
```

**References:** RESTAPI.md, CONFIGURATION.md
**Test Requirements:** 413 response tests, binary upload tests, config override tests

---

### Task: Implement API Versioning Strategy

**Argumentation Summary:** The API is currently at v1 with no versioning strategy documented. A clear versioning approach is needed for future evolution.

**Improvement Summary:** Define and implement an API versioning strategy (URL path versioning, header-based, or media type versioning) with migration path for v2.

```
Define and implement an API versioning strategy for the Loom REST API.

**Current State (from RESTAPI.md section 1.1):**
- All endpoints under `/api/v1` path prefix
- Current version is v1, no v2 yet
- OpenAPI spec served at `/api/v1/openapi`

**Files to Modify:**
1. `loom/services/rest/src/main/java/io/metaloom/loom/rest/RESTConstants.java` - Version constants
2. `loom/services/rest/src/main/java/io/metaloom/loom/rest/ApiRouter.java` - Version-aware routing
3. `loom-shared/rest-model` - Version-specific DTOs if needed
4. Documentation updates

**Implementation Steps:**
1. Document versioning strategy (recommend: URL path versioning `/api/v1`, `/api/v2`)
2. Add version negotiation via `Accept` header (e.g., `application/vnd.metaloom.v2+json`)
3. Create version-aware `ApiRouter` that can mount multiple versions
4. Implement deprecation headers for v1 endpoints when v2 exists
5. Add version info to `/api` root endpoint listing available versions
6. Plan v2 breaking changes (e.g., switch to PUT/PATCH, standardize error formats)

**References:**
- RESTAPI.md section 1.1, 1.7
- LOOM.md section 2 (module layout)

**Test Requirements:**
- Test version routing works correctly
- Test Accept header negotiation
- Test deprecation headers on v1 endpoints
- Test OpenAPI spec generation per version
```

**References:** RESTAPI.md, LOOM.md
**Test Requirements:** Version routing tests, header negotiation tests, OpenAPI per-version tests

---

## WebSocket Tasks

### Task: Implement WebSocket Authentication for MCP Endpoints

**Argumentation Summary:** The MCP WebSocket endpoint (`/mcp/ws`) currently has no authentication, creating a security gap. The processor and pipeline event WebSockets use token-based auth that should be reused.

**Improvement Summary:** Add JWT token authentication to MCP WebSocket endpoint using the existing `WebSocketAuthenticator` infrastructure.

```
Add authentication to the MCP WebSocket endpoint (/mcp/ws) using the existing WebSocketAuthenticator.

**Current State (from MCP.md section 7, 11.4):**
- MCP server has NO authentication on any endpoint (SSE, message, WebSocket)
- Processor WebSocket (`/api/v1/processors/ws`) and Pipeline Events WebSocket (`/api/v1/pipelines/events/ws`) use `?token=<jwt>` query parameter validated by `WebSocketAuthenticator`
- MCP WebSocket at `/mcp/ws` is completely unauthenticated
- Security implication: anyone with network access to port 4041 can call tools and read asset data

**Files to Modify:**
1. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPService.java` - Add auth to WS endpoint
2. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/handler/MCPJsonRpcHandler.java` - Extract token from WS handshake
3. `loom/services/rest/src/main/java/io/metaloom/loom/rest/websocket/WebSocketAuthenticator.java` - Reuse for MCP
4. `loom/api/options/AuthenticationOptions.java` - Add MCP auth configuration

**Implementation Steps:**
1. In `MCPService.start()`, apply `WebSocketAuthenticator` to the `/mcp/ws` upgrade route
2. Extract token from `?token=<jwt>` query parameter (same pattern as processor WS)
3. Validate token using `LoomAuthenticationHandler.authenticateToken()`
4. Close with code 4401 on invalid/missing token (consistent with other WS endpoints)
5. Add strict/lenient mode via `LOOM_MCP_WS_STRICT_AUTH` env var
6. Add configuration option to disable auth for development (default lenient)
7. Update MCP.md progress checklist (section 11.4)

**References:**
- MCP.md sections 2.2, 7, 11.2, 11.4
- WEBSOCKET.md sections 2, 3.2, 3.3, 4.2
- LOOM.md section 3.1 (BootstrapInitializer starts MCP after REST)

**Test Requirements:**
- Test WS connection rejected without token in strict mode
- Test WS connection accepted with valid token
- Test WS connection accepted without token in lenient mode (with warning log)
- Test close code 4401 on invalid token
- Test integration with existing JWT infrastructure
```

**References:** MCP.md, WEBSOCKET.md, LOOM.md
**Test Requirements:** Auth rejection/acceptance tests, strict/lenient mode tests, close code verification

---

### Task: Add SSE Authentication for MCP Endpoints

**Argumentation Summary:** The MCP SSE endpoint (`/mcp/sse`) and message endpoint (`/mcp/message`) have no authentication, same security gap as WebSocket.

**Improvement Summary:** Add JWT token authentication to MCP SSE and HTTP message endpoints using Authorization header or query parameter.

```
Add authentication to MCP SSE (/mcp/sse) and message (/mcp/message) endpoints.

**Current State (from MCP.md sections 2.1, 7, 11.4):**
- SSE endpoint: `GET /mcp/sse` - no auth
- Message endpoint: `POST /mcp/message` - no auth
- Both endpoints allow unauthenticated access to tool execution
- CORS on SSE allows all origins (`*`)

**Files to Modify:**
1. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPService.java` - Add auth handlers to routes
2. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/handler/MCPJsonRpcHandler.java` - Extract and validate token
3. `loom/services/rest/src/main/java/io/metaloom/loom/rest/auth/LoomAuthenticationHandler.java` - Reuse for MCP

**Implementation Steps:**
1. For SSE endpoint: Accept token via `?token=<jwt>` query parameter OR `Authorization: Bearer <jwt>` header
2. For message endpoint: Require `Authorization: Bearer <jwt>` header (POST body cannot carry token securely)
3. Validate using existing `LoomAuthenticationHandler.authenticateToken()`
4. Return 401 on invalid/missing token
5. Add `LOOM_MCP_HTTP_STRICT_AUTH` env var for strict mode
6. Restrict CORS on SSE endpoint in production (configurable allowed origins)
7. Update MCP.md progress checklist (section 11.4)

**References:**
- MCP.md sections 2.1, 7, 11.2, 11.4
- RESTAPI.md section 2.1 (JWT authentication)
- CONFIGURATION.md (AuthenticationOptions)

**Test Requirements:**
- Test SSE connection rejected without valid token
- Test message endpoint returns 401 without Authorization header
- Test both query param and header token extraction
- Test CORS restriction configuration
- Test integration with existing JWT infrastructure
```

**References:** MCP.md, RESTAPI.md, CONFIGURATION.md
**Test Requirements:** SSE auth tests, message endpoint auth tests, CORS tests

---

### Task: Implement WebSocket Reconnection with Exponential Backoff for UI Clients

**Argumentation Summary:** The UI client (`loom-ui/src/api/pipelineEvents.ts`) has basic auto-reconnect (3s delay) but no exponential backoff, which can cause thundering herd problems on server restart.

**Improvement Summary:** Enhance the TypeScript WebSocket client with exponential backoff, jitter, and maximum retry limits.

```
Enhance the UI WebSocket client with exponential backoff reconnection logic.

**Current State (from EVENTBUS.md section 3.9):**
- `loom-ui/src/api/pipelineEvents.ts` has auto-reconnect with fixed 3s delay
- No exponential backoff, no jitter, no max retry limit
- Can cause thundering herd on server restart

**Files to Modify:**
1. `loom-ui/src/api/pipelineEvents.ts` - Enhance reconnection logic
2. `loom-ui/src/api/pipelineEvents.ts` - Add configuration options

**Implementation Steps:**
1. Implement exponential backoff: `delay = min(baseDelay * 2^attempt, maxDelay)`
2. Add jitter: `delay = delay * (0.5 + Math.random())` to prevent synchronized retries
3. Add maximum retry attempts (configurable, default 10)
4. Add connection state events: `connecting`, `connected`, `disconnected`, `failed`
5. Expose configuration via constructor options
6. Add cleanup on component unmount (abort reconnection attempts)

**References:**
- EVENTBUS.md section 3.9 (UI Client)
- WEBSOCKET.md section 4.8 (Connection Lifecycle)

**Test Requirements:**
- Unit test backoff calculation with various attempts
- Unit test jitter adds randomness
- Unit test max retry limit stops reconnection
- Integration test: server restart triggers reconnection with backoff
- Test cleanup on unmount
```

**References:** EVENTBUS.md, WEBSOCKET.md
**Test Requirements:** Backoff calculation tests, jitter tests, max retry tests, integration tests

---

## MCP Tasks

### Task: Implement MCP Authentication (JWT + API Key)

**Argumentation Summary:** MCP server has no authentication on any endpoint (sections 7, 11.4 of MCP.md). This is a critical security gap for production use.

**Improvement Summary:** Implement comprehensive authentication for MCP server supporting JWT tokens (via Authorization header and query param) and API keys (via custom header), with permission-scoped tool access.

```
Implement authentication for the MCP server across all transports (SSE, HTTP message, WebSocket).

**Current State (from MCP.md sections 7, 11.4):**
- NO authentication on any MCP endpoint
- Tools access DAOs directly without permission checks
- MCP port (4041) should not be exposed to untrusted networks
- Planned: JWT via Authorization header, API key via X-API-Key, WS token query param, permission-scoped tools

**Files to Modify:**
1. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPService.java` - Add auth handlers to all routes
2. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/handler/MCPJsonRpcHandler.java` - Extract credentials
3. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/MCPToolRegistry.java` - Add permission checks
4. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/model/MCPToolDescriptor.java` - Add permission declarations
5. `loom/api/options/AuthenticationOptions.java` - Add MCP auth config
6. `loom/services/auth-common` - Reuse JWT validation logic

**Implementation Steps:**
1. Create `MCPAuthenticationHandler` extending/reusing `LoomAuthenticationHandler`
2. For SSE (`/mcp/sse`): Accept token via `?token=` query param OR `Authorization: Bearer` header
3. For message (`/mcp/message`): Require `Authorization: Bearer` header OR `X-API-Key` header
4. For WebSocket (`/mcp/ws`): Accept token via `?token=` query param (reuse WebSocketAuthenticator)
5. Validate JWT using existing infrastructure; validate API keys via TokenDao
6. Add permission declarations to `MCPToolDescriptor` (e.g., `requiredPermissions: ["READ_ASSET"]`)
7. In `MCPToolRegistry.dispatch()`, check permissions before tool execution
8. Return structured error responses with error codes (not generic messages)
9. Add configuration: `mcp.auth.enabled`, `mcp.auth.strictMode`, `mcp.auth.allowedOrigins`
10. Update MCP.md progress checklist sections 11.2, 11.4

**References:**
- MCP.md sections 2, 7, 11.2, 11.4
- RESTAPI.md section 2 (JWT, OAuth2, API tokens)
- WEBSOCKET.md section 2 (WS authentication pattern)
- CONFIGURATION.md (AuthenticationOptions)

**Test Requirements:**
- Test all three transports with JWT token
- Test message endpoint with API key
- Test permission denial returns structured error
- Test strict/lenient modes
- Test CORS restriction on SSE
- Test tool descriptor includes permissions
```

**References:** MCP.md, RESTAPI.md, WEBSOCKET.md, CONFIGURATION.md
**Test Requirements:** Multi-transport auth tests, permission tests, error format tests, config tests

---

### Task: Implement MCP Resource Providers

**Argumentation Summary:** MCP resources are completely stubbed (`resources/list` returns empty, `resources/read` returns error). Resources could expose assets, collections, pipelines as readable resources.

**Improvement Summary:** Implement MCP resource providers for assets, collections, pipelines, and other domain objects with subscribe/unsubscribe support.

```
Implement MCP resource providers to expose Loom domain objects as MCP resources.

**Current State (from MCP.md section 11.5):**
- `resources/list` returns empty list (stubbed)
- `resources/read` returns method-not-found error
- No resource providers implemented
- No `resources/subscribe` or `resources/unsubscribe` support

**Files to Modify:**
1. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/handler/MCPJsonRpcHandler.java` - Add resource methods
2. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/resource/` - New package for resource providers
3. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/model/` - Resource models
4. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPToolRegistry.java` - Or new `MCPResourceRegistry`

**Implementation Steps:**
1. Define `MCPResourceProvider` SPI interface with `listResources()`, `readResource(uri)`, `subscribe(uri)`, `unsubscribe(uri)`
2. Create `MCPResourceRegistry` (similar to `MCPToolRegistry`) with EventBus dispatch
3. Implement providers for:
   - Assets: `loom://assets/{uuid}` - full asset metadata
   - Collections: `loom://collections/{uuid}` - collection with asset refs
   - Pipelines: `loom://pipelines/{uuid}` - pipeline definition
   - Search results: `loom://search?query=...` - paginated search
4. Register providers via Dagger multibinding (like `MCPToolModule`)
5. Implement `resources/list` to return all available resource templates
6. Implement `resources/read` with URI template matching
7. Implement `resources/subscribe`/`unsubscribe` for live updates (push via SSE/WS)
8. Add resource metadata: name, description, mimeType, size
9. Update MCP.md progress checklist section 11.5

**References:**
- MCP.md sections 3.3, 11.5
- MCP spec: https://modelcontextprotocol.io/specification/2025-03-26/resources
- RESTAPI.md (asset/collection/pipeline REST models for reference)

**Test Requirements:**
- Test resources/list returns resource templates
- Test resources/read for each provider type
- Test resources/subscribe/unsubscribe with SSE push
- Test URI template matching
- Test permission checks on resource access
```

**References:** MCP.md, MCP Specification
**Test Requirements:** Resource listing, reading, subscription tests, permission tests

---

### Task: Fix search_assets Tool Filtering

**Argumentation Summary:** The `search_assets` tool accepts `query` and `mimeType` parameters but doesn't apply them (MCP.md section 11.3). It loads a page without filtering.

**Improvement Summary:** Wire the search parameters to DAO-level filtering using the existing filter infrastructure.

```
Fix the search_assets MCP tool to actually apply query and mimeType filters.

**Current State (from MCP.md section 5.1, 11.3):**
- `search_assets` tool accepts `query` (string) and `mimeType` (string) parameters
- Parameters are accepted but NOT wired to DAO-level filtering
- Tool currently loads a page of assets without applying filters
- Limitation explicitly noted in progress checklist

**Files to Modify:**
1. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/impl/SearchAssetsTool.java` - Implement filtering
2. `loom/db/api/src/main/java/io/metaloom/loom/db/model/asset/AssetDao.java` - Add search methods
3. `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/asset/AssetDaoImpl.java` - Implement search
4. `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/filter/` - Add asset search filters

**Implementation Steps:**
1. Add `searchAssets(query, mimeType, limit, offset)` method to `AssetDao` interface
2. Implement in `AssetDaoImpl` using jOOQ with:
   - Full-text search on filename, initialOrigin, meta fields for `query`
   - MIME type prefix matching for `mimeType` (e.g., `image/*` matches `image/jpeg`)
   - Keyset pagination using existing `loadPage` infrastructure
3. Update `SearchAssetsTool.execute()` to call the new DAO method
4. Return pagination metadata (total count, next cursor) in result
5. Add `FilterKey` constants for asset search (extend `LoomFilterKey`)
6. Update tool descriptor schema to document filter behavior

**References:**
- MCP.md sections 5.1, 11.3
- PERSISTENCE.md (Filter system, CRUDDao, AbstractJooqDao)
- RESTAPI.md (Asset endpoint filtering for reference)

**Test Requirements:**
- Test query parameter filters by filename/origin/meta
- Test mimeType parameter filters by exact and wildcard MIME types
- Test pagination with limit/offset
- Test combined query + mimeType filters
- Test empty results handling
- Test performance with large dataset
```

**References:** MCP.md, PERSISTENCE.md, RESTAPI.md
**Test Requirements:** Filter tests, pagination tests, combined filter tests, performance tests

---

### Task: Implement search_transcript Tool with Elasticsearch/Lucene

**Argumentation Summary:** The `search_transcript` tool is a stub returning placeholder (MCP.md section 5.3, 11.3). Full-text search requires Elasticsearch/Lucene integration.

**Improvement Summary:** Implement full-text transcript search using the existing Elasticsearch/Lucene service modules.

```
Implement the search_transcript MCP tool with full-text search using Elasticsearch/Lucene.

**Current State (from MCP.md sections 5.3, 11.3):**
- `search_transcript` tool returns stub response
- Full-text search requires Elasticsearch/Lucene integration not yet implemented
- Loom has `loom/services/elasticsearch` and `loom/services/lucene` modules

**Files to Modify:**
1. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/impl/SearchTranscriptTool.java` - Implement search
2. `loom/services/elasticsearch/src/main/java/io/metaloom/loom/elasticsearch/` - Add transcript search
3. `loom/services/lucene/src/main/java/io/metaloom/loom/lucene/` - Alternative implementation
4. `loom/db/api/src/main/java/io/metaloom/loom/db/model/asset/AssetDao.java` - Transcript search method

**Implementation Steps:**
1. Determine which search backend to use (Elasticsearch preferred for distributed, Lucene for embedded)
2. Add transcript indexing to pipeline sync (when `syncToLoom` nodes produce transcripts)
3. Create search index mapping for transcripts (asset UUID, transcript text, timestamps)
4. Implement `searchTranscript(query, limit)` in chosen search service
5. Update `SearchTranscriptTool.execute()` to call search service
6. Return matching snippets with asset references and context
7. Add configuration for search backend selection
8. Handle case where search backend is not configured (graceful degradation)

**References:**
- MCP.md sections 5.3, 11.3
- LOOM.md section 2 (elasticsearch, lucene service modules)
- PIPELINE.md (transcript extraction nodes like WhisperNode)
- EVENTBUS.md (pipeline events for indexing triggers)

**Test Requirements:**
- Test transcript search returns relevant snippets
- Test search across multiple assets
- Test limit parameter
- Test with no search backend configured
- Test indexing triggered by pipeline completion
- Test search performance
```

**References:** MCP.md, LOOM.md, PIPELINE.md, EVENTBUS.md
**Test Requirements:** Search relevance tests, multi-asset tests, indexing integration tests

---

### Task: Add Write Operations to MCP Tools

**Argumentation Summary:** MCP tools are currently read-only (search, get, list, stats). No tools for creating/updating/deleting assets, managing pipelines, tags, users, etc.

**Improvement Summary:** Add MCP tools for write operations with proper permission checks and validation.

```
Add write operation MCP tools for asset management, pipeline operations, tags, users, and other domain objects.

**Current State (from MCP.md section 11.3):**
- All 5 tools are read-only: search_assets, get_asset, search_transcript, list_collections, asset_statistics
- No tools for create/update/delete operations
- No tools for pipeline operations, tag management, user/role/group management, embeddings, webhooks, tasks

**Files to Modify:**
1. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/impl/` - New tool implementations
2. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/MCPToolModule.java` - Register new tools
3. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/model/MCPToolDescriptor.java` - Permission declarations

**Implementation Steps:**
1. Design tool set for write operations:
   - `create_asset` - upload/create asset record
   - `update_asset` - update asset metadata
   - `delete_asset` - delete asset
   - `run_pipeline` - trigger pipeline execution
   - `create_tag` / `assign_tag` / `remove_tag` - tag management
   - `create_collection` / `update_collection` / `delete_collection` - collection management
   - `create_user` / `update_user` / `delete_user` - user management
   - `create_webhook` / `update_webhook` / `delete_webhook` - webhook management
2. For each tool:
   - Implement `MCPTool` with proper validation
   - Declare required permissions in descriptor
   - Use DAOs directly (with permission checks in registry)
   - Return structured results with created/updated resource
3. Add permission checks in `MCPToolRegistry.dispatch()`
4. Add audit logging for write operations
5. Update MCP.md progress checklist section 11.3

**References:**
- MCP.md sections 4, 5, 11.3
- RESTAPI.md (CRUD endpoints for reference)
- PERSISTENCE.md (DAO patterns)
- EVENTBUS.md (pipeline run via work orders)

**Test Requirements:**
- Test each write tool with valid permissions
- Test permission denial
- Test validation errors
- Test audit logging
- Test integration with existing data
```

**References:** MCP.md, RESTAPI.md, PERSISTENCE.md, EVENTBUS.md
**Test Requirements:** Write tool tests, permission tests, validation tests, audit tests

---

### Task: Make MCP Port Configurable

**Argumentation Summary:** MCP server port is hardcoded to 4041 (MCP.md section 2.3, 11.7). Should be configurable via LoomOptions like other server ports.

**Improvement Summary:** Add MCP port configuration to ServerOptions and use it in MCPService.

```
Make the MCP server port configurable via LoomOptions.

**Current State (from MCP.md sections 2.3, 8.1, 11.7):**
- Default port: 4041 (hardcoded in `MCPService.DEFAULT_MCP_PORT`)
- When REST port is 0 (test mode), MCP also uses port 0
- Port not configurable via `LoomOptions` (hardcoded)
- Bind address taken from `options().getServer().getBindAddress()`

**Files to Modify:**
1. `loom/api/options/ServerOptions.java` - Add `mcpPort` property
2. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPService.java` - Use configured port
3. `loom/api/options/LoomOptions.java` - Ensure ServerOptions included
4. `config/loom.yml` - Document new setting

**Implementation Steps:**
1. Add `mcpPort` field to `ServerOptions` with default 4041
2. Add environment variable override: `LOOM_SERVER_MCP_PORT`
3. Add `@EnvironmentVariable` annotation and `overrideWithEnv()` handling
4. In `MCPService.start()`, use `options().getServer().getMcpPort()` instead of constant
5. Keep test mode behavior (port 0 when REST port is 0)
6. Update CONFIGURATION.md with new setting
7. Update MCP.md progress checklist section 11.7

**References:**
- MCP.md sections 2.3, 8.1, 11.7
- CONFIGURATION.md (ServerOptions structure)
- LOOM.md section 3.1 (BootstrapInitializer)

**Test Requirements:**
- Test custom port via config file
- Test custom port via environment variable
- Test test mode (port 0) still works
- Test bind address configuration
- Test port conflict handling
```

**References:** MCP.md, CONFIGURATION.md, LOOM.md
**Test Requirements:** Config file port test, env var port test, test mode test, bind address test

---

### Task: Add MCP Health Check and Metrics Endpoints

**Argumentation Summary:** MCP server has no health check endpoint or metrics/observability (MCP.md section 11.7). Needed for production monitoring.

**Improvement Summary:** Add health check endpoint and basic metrics (tool call count, latency, error rates) for MCP server.

```
Add health check endpoint and metrics/observability for the MCP server.

**Current State (from MCP.md section 11.7):**
- No health check endpoint for MCP server
- No metrics/observability (tool call count, latency, error rates)
- No graceful shutdown timeout (server.close() is immediate)

**Files to Modify:**
1. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPService.java` - Add health/metrics routes
2. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/handler/MCPJsonRpcHandler.java` - Instrument tool calls
3. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/MCPToolRegistry.java` - Add metrics collection
4. `loom/services/monitoring` - Integrate with existing monitoring service

**Implementation Steps:**
1. Add `GET /mcp/health` endpoint returning `{ "status": "UP", "tools": N, "uptime": ... }`
2. Add `GET /mcp/metrics` endpoint with:
   - Total tool calls per tool
   - Average latency per tool
   - Error count per tool
   - Active SSE sessions count
   - Active WebSocket connections count
3. Instrument `MCPToolRegistry.dispatch()` with timing and counters
4. Instrument SSE session creation/closure
5. Instrument WebSocket connection creation/closure
6. Add graceful shutdown: wait for in-flight requests (configurable timeout)
7. Integrate with `loom/services/monitoring` if available
8. Update MCP.md progress checklist section 11.7

**References:**
- MCP.md sections 8, 11.7
- LOOM.md section 2 (monitoring service module)
- RESTAPI.md (monitoring port 8989 reserved)

**Test Requirements:**
- Test health endpoint returns correct status
- Test metrics endpoint returns expected counters
- Test metrics increment on tool calls
- Test graceful shutdown waits for in-flight requests
- Test metrics reset on restart
```

**References:** MCP.md, LOOM.md, RESTAPI.md
**Test Requirements:** Health endpoint tests, metrics tests, graceful shutdown tests

---

## GraphQL Tasks

### Task: Register GraphQL Endpoint in EndpointModule

**Argumentation Summary:** GraphQL service is implemented but not registered in `EndpointModule` (GRAPHQL.md section 1.1, LOOM.md section 2). The endpoint exists but is not wired.

**Improvement Summary:** Register the GraphQL endpoint in the Dagger `EndpointModule` so it becomes accessible at `/graphql`.

```
Register the GraphQL endpoint in EndpointModule to make the GraphQL API accessible.

**Current State (from GRAPHQL.md section 1.1, LOOM.md section 2):**
- GraphQL module `loom/services/graphql` is implemented
- Schema loaded from `loom.graphqls`, DataFetchers wired to DAOs
- **Not yet registered in `EndpointModule`** (see LOOM.md)
- GraphQL Java version 25.0

**Files to Modify:**
1. `loom/services/rest/src/main/java/io/metaloom/loom/rest/dagger/EndpointModule.java` - Add GraphQL endpoint
2. `loom/services/graphql/src/main/java/io/metaloom/loom/graphql/LoomGraphQLProvider.java` - Ensure endpoint creation
3. `loom/services/rest/src/main/java/io/metaloom/loom/rest/RESTService.java` - Mount GraphQL route

**Implementation Steps:**
1. In `EndpointModule`, add a provider method for `GraphQLEndpoint` (annotated with `@RESTEndpoints`)
2. Create `GraphQLEndpoint` class implementing `RESTEndpoint` that:
   - Mounts `POST /graphql` route
   - Optionally mounts `GET /graphql` for GraphiQL/Playground (dev only)
   - Uses `LoomGraphQLProvider.graphQL()` for execution
   - Applies authentication (reuse `LoomAuthenticationHandler`)
3. In `RESTService.setupRouter()`, ensure GraphQL route is registered
4. Add CORS configuration for GraphQL endpoint
5. Add GraphQL endpoint to OpenAPI spec (or separate GraphQL introspection)
6. Update GRAPHQL.md progress checklist

**References:**
- GRAPHQL.md sections 1.1, 2
- LOOM.md section 2 (EndpointModule)
- RESTAPI.md (auth, CORS patterns)

**Test Requirements:**
- Test GraphQL endpoint accessible at /graphql
- Test query execution (asset, assets)
- Test authentication required
- Test GraphiQL playground (if enabled)
- Test introspection query
- Test error handling
```

**References:** GRAPHQL.md, LOOM.md, RESTAPI.md
**Test Requirements:** Endpoint accessibility tests, query execution tests, auth tests, playground tests

---

### Task: Implement GraphQL Authentication

**Argumentation Summary:** GraphQL authentication is not yet implemented (GRAPHQL.md section 1.1). Should reuse JWT/OAuth2 infrastructure from REST API.

**Improvement Summary:** Add JWT and OAuth2 authentication to GraphQL endpoint with permission checks on fields.

```
Implement authentication for the GraphQL endpoint using existing JWT/OAuth2 infrastructure.

**Current State (from GRAPHQL.md section 1.1):**
- Authentication: Not yet implemented
- Would need JWT/OAuth2 integration like REST API

**Files to Modify:**
1. `loom/services/graphql/src/main/java/io/metaloom/loom/graphql/LoomGraphQLProvider.java` - Add auth context
2. `loom/services/graphql/src/main/java/io/metaloom/loom/graphql/` - Auth directives
3. `loom/services/auth-common` - Reuse auth utilities

**Implementation Steps:**
1. Add authentication context to GraphQL execution (extract from Vert.x RoutingContext)
2. Implement `@auth` directive for schema fields requiring authentication
3. Implement `@perm(permission)` directive for field-level permission checks
4. Reuse `LoomAuthenticationHandler` for JWT validation
5. Support OAuth2 tokens via Authorization header
6. Add unauthenticated access for public fields (if any)
7. Return GraphQL errors with extensions for auth failures
8. Update GRAPHQL.md with auth implementation details

**References:**
- GRAPHQL.md section 1.1
- RESTAPI.md section 2 (JWT, OAuth2, permissions)
- GraphQL spec: https://spec.graphql.org/

**Test Requirements:**
- Test authenticated query succeeds
- Test unauthenticated query fails with proper error
- Test field-level permission denial
- Test OAuth2 token authentication
- Test public field access without auth
```

**References:** GRAPHQL.md, RESTAPI.md
**Test Requirements:** Auth success/failure tests, field-level permission tests, OAuth2 tests

---

### Task: Extend GraphQL Schema with Mutations and Subscriptions

**Argumentation Summary:** Current GraphQL schema only has Query type with asset/assets fields (GRAPHQL.md section 3.1). No mutations for write operations, no subscriptions for real-time updates.

**Improvement Summary:** Add GraphQL mutations for CRUD operations and subscriptions for real-time pipeline events.

```
Extend the GraphQL schema with mutations for write operations and subscriptions for real-time updates.

**Current State (from GRAPHQL.md section 3.1):**
- Schema only has Query type: `asset(uuid)`, `assets`
- No mutations, no subscriptions
- SDL at `src/main/resources/loom.graphqls`

**Files to Modify:**
1. `loom/services/graphql/src/main/resources/loom.graphqls` - Extend schema
2. `loom/services/graphql/src/main/java/io/metaloom/loom/graphql/LoomGraphQLProvider.java` - Wire mutation/subscription resolvers
3. `loom/services/graphql/src/main/java/io/metaloom/loom/graphql/` - New resolver classes

**Implementation Steps:**
1. Add Mutation type with:
   - `createAsset(input: CreateAssetInput!): Asset`
   - `updateAsset(uuid: ID!, input: UpdateAssetInput!): Asset`
   - `deleteAsset(uuid: ID!): Boolean`
   - `createCollection(input: CreateCollectionInput!): Collection`
   - `runPipeline(uuid: ID!, assetUuids: [ID!]!): PipelineRunResult`
2. Add Subscription type with:
   - `pipelineEvents(pipelineName: String): PipelineEvent`
   - `assetChanges: AssetChange`
3. Implement mutation resolvers calling DAO methods
4. Implement subscription resolvers using Vert.x EventBus or WebSocket bridge
5. Add input types for mutations
6. Add permission checks on mutations
7. Update schema documentation

**References:**
- GRAPHQL.md section 3.1
- RESTAPI.md (CRUD patterns)
- WEBSOCKET.md (pipeline events for subscriptions)
- EVENTBUS.md (event bus for real-time)

**Test Requirements:**
- Test mutation execution with valid input
- Test mutation permission checks
- Test subscription receives events
- Test subscription filtering
- Test error handling in mutations
```

**References:** GRAPHQL.md, RESTAPI.md, WEBSOCKET.md, EVENTBUS.md
**Test Requirements:** Mutation tests, subscription tests, permission tests, error tests

---

## gRPC Tasks

### Task: Implement gRPC Service

**Argumentation Summary:** gRPC service is planned but the GRPC.md file is empty. The module `loom/services/grpc` exists but is commented out in parent POM (LOOM.md section 2).

**Improvement Summary:** Implement gRPC service with protobuf definitions, service implementations, and integration with existing DAOs.

```
Implement the gRPC service for Loom with protobuf definitions and service implementations.

**Current State (from LOOM.md section 2, GRPC.md):**
- Module `loom/services/grpc` exists but commented out in parent POM
- `loom-shared/proto` module for protobuf definitions
- GRPC.md is empty (no specification yet)
- gRPC planned for high-performance internal communication

**Files to Modify:**
1. `loom-shared/proto/src/main/proto/loom.proto` - Define gRPC services and messages
2. `loom/services/grpc/src/main/java/io/metaloom/loom/grpc/` - Service implementations
3. `loom/services/grpc/pom.xml` - Enable module
4. `loom/pom.xml` - Uncomment grpc module
5. `loom/services/rest/src/main/java/io/metaloom/loom/rest/dagger/EndpointModule.java` - Or separate gRPC server

**Implementation Steps:**
1. Define protobuf schema in `loom.proto`:
   - AssetService: GetAsset, ListAssets, CreateAsset, UpdateAsset, DeleteAsset
   - PipelineService: GetPipeline, ListPipelines, RunPipeline
   - CollectionService, UserService, etc.
   - Message types matching REST models
2. Generate Java stubs via protobuf-maven-plugin
3. Implement gRPC service classes extending generated base classes
4. Wire services to DAOs via `DaoCollection`
5. Add gRPC server startup in `BootstrapInitializer` (port 8091 per SERVER.md)
6. Add authentication (reuse JWT validation via metadata)
7. Add health check endpoint (gRPC health protocol)
8. Add reflection service for tooling
9. Document in GRPC.md

**References:**
- LOOM.md section 2 (grpc module, port 8091)
- SERVER.md (grpcPort 8091, GrpcService)
- RESTAPI.md (service patterns)
- CONFIGURATION.md (ServerOptions grpcPort)

**Test Requirements:**
- Test gRPC service startup on port 8091
- Test each service method (Get, List, Create, Update, Delete)
- Test authentication via metadata
- Test health check endpoint
- Test reflection service
- Test integration with DAOs
```

**References:** LOOM.md, SERVER.md, RESTAPI.md, CONFIGURATION.md
**Test Requirements:** Service startup tests, method tests, auth tests, health check tests

---

## Persistence Tasks

### Task: Add Soft Delete Support for All Entities

**Argumentation Summary:** Some DAOs (e.g., `UserDaoImpl`) override `load()` to filter `deleted = false` but this is not consistent across all entities. A unified soft delete pattern would improve data safety.

**Improvement Summary:** Implement consistent soft delete pattern across all DAOs with a `deleted` column, filtered index, and unified `load()`/`findAll()` behavior.

```
Implement consistent soft delete pattern across all DAOs.

**Current State (from PERSISTENCE.md):**
- `UserDaoImpl` overrides `load()` to filter `deleted = false`
- Other DAOs may not have soft delete
- No unified pattern documented

**Files to Modify:**
1. `loom/db/flyway/src/main/resources/db/migration/` - Add `deleted` column to all tables
2. `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/*/AbstractJooqDao.java` - Add soft delete logic
3. `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/*/*DaoImpl.java` - Override load/findAll
4. `loom/db/api/src/main/java/io/metaloom/loom/db/model/*/Element.java` - Add deleted field

**Implementation Steps:**
1. Add `deleted BOOLEAN NOT NULL DEFAULT false` column to all entity tables via Flyway migration
2. Add partial index `WHERE deleted = false` for performance
3. Add `deleted` field to `CUDElement` / `AbstractEditableElement`
4. In `AbstractJooqDao`:
   - Override `load()` to add `AND deleted = false`
   - Override `findAll()` to add `WHERE deleted = false`
   - Override `loadPage()` to add `AND deleted = false`
   - Add `hardDelete(UUID id)` for actual deletion
   - Add `softDelete(UUID id)` setting `deleted = true`
   - Keep `delete(UUID id)` as soft delete (or configurable)
5. Update all concrete DAOs to inherit behavior
6. Add `includeDeleted` parameter option for admin queries
7. Update PERSISTENCE.md with soft delete pattern

**References:**
- PERSISTENCE.md (DAO pattern, AbstractJooqDao, UserDaoImpl example)
- LOOM.md (module layout)

**Test Requirements:**
- Test soft delete marks deleted=true
- Test load/findAll/loadPage exclude deleted by default
- Test hardDelete removes row
- Test includeDeleted option works
- Test partial index performance
- Test all entity types
```

**References:** PERSISTENCE.md, LOOM.md
**Test Requirements:** Soft delete tests, query filtering tests, hard delete tests, performance tests

---

### Task: Implement SQL Aggregate Queries for asset_statistics

**Argumentation Summary:** The `asset_statistics` MCP tool loads up to 10,000 assets in memory and aggregates (MCP.md section 5.5, 11.3). Should use SQL aggregate queries for performance.

**Improvement Summary:** Replace in-memory aggregation with SQL COUNT/SUM queries grouped by MIME type category.

```
Replace in-memory aggregation in asset_statistics tool with SQL aggregate queries.

**Current State (from MCP.md sections 5.5, 11.3):**
- `asset_statistics` tool loads up to 10,000 assets in memory
- Aggregates counts by MIME type and total storage in Java
- Should use SQL aggregate queries for performance
- `collection` parameter accepted but not used for scoping

**Files to Modify:**
1. `loom/db/api/src/main/java/io/metaloom/loom/db/model/asset/AssetDao.java` - Add statistics method
2. `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/asset/AssetDaoImpl.java` - Implement SQL aggregates
3. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/impl/AssetStatisticsTool.java` - Use new DAO method

**Implementation Steps:**
1. Add `getAssetStatistics(collectionUuid)` method to `AssetDao` interface
2. Implement in `AssetDaoImpl` using jOOQ:
   - `SELECT COUNT(*), SUM(size) FROM asset WHERE deleted = false`
   - `SELECT mimeType, COUNT(*), SUM(size) FROM asset WHERE deleted = false GROUP BY mimeType`
   - Add optional `AND collection_uuid = ?` for scoping
   - Map MIME types to categories (image, video, audio, document, other)
3. Return structured result with totals and per-category breakdown
4. Update `AssetStatisticsTool` to call new DAO method
5. Handle case where collection doesn't exist
6. Add performance test with large dataset

**References:**
- MCP.md sections 5.5, 11.3
- PERSISTENCE.md (jOOQ patterns, AbstractJooqDao)
- RESTAPI.md (asset statistics endpoint if exists)

**Test Requirements:**
- Test statistics match in-memory calculation
- Test collection scoping
- Test performance with 100k+ assets
- Test MIME type categorization
- Test empty database
```

**References:** MCP.md, PERSISTENCE.md
**Test Requirements:** Accuracy tests, scoping tests, performance tests, categorization tests

---

### Task: Add Database Connection Pool Metrics

**Argumentation Summary:** No visibility into database connection pool health (usage, wait times, leaks). Needed for production monitoring.

**Improvement Summary:** Expose HikariCP metrics via monitoring endpoint and/or Micrometer.

```
Add database connection pool metrics exposure for monitoring.

**Current State (from CONFIGURATION.md, PERSISTENCE.md):**
- Database pool configured via DatabaseOptions (minPoolSize, maxPoolSize, acquireIncrement)
- No metrics exposure documented
- Monitoring port 8989 reserved (SERVER.md)

**Files to Modify:**
1. `loom/common/src/main/java/io/metaloom/loom/common/dagger/VertxModule.java` - Or database module
2. `loom/services/monitoring/src/main/java/io/metaloom/loom/monitoring/` - Metrics collection
3. `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/` - Pool access

**Implementation Steps:**
1. Expose HikariCP `HikariDataSource` metrics:
   - Active connections
   - Idle connections
   - Pending threads (waiting for connection)
   - Connection creation rate
   - Connection timeout rate
   - Max pool size, min pool size
2. Register metrics with Micrometer (if used) or custom metrics registry
3. Expose via `GET /api/v1/monitoring/db-pool` or monitoring port 8989
4. Add health check: pool exhaustion detection
5. Add alerting thresholds (configurable)
6. Document in CONFIGURATION.md and monitoring docs

**References:**
- CONFIGURATION.md (DatabaseOptions)
- SERVER.md (monitoringPort 8989)
- LOOM.md (monitoring service module)
- PERSISTENCE.md (database layer)

**Test Requirements:**
- Test metrics accuracy under load
- Test pool exhaustion detection
- Test metrics endpoint response
- Test configuration of thresholds
```

**References:** CONFIGURATION.md, SERVER.md, LOOM.md, PERSISTENCE.md
**Test Requirements:** Metrics accuracy tests, exhaustion detection tests, endpoint tests

---

## Pipeline Tasks

### Task: Implement Pipeline Dry-Run Mode for All Nodes

**Argumentation Summary:** Dry-run mode exists (PIPELINE.md section 3.1, 5.1) but only skips node execution. Should also validate pipeline structure, check dependencies, and simulate results without side effects.

**Improvement Summary:** Enhance dry-run mode to perform full pipeline validation and simulation without executing actual node logic.

```
Enhance pipeline dry-run mode to validate and simulate without side effects.

**Current State (from PIPELINE.md sections 3.1, 5.1, 5.3):**
- `Pipeline.isDryRun()` flag exists
- `ReactivePipelineExecutor` returns `NodeResult.skipped(id, "dry-run")` for all nodes
- No validation of pipeline structure in dry-run
- No simulation of expected outputs

**Files to Modify:**
1. `cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/executor/ReactivePipelineExecutor.java` - Enhance dry-run
2. `cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/Pipeline.java` - Validation methods
3. `cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/node/AbstractPipelineNode.java` - Dry-run simulation

**Implementation Steps:**
1. In dry-run mode:
   - Validate pipeline DAG (cycle detection, reachability from source)
   - Validate all node IDs match pattern
   - Validate dependencies exist
   - Validate conditional dependencies reference valid filter branches
   - Check all nodes have required options configured
2. Simulate execution:
   - For each node, generate mock `NodeResult` with expected output keys
   - Use node's `options()` to determine expected outputs
   - Track simulated timing (zero duration)
   - Emit tracking events with `dryRun: true` flag
3. Return `PipelineResult` with `dryRun = true` and simulated node results
4. Add dry-run specific validation errors to result
5. Update PIPELINE.md with enhanced dry-run behavior

**References:**
- PIPELINE.md sections 3.1, 3.2, 5.1, 5.3
- PIPELINE.md section 11.1 (test patterns)

**Test Requirements:**
- Test dry-run validates cycle detection
- Test dry-run validates missing dependencies
- Test dry-run simulates expected outputs
- Test dry-run emits tracking events
- Test dry-run returns success for valid pipeline
- Test dry-run returns validation errors for invalid pipeline
```

**References:** PIPELINE.md
**Test Requirements:** Validation tests, simulation tests, tracking event tests, error tests

---

### Task: Add Pipeline Versioning and Rollback

**Argumentation Summary:** Pipeline definitions are stored as JSON in database (PIPELINE.md section 9.1) but no versioning or rollback mechanism exists.

**Improvement Summary:** Add version history for pipeline definitions with ability to rollback to previous versions.

```
Add version history and rollback capability for pipeline definitions.

**Current State (from PIPELINE.md section 9.1):**
- Pipeline table has `definition JSONB` column
- No version history tracked
- No rollback mechanism

**Files to Modify:**
1. `loom/db/flyway/src/main/resources/db/migration/` - Add pipeline_version table
2. `loom/db/api/src/main/java/io/metaloom/loom/db/model/pipeline/PipelineDao.java` - Version methods
3. `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/pipeline/PipelineDaoImpl.java` - Implement versioning
4. `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEndpoint.java` - Version endpoints
5. `loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/` - Version DTOs

**Implementation Steps:**
1. Create `pipeline_version` table:
   - `uuid`, `pipeline_uuid`, `version`, `definition`, `created`, `creator_uuid`, `comment`
2. On pipeline create/update, automatically create version entry
3. Add `PipelineDao` methods:
   - `listVersions(pipelineUuid)`
   - `loadVersion(pipelineUuid, version)`
   - `rollback(pipelineUuid, version)`
4. Add REST endpoints:
   - `GET /api/v1/pipelines/:uuid/versions` - List versions
   - `GET /api/v1/pipelines/:uuid/versions/:version` - Load version
   - `POST /api/v1/pipelines/:uuid/rollback` - Rollback to version
5. Add permission: `READ_PIPELINE_VERSION`, `ROLLBACK_PIPELINE`
6. Update PipelineModel to include current version number
7. Update PIPELINE.md section 9.1

**References:**
- PIPELINE.md section 9.1 (database schema)
- PERSISTENCE.md (DAO patterns)
- RESTAPI.md (CRUD patterns)

**Test Requirements:**
- Test version created on pipeline create/update
- Test list versions returns history
- Test load version returns correct definition
- Test rollback restores previous definition
- Test permissions on version endpoints
- Test rollback creates new version entry
```

**References:** PIPELINE.md, PERSISTENCE.md, RESTAPI.md
**Test Requirements:** Version creation tests, list/load tests, rollback tests, permission tests

---

### Task: Implement Pipeline Scheduling and Cron Triggers

**Argumentation Summary:** Pipelines are currently triggered manually via `POST /api/v1/pipelines/:uuid/run` (PIPELINE.md section 9.4). No scheduling or cron-based triggers.

**Improvement Summary:** Add pipeline scheduling with cron expressions, interval triggers, and event-based triggers (e.g., on asset upload).

```
Implement pipeline scheduling with cron triggers, intervals, and event-based triggers.

**Current State (from PIPELINE.md section 9.4, 9.5):**
- Pipeline run triggered manually via REST `POST /api/v1/pipelines/:uuid/run`
- Work order dispatched to processor via WebSocket
- No scheduling mechanism

**Files to Modify:**
1. `loom/db/flyway/src/main/resources/db/migration/` - Add pipeline_schedule table
2. `loom/db/api/src/main/java/io/metaloom/loom/db/model/pipeline/` - Schedule DAO
3. `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEndpoint.java` - Schedule endpoints
4. `loom/core/src/main/java/io/metaloom/loom/core/` - Scheduler service
5. `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/` - Schedule service

**Implementation Steps:**
1. Create `pipeline_schedule` table:
   - `uuid`, `pipeline_uuid`, `cron_expression`, `interval_seconds`, `trigger_type` (CRON, INTERVAL, EVENT), `event_filter`, `enabled`, `next_run`
2. Implement scheduler service (Quartz or custom Vert.x timer):
   - On startup, load enabled schedules
   - For CRON: evaluate next run time, set timer
   - For INTERVAL: periodic timer
   - For EVENT: listen to asset upload events
3. When trigger fires, dispatch `WORK_ORDER` via `ProcessorRegistry`
4. Add REST endpoints for schedule CRUD
5. Add `PipelineScheduleModel` DTO
6. Handle scheduler clustering (if multiple Loom instances)
7. Update PIPELINE.md with scheduling architecture

**References:**
- PIPELINE.md sections 9.4, 9.5
- EVENTBUS.md (event bus for triggers)
- WEBSOCKET.md (work order dispatch)
- LOOM.md (clustering considerations)

**Test Requirements:**
- Test CRON schedule triggers at correct times
- Test INTERVAL schedule triggers periodically
- Test EVENT schedule triggers on asset upload
- Test schedule enable/disable
- Test clustering (single execution per trigger)
- Test next_run calculation
```

**References:** PIPELINE.md, EVENTBUS.md, WEBSOCKET.md, LOOM.md
**Test Requirements:** Cron tests, interval tests, event tests, clustering tests

---

### Task: Add Pipeline Node Metrics and Profiling

**Argumentation Summary:** Pipeline execution has tracking events but no detailed metrics on node performance (latency percentiles, throughput, resource usage).

**Improvement Summary:** Add per-node metrics collection with percentiles, histograms, and resource usage tracking.

```
Add detailed metrics and profiling for pipeline node execution.

**Current State (from PIPELINE.md, EVENTBUS.md):**
- Tracking events: NODE_STARTED, NODE_COMPLETED, NODE_FAILED, NODE_STATS
- NODE_STATS has activeCount, pendingCount, processedCount, failedCount
- No latency percentiles, no resource usage, no histograms

**Files to Modify:**
1. `cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/executor/ReactivePipelineExecutor.java` - Metrics collection
2. `cortex/pipeline-common/src/main/java/io/metaloom/cortex/pipeline/common/event/DefaultPipelineEventBus.java` - Metric events
3. `loom/services/monitoring/src/main/java/io/metaloom/loom/monitoring/` - Metrics exposure
4. `cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/event/` - Metric event types

**Implementation Steps:**
1. Add metric collection in `ReactivePipelineExecutor`:
   - Per-node latency histogram (HdrHistogram or similar)
   - Percentiles: p50, p95, p99, max
   - Throughput: items/sec per node
   - Resource usage: CPU, memory (if available)
   - Queue depth over time
2. Add `NODE_METRICS` tracking event type with metric snapshot
3. Periodically emit metrics (configurable interval, default 30s)
4. Expose metrics via:
   - MCP tool: `get_pipeline_metrics`
   - REST endpoint: `GET /api/v1/pipelines/:uuid/metrics`
   - Monitoring port 8989
5. Add metric retention and aggregation
6. Update PIPELINE.md and EVENTBUS.md

**References:**
- PIPELINE.md sections 3.9, 5.1
- EVENTBUS.md sections 3.2, 3.3
- SERVER.md (monitoringPort 8989)
- LOOM.md (monitoring service)

**Test Requirements:**
- Test latency histogram accuracy
- Test percentile calculations
- Test throughput measurement
- Test metrics emission interval
- Test metrics exposure via REST/MCP
- Test low overhead (<5% performance impact)
```

**References:** PIPELINE.md, EVENTBUS.md, SERVER.md, LOOM.md
**Test Requirements:** Histogram tests, percentile tests, throughput tests, overhead tests

---

## EventBus Tasks

### Task: Implement Vert.x EventBus Clustering for Pipeline Events

**Argumentation Summary:** Pipeline events use in-process `DefaultPipelineEventBus` and WebSocket fan-out. For multi-instance Loom deployments, events need to be clustered via Vert.x EventBus.

**Improvement Summary:** Add Vert.x EventBus clustering support for pipeline events to enable multi-instance deployments.

```
Implement Vert.x EventBus clustering for pipeline events to support multi-instance Loom deployments.

**Current State (from EVENTBUS.md section 1):**
- Two independent event systems:
  - Cortex Pipeline Event Bus (in-process, no Vert.x EventBus)
  - Vert.x EventBus (MCP tool dispatch only)
  - WebSocket fan-out (raw ServerWebSocket)
- `loom/services/eventbus` module is empty placeholder
- No clustering for pipeline events

**Files to Modify:**
1. `loom/services/eventbus/src/main/java/io/metaloom/loom/eventbus/` - New implementation
2. `cortex/pipeline-common/src/main/java/io/metaloom/cortex/pipeline/common/event/DefaultPipelineEventBus.java` - Clustered variant
3. `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEventBroadcaster.java` - Cluster awareness
4. `loom/common/src/main/java/io/metaloom/loom/common/dagger/VertxModule.java` - Cluster config

**Implementation Steps:**
1. Configure Vert.x clustering (Hazelcast or built-in):
   - Add `vertx.clustered()` in `VertxModule`
   - Configuration via `LoomOptions` (cluster enabled, hosts, ports)
2. Create `ClusteredPipelineEventBus` implementing `PipelineEventBus`:
   - Publish to Vert.x EventBus address `loom.pipeline.events`
   - Subscribe local handlers + remote consumers
   - Serialize events as JSON
3. Modify `PipelineEventBroadcaster`:
   - On clustered deployment, receive events from Vert.x EventBus
   - Fan out to local WebSocket subscribers
   - Deduplicate events (same event may arrive from multiple nodes)
4. Update `LoomControlChannel` (cortex side):
   - Option to send events via Vert.x EventBus instead of WebSocket
   - Or keep WebSocket to single Loom instance, that instance clusters
5. Add cluster membership detection
6. Update EVENTBUS.md with clustering architecture

**References:**
- EVENTBUS.md sections 1, 2, 3.5, 3.7
- LOOM.md (clustering, Vert.x module)
- VERTX clustering docs

**Test Requirements:**
- Test event propagation across cluster nodes
- Test WebSocket fan-out on each node
- Test deduplication
- Test cluster membership changes
- Test event ordering guarantees
```

**References:** EVENTBUS.md, LOOM.md
**Test Requirements:** Cluster propagation tests, fan-out tests, deduplication tests, membership tests

---

### Task: Add Event Replay and Persistence

**Argumentation Summary:** Pipeline events are only delivered to live WebSocket subscribers. No persistence or replay capability for clients that connect late.

**Improvement Summary:** Add event persistence with replay capability for pipeline events.

```
Add event persistence and replay capability for pipeline events.

**Current State (from EVENTBUS.md, WEBSOCKET.md):**
- Events only delivered to live WebSocket subscribers
- No persistence
- Late-joining clients miss historical events
- Backpressure drops events when queue full

**Files to Modify:**
1. `loom/db/flyway/src/main/resources/db/migration/` - Add pipeline_event table
2. `loom/db/api/src/main/java/io/metaloom/loom/db/model/pipeline/` - Event DAO
3. `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEventEndpoint.java` - Replay endpoint
4. `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEventBroadcaster.java` - Persist on broadcast

**Implementation Steps:**
1. Create `pipeline_event` table:
   - `uuid`, `pipeline_name`, `node_id`, `type`, `media_path`, `timestamp`, `duration_ms`, `message`, `stats_json`
   - Partition by time (monthly) for retention
2. In `PipelineEventBroadcaster.broadcast()`, persist event before fan-out
3. Add REST endpoint: `GET /api/v1/pipelines/events?since=<timestamp>&pipeline=<name>&limit=100`
4. Add WebSocket replay: on connect, send events since `?since=` parameter
5. Add retention policy (configurable, default 30 days)
6. Add background cleanup job
7. Update EVENTBUS.md and WEBSOCKET.md

**References:**
- EVENTBUS.md section 3.5 (broadcaster)
- WEBSOCKET.md section 4.8 (connection lifecycle)
- PERSISTENCE.md (DAO patterns)
- RESTAPI.md (list endpoint patterns)

**Test Requirements:**
- Test event persistence on broadcast
- Test replay endpoint returns events since timestamp
- Test WebSocket replay on connect
- Test retention cleanup
- Test query performance with large event volume
- Test partition pruning
```

**References:** EVENTBUS.md, WEBSOCKET.md, PERSISTENCE.md, RESTAPI.md
**Test Requirements:** Persistence tests, replay endpoint tests, WebSocket replay tests, retention tests

---

## Configuration Tasks

### Task: Add Configuration Validation at Startup

**Argumentation Summary:** Configuration is loaded from multiple sources with priority (CONFIGURATION.md) but no validation occurs. Invalid config causes runtime failures.

**Improvement Summary:** Add configuration validation at startup with clear error messages for missing/invalid required settings.

```
Add configuration validation at startup with clear error messages.

**Current State (from CONFIGURATION.md):**
- Configuration loaded from 5 priority sources
- No validation documented
- Invalid config causes runtime failures later

**Files to Modify:**
1. `loom/api/options/LoomOptionsLoader.java` - Add validation
2. `loom/api/options/*.java` - Add validation annotations/methods
3. `loom/core/src/main/java/io/metaloom/loom/core/boot/BootstrapInitializer.java` - Validate early

**Implementation Steps:**
1. Add `validate()` method to each Options class:
   - `DatabaseOptions`: validate host, port, credentials, pool sizes
   - `ServerOptions`: validate ports (1-65535), bind address format
   - `AuthenticationOptions`: validate keystore path, token expiration > 0
   - `OAuth2Options`: if enabled, validate all URLs, client ID/secret
2. In `LoomOptionsLoader`, call validation after loading/merging
3. Collect all validation errors, throw single exception with all issues
4. Add `--validate-config` CLI flag to validate and exit
5. Document required vs optional settings in CONFIGURATION.md
6. Add environment variable validation (e.g., `LOOM_DB_PASSWORD` not empty in prod)

**References:**
- CONFIGURATION.md (loading priority, options structure)
- LOOM.md (BootstrapInitializer)
- SERVER.md (server options)

**Test Requirements:**
- Test validation catches missing required fields
- Test validation catches invalid values (port 0, negative pool size)
- Test validation catches invalid OAuth2 config when enabled
- Test all errors reported together
- Test CLI validate flag
```

**References:** CONFIGURATION.md, LOOM.md, SERVER.md
**Test Requirements:** Validation error tests, CLI flag tests, comprehensive error reporting tests

---

### Task: Add Configuration Hot Reload

**Argumentation Summary:** Configuration changes require server restart. Hot reload would allow updating certain settings without downtime.

**Improvement Summary:** Implement hot reload for non-critical configuration (log levels, feature flags, rate limits) with change notifications.

```
Implement configuration hot reload for non-critical settings.

**Current State (from CONFIGURATION.md, LOOM.md):**
- Configuration loaded at startup
- Changes require restart
- No hot reload mechanism

**Files to Modifiy:**
1. `loom/api/options/LoomOptionsLoader.java` - File watching
2. `loom/core/src/main/java/io/metaloom/loom/core/LoomImpl.java` - Reload trigger
3. `loom/services/rest/src/main/java/io/metaloom/loom/rest/RESTService.java` - Dynamic config
4. `loom/common/src/main/java/io/metaloom/loom/common/dagger/` - Dagger limitations

**Implementation Steps:**
1. Identify hot-reloadable settings:
   - Log levels
   - Feature flags
   - Rate limits
   - Cache TTLs
   - Non-structural settings (not ports, DB credentials, auth keys)
2. Add file watcher on config file (using Vert.x `FileSystem` or `WatchService`)
3. On change, re-load and validate configuration
4. For reloadable settings, update in-memory config and notify services
5. Use Vert.x EventBus to broadcast `config.changed` events
6. Services listen and update their behavior
7. Add `POST /api/v1/admin/config/reload` endpoint for manual trigger
8. Document which settings support hot reload
9. Handle Dagger singleton limitations (may need non-Dagger config holder)

**References:**
- CONFIGURATION.md
- LOOM.md (Dagger DI, BootstrapInitializer)
- RESTAPI.md (admin endpoints)

**Test Requirements:**
- Test hot reload updates log levels
- Test hot reload updates feature flags
- Test non-reloadable settings require restart
- Test manual reload endpoint
- Test config change event broadcast
- Test validation on reload
```

**References:** CONFIGURATION.md, LOOM.md, RESTAPI.md
**Test Requirements:** Hot reload tests, non-reloadable tests, event broadcast tests, validation tests

---

## Build Tasks

### Task: Add Build Pipeline Health Checks

**Argumentation Summary:** Build process (BUILD.md) produces multiple artifacts but no health checks or validation of produced artifacts (JARs, native binaries, containers).

**Improvement Summary:** Add post-build validation: JAR structure, native binary execution, container image tests.

```
Add post-build validation and health checks for all build artifacts.

**Current State (from BUILD.md):**
- Build produces: JVM JARs, native binaries, container images, UI assets
- No automated validation of artifacts
- Manual verification required

**Files to Modify:**
1. `build.sh` - Add validation steps
2. `loom/containers/build-containers.sh` - Add image validation
3. `loom/containers/demo/Containerfile*` - Health check endpoints
4. `loom/containers/server/Containerfile*` - Health check endpoints
5. `cortex/container/build-container.sh` - Add validation

**Implementation Steps:**
1. JVM JAR validation:
   - Verify `MANIFEST.MF` has Main-Class
   - Verify all dependencies included (shaded)
   - Run `java -cp loom-demo.jar -version` quick test
2. Native binary validation:
   - Verify binary executes: `./loom-demo --version`
   - Verify dynamic linking: `ldd loom-demo`
   - Test basic startup (exit code 0)
3. Container image validation:
   - Add `HEALTHCHECK` to Containerfiles
   - Run container and test health endpoint
   - Verify UI assets included: `curl /ui/index.html`
   - Verify non-root user (UID 1000)
4. UI build validation:
   - Verify `loom-ui/build/index.html` exists
   - Verify no TypeScript errors in build output
   - Verify source maps generated (if configured)
5. Cortex container validation:
   - Verify CUDA libraries present
   - Verify OpenCV JNI libraries
   - Verify InspireFace model downloaded
6. Add validation to `build.sh` with `--validate` flag
7. Fail build if validation fails
8. Update BUILD.md with validation steps

**References:**
- BUILD.md (all build stages)
- LOOM.md (container modules)

**Test Requirements:**
- Test JAR validation catches missing Main-Class
- Test native binary validation catches linking issues
- Test container health checks pass
- Test UI asset inclusion
- Test validation fails build on error
```

**References:** BUILD.md, LOOM.md
**Test Requirements:** JAR validation tests, native validation tests, container health tests, UI validation tests

---

### Task: Add Build Reproducibility Verification

**Argumentation Summary:** No verification that builds are reproducible (same source = same artifacts). Important for supply chain security.

**Improvement Summary:** Add reproducible build verification by building twice and comparing artifact hashes.

```
Add reproducible build verification to the build pipeline.

**Current State (from BUILD.md):**
- No reproducibility verification
- Build timestamps, non-deterministic ordering may cause differences

**Files to Modify:**
1. `build.sh` - Add reproducibility check
2. `loom/containers/build-containers.sh` - Deterministic image builds
3. `loom/pom.xml` - Reproducible Maven build config
4. `loom-ui/package.json` - Reproducible npm build

**Implementation Steps:**
1. Maven reproducible build:
   - Add `<useDefaultManifestFile>true</useDefaultManifestFile>` to shade plugin
   - Set `project.build.outputTimestamp` to fixed value
   - Use `maven-enforcer-plugin` to ban non-deterministic plugins
2. Native image reproducibility:
   - GraalVM `native-image` with `--reproducible` flag (if available)
   - Fixed build timestamp via `SOURCE_DATE_EPOCH`
3. Container image reproducibility:
   - Use `--build-arg SOURCE_DATE_EPOCH` in Containerfiles
   - Pin base image digests (not tags)
   - Sort layers deterministically
4. UI build reproducibility:
   - Set `SOURCE_DATE_EPOCH` for Vite
   - Disable timestamps in output
5. Verification script:
   - Build twice in clean environments
   - Compare SHA256 of all artifacts
   - Report differences
6. Add to CI pipeline
7. Update BUILD.md with reproducibility requirements

**References:**
- BUILD.md (build stages, container build)
- Reproducible Builds: https://reproducible-builds.org/

**Test Requirements:**
- Test double build produces identical JARs
- Test double build produces identical native binaries
- Test double build produces identical container images (digest)
- Test double build produces identical UI assets
- Test CI integration
```

**References:** BUILD.md
**Test Requirements:** Double build comparison tests for all artifact types

---

## Security Tasks

### Task: Implement CVE Scanning in Build Pipeline

**Argumentation Summary:** No CVE scanning in build pipeline (BUILD.md). Dependencies should be scanned for vulnerabilities.

**Improvement Summary:** Integrate CVE scanning (OWASP Dependency Check, Trivy, or GitHub Advisory) into Maven and container build.

```
Integrate CVE scanning into the build pipeline for dependencies and container images.

**Current State (from BUILD.md, LOOM.md):**
- No CVE scanning documented
- Maven dependencies not scanned
- Container images not scanned
- `cve-remediation` skill exists but not integrated

**Files to Modify:**
1. `build.sh` - Add CVE scan steps
2. `loom/pom.xml` - Add dependency check plugin
3. `loom/containers/build-containers.sh` - Add container scan
4. `loom/containers/demo/Containerfile*` - Minimal base images
5. `loom/containers/server/Containerfile*` - Minimal base images

**Implementation Steps:**
1. Maven dependency scanning:
   - Add `owasp/dependency-check-maven` plugin
   - Configure to fail on CVSS >= 7 (critical/high)
   - Generate HTML/JSON report
   - Run in `build.sh` before packaging
2. Container image scanning:
   - Integrate Trivy or Grype in `build-containers.sh`
   - Scan built images before tagging
   - Fail on critical/high vulnerabilities in base image
   - Use distroless/minimal base images where possible
3. Base image hardening:
   - Use `eclipse-temurin:25-jre-alpine` (minimal)
   - Use `debian:stable-slim` (not full debian)
   - Remove package managers, shells from final images
4. SBOM generation:
   - Generate CycloneDX SBOM for Maven build
   - Generate SPDX SBOM for container images
5. Add CVE scan results to build artifacts
6. Update BUILD.md with security scanning steps

**References:**
- BUILD.md (build pipeline)
- cve-remediation skill (if available)
- OWASP Dependency Check
- Trivy/Grype documentation

**Test Requirements:**
- Test dependency check fails on vulnerable dependency
- Test container scan fails on vulnerable base image
- Test SBOM generation
- Test minimal base images reduce attack surface
- Test false positive handling
```

**References:** BUILD.md, cve-remediation skill
**Test Requirements:** Dependency scan tests, container scan tests, SBOM tests, base image tests

---

## Testing Tasks

### Task: Add Contract Testing for REST API

**Argumentation Summary:** REST API has OpenAPI spec but no contract testing to ensure implementation matches spec.

**Improvement Summary:** Add contract testing (Pact, Spring Cloud Contract, or custom) to validate REST endpoints against OpenAPI spec.

```
Add contract testing for REST API endpoints against OpenAPI specification.

**Current State (from RESTAPI.md, BUILD.md):**
- OpenAPI spec generated at `/api/v1/openapi`
- No contract testing documented
- Tests use real database (integration tests)

**Files to Modify:**
1. `loom/services/rest/src/test/java/io/metaloom/loom/rest/` - Contract tests
2. `loom-shared/rest-model/src/test/` - Model validation tests
3. `build.sh` - Add contract test phase

**Implementation Steps:**
1. Choose contract testing approach:
   - Option A: Custom test reading OpenAPI spec and validating responses
   - Option B: Pact consumer-driven contracts
   - Option C: Spring Cloud Contract (if Spring used - not the case)
2. Implement test suite that:
   - Loads OpenAPI spec from running server or generated file
   - For each endpoint, sends valid request
   - Validates response status, headers, body schema
   - Tests error responses (400, 403, 404, 500)
   - Tests all query parameter combinations
3. Run contract tests in CI after unit tests
4. Fail build on contract violation
5. Add contract test for each new endpoint (enforce via PR template)

**References:**
- RESTAPI.md (OpenAPI, endpoints, error codes)
- BUILD.md (test setup)
- PERSISTENCE.md (test fixtures)

**Test Requirements:**
- Test all endpoints match OpenAPI spec
- Test error response formats
- Test query parameter validation
- Test contract test fails on spec mismatch
- Test CI integration
```

**References:** RESTAPI.md, BUILD.md, PERSISTENCE.md
**Test Requirements:** Endpoint contract tests, error format tests, query param tests, CI tests

---

### Task: Add Chaos Engineering Tests

**Argumentation Summary:** No resilience testing for network partitions, database failures, processor disconnections, etc.

**Improvement Summary:** Add chaos engineering tests using TestContainers and Toxiproxy for failure injection.

```
Add chaos engineering tests for system resilience validation.

**Current State (from BUILD.md, LOOM.md):**
- Unit tests, integration tests, e2e tests exist
- No chaos/resilience testing documented
- TestContainers used for database

**Files to Modify:**
1. `integration-test/` or `e2e-test/` modules - Chaos tests
2. `build.sh` - Add chaos test phase
3. `loom/services/rest/src/test/` - Failure injection tests

**Implementation Steps:**
1. Add Toxiproxy TestContainer for network failure injection
2. Test scenarios:
   - Database connection failure (timeout, connection refused)
   - Database query timeout
   - Processor WebSocket disconnect during pipeline
   - Processor WebSocket slow response
   - MCP server unavailable
   - gRPC server unavailable (when implemented)
   - High latency on all external calls
   - Partial network partition
3. For each scenario:
   - Verify graceful degradation
   - Verify error responses (503, 504, etc.)
   - Verify recovery after failure resolves
   - Verify no data corruption
4. Add chaos test suite to CI (nightly)
5. Document expected behavior in each scenario

**References:**
- BUILD.md (integration tests, TestContainers)
- LOOM.md (service modules)
- EVENTBUS.md (processor WebSocket)
- WEBSOCKET.md (connection lifecycle)

**Test Requirements:**
- Test each failure scenario
- Test graceful degradation
- Test recovery
- Test no data corruption
- Test CI integration (nightly)
```

**References:** BUILD.md, LOOM.md, EVENTBUS.md, WEBSOCKET.md
**Test Requirements:** Failure scenario tests, degradation tests, recovery tests, data integrity tests

---

## Documentation Tasks

### Task: Generate API Documentation from OpenAPI Spec

**Argumentation Summary:** OpenAPI spec exists but no user-friendly API documentation (like Swagger UI, Redoc, or generated markdown).

**Improvement Summary:** Add Swagger UI/Redoc endpoint and/or generate markdown documentation from OpenAPI spec.

```
Generate user-friendly API documentation from OpenAPI specification.

**Current State (from RESTAPI.md section 1.7):**
- OpenAPI spec served at `/api/v1/openapi` (YAML)
- No Swagger UI, Redoc, or generated documentation
- API info at `/api/v1`

**Files to Modify:**
1. `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/RESTInfoEndpoint.java` - Add UI
2. `loom/services/rest/src/main/resources/` - Static Swagger UI/Redoc assets
3. `build.sh` - Generate markdown docs

**Implementation Steps:**
1. Add Swagger UI:
   - Serve static Swagger UI assets from classpath
   - Mount at `/api/v1/docs` or `/api/v1/swagger`
   - Configure to load `/api/v1/openapi`
2. Add Redoc alternative:
   - Mount at `/api/v1/redoc`
   - Better for reading, less interactive
3. Generate markdown documentation:
   - Use `openapi-generator` or custom script
   - Generate `docs/api/rest-api.md` in build
   - Include in website/docs
4. Add to `build.sh` documentation generation step
5. Update RESTAPI.md with documentation links

**References:**
- RESTAPI.md section 1.7
- BUILD.md (UI build, doc module)
- LOOM.md (doc module)

**Test Requirements:**
- Test Swagger UI loads and shows endpoints
- Test Redoc loads and renders spec
- Test markdown generation produces valid docs
- Test all endpoints documented
- Test authentication flows documented
```

**References:** RESTAPI.md, BUILD.md, LOOM.md
**Test Requirements:** Swagger UI tests, Redoc tests, markdown generation tests

---

### Task: Add Architecture Decision Records (ADRs)

**Argumentation Summary:** Major architectural decisions (Vert.x, Dagger, RxJava, dual node hierarchies, etc.) are documented in specs but not as formal ADRs with context, decision, consequences.

**Improvement Summary:** Create ADR documents for key architectural decisions in `docs/adr/`.

```
Create Architecture Decision Records (ADRs) for key architectural decisions.

**Current State (from all spec files):**
- Decisions documented inline in specs
- No formal ADR structure
- Hard to track decision history and rationale

**Files to Create:**
1. `docs/adr/001-vertx-reactive-architecture.md`
2. `docs/adr/002-dagger-dependency-injection.md`
3. `docs/adr/003-rxjava3-pipeline-execution.md`
4. `docs/adr/004-dual-node-hierarchies.md`
5. `docs/adr/005-websocket-event-fanout.md`
6. `docs/adr/006-mcp-integration.md`
7. `docs/adr/007-jpa-vs-jooq.md`
8. `docs/adr/008-multi-module-maven.md`

**Implementation Steps:**
1. Create `docs/adr/` directory
2. For each ADR, use template:
   - Title, Status (Accepted/Proposed/Deprecated)
   - Context (problem, constraints)
   - Decision (what was chosen)
   - Consequences (positive, negative, risks)
   - Alternatives considered
   - Links to related specs/code
3. Reference ADRs from spec files
4. Add ADR index in `docs/adr/README.md`
5. Establish process for new ADRs

**References:**
- All spec files (LOOM.md, PIPELINE.md, EVENTBUS.md, etc.)
- ADR template: https://adr.github.io/

**Test Requirements:**
- Verify all major decisions have ADRs
- Verify ADR format consistency
- Verify cross-references from specs
```

**References:** All spec files
**Test Requirements:** ADR completeness check, format validation, cross-reference validation

---

## Performance Tasks

### Task: Add Pipeline Execution Benchmarks

**Argumentation Summary:** No performance benchmarks for pipeline execution. Need baseline metrics for regression detection.

**Improvement Summary:** Add benchmark suite for pipeline execution with various node types, concurrency levels, and data sizes.

```
Add pipeline execution benchmarks for performance regression detection.

**Current State (from PIPELINE.md, BUILD.md):**
- No benchmarks documented
- Pipeline tests use `TestNode` with artificial delays
- No real-world performance baselines

**Files to Modify:**
1. `cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/benchmark/` - Benchmark tests
2. `build.sh` - Add benchmark phase
3. `cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/executor/` - Benchmark utilities

**Implementation Steps:**
1. Create benchmark suite using JMH (Java Microbenchmark Harness):
   - Benchmark: Single node execution (hash, facedetect, whisper)
   - Benchmark: Linear pipeline (5 nodes, 100 media items)
   - Benchmark: Fan-out pipeline (1 source → 10 parallel → 1 sink)
   - Benchmark: High concurrency (maxConcurrentMedia = 100)
   - Benchmark: Cache hit vs miss
   - Benchmark: Dry-run vs real execution
2. Metrics to capture:
   - Throughput (items/sec)
   - Latency (p50, p95, p99 per node)
   - CPU/memory usage
   - Event bus overhead
   - Sync collector throughput
3. Run benchmarks in CI (weekly)
4. Store results for trend analysis
5. Alert on >10% regression
6. Document baseline results in PIPELINE.md

**References:**
- PIPELINE.md (executor, nodes, caching)
- BUILD.md (test setup)
- JMH documentation

**Test Requirements:**
- Benchmark runs without errors
- Results captured in structured format
- CI integration
- Regression detection
- Baseline documentation
```

**References:** PIPELINE.md, BUILD.md
**Test Requirements:** Benchmark execution tests, metrics capture tests, CI integration tests

---

### Task: Optimize Database Query Performance for Large Datasets

**Argumentation Summary:** Several operations load large datasets in memory (asset_statistics 10k assets, search_transcript stub, pipeline loading). Need SQL-level optimization.

**Improvement Summary:** Analyze and optimize slow queries, add indexes, use pagination, push aggregation to database.

```
Optimize database query performance for large dataset operations.

**Current State (from MCP.md, PIPELINE.md, PERSISTENCE.md):**
- asset_statistics loads 10k assets in memory
- search_transcript not implemented (would need full-text search)
- Pipeline loading fetches all pipelines
- No query performance analysis documented

**Files to Modify:**
1. `loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/` - Query optimization
2. `loom/db/flyway/src/main/resources/db/migration/` - Indexes
3. `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/impl/` - Tool optimization

**Implementation Steps:**
1. Identify slow queries via:
   - PostgreSQL `pg_stat_statements`
   - EXPLAIN ANALYZE on key queries
   - Load testing with large datasets
2. Optimize asset_statistics:
   - SQL aggregates (already a task)
   - Add indexes on `mimeType`, `size`, `collection_uuid`
3. Optimize pipeline loading:
   - Add pagination to `listPipelines`
   - Add index on `enabled`, `priority`
4. Optimize asset search:
   - Full-text search index (GIN on tsvector)
   - Trigram index for fuzzy filename search
5. Add query plan analysis to CI
6. Document indexes and query patterns in PERSISTENCE.md

**References:**
- MCP.md (asset_statistics, search_transcript)
- PIPELINE.md (pipeline loading)
- PERSISTENCE.md (jOOQ, filters)

**Test Requirements:**
- Query performance benchmarks before/after
- EXPLAIN ANALYZE shows index usage
- Large dataset tests (100k+ assets)
- Pagination performance tests
```

**References:** MCP.md, PIPELINE.md, PERSISTENCE.md
**Test Requirements:** Query performance benchmarks, index usage verification, large dataset tests

---

## Summary

This TASKS.md file contains **35 tasks** organized by specification area:

| Area | Tasks |
|------|-------|
| REST API | 3 |
| WebSocket | 3 |
| MCP | 7 |
| GraphQL | 3 |
| gRPC | 1 |
| Persistence | 3 |
| Pipeline | 4 |
| EventBus | 2 |
| Configuration | 2 |
| Build | 2 |
| Security | 1 |
| Testing | 2 |
| Documentation | 2 |
| Performance | 2 |

Each task includes:
- **Argumentation Summary** - Why the task is needed
- **Improvement Summary** - What the improvement entails
- **Detailed prompt** - Implementation guidance with file references
- **References** - Links to relevant spec files
- **Test Requirements** - Testing expectations

Tasks should be prioritized based on:
1. **Security** (CVE scanning, authentication gaps)
2. **Production readiness** (health checks, metrics, clustering)
3. **Developer experience** (API versioning, documentation, contract testing)
4. **Performance** (benchmarks, query optimization)
5. **Feature completeness** (GraphQL registration, gRPC, scheduling)

When implementing a task, **update the corresponding spec file** in `spec/loom/` to reflect the changes.




