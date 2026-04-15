# Cortex Event Handling & Pipeline Execution Modes

## 1. Overview

This specification defines how Loom event messages control and trigger processing nodes in Cortex. Three distinct execution modes are described, each offering different trade-offs between data locality, control granularity, network overhead, and testability.

**Existing infrastructure leveraged:**
- WebSocket control channel (`LoomControlChannel` ↔ `ProcessorEndpoint`) with `ProcessorMessage` envelope
- `WorkOrder` / `WorkOrderResult` dispatch model
- `PipelineEventBus` for intra-pipeline event propagation
- `LoomBulkSyncCollector` for batched result submission
- `LoomMedia` as file-handle abstraction with xattr-backed metadata

---

## 2. Execution Modes

### Mode A — Cortex-Local Pipeline Execution

> *"Pipeline runs on Cortex. Loom only triggers and receives results."*

#### Description

The full DAG pipeline executes inside the Cortex process. Loom sends a lightweight trigger event (work order) that names a pipeline and optionally a media source (filesystem path, asset location UUID, or filter criteria). Cortex resolves the media items locally and feeds them through the pipeline's source node. Results flow back to Loom via `LoomBulkSyncCollector` and `PIPELINE_EVENT` messages.

#### Event Flow

```
Loom                              Cortex
 │                                  │
 │──WORK_ORDER(RUN_PIPELINE)───────>│  (1) trigger
 │                                  │──resolve media locally
 │                                  │──execute DAG pipeline
 │<──PIPELINE_EVENT(STARTED)────────│  (2) progress
 │<──PIPELINE_EVENT(NODE_COMPLETED)─│  (3) per-node progress
 │<──PIPELINE_EVENT(NODE_COMPLETED)─│
 │<──WORK_ORDER_RESULT──────────────│  (4) completion summary
 │                                  │
 │<──bulk sync (REST)───────────────│  (5) batched results
```

#### WorkOrder Extension

```json
{
  "workOrderId": "uuid",
  "type": "RUN_PIPELINE",
  "parameters": {
    "pipelineName": "default-ingest",
    "sourcePath": "/mnt/media/incoming",
    "assetLocationUuid": "uuid (alternative to sourcePath)",
    "filter": { "mimeTypePrefix": "video/", "minSizeBytes": 1024 },
    "dryRun": false
  }
}
```

#### Pros

| Aspect | Detail |
|--------|--------|
| **Data locality** | Media files are read directly from the filesystem — zero network transfer for media bytes |
| **Throughput** | Full DAG parallelism with semaphore-based per-node concurrency (e.g., whisper=1, hash=4) |
| **Simplicity** | Pipeline topology is resolved once at startup; no per-asset coordination overhead |
| **Existing code** | Maps directly to current `DAGPipelineExecutor`, `FilesystemProcessor`, and `CortexNodeAdapter` |
| **Caching** | Full xattr/sidecar/heap cache stack available — skipped nodes cost nearly nothing |
| **Offline capable** | Cortex can operate without Loom connection (results cached locally, synced later) |

#### Cons

| Aspect | Detail |
|--------|--------|
| **Coarse control** | Loom cannot steer individual node execution—it's all-or-nothing per pipeline trigger |
| **Scaling** | Scaling requires deploying more Cortex instances with shared filesystem access (NFS/CIFS) |
| **Coupling** | Cortex must have filesystem access to media; cannot process cloud-stored assets without mounting |
| **Pipeline rigidity** | Pipeline DAG is defined in Cortex config; changing topology requires Cortex restart or `reload-pipelines` work order |

#### Testing Nodes

```java
// Unit test: instantiate node directly, pass mock LoomMedia
LoomMedia media = LoomMediaImpl.of(Path.of("test-video.mp4"));
Map<String, NodeResult> upstreamResults = Map.of();
NodeResult result = myNode.process(media, upstreamResults);
assertThat(result.state()).isEqualTo(NodeState.COMPLETED);
assertThat(result.getOutput("sha512")).isNotNull();

// Integration test: build mini-pipeline with real DAGPipelineExecutor
Pipeline pipeline = DefaultPipeline.builder("test")
    .source(sourceNode)
    .build();
PipelineResult pr = executor.execute(pipeline, media);
assertTrue(pr.isSuccess());
```

