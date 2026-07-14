# MetaLoom — Cortex Pipeline Context for AI Agents

Companion to [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md). This document
covers the **pipeline execution engine** that Cortex uses to process
media assets, and the **Loom-side** persistence + event bridge that
lets pipelines be defined and observed through the REST/UI layer.

Additional living notes: `/memories/repo/cortex-pipeline-detailed.md`
(kept in agent memory; may lag behind this document).

---

## 1. TL;DR

- A **pipeline** is a directed acyclic graph (DAG) of **PipelineNode**s
  that Cortex runs against a stream of `LoomMedia` items.
- Everything is built on **RxJava 3** (`Flowable`, `Single`) — there is
  no `CompletableFuture` executor anymore. Backpressure and per-node
  concurrency are first-class.
- Each pipeline has **exactly one source node**; other nodes are
  discovered by walking the connection graph.
- Nodes are wired with `parent.connectTo(child)` or
  `parent.connectTo(child, FilterBranch.PASS|REJECT)` for filter
  branches. Dependencies (upstream ids) are computed from the inverse.
- Cortex pulls pipeline **definitions from Loom** (`GET /api/v1/pipelines`)
  and emits **tracking events back to Loom** over a processor WebSocket;
  Loom re-broadcasts them to UI clients over
  `/api/v1/pipelines/events/ws`.
- Two independent node hierarchies exist and are bridged by
  `CortexNodeAdapter`:
  - The **new pipeline-api** nodes (`PipelineNode` /
    `AbstractPipelineNode`).
  - The **legacy cortex node** API (`CortexNode` / `AbstractMediaNode`
    with a `NodeContext`) which still hosts the concrete
    hash/facedetect/whisper/etc. implementations.

---

## 2. Module Map

Located under [cortex/](../cortex/):

| Module | Role |
| --- | --- |
| [pipeline-api](../cortex/pipeline-api/) | Public interfaces: `Pipeline`, `PipelineNode`, `PipelineExecutor`, `PipelineManager`, `NodeResult`, `NodeState`, `NodeMode`, `MediaContext`, `PartitionedFlowable`, event/cache/sync SPIs |
| [pipeline-core](../cortex/pipeline-core/) | `DefaultPipeline`, `DefaultPipelineManager`, `ReactivePipelineExecutor`, `AbstractPipelineNode`, `AbstractFilterNode`, `AssetSourceNode`, `LoomFetchNode`, `CortexNodeAdapter`, JSON serde |
| [pipeline-common](../cortex/pipeline-common/) | `DefaultPipelineEventBus`, `DefaultLoomBulkSyncCollector`, cache impls (`NoOpNodeCache`, `HeapNodeCache`, `XAttrNodeCache`, `SidecarFileNodeCache`, `LayeredNodeCache`) |
| [common/…/node](../cortex/common/src/main/java/io/metaloom/cortex/common/node/) | Legacy base classes for cortex nodes: `AbstractCortexNode`, `AbstractFilesystemNode`, `AbstractMediaNode`, `AbstractNodeModule` |
| [nodes/](../cortex/nodes/) | Concrete processing nodes (hash, facedetect, fingerprint, ocr, thumbnail, llm, whisper, tika, dedup, quality, captioning, consistency, scene-detection, loom) + `common-api`, `filter-api`, `source-api` descriptor providers |
| [core/](../cortex/core/) | Runtime wiring, CLI, pipeline loader, Loom control channel |

**Cortex-side wiring** lives in
[CortexBindModule](../cortex/core/src/main/java/io/metaloom/cortex/cli/dagger/CortexBindModule.java) —
Dagger `@Provides` for `PipelineManager`, `PipelineEventBus`, and
`PipelineExecutor` (returns `ReactivePipelineExecutor(4, eventBus)`).
Node collections come from
[NodeCollectionModule](../cortex/cli/src/main/java/io/metaloom/cortex/cli/dagger/NodeCollectionModule.java).

**Loom-side entry points** live in
[loom/services/rest](../loom/services/rest/) (REST + WS endpoints),
[loom/db](../loom/db/) (persistence), and
[loom-shared/rest-model](../loom-shared/rest-model/) (DTOs for
`PipelineModel` and pipeline event messages).

---

## 3. Core API (pipeline-api)

Package root: `io.metaloom.cortex.pipeline.api`.

### 3.1 `Pipeline`

Interface with these members:

- `name()`, `description()`, `priority()`
- `isEnabled()`, `isDryRun()`
- `sourceNode()` — the single entry-point node
- `nodes()` — all nodes in topological order (immutable)
- `node(String id)` — lookup by id

Built through `DefaultPipeline.builder(name).description(...).priority(...).enabled(...).dryRun(...).source(node).build()`.

**Node discovery**: BFS from `sourceNode` following `PipelineNode.children()`.

**Node id validation**: must match
`^[a-z0-9]([a-z0-9\-]{0,62}[a-z0-9])?$` and be unique per pipeline
(`DefaultPipeline.NODE_ID_PATTERN`).

**Cycle detection**: `topologicalSort()` throws
`IllegalStateException` if not all nodes are reachable
(`"Pipeline '...' has a dependency cycle"`).

### 3.2 `PipelineNode`

The main SPI. Key members:

- `id()`, `name()`, `isSource()`, `mode()` (`SEQUENTIAL` | `PARALLEL`)
- `isBlocking()` — if `true`, downstream nodes wait; otherwise they
  are notified asynchronously via the event bus
- `dependencies(): Set<String>` — upstream node ids (computed from
  inverse of `connectTo`)
- `conditionalDependencies(): Map<String, FilterBranch>` — filter
  branch conditions per parent
- `concurrency(): int` — per-node semaphore permits (e.g. `whisper=1`,
  `hasher=4`)
- `syncToLoom(): boolean` — whether the result should be batched for
  bulk upload to Loom
- `connectTo(downstream)` / `connectTo(downstream, FilterBranch)` —
  fluent DAG wiring; both mutate `downstream.parentIds`
- `children(): List<PipelineNode>` — unmodifiable downstream list
- `process(LoomMedia, Map<String, NodeResult> upstreamResults): NodeResult` —
  the actual work
