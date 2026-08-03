# Cortex — Build Specification

> Building Cortex from source: Maven reactor layout, shaded CLI JAR, container
> image, native dependencies, test recipes.
>
> **Cross-references** (do not duplicate content from these):
> - [CORTEX.md](CORTEX.md) — Architecture, module responsibilities, Dagger wiring, startup lifecycle
> - [CONFIGURATION.md](CONFIGURATION.md) — Runtime configuration, CLI flags, node options
> - [../loom/BUILD.md](../loom/BUILD.md) — Loom/UI build, `build-containers.sh`, native (GraalVM) builds
> - [../METALOOM.md](../METALOOM.md) — Repository-wide layout and testing overview

---

## 1. Prerequisites

| Requirement | Version | Needed for |
|---|---|---|
| JDK | 25 | `<release>25</release>` is set in `maven-parent` and re-declared in `cortex/nodes/pom.xml`, `cortex/core/pom.xml` |
| Maven | 3.9+ | `maven-compiler-plugin` 3.14.0, `maven-shade-plugin` 3.4.1 (versions from `maven-parent`) |
| Docker | recent | Container image only (`build-container.sh` calls `docker build`) |
| OpenCV **5.1** build | soname `.so.501` | Container image build (staged by `build-container.sh`); runtime for video4j-backed nodes |
| InspireFace | native lib + model pack | facedetect node (InspireFace backend) |
| whisper.cpp | via `asr4j` | whisper node |
| Tesseract | 5+, via `tess4j` | ocr node |
| Sidecar services | HTTP | tts, depthmap, image-generation, video-generation, sentiment, llm, vlm, captioning (see `sidecars/`) |

Cortex builds and runs without any of the native/sidecar dependencies — the
affected nodes simply fail or stay disabled at runtime.

---

## 2. Maven Module Structure

Reactor root: `cortex/pom.xml` (groupId `io.metaloom.cortex`, packaging `pom`,
parent `io.metaloom:metaloom-parent:1.0.0-SNAPSHOT` → `io.metaloom:maven-parent:3.0.0-SNAPSHOT`).
`cortex` is a module of the repository root `pom.xml`.

### 2.1 Top-level modules

| Directory | artifactId | Role |
|---|---|---|
| `api` | `cortex-api` | Public interfaces, options, `NodeResult`, media types |
| `common` | `cortex-common` | Shared impls: MetaStorage, options loader, media loader |
| `s3-common` | `cortex-s3-common` | AWS SDK S3/SQS client, lazy `s3://` media materialization |
| `fs` | `cortex-fs` | Linux filesystem scanner (xattr) |
| `core-media` | `cortex-core-media` | Media decorator types + AssertJ test helpers |
| `nodes` | `cortex-nodes` | Aggregator for all node modules (see 2.2) |
| `processor` | `cortex-processor` | `MediaProcessor`, `FilesystemProcessor` |
| `core` | `cortex-core` | Runtime wiring, CLI commands, Dagger modules, Loom control channel |
| `cli` | `cortex-cli` | Picocli entry point, Dagger component, shade plugin |
| `container` | `cortex-container` | Copies the shaded JAR for the image build (packaging `pom`) |
| `pipeline-api` | `cortex-pipeline-api` | Pipeline/executor/event/cache SPIs |
| `pipeline-common` | `cortex-pipeline-common` | Event bus, cache impls, bulk sync collector |
| `pipeline-core` | `cortex-pipeline-core` | `DefaultPipeline`, reactive executor, filters, JSON serde |
| `node-runtime` | `cortex-node-runtime` | Standalone node runtime harness (pipeline-model + Vert.x) |

### 2.2 Node modules (`cortex/nodes/`)

Every node except the two source nodes is a **two-level module**: a
packaging=`pom` parent `cortex-<name>` containing a `core/` submodule that
produces `cortex-<name>-node`. Depend on the `-node` artifact, never the parent.

