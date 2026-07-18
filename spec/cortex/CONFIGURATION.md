# Cortex — Configuration Specification

> This document covers all configurable settings for Cortex: the YAML
> config file, CLI flags, environment variables, and per-node options.
> It is a companion to [CORTEX.md](CORTEX.md) (general overview) and
> [NODES.md](../features/pipeline-nodes/NODES.md) (node-specific options).
>
> **Cross-references**:
> - [CORTEX.md](CORTEX.md) — Architecture, module map, startup lifecycle
> - [BUILD.md](BUILD.md) — Build, container, native dependencies
> - [PIPELINE.md](../features/pipeline/PIPELINE.md) — Pipeline engine configuration
> - [NODES.md](../features/pipeline-nodes/NODES.md) — Per-node options classes and MetaStorage

---

## 1. Configuration Sources (Priority Order)

Cortex resolves configuration from multiple sources in this order
(higher priority overrides lower):

| Priority | Source | Description |
|---|---|---|
| 1 (highest) | CLI flags | Picocli `@Option` parameters on `cortex` command |
| 2 | Environment variables | Resolved by `EnvDefaultProvider` as fallback for CLI flags |
| 3 | YAML config file | `~/.config/metaloom/cortex.yml` (loaded by `CortexOptionsLoader`) |
| 4 (lowest) | Code defaults | Hardcoded defaults in `CortexCLI` and `CortexOptions` |

### 1.1 Config File Location

The config file is named `cortex.yml` (constant: `CortexEnv.CORTEX_CONF_FILENAME`)
and is loaded from:

```
~/.config/metaloom/cortex.yml
```

If the file does not exist, `CortexOptionsLoader.load()` logs an info
message and generates a default `CortexOptions` with empty node settings.

### 1.2 YAML Format

The config file is parsed by Jackson with a YAML factory. Unknown
properties are silently ignored (`FAIL_ON_UNKNOWN_PROPERTIES = false`).
Node options are polymorphic — the deserializer key is the node's config
prefix (e.g. `"hash"`, `"whisper"`, `"facedetection"`).

```yaml
dryrun: false
metaPath: /cortex/meta
monitoringPort: 8093
loom:
  hostname: loom
  port: 7733
  token: ${LOOM_TOKEN}
nodes:
  filesystem-source:
    enabled: true
    # Default selection, used when a pipeline definition supplies neither
    # `path` nor `pathGlobs` for its filesystem-source node.
    path: /media/library
    pathGlobs:
      - "/media/library/**.mp4"
  hash:
    enabled: true
    sha512: true
    sha256: false
    md5: false
    chunkHash: false
  facedetection:
    enabled: true
    videoChopRate: 10
    videoScaleSize: 320
  whisper:
    enabled: true
    modelPath: /models/ggml-tiny.bin
    language: en
```

---

## 2. Global CLI Flags & Environment Variables

These are the top-level flags on the `cortex` command (defined in
`CortexCLI`). They are inherited by all subcommands via
`ScopeType.INHERIT`.

| CLI Flag | Short | Env Var | Default | Description |
|---|---|---|---|---|
| `--hostname` | `-h` | `LOOM_HOST` | `localhost` | Loom server hostname |
| `--port` | `-p` | `LOOM_PORT` | `7733` | Loom server HTTP port |
| `--monitoring-port` | — | `CORTEX_MONITORING_PORT` | `8093` | Monitoring HTTP port (health/ready) |
| `--meta-path` | — | `CORTEX_META_PATH` | `~/.cache/metaloom/cortex/meta` | Base path for metadata storage |
| `-v` | — | — | — | Verbosity: `-v` = DEBUG, `-vv` = TRACE |

### 2.1 Additional Environment Variables

| Env Var | Description |
|---|---|
| `LOOM_TOKEN` | Bearer token for Loom WebSocket authentication. Used when `LoomClientOptions.token` is not set explicitly. |
| `JAVA_TOOL_OPTIONS` | JVM options (e.g. `-Xms256m -Xmx512m`). Set in the container image. |
| `HOME` | Used to resolve the default config path (`~/.config/metaloom/cortex.yml`) and meta path. |

### 2.2 Subcommands

| Command | Alias | Description |
|---|---|---|
| `cortex server start` | `cortex s start` | Start the Cortex server (WebSocket to Loom, monitoring HTTP, pipeline execution) |
| `cortex process run` | `cortex p run` | Process files using configured actions (offline batch mode) |

#### `cortex process run`

```
cortex process run [-a <actions>] <path>
```