- `apply(Flowable<MediaContext>): Flowable<MediaContext>` — default
  reactive operator (wraps `process` in a
  `flatMap(...,concurrency())` on `Schedulers.io()`)
- `isPartitioning()` / `partition(Flowable<MediaContext>): PartitionedFlowable<MediaContext>` —
  filter-only, splits stream into PASS/REJECT branches
- `options(): Map<String, Object>` — node-specific config
- `cacheProvider(): NodeCacheProvider` — nullable result cache
- `initialize()` / `shutdown()` — lifecycle

Constant: `PipelineNode.FILTER_PASSED = "filter_passed"` — the
standard output key emitted by filter nodes.

### 3.3 `NodeResult` and `NodeState`

`NodeState` = `PENDING | RUNNING | COMPLETED | FAILED | SKIPPED`.

`NodeResult` fields: `nodeId`, `state`, `durationMs`, `message`,
`output: Map<String, Object>` (immutable copy).

Factories:

- `NodeResult.success(nodeId, durationMs)`
- `NodeResult.success(nodeId, durationMs, Map<String, Object> output)`
- `NodeResult.failed(nodeId, durationMs, message)`
- `NodeResult.skipped(nodeId, reason)` (durationMs = 0)

Convenience accessor: `<T> T getOutput(String key)` with unchecked
cast. Common output keys used across the codebase: `"sha512"`,
`"md5"`, `"sha256"`, `"description"`, `"transcript"`, `"tags"`,
`"embedding"`, `"image"`, `"answer"`, `"filter_passed"`,
`"filter_reason"`.

### 3.4 `PipelineResult`

- `pipelineName`, `media`, `nodeResults: Map<String, NodeResult>`,
  `totalDurationMs`, `dryRun`
- `isSuccess()` iff every node's state is `COMPLETED` or `SKIPPED`

### 3.5 `PipelineExecutor`

Reactive API on top of `Flowable<LoomMedia>`:

- `PipelineResult execute(Pipeline, LoomMedia)` — blocking convenience
- `Flowable<PipelineResult> execute(Pipeline, Flowable<LoomMedia>)` —
  reactive stream (backpressure-aware)
- `List<PipelineResult> executeBatch(Pipeline, List<LoomMedia>)` —
  default impl converts and calls `flushSync()` at the end
- `int flushSync()` — drain the `LoomBulkSyncCollector`
- `shutdown()` — clears event bus, releases resources

### 3.6 `PipelineManager`

- `register(Pipeline)`, `unregister(name)`
- `pipelines(): List<Pipeline>` — sorted by priority DESC
- `pipeline(name): Optional<Pipeline>`
- `resolve(LoomMedia): Optional<Pipeline>` — current
  `DefaultPipelineManager` returns the highest-priority enabled
  pipeline. Media-based filtering is now done via **filter nodes
  inside the pipeline**, not at the manager level.

### 3.7 Filter Branching

`FilterBranch` enum in
[filter/FilterBranch.java](../cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/filter/FilterBranch.java):

- `ANY` — always execute (default for regular dependencies)
- `PASS` — execute only if upstream filter emitted
  `filter_passed = true`
- `REJECT` — execute only if `filter_passed = false`

The executor consults `node.conditionalDependencies()` before running a
node; branch mismatch produces `NodeResult.skipped(...)`.

### 3.8 `MediaContext` and `PartitionedFlowable`

`MediaContext` (immutable) is the carrier for RxJava-style
subscription pipelines: `getMedia()`, `getUpstreamResults()`,
`withResult(id, result)`, `merge(other)`. Used by
`PipelineNode.apply(Flowable<MediaContext>)` and
`AbstractFilterNode.partition(...)`.

`PartitionedFlowable<T>` = `{ pass(): Flowable<T>, reject(): Flowable<T> }`,
returned only by partitioning (filter) nodes.

### 3.9 Events

Two channels on `PipelineEventBus`:

1. **Node completion** — full-fidelity `NodeCompletionEvent`
   `(nodeId, LoomMedia, NodeResult, timestamp)`. Used internally by
   the executor and available via `subscribe(nodeId, …)` /
   `subscribeAll(…)`.
2. **Tracking** — lightweight `PipelineTrackingEvent` designed for
   WebSocket forwarding. Scalar-only:
   `(type, pipelineName, nodeId, mediaPath, timestamp, durationMs, message)`.
   Subscribe via `subscribeTracking(…)`.

`PipelineTrackingEvent.Type` enum — kept **name-aligned** with
[`PipelineEventType`](../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/event/PipelineEventType.java):

`PIPELINE_STARTED, PIPELINE_COMPLETED, NODE_STARTED, NODE_COMPLETED, NODE_FAILED, NODE_SKIPPED, NODE_BUFFERED`
(the Loom-side enum also adds `NODE_STATS`).

### 3.10 Caching

`NodeCacheProvider` SPI — `get`, `put`, `invalidate`, `clear`, keyed
by `(nodeId, LoomMedia)`. Implementations in `pipeline-common`:

- `NoOpNodeCache.INSTANCE` — singleton default when node returns
  `null` from `cacheProvider()`
- `HeapNodeCache` — Caffeine, configurable maxSize + TTL; cache key
  is `nodeId + ":" + sha512 || absolutePath`
- `XAttrNodeCache` — filesystem extended attributes
- `SidecarFileNodeCache` — `.json` sidecar files next to media
- `LayeredNodeCache` — chained providers (e.g. heap in front of xattr)

### 3.11 Bulk Sync

`LoomBulkSyncCollector` SPI — `collect(media, nodeId, result)`,
`flush(): int`, `pending(): int`. Default:
[`DefaultLoomBulkSyncCollector`](../cortex/pipeline-common/src/main/java/io/metaloom/cortex/pipeline/common/sync/DefaultLoomBulkSyncCollector.java) —
buffers `SyncEntry` tuples and auto-flushes on batch size (default
100). Delegates the actual write to a `BulkSyncWriter` strategy. On
failure, re-adds the batch to the buffer for retry on the next flush.

