# gRPC Service

Loom exposes a gRPC API next to the REST API. The gRPC server runs on its **own** HTTP/2 server (its own
`HttpServer` instance, its own port), separate from the REST/UI/GraphQL/WebSocket server, so both can be bound
and scaled independently.

**Scope of this file:** everything under `loom/services/grpc/` plus the protobuf definitions in
`loom-shared/proto/`. Neighbouring transports are specified elsewhere and are *not* duplicated here:

| Transport | Spec |
|-----------|------|
| REST | [RESTAPI.md](RESTAPI.md) |
| GraphQL | [GRAPHQL.md](GRAPHQL.md) |
| WebSocket | [WEBSOCKET.md](WEBSOCKET.md) |
| MCP | [MCP.md](MCP.md) |
| Server bootstrap, ports overview | [SERVER.md](SERVER.md) |
| Config file / env var precedence | [CONFIGURATION.md](CONFIGURATION.md) |
| Auth model, JWT issuance | [../features/rbac/RBAC.md](../features/rbac/RBAC.md), [../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md) |
| Helm ports/env wiring | [../features/helm/HELM_LOOM.md](../features/helm/HELM_LOOM.md) |
| Liveness/metrics (HTTP, not gRPC) | [../features/ops/MONITORING.md](../features/ops/MONITORING.md), [../features/ops/METRICS.md](../features/ops/METRICS.md) |

## Progress Assessment

- [x] Standalone gRPC `HttpServer` bound from `ServerOptions` (`grpcPort` / `bindAddress`)
- [x] Started and stopped by `BootstrapInitializer` (`init()` after MCP + monitoring, `deinit()` before HTTP close)
- [x] Dagger-wired (`@Singleton` + `@Inject`, exposed via `LoomCoreComponent.grpcService()`)
- [x] `LoomGrpcService` SPI — one class per protobuf service
- [x] `asset.AssetLoader` (`Store`, `Load`) backed by `GrpcAssetLoader` + `DaoCollection`
- [x] `grpc.health.v1.Health` (`Check`, `Watch`) — unauthenticated
- [x] `grpc.reflection.v1.ServerReflection` (+ legacy `v1alpha` alias) — unauthenticated
- [x] JWT bearer authentication via `GrpcAuthenticator` → `LoomAuthenticationHandler`
- [x] Exception → `GrpcStatus` mapping with leak-safe `INTERNAL` messages (`GrpcErrors`)
- [x] Blocking DAO work dispatched via `vertx.executeBlocking(...)`
- [x] `GrpcServiceTest` — server startup, health, reflection, auth gate (no DB, no keystore)
- [ ] **No permission checks.** Authentication only — any valid token may `Store`/`Load` any asset.
      REST enforces permissions ([../features/permissions/PERMISSIONS.md](../features/permissions/PERMISSIONS.md)); gRPC does not.
- [ ] No end-to-end (DB-backed) test for `Store`/`Load`; only the auth gate is covered
- [ ] `loom-client/grpc` is still commented out in `loom-client/pom.xml` (not ported to Vert.x 5)
- [ ] Only the asset service is exposed — pipeline, collection, user, library, space, tag have no `.proto`
- [ ] No TLS/mTLS on the gRPC listener (plaintext h2c; terminate TLS at the ingress)
- [ ] `GrpcHealthService.setStatus(...)` is never called in production code — only the server-level status exists
- [ ] List operations should use server-side streaming once they are added
- [ ] `LoomContainer.GRPC_PORT` (`6334`) does not match the server default `8091`

## Architecture

```mermaid
flowchart TD
    Client["gRPC client<br/>(grpcurl, SDK)"] -->|HTTP/2 h2c :8091| HS["HttpServer<br/>(GrpcService)"]
    HS --> GS["io.vertx.grpc.server.GrpcServer"]

    GS --> AS["GrpcAssetService<br/>asset.AssetLoader"]
    GS --> HE["GrpcHealthService<br/>grpc.health.v1.Health"]
    GS --> RE["GrpcReflectionService<br/>grpc.reflection.v1(alpha)"]

    AS -->|GrpcHandlers.authenticated| AU["GrpcAuthenticator"]
    AU --> LAH["LoomAuthenticationHandler<br/>(shared with REST)"]
    AS --> AL["GrpcAssetLoader"]
    AL -->|vertx.executeBlocking| DAO["DaoCollection.assetDao()"]

    HE -->|GrpcHandlers.anonymous| HE
    RE -->|descriptors of all<br/>registered services| GS

    AS -.failure.-> ERR["GrpcErrors.fail()"]
    HE -.failure.-> ERR
    RE -.failure.-> ERR

    BI["BootstrapInitializer"] -->|start()/stop()| HS
```