- Nodes are plain Java objects — no network mocking required
- `HeapNodeCache` or `NoOpNodeCache` for deterministic test runs
- `FilesystemProcessorTest` pattern already established in codebase

---

### Mode B — Loom-Orchestrated Node-Level Dispatch

> *"Pipeline runs on Loom. Cortex nodes are stateless workers commanded per-asset-per-node."*

#### Description

Loom maintains the pipeline DAG and acts as an orchestrator. For each asset × node combination, Loom sends a `WORK_ORDER` containing the asset reference and required processing action. The Cortex node executes only the requested operation and returns the result. Loom tracks DAG state, resolves dependencies, and dispatches the next work orders.

#### Event Flow

```
Loom (orchestrator)                Cortex (worker)
 │                                   │
 │──WORK_ORDER(PROCESS_NODE)────────>│  asset_uuid + node_type + params
 │                                   │──fetch asset (REST or local)
 │                                   │──execute single node
 │<──WORK_ORDER_RESULT───────────────│  node result payload
 │                                   │
 │  (evaluate DAG, dispatch next)    │
 │──WORK_ORDER(PROCESS_NODE)────────>│  next node in DAG
 │                                   │──...
```

#### WorkOrder Extension

```json
{
  "workOrderId": "uuid",
  "type": "PROCESS_NODE",
  "requiredCapability": "WHISPER_TRANSCRIPTION",
  "assetUuids": ["uuid1"],
  "parameters": {
    "nodeType": "whisper-transcription",
    "pipelineName": "default-ingest",
    "upstreamResults": {
      "hash-node": { "sha512": "abc...", "md5": "def..." },
      "mime-filter": { "filter_passed": true }
    },
    "assetDownloadUrl": "https://loom.internal/api/v1/assets/uuid1/binary",
    "assetMimeType": "video/mp4",
    "assetSizeBytes": 52428800
  }
}
```

#### Pros

| Aspect | Detail |
|--------|--------|
| **Fine-grained control** | Loom decides exactly which node processes which asset — supports priority queues, retries, rescheduling |
| **Heterogeneous scaling** | Route GPU work orders to GPU nodes, CPU work to CPU nodes based on `requiredCapability` |
| **No shared filesystem** | Assets can be fetched via HTTP; Cortex workers can be cloud VMs without NFS mounts |
| **Dynamic topology** | Pipeline DAG changes in Loom take effect immediately — no Cortex restart needed |
| **Centralized state** | All progress, failures, and retries tracked in Loom's database |

#### Cons

| Aspect | Detail |
|--------|--------|
| **Data transfer overhead** | Large media files must be transferred per work order (video=GBs); upstream `NodeResult` maps serialized in every message |
| **Orchestration complexity** | Loom must implement DAG dependency resolution, failure handling, retry logic, and work queue management |
| **Latency** | Network round-trip per node per asset; deep pipelines (10+ nodes) multiply latency |
| **Result serialization** | `upstreamResults` grows as pipeline progresses — passing all prior outputs is expensive for embedding vectors, thumbnails, etc. |
| **Cache invalidation** | No local xattr cache; either Loom caches results server-side or work is repeated |
| **Testing** | Requires mocking the Loom dispatch loop or building a test harness that simulates work order sequences |

#### Data Transfer Mitigation Strategies

1. **Reference-based transfer**: Pass asset SHA-512 + download URL instead of bytes. Cortex fetches on demand.
2. **Result trimming**: Only include upstream results that the target node actually declares as dependencies (via `NodeOutputKey` declarations).
3. **Shared object store**: Both Loom and Cortex access an S3/MinIO bucket; work orders reference object keys.
4. **Chunked processing**: For large files, stream via HTTP range requests rather than full download.

#### Testing Nodes

```java
// Unit test: same as Mode A (nodes are still plain Java)
NodeResult result = myNode.process(media, upstreamResults);

// Integration test: mock Loom dispatch
WorkOrder wo = new WorkOrder()
    .setType(WorkOrderType.PROCESS_NODE)
    .setParameters(new JsonObject()
        .put("nodeType", "whisper-transcription")
        .put("upstreamResults", JsonObject.mapFrom(upstreamResults)));

WorkOrderResult wor = workOrderHandler.handle(wo);
assertEquals(WorkOrderStatus.COMPLETED, wor.getStatus());

// End-to-end: requires running Loom + Cortex; significantly more setup
```

