# MetaLoom // Cortex

*MetaLoom // Cortex* is the **worker** of the *MetaLoom* Headless Media Asset Management System from
[MetaLoom](https://metaloom.io/). It executes media processing tasks — hashing, fingerprinting, face
detection, thumbnailing, metadata extraction and more — on behalf of a *Loom* server.

Cortex is a container, not a command line tool. There are no subcommands and no flags:
[`CortexMain`](cli/src/main/java/io/metaloom/cortex/cli/CortexMain.java) assembles the Dagger graph,
connects to Loom and runs in the foreground until it is signalled to stop. Everything is configured
through `cortex.yml` and the environment.

[![](https://dcbadge.vercel.app/api/server/3Dy2SxKUtw)](https://discord.gg/3Dy2SxKUtw)

## How it works

Cortex holds no database and no pipeline graph. Loom owns the pipeline definition and walks it;
Cortex receives one concrete unit of work at a time and reports the result back.

1. **Dial out.** The worker opens a WebSocket to Loom at `/api/v1/processors/ws`
   ([`LoomControlChannel`](core/src/main/java/io/metaloom/cortex/impl/loom/LoomControlChannel.java)).
   Loom never connects to a worker, so workers may sit behind NAT with no inbound ports.
2. **Register.** It sends a `REGISTER` frame carrying its worker id, capabilities and the node kinds
   it is willing to run (its whitelist, narrowed by its blacklist).
3. **Announce contracts.** After `REGISTERED` it sends `NODE_REGISTRATION` with the descriptors of
   the nodes it can actually execute — the intersection of
   [`RegistryNodeFactory#registeredTypes`](core/src/main/java/io/metaloom/cortex/pipeline/loader/RegistryNodeFactory.java)
   with its whitelist — so those nodes can be authored in Loom's pipeline editor.
4. **Execute dispatches.** Loom pushes `NODE_TASK`, `SEGMENT_TASK` and `SOURCE_TASK` frames down the
   connection the worker already opened;
   [`PipelineTaskHandler`](core/src/main/java/io/metaloom/cortex/impl/loom/PipelineTaskHandler.java)
   runs them and returns results.

A worker **requires a stable id**: `CORTEX_NODE_ID` (or `nodeId` in `cortex.yml`). Loom keys
registration, node-kind restrictions and run attribution on it and rejects a second worker
announcing an id already in use, so a missing id is a hard startup failure — `CortexMain` exits with
`EXIT_INVALID_CONFIGURATION` (2) before ever going online.

If no Loom endpoint is configured the worker starts *offline*: no WebSocket, no tasks, no sync. It
simply idles, because nothing drives work. Note that a worker with no `cortex.yml` defaults to
`localhost:7733` — offline requires explicitly clearing the `loom` section.

## Capabilities

Node implementations live under [`nodes/`](nodes/), one Maven module per node kind. A worker only
runs the kinds it was built with and announced.

**Hashing** — asset hashing with multiple methods (sha512, sha256, md5). Identical files produce the
same hash regardless of location, which is what deduplication is built on.

**Fingerprinting** — content fingerprints that identify media by what it *is* rather than by its
bytes, for tracking copyrighted material or monitoring user-generated content.

**Face detection** — detects and locates faces in media assets and extracts embeddings, the
numerical representations used to recognise and compare faces across assets. Detected faces also
drive the optimal focal point for cropped and resized images.

**Thumbnails** — generates thumbnail images for media assets with configurable size, format and
quality.

**Metadata extraction** — file format, resolution, creation date, camera settings and more, so
assets can be organised, searched and filtered.

**Consistency checks** — detects inconsistencies in the format, metadata and content of media
assets, catching corruption and file errors before they matter.

Beyond these the reactor also ships source and sink nodes (filesystem, S3, cloud), transcription,
OCR, object and scene detection, LLM/VLM nodes, generation and manipulation nodes, and filter/guard
nodes. See [../spec/features/nodes/NODES.md](../spec/features/nodes/NODES.md) for the per-node
reference.

Cortex still does not require importing or moving the content it parses — files stay where they are.
Extracted information is sent to Loom, which is the system of record.
[`XAttrs`](fs/src/main/java/io/metaloom/cortex/fs/XAttrs.java) additionally caches a computed SHA-512
in the `loom_sha512` extended attribute so a re-run does not have to re-digest the file. That is a
local cache and an optimisation — not the product's storage model, and not something Loom reads.

## Deployment

Cortex is deployed as a long-running container, typically several of them.

| Artefact | Purpose |
|---|---|
| [`container/Containerfile`](container/Containerfile) | The worker image: JRE 25, CUDA and OpenCV runtime, InspireFace model pack, `cortex-cli.jar` |
| [`container/build-container.sh`](container/build-container.sh) | Builds that image locally (run `mvn package -pl cortex/container,cortex/cli -am` first) |
| [`../helm/cortex`](../helm/cortex) | Helm chart — a StatefulSet, so `CORTEX_NODE_ID` can come from the stable pod name |
| [`../start-cortex.sh`](../start-cortex.sh) | Minimal `docker run` for a local worker against a dev Loom |

Configuration is read from `cortex.yml` (mounted at `/config`) and the environment; `LOOM_HOST` /
`LOOM_PORT` point at Loom and `CORTEX_MONITORING_PORT` (default 8093) exposes health and readiness.
The full variable list is in
[../spec/cortex/CONFIGURATION.md](../spec/cortex/CONFIGURATION.md).

## Documentation

* [../spec/cortex/CORTEX.md](../spec/cortex/CORTEX.md) — module layout, startup lifecycle,
  online/offline mode, node-kind registration, monitoring
* [../spec/cortex/METALOOM_ARCHITECTURE.md](../spec/cortex/METALOOM_ARCHITECTURE.md) — the Loom ↔
  Cortex boundary: registration, wire protocol, dispatch, results, deployment
* [../spec/cortex/BUILD.md](../spec/cortex/BUILD.md) — Maven build, dependency versions, native
  dependencies
* [https://metaloom.io/docs/cortex/](https://metaloom.io/docs/cortex/) — user documentation

## State

* In development

## Releasing

TBD

## License

Apache License, Version 2.0.
