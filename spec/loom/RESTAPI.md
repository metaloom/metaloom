# MetaLoom // Loom REST API Specification

> This document is a living specification for the Loom REST API. It is intended
> to be consumed by AI agents and developers who need to understand, extend, or
> integrate with the API. The progress checklist at the end tracks areas that
> still need improvement.

---

## 1. General Principles

### 1.1 API Versioning

- All REST endpoints are mounted under the `/api/v1` path prefix
  (`RESTConstants.API_V1_PATH`).
- The current version is v1. There is no v2 yet.
- The OpenAPI spec is served at `/api/v1/openapi` (YAML format) and the API
  info endpoint at `/api/v1` (`RESTInfoEndpoint`).

### 1.2 HTTP Methods

| Method   | Usage                                                        |
|----------|--------------------------------------------------------------|
| `GET`    | List (collection) or load (single) resources                |
| `POST`   | Create a resource **or** update a resource (Loom uses POST for updates instead of PUT/PATCH) |
| `DELETE` | Delete a resource                                           |
| `OPTIONS`| CORS preflight (handled by CorsHandler)                     |
| `PATCH`  | Not used by the server (supported in CORS, but no endpoints) |
| `PUT`    | Not used by the server (supported in CORS, but no endpoints) |

> **Note:** Loom uses `POST` for both create and update operations. Updates are
> performed via `POST /resource/:uuid` rather than `PUT /resource/:uuid`.

### 1.3 Content Types

- Request and response bodies use `application/json` (defined in
  `HTTPConstants.APPLICATION_JSON`).
- The OpenAPI spec endpoint returns `text/vnd.yaml`.
- Binary uploads (attachments) use `multipart/form-data`.
- WebSocket endpoints use the standard WS upgrade protocol.

### 1.4 Response Codes

| Code | Meaning                                                        |
|------|---------------------------------------------------------------|
| 200  | OK - successful load, update, or list                        |
| 201  | Created - successful create                                   |
| 204  | No Content - successful delete                                |
| 400  | Bad Request - validation error or bad path/query params      |
| 403  | Forbidden - missing permissions (`MISSING_PERM`)             |
| 404  | Not Found - resource does not exist                          |
| 500  | Internal Server Error                                        |
| 503  | Service Unavailable - e.g. no processor available for pipeline run |
| 4401 | WebSocket close code - unauthorized (custom close code)      |

Error responses use `GenericMessageResponse` with a `message` field.

### 1.5 ID Format

- Most resources use UUID identifiers (`:uuid` path parameter).
- Assets additionally support SHA-512 hash-based identifiers via
  `/assets/sha512/:sha512`.
- The `AssetId` type in the client encapsulates both forms.

### 1.6 Query Parameters (List Endpoints)

List endpoints (`addListRoute`) support the following query parameters
(defined in `QueryParameterKey`):

| Parameter | Key     | Type    | Default | Description                              |
|-----------|---------|---------|---------|------------------------------------------|
| Limit     | `limit` | Integer | 25      | Maximum number of items per page         |
| From      | `from`  | UUID    | null    | Seek to the element with the given UUID  |
| Filter    | `filter`| String  | null    | LHS filter expression (e.g. `name[eq]=joedoe`) |
| Sort      | `sort`  | String  | null    | Sort field name                          |
| Direction | `dir`   | Enum    | ASC     | Sort direction (`ASCENDING` or `DESCENDING`) |

### 1.7 OpenAPI Spec Generation

- The `RESTInfoEndpoint` serves the OpenAPI spec at `GET /api/v1/openapi`.
- The `LoomOpenAPI` class can generate the spec programmatically.
- Routes are registered via `ApiRouter` which supports description, request/
  response examples, and query parameter documentation.
- Each route is registered with a description string, optional request example,
  and optional response example for the OpenAPI spec.

### 1.8 CORS

- CORS is configured in `RESTService.setupRouter()` with `CorsHandler.create()`
  allowing all origins (`.*` regex), methods GET/POST/PUT/DELETE/PATCH/OPTIONS,
  and headers `Content-Type`, `Authorization`, `Accept`.
- `allowCredentials(true)` is set.

### 1.9 Body Handling

- `BodyHandler.create().setBodyLimit(-1)` is configured globally, meaning
  there is **no body size limit**. This is intentional for large binary uploads
  but should be noted for security considerations.