- Individual nodes remain testable in isolation (same `process()` signature)
- Integration tests need a `WorkOrderHandler` that resolves node type → node instance, fetches/mocks media
- E2E tests require the full Loom orchestration loop

---

### Mode C — Hybrid: Cortex-Local Pipeline with Loom Event Injection

> *"Pipeline runs on Cortex, but Loom can inject assets into a running pipeline via events."*

#### Description

Cortex runs the pipeline DAG locally (like Mode A), but instead of only scanning the filesystem, it also listens for `PROCESS_ASSET` events from Loom via the WebSocket control channel. When Loom detects a new asset (e.g., via upload API, external webhook, or database trigger), it sends an event containing the asset's metadata and optional download URL. Cortex resolves the asset to a local `LoomMedia` (either by local path lookup or by downloading to a staging area) and injects it into the running pipeline.

#### Event Flow

```
Loom                              Cortex
 │                                  │
 │  (cortex starts, connects)       │
 │<──REGISTER─────────────────────  │
 │──REGISTERED───────────────────>  │
 │                                  │──start pipelines, scan filesystem
 │                                  │
 │  (new asset uploaded to Loom)    │
 │──PROCESS_ASSET(event)──────────> │  asset_uuid + sha512 + path/URL
 │                                  │──resolve to local LoomMedia
 │                                  │──inject into pipeline source
 │<──PIPELINE_EVENT(STARTED)──────  │
 │<──PIPELINE_EVENT(NODE_COMPLETED) │
 │<──bulk sync (REST)───────────────│
```

#### New Message Type

Add `PROCESS_ASSET` to `ProcessorMessageType`:

```java
/** Loom requests Cortex to process a specific asset through a named pipeline */
PROCESS_ASSET
```

#### Message Payload

```json
{
  "type": "PROCESS_ASSET",
  "body": {
    "assetUuid": "uuid",
    "sha512": "hex-string",
    "localPath": "/mnt/media/uploads/video.mp4",
    "downloadUrl": "https://loom.internal/api/v1/assets/uuid/binary",
    "pipelineName": "default-ingest",
    "priority": "HIGH",
    "metadata": {
      "originalFilename": "video.mp4",
      "mimeType": "video/mp4",
      "sizeBytes": 52428800
    }
  }
}
```

#### Pros

| Aspect | Detail |
|--------|--------|
| **Data locality preserved** | Pipeline still runs locally; media resolved via local path when available |
| **Event-driven** | Loom pushes assets as they arrive — no polling, no scanning delay |
| **Full DAG execution** | Retains all pipeline benefits (caching, parallelism, filter branches) |
| **Selective processing** | Loom can target specific pipelines and set priorities |
| **Incremental** | Only new/changed assets dispatched — no full re-scan needed |
| **Graceful degradation** | If Loom disconnects, Cortex continues with filesystem scanning |

#### Cons

| Aspect | Detail |
|--------|--------|
| **Requires reachable media** | Cortex must be able to resolve the asset to a local path or download it |
| **Download staging** | For remote assets, Cortex needs a staging directory and cleanup policy |
| **Priority management** | Injected assets compete with filesystem-scanned items for pipeline capacity |
| **Message ordering** | No guarantee that PROCESS_ASSET events arrive in desired order; needs queue/priority handling |

#### Testing Nodes

Same as Mode A — nodes are pipeline-local. Additional test surface:

```java
// Test asset injection path
ProcessorMessage msg = new ProcessorMessage()
    .setType(ProcessorMessageType.PROCESS_ASSET)
    .setBody(new JsonObject()
        .put("assetUuid", "test-uuid")
        .put("localPath", "/tmp/test-media/sample.mp4")
        .put("pipelineName", "default-ingest"));

controlChannel.handleIncoming(msg);
// Assert pipeline picked up the media item
```

---

### Mode D — Distributed Pipeline with Node-Level Delegation

