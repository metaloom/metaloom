# Cortex — Configuration Specification

> All configurable settings for a Cortex worker: environment variables, the
> `cortex.yml` file, and per-node option classes.
>
> **Cortex has no command line.** It ships as a container and is started by
> `CortexMain` with no arguments; the environment is the only runtime override.
>
> **Cross-references** — do not duplicate content from these:
> - [CORTEX.md](CORTEX.md) — architecture, module map, startup lifecycle
> - [BUILD.md](BUILD.md) — build, container image, native dependencies
> - [METALOOM_ARCHITECTURE.md](METALOOM_ARCHITECTURE.md) — system-wide view
> - [PIPELINE.md](../features/pipeline/PIPELINE.md) — pipeline engine & definition JSON
> - [NODES.md](../features/nodes/NODES.md) — per-node behaviour and full option field tables
> - [HELM_CORTEX.md](../features/helm/HELM_CORTEX.md) — Kubernetes deployment values

---

## 1. Configuration Sources — what actually happens

| Layer | Source | Status |
|---|---|---|
| 1 (highest) | Environment variables (`CortexEnvOptions.applyEnv`) | ✅ works |
| 2 | Per-node parameters in the **pipeline definition JSON** from Loom (`PipelineConfigurable.configure(nodeDef)`) | ✅ works — the real per-node config path |
| 3 | YAML file `~/.config/metaloom/cortex.yml` | ✅ works — but see the container path caveat in §1.2 |
| 4 (lowest) | Code defaults in `CortexOptionsLoader.generateDefaultConfig()` / `CortexOptions` / each `*NodeOptions` class | ✅ works |

Everything is resolved once, inside the Dagger graph:

```
CortexMain.main()                       // no arguments, ever
  └── DaggerCortexComponent.builder().options(null)   // @Named("default-options") = null
        └── CortexClientModule.options(null, loader)
              └── CortexOptionsLoader.load()
                    ├── load(~/.config/metaloom/cortex.yml)   // or generateDefaultConfig()
                    ├── CortexEnvOptions.applyEnv(options)    // environment wins
                    └── validateOptions(options)
```

`@Named("default-options")` is non-null only when a test or an embedding application builds
the component itself; that instance is then used verbatim, with neither the YAML file nor the
environment applied on top.

### 1.1 Defaults when there is no `cortex.yml`

`generateDefaultConfig()` supplies `metaPath = ${user.home}/.cache/metaloom/cortex/meta` and
`loom = localhost:7733`, so a worker with no config file and no `LOOM_HOST` is *online against
localhost* rather than offline. True offline mode means a `cortex.yml` whose `loom` section is
cleared — a loaded file is never patched up with those defaults, and `applyEnv` will not
re-create a `loom` section that the file removed.

### 1.2 🔴 Known gap: the container's config path

`CortexOptionsLoader` resolves `${user.home}/.config/metaloom/cortex.yml`, i.e.
`/cortex/.config/metaloom/cortex.yml` in the image (`HOME=/cortex`). The image and the Helm
chart mount config at `/config` (symlinked to `/cortex/config`), so a mounted `cortex.yml` is
**not** picked up. Either mount it at the loader's path or align
`CortexOptionsLoader.defaultConfigPath()` with `/config`. Everything below `nodes:` in §5
depends on this.

---

## 2. Environment Variables

Applied by `CortexEnvOptions` (`cortex/common`). A variable that is unset **or blank** is
skipped, so an empty value in a StatefulSet does not blank out a configured setting. Values are
parsed strictly: a bad integer, a non-`true`/`false` boolean or an unknown enum aborts the
startup instead of reading as a default.

Log levels are configured through logback (`-Dlogback.configurationFile`), not through a
variable — the former `-v` flag is gone.

### 2.1 Core variables

| Env Var | Default | Description |
|---|---|---|
| `LOOM_HOST` | `localhost` (no config file) | Loom server hostname |
| `LOOM_PORT` | `7733` (no config file) | Loom server HTTP port |
| `CORTEX_MONITORING_PORT` | `8093` | Cortex monitoring HTTP port (health/ready, S3 webhook) |
| `CORTEX_META_PATH` | `${user.home}/.cache/metaloom/cortex/meta` | Base path for metadata storage |
| `CORTEX_NODE_ID` | — | Worker identity. **Required** — `CortexMain` exits with code 2 when it is missing; unique per worker, stable across restarts |
| `CORTEX_NODE_WHITELIST` | — | Comma-split set of node kinds this worker executes. Unset/empty = anything |
| `CORTEX_NODE_BLACKLIST` | — | Comma-split set of refused node kinds. Wins over the whitelist |
| `CORTEX_DRAIN_TIMEOUT_MS` | `30000` | Grace period for running tasks on shutdown before they are handed back to Loom |

