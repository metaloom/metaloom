# gRPC Service

Loom exposes a gRPC API next to the REST API. The gRPC server runs on its own HTTP/2 server, separate from the REST
server, so that both can be bound and scaled independently.

## Configuration

| Option | Default | Description |
|--------|---------|-------------|
| `server.grpcPort` | `8091` | Port the gRPC server binds to. Use `0` to let the OS pick a free port (used by tests). |
| `server.bindAddress` | `0.0.0.0` | Address the gRPC server binds to. Shared with the REST server. |

The server is started by `BootstrapInitializer` after the REST and MCP services and is shut down again via
`BootstrapInitializer.deinit()`.

## Implementation

The server is built on `vertx-grpc-server` (Vert.x 5). Each protobuf service is implemented by one class that
implements `LoomGrpcService`, mirroring the one-class-per-endpoint pattern used by the REST layer:

| Class | Protobuf service |
|-------|------------------|
| `GrpcAssetService` | `asset.AssetLoader` |
| `GrpcHealthService` | `grpc.health.v1.Health` |
| `GrpcReflectionService` | `grpc.reflection.v1.ServerReflection` |

`GrpcService` wires the services onto a `GrpcServer` and manages the HTTP server lifecycle.

> **Note:** the `io.metaloom.vertx:vertx-grpc-jwt` library is *not* used. It was built against Vert.x 4.4, bundles
> forked copies of `io.vertx.grpc.server` classes, and is incompatible with the Vert.x 5 gRPC API
> (`ServiceMethod` replaced `io.grpc.MethodDescriptor`). Authentication is implemented in-repo instead, see below.

### Protobuf toolchain

Protobuf definitions live in `loom-shared/proto/src/main/proto` and are compiled by `protobuf-maven-plugin`.

The `protoc` version is pinned to `${protobuf.version}` from the root POM, which also pins the `protobuf-java`
runtime. **These must stay in lockstep** — generated protobuf code only runs against the runtime generation it was
produced for, and a mismatch fails at class-initialisation time with `NoSuchMethodError` on
`Descriptors$FileDescriptor`.

## Authentication

All calls on business services require a JWT. The token is read from the `authorization` call metadata:

```
authorization: Bearer <jwt>
```

`GrpcAuthenticator` validates it through the same `LoomAuthenticationHandler` the REST layer uses, so tokens are
interchangeable between transports. Unlike REST there is no cookie fallback — gRPC clients always send metadata.

The loom user uuid is taken from the `uuid` claim of the token.

Missing, malformed, invalid or expired tokens are answered with `UNAUTHENTICATED`.

The health and reflection services are deliberately **unauthenticated** so that load balancers can probe the server and
tooling can discover the schema without holding a token.

## Error mapping

`GrpcErrors` maps exceptions onto gRPC status codes:

| Exception | Status |
|-----------|--------|
| `GrpcServiceException` | the status it carries |
| `IllegalArgumentException` | `INVALID_ARGUMENT` |
| `NoSuchElementException` | `NOT_FOUND` |
| anything else | `INTERNAL` |

`INTERNAL` errors are logged in full but reported to the client as a generic `Internal error` so that implementation
details are not leaked.

Service code that wants an explicit status throws `GrpcServiceException`.

## Threading

The DAO layer is blocking. Service implementations therefore dispatch database work via `vertx.executeBlocking(...)`
rather than running it on the event loop that serves the gRPC connections.

## Services

### asset.AssetLoader

| RPC | Description |
|-----|-------------|
| `Store` | Creates the asset when no asset with the given SHA-512 exists, otherwise updates it. Only fields the client actually set are applied. |
| `Load` | Loads the asset by SHA-512. Answers `NOT_FOUND` when it does not exist. |

`sha512sum` is mandatory on both calls; omitting it or sending a malformed checksum yields `INVALID_ARGUMENT`.

The `fingerprint` field is not persisted on the asset and is echoed back from the request.

### grpc.health.v1.Health

Implements the [standard health checking protocol](https://github.com/grpc/grpc/blob/master/doc/health-checking.md).

- `Check` — the empty service name reports the server as a whole, which is `SERVING` once started. An unregistered
  service name answers `NOT_FOUND`.
- `Watch` — streams the current status followed by every subsequent change. An unknown service is reported as
  `SERVICE_UNKNOWN` on the stream rather than failing the call.

Statuses are updated through `GrpcHealthService.setStatus(service, status)`.

### grpc.reflection.v1.ServerReflection

Implements server reflection so that tooling works without a local copy of the proto files. The handler is registered
under both `grpc.reflection.v1` and the legacy `grpc.reflection.v1alpha` name — the two are wire compatible — so older
clients are served as well.

Supported requests: `list_services`, `file_by_filename`, `file_containing_symbol`. Extension lookups only apply to
proto2 extensions, which loom does not use, and answer `UNIMPLEMENTED`.

Descriptor responses include the full transitive dependency closure of the file so that clients can build a complete
descriptor pool.

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

## Tests

`GrpcServiceTest` in `loom/services/grpc` boots the server on an ephemeral port and drives it with a real gRPC client,
covering startup, health, reflection and the authentication gate. It uses a stub authentication handler so it needs
neither a keystore nor a database.

End-to-end asset calls require a database and belong in the core module endpoint tests.

## Open items

- The `loom-client/grpc` module is still commented out in the parent POM and has not been ported to Vert.x 5.
- Only the asset service is exposed. Pipeline, collection, user, library, space and tag services are not defined yet.
- List operations should use server-side streaming once they are added.
