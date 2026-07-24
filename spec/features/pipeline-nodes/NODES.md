# Cortex Node System Specification

> This document describes the Cortex node system for AI agents. It covers the
> node lifecycle, data persistence, configuration, execution model, and all
> implemented nodes. A progress assessment with a task checklist is at the end.
>
> **Source of truth**: the code under `cortex/`. This spec is a companion
> guide, not a replacement for reading the code.

---

## 1. General Node Principle

Cortex nodes are the atomic processing units of the Cortex pipeline. Each node
performs a single, well-defined operation on a media item (e.g. hash, face
detect, OCR, thumbnail). Nodes are composed into a directed acyclic graph
(DAG) by the pipeline system, which controls execution order, parallelism,
and data flow between nodes.

### Two-Level Node Hierarchy

There are two parallel hierarchies:

| Hierarchy | Base Interface | Base Class | Purpose |
|---|---|---|---|
| **Cortex-level** (legacy/CLI) | `CortexNode<I, T>` | `AbstractCortexNode` -> `AbstractFilesystemNode` -> `AbstractMediaNode` | Direct CLI invocation, standalone processing |
| **Pipeline-level** (DAG) | `PipelineNode` | `AbstractPipelineNode` | DAG execution, reactive streams, filter branching |

The pipeline-level hierarchy wraps Cortex-level nodes via
`CortexNodeAdapter`, which adapts a `FilesystemNode` into a `PipelineNode`.
This allows all existing Cortex nodes to participate in pipeline DAGs without
modification.

### Cortex-Level Class Hierarchy

```
CortexNode<I, T>                          (interface: name(), options(), initialize(), isDryrun())
  +-- AbstractCortexNode<I, T>            (holds LoomClient, CortexOptions, node options)
       +-- AbstractFilesystemNode<I, T>   (adds progress tracking, print/error, set(current, total))
            +-- AbstractMediaNode<T>      (the main base: process() -> isProcessable() -> compute())
```

### Key Interfaces

- `CortexNode<I, T extends CortexNodeOptions>` - top-level interface. `I` is
  the input type, `T` is the options type.
- `FilesystemNode<I, T>` extends `SourceNode<I, T>` - adds `process(NodeContext<I>)`
  and `process(LoomMedia, upstreamOutputs)`.
- `SourceNode<I, T>` - marker interface for nodes that yield media items
  (source nodes in the pipeline).
- `PipelineNode` - pipeline-level interface with DAG connectivity, concurrency,
  caching, and reactive `apply()`.

### Node Lifecycle (AbstractMediaNode)

The `AbstractMediaNode.process(NodeContext<LoomMedia>)` method implements a
fixed lifecycle:

1. **enabled check**: If `options().isEnabled()` is false, return
   `ctx.skipped("Disabled").next()`.
2. **exists check**: If the media file does not exist, return
   `ctx.failure("File not found").abort()`.
3. **processable check**: Call `isProcessable(ctx)` (abstract). If false,
   return `ctx.skipped("unprocessable").next()`.
4. **asset fetch**: If not in offline mode, fetch the `AssetResponse` from
   the Loom backend by SHA-512. This allows nodes to short-circuit if the
   result already exists remotely.
5. **compute**: Call `compute(ctx, asset)` (abstract). The node does its
   work and stores outputs via `ctx.output(key, value)`.
6. **result**: The `NodeResult` carries state (SUCCESS/SKIPPED/FAILED),
   outputs map, and origin (COMPUTED/LOCAL/REMOTE).

### NodeContext

`NodeContext<I>` is the per-invocation context:

- `input()` - the typed input
- `media()` - the `LoomMedia` file handle
- `duration()` - elapsed time since context creation
- `output(key, value)` / `output(NodeOutputKey, value)` - accumulate outputs
- `outputs()` - get all accumulated outputs as a map
- `upstreamOutputs()` - outputs from upstream dependency nodes (keyed by
  node id), available when running in a pipeline
- `upstreamOutput(nodeId, key)` - convenience accessor for a specific
  upstream output
- `skipped(reason)` / `failure(cause)` / `origin(ResultOrigin)` - state setters
- `next()` / `abort()` - produce `NodeResult` (continue or abort)
- `print(result, msg)` / `info(msg)` - progress logging

### NodeResult (Cortex-level)

- `ResultState`: `SUCCESS`, `SKIPPED`, `FAILED`
- `ResultOrigin`: `COMPUTED` (locally computed), `LOCAL` (from xattr/sidecar
  cache), `REMOTE` (fetched from Loom backend)
- `getOutputs()` - map of output key -> value
- `get(NodeOutputKey)` / `getOutput(String)` - typed accessors

### NodeOutputKey

Type-safe output key: `NodeOutputKey.of("sha512", String.class)`. Each node
declares its output keys as `public static final` constants. The string key
is also used for xattr/sidecar persistence.

---

## 2. Node Data Persistence

### MetaStorage System

Node results are persisted using the `MetaStorage` system, which stores
typed metadata on media files via `LoomMetaKey<T>`.

**`MetaStorage` interface**:
- `has(media, metaKey)` / `get(media, metaKey)` / `put(media, metaKey, value)`
- `append(media, metaKey, value)` - append to a list-valued key
- `getAll(media, metaKey)` - get all values for a list-valued key
- `setSHA512(media, hash)` / `getSHA512(media)` - convenience for SHA-512

