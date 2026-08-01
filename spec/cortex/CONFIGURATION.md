# Cortex — Configuration Specification

> All configurable settings for a Cortex worker: CLI flags, environment
> variables, the (currently dead) YAML file, and per-node option classes.
>
> **Cross-references** — do not duplicate content from these:
> - [CORTEX.md](CORTEX.md) — architecture, module map, startup lifecycle
> - [BUILD.md](BUILD.md) — build, container image, native dependencies
> - [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) — system-wide view
> - [PIPELINE.md](../features/pipeline/PIPELINE.md) — pipeline engine & definition JSON
> - [NODES.md](../features/pipeline-nodes/NODES.md) — per-node behaviour and full option field tables
> - [HELM_CORTEX.md](../features/helm/HELM_CORTEX.md) — Kubernetes deployment values

---

## 1. Configuration Sources — what actually happens

| Layer | Source | Status |
|---|---|---|
| 1 (highest) | CLI flags on the `cortex` command (`CortexCLI`, picocli `@Option`) | ✅ works |
| 2 | Environment variables (`EnvDefaultProvider`, picocli `IDefaultValueProvider`) | ✅ works |
| 3 | Per-node parameters in the **pipeline definition JSON** from Loom (`PipelineConfigurable.configure(nodeDef)`) | ✅ works — the real per-node config path |
| 4 | YAML file `~/.config/metaloom/cortex.yml` | 🔴 **never read** on any CLI path — see §1.1 |
| 5 (lowest) | Code defaults in `CortexCLI` / `CortexOptions` / each `*NodeOptions` class | ✅ works |

### 1.1 🔴 Known gap: `cortex.yml` is never loaded

`CortexOptionsLoader` is fully implemented but unreachable from the CLI:

```
CortexCLIMain.execute(args)
  └── parseOptions(args)              // ALWAYS returns a non-null CortexOptions
  └── DaggerCortexComponent.builder().options(options)   // binds @Named("default-options")
        └── CortexClientModule.options(defaultOptions, loader)
              if (defaultOptions != null) return defaultOptions;   // ← always taken
              else return loader.load();                           // ← dead on the CLI path
```

Consequences an agent must account for:

- `CortexOptions.getNodes()` is **always empty** at runtime. `AbstractNodeModule.nodeOptions(opts, KEY, new XOptions())` therefore always returns the *default* options instance, so every `nodes:` key in §5 is currently documentation of a dormant mechanism, not live behaviour.
- Worker-level knobs with no CLI flag (`dryrun`, `maxConcurrentMedia`, `loom.token`, `s3.events.*` beyond the flags) are only settable programmatically or via env/flags where one exists.
- `CortexOptionsLoader.load()` runs `validateOptions()` (non-null `metaPath` + `CortexNodeOptions.validate()` per node) — that validation never executes either.
- **Container/Helm path mismatch (second, independent bug):** the loader resolves `${user.home}/.config/metaloom/cortex.yml`, i.e. `/cortex/.config/metaloom/cortex.yml` in the image (`HOME=/cortex`). The image and the Helm chart mount config at `/config` (symlinked to `/cortex/config`). Even if layer 4 were wired up, that file would not be found.

**Fix sketch** (not yet implemented): have `CortexCLIMain` pass `null` when no flag/env differs from the default, or merge the loaded YAML into the CLI-built `CortexOptions` inside `CortexClientModule.options(...)`, and align the container path with `CortexOptionsLoader.defaultConfigPath()`.

Only `CortexOptionsLoaderTest` (`cortex/common`) and direct `CortexCLIMain.execute(null, args)` callers exercise the YAML path.

---

## 2. Global CLI Flags & Environment Variables

Defined on `CortexCLI`, all `ScopeType.INHERIT` (available on every subcommand).
Env vars are resolved by `EnvDefaultProvider` as picocli *default values* — an explicit flag always wins.

### 2.1 Core flags