**On `CORTEX_DRAIN_TIMEOUT_MS`.** The default matches Kubernetes' 30 s termination grace period; waiting longer than the orchestrator is pointless because the process is killed mid-drain and tasks fall back to lease expiry. Raise it *together with* the orchestrator grace period for minutes-long nodes (whisper, OCR). `0` hands every running task straight back. See [CORTEX.md](CORTEX.md).

### 2.2 S3 variables

Worker-level rather than pipeline-node parameters, so credentials never enter the pipeline definition (stored in Postgres, rendered in the pipeline editor, which has no secret parameter type). Needed by **every** worker that touches `s3://` media — not only the one running `s3-source` — because media is materialized lazily by whichever worker runs a node task.

| Env Var | Default | Description |
|---|---|---|
| `CORTEX_S3_ENDPOINT` | — | Endpoint override (e.g. `http://minio:9000`); unset = real AWS |
| `CORTEX_S3_REGION` | `us-east-1` | S3 region |
| `CORTEX_S3_ACCESS_KEY` | — | Unset = AWS default credentials chain (env, profile, instance role, IRSA) |
| `CORTEX_S3_SECRET_KEY` | — | S3 secret key |
| `CORTEX_S3_PATH_STYLE` | `true` when an endpoint is set, else `false` | Path-style bucket addressing (MinIO and most gateways need it) |
| `CORTEX_S3_CACHE_PATH` | `<meta-path>/s3_bin` | Directory for materialized S3 objects |
| `CORTEX_S3_INDEX_PATH` | `<meta-path>/s3-index` | Directory for persisted per-bucket object indexes |
| `CORTEX_S3_MAX_CACHE_BYTES` | `53687091200` (50 GiB) | Cache size budget; oldest evicted past it. `0` disables eviction |
| `CORTEX_S3_MAX_OBJECT_SIZE` | `0` | Largest object to materialize, in bytes. `0` = unbounded |
| `CORTEX_S3_RECONCILE_INTERVAL_MS` | `21600000` (6 h) | How long the event fast path may be trusted before a full listing is forced |
| `CORTEX_S3_EVENTS_ENABLED` | `false` | Accept S3 bucket notifications so a run can skip listing |
| `CORTEX_S3_EVENTS_MODE` | `WEBHOOK` | `WEBHOOK` (MinIO `notify_webhook`, posted to the monitoring port) or `SQS` |
| `CORTEX_S3_EVENTS_WEBHOOK_PATH` | `/s3-events` | Route registered on the monitoring server |
| `CORTEX_S3_EVENTS_WEBHOOK_SECRET` | — | Shared secret in the `X-Cortex-S3-Token` header; required with webhook events |
| `CORTEX_S3_EVENTS_QUEUE_URL` | — | SQS queue URL fed by S3 notifications |
| `CORTEX_S3_EVENTS_MAX_BUFFERED_KEYS` | `50000` | Buffer ceiling; on overflow the next run falls back to a full listing |

### 2.3 Google Drive variables

Worker-level for the same reason as the S3 variables, with one sharper edge: `ParameterType` has no `SECRET`, so a service-account key placed on a node definition would be stored in Postgres and rendered as plain text in the pipeline editor. Needed by **every** worker that touches `gdrive://` media — not only the one running `gdrive-source`.

Google is considered configured when **either** a service-account key **or** a complete `clientId`+`clientSecret`+`refreshToken` set is present. A partially filled set is a hard failure at boot naming the missing variable, not a silent "not configured": turning a typo into a missing capability produces a dead run rather than a startup error.