**`LoomMetaKey<T>`**:
- Fields: `key()` (string), `version()` (int), `type()` (`LoomMetaCoreType`),
  `getValueClazz()` (class)
- Created via `LoomMetaKey.metaKey(name, version, type, valueClass)`
- The `type` field determines which `LoomMetaTypeHandler` handles storage.

**`LoomMetaCoreType`** (storage backends):

| Type | Description |
|---|---|
| `XATTR` | Linux extended attributes on the media file itself |
| `FS` | Filesystem-based storage (sidecar files) |
| `HEAP` | In-memory storage |
| `AVRO` | Avro-serialized binary storage |

**`MetaStorageImpl`** delegates to the appropriate `LoomMetaTypeHandler`
based on the key's `LoomMetaCoreType`. Handlers are discovered via Dagger
multibindings (`Set<LoomMetaTypeHandler>`).

### Per-Node MetaStorage

Each node that persists data has its own `*MetaStorage` class extending
`AbstractMetaStorage`:

| Node | MetaStorage Class | Key Examples | Storage Type |
|---|---|---|---|
| Hash | `HashMetaStorage` | `sha512`, `sha256`, `md5`, `chunk_hash` | XATTR |
| Fingerprint | `FingerprintMetaStorage` | `fingerprint` | XATTR |
| Facedetect | `FacedetectionMetaStorage` | `facedetect_count`, `facedetect_flag`, `facedetect_result` | XATTR + AVRO |
| Thumbnail | `ThumbnailMetaStorage` | thumbnail binary data | FS |
| Consistency | `ConsistencyMetaStorage` | consistency data | XATTR |
| Scene | `SceneDetectionMetaStorage` | scene data | XATTR |
| Tika | `TikaMetaStorage` | tika content/flags | XATTR |
| Whisper | `WhisperMetaStorage` | transcription data | XATTR |

### Pipeline-Level Caching (NodeCacheProvider)

In addition to MetaStorage, the pipeline system provides a separate caching
layer for `NodeResult` objects:

| Implementation | Description |
|---|---|
| `NoOpNodeCache` | No-op cache (default when caching disabled) |
| `HeapNodeCache` | In-memory Caffeine cache (max 10,000 entries, 60min TTL) |
| `XAttrNodeCache` | Extended attributes on the media file (`loom_cache_{nodeId}`) |
| `SidecarFileNodeCache` | Sidecar `.cache` files in a segmented directory structure |
| `LayeredNodeCache` | Multi-layer cache with back-fill (e.g. heap -> xattr -> sidecar) |

The pipeline executor checks the cache before invoking `process()`. On a
cache hit, the node is skipped and the cached result is used. On success,
the result is cached. Only `COMPLETED` results are cached.

### Loom Backend Sync

Nodes that produce metadata (hashes, fingerprints, transcripts, etc.) can
sync results back to the Loom REST API. Two mechanisms exist:

1. **Direct sync** (legacy, in-node): Nodes like `WhisperNode` directly call
   `client().createAssetTranscript(...)` inside `compute()`.
2. **Bulk sync** (pipeline-level): Nodes with `syncToLoom() == true` have
   their results collected by `LoomBulkSyncCollector`, which batches and
   flushes to Loom via the bulk API.
3. **LoomNode**: A dedicated node that accumulates `AssetBulkUpdateEntry`
   objects and flushes them in batches of 50.

---

## 3. List of Nodes

### Processing Nodes (AbstractMediaNode subclasses)

| Node | Module | `name()` | Output Keys | Processable Media | Description |
|---|---|---|---|---|---|
| `MD5Node` | hash | `md5` | `md5` (String) | Any file | Computes MD5 hash; checks Loom first for remote hit |
| `SHA256Node` | hash | `sha256` | `sha256` (String) | Any file | Computes SHA-256 hash |
| `SHA512Node` | hash | `sha512` | `sha512` (String) | Any file | Computes SHA-512 hash |
| `ChunkHashNode` | hash | `chunk-hash` | `chunk_hash` (String) | Any file | Computes chunk hash for dedup |
| `FingerprintNode` | fingerprint | `fingerprint` | `fingerprint` (String) | Video only | Multi-sector video fingerprint; checks xattr first, then Loom |
| `ConsistencyNode` | consistency | `consistency` | `zero_chunk_count` (Long), `is_complete` (Boolean) | Video, Audio | Counts zero chunks to detect incomplete files |
| `ThumbnailNode` | thumbnail | `thumbnail` | `thumbnail_flag` (String), `thumbnail_path` (String) | Video only | Generates contact-sheet thumbnails; checks upstream consistency |
| `FacedetectNode` | facedetect | `facedetect` | `face_count` (Integer), `facedetect_flag` (String) | Video, Image | Face detection via InspireFace; video uses frame scanning |
| `FacedescriptionNode` | facedetect | `facedescription` | `face_description` (String) | Video, Image | LLM-based face description; reads upstream `facedetect` output |
| `OCRNode` | ocr | `ocr` | `ocr_text` (String) | Image only | OCR via Tesseract; configurable language and tessdata path |
| `TikaNode` | tika | `tika` | `tika_flags` (String), `tika_content` (String) | Image, Audio, Video, Document | Apache Tika metadata extraction |
| `WhisperNode` | whisper | `whisper` | `whisper_result` (String JSON) | Video, Audio | Speech-to-text via whisper.cpp; persists transcript to Loom |
| `LLMNode` | llm | `llm` | `llm_result_{promptId}` (String) | Any (uses filename) | LLM-based metadata extraction via Ollama; configurable prompts |
| `QualityNode` | quality | `quality` | `blurriness`, `image_width/height`, `video_width/height/fps/frame_count`, `quality_flag` | Video, Image | Quality metrics (resolution, blurriness via Laplacian) |
| `SceneDetectionNode` | scene-detection | `scene-detection` | `scene_detection` (String) | Video only | Optical-flow scene detection |
| `CaptioningNode` | captioning | `captioning` | `caption_result` (String) | Image (video/audio stub) | Image captioning via SmolVLM vision model |
| `HashDedupNode` | dedup | `sha512-dedup` | (side effects: moves files) | Any (requires SHA-512) | Deduplicates files by SHA-512 hash; moves dups to target folder |
| `FingerprintDedupNode` | dedup | (fingerprint dedup) | (side effects) | Video only | Deduplicates by video fingerprint |
| `LoomNode` | loom | `loom` | (side effects: bulk update) | Any | Syncs hash results to Loom backend in batches of 50 |