| CLI Flag | Short | Env Var | Default | Description |
|---|---|---|---|---|
| `--hostname` | `-h` | `LOOM_HOST` | `localhost` | Loom server hostname |
| `--port` | `-p` | `LOOM_PORT` | `7733` | Loom server HTTP port |
| `--monitoring-port` | — | `CORTEX_MONITORING_PORT` | `8093` | Cortex monitoring HTTP port (health/ready, S3 webhook) |
| `--meta-path` | — | `CORTEX_META_PATH` | `${user.home}/.cache/metaloom/cortex/meta` | Base path for metadata storage |
| `--node-id` | — | `CORTEX_NODE_ID` | — | Worker identity. **Required by `server start`** (`requireNodeId()`); unique per worker, stable across restarts |
| `--node-whitelist` | — | `CORTEX_NODE_WHITELIST` | — | Comma-split set of node kinds this worker executes. Unset/empty = anything |
| `--node-blacklist` | — | `CORTEX_NODE_BLACKLIST` | — | Comma-split set of refused node kinds. Wins over the whitelist |
| `--drain-timeout-ms` | — | `CORTEX_DRAIN_TIMEOUT_MS` | `30000` | Grace period for running tasks on shutdown before they are handed back to Loom |
| `-v` | — | — | — | Raise root logback level (see gotcha in §9) |

**On `--drain-timeout-ms`.** The default matches Kubernetes' 30 s termination grace period; waiting longer than the orchestrator is pointless because the process is killed mid-drain and tasks fall back to lease expiry. Raise it *together with* the orchestrator grace period for minutes-long nodes (whisper, OCR). `0` hands every running task straight back. See [CORTEX.md](CORTEX.md).

### 2.2 S3 flags

Worker-level rather than pipeline-node parameters, so credentials never enter the pipeline definition (stored in Postgres, rendered in the pipeline editor, which has no secret parameter type). Needed by **every** worker that touches `s3://` media — not only the one running `s3-source` — because media is materialized lazily by whichever worker runs a node task.

| CLI Flag | Env Var | Default | Description |
|---|---|---|---|
| `--s3-endpoint` | `CORTEX_S3_ENDPOINT` | — | Endpoint override (e.g. `http://minio:9000`); unset = real AWS |
| `--s3-region` | `CORTEX_S3_REGION` | `us-east-1` | S3 region |
| `--s3-access-key` | `CORTEX_S3_ACCESS_KEY` | — | Unset = AWS default credentials chain (env, profile, instance role, IRSA) |
| `--s3-secret-key` | `CORTEX_S3_SECRET_KEY` | — | S3 secret key |
| `--s3-path-style` | `CORTEX_S3_PATH_STYLE` | `true` when an endpoint is set, else `false` | Path-style bucket addressing (MinIO and most gateways need it) |
| `--s3-cache-path` | `CORTEX_S3_CACHE_PATH` | `<meta-path>/s3_bin` | Directory for materialized S3 objects |
| `--s3-index-path` | `CORTEX_S3_INDEX_PATH` | `<meta-path>/s3-index` | Directory for persisted per-bucket object indexes |
| `--s3-max-cache-bytes` | `CORTEX_S3_MAX_CACHE_BYTES` | `53687091200` (50 GiB) | Cache size budget; oldest evicted past it. `0` disables eviction |
| `--s3-max-object-size` | `CORTEX_S3_MAX_OBJECT_SIZE` | `0` | Largest object to materialize, in bytes. `0` = unbounded |
| `--s3-reconcile-interval-ms` | `CORTEX_S3_RECONCILE_INTERVAL_MS` | `21600000` (6 h) | How long the event fast path may be trusted before a full listing is forced |
| `--s3-events-enabled` | `CORTEX_S3_EVENTS_ENABLED` | `false` | Accept S3 bucket notifications so a run can skip listing |
| `--s3-events-mode` | `CORTEX_S3_EVENTS_MODE` | `WEBHOOK` | `WEBHOOK` (MinIO `notify_webhook`, posted to the monitoring port) or `SQS` |
| `--s3-events-webhook-path` | `CORTEX_S3_EVENTS_WEBHOOK_PATH` | `/s3-events` | Route registered on the monitoring server |
| `--s3-events-webhook-secret` | `CORTEX_S3_EVENTS_WEBHOOK_SECRET` | — | Shared secret in the `X-Cortex-S3-Token` header; required with webhook events |
| `--s3-events-queue-url` | `CORTEX_S3_EVENTS_QUEUE_URL` | — | SQS queue URL fed by S3 notifications |
| `--s3-events-max-buffered-keys` | `CORTEX_S3_EVENTS_MAX_BUFFERED_KEYS` | `50000` | Buffer ceiling; on overflow the next run falls back to a full listing |