| Env Var | Default | Description |
|---|---|---|
| `CORTEX_GDRIVE_SERVICE_ACCOUNT_JSON` | — | Service-account key as inline JSON. **The production credential**: it does not expire and needs no interactive consent |
| `CORTEX_GDRIVE_SERVICE_ACCOUNT_FILE` | — | Path to the same key. An alternative to the inline form, not a fallback for it |
| `CORTEX_GDRIVE_IMPERSONATE_SUBJECT` | — | User to impersonate through domain-wide delegation; required to read a specific person's My Drive |
| `CORTEX_GDRIVE_CLIENT_ID` | — | OAuth client id, for refresh-token auth |
| `CORTEX_GDRIVE_CLIENT_SECRET` | — | OAuth client secret |
| `CORTEX_GDRIVE_REFRESH_TOKEN` | — | 🔴 **Development only.** Tokens from an app in Google's "Testing" publishing status expire after 7 days and cannot be renewed unattended |
| `CORTEX_GDRIVE_SCOPES` | `https://www.googleapis.com/auth/drive.readonly` | Space-separated OAuth scopes |
| `CORTEX_GDRIVE_API_BASE_URL` | `https://www.googleapis.com` | Overridable so the client can be pointed at a stub server in tests |
| `CORTEX_GDRIVE_TOKEN_URL` | `https://oauth2.googleapis.com/token` | OAuth token endpoint |
| `CORTEX_GDRIVE_DEFAULT_DRIVE_ID` | — | Shared drive used when a node names none; unset = the credential's My Drive |
| `CORTEX_GDRIVE_CACHE_PATH` | `<meta-path>/gdrive_bin` | Directory for materialized files |
| `CORTEX_GDRIVE_INDEX_PATH` | `<meta-path>/gdrive-index` | Directory for persisted scan indexes |
| `CORTEX_GDRIVE_MAX_CACHE_BYTES` | `53687091200` (50 GiB) | Cache budget; `0` disables eviction |
| `CORTEX_GDRIVE_MAX_OBJECT_SIZE` | `0` | Largest file to materialize, in bytes. `0` = unbounded |
| `CORTEX_GDRIVE_RECONCILE_INTERVAL_MS` | `86400000` (24 h) | How long the change feed may be trusted before a full folder walk is forced. Longer than S3's 6 h because a delta feed is a provider guarantee, not a notification that can be lost |
| `CORTEX_GDRIVE_REQUEST_TIMEOUT_MS` | `60000` | Per-request timeout |
| `CORTEX_GDRIVE_MAX_RETRIES` | `5` | Retries for a throttled or 5xx request |
| `CORTEX_GDRIVE_EXPORT_NATIVE_DOCS` | `false` | Worker default for the node option. Google Docs/Sheets/Slides have no bytes; exporting them is lossy and capped at 10 MB |

### 2.4 OneDrive variables

OneDrive and SharePoint document libraries, over Microsoft Graph v1.0. Microsoft is considered configured when **either** app-only credentials (a concrete `tenantId` plus `clientId`+`clientSecret`) **or** a delegated `clientId`+`clientSecret`+`refreshToken` set is present.

⚠️ An app-only token has no `/me`, so there is no implicit drive: either `CORTEX_ONEDRIVE_DEFAULT_DRIVE_ID` or the node's `driveId` must be set. `resolveDriveId` fails fast naming the setting rather than letting Graph answer 400 three calls later.

| Env Var | Default | Description |
|---|---|---|
| `CORTEX_ONEDRIVE_TENANT_ID` | `common` | Entra tenant. Required for app-only access; `common` only works with a delegated refresh token |
| `CORTEX_ONEDRIVE_CLIENT_ID` | — | Application (client) id |
| `CORTEX_ONEDRIVE_CLIENT_SECRET` | — | Application client secret |
| `CORTEX_ONEDRIVE_REFRESH_TOKEN` | — | 🔴 **Development only.** Microsoft rotates the token on every use and expects the caller to persist the replacement, which a stateless worker cannot |
| `CORTEX_ONEDRIVE_SCOPES` | `…/.default` app-only; `offline_access …/Files.Read.All` delegated | Space-separated OAuth scopes |
| `CORTEX_ONEDRIVE_API_BASE_URL` | `https://graph.microsoft.com/v1.0` | Overridable for tests |
| `CORTEX_ONEDRIVE_AUTHORITY_URL` | `https://login.microsoftonline.com` | Identity platform base URL |
| `CORTEX_ONEDRIVE_DEFAULT_DRIVE_ID` | — | Drive used when a node names none. Effectively required for app-only access |
| `CORTEX_ONEDRIVE_CACHE_PATH` | `<meta-path>/onedrive_bin` | Directory for materialized files |
| `CORTEX_ONEDRIVE_INDEX_PATH` | `<meta-path>/onedrive-index` | Directory for persisted scan indexes |
| `CORTEX_ONEDRIVE_MAX_CACHE_BYTES` | `53687091200` (50 GiB) | Cache budget; `0` disables eviction |
| `CORTEX_ONEDRIVE_MAX_OBJECT_SIZE` | `0` | Largest file to materialize, in bytes. `0` = unbounded |
| `CORTEX_ONEDRIVE_RECONCILE_INTERVAL_MS` | `86400000` (24 h) | How long the change feed may be trusted before a full folder walk is forced |
| `CORTEX_ONEDRIVE_REQUEST_TIMEOUT_MS` | `60000` | Per-request timeout |
| `CORTEX_ONEDRIVE_MAX_RETRIES` | `5` | Retries for a throttled or 5xx request |