> *"Pipeline DAG runs on Cortex, but individual nodes can delegate to remote Cortex workers."*

#### Description

A hybrid of A and B. The pipeline DAG executes on a **coordinator** Cortex instance. Most nodes run locally, but heavy nodes (e.g., Whisper transcription, LLM inference, YOLO detection) are marked as `remote` and their execution is delegated to specialized worker Cortex instances via Loom's work order routing. The coordinator sends a `PROCESS_NODE` work order through Loom, which routes it to a capable worker. The worker returns the `NodeResult`, and the coordinator continues the DAG.

#### Event Flow

```
Loom                   Cortex-Coordinator          Cortex-Worker (GPU)
 │                          │                            │
 │──WORK_ORDER(RUN_PIPELINE)>│                           │
 │                          │──execute DAG               │
 │                          │  (hash node: local)        │
 │                          │  (whisper node: remote)    │
 │                          │                            │
 │<──WORK_ORDER(DELEGATE)───│                            │
 │──WORK_ORDER(PROCESS_NODE)──────────────────────────> │
 │                          │                            │──run whisper
 │<──WORK_ORDER_RESULT──────────────────────────────────│
 │──WORK_ORDER_RESULT──────>│                            │
 │                          │  (continue DAG with result)│
 │                          │  (llm node: remote)        │
 │                          │  ...                       │
 │<──PIPELINE_EVENT─────────│                            │
```

#### Pros

| Aspect | Detail |
|--------|--------|
| **Best of both worlds** | Local execution for cheap nodes, remote for expensive ones |
| **GPU scaling** | GPU-heavy nodes routed to GPU workers without moving entire pipeline |
| **Capability routing** | Loom routes by `ProcessorCapability`; workers self-declare capabilities at registration |
| **DAG stays local** | No need to reimplement DAG resolution in Loom |
| **Selective data transfer** | Only remote nodes pay the network cost; cheap nodes (hash, filter) stay local |

#### Cons

| Aspect | Detail |
|--------|--------|
| **Complexity** | Coordinator must handle async delegation, timeouts, and fallback to local execution |
| **Partial data transfer** | Remote nodes still need media access (shared FS or download) |
| **Coordinator bottleneck** | Single coordinator must manage all pipeline state |
| **New abstraction** | Requires `RemotePipelineNode` wrapper that delegates `process()` to work order dispatch |

#### Testing Nodes

```java
// Unit: identical to Mode A
// Integration: mock the delegation path
RemotePipelineNode remoteWhisper = new RemotePipelineNode("whisper", delegator);
NodeResult result = remoteWhisper.process(media, upstreamResults);
// delegator is a mock that returns a canned WorkOrderResult

// Full integration: requires coordinator + worker + Loom
```

---

## 3. Comparison Matrix

| Criterion | Mode A (Cortex-Local) | Mode B (Loom-Orchestrated) | Mode C (Hybrid Event) | Mode D (Distributed DAG) |
|-----------|----------------------|---------------------------|----------------------|--------------------------|
| **Data locality** | Excellent | Poor (network transfer) | Good (local preferred) | Mixed (per-node) |
| **Control granularity** | Pipeline-level | Node × asset level | Pipeline + asset-level | Pipeline + selective node |
| **Network overhead** | Minimal (results only) | High (media + results) | Low-Medium | Medium (remote nodes only) |
| **Scaling model** | Horizontal (shared FS) | Horizontal (stateless) | Horizontal (shared FS) | Heterogeneous |
| **Implementation effort** | Low (existing code) | High (new orchestrator) | Medium (new message type) | High (delegation layer) |
| **Testability** | Excellent | Good (unit) / Hard (E2E) | Excellent | Good (unit) / Medium (E2E) |
| **Offline operation** | Yes | No | Partial | Partial |
| **Dynamic topology** | Restart needed | Immediate | Restart needed | Restart needed |
| **GPU routing** | No (all local) | Yes | No (all local) | Yes |
| **Failure isolation** | Pipeline-level | Node-level | Pipeline-level | Node-level for remote |

---

## 4. Recommendation

**Start with Mode A + C (phased approach):**

1. **Phase 1 — Mode A** is already implemented. Solidify the `RUN_PIPELINE` work order type to allow Loom to trigger pipeline execution with asset location scoping and filter parameters. This requires minimal new code.