### 2.3 Environment variables with no CLI flag

| Env Var | Read by | Description |
|---|---|---|
| `LOOM_TOKEN` | `LoomControlChannel.resolveToken()` | Bearer token for the Loom WebSocket handshake (`/api/v1/processors/ws?token=…`). Used only when `LoomClientOptions.token` is unset — and since §1.1 makes YAML dead, this env var is the **only** way to set it |
| `HOME` | JVM → `user.home` | Resolves the default meta path and the (unused) config path |
| `JAVA_TOOL_OPTIONS` | JVM | Heap/JVM options; set in the container image |

There are exactly two `System.getenv` call sites in `cortex/`: `EnvDefaultProvider` (the table in §2.1/§2.2) and `LoomControlChannel` (`LOOM_TOKEN`). Any other `CORTEX_*` name found in docs or charts is not read by the code.

### 2.4 Subcommands

| Command | Alias | Description |
|---|---|---|
| `cortex server start` | `cortex s start` | Start the worker: WebSocket to Loom, monitoring HTTP, pipeline execution. Fails fast without `--node-id` |
| `cortex process run <path>` | `cortex p run <path>` | Offline batch processing of a directory |

| Subcommand parameter | Flag | Effect |
|---|---|---|
| Actions (`process run`) | `-a` / `--actions` | Comma-separated node names passed to `MediaProcessor.process(actionList, folder)` |
| Path (`process run`) | positional index 0 | Directory to process |
| Actions (`server start`) | `-a` / `--actions` | **Accepted but unused** — the parameter is never read; nodes come from Dagger + the pipeline definition |

---

## 3. CortexOptions (root config object)

`io.metaloom.cortex.api.option.CortexOptions`.

| Field | Type | Default | Set by |
|---|---|---|---|
| `nodes` | `Map<String, CortexNodeOptions>` | empty map | YAML only → **always empty**, see §1.1 |
| `loom` | `LoomClientOptions` | new instance | `--hostname` / `--port` |
| `s3` | `S3ClientOptions` | new instance | `--s3-*` flags |
| `dryrun` | `boolean` | `false` | no flag, no env — programmatic only |
| `metaPath` | `Path` | `null` (CLI supplies `~/.cache/metaloom/cortex/meta`) | `--meta-path` |
| `monitoringPort` | `int` | `8093` | `--monitoring-port` |
| `nodeId` | `String` | `null` | `--node-id` |
| `nodeWhitelist` / `nodeBlacklist` | `Set<String>` | `null` | `--node-whitelist` / `--node-blacklist` |
| `drainTimeoutMs` | `long` | `30000` (clamped `>= 0`) | `--drain-timeout-ms` |
| `maxConcurrentMedia` | `int` | `4` | no flag, no env — programmatic only |

### 3.1 Default node timeouts

`CortexOptions.getDefaultTimeoutMs(nodeType)` backs an immutable built-in map. `RegistryNodeRegistrar.adapt()` uses it only when the pipeline definition sets no `timeoutMs`; the lookup key is the definition's `type` field (falling back to the node's `name()`), **not** the YAML config key of §5.

| ms | Node types |
|---|---|
| 30000 | `sha512`, `sha256`, `md5` |
| 60000 | `chunk-hash`, `quality`, `dedup`, `consistency`, `loom-sync` |
| 120000 | `thumbnail`, `tika` |
| 300000 | `facedetect`, `fingerprint`, `ocr`, `captioning`, `scene-detection` |
| 600000 | `whisper`, `llm`, `vlm` |

A type absent from the map yields `0` = no timeout.

### 3.2 LoomClientOptions / S3ClientOptions / S3EventOptions