### 1.10 Failure Handling

- `ServerFailureHandler` handles all routing failures.
- `ValidationException` results in HTTP 400.
- `LoomRestException` results in the HTTP code specified in the exception.
- All other exceptions result in HTTP 500 with "Internal Server Error".
- 404 handler returns a JSON error message with the normalized path.

---

## 2. Authentication

### 2.1 JWT-Based Authentication

- The REST API uses JWT (JSON Web Tokens) for authentication.
- The `LoomAuthenticationHandler` (a Vert.x `Handler<RoutingContext>`) is
  applied to secured paths via `secure(path)` in `AbstractEndpoint`.
- Secured paths use a wildcard pattern, e.g. `secure(basePath() + "*")`.
- The JWT token is set as an `HttpOnly`, `Secure`, `SameSite=STRICT` cookie
  named per `AuthenticationOptions.TOKEN_COOKIE_KEY`.
- Token expiration is configurable via `AuthenticationOptions.getTokenExpirationTime()`.

### 2.2 Login Endpoint

- **Path:** `POST /api/v1/login`
- **Request:** `AuthLoginRequest` with `username` and `password` fields.
- **Response:** `AuthLoginResponse` with `token` field.
- On failure, returns 401 with `GenericMessageResponse` ("Login failed").
- On success, sets the JWT cookie and returns the token in the response body.

### 2.3 OAuth2 (BFF Pattern)

- **Base path:** `/api/v1/auth/oauth2`
- Implements the BFF (Backend-For-Frontend) pattern per
  draft-ietf-oauth-browser-based-apps-21.
- **Endpoints:**
  - `GET /api/v1/auth/oauth2/login` - Initiates the OAuth2 flow with PKCE.
    Redirects browser to IdP authorization endpoint.
  - `GET /api/v1/auth/oauth2/callback` - Handles the callback from the IdP,
    exchanges the authorization code for tokens, resolves/creates the user,
    and sets the Loom JWT cookie.
  - `GET /api/v1/auth/oauth2/logout` - Clears the session cookie.
- **PKCE:** Uses S256 code challenge. PKCE verifier and state are stored in
  `__Host-` prefixed HttpOnly cookies with 10-minute expiry.
- **State validation:** CSRF protection via state parameter matching.
- **Auto-provisioning:** If the OAuth2 user does not exist in the database,
  a new SSO user is automatically created.

### 2.4 API Tokens

- **Path:** `/api/v1/tokens`
- CRUD endpoints for managing API tokens (create, list, load, update, delete).
- Tokens are generated with `StringUtils.randomHumanString(8)`.
- Token operations require specific permissions:
  `CREATE_TOKEN`, `READ_TOKEN`, `UPDATE_TOKEN`, `DELETE_TOKEN`.

### 2.5 WebSocket Authentication

- WebSocket endpoints use a `?token=<jwt>` query parameter for authentication,
  validated by `WebSocketAuthenticator` after the WS upgrade completes.
- Invalid tokens result in close code `4401`.
- Strict mode (`LOOM_WS_STRICT_AUTH=true`) requires a token on every connection.
- See [WEBSOCKET.md](WEBSOCKET.md) for full details on WebSocket authentication,
  message protocols, and connection lifecycle.

### 2.6 Permissions

- Permission checks are performed via `lrc.requirePerm(Permission...)` which
  uses Vert.x's `PermissionBasedAuthorization`.
- Each CRUD operation maps to a specific permission (e.g. `CREATE_USER`,
  `READ_USER`, `UPDATE_USER`, `DELETE_USER`).
- Missing permissions result in HTTP 403 with `MISSING_PERM` error code.

---

## 3. Endpoint Reference

### 3.1 CRUD Endpoints (Standard Pattern)

Most resource endpoints follow a standard CRUD pattern:

| Operation | Method | Path                    |
|-----------|--------|-------------------------|
| Create    | POST   | `/api/v1/{resource}`    |
| List      | GET    | `/api/v1/{resource}`    |
| Load      | GET    | `/api/v1/{resource}/:uuid` |
| Update    | POST   | `/api/v1/{resource}/:uuid` |
| Delete    | DELETE | `/api/v1/{resource}/:uuid` |

### 3.2 Endpoint Inventory