Registration order matters only in that `GrpcService.start()` calls
`reflectionService.setServices(services)` **before** registering handlers, so reflection advertises every service
including itself.

## Configuration

All options live under `server:` in `loom.yml` and are modelled by
`io.metaloom.loom.api.options.ServerOptions`. Env vars win over the config file
(see [CONFIGURATION.md](CONFIGURATION.md)).

| YAML | Env var | Default | Description |
|------|---------|---------|-------------|
| `server.grpcPort` | `LOOM_SERVER_GRPC_PORT` | `8091` (`ServerOptions.DEFAULT_GRPC_PORT`) | Port the gRPC server binds to. `0` lets the OS pick a free port (used by `GrpcServiceTest`). |
| `server.bindAddress` | `LOOM_SERVER_GRPC_BIND_ADDRESS` | `0.0.0.0` | Bind address. **Shared with the REST server** — the env var name is gRPC-flavoured but it sets the single `bindAddress` field. |

Validation (`ServerOptions.validate()`) requires `grpcPort` in `1..65535` and **distinct from**
`restPort` (8092), `monitoringPort` (8989) and `mcpPort` (4041).

Helm maps `.Values.service.grpcPort` onto both `LOOM_SERVER_GRPC_PORT` and the `grpc` container/service port.

## Implementation

Built on `vertx-grpc-server` (Vert.x 5). Each protobuf service is implemented by one class implementing
`LoomGrpcService`, mirroring the one-class-per-endpoint pattern of the REST layer.

`LoomGrpcService` provides:
- `descriptor()` — the protobuf `ServiceDescriptor`, used by reflection to advertise the service
- `register(GrpcServer)` — install the call handlers
- `name()` — derived `ServiceName` (e.g. `asset.AssetLoader`)
- `method(name, requestPrototype)` — builds a server-side `ServiceMethod` with the protobuf encoder/decoder

`GrpcHandlers` supplies the two handler shapes used today:
- `authenticated(authenticator, (user, req) -> Future<Resp>)` — unary, requires a valid JWT
- `anonymous(req -> Future<Resp>)` — unary, no token

Streaming calls (`Health.Watch`, `ServerReflection.ServerReflectionInfo`) bypass `GrpcHandlers` and drive
`GrpcServerRequest`/`GrpcServerResponse` directly.

> **Note:** the `io.metaloom.vertx:vertx-grpc-jwt` library is *not* used. It was built against Vert.x 4.4, bundles
> forked copies of `io.vertx.grpc.server` classes, and is incompatible with the Vert.x 5 gRPC API
> (`ServiceMethod` replaced `io.grpc.MethodDescriptor`). Authentication is implemented in-repo instead.

### Protobuf toolchain

Protobuf definitions live in `loom-shared/proto/src/main/proto` and are compiled by `protobuf-maven-plugin`
(`0.6.1`) with `os-maven-plugin` selecting the native `protoc`. Generated sources land in
`loom-shared/proto/target/generated-sources/protobuf/{java,grpc-java}`.

| Property | Where | Value |
|----------|-------|-------|
| `protobuf.version` | root `pom.xml` | `4.29.3` — pins both `protoc` and the `protobuf-java` runtime |
| `protoc.grpc.version` | `loom-shared/proto/pom.xml` | `1.65.0` — must match the `io.grpc` artifacts pulled in via `vertx-grpc` |

**These must stay in lockstep** — generated protobuf code only runs against the runtime generation it was produced
for, and a mismatch fails at class-initialisation time with `NoSuchMethodError` on `Descriptors$FileDescriptor`.

## Authentication

Business services require a JWT read from the `authorization` call metadata:

```
authorization: Bearer <jwt>
```