| Class | Fields |
|---|---|
| `LoomClientOptions` | `hostname` (null; CLI `localhost`), `port` (`0`; CLI `7733`), `token` (null → `LOOM_TOKEN`) |
| `S3ClientOptions` | `endpoint`, `region`, `accessKey`, `secretKey`, `pathStyleAccess` (`Boolean`, null = derive from endpoint), `cachePath`, `indexPath`, `maxCacheBytes`, `maxObjectSize`, `reconcileIntervalMs`, `events` — defaults per §2.2 |
| `S3EventOptions` | `enabled`, `mode` (`WEBHOOK`/`SQS`), `webhookPath`, `webhookSecret`, `queueUrl`, `maxBufferedKeys` — setters coerce null/non-positive back to the defaults |

`S3ClientOptions.isConfigured()` is true when *any* of endpoint / accessKey / region is set — and `region` defaults to `us-east-1`, so it is effectively always true.

---

## 4. Where per-node configuration actually comes from

```
Loom pipeline definition JSON  ──► RegistryNodeRegistrar.adapt(nodeDef)
   id / mode / blocking / concurrency / syncToLoom / timeoutMs   ──► CortexNodeAdapter
   remaining keys ──► PipelineConfigurable.configure(nodeDef)    ──► node instance fields

cortex.yml `nodes:` ──► CortexNodeOptionDeserializer ──► CortexOptions.nodes ──► 🔴 never populated
                                                          AbstractNodeModule.nodeOptions(...)
                                                             └── falls through to `new XOptions()` defaults
```

Only nodes declaring `PipelineConfigurable` see the definition; everything else runs on its code defaults.

---

## 5. Registered YAML node keys

Every node module contributes a `CortexNodeOptionDeserializerInfo(optionsClass, prefix)` via Dagger `@Provides @IntoSet`. The prefix is the key under `nodes:`; duplicates throw at startup. The list below is the complete, verified registration set — note it is **not** identical to the node-kind names used in pipeline definitions.

| YAML key | Options class | Module |
|---|---|---|
| `captioning` | `CaptioningNodeOptions` | `nodes/captioning` |
| `consistency` | `ConsistencyNodeOptions` | `nodes/consistency` |
| `dedup` | `DedupNodeOptions` | `nodes/dedup` |
| `depthmap` | `DepthmapNodeOptions` | `nodes/depthmap` |
| `dominant-color` | `DominantColorNodeOptions` | `nodes/dominant-color` |
| `facedetection` | `FacedetectNodeOptions` | `nodes/facedetect` |
| `filesystem-source` | `FilesystemSourceNodeOptions` | `nodes/filesystem-source` |
| `fingerprint` | `FingerprintNodeOptions` | `nodes/fingerprint` |
| `fingerprint-dedup` | `FingerprintDedupDiscoverOptions` | `nodes/dedup` |
| `fingerprint-dedup-apply` | `DedupNodeOptions` | `nodes/dedup` |
| `hash` | `HashNodeOptions` | `nodes/hash` |
| `imagegen` | `ImageGenNodeOptions` | `nodes/image-generation` |
| `llm` | `LLMNodeOptions` | `nodes/llm` |
| `ocr` | `OCRNodeOptions` | `nodes/ocr` |
| `quality` | `QualityNodeOptions` | `nodes/quality` |
| `s3-sink` | `S3SinkNodeOptions` | `nodes/s3-sink` |
| `s3-source` | `S3SourceNodeOptions` | `nodes/s3-source` |
| `scene-detector` | `SceneDetectionOptions` | `nodes/scene-detection` |
| `scene-layout` | `SceneLayoutNodeOptions` | `nodes/scene-layout` |
| `script` | `ScriptNodeOptions` | `nodes/script` |
| `sentiment` | `SentimentNodeOptions` | `nodes/sentiment` |
| `thumbnail` | `ThumbnailNodeOptions` | `nodes/thumbnail` |
| `tika` | `TikaNodeOptions` | `nodes/tika` |
| `tts` | `TtsNodeOptions` | `nodes/tts` |
| `videogen` | `VideoGenNodeOptions` | `nodes/video-generation` |
| `vlm` | `VlmNodeOptions` | `nodes/vlm` |
| `watermark` | `WatermarkNodeOptions` | `nodes/watermark` |
| `whisper` | `WhisperOptions` | `nodes/whisper` |

### 5.1 Common fields (`AbstractNodeOptions<T>`)

| Field | Type | Default |
|---|---|---|
| `enabled` | `boolean` | `true` |
| `processIncomplete` | `boolean` | `false` |
| `retryFailed` | `boolean` | `false` |
| `timeoutMs` | `long` | `0` (no timeout); `validateCommon()` rejects negatives |