| Endpoint                | Base Path                              | Methods                  | Notes                                      |
|-------------------------|----------------------------------------|--------------------------|--------------------------------------------|
| Login                   | `/api/v1/login`                        | POST                     | Username/password login, sets JWT cookie   |
| OAuth2                  | `/api/v1/auth/oauth2`                  | GET (login/callback/logout) | BFF pattern with PKCE                  |
| User                    | `/api/v1/users`                        | GET, POST, DELETE        | Standard CRUD                              |
| Role                    | `/api/v1/roles`                        | GET, POST, DELETE        | Standard CRUD                              |
| Group                   | `/api/v1/groups`                       | GET, POST, DELETE        | Standard CRUD (list uses `addRoute` not `addListRoute`) |
| Token                   | `/api/v1/tokens`                       | GET, POST, DELETE        | API token management                       |
| Person                  | `/api/v1/persons`                      | GET, POST, DELETE        | Standard CRUD                              |
| Space                   | `/api/v1/spaces`                       | GET, POST, DELETE        | Standard CRUD                              |
| Library                 | `/api/v1/libraries`                    | GET, POST, DELETE        | Standard CRUD                              |
| Collection              | `/api/v1/collections`                  | GET, POST, DELETE        | Standard CRUD                              |
| Tag                     | `/api/v1/tags`                         | GET, POST, DELETE        | Standard CRUD                              |
| Task                    | `/api/v1/tasks`                        | GET, POST, DELETE        | CRUD + reaction sub-resources              |
| Comment                 | `/api/v1/comments`                     | GET, POST, DELETE        | CRUD + reaction sub-resources              |
| Annotation              | `/api/v1/annotations`                  | GET, POST, DELETE        | CRUD + reaction sub-resources              |
| Reaction                | `/api/v1/reactions`                    | GET, DELETE              | List, load, delete + asset-scoped reactions |
| Blacklist               | `/api/v1/blacklists`                   | GET, POST, DELETE        | Standard CRUD                              |
| Chat                    | `/api/v1/chats`                        | GET, POST, DELETE        | Standard CRUD                              |
| Cluster                 | `/api/v1/clusters`                     | GET, POST, DELETE        | Standard CRUD                              |
| Embedding               | `/api/v1/embeddings`                   | GET, POST, DELETE        | CRUD + attachment sub-resources            |
| Webhook                 | `/api/v1/webhooks`                     | GET, POST, DELETE        | Standard CRUD                              |
| Pipeline                | `/api/v1/pipelines`                    | GET, POST, DELETE        | CRUD + `/:uuid/run` (POST) for execution   |
| Pipeline Versions       | `/api/v1/pipelines/:uuid/versions`     | GET, POST                | Version history + `/:version/restore` (POST) |
| Pipeline Events (WS)    | `/api/v1/pipelines/events/ws`          | WebSocket                | Live pipeline event stream                 |
| Processor               | `/api/v1/processors`                   | GET, WebSocket           | List/load processors + WS for processor nodes |
| Node Descriptors        | `/api/v1/pipeline/node-descriptors`    | GET                      | Pipeline node descriptor registry          |
| Content Types           | `/api/v1/pipeline/content-types`       | GET                      | Content type catalog                       |
| Asset                   | `/api/v1/assets`                       | GET, POST, DELETE        | CRUD + SHA-512 lookup + bulk + sub-resources |
| Asset (SHA-512)         | `/api/v1/assets/sha512/:sha512`        | GET, POST, DELETE        | Hash-based asset operations                |
| Asset Bulk              | `/api/v1/assets/bulk/create`           | POST                     | Bulk create assets                         |
| Asset Bulk              | `/api/v1/assets/bulk/update`           | POST                     | Bulk update assets                         |
| Asset Tags              | `/api/v1/assets/:uuid/tags`            | POST, DELETE             | Tag/untag an asset                         |
| Asset Reactions         | `/api/v1/assets/:uuid/reactions`       | GET, POST, DELETE        | Reactions on assets                        |
| Asset Detections        | `/api/v1/assets/:uuid/detections`      | GET, POST, DELETE        | Detections on assets + bulk create         |
| Asset Transcripts       | `/api/v1/assets/:uuid/transcripts`     | GET, POST, DELETE        | Transcripts on assets                      |
| Asset Binary            | `/api/v1/assets/:uuid/binary`          | GET, POST, DELETE        | One-to-one binary for an asset             |
| Asset Components        | `/api/v1/assets/:assetUuid/components` | GET, POST, DELETE        | Components for an asset                    |
| Asset Pool              | `/api/v1/pools`                        | GET, POST, DELETE        | Standard CRUD                              |
| Binary                  | `/api/v1/binaries`                     | GET, POST, DELETE        | Standalone binary CRUD                     |
| Attachment              | `/api/v1/attachments`                  | GET, POST, DELETE        | File upload (multipart) + download         |
| GraphQL                 | `/api/v1/graphql`                      | POST                     | GraphQL query endpoint                     |
| REST Info               | `/api/v1`                              | GET                      | API info + OpenAPI spec                    |
| REST OpenAPI            | `/api/v1/openapi`                      | GET                      | OpenAPI YAML spec                          |