| Module | artifactId (`core/`) | Notable build dependency |
|---|---|---|
| `filesystem-source` *(flat)* | `cortex-filesystem-source-node` | `differential-filesystem-scanner` |
| `s3-source` *(flat)* | `cortex-s3-source-node` | `differential-filesystem-scanner`, Avro |
| `cloud-source` *(flat)* | `cortex-cloud-source-node` | `cortex-cloud-common`, `differential-filesystem-scanner`, Avro |
| `s3-sink` | `cortex-s3-sink-node` | `cortex-s3-common` |
| `hash` | `cortex-hash-node` | — (pure Java) |
| `dedup` | `cortex-dedup-node` | `io.metaloom:utils` |
| `thumbnail` | `cortex-thumbnail-node` | `video4j` (OpenCV) |
| `consistency` | `cortex-consistency-node` | — |
| `fingerprint` | `cortex-fingerprint-node` | `video4j-fingerprint` (OpenCV) |
| `facedetect` | `cortex-facedetect-node` | `video4j-facedetect-{inspireface,insightface-http,dlib}`, `genai-utils`, `hash-utils`, Avro |
| `scene-detection` | `cortex-scene-detection-node` | `video4j`, `commons-math3` |
| `ocr` | `cortex-ocr-node` | `tess4j` (Tesseract JNA) |
| `llm` | `cortex-llm-node` | `genai-utils` |
| `vlm` | `cortex-vlm-node` | `genai-utils-mock-llm-server` (test) |
| `tika` | `cortex-tika-node` | Apache Tika 3.2.2 |
| `whisper` | `cortex-whisper-node` | `asr4j` (whisper.cpp) |
| `tts` | `cortex-tts-node` | HTTP sidecar |
| `sentiment` | `cortex-sentiment-node` | HTTP sidecar |
| `script` | `cortex-script-node` | GraalVM `polyglot` + `js-community` |
| `depthmap` | `cortex-depthmap-node` | HTTP sidecar |
| `scene-layout` | `cortex-scene-layout-node` | — |
| `dominant-color` | `cortex-dominant-color-node` | — |
| `quality` | `cortex-quality-node` | `video4j` (OpenCV) |
| `captioning` | `cortex-captioning-node` | `video4j`, HTTP sidecar |
| `image-generation` | `cortex-image-generation-node` | HTTP sidecar |
| `video-generation` | `cortex-video-generation-node` | HTTP sidecar |
| `watermark` | `cortex-watermark-node` | — |

### 2.3 Version management

| Where | Manages |
|---|---|
| `bom/pom.xml` | All external versions (`dagger` 2.57.2, `pico-cli` 4.7.7, `jackson` 2.18.2, `tika` 3.2.2, `aws.sdk` 2.49.4, `video4j` 2.0.0-SNAPSHOT, …) |
| root `pom.xml` | `vertx.version` 5.0.11, `avro.version` 1.12.0, protobuf/gRPC |
| `cortex/pom.xml` | Internal `io.metaloom.cortex:*` deps, `dagger.version`, `loom.version`, `loom.client.version` |

Never write a `<version>` for a managed dependency in a child POM.

---

## 3. Build Commands

| Goal | Command (from repo root unless noted) |
|---|---|
| Everything (Maven + UI + all containers) | `./build.sh` |
| Maven only, whole repo | `mvn -T 8 clean package -DskipTests` |
| Whole Cortex reactor | `mvn -T 8 clean package -DskipTests -f cortex/pom.xml` |
| Fast compile check | `mvn -T 8 test-compile -q -f cortex/pom.xml` |
| Shaded CLI JAR only | `mvn -T 8 clean package -DskipTests -pl cortex/cli -am` |
| CLI + container staging | `mvn -T 8 clean package -DskipTests -pl cortex/container,cortex/cli -am` |
| One module's tests | `mvn test -pl cortex/pipeline-core` / `-pl cortex/nodes/hash/core` |
| Integration tests | `./it.sh` (runs `PoolSetupRunner`, then `mvn verify -pl integration-test`) |
| E2E tests | `./e2e.sh` (builds demo container, starts Postgres + demo, runs `e2e-test`) |

`build.sh` performs, in order: `mvn -T 8 clean package -DskipTests` →
`loom-ui: npm run build` → `loom/containers/build-containers.sh all` →
`cortex/container/build-container.sh`.