### 2.5 Variables read outside `CortexEnvOptions`

| Env Var | Read by | Description |
|---|---|---|
| `LOOM_TOKEN` | `LoomControlChannel.resolveToken()` | Bearer token for the Loom WebSocket handshake (`/api/v1/processors/ws?token=…`). Used only when `LoomClientOptions.token` is unset, which today means `cortex.yml` |
| `HOME` | JVM → `user.home` | Resolves the default meta path and the config path |
| `JAVA_TOOL_OPTIONS` | JVM | Heap/JVM options; set in the container image |

There are exactly two `System.getenv` call sites in `cortex/`: `CortexEnvOptions.envLookup` (the tables in §2.1–§2.4) and `LoomControlChannel` (`LOOM_TOKEN`). Any other `CORTEX_*` name found in docs or charts is not read by the code.

### 2.6 There are no subcommands

The worker is the only thing the image runs. `CortexMain.main()` ignores any argument it is
given (logging a warning, so a stale `CMD … server start` still boots) and goes straight into
`Cortex.run()`, which blocks until shutdown. The former `server start` / `process run`
subcommands, and with them the `-a/--actions` parameter, are gone. Offline batch processing
over a directory has no entry point today — `MediaProcessor` / `FilesystemProcessor` are still
built and bound, but nothing invokes them.

---

## 3. CortexOptions (root config object)

`io.metaloom.cortex.api.option.CortexOptions`.

| Field | Type | Default | Set by |
|---|---|---|---|
| `nodes` | `Map<String, CortexNodeOptions>` | empty map | `cortex.yml` only (§1.2) |
| `loom` | `LoomClientOptions` | new instance | `LOOM_HOST` / `LOOM_PORT` |
| `s3` | `S3ClientOptions` | new instance | `CORTEX_S3_*` |
| `gdrive` | `GDriveClientOptions` | new instance | `CORTEX_GDRIVE_*` |
| `onedrive` | `OneDriveClientOptions` | new instance | `CORTEX_ONEDRIVE_*` |
| `dryrun` | `boolean` | `false` | no env var — `cortex.yml` or programmatic only |
| `metaPath` | `Path` | `null` (the loader supplies `~/.cache/metaloom/cortex/meta`) | `CORTEX_META_PATH` |
| `monitoringPort` | `int` | `8093` | `CORTEX_MONITORING_PORT` |
| `nodeId` | `String` | `null` | `CORTEX_NODE_ID` |
| `nodeWhitelist` / `nodeBlacklist` | `Set<String>` | `null` | `CORTEX_NODE_WHITELIST` / `CORTEX_NODE_BLACKLIST` |
| `drainTimeoutMs` | `long` | `30000` (clamped `>= 0`) | `CORTEX_DRAIN_TIMEOUT_MS` |
| `maxConcurrentMedia` | `int` | `4` | no env var — `cortex.yml` or programmatic only |

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

### 3.2 LoomClientOptions / S3ClientOptions / S3EventOptions / Cloud options

| Class | Fields |
|---|---|
| `LoomClientOptions` | `hostname` (null; the generated default config sets `localhost`), `port` (`0`; default config `7733`), `token` (null → `LOOM_TOKEN`) |
| `S3ClientOptions` | `endpoint`, `region`, `accessKey`, `secretKey`, `pathStyleAccess` (`Boolean`, null = derive from endpoint), `cachePath`, `indexPath`, `maxCacheBytes`, `maxObjectSize`, `reconcileIntervalMs`, `events` — defaults per §2.2 |
| `S3EventOptions` | `enabled`, `mode` (`WEBHOOK`/`SQS`), `webhookPath`, `webhookSecret`, `queueUrl`, `maxBufferedKeys` — setters coerce null/non-positive back to the defaults |

| `CloudClientOptions<T>` (abstract) | `cachePath`, `indexPath`, `maxCacheBytes`, `maxObjectSize`, `reconcileIntervalMs`, `requestTimeoutMs`, `maxRetries`, `defaultDriveId` — defaults per §2.3 |
| `GDriveClientOptions` | adds `serviceAccountJson`, `serviceAccountFile`, `impersonateSubject`, `clientId`, `clientSecret`, `refreshToken`, `scopes`, `apiBaseUrl`, `tokenUrl`, `exportNativeDocs` |
| `OneDriveClientOptions` | adds `tenantId`, `clientId`, `clientSecret`, `refreshToken`, `scopes`, `apiBaseUrl`, `authorityUrl`; `tokenUrl()` is derived from the authority and tenant |

