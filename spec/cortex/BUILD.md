# Cortex — Build Specification

> This document covers building Cortex from source, the Maven module
> structure, the container image, native dependencies, and fast-compile
> recipes. It is a companion to [CORTEX.md](CORTEX.md) (architecture)
> and [CONFIGURATION.md](CONFIGURATION.md) (runtime configuration).
>
> **Cross-references**:
> - [CORTEX.md](CORTEX.md) — Module map, Dagger wiring, startup lifecycle
> - [CONFIGURATION.md](CONFIGURATION.md) — Container env vars, CLI flags
> - [../METALOOM.md](../METALOOM.md) — Top-level project context and build scripts

---

## 1. Prerequisites

| Requirement | Minimum / Version | Notes |
|---|---|---|
| Java (JDK) | JDK 25 | The `maven.compiler.release` is set to `25` in `cortex/core/pom.xml` |
| Maven | 3.9+ | Uses `maven-compiler-plugin` 3.14.0, `maven-shade-plugin` 3.4.1 |
| Docker / Podman | Any recent version | Only needed for container image builds |
| OpenCV | 4.10+ (JNI) | Required by facedetect, thumbnail, quality, scene-detection nodes |
| InspireFace | 1.2.1+ | Required by facedetect node (native library + model packs) |
| whisper.cpp | Latest | Required by whisper node (ASR) |
| Tesseract | 5+ | Required by OCR node |
| Apache Tika | Java (Maven) | Required by tika node (metadata extraction) |
| Ollama | Any | Required by LLM node (local LLM inference) |

> **Note**: OpenCV, InspireFace, whisper.cpp, and Tesseract are only
> needed if the corresponding nodes are enabled. Cortex can be built
> and run without them (those nodes will be disabled at runtime).

---

## 2. Maven Module Structure

Cortex is a Maven reactor under `cortex/pom.xml` (parent:
`io.metaloom:metaloom-parent:1.0.0-SNAPSHOT`, which inherits from
`io.metaloom:maven-parent:3.0.0-SNAPSHOT`).

### 2.1 Module Hierarchy

```
cortex/                          (parent POM, packaging=pom)
├── api/                         (cortex-api)          — Public interfaces, options, media types
├── common/                      (cortex-common)        — Shared impls: MetaStorage, options loader, media loader
├── fs/                          (cortex-fs)            — Linux filesystem scanner (xattr support)
├── core-media/                  (cortex-core-media)    — Media decorator types, AssertJ test helpers
├── nodes/                       (cortex-nodes)         — Concrete processing nodes (parent POM)
│   ├── common-api/              (cortex-nodes-common-api)
│   ├── filter-api/              (cortex-filter-api)
│   ├── source-api/              (cortex-source-api)
│   ├── filesystem-source/       (cortex-filesystem-source-node)
│   ├── hash/                    (cortex-hash)
│   ├── fingerprint/             (cortex-fingerprint)
│   ├── facedetect/              (cortex-facedetect)
│   ├── thumbnail/               (cortex-thumbnail)
│   ├── consistency/             (cortex-consistency)
│   ├── dedup/                   (cortex-dedup)
│   ├── quality/                 (cortex-quality)
│   ├── scene-detection/         (cortex-scene-detection)
│   ├── ocr/                     (cortex-ocr)
│   ├── tika/                    (cortex-tika)
│   ├── whisper/                 (cortex-whisper)
│   ├── llm/                     (cortex-llm)
│   ├── captioning/              (cortex-captioning)
│   └── loom/                    (cortex-loom)
├── processor/                   (cortex-processor)     — MediaProcessor, FilesystemProcessor
├── pipeline-api/                (cortex-pipeline-api)  — Pipeline, PipelineNode, executor, events, cache SPIs
├── pipeline-core/               (cortex-pipeline-core) — DefaultPipeline, ReactivePipelineExecutor, filters, serde
├── pipeline-common/             (cortex-pipeline-common)— Event bus, cache impls, bulk sync collector
├── core/                        (cortex-core)          — Runtime wiring, CLI commands, Dagger modules, Loom channel
├── cli/                         (cortex-cli)           — CLI entry point, Dagger component, shade plugin
├── container/                   (cortex-container)     — Containerfile + dependency copy
```

### 2.2 Key Dependencies