**Dagger**: `DaggerCortexComponent` is generated under
`cortex/cli/target/generated-sources/annotations/`. After changing `@Module`,
`@Binds`, `@Provides` or `@IntoSet`, run a clean build of the affected module —
incremental compilation does not reliably re-run the processor.

---

## 4. Container Image

### 4.1 Contents (`cortex/container/Containerfile`)

Base `debian:trixie-slim`; produces `metaloom/cortex-server:${TAG:-latest}`.

| Layer | Source |
|---|---|
| CUDA 13.2 runtime (`cuda-cudart-13-2`, `libcublas-13-2`) | NVIDIA `debian13` repo via `cuda-keyring` |
| FFmpeg runtime libs (`libavcodec61`, `libavformat61`, `libavutil59`, `libswscale8`, `libswresample5`), `adduser` | Debian apt |
| **OpenCV 5.1 shared libs** → `/opt/opencv/lib` | Staged from a local OpenCV build by `build-container.sh` into `container/target/opencv-libs` |
| Temurin JRE 25.0.2+10 → `/opt/java25` | Downloaded from the adoptium GitHub release |
| InspireFace `Pikachu` model pack → `/cortex/packs` | HyperInspire GitHub release `v1.x` |
| `cortex-cli.jar`, `logback.xml` → `/cortex` | `container/target/cortex-cli/`, `container/logback.xml` |

CMD:
`/opt/java25/bin/java -Djna.tmpdir=/tmp/.jna -Duser.dir=/cortex -Dlogback.configurationFile=/cortex/logback.xml --enable-native-access=ALL-UNNAMED -jar cortex-cli.jar`

No arguments — Cortex has no CLI. `CortexMain` runs the worker and reads its configuration
from `cortex.yml` and the environment.

Runs as user `cortex` (uid 1000, group `root`/0). Exposes `8093`.
Volumes: `/config` (symlinked to `/cortex/config`) and `/meta`.

### 4.2 Building

```bash
mvn -T 8 clean package -DskipTests -pl cortex/container,cortex/cli -am
cortex/container/build-container.sh          # TAG=v1.0.0 to override the tag
```

`build-container.sh` stages OpenCV before calling docker: it requires
`libopencv_core.so.501` in `$OPENCV_LIB_DIR` and aborts with an explicit error
otherwise. The build context is the **repository root**, so `Containerfile`
paths are `./cortex/container/...`.

### 4.3 Build-script environment

| Env Var | Default | Purpose |
|---|---|---|
| `TAG` | `latest` | Image tag for `metaloom/cortex-server` |
| `OPENCV_LIB_DIR` | `<repo>/../opencv/build/lib` | Directory holding `libopencv_*.so.501` to stage into the image |

### 4.4 Image environment (defaults baked in)

| Env Var | Default | Description |
|---|---|---|
| `LOOM_HOST` | `loom` | Loom server hostname |
| `LOOM_PORT` | `8092` | Loom server HTTP port |
| `CORTEX_MONITORING_PORT` | `8093` | Monitoring HTTP port |
| `HOME` | `/cortex` | Home / working directory |
| `JAVA_TOOL_OPTIONS` | `-Xms256m -Xmx512m` | JVM heap |
| `JAVA_HOME` | `/opt/java25` | Temurin JRE 25 |
| `LD_LIBRARY_PATH` | `/opt/opencv/lib` | OpenCV 5.1 libraries |
| `PATH` | `/usr/local/cuda-13.2/bin:$PATH` | CUDA tools |
| `LOOM_TOKEN` | *(unset)* | Bearer token read by `LoomControlChannel` for WebSocket auth |

Runtime env mapping beyond these lives in [CONFIGURATION.md](CONFIGURATION.md). `CORTEX_NODE_ID` is
**not** baked in and is mandatory: without it `CortexMain` exits with code 2.

---

## 5. Shaded worker JAR

`cortex-cli` attaches a shaded artifact with classifier `combined`:
`cortex/cli/target/cortex-cli-1.0.0-SNAPSHOT-combined.jar`.
`cortex-container` copies it via `maven-dependency-plugin` to
`cortex/container/target/cortex-cli/cortex-cli.jar`.