`S3ClientOptions.isConfigured()` is true when *any* of endpoint / accessKey / region is set — and `region` defaults to `us-east-1`, so it is effectively always true. The gate that actually decides whether `s3-source` is advertised is `S3Module.isConfigured()` (endpoint **or** access key).

The cloud options are stricter, and deliberately so: `isConfigured()` requires a *complete* credential set, and `partialConfigurationReason()` returns a message naming the missing setting for a half-filled one. `CloudModule` turns that into a boot failure rather than a silent "not configured", because a missing capability surfaces much later and much less clearly than a startup error.

---

## 4. Where per-node configuration actually comes from

```
Loom pipeline definition JSON  ──► RegistryNodeRegistrar.adapt(nodeDef)
   id / mode / blocking / concurrency / syncToLoom / timeoutMs   ──► CortexNodeAdapter
   remaining keys ──► PipelineConfigurable.configure(nodeDef)    ──► node instance fields

cortex.yml `nodes:` ──► CortexNodeOptionDeserializer ──► CortexOptions.nodes
                                                          AbstractNodeModule.nodeOptions(...)
                                                             └── falls through to `new XOptions()` defaults
                                                                 when the key is absent
```

Only nodes declaring `PipelineConfigurable` see the definition; everything else runs on its code defaults.

**Which nodes declare it:** `script`, `s3-sink`, `tag`, `filter`, `metadata`, `move` / `assign`
(`relocate`) and `facedetect`. A node's *declared* parameters are not evidence — `facedetect` had
advertised `faceClusterEPS` through its descriptor (so the editor rendered a field for it) for as long
as clustering had existed, while dropping every value an author typed, because it did not implement
this interface. If a parameter is meant to be authored per node, the node must be on this list.

⚠️ A `PipelineConfigurable` whose options key also appears under `cortex.yml` `nodes:` must **not**
write the per-instance value into `options()`: `AbstractNodeModule.nodeOptions(...)` returns the *same*
options instance to every injection point, so that mutation escapes to every other node of that kind —
and only on the workers whose YAML sets the key. `FacedetectNode` holds its per-instance values on the
node itself for this reason.

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
| `objectdetect` | `ObjectDetectNodeOptions` | `nodes/objectdetect` |
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
| `gdrive-source` | `GDriveSourceNodeOptions` | `nodes/cloud-source` |
| `onedrive-source` | `OneDriveSourceNodeOptions` | `nodes/cloud-source` |
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

Spot-check values; the authoritative per-field tables live in [NODES.md](../features/nodes/NODES.md) §5.

| Key | Notable fields (defaults) |
|---|---|
| `filesystem-source` | `path` (null), `pathGlobs` (`[]`, wins over `path`), `emitStates` (`NEW, MODIFIED, MOVED`), `indexPath` (null → derived from `metaPath`) |
| `hash` | `md5`, `sha256`, `sha512`, `chunkHash` — **all `true`** |
| `facedetection` | `videoChopRate` 15, `videoScaleSize` 0 (= native), `minFaceHeightFactor` 0.05, `maxFaceAngle` 30, `faceClusterMinimum` 2, `faceClusterEPS` 0.6, `inspirefacePackPath` `packs/Pikachu`, `capabilities` `{INSPIREFACE}`, `embeddingsEnabled` true, `embeddingModel` `inspireface-r18`. ⚠️ `faceClusterEPS` / `faceClusterMinimum` are **also settable per pipeline node** (§4) and the node's value wins |
| `objectdetect` | `modelPath` `models/yolo/YOLOv11n_voc.onnx`, `labelsPath` `models/yolo/voc.names`, `useGpu` true, `onnxRuntimeLibPath` null, `minConfidence` 0.5, `videoChopRate` 25, `videoScaleSize` 1024, `maxDetections` 500, `classFilter` `{}` |
| `whisper` | `modelPath` `models/ggml-large-v3-turbo.bin`, `temperature` 0.0, `temperatureInc` 0.2, `language` null, `useGpu` true, `gpuDevice` 0 |
| `thumbnail` | `cols` 6, `rows` 1, `tileSize` 384 |
| `ocr` | `tessDataPath` `/usr/share/tesseract-ocr/5/tessdata`, `language` `eng` |
| `quality` | `checkBlurriness`, `checkResolution`, `checkVideoBitrate`, `checkAudioBitrate` — all `true` |
| `dedup` | `dupFolder` `duplicates` |
| `llm` | `openaiUrl` `http://127.0.0.1:8080/v1`, `contextWindow` `2048`, `prompts` map |
| `vlm` | `endpointUrl`, `apiKey`, `prompts` (`VlmNodePrompt`) |
| `s3-source` | `bucket`, `prefix`, `suffixes`, `emitStates`, `startAfter`, `useEvents` |
| `gdrive-source` | `driveId`, `folderId`, `recursive` (true), `maxDepth` (0 = unlimited), `suffixes`, `mimeTypes`, `emitStates` (`NEW, MODIFIED, MOVED`), `useDelta` (true), `includeTrashed` (false), `exportNativeDocs` (false) |
| `onedrive-source` | the same minus `exportNativeDocs`, which is Google-only and is a validation error here |
| `s3-sink` | `bucket`, `keyTemplate`, `includeSource`, `createAssets` (true), `overwrite` `IF_DIFFERENT`, `deleteAfterUpload`, `maxArtifacts`, `maxArtifactBytes`, `failOnPartial` (true) |
| `script` | `engine`, `script`, `outputs`, `params`, `trusted` (true), `allowNetwork`/`allowFilesystem` (false), `statementLimit`, `maxOutputBytes`, `maxLogLines` |
| `tika`, `consistency`, `fingerprint`, `scene-detector` | no fields beyond §5.1 |