### 3.3 Asset Endpoint Detail

The asset endpoint is the most complex, supporting:

- Standard CRUD by UUID
- CRUD by SHA-512 hash (`/assets/sha512/:sha512`)
- Bulk create (`/assets/bulk/create`) and bulk update (`/assets/bulk/update`)
- Sub-resources:
  - Tags: `/assets/:uuid/tags`, `/assets/:uuid/tags/:tagUuid`
  - Reactions: `/assets/:uuid/reactions`, `/assets/:uuid/reactions/:reactionUuid`
  - Detections: `/assets/:uuid/detections`, `/assets/:uuid/detections/:detectionUuid`, `/assets/:uuid/detections/bulk`
  - Transcripts: `/assets/:uuid/transcripts`, `/assets/:uuid/transcripts/:transcriptUuid`
  - Binary: `/assets/:uuid/binary` (one-to-one)

### 3.4 Pipeline Run Endpoint

- `POST /api/v1/pipelines/:uuid/run` - Triggers execution of a pipeline.
- Dispatches a `WorkOrder` of type `PIPELINE_RUN` to a registered processor.
- Returns `PipelineRunResponse` with `workOrderId`, `dispatched` flag, and
  `processorNodeId`.
- Returns 202 (Accepted) on success, 503 if no processor available.

### 3.5 Pipeline Versions and the Flattened Pipeline Model

Persistence keeps a pipeline and its versions as **two separate elements** — the
`pipeline` and `pipeline_version` tables, with `pipeline.latest_version_uuid`
pointing at the current revision. Every mutation (create, update, restore)
appends a new `pipeline_version` row rather than editing one in place.

The REST API deliberately **does not** mirror that split. There is a single
`PipelineResponse` model that merges both halves, so a client never has to issue
a second request just to learn a pipeline's name or definition:

| Field | Meaning |
|-------|---------|
| `uuid` | The **pipeline** UUID — stable across all versions |
| `versionUuid` | The `pipeline_version` this payload was rendered from |
| `versionNumber` | Sequential version number (1, 2, 3, …) |
| `name`, `description`, `definition`, `enabled`, `priority`, `dryRun` | Version-scoped fields, served inline |
| `meta` | Custom metadata |
| `status` | Creator/editor info |

The same flattened model is returned by every pipeline-shaped endpoint:

- `GET /api/v1/pipelines` and `GET /api/v1/pipelines/:uuid` — rendered from the
  latest version. The list resolves all versions in a single batched query, so
  entries carry their definition without an N+1 lookup.
- `POST /api/v1/pipelines` and `POST /api/v1/pipelines/:uuid` — rendered from the
  version the call just created.
- `GET /api/v1/pipelines/:uuid/versions` — paged history. Each entry keeps `uuid`
  as the pipeline UUID and pins the revision via `versionUuid`/`versionNumber`;
  the creator/editor status is that of the version's author.
- `GET /api/v1/pipelines/:uuid/versions/:version` — one historic version.
- `POST /api/v1/pipelines/:uuid/versions/:version/restore` — copies the named
  version into a **new** latest version and returns the pipeline rendered from
  it (201).

Deleting a pipeline removes all of its versions.

### 3.6 WebSocket Endpoints

The REST API exposes two WebSocket endpoints. Full protocol details, message
formats, authentication, and lifecycle are documented in
[WEBSOCKET.md](WEBSOCKET.md).

#### Processor WebSocket (`/api/v1/processors/ws`)