### 5.2 Selected node defaults

Spot-check values; the authoritative per-field tables live in [NODES.md](../features/pipeline-nodes/NODES.md) §5.

| Key | Notable fields (defaults) |
|---|---|
| `filesystem-source` | `path` (null), `pathGlobs` (`[]`, wins over `path`), `emitStates` (`NEW, MODIFIED, MOVED`), `indexPath` (null → derived from `metaPath`) |
| `hash` | `md5`, `sha256`, `sha512`, `chunkHash` — **all `true`** |
| `facedetection` | `videoChopRate` 5, `videoScaleSize` 384, `minFaceHeightFactor` 0.05, `faceClusterMinimum` 2, `faceClusterEPS` 0.6, `inspirefacePackPath` `packs/Pikachu`, `capabilities` `{INSPIREFACE}` |
| `whisper` | `modelPath` `models/ggml-large-v3-turbo.bin`, `temperature` 0.0, `temperatureInc` 0.2, `language` null, `useGpu` true, `gpuDevice` 0 |
| `thumbnail` | `cols` 6, `rows` 1, `tileSize` 384 |
| `ocr` | `tessDataPath` `/usr/share/tesseract-ocr/5/tessdata`, `language` `eng` |
| `quality` | `checkBlurriness`, `checkResolution`, `checkVideoBitrate`, `checkAudioBitrate` — all `true` |
| `dedup` | `dupFolder` `duplicates` |
| `llm` | `ollamaUrl` `http://127.0.0.1:11434`, `providerType` `OLLAMA`, `prompts` map |
| `vlm` | `endpointUrl`, `apiKey`, `prompts` (`VlmNodePrompt`) |
| `s3-source` | `bucket`, `prefix`, `suffixes`, `emitStates`, `startAfter`, `useEvents` |
| `s3-sink` | `bucket`, `keyTemplate`, `includeSource`, `createAssets` (true), `overwrite` `IF_DIFFERENT`, `deleteAfterUpload`, `maxArtifacts`, `maxArtifactBytes`, `failOnPartial` (true) |
| `script` | `engine`, `script`, `outputs`, `params`, `trusted` (true), `allowNetwork`/`allowFilesystem` (false), `statementLimit`, `maxOutputBytes`, `maxLogLines` |
| `tika`, `consistency`, `fingerprint`, `scene-detector` | no fields beyond §5.1 |

### 5.3 Example `cortex.yml` (aspirational — see §1.1)

```yaml
dryrun: false
metaPath: /cortex/meta
monitoringPort: 8093
maxConcurrentMedia: 4
loom:
  hostname: loom
  port: 7733
  token: secret
nodes:
  filesystem-source:
    enabled: true
    path: /media/library
    emitStates: [NEW, MODIFIED, MOVED]
  hash:
    enabled: true
    sha512: true
    chunkHash: false
  whisper:
    modelPath: /models/ggml-tiny.bin
    language: en
```

---

## 6. Pipeline-Level Configuration

Read from the pipeline definition JSON by `RegistryNodeRegistrar.adapt()`.

| JSON key | `PipelineNode` accessor | Default | Notes |
|---|---|---|---|
| `id` | `id()` | node `name()` | Must match `^[a-z0-9]([a-z0-9\-]{0,62}[a-z0-9])?$` |
| `mode` | `mode()` | `PARALLEL` | `SEQUENTIAL` \| `PARALLEL` |
| `blocking` | `isBlocking()` | `true` | Downstream waits for completion |
| `concurrency` | `concurrency()` | `1` | Must be `> 0`, else `IllegalStateException` |
| `syncToLoom` | `syncToLoom()` | `false` | Batch results for bulk upload |
| `timeoutMs` | `timeoutMs()` | `0` → §3.1 default by `type` | Must be `>= 0` |
| `type` | — | node `name()` | Keys the default-timeout lookup |
| *(remaining keys)* | `options()` | empty map | Delivered via `PipelineConfigurable.configure(nodeDef)` |
| — | `conditionalDependencies()` | empty map | Filter-branch dependencies |
| — | `cacheProvider()` | `null` | Optional `NodeCacheProvider` |

