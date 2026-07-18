## Plan: Implement gRPC Service for Loom

### TL;DR
The gRPC service infrastructure exists but is disabled (commented out in parent POM). The protobuf definitions for Asset service exist, and there's a partial implementation using Vert.x gRPC with JWT authentication. Need to: (1) enable the module, (2) fix configuration binding, (3) implement all service methods, (4) wire into bootstrap, (5) add health/reflection, (6) define additional services, (7) document and test.

---

### Current State Summary
- **Module**: `loom/services/grpc` exists but commented out in `loom/services/pom.xml`
- **Protobuf**: `loom-shared/proto/src/main/proto/asset.proto` defines `AssetLoader` service with `Store`/`Load` RPCs
- **Service**: `GrpcService.java` uses `JWTGrpcServer` but has hardcoded `port=0`/`host=localhost` and only handles `Load` method
- **Business Logic**: `GrpcAssetLoader.java` implements both `store()` and `load()` using `DaoCollection`
- **Client**: `loom-client/grpc` exists but commented out in parent
- **Config**: `ServerOptions` has `grpcPort` (8091) and `bindAddress` but `GrpcService` doesn't use them
- **Bootstrap**: `BootstrapInitializer` has commented-out `GrpcService` injection and startup

---

### Steps

#### Phase 1: Enable Module & Fix Configuration
1. **Uncomment grpc module** in `loom/services/pom.xml`
2. **Fix GrpcService** to use `ServerOptions.getGrpcPort()` and `getBindAddress()` instead of hardcoded values
3. **Add GrpcService to LoomCoreComponent** Dagger modules (create GrpcModule/GrpcBindModule)
4. **Inject GrpcService into BootstrapInitializer** and call `start()`/`stop()`

#### Phase 2: Complete Service Implementation
5. **Register both Store and Load handlers** in `GrpcService` using `GrpcAssetLoader`
6. **Wire GrpcAssetLoader** into the gRPC call handlers (currently only Load has a trivial handler)
7. **Add proper error handling** and validation in gRPC handlers

#### Phase 3: Extend Protobuf Schema
8. **Define additional services** in `loom.proto` (or new proto files):
   - `PipelineService`: GetPipeline, ListPipelines, RunPipeline
   - `CollectionService`: CRUD operations
   - `UserService`: CRUD operations
   - `AssetService`: Extended operations (bulk, SHA-512 lookup, sub-resources)
   - `LibraryService`, `SpaceService`, `TagService`, etc.
9. **Regenerate Java stubs** via protobuf-maven-plugin

#### Phase 4: Implement Additional Services
10. **Create service implementation classes** for each new service (e.g., `GrpcPipelineService`, `GrpcCollectionService`)
11. **Wire all services** into `GrpcService` startup
12. **Reuse existing DAO patterns** from REST services

#### Phase 5: Health Check & Reflection
13. **Add gRPC Health Protocol** implementation (standard `grpc.health.v1.Health` service)
14. **Add gRPC Server Reflection** for tooling support (grpcurl, etc.)
15. **Expose health endpoint** on same port

#### Phase 6: Documentation & Testing
16. **Write GRPC.md** specification document
17. **Add integration tests** for service startup, each method, auth, health, reflection
18. **Test gRPC client** (`loom-client/grpc`) against the server

---

### Relevant Files

| File | Purpose |
|------|---------|
| `loom/services/pom.xml` | Uncomment `<module>grpc</module>` |
| `loom/services/grpc/pom.xml` | Module dependencies (already configured) |
| `loom/services/grpc/src/main/java/io/metaloom/loom/server/grpc/GrpcService.java` | Main service - fix config, add handlers |
| `loom/services/grpc/src/main/java/io/metaloom/loom/server/grpc/impl/GrpcAssetLoader.java` | Business logic - already implemented |
| `loom-shared/proto/src/main/proto/asset.proto` | Current proto - extend with more services |
| `loom-shared/proto/pom.xml` | Protobuf plugin config (already configured) |
| `loom/core/src/main/java/io/metaloom/loom/core/boot/BootstrapInitializer.java` | Add GrpcService injection and startup |
| `loom/core/src/main/java/io/metaloom/loom/core/dagger/LoomCoreComponent.java` | Add GrpcModule |
| `loom-shared/api/src/main/java/io/metaloom/loom/api/options/ServerOptions.java` | Config (already has grpcPort/bindAddress) |
| `spec/loom/GRPC.md` | Write specification (currently empty) |

---

### Verification

1. **Module builds**: `mvn -pl loom/services/grpc -am clean compile`
2. **Service starts on port 8091**: Check logs show "Server started and listening on port 8091"
3. **Health check works**: `grpcurl -plaintext localhost:8091 grpc.health.v1.Health/Check`
4. **Reflection works**: `grpcurl -plaintext localhost:8091 list`
5. **Asset Store/Load works**: Test via gRPC client or grpcurl
6. **JWT authentication works**: Valid token required for calls
7. **Integration tests pass**: New tests in integration-test module

---

### Decisions

- **Protobuf organization**: Keep single `loom.proto` file or split by service? → Start with single file for simplicity, split later if needed
- **Service granularity**: One gRPC service class per protobuf service (mirrors REST endpoint pattern)
- **Authentication**: Reuse existing `JWTAuth` and `JWTGrpcServer` (already wired)
- **Port configuration**: Use `ServerOptions.grpcPort` (8091) and `bindAddress` (0.0.0.0) - fix the hardcoded values
- **Health protocol**: Implement standard `grpc.health.v1.Health` service
- **Reflection**: Enable `ServerReflection` from `io.grpc:grpc-services`

---

### Further Considerations

1. **Client module**: `loom-client/grpc` is also commented out - should be enabled after server works
2. **Service coverage**: Start with Asset service (already defined), then add Pipeline, Collection, User as priority
3. **Error mapping**: Map DAO exceptions to gRPC status codes (NOT_FOUND, INVALID_ARGUMENT, INTERNAL)
4. **Streaming**: Consider server-side streaming for list operations (large collections)
5. **Compatibility**: Ensure protobuf changes are backward compatible (field numbers, optional fields)