- Bidirectional WebSocket for cortex processor nodes.
- Messages: `REGISTER`, `HEARTBEAT`, `STATUS_UPDATE`, `STATE_CHANGE`,
  `WORK_ORDER_RESULT`, `PIPELINE_EVENT` (processor -> loom);
  `REGISTERED`, `HEARTBEAT_ACK`, `WORK_ORDER`, `ERROR` (loom -> processor).
- Authentication via `?token=<jwt>` query parameter.

#### Pipeline Events WebSocket (`/api/v1/pipelines/events/ws`)

- Read-only WebSocket for UI clients to receive live pipeline tracking events.
- Optional `?pipeline=<name>` filter to receive events for a specific pipeline.
- Events are JSON-encoded `PipelineEventMessage` objects.
- Authentication via `?token=<jwt>` query parameter.

### 3.7 GraphQL Endpoint

- `POST /api/v1/graphql` - Executes a GraphQL query.
- Request body: JSON with `query`, optional `operationName`, optional `variables`.
- Response: standard GraphQL JSON response.
- Currently **not registered** in `EndpointModule` (imported but commented out).

---

## 4. Monitoring Endpoints

### 4.1 REST Info

- `GET /api/v1` - Returns API info (currently returns "not yet implemented").

### 4.2 OpenAPI Spec

- `GET /api/v1/openapi` - Returns the OpenAPI YAML spec.
- Generated dynamically from registered routes via `OpenAPIGenerator`.

### 4.3 Processor Status

- `GET /api/v1/processors` - Lists all registered processor nodes with their
  status, capabilities, and system info.
- `GET /api/v1/processors/:uuid` - Loads a single processor by UUID.

### 4.4 Node Descriptors

- `GET /api/v1/pipeline/node-descriptors` - Lists all pipeline node descriptors
  and content types (combined response for UI).
- `GET /api/v1/pipeline/node-descriptors/:kind` - Loads a single descriptor by kind.
- `GET /api/v1/pipeline/content-types` - Lists all content types.

### 4.5 MCP Server (Separate Port)

The MCP (Model Context Protocol) server runs on a **separate HTTP server**
(port `4041`) from the REST API (port `6333`). It exposes Loom's asset
library to AI assistants via JSON-RPC 2.0 over HTTP+SSE and WebSocket.

The MCP server is **not** mounted under `/api/v1` — it has its own paths:
`/mcp/sse` (SSE stream), `/mcp/message` (JSON-RPC POST), and `/mcp/ws`
(WebSocket). It does not share the REST API's authentication handler.

MCP tools access the same DAOs as the REST API but bypass the REST
authentication and permission layers. See [MCP.md](MCP.md) for the full
MCP specification, transport details, tool catalog, and progress checklist.

---

## 5. REST Clients

### 5.1 Java HTTP Client (`loom-client-rest`)

- Module: `loom-client/rest`
- Implementation: `LoomHttpClientImpl` (extends `AbstractLoomOkHttpClient`)
- Uses OkHttp as the underlying HTTP client.
- Builder pattern: `LoomHttpClient.builder().setHostname(...).setPort(...).build()`
- Default port: 6333
- Default scheme: `http`
- Configurable timeouts: connect, read, write (default 10s each)
- Authentication: Bearer token set via `client.setToken(token)`, sent as
  `Authorization: Bearer <token>` header.
- Supports both synchronous (`.sync()`) and asynchronous (`.async()`) request
  execution via RxJava `Single`.
- Returns `LoomClientResponse<T>` with body, status code, message, and headers.
- Binary downloads return `LoomBinaryResponse`.
- File uploads use multipart form data.
- Implements `ClientMethods` interface which composes all entity method
  interfaces (UserMethods, AssetMethods, etc.).
- API path prefix: `/api/v1` (defined in `LoomHttpClient.API_V1_PATH`).

### 5.2 Java gRPC Client (`loom-client-grpc`)

- Module: `loom-client/grpc` (currently commented out in parent pom)
- Implementation: `LoomGRPCClientImpl`
- Uses gRPC for communication.
- JWT authentication via `ClientJWTInterceptor`.
- Currently only implements `AssetMethods`.

### 5.3 Client Method Interfaces (`loom-client-common`)

The `ClientMethods` interface is a composite of all entity-specific method
interfaces, providing a unified API surface:

- `UserMethods`, `AssetMethods`, `AssetLocationMethods`, `AssetBinaryMethods`,
  `AssetComponentMethods`, `AssetPoolMethods`, `AttachmentMethods`,
  `BlacklistMethods`, `ChatMethods`, `ClusterMethods`, `DetectionMethods`,
  `GroupMethods`, `RoleMethods`, `CollectionMethods`, `AnnotationMethods`,
  `TaskMethods`, `TagMethods`, `AuthenticationMethods`, `ReactionMethods`,
  `TokenMethods`, `LibraryMethods`, `PersonMethods`, `PipelineMethods`,
  `SpaceMethods`, `CommentMethods`, `EmbeddingMethods`, `TranscriptMethods`

### 5.4 Client Usage Example

```java
try (LoomClient client = LoomHttpClient.builder()
    .setHostname("localhost")
    .setPort(6333)
    .build()) {

    // Login
    AuthLoginResponse response = client.login("admin", "password").sync().body();
    client.setToken(response.getToken());

    // Create a user
    UserCreateRequest request = new UserCreateRequest();
    request.setUsername("johndoe");
    UserResponse user = client.createUser(request).sync().body();

    // Load a user
    UserResponse user = client.loadUser(uuid).sync().body();

    // List users
    UserListResponse list = client.listUsers().sync().body();
}
```

---

## 6. Architecture and Design Patterns

### 6.1 Endpoint Pattern

- All endpoints extend `AbstractEndpoint` which implements `RESTEndpoint`.
- Each endpoint declares a `basePath()` (prefixed with `/api/v1`) and a
  `name()` (for logging).
- The `register()` method wires up routes using `addRoute()` and `addListRoute()`.
- The `secure(path)` method applies the authentication handler to a path pattern.
- Routes are registered via `ApiRouter` which supports OpenAPI documentation
  metadata (description, request/response examples, query parameters).

### 6.2 Dependency Injection

- Dagger is used for DI.
- `RESTModule` provides the `ApiRouter`, `LoomModelValidator`,
  `NodeDescriptorRegistry`, and other singletons.
- `RESTBindModule` binds the `LoomModelBuilder` interface to its implementation.
- `EndpointModule` collects all `RESTEndpoint` instances into a set annotated
  with `@RESTEndpoints`.
- `EndpointDependencies` bundles the Vertx instance, router, auth handler, and
  DI component provider for per-request scopes.
- Each request creates a new `RestComponent` DI scope via
  `restComponentProvider.get().context(rc).build()`.

### 6.3 Service Layer

- `AbstractEndpointService` provides common utilities: `checkPerm()`,
  `setEditor()`, and `update()` (conditional field update).
- `AbstractCRUDEndpointService` provides the standard CRUD pattern:
  `create()`, `load()`, `update()`, `delete()`, `list()` with permission
  checks and DAO delegation.
- Each entity has a corresponding `*EndpointService` that extends
  `AbstractCRUDEndpointService` with entity-specific logic.

### 6.4 Model Building

- `LoomModelBuilder` (bound via `RESTBindModule`) converts DAO entities to
  REST response models.
- `ModelExamples` provides request/response examples for OpenAPI documentation.

### 6.5 Validation

- `LoomModelValidator` validates request models.
- `ValidationException` results in HTTP 400.

### 6.6 Error Handling

- `LoomRestException` carries an HTTP status code, an error code
  (`LoomRestErrorCode`), and a message.
- `LoomRestErrorCode` enum: `MISSING_PERM`, `BAD_QUERY_PARAMS`,
  `BAD_PATH_PARAMS`, `NOT_FOUND`, `BAD_REQUEST`, `UPLOAD_DATA_MISSING`,
  `INTERNAL_ERROR`.
- `ServerFailureHandler` catches all routing failures and maps them to
  appropriate HTTP responses.

---

## 7. Progress Assessment

The following checkboxes track aspects of the REST API that need improvement,
fixes, or are incomplete. AI agents can use this list to identify work items.

### 7.1 Core API Completeness

- [x] Standard CRUD pattern implemented for all primary resources
- [x] Asset endpoint with SHA-512 hash-based lookup
- [x] Asset bulk create/update operations
- [x] Sub-resource pattern (tags, reactions, detections, transcripts, binary, components on assets)
- [x] Reactions on tasks, comments, annotations, assets
- [x] Pipeline run endpoint with work order dispatch
- [x] WebSocket endpoints for processor and pipeline events
- [x] Node descriptor and content type catalog endpoints
- [x] Token management endpoints (API tokens)
- [x] OAuth2 BFF flow with PKCE
- [x] JWT cookie-based authentication
- [x] OpenAPI spec generation endpoint
- [x] GraphQL endpoint implemented (but not registered in EndpointModule)
- [x] REST info endpoint (stub - returns "not yet implemented")