### Pipeline-Only Nodes (AbstractPipelineNode subclasses)

| Node | Module | `kind` | Description |
|---|---|---|---|
| `FilesystemSourceNode` | `nodes/filesystem-source` | `filesystem-source` | Source node that enumerates media files from a directory tree or a set of path globs |
| `AssetSourceNode` | `pipeline-core` | (not registered) | Source node that emits a single configured media asset |
| `LoomFetchNode` | `pipeline-core` | `loom-fetch` (descriptor only) | Fetches user metadata (tags, annotations) from Loom backend |
| `CortexNodeAdapter` | `pipeline-core` | — | Wraps a `FilesystemNode` as a `PipelineNode` for DAG execution |
| `AbstractFilterNode` subclasses | `pipeline-core` | `filter-*` | Filter nodes (see below) |

### Filter Nodes (AbstractFilterNode subclasses)

Filter nodes partition the pipeline into PASS/REJECT branches. They emit a
`FILTER_PASSED` boolean output. Available filter implementations (in
`pipeline-core`):

- `MimeTypeFilterNode` - filters by MIME type patterns
- `DateFilterNode` - filters by date range
- `SizeFilterNode` - filters by file size
- `DuplicateFilterNode` - filters duplicates
- `BlacklistFilterNode` - filters blacklisted paths
- `QualityFilterNode` - filters by quality thresholds
- `ThresholdFilterNode` - generic threshold filter
- `AssetAttributeFilterNode` - filters by Loom asset attributes

---

## 4. Special Nodes: Filter and Source

### Filter Nodes

Filter nodes extend `AbstractFilterNode` (which extends `AbstractPipelineNode`).
They are **pipeline-only** constructs - they do not exist at the Cortex level.

**Design**:
- `AbstractFilterNode.process()` calls `evaluate(media, upstreamResults)`
  which returns `true` (pass) or `false` (reject).
- The result is stored as `FILTER_PASSED` (boolean) and `filter_reason`
  (String) in the `NodeResult` output map.
- `isPartitioning()` returns `true`.
- `partition(input)` splits the `Flowable<MediaContext>` into pass and
  reject branches using `share()` + `filter()`.
- Downstream nodes declare `conditionalDependencies()` mapping the filter
  node id to `FilterBranch.PASS` or `FilterBranch.REJECT`.
- The executor checks filter branch conditions before executing a node:
  if the filter output doesn't match the required branch, the node is
  skipped with `NodeResult.skipped(node.id, "Filter branch mismatch")`.

**FilterBranch enum**:
- `PASS` - execute only if filter passed
- `REJECT` - execute only if filter rejected
- `ANY` - execute regardless (default for non-filter dependencies)

### Source Nodes

Source nodes yield media items for the pipeline. There are two distinct notions
of "source", and confusing them is a common mistake:

| Concept | Interface | Meaning |
|---|---|---|
| **Marks itself as the entry point** | `PipelineNode.isSource()` | The DAG's root. Says nothing about where media comes from |
| **Produces the media stream** | `MediaSourceNode` (pipeline-api) | Owns its own selection and can enumerate it via `Flowable<LoomMedia> stream()` |
| **Cortex-level marker** | `SourceNode<I, T>` | Marker interface extending `CortexNode`, used by the legacy CLI hierarchy |

#### `MediaSourceNode` — self-describing selection

Historically the media stream was always supplied by the *caller* of
`PipelineExecutor.execute(pipeline, Flowable<LoomMedia>, runContext)`, so every
caller reimplemented its own discovery. `MediaSourceNode` moves that
responsibility onto the node:

```java
public interface MediaSourceNode extends PipelineNode {
    Flowable<LoomMedia> stream();
    default boolean isSource() { return true; }
}
```

This enables the source-driven entry point, where the pipeline decides what to
process:

```java
pipelineExecutor.execute(pipeline, runContext);   // no media argument
```

The default implementation on `PipelineExecutor` resolves `pipeline.sourceNode()`,
requires it to be a `MediaSourceNode`, and subscribes to its `stream()`. A
pipeline whose source is only a marker fails fast with an `IllegalStateException`
naming the offending node.