| Dependency | Version | Scope | Purpose |
|---|---|---|---|
| `io.vertx:vertx-core` | 5.0.11 | compile | Vert.x event loop, WebSocket client, HTTP server |
| `io.vertx:vertx-web` | 5.0.11 | compile | Router for monitoring endpoints |
| `com.google.dagger:dagger` | 2.57.2 | compile | Dependency injection |
| `com.google.dagger:dagger-compiler` | 2.57.2 | annotation processor | Dagger code generation |
| `info.picocli:picocli` | 4.7.7 | compile | CLI framework |
| `info.picocli:picocli-codegen` | 4.7.7 | annotation processor | Picocli completion/reflection config |
| `io.reactivex.rxjava3:rxjava` | (from BOM) | compile | Reactive streams (pipeline executor) |
| `com.fasterxml.jackson` | 2.18.2 | compile | JSON/YAML serialization (config, pipeline serde) |
| `org.apache.commons:commons-io` | (from BOM) | compile | File utilities |
| `io.metaloom.loom:loom-client` | 1.0.0-SNAPSHOT | compile | Loom REST client (online mode) |
| `io.metaloom.loom:loom-shared-rest-model` | 1.0.0-SNAPSHOT | compile | DTOs (pipeline, processor, asset) |

### 2.3 Dependency Management

All external dependency versions are managed in the root BOM
(`bom/pom.xml`) and the `metaloom-parent` POM. Internal
`io.metaloom.cortex:*` dependencies are managed in `cortex/pom.xml`
under `<dependencyManagement>`.

---

## 3. Build Commands

### 3.1 Full Build (All Modules)

```bash
# From the project root (metaloom/)
mvn -T 8 clean package -DskipTests
```

Or use the convenience script:

```bash
./build.sh
```

`build.sh` does:
1. `mvn -T 8 clean package -DskipTests` (Maven build)
2. `npm run build` (Loom UI)
3. `loom/containers/build-containers.sh all` (Loom containers)
4. `cortex/container/build-container.sh` (Cortex container)

### 3.2 Build Only Cortex

```bash
# Build all cortex modules (skip tests)
mvn -T 8 clean package -DskipTests -pl cortex -am

# Or from within the cortex/ directory:
cd cortex/
mvn -T 8 clean package -DskipTests
```

### 3.3 Fast Compile Check

```bash
mvn -T 8 test-compile -q -DskipTests -pl cortex
```

### 3.4 Build Only the CLI JAR

```bash
mvn -T 8 clean package -DskipTests -pl cortex/cli -am
# Output: cortex/cli/target/cortex-cli-*.jar
```

### 3.5 Run Tests

```bash
# All cortex tests
mvn -T 8 test -pl cortex

# Specific module
mvn test -pl cortex/pipeline-core
mvn test -pl cortex/nodes/hash

# Integration tests (requires test database pool)
cd ../  # project root
./it.sh
```

### 3.6 Dagger Annotation Processing

Dagger generates `DaggerCortexComponent` and related classes during
compilation. The generated sources appear under:

```
cortex/cli/target/generated-sources/annotations/
```

If you change Dagger modules, `@Binds`, `@Provides`, or `@IntoSet`
annotations, do a full `mvn clean compile` — incremental compilation
may not re-trigger the annotation processor.

---

## 4. Container Image

### 4.1 Containerfile

Location: `cortex/container/Containerfile`

The container image is based on `debian:trixie-slim` and includes:

| Component | Source | Purpose |
|---|---|---|
| CUDA 13.2 runtime | NVIDIA Debian13 repo | GPU support for whisper, facedetect |
| OpenCV 4.10 JNI | Debian Trixie apt | Image/video processing |
| OpenJDK 25 JRE | Eclipse Temurin | Runtime for Cortex |
| InspireFace model packs | GitHub releases | Face detection models |
| cortex-cli.jar | Maven build | The shaded CLI JAR |

### 4.2 Building the Container Image

```bash
# Prerequisite: build the CLI JAR first
mvn -T 8 clean package -DskipTests -pl cortex/container,cortex/cli -am

# Build the container image
cd cortex/container/
./build-container.sh

# Or with a custom tag:
TAG=v1.0.0 ./build-container.sh
```

The script produces `metaloom/cortex-server:latest` (or `:$TAG`).

### 4.3 Container Runtime