| Parameter | Flag | Description |
|---|---|---|
| Actions | `-a` / `--actions` | Comma-separated list of node names to enable (e.g. `hash,thumbnail`) |
| Path | (positional) | Directory path to process |

#### `cortex server start`

```
cortex server start [-a <actions>]
```

| Parameter | Flag | Description |
|---|---|---|
| Actions | `-a` / `--actions` | Comma-separated list of node names (currently informational; nodes are always registered via Dagger) |

---

## 3. CortexOptions (Root Config Object)

`CortexOptions` is the root configuration object, defined in
`io.metaloom.cortex.api.option.CortexOptions`.

| Field | Type | Default | Description |
|---|---|---|---|
| `nodes` | `Map<String, CortexNodeOptions>` | empty map | Per-node options, keyed by node config prefix |
| `loom` | `LoomClientOptions` | new `LoomClientOptions()` | Loom backend connection settings |
| `dryrun` | `boolean` | `false` | Global dry-run mode (nodes log but don't mutate) |
| `metaPath` | `Path` | `null` (CLI default: `~/.cache/metaloom/cortex/meta`) | Base path for metadata storage files |
| `monitoringPort` | `int` | `8093` | Monitoring HTTP port |

### 3.1 LoomClientOptions

Defined in `io.metaloom.cortex.api.option.LoomClientOptions`.

| Field | Type | Default | Description |
|---|---|---|---|
| `hostname` | `String` | `null` (CLI: `localhost`) | Loom server hostname |
| `port` | `int` | `0` (CLI: `7733`) | Loom server HTTP port |
| `token` | `String` | `null` | Bearer token for WebSocket auth; falls back to `LOOM_TOKEN` env var |

---

## 4. Per-Node Options

Every node has its own options class extending `AbstractNodeOptions<T>`.
Options are registered for deserialization via Dagger
`@Provides CortexNodeOptionDeserializerInfo` in each node's Dagger
module. The deserializer key (prefix) must match the node's `KEY`
constant.

### 4.1 Common Options (AbstractNodeOptions)

| Field | Type | Default | Description |
|---|---|---|---|
| `enabled` | `boolean` | `true` | Whether the node is active |
| `processIncomplete` | `boolean` | `false` | Whether to process incomplete media |
| `retryFailed` | `boolean` | `false` | Whether to retry previously failed items |

### 4.2 Per-Node Options Reference

| Node | Config Key | Options Class | Key Fields |
|---|---|---|---|
| Filesystem Source | `filesystem-source` | `FilesystemSourceNodeOptions` | `path` (String), `pathGlobs` (List&lt;String&gt;) — defaults for the source node's selection; `pathGlobs` wins over `path` |
| Hash | `hash` | `HashNodeOptions` | `md5`, `sha256`, `sha512`, `chunkHash` (booleans) |
| Facedetect | `facedetection` | `FacedetectNodeOptions` | `videoChopRate`, `videoScaleSize`, `faceClusterMinimum`, `faceClusterEPS`, `minFaceHeightFactor`, `inspirefacePackPath`, `capabilities` |
| Whisper | `whisper` | `WhisperOptions` | `modelPath`, `temperature`, `temperatureInc`, `language`, `useGpu`, `gpuDevice` |
| Quality | `quality` | `QualityNodeOptions` | `checkBlurriness`, `checkResolution`, `checkVideoBitrate`, `checkAudioBitrate` |
| Thumbnail | `thumbnail` | `ThumbnailNodeOptions` | `tileSize`, `cols`, `rows` |
| OCR | `ocr` | `OCRNodeOptions` | `tessDataPath`, `language` |
| LLM | `llm` | `LLMNodeOptions` | `ollamaUrl`, `prompts` (Map of prompt configs) |
| Captioning | `captioning` | `CaptioningNodeOptions` | `smolVLMHost`, `smolVLMPort` |
| Dedup | `dedup` | `DedupNodeOptions` | `dupFolder` (Path) |
| Scene | `scene-detection` | `SceneDetectionOptions` | (no custom fields) |
| Consistency | `consistency` | `ConsistencyNodeOptions` | (no custom fields) |
| Tika | `tika` | `TikaNodeOptions` | (tika-specific fields) |
| Loom | `loom` | `LoomNodeOptions` | (no custom fields) |

> **See [NODES.md](../features/pipeline-nodes/NODES.md) Section 5** for the full node options reference
> with all fields and their types.

---

## 5. Pipeline-Level Configuration

Pipeline nodes carry configuration via the `PipelineNode` interface.
These are set programmatically or loaded from the Loom server's pipeline
definition JSON.

| Property | Method | Default | Description |
|---|---|---|---|
| Node ID | `id()` | — | Unique identifier within the pipeline (regex: `^[a-z0-9]([a-z0-9\-]{0,62}[a-z0-9])?$`) |
| Mode | `mode()` | `PARALLEL` | `SEQUENTIAL` or `PARALLEL` |
| Blocking | `isBlocking()` | `true` | Whether downstream nodes wait for completion |
| Concurrency | `concurrency()` | `1` | Max concurrent executions (semaphore permits) |
| Sync to Loom | `syncToLoom()` | `false` | Whether results are batched for bulk upload |
| Options | `options()` | empty map | Arbitrary key-value config for pipeline-loaded nodes |
| Cache provider | `cacheProvider()` | `null` | Optional `NodeCacheProvider` for result caching |

### 5.1 Source Node Selection (`filesystem-source`)

The `filesystem-source` node declares which media a pipeline processes. Its
selection is read from the node's own entry in the pipeline definition JSON:

```json
{
  "id": "filesystem-source",
  "type": "filesystem-source",
  "path": "/media/inbox",
  "pathGlobs": ["/media/inbox/**.mp4", "/archive/*.mkv"]
}
```

| Key | Type | Description |
|---|---|---|
| `path` | String | Root directory, walked recursively |
| `pathGlobs` | String[] | Globs to expand; **takes precedence** over `path` |

Resolution order for the selection is: definition `pathGlobs` → definition
`path` → configured `filesystem-source` defaults (§4.2). A node that ends up
with no selection from any of these is rejected at construction rather than
silently processing nothing.

Because the node implements `MediaSourceNode`, a pipeline built around it is run
without the caller supplying media:

```java
pipelineExecutor.execute(pipeline, runContext);
```

A `run-pipeline` work order may still override the selection with its own
`pathGlobs`. When it supplies **no** selection parameters, the source node's
configuration decides what is processed. A work order that requests a selection
which resolves to nothing does *not* fall back to the source node — that would
widen the run from the requested items to the node's entire configured tree.

> **See [PIPELINE.md](../features/pipeline/PIPELINE.md)** for the full pipeline configuration
> and execution model, and [NODES.md](../features/pipeline-nodes/NODES.md) §4 for the
> `MediaSourceNode` contract.

---

## 6. Dagger Wiring of Configuration

### 6.1 Options Loading

```
CortexCLIMain.parseOptions(args)
  └── CortexCLI.toCortexOptions()     // CLI flags → CortexOptions
      └── LoomClientOptions(hostname, port)

DaggerCortexComponent.builder().options(options).build()
  └── CortexClientModule.options(defaultOptions, loader)
      └── if defaultOptions != null → use CLI options
          else → CortexOptionsLoader.load() → cortex.yml
```

### 6.2 Node Options Deserialization

Each node module provides a `CortexNodeOptionDeserializerInfo` via
Dagger `@Provides`. The `CortexNodeOptionDeserializer` collects all
such providers into a map keyed by the node's config prefix. When the
YAML is parsed, each key under `nodes:` is matched to the corresponding
options class.

### 6.3 EnvDefaultProvider

`EnvDefaultProvider` (picocli `IDefaultValueProvider`) maps CLI option
names to environment variables:

| CLI Option | Environment Variable |
|---|---|
| `--hostname` | `LOOM_HOST` |
| `--port` | `LOOM_PORT` |
| `--monitoring-port` | `CORTEX_MONITORING_PORT` |
| `--meta-path` | `CORTEX_META_PATH` |

---

## 7. Container Configuration

The container image (`cortex/container/Containerfile`) sets these
defaults:

| Env Var | Default | Description |
|---|---|---|
| `LOOM_HOST` | `loom` | Loom server hostname (container name) |
| `LOOM_PORT` | `8092` | Loom server HTTP port |
| `CORTEX_MONITORING_PORT` | `8093` | Monitoring HTTP port |
| `HOME` | `/cortex` | Home directory |
| `JAVA_TOOL_OPTIONS` | `-Xms256m -Xmx512m` | JVM heap settings |

Volumes:

| Volume | Mount | Purpose |
|---|---|---|
| `/config` | symlinked to `/cortex/config` | Config files (e.g. `cortex.yml`) |
| `/meta` | — | Metadata storage |

---

## 8. Key Classes Reference

| Class | Package | Purpose |
|---|---|---|
| `CortexOptions` | `io.metaloom.cortex.api.option` | Root config object |
| `LoomClientOptions` | `io.metaloom.cortex.api.option` | Loom connection settings |
| `CortexNodeOptions` | `io.metaloom.cortex.api.option.node` | Interface for per-node options |
| `AbstractNodeOptions` | `io.metaloom.cortex.api.option.node` | Base class with `enabled`, `processIncomplete`, `retryFailed` |
| `CortexOptionsLoader` | `io.metaloom.cortex.common.option` | Loads `cortex.yml`, generates default config |
| `CortexNodeOptionDeserializer` | `io.metaloom.cortex.common.option` | Polymorphic YAML deserializer for node options |
| `CortexNodeOptionDeserializerInfo` | `io.metaloom.cortex.common.option` | Registration class (prefix → options class) |
| `CortexCLI` | `io.metaloom.cortex.cli` | Picocli command root with global flags |
| `EnvDefaultProvider` | `io.metaloom.cortex.cli` | Maps CLI options to env vars |
| `CortexEnv` | `io.metaloom.cortex` | Constants (`CORTEX_CONF_FILENAME = "cortex.yml"`) |

---

## 9. Conventions and Gotchas

- **Config file is optional**: If `cortex.yml` does not exist, Cortex
  starts with a default `CortexOptions` (all nodes disabled, no Loom
  connection, monitoring port 8093).
- **YAML unknown properties are ignored**: The Jackson mapper is
  configured with `FAIL_ON_UNKNOWN_PROPERTIES = false`. This means
  typos in node config keys are silently ignored — check logs for
  warnings.
- **Node config prefix**: Each node registers a prefix (e.g. `"hash"`,
  `"facedetection"`) for YAML deserialization. This prefix must match
  the key in the `nodes:` map. Conflicting prefixes cause a startup
  error.
- **CLI overrides config**: CLI flags take priority over env vars,
  which take priority over the YAML config file. The `EnvDefaultProvider`
  only provides defaults when the CLI flag is not explicitly set.
- **Token resolution**: The Loom auth token is resolved from
  `LoomClientOptions.token()` first, then falls back to the
  `LOOM_TOKEN` environment variable. This is done in
  `LoomControlChannel.resolveToken()`.
- **Monitoring port vs Loom port**: The monitoring port (8093) is for
  Cortex's own health/ready endpoints. The Loom port (7733) is where
  the Loom server listens. They must not conflict.
- **Meta path**: The `metaPath` is used by `MetaStorage` for sidecar
  files and filesystem-based metadata. In the container, it defaults to
  `/cortex/meta` (via the `/meta` volume). On the host, it defaults to
  `~/.cache/metaloom/cortex/meta`.
- **Dry-run mode**: When `dryrun=true`, nodes should log their intended
  actions but not mutate state. This is a global flag on `CortexOptions`;
  individual nodes must check `options.isDryrun()` in their `compute()`
  method.

---

## 10. Where do I find …?

| Need | Look here |
|---|---|
| Root config class | `cortex/api/src/main/java/io/metaloom/cortex/api/option/CortexOptions.java` |
| Loom connection options | `cortex/api/src/main/java/io/metaloom/cortex/api/option/LoomClientOptions.java` |
| Node options base class | `cortex/api/src/main/java/io/metaloom/cortex/api/option/node/AbstractNodeOptions.java` |
| Config file loader | `cortex/common/src/main/java/io/metaloom/cortex/common/option/CortexOptionsLoader.java` |
| Node options deserializer | `cortex/common/src/main/java/io/metaloom/cortex/common/option/CortexNodeOptionDeserializer.java` |
| CLI flags | `cortex/cli/src/main/java/io/metaloom/cortex/cli/CortexCLI.java` |
| Env var provider | `cortex/cli/src/main/java/io/metaloom/cortex/cli/EnvDefaultProvider.java` |
| Config constant | `cortex/api/src/main/java/io/metaloom/cortex/CortexEnv.java` |
| Container env vars | `cortex/container/Containerfile` |

---

## 11. Progress Assessment

- [x] Configuration sources and priority order documented
- [x] YAML config file format documented with example
- [x] CLI flags table with env var mappings
- [x] CortexOptions fields documented
- [x] LoomClientOptions fields documented
- [x] Per-node options reference (cross-ref to NODES.md)
- [x] Pipeline-level configuration (cross-ref to PIPELINE.md)
- [x] Dagger wiring of configuration documented
- [x] Container configuration documented
- [x] Key classes reference table
- [x] Conventions and gotchas
- [x] "Where do I find" cheat sheet
- [ ] Per-node options detailed field tables (see NODES.md Section 5)
- [ ] Config validation rules (beyond null-check on metaPath)