### 5.3 Example `cortex.yml` (see the container path caveat in §1.2)

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
| — | `artifacts()` on `NodeInputs`/`NodeContext` | `ArtifactCache.noop()` | Segment-scoped artifact cache; not configurable per node — the runner opens it. See [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) §7.4 |

### 6.1 Source node selection (`filesystem-source`)

```json
{ "id": "filesystem-source", "type": "filesystem-source",
  "path": "/media/inbox", "pathGlobs": ["/media/inbox/**.mp4"] }
```

Resolution order: definition `pathGlobs` → definition `path` → configured `filesystem-source` defaults (from `cortex.yml`, §1.2). A node with no selection from any source throws `IllegalArgumentException` in the `FilesystemSourceNode` constructor rather than silently processing nothing.

A run request may override the selection via `pathGlobs` in the `SOURCE_TASK` options. A request whose selection resolves to nothing does *not* fall back to the node's configuration — that would widen the run from the requested items to the whole configured tree.

Root (`path`) mode performs a **differential** scan against a persisted per-root index and emits only files whose `FileState` is in `emitStates`; glob mode always re-walks and emits every match.

See [PIPELINE.md](../features/pipeline/PIPELINE.md) and [NODES.md](../features/nodes/NODES.md) §4 (`MediaSourceNode`).

### 6.2 Cloud source selection (`gdrive-source`, `onedrive-source`)

```json
{ "id": "gdrive-source", "type": "gdrive-source",
  "driveId": "0AH...", "folderId": "1BxY...",
  "suffixes": "mp4,mkv", "emitStates": ["NEW", "MODIFIED", "MOVED"] }
```

Only the *selection* comes from the definition. Credentials are worker-level (§2.3, §2.4) — a definition is stored in Postgres and rendered in the editor, and `ParameterType` has no `SECRET`.

`driveId` resolves definition → configured node defaults → `CORTEX_<PROVIDER>_DEFAULT_DRIVE_ID`. Google treats an unresolved drive as My Drive; Microsoft app-only has no `/me` and fails with a message naming the setting.

Both kinds are advertised **only when that provider's credentials are configured**, which is the reason they are two kinds rather than one with a `provider` parameter — see [NODE_CLOUDSOURCE.md](../features/nodes/cloud-source/NODE_CLOUDSOURCE.md).

---

## 7. Container Configuration

`cortex/container/Containerfile` — see [BUILD.md](BUILD.md) for the full image.

| Env Var | Value | Note |
|---|---|---|
| `LOOM_HOST` | `loom` | |
| `LOOM_PORT` | `8092` | ⚠️ differs from the code default `7733` |
| `CORTEX_MONITORING_PORT` | `8093` | |
| `HOME` | `/cortex` | |
| `JAVA_TOOL_OPTIONS` | `-Xms256m -Xmx512m` | |
| `JAVA_HOME` | `/opt/java25` | |
| `LD_LIBRARY_PATH` | `/opt/opencv/lib` | |

Entrypoint: `java … -Duser.dir=/cortex -jar cortex-cli.jar` — no arguments. A bare `podman run` of the image exits with code 2 unless `CORTEX_NODE_ID` is set.

| Volume | Note |
|---|---|
| `/config` | symlinked to `/cortex/config`; ⚠️ *not* the loader's `${user.home}/.config/metaloom/` path (§1.2) |
| `/meta` | metadata storage; pair with `CORTEX_META_PATH=/meta` |

---