```bash
# Run the container (server mode, connecting to Loom)
docker run -d \
  -e LOOM_HOST=loom \
  -e LOOM_PORT=8092 \
  -e LOOM_TOKEN=your-token \
  -p 8093:8093 \
  -v /path/to/meta:/meta \
  -v /path/to/config:/config \
  metaloom/cortex-server:latest

# The container CMD is:
# /opt/java25/bin/java -Djna.tmpdir=/tmp/.jna -Duser.dir=/cortex \
#   --enable-native-access=ALL-UNNAMED -jar cortex-cli.jar server start
```

### 4.4 Container Environment

| Env Var | Default | Description |
|---|---|---|
| `LOOM_HOST` | `loom` | Loom server hostname |
| `LOOM_PORT` | `8092` | Loom server HTTP port |
| `CORTEX_MONITORING_PORT` | `8093` | Monitoring HTTP port |
| `HOME` | `/cortex` | Home directory |
| `JAVA_TOOL_OPTIONS` | `-Xms256m -Xmx512m` | JVM heap settings |
| `LOOM_TOKEN` | (not set) | Bearer token for Loom WebSocket auth |

### 4.5 Container Volumes

| Volume | Container Path | Purpose |
|---|---|---|
| `/config` | symlinked to `/cortex/config` | Config files (`cortex.yml`) |
| `/meta` | — | Metadata storage (sidecar files, xattr) |

---

## 5. CLI JAR Packaging

The `cortex-cli` module uses the `maven-shade-plugin` to produce a
shaded (uber) JAR with classifier `combined`:

```
cortex/cli/target/cortex-cli-1.0.0-SNAPSHOT-combined.jar
```

The `cortex-container` module uses `maven-dependency-plugin` to copy
this JAR to `cortex/container/target/cortex-cli/cortex-cli.jar`.

**Main class**: `io.metaloom.cortex.cli.CortexCLIMain`

The shade plugin:
- Merges all dependency JARs into a single JAR
- Excludes signature files (`META-INF/*.SF`, `*.DSA`, `*.RSA`)
- Sets `Main-Class` to `io.metaloom.cortex.cli.CortexCLIMain` via
  `ManifestResourceTransformer`

---

## 6. Native Dependencies

Cortex nodes depend on native libraries. These are not bundled in the
JAR — they must be available on the library path or installed system-wide.

| Node | Native Dependency | Library / Path | Container Source |
|---|---|---|---|
| Facedetect | InspireFace | `libinspireface.so` + model packs | Installed in `/cortex/packs` |
| Facedetect / Thumbnail / Quality / Scene | OpenCV | `libopencv410-jni` + native libs | apt: `libopencv410-jni` |
| Whisper | whisper.cpp | `libwhisper.so` + GGML model | Not in container (mount or install) |
| OCR | Tesseract | `libtesseract.so` + tessdata | Not in container (install separately) |
| Hash | None (pure Java) | — | — |
| Tika | None (Java) | — | — |
| LLM | Ollama (HTTP) | `ollama` server running locally | Not in container |
| Captioning | SmolVLM (HTTP) | External service | Not in container |

### JVM Native Access

The container runs with `--enable-native-access=ALL-UNNAMED` to allow
JNI calls to OpenCV and InspireFace without warnings on JDK 25.

---

## 7. Key Build Artifacts

| Artifact | Module | Description |
|---|---|---|
| `cortex-api` jar | `cortex/api` | Public interfaces (Cortex, CortexOptions, CortexNode, LoomMedia) |
| `cortex-common` jar | `cortex/common` | Shared impls (MetaStorage, options loader, media loader) |
| `cortex-core` jar | `cortex/core` | Runtime wiring, CLI commands, Dagger modules |
| `cortex-cli` jar (combined) | `cortex/cli` | Shaded uber JAR with all dependencies |
| `cortex-pipeline-api` jar | `cortex/pipeline-api` | Pipeline interfaces |
| `cortex-pipeline-core` jar | `cortex/pipeline-core` | Pipeline executor, filters, JSON serde |
| `cortex-pipeline-common` jar | `cortex/pipeline-common` | Event bus, cache impls, bulk sync |
| `cortex-container` (pom) | `cortex/container` | Copies CLI JAR for container build |
| Node jars | `cortex/nodes/*` | Per-node implementations |

---

## 8. Test Setup

### 8.1 Unit Tests

Each module has `src/test/java` with unit tests. Run with:

```bash
mvn test -pl cortex/<module>
```

### 8.2 Test Dependencies