### 7.2 Authentication and Security

- [x] JWT-based authentication with HttpOnly, Secure, SameSite=STRICT cookies
- [x] OAuth2 BFF pattern with PKCE (S256)
- [x] CSRF protection via state parameter in OAuth2 flow
- [x] WebSocket authentication via token query parameter
- [x] Permission-based authorization per endpoint
- [x] API token management for programmatic access
- [ ] REST info endpoint (`GET /api/v1`) is not implemented (returns error)
- [ ] Strict WebSocket auth is opt-in (default is lenient - accepts missing token)
- [ ] No rate limiting on authentication endpoints
- [ ] No account lockout policy on login endpoint
- [ ] OAuth2 callback does not validate PKCE verifier against stored cookie in all error paths
- [ ] OAuth2 logout endpoint only clears cookie, does not revoke IdP tokens

### 7.3 API Design Consistency

- [x] Consistent CRUD pattern across most resources
- [x] Consistent error responses (`GenericMessageResponse`)
- [x] Consistent query parameters for list endpoints
- [x] Consistent path parameter naming (`:uuid`)
- [ ] GraphQL endpoint is implemented but commented out in `EndpointModule` - not registered
- [ ] `GroupEndpoint` uses `addRoute` instead of `addListRoute` for the list
  endpoint (missing query parameter documentation for OpenAPI)
- [ ] `EmbeddingEndpoint` has attachment sub-resource routes without OpenAPI
  examples (uses simplified `addRoute` without request/response examples)
- [ ] `NodeDescriptorEndpoint` and `ContentTypes` endpoints are not secured
  (no `secure()` call)
- [ ] `ProcessorEndpoint` list/load routes are secured but the WebSocket route
  is not secured via standard auth handler (see [WEBSOCKET.md](WEBSOCKET.md) for WebSocket auth details)
- [ ] `PipelineEventEndpoint` WebSocket route is not secured via standard auth
  handler (uses post-upgrade authentication, see [WEBSOCKET.md](WEBSOCKET.md))
- [ ] `GraphQL` endpoint is not secured (no `secure()` call)
- [ ] `LoginEndpoint` and `OAuth2Endpoint` are correctly not secured (pre-auth)
- [ ] `RESTInfoEndpoint` is not secured (acceptable for info/OpenAPI endpoints)
- [ ] `ReactionEndpoint` uses a different pattern (`/reactions/assets/:assetUuid`)
  vs asset-scoped pattern on other endpoints (`/assets/:uuid/reactions`)
- [ ] `EmbeddingEndpoint` attachment routes use `:embeddingUuid` path param
  but do not use `addListRoute` for the list route

### 7.4 Client Completeness

- [x] HTTP client covers all primary CRUD operations
- [x] HTTP client supports asset SHA-512 lookups
- [x] HTTP client supports bulk asset operations
- [x] HTTP client supports sub-resource operations (tags, reactions, detections, transcripts, components, binary)
- [x] HTTP client supports file upload (attachments) via multipart form data
- [x] HTTP client supports binary download
- [x] HTTP client supports both sync and async execution
- [x] HTTP client supports configurable timeouts
- [x] gRPC client skeleton exists (commented out in parent pom)
- [ ] HTTP client does not have methods for pipeline run (`POST /pipelines/:uuid/run`)
- [ ] HTTP client does not have methods for processor listing/loading
- [ ] HTTP client does not have methods for node descriptors
- [ ] HTTP client does not have methods for GraphQL queries
- [ ] HTTP client does not have methods for OAuth2 login/callback/logout
- [ ] HTTP client does not have methods for pipeline events WebSocket (see [WEBSOCKET.md](WEBSOCKET.md))
- [ ] HTTP client does not have methods for processor WebSocket (see [WEBSOCKET.md](WEBSOCKET.md))
- [ ] HTTP client does not have methods for REST info / OpenAPI spec
- [ ] HTTP client does not have methods for embedding attachments
- [ ] HTTP client does not have methods for asset pool operations (pool CRUD exists in client but not in endpoint)
- [ ] HTTP client `listCommentsForAnnotation` uses wrong path `annotation/` (singular) instead of `annotations/` (plural)
- [ ] gRPC client only implements `AssetMethods` - all other methods are missing