### 6.1 Source node selection (`filesystem-source`)

```json
{ "id": "filesystem-source", "type": "filesystem-source",
  "path": "/media/inbox", "pathGlobs": ["/media/inbox/**.mp4"] }
```

Resolution order: definition `pathGlobs` → definition `path` → configured `filesystem-source` defaults (🔴 currently unreachable, §1.1). A node with no selection from any source throws `IllegalArgumentException` in the `FilesystemSourceNode` constructor rather than silently processing nothing.

A run request may override the selection via `pathGlobs` in the `SOURCE_TASK` options. A request whose selection resolves to nothing does *not* fall back to the node's configuration — that would widen the run from the requested items to the whole configured tree.

Root (`path`) mode performs a **differential** scan against a persisted per-root index and emits only files whose `FileState` is in `emitStates`; glob mode always re-walks and emits every match.

See [PIPELINE.md](../features/pipeline/PIPELINE.md) and [NODES.md](../features/pipeline-nodes/NODES.md) §4 (`MediaSourceNode`).

---

## 7. Container Configuration

`cortex/container/Containerfile` — see [BUILD.md](BUILD.md) for the full image.

| Env Var | Value | Note |
|---|---|---|
| `LOOM_HOST` | `loom` | |
| `LOOM_PORT` | `8092` | ⚠️ differs from the CLI default `7733` |
| `CORTEX_MONITORING_PORT` | `8093` | |
| `HOME` | `/cortex` | |
| `JAVA_TOOL_OPTIONS` | `-Xms256m -Xmx512m` | |
| `JAVA_HOME` | `/opt/java25` | |
| `LD_LIBRARY_PATH` | `/opt/opencv/lib` | |

Entrypoint: `java … -Duser.dir=/cortex -jar cortex-cli.jar server start` — note it supplies **no `--node-id`**, so a bare `podman run` of the image fails `requireNodeId()` unless `CORTEX_NODE_ID` is set.

| Volume | Note |
|---|---|
| `/config` | symlinked to `/cortex/config`; ⚠️ *not* the loader's `${user.home}/.config/metaloom/` path (§1.1) |
| `/meta` | metadata storage; pair with `CORTEX_META_PATH=/meta` |

---

## 8. Key Classes Reference

| Class | Package | Purpose |
|---|---|---|
| `CortexCLIMain` | `io.metaloom.cortex.cli` | Entry point; pre-parses args into `CortexOptions`, builds the Dagger component |
| `CortexCLI` | `io.metaloom.cortex.cli` | Picocli root command; all global flags; `toCortexOptions()` |
| `EnvDefaultProvider` | `io.metaloom.cortex.cli` | Picocli `IDefaultValueProvider` mapping long option names → env vars |
| `AbstractLoomWorkerCommand` | `io.metaloom.cortex.cli.cmd` | Shared subcommand base; `requireNodeId()` |
| `ServerCommand` / `ProcessCommand` | `io.metaloom.cortex.cli.cmd` | `server start` / `process run` |
| `CortexComponent` | `io.metaloom.cortex.cli.dagger` | Dagger component; `@BindsInstance @Named("default-options")` |
| `CortexClientModule` | `io.metaloom.cortex.cli.dagger` | Provides `CortexOptions` and the Loom REST client — site of the §1.1 gap |
| `S3Module` | `io.metaloom.cortex.cli.dagger` | Builds the S3 store/materializer; derives cache (`s3_bin`) and index (`s3-index`) dirs from `metaPath` |
| `RegistryNodeRegistrar` | `io.metaloom.cortex.cli.dagger` | Adapts pipeline-definition JSON to `PipelineNode`; timeout defaults; `PipelineConfigurable.configure` |
| `CortexOptions` | `io.metaloom.cortex.api.option` | Root config object + built-in default timeouts |
| `LoomClientOptions` / `S3ClientOptions` / `S3EventOptions` | `io.metaloom.cortex.api.option` | Connection, cache and event settings |
| `CortexNodeOptions` / `AbstractNodeOptions` | `io.metaloom.cortex.api.option.node` | Node options interface / base with `enabled`, `processIncomplete`, `retryFailed`, `timeoutMs`, `validate()` |
| `ValidationResult` | `io.metaloom.cortex.api.option.node` | Result of `CortexNodeOptions.validate()` |
| `CortexOptionsLoader` | `io.metaloom.cortex.common.option` | Loads/saves `cortex.yml`, generates defaults, validates — **currently unreachable** |
| `CortexNodeOptionDeserializer` | `io.metaloom.cortex.common.option` | Polymorphic Jackson deserializer keyed by node prefix |
| `CortexNodeOptionDeserializerInfo` | `io.metaloom.cortex.common.option` | `(optionsClass, prefix)` registration |
| `AbstractNodeModule` | `io.metaloom.cortex.common.node` | `nodeOptions(cortexOptions, key, defaults)` lookup helper |
| `LoomControlChannel` | `io.metaloom.cortex.impl.loom` | `resolveToken()` — `LoomClientOptions.token` → `LOOM_TOKEN` |
| `CortexEnv` | `io.metaloom.cortex` | `CORTEX_CONF_FILENAME = "cortex.yml"` |