| Shade setting | Value / reason |
|---|---|
| `shadedArtifactAttached` / `shadedClassifierName` | `true` / `combined` — the plain `cortex-cli.jar` stays unshaded |
| Filter `*:*` excludes | `META-INF/*.SF`, `*.DSA`, `*.RSA` (signature files break the uber JAR) |
| `ManifestResourceTransformer` | `Main-Class` = `io.metaloom.cortex.cli.CortexMain` |
| `ServicesResourceTransformer` | **Required** — merges `META-INF/services`; without it the AWS SDK `SdkHttpService` entry is overwritten and the shaded JAR fails at runtime with *"Unable to load an HTTP implementation from any provider in the chain"*. Classpath-based tests never catch this. |

Annotation processor on `cortex-cli`: `dagger-compiler`. Picocli is no longer a dependency of any
`cortex/` module.

---

## 6. Native Dependencies

Native libraries are **not** bundled in the JAR.

| Node(s) | Native / external | In the container? |
|---|---|---|
| thumbnail, quality, scene-detection, fingerprint, captioning | OpenCV 5.1 via `video4j` / `opencv-ffm` (`libopencv_ffm.so`, soname `.so.501`) | Yes — `/opt/opencv/lib` |
| facedetect | InspireFace (`libinspireface.so` + `Pikachu` pack) | Model pack yes (`/cortex/packs`); native lib via `video4j-facedetect-inspireface` |
| whisper | whisper.cpp via `asr4j` + GGML model | No — mount/install |
| ocr | Tesseract via `tess4j` + tessdata | No — install separately |
| script | GraalVM `js-community` (JVM-embedded) | Ships in the JAR |
| llm, vlm, tts, sentiment, depthmap, image/video-generation, captioning | HTTP sidecar services (`sidecars/`) or an OpenAI-compatible server (llama.cpp, vLLM, …) | No |
| hash, dedup, tika, consistency, watermark, dominant-color, scene-layout, s3-* | Pure Java | n/a |

**Do not** install Debian's `libopencv410*` packages instead of staging 5.1 — the
bundled `libopencv_ffm.so` links against soname `.so.501` and the container dies
on startup with `UnsatisfiedLinkError: libopencv_video.so.501`.

---

## 7. Test Setup

| Kind | Location | How to run |
|---|---|---|
| Unit tests | `src/test/java` in each module | `mvn test -pl cortex/<module>` |
| Pipeline / serde / control-channel tests | `cortex/pipeline-core`, `cortex/core` | `mvn test -pl cortex/pipeline-core` |
| Per-node E2E integration tests | `integration-test/` (depends on every `cortex-*-node` + `cortex-cli`) | `./it.sh` |
| E2E against a running backend | `e2e-test/` | `./e2e.sh` |

Test libraries: JUnit Jupiter, AssertJ, Testcontainers, `loom-test-env`
(inherited as a test dependency by every Cortex module from `cortex/pom.xml`).

Custom AssertJ assertions:

| Assert | Module |
|---|---|
| `LoomMediaAssert`, `NodeResultAssert`, `AbstractProcessableMediaAssert` | `cortex/core-media` |
| `PipelineResultAssert`, `PipelineNodeResultAssert` | `cortex/pipeline-core` |
| `CortexNodeOptionsAssert`, `ValidationResultAssert` | `cortex/api` |
| `<Node>OptionsAssert` (facedetect, thumbnail, tika, tts, watermark, …) | `cortex/nodes/<node>/core` |

Cortex unit tests need no database; `integration-test` requires the pooled test
database — run `./setup-pool.sh` first (or use `./it.sh`, which does it).

---

## 8. Conventions and Gotchas

- **`-pl cortex` does not build the nodes.** `cortex` is an aggregator POM;
  `-pl cortex` selects only that POM. Use `-f cortex/pom.xml` for the whole
  Cortex reactor, or `-pl cortex/<module> -am` for a slice.
- **Node artifact naming**: the module directory is `cortex/nodes/<name>`, the
  parent artifact is `cortex-<name>` (packaging `pom`), the jar is
  `cortex-<name>-node` in `<name>/core`. `filesystem-source`, `s3-source` and
  `cloud-source` are flat — no `core/` submodule.