### 7.5 Documentation and OpenAPI

- [x] OpenAPI spec generation via `OpenAPIGenerator`
- [x] Route descriptions on all registered routes
- [x] Request/response examples on most CRUD endpoints
- [x] Query parameter documentation on list endpoints (via `addListRoute`)
- [ ] REST info endpoint (`GET /api/v1`) returns "not yet implemented" instead of actual API metadata
- [ ] OpenAPI spec has a hardcoded base URL `https://server.tld` instead of the actual server URL
- [ ] OpenAPI spec description says "The API for our example server" instead of a proper description
- [ ] Several endpoints use the simplified `addRoute` (without examples) for sub-resource routes, missing OpenAPI documentation
- [ ] WebSocket endpoints are not documented in the OpenAPI spec (see [WEBSOCKET.md](WEBSOCKET.md) for WebSocket docs)
- [ ] No OpenAPI schema definitions for request/response models (only examples)
- [ ] No OpenAPI security scheme definitions

### 7.6 Error Handling

- [x] `ServerFailureHandler` handles routing failures
- [x] `LoomRestException` with HTTP codes and error codes
- [x] 404 handler with JSON error response
- [x] Validation errors return 400
- [ ] Error responses do not include the error code (`LoomRestErrorCode`) in the JSON body, only the message
- [ ] No standardized error response model (uses `GenericMessageResponse` which only has a `message` field)
- [ ] No HATEOAS links or Location headers on create operations
- [ ] Pipeline run returns 503 when no processor is available, but the client may not handle this gracefully
- [ ] `NodeDescriptorEndpoint` returns raw JSON strings instead of using the standard response model

### 7.7 Testing

- [x] HTTP client has test infrastructure (`AbstractContainerTest`, `AbstractHTTPClientTest`)
- [x] HTTP client has `LoomHttpClientAssert` for assertions
- [x] Basic usage example test exists
- [ ] No dedicated endpoint-level integration tests visible in the REST service module
- [ ] No OpenAPI spec validation tests
- [ ] No WebSocket endpoint tests (see [WEBSOCKET.md](WEBSOCKET.md) for WebSocket-specific testing gaps)
- [ ] No OAuth2 flow tests
- [ ] No rate limiting tests

### 7.8 Infrastructure and Configuration

- [x] Dagger-based DI with per-request scope
- [x] Configurable timeouts on HTTP client
- [x] Configurable body limit (currently unlimited: `setBodyLimit(-1)`)
- [x] CORS configured for all origins/methods
- [ ] No rate limiting middleware
- [ ] No request logging middleware
- [ ] No compression middleware
- [ ] Body size limit is unlimited (`-1`) which may be a security concern
- [ ] CORS allows all origins (`.*`) which may not be suitable for production

### 7.9 Missing or Incomplete Features

- [ ] `RESTInfoEndpoint` (`GET /api/v1`) is a stub - returns "not yet implemented"
- [ ] GraphQL endpoint is implemented but not registered in `EndpointModule`
- [ ] No health check endpoint (e.g. `/api/v1/health` or `/health`)
- [ ] No readiness probe endpoint
- [ ] No metrics endpoint (e.g. `/api/v1/metrics` or `/metrics`)
- [ ] No versioned API deprecation headers
- [ ] No pagination metadata in list responses (no total count, no next/prev links)
- [ ] No ETag/conditional request support
- [ ] No request ID / correlation ID tracking
- [ ] No audit logging
- [ ] `AssetPool` client methods exist but no corresponding `AssetPool` endpoint is registered (only the `AssetPoolEndpoint` exists for pools, which is correct - but the client naming is inconsistent)
- [ ] `DetectionMethods` and `TranscriptMethods` exist in the client common module but are not listed in `ClientMethods` interface (they are only accessible as asset sub-resources)
- [ ] `AssetLocationMethods` exist in client but no `AssetLocation` endpoint is registered in `EndpointModule`
- [ ] MCP server (port 4041) has no authentication — tools bypass REST API auth and access DAOs directly (see [MCP.md](MCP.md))
- [ ] MCP server port is hardcoded to 4041, not configurable via `LoomOptions`
- [ ] MCP tools are read-only (search, get, list, stats) — no tools for create/update/delete operations