---

## 9. Conventions and Gotchas

- 🔴 **`cortex.yml` is dead code on the CLI path** (§1.1). Never assume a `nodes:` block takes effect; per-node runtime config comes from the pipeline definition.
- **`-v` has an off-by-one bug**: `setVerbose` sets `DEBUG` when `verbose.length > 0` and then immediately `TRACE` when `verbose.length >= 1`. Every `-v` therefore yields `TRACE`; `-vv` is not distinct. The `-v` row in §2.1 documents the *intent*, not the behaviour.
- **Three separate name spaces** — do not conflate: YAML config prefix (§5, e.g. `scene-detector`), pipeline-definition `type` used for default timeouts (§3.1, e.g. `scene-detection`), and the node kind used for whitelist/blacklist matching. `scene-detector` vs `scene-detection` is a real, existing mismatch.
- **Unknown YAML handling is asymmetric**: an unknown key under `nodes:` logs `WARN "Did not find module options class for mapping {}"` and deserializes to `null`; an unknown *field inside* a known node block is silently dropped (`FAIL_ON_UNKNOWN_PROPERTIES = false`). Duplicate prefixes throw `RuntimeException("Invalid configuration mapping")` at startup.
- **`--node-id` is mandatory for `server start`** and only there. `process run` works without it.
- **Env vars are defaults, not overrides**: `EnvDefaultProvider` supplies a value only when the flag is absent from the command line. A flag set to its own default value is still "explicitly set" as far as picocli is concerned.
- **`LOOM_TOKEN` is the only auth path** today (§2.3) — there is no `--token` flag.
- **Monitoring port doubles as the S3 webhook listener** (`--s3-events-webhook-path`), so it is not purely a health endpoint. Keep it distinct from the Loom port.
- **`--s3-path-style` defaults are conditional**: unset means "on when `--s3-endpoint` is set". Passing `false` explicitly against MinIO will break addressing.
- **Meta path is load-bearing beyond metadata**: S3 cache (`s3_bin`), S3 index (`s3-index`) and the filesystem-source differential index are all derived from it when not set explicitly.
- **`dryrun` and `maxConcurrentMedia` have no flag or env var** — they exist on `CortexOptions` but cannot be set from a deployment today. `dryrun` is honoured only by `HashDedupNode` and `FingerprintDedupApplyNode`; other nodes ignore it.
- **`drainTimeoutMs` is clamped** to `>= 0` by the setter; negative input becomes `0`.
- **`server start -a/--actions` is accepted and ignored.** Do not document it as functional.

---

## 10. Test Setup

| What | Where |
|---|---|
| YAML load/validate/serialize round-trip | `cortex/common/src/test/java/io/metaloom/cortex/common/option/CortexOptionsLoaderTest.java` — sets `user.home=target/fakehome`; asserts the config file is **not** auto-written on load and that `nodes` stays empty |
| CLI flag parsing | `cortex/core/src/test/java/io/metaloom/cortex/cli/CortexCLITest.java` |
| Dagger component wiring | `cortex/cli/src/test/java/io/metaloom/cortex/cli/CortexComponentTest.java` |
| `process run` end-to-end | `cortex/cli/src/test/java/io/metaloom/cortex/cli/ProcessCommandTest.java` |
| Definition → node adaptation | `cortex/cli/src/test/java/io/metaloom/cortex/cli/dagger/{NodeRegistrarTest,PipelineConfigurableTest}.java` |
| Dummy options for deserializer tests | `cortex/common/src/test/java/io/metaloom/cortex/common/node/dummy/DummyOptions.java` |