## 8. Key Classes Reference

| Class | Package | Purpose |
|---|---|---|
| `CortexMain` | `io.metaloom.cortex.cli` | Entry point; builds the Dagger component, enforces `CORTEX_NODE_ID`, runs the worker in the foreground |
| `CortexEnvOptions` | `io.metaloom.cortex.common.option` | Applies the `LOOM_*` / `CORTEX_*` variables onto `CortexOptions`; `envLookup` is swappable for tests |
| `CortexComponent` | `io.metaloom.cortex.cli.dagger` | Dagger component; `@BindsInstance @Named("default-options")` |
| `CortexClientModule` | `io.metaloom.cortex.cli.dagger` | Provides `CortexOptions` (loader + env) and the Loom REST client |
| `S3Module` | `io.metaloom.cortex.cli.dagger` | Builds the S3 store/materializer; derives cache (`s3_bin`) and index (`s3-index`) dirs from `metaPath` |
| `RegistryNodeRegistrar` | `io.metaloom.cortex.cli.dagger` | Adapts pipeline-definition JSON to `PipelineNode`; timeout defaults; `PipelineConfigurable.configure` |
| `CortexOptions` | `io.metaloom.cortex.api.option` | Root config object + built-in default timeouts |
| `LoomClientOptions` / `S3ClientOptions` / `S3EventOptions` | `io.metaloom.cortex.api.option` | Connection, cache and event settings |
| `CortexNodeOptions` / `AbstractNodeOptions` | `io.metaloom.cortex.api.option.node` | Node options interface / base with `enabled`, `processIncomplete`, `retryFailed`, `timeoutMs`, `validate()` |
| `ValidationResult` | `io.metaloom.cortex.api.option.node` | Result of `CortexNodeOptions.validate()` |
| `CortexOptionsLoader` | `io.metaloom.cortex.common.option` | Loads/saves `cortex.yml`, generates defaults, applies the environment, validates |
| `CortexNodeOptionDeserializer` | `io.metaloom.cortex.common.option` | Polymorphic Jackson deserializer keyed by node prefix |
| `CortexNodeOptionDeserializerInfo` | `io.metaloom.cortex.common.option` | `(optionsClass, prefix)` registration |
| `AbstractNodeModule` | `io.metaloom.cortex.common.node` | `nodeOptions(cortexOptions, key, defaults)` lookup helper |
| `LoomControlChannel` | `io.metaloom.cortex.impl.loom` | `resolveToken()` — `LoomClientOptions.token` → `LOOM_TOKEN` |
| `CortexEnv` | `io.metaloom.cortex` | `CORTEX_CONF_FILENAME = "cortex.yml"` |

---

## 9. Conventions and Gotchas

- **There is no CLI.** No flags, no subcommands, no `--help`. Anything that must be settable from a deployment needs an entry in `CortexEnvOptions` (and this spec); adding a field to `CortexOptions` alone reaches nothing.
- 🔴 **`cortex.yml` is loaded but the container mounts it in the wrong place** (§1.2). In the image a `nodes:` block only takes effect at `/cortex/.config/metaloom/cortex.yml`, not at `/config`.
- **Three separate name spaces** — do not conflate: YAML config prefix (§5, e.g. `scene-detector`), pipeline-definition `type` used for default timeouts (§3.1, e.g. `scene-detection`), and the node kind used for whitelist/blacklist matching. `scene-detector` vs `scene-detection` is a real, existing mismatch.
- **Unknown YAML handling is asymmetric**: an unknown key under `nodes:` logs `WARN "Did not find module options class for mapping {}"` and deserializes to `null`; an unknown *field inside* a known node block is silently dropped (`FAIL_ON_UNKNOWN_PROPERTIES = false`). Duplicate prefixes throw `RuntimeException("Invalid configuration mapping")` at startup.
- **`CORTEX_NODE_ID` is mandatory.** `CortexMain` exits with code 2 before the Dagger graph is used for anything, and `LoomControlChannel.start()` throws as a second guard for embedders.
- **Blank is not a value.** `CortexEnvOptions` skips an unset *or blank* variable, so `CORTEX_NODE_ID=""` in a StatefulSet reads as "not configured" rather than blanking a `cortex.yml` value.
- **A bad value is fatal, not ignored.** A non-numeric port, a boolean that is not `true`/`false` or an unknown `CORTEX_S3_EVENTS_MODE` aborts the startup — a mistyped `CORTEX_S3_EVENTS_ENABLED` must not silently read as "off" and turn every run into a full bucket listing.
- **`LOOM_TOKEN` is the only auth path** today (§2.5) — there is no token variable in `CortexEnvOptions`.
- **Monitoring port doubles as the S3 webhook listener** (`--s3-events-webhook-path`), so it is not purely a health endpoint. Keep it distinct from the Loom port.
- **`CORTEX_S3_PATH_STYLE` defaults are conditional**: unset means "on when `CORTEX_S3_ENDPOINT` is set". Setting it to `false` explicitly against MinIO will break addressing.
- **Meta path is load-bearing beyond metadata**: S3 cache (`s3_bin`), S3 index (`s3-index`) and the filesystem-source differential index are all derived from it when not set explicitly.
- **`dryrun` and `maxConcurrentMedia` have no env var** — they exist on `CortexOptions` and can only be set from `cortex.yml` or programmatically. `dryrun` is honoured only by `HashDedupNode` and `FingerprintDedupApplyNode`; other nodes ignore it.
- **`drainTimeoutMs` is clamped** to `>= 0` by the setter; negative input becomes `0`.
- **Injected options bypass both layers.** A component built with a non-null `@Named("default-options")` (tests, embedders) sees neither `cortex.yml` nor the environment.