| Library | Scope | Purpose |
|---|---|---|
| JUnit 6 (`junit-jupiter`) | test | Test framework |
| Testcontainers | test | Integration tests (Loom server, Postgres) |
| AssertJ | test | Custom assertions (see [CORTEX.md](CORTEX.md) Section 9.3) |

### 8.3 Integration Tests

Integration tests are in `cortex/pipeline-core/src/test` and
`cortex/core/src/test`. They test the pipeline executor, JSON serde,
and Loom control channel.

### 8.4 Custom AssertJ Assertions

| Assert | Module |
|---|---|
| `PipelineResultAssert` | `cortex/pipeline-core` |
| `PipelineNodeResultAssert` | `cortex/pipeline-core` |
| `NodeResultAssert` | `cortex/core-media` |
| `FaceAssert` | `cortex/nodes/facedetect/core` |

---

## 9. Conventions and Gotchas

- **Java 25**: The compiler release is set to `25` in `cortex/core/pom.xml`.
  Ensure your JDK is 25+ or the build will fail.
- **Dagger regeneration**: After changing `@Binds`, `@Provides`,
  `@IntoSet`, or `@Module` annotations, run `mvn clean compile` —
  incremental builds may not regenerate `DaggerCortexComponent`.
- **Shade plugin**: The `cortex-cli` JAR is shaded with classifier
  `combined`. The container module references it via
  `<classifier>combined</classifier>`. If you change the shade config,
  rebuild both `cortex-cli` and `cortex-container`.
- **BOM**: All external dependency versions are managed in the root
  `bom/pom.xml`. Do not add version numbers in child POMs — use the
  managed version.
- **Vert.x 5**: Cortex uses Vert.x 5.0.11 (not Vert.x 4). The API
  differs significantly — use `io.vertx.core.http.WebSocketClient`
  (not `HttpClient.webSocket()`).
- **RxJava 3**: The pipeline executor uses `io.reactivex.rxjava3.core.
  Flowable` and `Single`. Do not mix with Reactor or CompletableFuture.
- **Native libraries**: If a node fails with `UnsatisfiedLinkError`,
  check that the native library is on `java.library.path` or
  `LD_LIBRARY_PATH`. The container sets `--enable-native-access=ALL-UNNAMED`.
- **Test database**: Cortex unit tests do not require a database.
  Integration tests that interact with Loom require the Loom server
  to be running (see [../METALOOM.md](../METALOOM.md) Section 7).
- **Build order**: The reactor build order is defined by module
  dependencies. `cortex-api` is built first, then `common`, then
  `nodes`, then `pipeline-*`, then `core`, then `cli`, then `container`.

---

## 10. Where do I find …?

| Need | Look here |
|---|---|
| Root POM | `cortex/pom.xml` |
| BOM (dependency versions) | `bom/pom.xml` |
| Parent POM | `metaloom-parent` → `maven-parent` |
| CLI entry point | `cortex/cli/src/main/java/.../CortexCLIMain.java` |
| CLI POM (shade plugin) | `cortex/cli/pom.xml` |
| Container POM | `cortex/container/pom.xml` |
| Containerfile | `cortex/container/Containerfile` |
| Container build script | `cortex/container/build-container.sh` |
| Full build script | `build.sh` (project root) |
| Dagger component | `cortex/cli/src/main/java/.../dagger/CortexComponent.java` |
| Dagger bindings | `cortex/core/src/main/java/.../dagger/CortexBindModule.java` |
| Node modules POM | `cortex/nodes/pom.xml` |
| Pipeline API POM | `cortex/pipeline-api/pom.xml` |
| Integration test script | `it.sh` (project root) |
| E2E test script | `e2e.sh` (project root) |
| Custom node example | `examples/cortex-custom-node/` |
| Custom CLI example | `examples/cortex-custom-cli/` |

---

## 11. Progress Assessment

- [x] Prerequisites documented (JDK, Maven, native deps)
- [x] Maven module structure documented
- [x] Key dependencies table
- [x] Build commands (full, cortex-only, fast compile, CLI JAR, tests)
- [x] Container image build and runtime documented
- [x] CLI JAR packaging (shade plugin) documented
- [x] Native dependencies table
- [x] Build artifacts table
- [x] Test setup section
- [x] Conventions and gotchas
- [x] "Where do I find" cheat sheet
- [ ] CI/CD pipeline configuration
- [ ] GraalVM native image build
- [ ] Release process documentation