`GrpcAuthenticator` validates it through the same `LoomAuthenticationHandler` the REST layer uses
(`authenticateToken(String)`), so tokens are interchangeable between transports. Unlike REST there is **no cookie
fallback** — gRPC clients always send metadata. The bearer prefix match is case-insensitive; the value must be
exactly two space-separated parts.

The loom user uuid is taken from the `uuid` claim (`GrpcAssetService.userUuid(User)`); a missing or malformed
claim also yields `UNAUTHENTICATED`.

Missing, malformed, invalid or expired tokens are answered with `UNAUTHENTICATED`.

Health and reflection are deliberately **unauthenticated** so load balancers can probe the server and tooling can
discover the schema without holding a token.

## Error mapping

`GrpcErrors.statusOf(Throwable)`:

| Exception | Status |
|-----------|--------|
| `GrpcServiceException` | the status it carries |
| `IllegalArgumentException` | `INVALID_ARGUMENT` |
| `NoSuchElementException` | `NOT_FOUND` |
| anything else | `INTERNAL` |

`INTERNAL` errors are logged at `error` level in full but reported to the client as a generic `Internal error`.
Other status messages are passed through with `\r`/`\n` stripped, because the message lands in the `grpc-message`
trailer and would otherwise break header framing.

Service code that wants an explicit status throws `GrpcServiceException(status, message[, cause])`. Exceptions
thrown synchronously inside a handler are converted to failed futures by `GrpcHandlers.invoke(...)`, so both
failure modes map identically.

## Threading

The DAO layer is blocking. `GrpcAssetLoader` therefore wraps every database interaction in
`vertx.executeBlocking(...)` rather than running it on the event loop that serves the gRPC connections. Any new
service that touches `DaoCollection` must do the same.

## Services

### asset.AssetLoader (`asset.proto`)

| RPC | Kind | Auth | Description |
|-----|------|------|-------------|
| `Store` | unary | yes | Creates the asset when no asset with the given SHA-512 exists, otherwise updates it. Only fields the client actually set are applied. |
| `Load` | unary | yes | Loads the asset by SHA-512. Answers `NOT_FOUND` when it does not exist. |

`AssetRequest` fields: `sha512sum`, `sha256sum`, `md5sum`, `chunkHash`, `mimeType`, `filename`, `size`,
`fingerprint`, `zeroChunkCount`, `initialOrigin`.
`AssetResponse` additionally carries `uuid` and echoes the checksums back.

- `sha512sum` is mandatory on both calls; omitting it or sending a malformed checksum yields `INVALID_ARGUMENT`.
- On `Store` the authenticated user's uuid is recorded as creator of newly created assets.
- Because proto3 scalars cannot be null, "unset" is encoded as the empty string / `0`. Empty strings and `0`
  sizes are therefore treated as *not sent* and leave the stored value untouched — a field can be set but never
  cleared over gRPC.
- The `fingerprint` field is not persisted on the asset and is echoed back from the request.

### grpc.health.v1.Health (`health.proto`)