---

## 10. Test Setup

| What | Where |
|---|---|
| YAML load/validate/serialize round-trip | `cortex/common/src/test/java/io/metaloom/cortex/common/option/CortexOptionsLoaderTest.java` — sets `user.home=target/fakehome`; asserts the config file is **not** auto-written on load and that `nodes` stays empty |
| Environment variable parsing | `cortex/common/src/test/java/io/metaloom/cortex/common/option/CortexEnvOptionsTest.java` — swaps `CortexEnvOptions.envLookup` for a map; covers identity, restrictions, blank values, cloud settings and the fail-fast on a bad value |
| Dagger component wiring | `cortex/cli/src/test/java/io/metaloom/cortex/cli/CortexComponentTest.java` |
| Definition → node adaptation | `cortex/cli/src/test/java/io/metaloom/cortex/cli/dagger/{NodeRegistrarTest,PipelineConfigurableTest}.java` |
| Dummy options for deserializer tests | `cortex/common/src/test/java/io/metaloom/cortex/common/node/dummy/DummyOptions.java` |
| Container env → running worker | `integration-test/.../container/CortexContainer.java` (sets `LOOM_HOST`, `CORTEX_NODE_ID`, `CORTEX_NODE_WHITELIST`, `CORTEX_META_PATH`) |

`CortexEnvOptions.envLookup` is the seam that makes §2 testable — the same convention `OptionUtils.envLookup` uses on the Loom side. Do not call `System.getenv` directly in new option code.

---

## 11. Where do I find …?

| Need | Path |
|---|---|
| Entry point | `cortex/cli/src/main/java/io/metaloom/cortex/cli/CortexMain.java` |
| Env var mapping | `cortex/common/src/main/java/io/metaloom/cortex/common/option/CortexEnvOptions.java` |
| Option resolution (YAML + env) | `cortex/common/src/main/java/io/metaloom/cortex/common/option/CortexOptionsLoader.java`, `cortex/core/src/main/java/io/metaloom/cortex/cli/dagger/CortexClientModule.java` (`options(...)`) |
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

- [x] Configuration sources documented against actual behaviour
- [x] Core env var table complete and verified against `CortexEnvOptions`
- [x] S3 env var table (16 variables) documented
- [x] Variables read outside `CortexEnvOptions` enumerated (`LOOM_TOKEN`, `HOME`, `JAVA_TOOL_OPTIONS`)
- [x] `CortexOptions`, `LoomClientOptions`, `S3ClientOptions`, `S3EventOptions` fields + defaults
- [x] Built-in default-timeout map documented
- [x] Complete registered YAML node key → options class table (28 entries)
- [x] Pipeline-definition configuration keys and `PipelineConfigurable` path
- [x] Container configuration and its config-path mismatch
- [x] Key classes reference, gotchas, cheat sheet, test setup
- [x] Picocli removed; `cortex.yml` reaches the running worker; env parsing is regression-tested
- [ ] **Align the container config path** (§1.2) so a mounted `/config/cortex.yml` is loaded — or teach the loader a `CORTEX_CONF_PATH`
- [ ] Reconcile the `scene-detector` / `scene-detection` naming mismatch
- [ ] Expose `dryrun` and `maxConcurrentMedia` as env vars, or drop them
- [ ] Per-node exhaustive field tables live in [NODES.md](../features/nodes/NODES.md) §5 — verify they match §5.2 here

---
_Git HEAD revision: `742dae2d`_
_Last updated: 2026-08-06 (reference sweep — no content changes)_