Env-var behaviour is not covered by a test; `EnvDefaultProvider` reads `System.getenv` directly, which is not settable from JUnit without a wrapper. Adding one is a prerequisite for regression-testing §2.

---

## 11. Where do I find …?

| Need | Path |
|---|---|
| CLI flags / entry point | `cortex/core/src/main/java/io/metaloom/cortex/cli/CortexCLI.java`, `cortex/cli/src/main/java/io/metaloom/cortex/cli/CortexCLIMain.java` |
| Env var mapping | `cortex/core/src/main/java/io/metaloom/cortex/cli/EnvDefaultProvider.java` |
| Subcommands | `cortex/core/src/main/java/io/metaloom/cortex/cli/cmd/` |
| The §1.1 options gap | `cortex/core/src/main/java/io/metaloom/cortex/cli/dagger/CortexClientModule.java` (`options(...)`) |
| Root config class | `cortex/api/src/main/java/io/metaloom/cortex/api/option/CortexOptions.java` |
| Loom / S3 option classes | `cortex/api/src/main/java/io/metaloom/cortex/api/option/{LoomClientOptions,S3ClientOptions,S3EventOptions}.java` |
| Node options base class | `cortex/api/src/main/java/io/metaloom/cortex/api/option/node/AbstractNodeOptions.java` |
| YAML loader / deserializer | `cortex/common/src/main/java/io/metaloom/cortex/common/option/` |
| Node options lookup helper | `cortex/common/src/main/java/io/metaloom/cortex/common/node/AbstractNodeModule.java` |
| A node's options + its YAML key | `cortex/nodes/<node>/core/src/main/java/…/{XNodeOptions,XNodeModule}.java` |
| Definition → node adaptation, timeouts | `cortex/cli/src/main/java/io/metaloom/cortex/cli/dagger/RegistryNodeRegistrar.java` |
| S3 cache/index path derivation | `cortex/core/src/main/java/io/metaloom/cortex/cli/dagger/S3Module.java` |
| Token resolution | `cortex/core/src/main/java/io/metaloom/cortex/impl/loom/LoomControlChannel.java` |
| Container env vars & volumes | `cortex/container/Containerfile` |

---

## 12. Progress Assessment

- [x] Configuration sources documented against actual behaviour, YAML gap flagged
- [x] Core CLI flag / env var table complete and verified against `CortexCLI` + `EnvDefaultProvider`
- [x] S3 flag / env var table (16 flags) documented
- [x] Env vars without a CLI flag enumerated (`LOOM_TOKEN`, `HOME`, `JAVA_TOOL_OPTIONS`)
- [x] `CortexOptions`, `LoomClientOptions`, `S3ClientOptions`, `S3EventOptions` fields + defaults
- [x] Built-in default-timeout map documented
- [x] Complete registered YAML node key → options class table (28 entries)
- [x] Pipeline-definition configuration keys and `PipelineConfigurable` path
- [x] Container configuration and its config-path mismatch
- [x] Key classes reference, gotchas, cheat sheet, test setup
- [ ] **Fix the §1.1 gap in code** — wire `CortexOptionsLoader` into `CortexClientModule.options(...)` and align the container config path
- [ ] **Fix the `-v` / `-vv` level bug** in `CortexCLI.setVerbose`
- [ ] Reconcile the `scene-detector` / `scene-detection` naming mismatch
- [ ] Expose `dryrun` and `maxConcurrentMedia` as flags/env vars, or drop them
- [ ] Add a testable env-var indirection so §2 can be regression-tested
- [ ] Per-node exhaustive field tables live in [NODES.md](../features/pipeline-nodes/NODES.md) §5 — verify they match §5.2 here

---

_Git HEAD revision: `2e5981cb`_
_Last updated: 2026-08-01 (Rewritten against the code: YAML loader gap documented, S3 flags added, node key table completed.)_