- **`ServicesResourceTransformer` must stay in the shade config.** Removing it
  breaks the S3 nodes only in the shaded JAR (see §5). The cloud nodes are
  immune: their clients are hand-rolled `java.net.http` with no `ServiceLoader`
  and no `META-INF/services` of their own.
- **OpenCV 5.1, not 4.10** — see §6.
- **Rebuild both `cortex-cli` and `cortex-container`** after touching the shade
  config; the container module resolves the `combined` classifier from the repo.
- **Dagger regeneration**: clean-build after annotation changes; stale
  `DaggerCortexComponent` surfaces as `NoSuchMethodError` at startup.
- **Vert.x 5.0.11** (not 4) — use `io.vertx.core.http.WebSocketClient`.
- **RxJava 3** in the pipeline executor — do not mix with Reactor/CompletableFuture.
- **BOM only**: no `<version>` for managed deps in child POMs.
- **`UnsatisfiedLinkError`** → check `LD_LIBRARY_PATH` / `java.library.path`;
  the container also needs `--enable-native-access=ALL-UNNAMED` on JDK 25.
- **`build-container.sh` ignores its positional argument** (`target="${1:-all}"`
  is parsed but unused) — it always builds the single server image.
- **Docker build context is the repo root**, not `cortex/container/`.

---

## 9. Where do I find …?

| Need | Path |
|---|---|
| Cortex reactor POM | `cortex/pom.xml` |
| Node aggregator POM | `cortex/nodes/pom.xml` |
| External dependency versions | `bom/pom.xml`, root `pom.xml` |
| Compiler/plugin versions | `maven-parent` (sibling checkout `../maven-parent/pom.xml`) |
| Shade plugin config | `cortex/cli/pom.xml` |
| Entry point | `cortex/cli/src/main/java/io/metaloom/cortex/cli/CortexMain.java` |
| Dagger component | `cortex/cli/src/main/java/io/metaloom/cortex/cli/dagger/CortexComponent.java` |
| Dagger bindings | `cortex/core/src/main/java/io/metaloom/cortex/cli/dagger/CortexBindModule.java` |
| Env → options mapping | `cortex/common/src/main/java/io/metaloom/cortex/common/option/CortexEnvOptions.java` |
| Containerfile / build script / logging config | `cortex/container/{Containerfile,build-container.sh,logback.xml}` |
| Full build script | `build.sh` (repo root) |
| Integration / E2E scripts | `it.sh`, `e2e.sh`, `setup-pool.sh` (repo root) |
| Integration test module | `integration-test/` |
| Sidecar services | `sidecars/` |
| Custom node examples | `examples/cortex-custom-node/`, `examples/cortex-custom/`, `examples/cortex-python/` |

---

## 10. Progress Assessment

- [x] Prerequisites table
- [x] Complete module list incl. `s3-common`, `node-runtime` and all 26 node modules
- [x] Two-level node module layout documented
- [x] Version management (BOM / root POM / cortex POM) documented
- [x] Build commands verified against `build.sh`, `it.sh`, `e2e.sh`
- [x] Container image contents (CUDA 13.2, OpenCV 5.1 staging, Temurin 25.0.2+10, logback)
- [x] Build-script env vars (`TAG`, `OPENCV_LIB_DIR`) and image env vars
- [x] Shade plugin config incl. `ServicesResourceTransformer` rationale
- [x] Native dependency matrix
- [x] Test setup incl. `integration-test` / `e2e-test`
- [x] Conventions and gotchas
- [x] "Where do I find …?" cheat sheet
- [ ] CI/CD pipeline configuration (no CI config present in the repo)
- [ ] GraalVM native image build for Cortex (only Loom has native profiles — see [../loom/BUILD.md](../loom/BUILD.md))
- [ ] Release / publishing process
- [ ] `examples/cortex-python` is not wired into `examples/pom.xml` — decide whether it should be

---

_Git HEAD revision: `4dc0390a`_
_Last updated: 2026-08-03 (model-backed nodes now name an OpenAI-compatible server rather than Ollama)_