Implements the [standard health checking protocol](https://github.com/grpc/grpc/blob/master/doc/health-checking.md).

- `Check` — the empty service name (`GrpcHealthService.SERVER`) reports the server as a whole, which is `SERVING`
  from construction. An unregistered service name answers `NOT_FOUND`.
- `Watch` — server streaming: emits the current status, then every subsequent change. An unknown service is
  reported as `SERVICE_UNKNOWN` on the stream rather than failing the call. Watchers are dropped on connection
  close and on stream exception so the watcher set cannot grow unbounded.

Statuses are updated through `GrpcHealthService.setStatus(service, status)` — **currently never called outside
tests**, so only the server-level status exists at runtime.

### grpc.reflection.v1.ServerReflection (`reflection.proto`)

Bidirectional streaming (`ServerReflectionInfo`); every request message is answered in order and the response is
closed when the client half-closes. Registered under both `grpc.reflection.v1` and the legacy
`grpc.reflection.v1alpha` name — the two are wire compatible — so older clients are served as well.

| Request | Behaviour |
|---------|-----------|
| `list_services` | Names of all registered `LoomGrpcService`s (including reflection itself) |
| `file_by_filename` | Descriptor of the matching `.proto`, else `NOT_FOUND` error response |
| `file_containing_symbol` | Matches service names, `service.Method` names and message full names, else `NOT_FOUND` |
| `file_containing_extension`, `all_extension_numbers_of_type` | `UNIMPLEMENTED` — proto2 extensions are not used |

Lookup failures are returned as an in-band `ErrorResponse` on the stream, not as a failed call.
Descriptor responses include the full transitive dependency closure of the file so clients can build a complete
descriptor pool.

## Conventions and Gotchas

- **Authentication ≠ authorization.** No gRPC call performs a permission check. Do not treat the gRPC surface as
  equivalent to REST for access control; anything sensitive must gain permission checks first.
- **Plaintext.** The server speaks h2c. TLS is expected to be terminated in front of it.
- **Adding a service:** put the `.proto` in `loom-shared/proto/src/main/proto`, implement `LoomGrpcService`,
  annotate `@Singleton` with an `@Inject` constructor, and add it to the `List.of(...)` in `GrpcService.start()` —
  services are **not** auto-discovered, and a service missing from that list is also invisible to reflection.
- **`ServiceMethod.server(...)` vs `.client(...)`** — server handlers use `server(...)`; tests build the mirror
  `client(...)` with the *response* prototype as decoder. Getting these backwards produces confusing decode errors.
- **Trailer-only responses:** for a failed call the status is available immediately, but for a *successful* call
  the client must read `response.last()` before `response.status()` is meaningful.
- **Port 0 in tests only.** Production configs pin `8091`; validation rejects a port shared with REST/monitoring/MCP.
- `LoomContainer` (`loom-test-env`) still exposes `GRPC_PORT = 6334`, which does not match the server default —
  a container-based gRPC test would need this corrected.
- `LoomCoreTestExtension` has the gRPC port wiring commented out (`// .setPort(...getGrpcService()...)`), so core
  endpoint tests do not talk gRPC today.
- Bumping `vertx-grpc` may require bumping `protoc.grpc.version` **and** `protobuf.version` together; see the
  toolchain table above.
- `SERVER.md` historically claimed the gRPC port was hardcoded and the service disabled — both are false as of this
  revision: `GrpcService` reads `ServerOptions` and `BootstrapInitializer.init()` starts it unconditionally.

## Test Setup

`GrpcServiceTest` (`loom/services/grpc/src/test/java/io/metaloom/loom/server/grpc/GrpcServiceTest.java`) is a plain
JUnit 5 test — **no database, no keystore, no test pool**:

1. `Vertx.vertx()`, `LoomOptions` with `bindAddress=localhost` and `grpcPort=0`.
2. `GrpcAuthenticator` over a `StubAuthHandler` that accepts the single token `valid-token` and returns a `User`
   carrying a random `uuid` claim.
3. `GrpcAssetService` over `new GrpcAssetLoader(vertx, null)` — the null `DaoCollection` is deliberate: no test
   gets past validation into the DAO.
4. `grpcService.start()`, then a real `GrpcClient` against `grpcService.port()`.

Covered: ephemeral bind, `Health/Check` `SERVING`, unknown-service `NOT_FOUND`, reflection `list_services`,
reflection `file_containing_symbol`, missing token → `UNAUTHENTICATED`, bad token → `UNAUTHENTICATED`,
valid token + empty request → `INVALID_ARGUMENT` (proves the call passed the auth gate).

Run just this module:

```bash
mvn -pl loom/services/grpc -am test
```

End-to-end asset calls need a database and belong in the core module endpoint tests — which requires
`./setup-pool.sh` (see the repo `CLAUDE.md`). None exist yet.

## Verifying manually

With [`grpcurl`](https://github.com/fullstorydev/grpcurl):

```bash
# Discover the exposed services
grpcurl -plaintext localhost:8091 list

# Health check
grpcurl -plaintext localhost:8091 grpc.health.v1.Health/Check

# Authenticated call
grpcurl -plaintext \
  -H "authorization: Bearer $TOKEN" \
  -d '{"sha512sum": "<checksum>"}' \
  localhost:8091 asset.AssetLoader/Load
```

## Key Classes Reference

| Class | Package | Purpose |
|-------|---------|---------|
| `GrpcService` | `io.metaloom.loom.server.grpc` | Owns the `HttpServer` + `GrpcServer`; registers services, `start()`/`stop()`/`port()` |
| `LoomGrpcService` | `io.metaloom.loom.server.grpc` | SPI: `descriptor()`, `register(GrpcServer)`, `name()`, `method(...)` |
| `GrpcHandlers` | `io.metaloom.loom.server.grpc` | Handler factories `authenticated(...)` / `anonymous(...)` |
| `GrpcAuthenticator` | `io.metaloom.loom.server.grpc` | Extracts + validates the `authorization: Bearer` metadata |
| `GrpcErrors` | `io.metaloom.loom.server.grpc` | Exception → `GrpcStatus` mapping and safe trailer messages |
| `GrpcServiceException` | `io.metaloom.loom.server.grpc` | `RuntimeException` carrying an explicit `GrpcStatus` |
| `GrpcAssetService` | `io.metaloom.loom.server.grpc.impl` | Binds `asset.AssetLoader` RPCs; resolves the user uuid claim |
| `GrpcAssetLoader` | `io.metaloom.loom.server.grpc.impl` | Asset store/load business logic over `DaoCollection` (blocking) |
| `GrpcHealthService` | `io.metaloom.loom.server.grpc.impl` | `grpc.health.v1.Health` incl. `Watch` fan-out and `setStatus(...)` |
| `GrpcReflectionService` | `io.metaloom.loom.server.grpc.impl` | `ServerReflection` v1 + v1alpha, descriptor closure walking |
| `BootstrapInitializer` | `io.metaloom.loom.core.boot` | Starts gRPC after MCP + monitoring; `deinit()` stops it |
| `LoomCoreComponent` | `io.metaloom.loom.core.dagger` | Dagger component exposing `grpcService()` |
| `ServerOptions` | `io.metaloom.loom.api.options` | `grpcPort` / `bindAddress`, env overrides, port validation |
| `LoomAuthenticationHandler` | `io.metaloom.loom.auth` | Shared JWT validation (`authenticateToken`) used by REST and gRPC |
| `GrpcServiceTest` | `io.metaloom.loom.server.grpc` | Boots on an ephemeral port, drives a real `GrpcClient` |

## Where do I find ...?

| I want ... | Path |
|------------|------|
| The gRPC server lifecycle | `loom/services/grpc/src/main/java/io/metaloom/loom/server/grpc/GrpcService.java` |
| The service SPI | `loom/services/grpc/src/main/java/io/metaloom/loom/server/grpc/LoomGrpcService.java` |
| Auth / handler / error plumbing | `loom/services/grpc/src/main/java/io/metaloom/loom/server/grpc/Grpc{Authenticator,Handlers,Errors,ServiceException}.java` |
| Service implementations | `loom/services/grpc/src/main/java/io/metaloom/loom/server/grpc/impl/` |
| Protobuf definitions | `loom-shared/proto/src/main/proto/{asset,health,reflection}.proto` |
| Generated protobuf/grpc sources | `loom-shared/proto/target/generated-sources/protobuf/` |
| protoc / grpc-java versions | `pom.xml` (`protobuf.version`), `loom-shared/proto/pom.xml` (`protoc.grpc.version`) |
| Module dependencies | `loom/services/grpc/pom.xml` |
| Where the server is started | `loom/core/src/main/java/io/metaloom/loom/core/boot/BootstrapInitializer.java` |
| Dagger exposure | `loom/core/src/main/java/io/metaloom/loom/core/dagger/LoomCoreComponent.java` |
| Port/bind options + validation | `loom-shared/api/src/main/java/io/metaloom/loom/api/options/ServerOptions.java` |
| Option tests | `loom-shared/api/src/test/java/io/metaloom/loom/api/options/{ServerOptionsTest,LoomOptionsValidationTest}.java` |
| Default config file | `loom/containers/server/config/loom.yml`, `loom/doc/src/main/generated/loom-config.yaml` |
| Helm port/env wiring | `helm/loom/values.yaml`, `helm/loom/templates/{service,deployment}.yaml` |
| Tests | `loom/services/grpc/src/test/java/io/metaloom/loom/server/grpc/GrpcServiceTest.java` |
| The (disabled) Java gRPC client | `loom-client/grpc/` — module commented out in `loom-client/pom.xml` |

_Git HEAD revision: `2e5981cb`_
_Last updated: 2026-08-01 (Verified against code; corrected env vars, wiring and toolchain facts, and added the SPEC_RULES sections.)_