Only nodes with `syncToLoom() == true` are collected. Called by
`ReactivePipelineExecutor` only when a node reports
`NodeState.COMPLETED`.

---

## 4. `AbstractPipelineNode` — Base Implementation

Location:
[cortex/pipeline-core/…/node/AbstractPipelineNode.java](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/node/AbstractPipelineNode.java).

Constructor:
`(String id, String name, NodeMode mode, boolean blocking, int concurrency, [boolean syncToLoom])`.

Internal state:

- `children: List<PipelineNode>` (added via `connectTo`)
- `parentIds: Set<String>` (added by upstream's `connectTo`)
- `conditionalDependencies: Map<String, FilterBranch>` (added by
  `connectTo(downstream, branch)`)
- Setters: `setSource(boolean)`, `setSyncToLoom(boolean)`,
  `setCacheProvider(NodeCacheProvider)`, `addDependency(String)`,
  `setConditionalDependency(String, FilterBranch)` — last two used by
  the JSON deserializer / loader when reconstructing a graph.

Subclasses must implement:

```java
NodeResult process(LoomMedia media, Map<String, NodeResult> upstreamResults);
```

`AbstractFilterNode` extends this with a template method
`evaluate(media, upstreamResults): boolean` and always emits an
output map `{ filter_passed: bool, filter_reason: String }`. It
overrides `isPartitioning() = true` and `partition(...)` to split a
`Flowable<MediaContext>` via `share()` into pass/reject branches.

Concrete filter impls in
[cortex/pipeline-core/…/node/filter/](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/node/filter/):
`MimeTypeFilterNode`, `DateFilterNode`, `SizeFilterNode`,
`DuplicateFilterNode`, `BlacklistFilterNode`, `QualityFilterNode`,
`ThresholdFilterNode`, `AssetAttributeFilterNode`, `SamplingFilterNode`.

Other built-in pipeline-core nodes:

- `AssetSourceNode` — emits a single pre-configured `LoomMedia`
  (source node, sets `setSource(true)`, uses `AtomicBoolean` to only
  emit once per run). Outputs: `{ path, source: "asset" }`.
- `LoomFetchNode` (id `loom-fetch`) — non-blocking node with a
  pluggable `LoomMetadataFetcher` functional interface. Skips
  silently in offline mode.
- `CortexNodeAdapter` — bridge from legacy `FilesystemNode`/`SourceNode`
  to new `PipelineNode` (see §7).

---

## 5. `ReactivePipelineExecutor`

Location:
[cortex/pipeline-core/…/executor/ReactivePipelineExecutor.java](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/executor/ReactivePipelineExecutor.java).

Constructors:

```java
new ReactivePipelineExecutor(int maxConcurrentMedia);
new ReactivePipelineExecutor(int maxConcurrentMedia, PipelineEventBus eventBus);
new ReactivePipelineExecutor(int maxConcurrentMedia, PipelineEventBus eventBus,
                             LoomBulkSyncCollector syncCollector);
```

### 5.1 Execution model

1. `execute(Pipeline, Flowable<LoomMedia>)` — for each item, calls
   `executeSingle(pipeline, media).toFlowable().subscribeOn(Schedulers.io())`,
   composed with `flatMap(..., maxConcurrentMedia)` for
   backpressure.
2. `executeSingle` builds a per-media DAG of
   `Single<NodeResult>` values in topological order. Each node's
   `Single` is `.cache()`d so multiple downstream subscribers reuse a
   single execution.
3. Multi-parent dependencies are gathered via `Single.zip(depSingles, …)`.
4. Before executing, each node:
   a. Skips if any **blocking** dependency has `state == FAILED`.
   b. Skips if any conditional dependency's `filter_passed` disagrees
      with the required `FilterBranch`.
5. Actual execution (in `Single.fromCallable`):
   - Emit `NODE_BUFFERED` if the per-node semaphore has 0 permits.
   - Acquire semaphore → emit `NODE_STARTED`.
   - Check cache → return cached result if present.
   - If pipeline `isDryRun()` → return `NodeResult.skipped(id, "dry-run")`.
   - Call `node.process(media, upstream)`.
   - On `COMPLETED`: `cache.put(...)`; if `syncToLoom() && syncCollector != null`,
     `syncCollector.collect(...)`.
   - Release semaphore.
6. `.doOnSuccess`: publishes `NodeCompletionEvent`, updates
   processed/failed counters, and emits the matching tracking event
   (`NODE_COMPLETED` / `NODE_FAILED` / `NODE_SKIPPED`).
7. `.onErrorReturn`: converts exceptions into
   `NodeResult.failed(...)` + `NODE_FAILED` tracking event.
8. Once all node `Single`s complete, `Single.zip` combines them into
   `PipelineResult(pipelineName, media, resultMap, elapsed, dryRun)`.

### 5.2 Concurrency

- `maxConcurrentMedia` — bounds in-flight media items across the
  whole pipeline.
- Per-node `Semaphore(node.concurrency())` — bounds concurrent
  executions of that node across all in-flight media items.
  Semaphores are lazily created in `execute(...)` and shared across
  runs of the same executor instance.

### 5.3 Special cases

- Disabled pipeline (`!pipeline.isEnabled()`) → returns empty
  `PipelineResult` (`nodeResults = Map.of()`) for each media item
  without touching any node.
- `PIPELINE_STARTED` is emitted before subscribing to the source;
  `PIPELINE_COMPLETED` is emitted via `.doOnComplete(...)` on the
  outer `Flowable`.
- `shutdown()` calls `eventBus.clear()` — no other resources to
  release since RxJava schedulers are shared.

---

## 6. Pipeline Serialisation (JSON)

Location:
[cortex/pipeline-core/…/serde/](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/serde/).

- `PipelineSerializer` (Dagger `@Singleton`, `@Inject ObjectMapper`) —
  converts a `Pipeline` to `ObjectNode` / JSON string.
- `PipelineDeserializer` — rebuilds a `DefaultPipeline` from JSON
  using `DeserializedNode` (a private `AbstractPipelineNode` variant
  that carries `options` and connects via `addDependency` +
  `setConditionalDependency`).

Serialised shape:

```json
{
  "name": "video-analysis",
  "description": "…",
  "priority": 100,
  "enabled": true,
  "dryRun": false,
  "sourceNode": "filesystem",
  "nodes": [
    {
      "id": "sha512",
      "name": "SHA-512 Hash",
      "type": "source|filter|processor",
      "mode": "PARALLEL|SEQUENTIAL",
      "blocking": true,
      "concurrency": 4,
      "syncToLoom": true,
      "dependencies": ["filesystem"],
      "conditionalDependencies": { "video-filter": "PASS" },
      "options": { … },
      "children": ["tika", "fingerprint"]
    }
  ],
  "tree": {
    "root": "filesystem",
    "branches": { "filesystem": ["sha512"], … }
  }
}
```

The `type` label is inferred: `isSource()` → `source`; class name
containing `FilterNode` / `AbstractFilterNode` → `filter`; else
`processor`.

Round-trip guarantees (see
[PipelineSerdeRoundTripTest](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/core/serde/PipelineSerdeRoundTripTest.java)):
serialize → deserialize → serialize yields identical JSON. Node ids,
names, mode, blocking, concurrency, syncToLoom, dependencies,
conditional dependencies, and options are all preserved.

---

## 7. Two Node Trees + `CortexNodeAdapter`

There are two parallel node hierarchies. **Do not mix them without
using the adapter.**

### 7.1 New pipeline-api tree

`PipelineNode` (interface) → `AbstractPipelineNode` (abstract) →
`AbstractFilterNode` / concrete pipeline-core nodes.

`process(LoomMedia, Map<String, NodeResult>): NodeResult` returns the
new `NodeResult` (in `io.metaloom.cortex.pipeline.api`).

### 7.2 Legacy cortex node tree

Package `io.metaloom.cortex.api.node.*` +
[cortex/common/…/node](../cortex/common/src/main/java/io/metaloom/cortex/common/node/):

- `CortexNode<I, O>` (interface, generic input/output)
- `AbstractCortexNode<I, T extends CortexNodeOptions>` — holds
  `LoomClient` (may be null → offline), `CortexOptions`, node
  `options`
- `AbstractFilesystemNode<T extends FilesystemMedia, O extends CortexNodeOptions>` —
  scanning + metadata storage helpers
- `AbstractMediaNode<T extends CortexNodeOptions>` — the canonical
  base for concrete work. Lifecycle inside `process(NodeContext<LoomMedia>)`:
  1. `options().isEnabled()` → else `ctx.skipped("Disabled").next()`
  2. `media.exists()` → else `ctx.failure(...)`.abort()
  3. `isProcessable(ctx)` → else `ctx.skipped("unprocessable").next()`
  4. Fetch `AssetResponse` from Loom (skipped in offline mode)
  5. Call `compute(ctx, asset)` — write outputs via `ctx.output(key, value)`
     and return `ctx.origin(COMPUTED).next()` (see
     [SHA512Node](../cortex/nodes/hash/core/src/main/java/io/metaloom/cortex/node/hash/SHA512Node.java)
     as the canonical example)

Outputs use typed keys like
`NodeOutputKey.of("sha512", String.class)`. Downstream nodes read
them via `ctx.upstreamOutput(nodeName, key)`.

All concrete nodes in [cortex/nodes/](../cortex/nodes/) are on this
legacy tree. Each node module ships:

- `XxxNode.java` — extends `AbstractMediaNode<XxxNodeOptions>`
- `XxxNodeOptions.java` — POJO options
- `XxxNodeModule.java` — Dagger `@Module` with `@Provides` for options
- `XxxMetaStorage.java` (some) — helper for xattr / sidecar storage
- Optional companion node in
  [nodes/…/api](../cortex/nodes/hash/api/): a
  `NodeDescriptorProvider` (`HashDescriptorProvider`,
  `LoomNodeDescriptorProvider`, …) that publishes UI metadata to the
  `NodeDescriptorRegistry`.

### 7.3 The adapter

[CortexNodeAdapter](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/node/CortexNodeAdapter.java)
wraps a legacy `FilesystemNode<?, ?>` as an `AbstractPipelineNode`.
It:

- Uses `wrappedNode.name()` for both id and display name **by default**
- Has an alternate constructor
  `new CortexNodeAdapter(String id, FilesystemNode, NodeMode, boolean, int)`
  that lets you override the pipeline node id while keeping the wrapped
  node's own `name()` as the display name. Use this when a downstream
  legacy node expects a specific upstream id that does not match the
  wrapped node's `name()` — e.g. `LoomNode` reads
  `ctx.upstreamOutput("md5sum", "md5")` but `MD5Node.name()` is `"md5"`,
  so the MD5 adapter must be built as
  `new CortexNodeAdapter("md5sum", md5Node, PARALLEL, true, 1)`.
- Converts `Map<String, NodeResult>` → `Map<String, Map<String, Object>>`
  so the legacy node's `NodeContext.upstreamOutputs()` sees each
  parent's raw output map
- Maps legacy `NodeResult.State` (`SUCCESS`, `SKIPPED`, `FAILED`) to
  pipeline `NodeState` (`COMPLETED`, `SKIPPED`, `FAILED`)
- Forwards the wrapped result's `outputs` map onto the new
  `NodeResult`
- `isSource()` returns `true` iff the wrapped node implements
  `SourceNode`

This is the standard way to embed real hash/facedetect/whisper/etc.
nodes in a pipeline. **Do not** re-implement legacy nodes on top of
`AbstractPipelineNode`; wrap them via the adapter and keep option
plumbing in the Dagger module.

---

## 8. Node Descriptors (UI Metadata)

Package `io.metaloom.loom.nodes.spec` in
[nodes/common-api](../cortex/nodes/common-api/), plus the
role-specific descriptor providers in `nodes/*/api/`:

- `NodeDescriptor` — `kind`, `name`, `description`, `icon`,
  `category`, `inputs`, `outputs`, `parameters`, `defaultConcurrency`,
  `defaultMode`, `defaultBlocking`, `events`
- `NodeCategory` (enum), `NodeMode` (enum, mirror of pipeline-api),
  `NodeInput`, `NodeOutput`, `NodeParameter`, `ParameterType`,
  `ContentType`, `ContentTypes`
- `NodeDescriptorProvider` — SPI: `List<NodeDescriptor> getDescriptors()`
- `NodeDescriptorRegistry` — LinkedHashMap-backed registry populated
  at startup and served to the UI (used by the palette / edit forms /
  validation of REST payloads)

The `filter-api` and `source-api` modules define marker sub-interfaces
(`FilterDescriptorProvider`, `SourceDescriptorProvider`) so the UI
can categorise providers.

**Do NOT confuse** `io.metaloom.loom.nodes.spec.NodeMode` (UI
descriptor enum) with `io.metaloom.cortex.pipeline.api.NodeMode`
(runtime enum). They share names but are distinct types.

---

## 9. Loom-side Persistence, REST, and Events

### 9.1 Database

Migration:
[loom/db/flyway/…/V2.19__add_pipeline.sql](../loom/db/flyway/src/main/resources/db/migration/V2.19__add_pipeline.sql)

```sql
CREATE TABLE pipeline (
    uuid           UUID PRIMARY KEY,
    name           VARCHAR NOT NULL,
    description    VARCHAR,
    definition     JSONB NOT NULL DEFAULT '{}',
    enabled        BOOLEAN NOT NULL DEFAULT true,
    priority       INTEGER NOT NULL DEFAULT 0,
    dry_run        BOOLEAN NOT NULL DEFAULT false,
    meta           JSONB,
    created/edited TIMESTAMP + creator/editor_uuid FK to user
);
```

Also adds `CREATE_PIPELINE / READ_PIPELINE / UPDATE_PIPELINE /
DELETE_PIPELINE` values to `loom_permission`.

DAO:
[loom/db/api/…/pipeline/Pipeline.java](../loom/db/api/src/main/java/io/metaloom/loom/db/model/pipeline/Pipeline.java)
and
[PipelineDao.java](../loom/db/api/src/main/java/io/metaloom/loom/db/model/pipeline/PipelineDao.java).
jOOQ impl:
[loom/db/jooq/…/dao/pipeline/](../loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/pipeline/).

`Pipeline.getDefinition(): JsonObject` is where the serialised graph
(§6) lives.

### 9.2 REST

[PipelineEndpoint](../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEndpoint.java)
exposes CRUD at `/api/v1/pipelines`:

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/v1/pipelines` | Create — requires `CREATE_PIPELINE` |
| GET | `/api/v1/pipelines` | List (paged) — requires `READ_PIPELINE` |
| GET | `/api/v1/pipelines/:uuid` | Load — requires `READ_PIPELINE` |
| POST | `/api/v1/pipelines/:uuid` | Update — requires `UPDATE_PIPELINE` |
| DELETE | `/api/v1/pipelines/:uuid` | Delete — requires `DELETE_PIPELINE` |

DTOs in
[loom-shared/rest-model/…/pipeline/](../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/):
`PipelineModel`, `PipelineCreateRequest`, `PipelineUpdateRequest`,
`PipelineResponse`, `PipelineListResponse`, `PipelineExamples`.

Validation:
[PipelineModelValidator](../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/validation/PipelineModelValidator.java) —
require `name` and `definition` on create; standard creator/editor
checks on responses.

Service:
[PipelineEndpointService](../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEndpointService.java)
extends `AbstractCRUDEndpointService<PipelineDao, Pipeline>`.

### 9.3 Event WebSocket (Loom → UI)

Endpoint:
[PipelineEventEndpoint](../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEventEndpoint.java)
serves `/api/v1/pipelines/events/ws`. The route is registered with
`order(-1000)` so the WS upgrade wins over the secured
`/api/v1/pipelines*` route.

Broadcaster:
[PipelineEventBroadcaster](../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEventBroadcaster.java)
(Dagger `@Singleton`) holds a `ConcurrentHashMap.newKeySet()` of
`ServerWebSocket`s and calls
`ws.writeTextMessage(Json.encode(event))` on `broadcast(...)`.

Message:
[PipelineEventMessage](../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/event/PipelineEventMessage.java)
+
[PipelineEventType](../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/event/PipelineEventType.java)
(name-aligned with cortex `PipelineTrackingEvent.Type` plus a
`NODE_STATS` type).

### 9.4 Cortex → Loom Ingestion Path (Processor WebSocket)

Cortex nodes are treated as **processors** by Loom. They connect to
`/api/v1/processors/ws` (see
[LoomControlChannel](../cortex/core/src/main/java/io/metaloom/cortex/impl/loom/LoomControlChannel.java)):

1. `LoomControlChannel.start()` opens the WS, sends a
   `ProcessorMessage(REGISTER, ProcessorRegistration)`, then a
   periodic `HEARTBEAT` (10s) and `STATUS_UPDATE` (20s).
2. It subscribes to the local `PipelineEventBus` via
   `subscribeTracking(this::forwardPipelineTrackingEvent)`.
3. Every tracking event is converted into a `PipelineEventMessage`
   (types are `valueOf`'d by name) and sent as
   `ProcessorMessage(PIPELINE_EVENT, …)`.
4. Loom's
   [ProcessorEndpoint](../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ProcessorEndpoint.java)
   `handlePipelineEvent(...)` deserialises the body and calls
   `pipelineEventBroadcaster.broadcast(event)`.
5. UI clients on `/api/v1/pipelines/events/ws` receive the JSON.

Work orders flow the other direction: Loom sends
`ProcessorMessage(WORK_ORDER, WorkOrder)`, Cortex's
[PipelineWorkOrderHandler](../cortex/core/src/main/java/io/metaloom/cortex/impl/loom/PipelineWorkOrderHandler.java)
maps the command (`reload-pipelines`, `flush-sync`,
`list-pipelines`) to `PipelineExecutor.flushSync()` /
`LoomPipelineLoader.loadAndRegister()` / `PipelineManager.pipelines()`.

### 9.5 Pipeline Loading (Cortex startup)

[LoomPipelineLoader.loadAndRegister()](../cortex/core/src/main/java/io/metaloom/cortex/pipeline/loader/LoomPipelineLoader.java)
`GET`s all pipelines from Loom via `LoomClient.listPipelines()`,
walks each `PipelineResponse.definition.nodes[]` JSON, and builds a
`Pipeline` of `StubPipelineNode`s (log-and-succeed placeholders)
registered on `PipelineManager`.

A pluggable `NodeFactory` (`setNodeFactory(...)`) can replace stubs
with real (`CortexNodeAdapter`-wrapped) nodes — this is where a
custom CLI plugs its concrete node implementations in.

Definition JSON parsed by the loader:

```json
{
  "filters": { "mimeTypes": [...], "pathGlobs": [...] },
  "nodes": [
    {
      "id": "sha512", "name": "…", "mode": "PARALLEL",
      "blocking": true, "concurrency": 4, "syncToLoom": true,
      "dependencies": ["filesystem"], "options": { … },
      "source": false, "type": "processor"
    }
  ]
}
```

---

## 10. Configuration Touchpoints

Cortex-side:

- Executor: `providePipelineExecutor(...)` in
  [CortexBindModule](../cortex/core/src/main/java/io/metaloom/cortex/cli/dagger/CortexBindModule.java)
  hard-codes `new ReactivePipelineExecutor(4, eventBus)`. Change
  `maxConcurrentMedia` here.
- Node concurrency, blocking, mode, and syncToLoom are set on the
  node instance itself (constructor args / setters on
  `AbstractPipelineNode`).
- Per-node options live on the legacy `*NodeOptions` classes and are
  provided by Dagger via each `*NodeModule`. They are read inside
  `compute(...)` (typically to gate `isProcessable`).
- Pipeline-level `dryRun` / `enabled` / `priority` come from the JSON
  definition loaded from Loom (or the `DefaultPipeline.Builder`).
- WS endpoint / port for control channel: parsed by
  `LoomControlChannel.resolveEndpoint()` from
  `LoomClientOptions` on `CortexOptions`. If nothing is configured,
  the control channel logs a warning and stays disabled — pipelines
  still run locally.
- Cache providers are attached per node via
  `AbstractPipelineNode.setCacheProvider(...)`; no global cache
  binding today.

Loom-side:

- Pipeline permission gates (`CREATE_PIPELINE` etc.) — assign via
  the role/permission subsystem.
- Nothing throttles broadcast on the WS endpoint; keep tracking event
  volume in mind if adding new event types.

---

## 11. Testing Patterns

### 11.1 Fast pipeline unit tests

Location:
[cortex/pipeline-core/src/test/java/…/PipelineExecutorTest.java](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/core/PipelineExecutorTest.java).

Full worked examples of:

- Multi-node DAG execution with dependency ordering assertions
  (`testFullPipelineExecution`, `testComplexDAGWithMultipleLLMNodes`)
- Per-node concurrency limiting via semaphores
  (`testPerNodeConcurrencyLimiting`)
- Cache hit / miss (`testCaching` with `HeapNodeCache`)
- Dry-run mode (`testDryRunMode` — all nodes SKIPPED, none execute)
- Disabled pipeline (`testDisabledPipeline` — empty node results)
- Event bus subscription / filtering (`testEventBusNotifications`)
- `syncToLoom` flag + `DefaultLoomBulkSyncCollector`
  (`testBulkSyncCollectorIntegration`) — verifies only sync-eligible
  nodes are collected and count = mediaCount × syncNodeCount
- Cycle detection (`testDependencyCycleDetection` throws
  `IllegalStateException`)
- `PipelineManager` priority resolution (`testPipelineManager`)

Uses inline test doubles:

- `TestNode` — sleeps `delayMs`, appends id to `executionLog`
- `OutputTestNode` — same plus emits a configurable output map
- `StubLoomMedia` — no-op `LoomMedia` (in
  [test/StubLoomMedia.java](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/StubLoomMedia.java)).
  Factories: `StubLoomMedia.ofFile(File)`, `ofBytes(File tempDir, String name, byte[])`,
  `ofBytes(File tempDir, String name, String)` (UTF-8) — use these to
  avoid boilerplate `Files.write(...)` in `@BeforeEach` blocks.

### 11.2 Node pipeline tests — use the base class

For any pipeline-node-level test, extend
[`AbstractPipelineNodeTest`](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/AbstractPipelineNodeTest.java).
It manages the executor, event bus, and event collection, and provides
helpers that eliminate the repetitive adapter/executor boilerplate:

- `execute(media, PipelineNode...)` — builds a linear
  `AssetSourceNode → n1 → n2 → ...` pipeline and runs it.
- `executeWithSync(media, LoomBulkSyncCollector, PipelineNode...)` —
  spins up a fresh local executor with the given sync collector
  installed and calls `flush()` after execution. Use this to observe
  what would be persisted for `syncToLoom` nodes.
- `adapt(FilesystemNode<?,?>)` and
  `adapt(node, NodeMode, boolean, int)` — wrap a legacy node as a
  `CortexNodeAdapter` (default settings: PARALLEL, blocking,
  concurrency 1).
- `assertCompletionEvent(nodeId)` and
  `assertTrackingEvent(nodeId, Type)` — event assertions with helpful
  failure messages.

For asserting that a downstream node saw a specific upstream output,
use
[`CapturingNode`](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/CapturingNode.java)
instead of hand-rolling an anonymous `AbstractPipelineNode`:

```java
class MyNodePipelineTest extends AbstractPipelineNodeTest {
    @TempDir
    File tempDir;

    @Test
    void testOutputChaining() {
        LoomMedia media = StubLoomMedia.ofBytes(tempDir, "data.bin", "payload");
        CortexNodeAdapter node = adapt(new SHA512Node(null, opts, new HashNodeOptions()));
        CapturingNode capture = new CapturingNode("consumer", "sha512", "sha512");

        PipelineResult result = execute(media, node, capture);

        assertThat(result).isSuccess().hasCompletedNode("sha512");
        assertThat(capture.capturedValues()).containsExactly(expectedSha512);
        assertCompletionEvent("sha512");
        assertTrackingEvent("sha512", PipelineTrackingEvent.Type.NODE_COMPLETED);
    }
}
```

Custom assertj classes for pipeline results/events live in
[cortex/pipeline-core/src/test/java/…/test/assertj/](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/assertj/):
`PipelineResultAssert`, `PipelineNodeResultAssert`, `PipelineAssertions`.
Sister asserts for the legacy cortex node tree live under
[cortex/core-media/src/test/…/assertj/](../cortex/core-media/src/test/java/io/metaloom/cortex/media/test/assertj/)
(`NodeResultAssert`, `AbstractProcessableMediaAssert`, `FaceAssert`).

**Rule**: use these asserts instead of raw `assertEquals` on maps and
states — they give useful failure messages.

Canonical examples of this pattern (all extend
`AbstractPipelineNodeTest`): `MD5NodePipelineTest`,
`SHA512NodePipelineTest`, `ChunkHashNodePipelineTest`,
`FingerprintNodePipelineTest`, `ThumbnailNodePipelineTest`,
`LLMNodePipelineTest`, `FacedetectNodePipelineTest`,
`WhisperNodePipelineTest`.

### 11.3 Serde round-trip tests

[PipelineSerdeRoundTripTest](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/core/serde/PipelineSerdeRoundTripTest.java)
is the reference for shape assumptions. When changing the JSON
schema, add a test here.

### 11.4 Loom-side WS integration test

[PipelineEventEndpointTest](../loom/core/src/test/java/io/metaloom/loom/core/endpoint/test/PipelineEventEndpointTest.java)
uses `LoomCoreTestExtension` to boot Loom in-process and verifies:

- Client can connect to `/api/v1/pipelines/events/ws`
- A processor-side `PIPELINE_EVENT` frame is fan-out to all
  subscribers with payload intact

### 11.5 DAO test

[PipelineDaoTest](../loom/db/jooq/src/test/java/io/metaloom/loom/db/jooq/dao/PipelineDaoTest.java)
covers the persistence contract (leases a DB from the
`testdatabase-provider` — see PROJECT_CONTEXT §7).

### 11.6 End-to-end pipeline persistence integration test

[PipelinePersistenceIntegrationTest](../integration-test/src/test/java/io/metaloom/loom/test/integration/PipelinePersistenceIntegrationTest.java)
covers the full pipeline → `LoomNode` → REST → DB persistence path in
the [integration-test](../integration-test/) module. It boots Loom
in-process via `AbstractIntegrationTest`, pre-creates an asset with
only its SHA-512, then runs a real
`AssetSourceNode → sha512 → md5sum → loom` pipeline using production
`CortexNodeAdapter` + `LoomNode`, calls `loomNode.flush()`, reloads
the asset, and asserts the MD5 hash was persisted.

Key wiring detail: the MD5 adapter must be constructed with the id
`"md5sum"` (see §7.3) so that `LoomNode`'s
`ctx.upstreamOutput("md5sum", "md5")` lookup resolves.

**Prerequisite**: the shared `testdatabase-provider` container must
expose a pool named `loom-dev`. Provision it via `./setup-pool.sh`
from the repo root before running any integration-test in this
module. Without it, tests fail at `ProviderExtension.beforeEach` with
`Got error from server {Pool not found {loom-dev}}` — this is
identical for the sibling `BasicIntegrationTest` and is an env, not
code, issue.

---

## 12. Examples

Reference examples under [examples/](../examples/):

- [examples/cortex-custom-node](../examples/cortex-custom-node/) —
  minimal legacy node
  ([HelloWorldNode.java](../examples/cortex-custom-node/src/main/java/io/metaloom/cortex/node/hello/HelloWorldNode.java))
  showing how to extend `AbstractMediaNode<T>`, publish outputs via
  `ctx.output(...)`, and consume upstream outputs via
  `ctx.upstreamOutput(nodeName, key)`. Includes a
  `HelloWorldNodeModule` (Dagger) and `HelloWorldNodeTest`.
- [examples/cortex-custom-cli](../examples/cortex-custom-cli/) —
  minimal main class + Dagger component
  (`CortexCustomCLIMain`, `CortexComponent`, `NodeCollectionModule`)
  that shows how to add a custom node to the CLI. Mirrors
  [cortex/cli](../cortex/cli/) but only wires the custom node.

The built-in
[NodeCollectionModule](../cortex/cli/src/main/java/io/metaloom/cortex/cli/dagger/NodeCollectionModule.java)
lists every stock node module: hash, thumbnail, fingerprint, ocr,
facedetect, dedup, tika, llm, scene, loom, quality, captioning,
consistency.

---

## 13. Where do I find …? (cheat sheet)

| Need | Path |
| --- | --- |
| Pipeline interface | [pipeline-api/…/Pipeline.java](../cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/Pipeline.java) |
| Node interface | [pipeline-api/…/node/PipelineNode.java](../cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/node/PipelineNode.java) |
| Executor | [pipeline-core/…/executor/ReactivePipelineExecutor.java](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/executor/ReactivePipelineExecutor.java) |
| Node base class | [pipeline-core/…/node/AbstractPipelineNode.java](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/node/AbstractPipelineNode.java) |
| Filter base class | [pipeline-core/…/node/filter/AbstractFilterNode.java](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/node/filter/AbstractFilterNode.java) |
| Concrete filters | [pipeline-core/…/node/filter/](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/node/filter/) |
| Legacy → new bridge | [pipeline-core/…/node/CortexNodeAdapter.java](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/node/CortexNodeAdapter.java) |
| Legacy node base | [common/…/node/AbstractMediaNode.java](../cortex/common/src/main/java/io/metaloom/cortex/common/node/AbstractMediaNode.java) |
| Node test base | [pipeline-core/src/test/…/test/AbstractPipelineNodeTest.java](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/AbstractPipelineNodeTest.java) |
| Test capture node | [pipeline-core/src/test/…/test/CapturingNode.java](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/CapturingNode.java) |
| Test media stub | [pipeline-core/src/test/…/test/StubLoomMedia.java](../cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/StubLoomMedia.java) |
| E2E persistence test | [integration-test/…/PipelinePersistenceIntegrationTest.java](../integration-test/src/test/java/io/metaloom/loom/test/integration/PipelinePersistenceIntegrationTest.java) |
| Event bus | [pipeline-common/…/event/DefaultPipelineEventBus.java](../cortex/pipeline-common/src/main/java/io/metaloom/cortex/pipeline/common/event/DefaultPipelineEventBus.java) |
| Cache impls | [pipeline-common/…/cache/](../cortex/pipeline-common/src/main/java/io/metaloom/cortex/pipeline/common/cache/) |
| Bulk sync | [pipeline-common/…/sync/DefaultLoomBulkSyncCollector.java](../cortex/pipeline-common/src/main/java/io/metaloom/cortex/pipeline/common/sync/DefaultLoomBulkSyncCollector.java) |
| JSON serde | [pipeline-core/…/serde/](../cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/serde/) |
| Pipeline loader | [core/…/pipeline/loader/LoomPipelineLoader.java](../cortex/core/src/main/java/io/metaloom/cortex/pipeline/loader/LoomPipelineLoader.java) |
| Cortex Dagger wiring | [core/…/cli/dagger/CortexBindModule.java](../cortex/core/src/main/java/io/metaloom/cortex/cli/dagger/CortexBindModule.java) |
| Node collection | [cli/…/dagger/NodeCollectionModule.java](../cortex/cli/src/main/java/io/metaloom/cortex/cli/dagger/NodeCollectionModule.java) |
| Loom control channel | [core/…/impl/loom/LoomControlChannel.java](../cortex/core/src/main/java/io/metaloom/cortex/impl/loom/LoomControlChannel.java) |
| Work-order handler | [core/…/impl/loom/PipelineWorkOrderHandler.java](../cortex/core/src/main/java/io/metaloom/cortex/impl/loom/PipelineWorkOrderHandler.java) |
| Pipeline REST endpoint | [rest/…/endpoint/impl/PipelineEndpoint.java](../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEndpoint.java) |
| Pipeline event WS endpoint | [rest/…/endpoint/impl/PipelineEventEndpoint.java](../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEventEndpoint.java) |
| Processor WS endpoint | [rest/…/endpoint/impl/ProcessorEndpoint.java](../loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ProcessorEndpoint.java) |
| Event broadcaster | [rest/…/service/impl/PipelineEventBroadcaster.java](../loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEventBroadcaster.java) |
| DAO API | [db/api/…/model/pipeline/](../loom/db/api/src/main/java/io/metaloom/loom/db/model/pipeline/) |
| DAO impl | [db/jooq/…/dao/pipeline/](../loom/db/jooq/src/main/java/io/metaloom/loom/db/jooq/dao/pipeline/) |
| SQL migration | [db/flyway/…/V2.19__add_pipeline.sql](../loom/db/flyway/src/main/resources/db/migration/V2.19__add_pipeline.sql) |
| REST DTOs | [loom-shared/rest-model/…/pipeline/](../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/) |
| Event DTOs | [loom-shared/rest-model/…/pipeline/event/](../loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/event/) |
| Node descriptors | [nodes/common-api/…/spec/](../cortex/nodes/common-api/src/main/java/io/metaloom/loom/nodes/spec/) |
| Docs | [loom/doc/src/main/docs/pipeline/index.adoc](../loom/doc/src/main/docs/pipeline/index.adoc) |

---

## 14. Conventions & Gotchas

- **RxJava first**: any change to executor semantics goes through
  `ReactivePipelineExecutor` — do not resurrect a
  `CompletableFuture`-based executor. Older memory notes may still
  reference `DAGPipelineExecutor`; that class no longer exists.
- **Two `NodeMode` types** exist: `io.metaloom.cortex.pipeline.api.NodeMode`
  (runtime) vs `io.metaloom.loom.nodes.spec.NodeMode` (UI descriptor).
  Import the right one.
- **Two `NodeResult` types** exist: `io.metaloom.cortex.pipeline.api.NodeResult`
  (new) vs `io.metaloom.cortex.api.node.NodeResult` (legacy). The
  adapter (`CortexNodeAdapter`) converts between them.
- **Node ids**: lowercase, alphanumeric, hyphens; 1–64 chars; unique
  per pipeline. Enforced by `DefaultPipeline.NODE_ID_PATTERN`.
- **Source node**: exactly one, set via `setSource(true)` on an
  `AbstractPipelineNode` (or an `AssetSourceNode`/wrapped
  `SourceNode`).
- **Filter branch routing** relies on `filter_passed` being present in
  the filter node's output map. If you subclass `AbstractFilterNode`,
  the output is written for you — do not overwrite it.
- **`syncToLoom` only fires on `COMPLETED`** — failed/skipped node
  results are never bulk-synced.
- **Blocking dependency failure ⇒ SKIP downstream** (message
  `"Dependency <id> failed"`). Non-blocking dependencies do not
  cascade skips.
- **Dry-run mode**: nodes are all `SKIPPED` with message `"dry-run"`;
  neither cache nor sync-collector is touched.
- **Semaphores are executor-scoped**: an executor instance shares its
  per-node semaphores across all pipelines executed on it. Create a
  fresh executor per pipeline family if you need isolation.
- **Legacy nodes need `CortexNodeAdapter`** to participate in a
  pipeline — never make a concrete node extend both `AbstractMediaNode`
  and `AbstractPipelineNode`.
- **Tracking event enum sync**: adding a `PipelineTrackingEvent.Type`
  requires a matching entry in
  `io.metaloom.loom.rest.model.pipeline.event.PipelineEventType`,
  because `LoomControlChannel` maps them by `valueOf(name())`.
- **Loom pipeline resolution is now priority-only**: media-type
  filtering has moved into filter nodes; do not add filter logic to
  `DefaultPipelineManager.resolve`.
- **Do not create files unless necessary** — this file itself, DAO
  changes, event model changes, and adapter tweaks should each pass
  through the standard test layers listed in §11.
