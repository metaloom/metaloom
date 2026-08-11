[![Maven Central](https://maven-badges.sml.io/sonatype-central/io.metaloom/loom/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.metaloom.loom/loom)
[![License](https://img.shields.io/:license-apache-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)
[![Stack Overflow](https://img.shields.io/:stack%20overflow-metaloom-brightgreen.svg)](http://stackoverflow.com/questions/tagged/metaloom)

> ## ⚠️ State of development
>
> **MetaLoom is still under active development and not yet in a usable state.**
> APIs, database schema and container images change without notice, no release has been published,
> and the container images listed below are **not** deployed to any public registry yet.
> Everything has to be built from source.

# MetaLoom

MetaLoom is a DAM which consists of Loom (Backend Server) and Cortex (Processing Node).

📖 **Full documentation: [https://metaloom.io/docs/](https://metaloom.io/docs/)** —
[Loom](https://metaloom.io/docs/loom/) ·
[Cortex](https://metaloom.io/docs/cortex/) ·
[Pipelines](https://metaloom.io/docs/pipeline/) ·
[Processing Nodes](https://metaloom.io/docs/nodes/) ·
[Getting Started](https://metaloom.io/docs/getting-started/) ·
[Playbooks](https://metaloom.io/docs/playbooks/)

# MetaLoom - Loom

Loom is an advanced media asset management system designed to ease the management processes for digital media assets. With a decoupled processing mechanism, Loom separates processing of media assets, providing users with greater flexibility and scalability. Loom supports a wide range of industry-standard protocols, including REST, gRPC, and GraphQL, making it easy to integrate with other systems and workflows. In addition, Loom offers powerful features such as fingerprinting and face detection, enabling users to easily search, categorize, and manage their media assets.

[![](https://dcbadge.vercel.app/api/server/3Dy2SxKUtw)](https://discord.gg/3Dy2SxKUtw)

## Features at a Glance

### Asset management

* [Assets, libraries, collections and asset pools](https://metaloom.io/docs/loom/features/)
* [Binary storage](https://metaloom.io/docs/loom/binary-storage/) on disk or S3, selectable per library
* [Tagging](https://metaloom.io/docs/loom/features/#_tags) and ratings
* [Similarity search](https://metaloom.io/docs/loom/features/#_search_and_similarity) and [search indices](https://metaloom.io/docs/loom/search-indices/)
* [Permission system](https://metaloom.io/docs/loom/features/#_permissions) with users, groups and roles
* [Authentication](https://metaloom.io/docs/loom/authentication/) via JWT bearer tokens, API keys and optional OAuth2
* [Web UI](https://metaloom.io/docs/ui/) for assets, persons, pipelines, monitoring and administration

### Pipelines and processing

* [Pipelines](https://metaloom.io/docs/pipeline/) — DAGs of processing nodes, authored in the UI and versioned in the database
* [Typed ports](https://metaloom.io/docs/pipeline/#ports) connecting nodes, with implicit fan-out and gather
* [Debug mode](https://metaloom.io/docs/pipeline/#debug-mode) — halt a run at a node and inspect what it actually produced
* Horizontally scalable execution: Loom owns the graph and dispatches tasks to registered [Cortex](https://metaloom.io/docs/cortex/) workers
* [Custom nodes](https://metaloom.io/docs/cortex/custom-nodes/), including [nodes written in Python](https://metaloom.io/docs/playbooks/python-node/)

### Workflows (human in the loop)

Keyboard-driven bulk review screens where a node proposes and a human decides — see the
[Workflow section of the UI docs](https://metaloom.io/docs/ui/).

* Rating and tagging — tab through a set of assets, one keystroke per decision
* [Deduplication review](https://metaloom.io/docs/nodes/dedup/) — confirm or reject near-duplicate groups before anything is moved
* Face review — confirm detected faces and assign them to persons
* Object detection review — confirm or reject detected objects

### Processing nodes

* [Metadata extraction](https://metaloom.io/docs/nodes/tika/) and [image/video metadata](https://metaloom.io/docs/nodes/metadata/)
* [Asset hashing](https://metaloom.io/docs/nodes/hash/) and [consistency checks](https://metaloom.io/docs/nodes/consistency/)
* [Thumbnail generation](https://metaloom.io/docs/nodes/thumbnail/) and [image manipulation](https://metaloom.io/docs/nodes/image-manipulation/)
* [Video fingerprinting](https://metaloom.io/docs/nodes/fingerprint/) and [deduplication](https://metaloom.io/docs/nodes/dedup/)
* [Face detection](https://metaloom.io/docs/nodes/facedetect/) and [face description](https://metaloom.io/docs/nodes/facedescription/)
* [Object detection](https://metaloom.io/docs/nodes/objectdetect/), [segmentation (SAM2)](https://metaloom.io/docs/nodes/sam2/) and [depth maps](https://metaloom.io/docs/nodes/depthmap/)
* [Scene detection](https://metaloom.io/docs/nodes/scene-detection/) and [scene layout](https://metaloom.io/docs/nodes/scene-layout/)
* [Speech recognition (Whisper)](https://metaloom.io/docs/nodes/whisper/), [OCR](https://metaloom.io/docs/nodes/ocr/), [translation](https://metaloom.io/docs/nodes/translate/) and [text-to-speech](https://metaloom.io/docs/nodes/tts/)
* [LLM](https://metaloom.io/docs/nodes/llm/), [VLM](https://metaloom.io/docs/nodes/vlm/), [captioning](https://metaloom.io/docs/nodes/captioning/) and [sentiment](https://metaloom.io/docs/nodes/sentiment/)
* [Image generation](https://metaloom.io/docs/nodes/imagegen/), [video generation](https://metaloom.io/docs/nodes/videogen/) and [watermarking](https://metaloom.io/docs/nodes/watermark/)
* Sources and sinks: [filesystem](https://metaloom.io/docs/nodes/filesystem-source/), [S3 in](https://metaloom.io/docs/nodes/s3-source/) / [S3 out](https://metaloom.io/docs/nodes/s3-sink/), [Google Drive](https://metaloom.io/docs/nodes/gdrive-source/), [OneDrive](https://metaloom.io/docs/nodes/onedrive-source/)
* [Full node catalogue](https://metaloom.io/docs/nodes/)

### APIs and integration

* [REST API](https://metaloom.io/docs/loom/rest-api/)
* [GraphQL API](https://metaloom.io/docs/loom/graphql-api/)
* gRPC API
* [MCP server](https://metaloom.io/docs/loom/mcp/) — connect Claude Desktop, an IDE or your own agent
* [Chat & AI agent](https://metaloom.io/docs/loom/chat/) with sessions, skills, memory and an optional coding sandbox
* [Java client](https://metaloom.io/docs/loom/java-client/) and [Python client](https://metaloom.io/docs/loom/python-client/)
* [CLI](https://metaloom.io/docs/cli/)

### Operations

* [Configuration](https://metaloom.io/docs/loom/configuration/) via YAML file and environment variables
* [Prometheus metrics](https://metaloom.io/docs/loom/metrics/) and [Cortex monitoring](https://metaloom.io/docs/cortex/monitoring/)
* [Helm charts](https://metaloom.io/docs/deployment/helm/) for Kubernetes and a [Docker deployment playbook](https://metaloom.io/docs/playbooks/docker/)

## Building

### Requirements

| Requirement | Version | Needed for |
|---|---|---|
| JDK | 25 | Everything (`<release>25</release>`) |
| Maven | 3.9+ | Everything |
| Node.js / npm | 24 / 11 (current dev setup) | `loom-ui` build |
| Docker | recent | Container images, tests using containers |
| GraalVM | 25 (`GRAALVM_HOME`, default `/opt/jvm/graalvm-25`) | Native images and the native CLI binary only |
| PostgreSQL | 17 | Runtime and the test database pool (started via `./start-postgres.sh`) |

Optional, for individual Cortex nodes at build/run time — Cortex builds and runs without them, the
affected nodes simply stay disabled or fail:

| Dependency | Needed by |
|---|---|
| OpenCV 5.1 (local build, soname `.so.501`) | Cortex container image; video4j-backed nodes (thumbnail, fingerprint, scene detection, …) |
| InspireFace native lib + model pack | `facedetect` node |
| whisper.cpp (via `asr4j`) | `whisper` node |
| Tesseract 5+ (via `tess4j`) | `ocr` node |
| Sidecar services (see [`sidecars/`](sidecars/)) | tts, depthmap, image-generation, video-generation, sentiment, llm, vlm, captioning |

### Build

Full build — Maven reactor, UI bundle, Loom container images and the Cortex container image:

```bash
./build.sh
```

The individual stages, if you want to run them separately:

```bash
# 1. Java (whole reactor, no tests)
mvn -T 8 clean package -DskipTests

# 2. UI  ->  loom-ui/build/
cd loom-ui && npm run build

# 3. Loom container images  (jvm|native|both) (demo|server|all)
cd loom/containers && ./build-containers.sh all

# 4. Cortex container image
cd cortex/container && ./build-container.sh
```

Useful helper scripts in the repository root:

| Script | What it does |
|---|---|
| `./setup-pool.sh` | Provisions and populates the pooled test databases (see [Testing](#testing)) |
| `./it.sh` | Sets up the pool, then runs the integration tests |
| `./e2e.sh` | Builds the demo image and runs the end-to-end test suite against it |
| `./ui.sh` | `npm run dev` in `loom-ui` |
| `./start-postgres.sh`, `./start-minio.sh` | Start the backing services on the local `dev` docker network |
| `./start-demo.sh`, `./start-server.sh`, `./start-cortex.sh` | Run the built images locally |
| `cli/build-native.sh` | GraalVM native binary for the `metaloom` CLI (not part of `build.sh`) |

More detail: [`spec/loom/BUILD.md`](spec/loom/BUILD.md) and [`spec/cortex/BUILD.md`](spec/cortex/BUILD.md).

## Container Images

> **These images are not published yet.** There is no public registry deployment — the tags below
> are what the local build scripts produce, and every deployment currently has to build them itself.

| Image | Built by | Contents |
|---|---|---|
| `metaloom/loom-server:latest` | `loom/containers/build-containers.sh jvm server` | Loom server, shaded jar on `eclipse-temurin:25-jre-alpine`, UI at `/loom/ui` |
| `metaloom/loom-server:latest-native` | `loom/containers/build-containers.sh native server` | Same, as a GraalVM native binary on `debian:stable-slim` |
| `metaloom/loom-demo:latest` | `loom/containers/build-containers.sh jvm demo` | Loom with demo data and the AI agent memory enabled |
| `metaloom/loom-demo:latest-native` | `loom/containers/build-containers.sh native demo` | Native variant of the demo image |
| `metaloom/cortex-server:latest` | `cortex/container/build-container.sh` | Cortex worker; needs a locally built OpenCV 5.1 tree staged into the build context |

Override the tag with `TAG=<tag>`; native builds get `-native` appended automatically.

The [Helm charts](helm/) default to `metaloom/loom-server` and `metaloom/cortex-server` — point
`image.repository` / `image.tag` at your own registry until official images exist.

## Testing

All DAO/Database and some integration tests utilize the a prefilled database test pool.

1. This requires the start of the pool provider + database

```bash
cd test-database
podman-compose  up -d
```

2. The pool must initially be setup using the `io.metaloom.loom.test.PoolSetupRunner` from the `loom-fixture` project.

```bash
./setup-pool.sh
```

Re-run `./setup-pool.sh` after any Flyway migration change, otherwise the pooled databases are stale
relative to the new schema.

## License

Apache License, Version 2.0

## Attribution

Portions of the code in this project were co-authored with the assistance of AI.