**Contract**: `stream()` must return a **cold** `Flowable` — no filesystem or
network work before subscription, and every subscription re-enumerates. This is
what lets a node instance registered once in a `PipelineManager` pick up files
added since the previous run.

**`FilesystemSourceNode`** (`nodes/filesystem-source`, kind `filesystem-source`):
- Enumerates either a root directory (walked recursively) or a set of path globs.
- Globs take precedence when both are configured.
- Falls back to `FilesystemSourceNodeOptions` defaults when the pipeline
  definition supplies no selection; a node with no selection from either source
  is rejected at construction.
- Glob/walk logic lives in `FilesystemMediaScanner` — the **single**
  implementation of path-based media discovery in Cortex, driven through
  `FilesystemSourceNode` when the worker runs a `SOURCE_TASK`.
- `process()` records `path` and `source=filesystem` for the item currently
  flowing through the DAG; the enumeration itself happens in `stream()`.

**`AssetSourceNode`** (pipeline-level):
- Emits exactly one configured `LoomMedia` per pipeline run.
- Uses `AtomicBoolean` to ensure single emission.
- Returns `NodeResult.skipped()` on subsequent invocations.
- Sets `setSource(true)` in the constructor.
- Implements `MediaSourceNode`; its `stream()` is the single configured asset.

**`FilesystemNode`** (Cortex-level):
- Extends `SourceNode` and adds `process(NodeContext<I>)`.
- `AbstractFilesystemNode` adds progress tracking (`set(current, total)`,
  `print(ctx, result, msg)`, `error(media, msg)`).
- `AbstractMediaNode` extends `AbstractFilesystemNode` and implements the
  full lifecycle (enabled -> exists -> processable -> compute).

The pipeline requires exactly one source node. `DefaultPipeline` validates
this and discovers all reachable nodes via BFS from the source.

---

## 5. Configuration Handling

### CortexOptions (top-level)

`CortexOptions` is the root configuration object:

| Field | Type | Description |
|---|---|---|
| `nodes` | `Map<String, CortexNodeOptions>` | Per-node options, keyed by node name |
| `loom` | `LoomClientOptions` | Loom backend connection settings |
| `dryrun` | `boolean` | Global dry-run mode (nodes log but don't mutate) |
| `metaPath` | `Path` | Base path for metadata storage files |
| `monitoringPort` | `int` | Monitoring port (default 8093) |

### Node Options (CortexNodeOptions / AbstractNodeOptions)

Every node has its own options class extending `AbstractNodeOptions<T>`:

**Common fields** (from `AbstractNodeOptions`):
- `enabled` (boolean, default true) - whether the node is active
- `processIncomplete` (boolean) - whether to process incomplete media
- `retryFailed` (boolean) - whether to retry previously failed items

**Per-node options** (examples):

| Node | Options Class | Key Fields |
|---|---|---|
| Hash | `HashNodeOptions` | `md5`, `sha256`, `sha512`, `chunkHash` (booleans) |
| Facedetect | `FacedetectNodeOptions` | `videoChopRate`, `videoScaleSize`, `faceClusterMinimum`, `faceClusterEPS`, `minFaceHeightFactor`, `inspirefacePackPath`, `capabilities` |
| Whisper | `WhisperOptions` | `modelPath`, `temperature`, `temperatureInc`, `language`, `useGpu`, `gpuDevice` |
| Quality | `QualityNodeOptions` | `checkBlurriness`, `checkResolution`, `checkVideoBitrate`, `checkAudioBitrate` |
| Thumbnail | `ThumbnailNodeOptions` | `tileSize`, `cols`, `rows` |
| OCR | `OCRNodeOptions` | `tessDataPath`, `language` |
| LLM | `LLMNodeOptions` | `ollamaUrl`, `prompts` (Map of prompt configs) |
| Captioning | `CaptioningNodeOptions` | `smolVLMHost`, `smolVLMPort` |
| Dedup | `DedupNodeOptions` | `dupFolder` (Path) |
| Filesystem Source | `FilesystemSourceNodeOptions` | `path` (String), `pathGlobs` (List&lt;String&gt;) — defaults used when the pipeline definition supplies no selection |
| Scene | `SceneDetectionOptions` | (no custom fields) |
| Consistency | `ConsistencyNodeOptions` | (no custom fields) |
| Loom | `LoomNodeOptions` | (no custom fields) |

### Dagger Wiring

Each node module extends `AbstractNodeModule` and provides:
1. `@Binds @IntoSet FilesystemNode<?, ?>` - registers the node in the
   Dagger multibinding set.
2. `@Provides CortexNodeOptionDeserializerInfo` - registers the options
   class and its config key for deserialization.
3. `@Provides` method that extracts node-specific options from
   `CortexOptions` using `nodeOptions(cortexOptions, KEY, default)`.

The `CortexOptions.getNodes()` map is keyed by the node's `KEY` constant
(e.g. `"hash"`, `"facedetection"`, `"whisper"`).

### Pipeline-Level Node Configuration

Pipeline nodes carry configuration via:
- `id()` - unique identifier within the pipeline
- `mode()` - `NodeMode.SEQUENTIAL` or `NodeMode.PARALLEL`
- `blocking()` - whether downstream nodes wait for completion
- `concurrency()` - max concurrent executions (semaphore size)
- `syncToLoom()` - whether results are batched for Loom sync
- `cacheProvider()` - optional `NodeCacheProvider` for result caching
- `options()` - arbitrary key-value map (for pipeline-loaded nodes)

---

## 6. Pipeline Interaction

> The pipeline system is covered in detail in the pipeline specification.
> This section provides a brief overview for node developers.

### CortexNodeAdapter

The `CortexNodeAdapter` bridges Cortex-level nodes into the pipeline DAG:

- Wraps a `FilesystemNode<?, ?>` as a `PipelineNode`.
- Converts pipeline `NodeResult` maps to/from Cortex-level
  `NodeContext.upstreamOutputs()`.
- The adapter's `process(LoomMedia, Map<String, NodeResult>)` delegates to
  the wrapped node's `process(LoomMedia, upstreamOutputs)`.
- `isSource()` returns true if the wrapped node is a `SourceNode`.

### DAG Execution

The `ReactivePipelineExecutor` (in `pipeline-core`) executes pipelines:

1. **Topological sort**: `DefaultPipeline` sorts nodes via Kahn's algorithm
   to respect dependencies.
2. **Per-media DAG**: For each media item, builds `Single<NodeResult>` per
   node. Multi-parent dependencies use `Single.zip`.
3. **Reactive streams**: Uses RxJava 3 `Flowable` with backpressure.
   `maxConcurrentMedia` controls parallelism via `flatMap(fn, maxConcurrent)`.
4. **Per-node concurrency**: Each node has a `Semaphore(concurrency())`.
   The executor acquires/releases it around `process()`.
5. **Dependency resolution**: Before executing a node, checks:
   - Failed blocking dependencies -> skip
   - Filter branch conditions -> skip if mismatch
6. **Caching**: Checks `NodeCacheProvider` before executing. On success,
   caches the result.
7. **Loom sync**: If `syncToLoom() == true`, collects results for bulk sync.
8. **Event bus**: Publishes `NodeCompletionEvent` and `PipelineTrackingEvent`.

### Pipeline Construction

```java
sourceNode.connectTo(filterNode);
filterNode.connectTo(hashNode, FilterBranch.PASS);
filterNode.connectTo(tikaNode, FilterBranch.PASS);
hashNode.connectTo(fingerprintNode);

Pipeline pipeline = DefaultPipeline.builder("video-analysis")
    .source(sourceNode)
    .build();
```

### Pipeline Serialization

`PipelineSerializer` / `PipelineDeserializer` convert pipelines to/from JSON
for storage in the Loom backend. The JSON includes node ids, names, modes,
dependencies, concurrency, and options.

`LoomPipelineLoader` loads pipeline definitions from the Loom REST API and
registers them with the `PipelineManager`.

---

## 7. Execution Model

### CLI Execution (Legacy)

The CLI (`CortexCLIMain`) builds a `DaggerCortexComponent` with
`CortexOptions`, then invokes the selected actions. Nodes are injected via
Dagger and called directly (not through the pipeline DAG). The CLI iterates
over media files and calls `node.process(NodeContext)` for each.

### Pipeline Execution (Reactive)

The `ReactivePipelineExecutor` uses RxJava 3:

- **Backpressure**: `Flowable.flatMap(fn, maxConcurrentMedia)` - the source
  is paused when downstream can't keep up.
- **Scheduling**: `Schedulers.io()` for I/O-bound node work.
- **Per-node parallelism**: `Semaphore(concurrency())` per node.
- **Multi-parent gathering**: `Single.zip` of dependency singles.
- **Caching**: `Single.cache()` ensures each node's Single executes only
  once per media item even with multiple downstream subscribers.

### Virtual Threads

The codebase does **not** currently use virtual threads (`Thread.ofVirtual`,
`Executors.newVirtualThreadPerTaskExecutor`, or `StructuredTaskScope`).
The reactive executor uses RxJava 3 `Schedulers.io()` which is backed by a
standard thread pool. Virtual threads could be beneficial for I/O-bound
nodes (whisper, OCR, LLM, facedetect) but are not yet integrated.

### Node Concurrency Model

| Aspect | Mechanism |
|---|---|
| Media-level parallelism | `maxConcurrentMedia` in `ReactivePipelineExecutor` |
| Per-node parallelism | `Semaphore(concurrency())` on each `PipelineNode` |
| Node mode | `NodeMode.PARALLEL` vs `NodeMode.SEQUENTIAL` |
| Blocking | `isBlocking()` - if true, downstream nodes wait for completion |
| Filter partitioning | `AbstractFilterNode.partition()` splits into pass/reject branches |

### Dry-Run Mode

When `pipeline.isDryRun()` is true, the executor logs the node and media
but returns `NodeResult.skipped(node.id, "dry-run")` without calling
`process()`. This allows testing pipeline graphs without side effects.

---

## 8. Dagger DI Wiring

### Node Modules

Each node module extends `AbstractNodeModule` and uses Dagger `@Module`:

```java
@Module
public abstract class HashNodeModule extends AbstractNodeModule {
    @Binds @IntoSet
    abstract FilesystemNode<?, ?> bindSHA512Node(SHA512Node node);

    @IntoSet @Provides
    public static CortexNodeOptionDeserializerInfo optionInfo() {
        return new CortexNodeOptionDeserializerInfo(HashNodeOptions.class, HashNodeOptions.KEY);
    }

    @Provides
    public static HashNodeOptions options(CortexOptions options) {
        return nodeOptions(options, HashNodeOptions.KEY, new HashNodeOptions());
    }
}
```

The `@Binds @IntoSet` pattern collects all nodes into a `Set<FilesystemNode>`
multibinding. The `@Provides` methods extract per-node options from the
shared `CortexOptions`.

### NodeDescriptorRegistry

`NodeDescriptorProvider` (SPI via `ServiceLoader`) provides
`NodeDescriptor` objects for the UI. Each descriptor includes:
- `kind` - unique machine-readable id
- `name` - display name
- `category` - palette grouping
- `inputs` / `outputs` - connectors
- `parameters` - configurable form fields
- `defaultConcurrency`, `defaultMode`, `defaultBlocking`
- `events` - UI visualization events

---

## 9. Noteworthy Aspects

### Offline Mode

When `LoomClient` is null (no Loom backend configured), nodes run in
"offline mode". `AbstractMediaNode.fetchAsset()` returns null, and nodes
compute everything locally without checking for remote results.

### Upstream Output Access

Nodes can read outputs from upstream dependency nodes via:
- `ctx.upstreamOutput(nodeId, key)` - convenience accessor
- `ctx.upstreamOutputs()` - full map of upstream node id -> output map

Example: `FacedescriptionNode` reads `ctx.upstreamOutput("facedetect", "face_count")`
to skip face description when no faces were detected. `ThumbnailNode` reads
`ctx.upstreamOutput("consistency", "is_complete")` to skip incomplete videos.

### ResultOrigin

Nodes declare whether a result was `COMPUTED` (locally computed), `REMOTE`
(fetched from Loom backend), or `LOCAL` (from local cache). This allows
downstream nodes and the pipeline to distinguish between freshly computed
and cached results.

### LoomMedia

`LoomMedia` extends `ProcessableMedia` and is a pure file handle:
- `isVideo()`, `isImage()`, `isAudio()`, `isDocument()` - media type checks
- `file()`, `path()`, `absolutePath()` - file access
- `exists()`, `size()`, `open()` - file operations
- `getSHA512()`, `setSHA512()`, `hasSHA512()` - content-based identity
- `listXAttr()` - extended attributes listing

The media decorator pattern (e.g. `HashMedia`, `FacedetectMedia`) is
implemented via `MetaStorage` typed accessors rather than interface
decoration. Each `*MetaStorage` class provides typed getters/setters for
its domain-specific metadata.

### Pipeline Node IDs

Node IDs must match `^[a-z0-9]([a-z0-9\\-]{0,62}[a-z0-9])?$` (lowercase
alphanumeric with hyphens, 1-64 chars). This ensures stable identifiers for
UI mapping, WebSocket events, and serialization.

### Progress Tracking

`AbstractFilesystemNode` provides progress tracking via `set(current, total)`
and `print(ctx, result, msg)`. The print format is:
```
[current/total] [shortHash [nodeName]] result [duration] message
```

---

## 10. Progress Assessment

The node system is functional and well-structured, but several areas need
attention. The following checklist tracks aspects that need improvement,
fixes, or further development.

### Architecture and Design

- [ ] **Unify the two NodeResult classes**: There are two separate `NodeResult`
      classes - `io.metaloom.cortex.api.node.NodeResult` (Cortex-level, used
      by `AbstractMediaNode`) and `io.metaloom.cortex.pipeline.api.NodeResult`
      (pipeline-level, used by `PipelineNode`). The `CortexNodeAdapter`
      converts between them, but this duality is confusing and error-prone.
      Consider unifying into a single result type.

- [ ] **Unify the two NodeContext/NodeResult state enums**: The Cortex-level
      uses `ResultState` (SUCCESS, SKIPPED, FAILED) while the pipeline-level
      uses `NodeState` (PENDING, RUNNING, COMPLETED, FAILED, SKIPPED). These
      should be aligned or merged.

- [ ] **Formalize the CortexNodeAdapter mapping**: The adapter manually
      converts between Cortex and pipeline result types. This mapping should
      be formalized and tested for all state transitions.

### Node Implementations

- [x] **`filesystem-source` implemented** (`nodes/filesystem-source`): the
      descriptor had been advertised by `cortex-source-api` with no runtime
      behind it, so the kind resolved to a success-reporting stub. It is now a
      real `MediaSourceNode` and is registered in `PipelineNodeFactoryModule`.
      Path discovery is consolidated in `FilesystemMediaScanner`; the copy that
      lived in `PipelineWorkOrderHandler` was removed.

- [ ] **`loom-fetch` has no runtime**: `LoomFetchNode` exists in `pipeline-core`
      but no producer is registered for the `loom-fetch` kind, so the descriptor
      advertised by `cortex-source-api` still stubs out as a success. It is also
      not a `MediaSourceNode`, so it cannot drive a run on its own.

- [ ] **`AssetSourceNode` is not registered as a kind**: it implements
      `MediaSourceNode` and is used programmatically and in tests, but pipeline
      JSON cannot select it.

- [ ] **CaptioningNode video support**: The `CaptioningNode.compute()` returns
      `ctx.skipped("not implemented")` for video and audio media. Video
      captioning needs to be implemented.

- [ ] **FacedescriptionNode video support**: The `FacedescriptionNode` only
      processes images; video face description (per-frame extraction) is
      stubbed but not implemented.

- [ ] **HashDedupNode has dead code**: The `HashDedupNode` has commented-out
      code for storing updated paths and a `System.in.read()` blocking call
      in the error path that should be removed.

- [ ] **LLMNode creates a new LLM provider per invocation**: Each call to
      `compute()` creates a new `OllamaLLMProvider` and `LargeLanguageModelImpl`.
      These should be reused or injected for efficiency.

- [ ] **WhisperNode persists transcript directly**: The `WhisperNode` calls
      `client().createAssetTranscript()` inside `compute()`, bypassing the
      pipeline's `syncToLoom()` mechanism. This should be refactored to use
      the bulk sync collector.

- [ ] **LoomNode does not extend AbstractMediaNode**: `LoomNode` extends
      `AbstractFilesystemNode` directly, skipping the `isProcessable()` /
      `compute()` lifecycle. It also does not use `NodeOutputKey` for outputs.
      This is inconsistent with the rest of the node system.

- [ ] **ThumbnailNode duplicate variable**: `resolveThumbnailPath(media)` is
      called twice in `compute()` - once inside the try block and once after
      the try block. The second call is dead code.

### Configuration

- [ ] **ConsistencyNodeOptions and LoomNodeOptions have no custom fields**:
      These options classes extend `AbstractNodeOptions` but add nothing.
      They should either have meaningful fields or be simplified.

- [ ] **Node options are not validated**: There is no validation framework
      for node options. Invalid configurations (e.g. negative concurrency,
      empty model paths) are not caught until runtime.

- [ ] **Config key consistency**: Some nodes use a `KEY` constant (e.g.
      `"hash"`, `"facedetection"`, `"whisper"`) while others do not. The
      key should be consistently defined in every options class.

### Persistence and Caching

- [ ] **XAttrNodeCache serialization is fragile**: The cache uses a
      line-based `key=value` format that breaks on values containing
      newlines or `=` signs. Should use JSON or a more robust format.

- [ ] **SidecarFileNodeCache.clear() is not implemented**: The `clear()`
      method logs a warning and does nothing. This prevents full cache
      invalidation.

- [ ] **MetaStorage and NodeCacheProvider are separate systems**: The
      `MetaStorage` system (xattr/avro/fs) and the `NodeCacheProvider`
      system (heap/xattr/sidecar) both persist node results but are
      independent. This creates confusion about where data is stored
      and potential redundancy. Consider unifying or clearly documenting
      the separation.

- [ ] **No cache eviction strategy for SidecarFileNodeCache**: Sidecar
      cache files accumulate indefinitely with no cleanup mechanism.

### Pipeline Integration

- [ ] **CortexNodeAdapter does not propagate `syncToLoom()`**: The adapter
      constructor does not accept a `syncToLoom` flag; it defaults to false.
      Nodes that should sync to Loom need this set explicitly after
      adaptation.

- [ ] **CortexNodeAdapter does not set `cacheProvider()`**: The adapter
      does not propagate a cache provider from the wrapped Cortex node.
      Pipeline-level caching is not applied to adapted Cortex nodes unless
      set externally.

- [ ] **No reactive `apply()` override on CortexNodeAdapter**: The adapter
      relies on the default `AbstractPipelineNode.apply()` which wraps
      `process()` in a flatMap. Nodes with streaming characteristics (e.g.
      whisper processing long audio) cannot express streaming operators.

### Execution Model

- [ ] **No virtual thread support**: The executor uses RxJava `Schedulers.io()`
      with platform threads. For I/O-bound nodes (whisper, OCR, LLM, facedetect),
      virtual threads could improve throughput. Consider adding a
      `Thread.ofVirtual()`-based scheduler option.

- [ ] **No per-node timeout**: The executor does not enforce timeouts on
      individual node executions. A hung node (e.g. LLM call) blocks the
      semaphore indefinitely.

- [ ] **No retry mechanism**: The `retryFailed` option in
      `AbstractNodeOptions` is declared but never checked by the executor.
      Failed nodes are not retried.

- [ ] **No backpressure propagation to nodes**: While the pipeline uses
      `Flowable.flatMap` with `maxConcurrentMedia`, individual nodes have
      no way to signal backpressure to the executor.

### Testing

- [ ] **No integration tests for the reactive executor**: The
      `ReactivePipelineExecutor` has unit tests but no integration tests
      that exercise real node graphs with actual media files.

- [ ] **Filter nodes are not tested with real partitioning**: The
      `AbstractFilterNode.partition()` method is tested in isolation but
      not with the full executor and real filter branches.

- [ ] **LoomBulkSyncCollector has no tests**: The bulk sync collector
      interface and its default implementation lack test coverage.

### Documentation

- [ ] **Node descriptor registry is not populated**: The
      `NodeDescriptorRegistry` exists but no nodes register descriptors.
      The UI cannot render a node palette without descriptors.

- [ ] **Missing node documentation**: Individual nodes lack Javadoc on
      their options, outputs, and persistence keys. The
      `loom/doc/src/main/docs/cortex/nodes/index.adoc` file is the
      canonical doc but is not kept in sync with code changes.

- [ ] **No sequence diagram for pipeline execution**: The pipeline
      execution flow (source -> filter -> process -> sync) should be
      documented with a sequence diagram.

### Missing Features

- [ ] **No node-level dry-run**: The pipeline has a global `dryRun` flag,
      but individual nodes cannot be dry-run independently.

- [ ] **No node health/status endpoint**: There is no way to query the
      health or last-run status of a node outside of the event bus.

- [ ] **No node-level metrics**: The executor tracks `nodeProcessedCounts`
      and `nodeFailedCounts` but these are not exposed via a metrics
      endpoint or JMX.

- [ ] **No conditional node chaining**: Nodes cannot be conditionally
      enabled/disabled at runtime based on upstream results (only filter
      branches provide conditional execution).

- [ ] **No node versioning**: Nodes have no version field. When a node's
      algorithm changes (e.g. a new hash function), there is no way to
      invalidate cached results from the previous version.

---

## 11. Node Restriction & Cortex-Instance Persistence

A worker (Cortex/processor) does not have to be able to run every kind of node.
A deployment can dedicate machines to particular work — GPU boxes to embeddings,
the one host with the media mount to filesystem sources — by restricting which
node **kinds** a worker will accept. This section covers how that restriction is
expressed, persisted, and reconciled.

### Whitelist and blacklist (the `nodeKinds` rename)

The single announced field `nodeKinds` has been split into two, because "the
kinds a worker will run" and "the kinds a worker refuses" are different
questions and a single list answered only the first:

| Field | Meaning |
|---|---|
| `nodeWhitelist` | Kinds this worker will run. Null/empty means **anything**, so a worker that predates whitelisting keeps receiving everything rather than dropping out of the pool. |
| `nodeBlacklist` | Kinds this worker refuses. Takes precedence over the whitelist: a kind in the blacklist is rejected even when the whitelist would admit it. Null/empty means **refuse nothing**. |

The rename touches the whole worker-restriction path and nothing else (in
particular it is unrelated to `SegmentTask.getNodeKinds()` /
`PipelineSegment.getNodeKinds()`, which mean "the kinds contained in a pipeline
segment"):

- `ProcessorRegistration` (rest-model) — announced `nodeWhitelist` / `nodeBlacklist`.
- `ConnectedProcessor` (`ProcessorRegistry`) — `accepts(kind)` returns false for a
  blacklisted kind, otherwise true when the whitelist is empty or contains the kind.
- `CortexOptions` / `CortexCLI` (worker config) — `--node-whitelist` /
  `--node-blacklist` flags (env `CORTEX_NODE_WHITELIST` / `CORTEX_NODE_BLACKLIST`);
  `LoomControlChannel.sendRegister()` announces both. The whitelist still defaults
  to the node factory's `registeredTypes()` so a worker cannot advertise work it
  cannot perform.

### The `cortex_instance` record

Previously a registered worker lived only in `ProcessorRegistry`'s in-memory map
and died with the Loom process; its restriction was whatever it announced, with
no way to remember or override it. Registration is now backed by a durable record
(migration `V2.33__add_cortex_instance.sql`):

- **`cortex_instance`** — one row per worker, keyed by the stable `node_id`
  (`UNIQUE`). Tracks identity (`name`, `host`, `priority`), presence (`last_seen`,
  `state`, `first_registered`), and the standard `meta`/audit columns. The
  `creator_uuid` / `editor_uuid` audit columns are **nullable** because a row is
  created by the machine that registers, with no user; the admin override path (UI)
  fills `editor_uuid` when it edits.
- **`cortex_instance_node_kind`** `(instance_uuid, node_kind, list)` — the
  whitelist/blacklist as a queryable/indexable child table (`list ∈ {WHITELIST,
  BLACKLIST}`), so a single kind can be looked up across all workers rather than
  buried in an opaque JSONB blob.
- Permissions `MANAGE_CORTEX_INSTANCE` / `READ_CORTEX_INSTANCE` (two-permission
  model: manage = write, read = view).

Persistence is jOOQ-only (`CortexInstanceDao` / `CortexInstanceDaoImpl`, exposed on
`DaoCollection`); there is deliberately **no in-memory DAO**, matching the pipeline
DAOs. The DAO provides `loadByNodeId`, `findAll`, and `upsertByNodeId` (insert or
update keyed by `node_id`, so re-registration never creates a duplicate row), and
round-trips both kind lists through the child table.

### Startup-config (DEFAULT) vs DB-override (OVERRIDE) precedence

On `REGISTER`, `ProcessorRegistry.register(...)` reconciles the announced
restriction against the persisted record:

1. **First registration** (no row for the `node_id`): the announced
   `nodeWhitelist` / `nodeBlacklist` are the **DEFAULT** and are seeded into a new
   `cortex_instance` row, and applied to the in-memory `ConnectedProcessor`.
2. **Reconnect / restart** (row exists): identity and presence are refreshed, but
   the persisted whitelist/blacklist are the **OVERRIDE** — they are applied to the
   `ConnectedProcessor` instead of blindly trusting what the worker re-announced,
   and they survive across reconnects. An administrator edits them via the record
   (Task 2/UI), and the change sticks even though the worker keeps announcing its
   own set.

Persistence failures never take a worker offline: if the reconcile step throws, the
registry falls back to the announced restriction and keeps serving.