2. **Phase 2 — Add Mode C** by introducing the `PROCESS_ASSET` message type. This enables event-driven processing (upload triggers, webhooks) while keeping the pipeline execution local. The key new components are:
   - `PROCESS_ASSET` message handler in `LoomControlChannel`
   - Asset resolver (local path lookup → download fallback)
   - Priority queue for injected assets vs. scanned assets

3. **Phase 3 — Add Mode D selectively** for nodes that require GPU or specialized hardware. Introduce `RemotePipelineNode` as a pipeline node implementation that delegates to work order dispatch. This can be done incrementally per node type.

**Mode B is not recommended** as a primary architecture because the data transfer overhead for media-heavy workloads (video, audio) is prohibitive, and it requires reimplementing the DAG execution engine inside Loom. However, Mode B's per-node dispatch mechanism is effectively reused in Mode D's delegation layer.

---

## 5. WorkOrder Type Extensions

To support all modes, extend `WorkOrderType`:

```java
public enum WorkOrderType {
    FILESYSTEM_SCAN,    // existing
    FINGERPRINT,        // existing
    RUN_PIPELINE,       // Mode A: trigger full pipeline
    PROCESS_NODE,       // Mode B/D: execute single node
    RELOAD_PIPELINES,   // admin: reload pipeline configs
    FLUSH_SYNC,         // admin: flush bulk sync buffer
    LIST_PIPELINES      // admin: list registered pipelines
}
```

---

## 6. ProcessorMessageType Extensions

```java
public enum ProcessorMessageType {
    // existing...
    REGISTER, HEARTBEAT, STATUS_UPDATE, STATE_CHANGE,
    WORK_ORDER_RESULT, PIPELINE_EVENT,
    REGISTERED, HEARTBEAT_ACK, WORK_ORDER, ERROR,

    // new
    PROCESS_ASSET,          // Loom → Cortex: inject asset into pipeline (Mode C)
    PROCESS_ASSET_ACK,      // Cortex → Loom: asset accepted/queued
    DELEGATE_NODE,          // Cortex-Coordinator → Loom: delegate node execution (Mode D)
    DELEGATE_NODE_RESULT    // Loom → Cortex-Coordinator: delegated result (Mode D)
}
```

---

## 7. Asset Resolution Strategy

For Modes C and D, Cortex needs to resolve an asset reference to a local `LoomMedia`:

```
AssetReference (uuid + sha512 + localPath? + downloadUrl?)
        │
        ▼
   ┌─────────────────┐
   │ AssetResolver    │
   │                  │
   │ 1. localPath set │──> verify exists ──> LoomMedia
   │    and exists?   │
   │                  │
   │ 2. lookup by     │──> scan known     ──> LoomMedia
   │    sha512 in     │    asset locations
   │    local FS?     │
   │                  │
   │ 3. downloadUrl   │──> download to    ──> LoomMedia
   │    available?    │    staging dir
   │                  │
   │ 4. fetch via     │──> GET /assets/   ──> LoomMedia
   │    Loom REST?    │    {uuid}/binary
   │                  │
   │ 5. none          │──> REJECT with error
   └─────────────────┘
```

Staging directory cleanup: configurable TTL (default 24h), LRU eviction when disk usage exceeds threshold.

---

## 8. Testing Strategy Summary

| Test Level | Mode A | Mode B | Mode C | Mode D |
|------------|--------|--------|--------|--------|
| **Unit (node)** | `node.process(media, results)` — no infra needed | Same | Same | Same |
| **Unit (pipeline)** | `DAGPipelineExecutor` + in-memory nodes | N/A (no local pipeline) | Same as A | Same as A + mock delegator |
| **Integration** | `FilesystemProcessorTest` pattern | `WorkOrderHandler` + mock media fetch | Control channel + mock WebSocket | Coordinator + mock Loom routing |
| **E2E** | Cortex CLI + local files | Loom + Cortex + network | Loom + Cortex + WebSocket | Loom + Coordinator + Worker(s) |

All modes share the same node-level unit test approach because `process(LoomMedia, Map<String, NodeResult>)` is the universal contract.