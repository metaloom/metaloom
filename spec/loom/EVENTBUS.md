# Loom Eventbus System

> Spec and progress tracker for the MetaLoom eventbus and pipeline event dispatch system.
> Written for AI coding agents working on the MCP feature and related event infrastructure.

---

## 1. Overview

MetaLoom has **two independent event systems** that serve different purposes:

| System | Scope | Transport | Used For |
|--------|-------|----------|----------|
| **Cortex Pipeline Event Bus** (`PipelineEventBus`) | In-process (cortex side) | Java pub/sub (no Vert.x EventBus) | Internal pipeline coordination, sync collection, caching, lightweight tracking events |
| **Vert.x EventBus** | Vert.x instance (loom side) | Vert.x EventBus (in-process / cluster-capable) | MCP tool dispatch only (`mcp.tool.<name>`) |
| **WebSocket fan-out** (`PipelineEventBroadcaster`) | Loom REST server | Raw `ServerWebSocket` (no SockJS bridge) | Forwarding pipeline tracking events from cortex processors to UI clients |

The **`loom-service-eventbus` module** (`loom/services/eventbus/`) is an **empty placeholder** - it has a `pom.xml` and a one-line README but **no source files**. No other module depends on it. All actual event dispatching code lives in:
- `cortex/pipeline-common` - `DefaultPipelineEventBus` implementation
- `cortex/pipeline-core` - `ReactivePipelineExecutor` (event publisher)
- `cortex/core` - `LoomControlChannel` (bridge from cortex to loom)
- `loom/services/rest` - `PipelineEventBroadcaster`, `PipelineEventEndpoint`, `ProcessorEndpoint`
- `loom/services/mcp` - `MCPService`, `MCPToolRegistry` (Vert.x EventBus for tool dispatch)

---

## 2. Architecture Diagram

```mermaid
┌─────────────────────────────────────────────────────────────────┐
│  Cortex Processor Node (in-process)                             │
│                                                                 │
│  ReactivePipelineExecutor                                       │
│    |  publishes NodeCompletionEvent + PipelineTrackingEvent     │
│    v                                                            │
│  DefaultPipelineEventBus (in-process pub/sub)                   │
│    |  subscribeTracking()                                       │
│    v                                                            │
│  LoomControlChannel.forwardPipelineTrackingEvent()              │
│    |  converts PipelineTrackingEvent -> PipelineEventMessage    │
│    |  wraps in ProcessorMessage(PIPELINE_EVENT)                 │
│    v                                                            │
│  WebSocket client -> /api/v1/processors/ws                      │
└─────────────────────────────────────────────────────────────────┘
                           |
                           v
┌─────────────────────────────────────────────────────────────────┐
│  Loom REST Server                                               │
│                                                                 │
│  ProcessorEndpoint.handlePipelineEvent()                        │
│    |  deserializes PipelineEventMessage                         │
│    v                                                            │
│  PipelineEventBroadcaster.broadcast(event)                      │
│    |  fans out to all matching subscribers                      │
│    |  (per-pipeline filter, backpressure-aware)                │
│    v                                                            │
│  WebSocket -> /api/v1/pipelines/events/ws -> UI clients        │
└─────────────────────────────────────────────────────────────────┘
```

### Separate Vert.x EventBus (MCP only)

```mermaid
┌─────────────────────────────────────────────────────────────────┐
│  MCP Service (loom/services/mcp)                                │
│                                                                 │
│  MCPToolRegistry                                                │
│    |  registers consumers on Vert.x EventBus                    │
│    |  address: mcp.tool.<toolName>                              │
│    v                                                            │
│  Vert.x EventBus (vertx.eventBus())                             │
│    |  request("mcp.tool.<name>", arguments) -> reply            │
│    v                                                            │
│  MCPTool.execute() runs and replies                             │
└─────────────────────────────────────────────────────────────────┘
```

The Vert.x EventBus is **NOT** used for pipeline event dispatch. It is only used by the MCP tool registry for internal tool invocation.

---

## 3. Key Components

### 3.1 Cortex Pipeline Event Bus (in-process)

**Interface:** `cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/event/PipelineEventBus.java`

Two channels:
- **Node completion events** - full-fidelity `NodeCompletionEvent` carrying `LoomMedia` + `NodeResult`. Used for internal pipeline coordination (sync collection, caching).
- **Tracking events** - lightweight `PipelineTrackingEvent` with scalar-only data. Designed for high-volume WebSocket dispatch.

**Implementation:** `cortex/pipeline-common/src/main/java/io/metaloom/cortex/pipeline/common/event/DefaultPipelineEventBus.java`
- `ConcurrentHashMap` + `CopyOnWriteArrayList` for thread-safe subscriptions.
- `publish()` dispatches to node-specific subscribers, then global subscribers.
- `publishTracking()` dispatches to all tracking subscribers.
- Events dispatched **synchronously** on the publishing thread.
- Subscription handles are UUID strings stored in a `handleCleanup` map for O(1) unsubscribe.

**Wiring:** `CortexBindModule.providePipelineEventBus()` returns `new DefaultPipelineEventBus()`.

### 3.2 Event Types

**`PipelineTrackingEvent.Type`** (cortex side):
- `PIPELINE_STARTED`, `PIPELINE_COMPLETED`
- `NODE_STARTED`, `NODE_COMPLETED`, `NODE_FAILED`, `NODE_SKIPPED`, `NODE_BUFFERED`

**`PipelineEventType`** (REST model, `loom-shared/rest-model`):
- Same as above plus `NODE_STATS` (REST-model-only, injected by processors with aggregate throughput data: `activeCount`, `pendingCount`, `processedCount`, `failedCount`).

**`PipelineEventMessage`** (REST model, WebSocket envelope):
- Fields: `type`, `pipelineName`, `nodeId`, `mediaPath`, `timestamp`, `durationMs`, `message`, plus stats fields (`activeCount`, `pendingCount`, `processedCount`, `failedCount`).
- Implements `RestModel` (JSON-serializable).

**`NodeCompletionEvent`** (cortex internal):
- Fields: `nodeId`, `LoomMedia media`, `NodeResult result`, `timestamp`.
- NOT sent over WebSocket - used only for in-process coordination.

### 3.3 ReactivePipelineExecutor (Event Publisher)

**File:** `cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/executor/ReactivePipelineExecutor.java`

Publishes events at these lifecycle points:

| Location | Event | Type |
|---|---|---|
| `execute()` start | `publishTracking` | `PIPELINE_STARTED` |
| `execute()` completion | `publishTracking` | `PIPELINE_COMPLETED` |
| Node semaphore unavailable | `emitTrackingEvent` | `NODE_BUFFERED` |
| Node semaphore acquired | `emitTrackingEvent` | `NODE_STARTED` |
| Node success | `publish` + `emitTrackingEvent` | `NODE_COMPLETED` |
| Node failure | `publish` + `emitTrackingEvent` | `NODE_FAILED` |
| Dependency failure / filter mismatch | `publish` + `emitTrackingEvent` | `NODE_SKIPPED` |

The `emitTrackingEvent` helper converts `LoomMedia` to path string and calls `eventBus.publishTracking()`.

### 3.4 LoomControlChannel (Cortex -> Loom Bridge)

**File:** `cortex/core/src/main/java/io/metaloom/cortex/impl/loom/LoomControlChannel.java`

This is the critical bridge between the in-process cortex `PipelineEventBus` and the loom WebSocket dispatch. It runs inside a cortex processor node:

1. On `start()`, subscribes to tracking events: `pipelineEventBus.subscribeTracking(this::forwardPipelineTrackingEvent)`
2. Connects a WebSocket client to loom's `/api/v1/processors/ws` and sends a `REGISTER` message.
3. For each `PipelineTrackingEvent`, converts it to `PipelineEventMessage` and wraps in `ProcessorMessage(PIPELINE_EVENT, ...)`, sends over WebSocket.
4. Also handles heartbeats, status updates, work orders, and reconnection with exponential backoff.

### 3.5 PipelineEventBroadcaster (Loom-side Fan-out)

**File:** `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEventBroadcaster.java`

- `@Singleton`, thread-safe `ConcurrentHashMap<ServerWebSocket, Subscriber>`.
- `broadcast(PipelineEventMessage)` - fans out to all matching subscribers. JSON is lazily encoded (only if >=1 subscriber matches).
- **Per-pipeline filtering**: subscribers can filter by pipeline name via `?pipeline=<name>` query param.
- **Backpressure**: each subscriber has a bounded queue (default 1024). When `ws.writeQueueFull()`, events are dropped and `droppedCount` is incremented (logged every 100 drops).
- Uses `ws.writeTextMessage(json)` for non-blocking writes.

### 3.6 WebSocket Endpoints

**PipelineEventEndpoint** (`loom/services/rest/.../endpoint/impl/PipelineEventEndpoint.java`):
- Route: `GET /api/v1/pipelines/events/ws` (WebSocket upgrade, order -1000 to beat wildcard auth routes).
- Read-only from client side - clients only receive events, never send.
- Authentication via `?token=<jwt>` query parameter validated by `WebSocketAuthenticator` (close code `4401` on failure).
- Per-pipeline filtering via `?pipeline=<name>` query parameter.
- Registers each WebSocket as a subscriber in `PipelineEventBroadcaster`.

**ProcessorEndpoint** (`loom/services/rest/.../endpoint/impl/ProcessorEndpoint.java`):
- Route: `GET /api/v1/processors/ws` (WebSocket upgrade).
- Processor nodes connect, register, and exchange `ProcessorMessage` envelopes.
- The `PIPELINE_EVENT` message type handler (`handlePipelineEvent`) deserializes the body to `PipelineEventMessage` and calls `pipelineEventBroadcaster.broadcast(event)`.
- Also dispatches `WORK_ORDER` messages to processors via `ProcessorRegistry.dispatchWorkOrder()`.

### 3.7 Vert.x EventBus (MCP Tool Dispatch Only)

**VertxModule** (`loom/common/src/main/java/io/metaloom/loom/common/dagger/VertxModule.java`):
- Provides `EventBus` and `io.vertx.rxjava3.core.eventbus.EventBus` as singletons via Dagger.

**MCPToolRegistry** (`loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/MCPToolRegistry.java`):
- Registers each tool on the Vert.x EventBus at address `mcp.tool.<name>`.
- Dispatches tool calls via `vertx.eventBus().request("mcp.tool.<name>", arguments)`.
- Tools reply with results or failures using `msg.reply(result)` / `msg.fail(...)`.

**MCPService** (`loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPService.java`):
- Provides HTTP+SSE and WebSocket transports for MCP JSON-RPC.
- WebSocket at `/mcp/ws` for bidirectional streaming.
- Tool calls dispatched through `MCPToolRegistry.dispatch()` which uses the Vert.x EventBus.

### 3.8 Message Models

**ProcessorMessage** (`loom-shared/rest-model/.../processor/message/ProcessorMessage.java`):
- Fields: `type` (`ProcessorMessageType`), `body` (`JsonObject`).

**ProcessorMessageType**:
- Processor->Loom: `REGISTER`, `HEARTBEAT`, `STATUS_UPDATE`, `STATE_CHANGE`, `WORK_ORDER_RESULT`, `PIPELINE_EVENT`.
- Loom->Processor: `REGISTERED`, `HEARTBEAT_ACK`, `WORK_ORDER`, `ERROR`.

### 3.9 UI Client

**`loom-ui/src/api/pipelineEvents.ts`** - TypeScript client that opens a WebSocket to `/api/v1/pipelines/events/ws`, parses `PipelineEventMessage` JSON, and dispatches to registered listeners. Supports lazy connection, auto-reconnect with exponential backoff + jitter (bounded by `maxAttempts`, default 10), connection-state events (`connecting`/`connected`/`disconnected`/`failed`) via `subscribeConnectionState`, tunable via `configureReconnect`, and token auth via `?token=` query param.

**`loom-ui/src/Pipeline/PipelineArea.tsx`** - React component that subscribes to live events and updates `nodeStates` in real time, mapping `PipelineEventType` -> visual node status (`processing`, `completed`, `failed`, `skipped`, `buffered`).

---

## 4. Tests

### 4.1 Pipeline Executor Tests

**File:** `cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/core/PipelineExecutorTest.java`
- `testFullPipelineExecution()` - subscribes via `executor.getEventBus().subscribeAll(...)`, verifies 7 completion events for a 7-node DAG.
- Tests parallel execution, per-node concurrency limiting, caching, dry-run, dependency failure skipping.

### 4.2 Abstract Pipeline Node Test Base

**File:** `cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/AbstractPipelineNodeTest.java`
- Sets up `DefaultPipelineEventBus` + `ReactivePipelineExecutor` in `@BeforeEach`.
- Subscribes to both channels: `eventBus.subscribeAll(completionEvents::add)` and `eventBus.subscribeTracking(trackingEvents::add)`.
- Provides `assertCompletionEvent` / tracking event assertions for subclasses.

### 4.3 Node-specific Pipeline Tests

All extend `AbstractPipelineNodeTest` and use tracking events:
- `FacedetectNodePipelineTest`
- `FingerprintNodePipelineTest`
- `ChunkHashNodePipelineTest`
- `MD5NodePipelineTest`
- `SHA512NodePipelineTest`
- `LLMNodePipelineTest`
- `ThumbnailNodePipelineTest`
- `WhisperNodePipelineTest`

### 4.4 WebSocket Endpoint Tests

**File:** `loom/core/src/test/java/io/metaloom/core/endpoint/test/PipelineEventEndpointTest.java`

End-to-end tests using real Vert.x server:
- `testConnectToPipelineEventsWs()` - UI client connects to `/api/v1/pipelines/events/ws`
- `testPipelineEventForwarding()` - processor sends `PIPELINE_EVENT`, UI subscriber receives it
- `testMultipleSubscribersReceiveEvent()` - fan-out to multiple UI clients
- `testPipelineEventWithoutRegister()` - error if processor sends event before REGISTER
- `testPipelineEventWithoutBody()` - error if event has no body
- `testNodeLifecycleEventSequence()` - full `PIPELINE_STARTED` -> `NODE_STARTED` -> `NODE_COMPLETED` -> `PIPELINE_COMPLETED` sequence
- `testNodeStatsEvent()` - `NODE_STATS` with volume fields

**File:** `loom/core/src/test/java/io/metaloom/core/endpoint/test/ProcessorEndpointTest.java`
- Processor WebSocket lifecycle tests.

### 4.5 Test Coverage Gaps

- **No tests for Vert.x EventBus integration** - the MCP tool dispatch via EventBus has no dedicated tests.
- **No tests for `loom-service-eventbus` module** - the module is empty.
- **No integration tests for the full cortex->loom event flow** - the `LoomControlChannel` -> `ProcessorEndpoint` -> `PipelineEventBroadcaster` chain is only tested piecewise.
- **No tests for backpressure behavior** - the `PipelineEventBroadcaster`'s event dropping when `writeQueueFull()` is not tested.
- **No tests for WebSocket reconnection** - `LoomControlChannel`'s exponential backoff reconnection is not tested.

---

## 5. Integration with the Pipeline System

### 5.1 Event Flow (End-to-End)

1. **Cortex executor runs pipeline** - `ReactivePipelineExecutor` processes media items through a DAG of nodes.
2. **Executor publishes events** - At each lifecycle point (start, node start, node complete, etc.), the executor calls `eventBus.publishTracking(new PipelineTrackingEvent(...))` and/or `eventBus.publish(new NodeCompletionEvent(...))`.
3. **LoomControlChannel receives tracking events** - Its `subscribeTracking` subscription receives each `PipelineTrackingEvent`.
4. **Control channel converts and forwards** - `forwardPipelineTrackingEvent()` converts `PipelineTrackingEvent` to `PipelineEventMessage`, wraps it in `ProcessorMessage(PIPELINE_EVENT)`, and sends it over the processor WebSocket to loom.
5. **ProcessorEndpoint receives** - `handlePipelineEvent()` deserializes the `PipelineEventMessage` and calls `pipelineEventBroadcaster.broadcast(event)`.
6. **Broadcaster fans out** - `PipelineEventBroadcaster` iterates subscribers, filters by pipeline name, and writes JSON to each WebSocket via `ws.writeTextMessage(json)`.
7. **UI client receives** - The TypeScript client in `pipelineEvents.ts` parses the JSON and dispatches to registered listeners. `PipelineArea.tsx` updates the visual node states.

### 5.2 Pipeline Run Trigger

The REST endpoint `POST /api/v1/pipelines/{id}/run` (handled by `PipelineEndpointService`) triggers pipeline execution by dispatching a `WorkOrder` (type `PIPELINE_RUN`) to a registered processor via `processorRegistry.selectProcessor(CPU)` and `processorRegistry.dispatchWorkOrder()`. The response includes `PipelineRunResponse` with `workOrderId`, `processorNodeId`, and `dispatched` flag. The doc notes that per-node status can be observed on the pipeline events WebSocket.

### 5.3 Processor Registry and Work Orders

- `ProcessorRegistry` tracks connected processor nodes (state, heartbeat, status, capabilities).
- `WorkOrderResultRegistry` maps work-order UUIDs to completion callbacks with optional timeout.
- These are in `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/`.

---

## 6. Vert.x EventBus Assessment

### 6.1 Current State

The Vert.x EventBus is provided via Dagger (`VertxModule`) and is available throughout the loom application. However, it is **only used for MCP tool dispatch** (`mcp.tool.<name>` addresses). The pipeline event system does **not** use the Vert.x EventBus at all.

The pipeline event flow uses:
- A custom in-process `DefaultPipelineEventBus` (Java pub/sub with `ConcurrentHashMap`).
- Raw `ServerWebSocket` connections for cortex->loom and loom->UI communication.
- A custom JSON protocol (`ProcessorMessage` / `PipelineEventMessage`).

### 6.2 Vert.x EventBus Features Relevant to MetaLoom

Based on the [Vert.x EventBus documentation](https://vertx.io/docs/vertx-core/java/#event_bus):

| Feature | Description | Relevance to MetaLoom |
|--------|-------------|----------------------|
| **Publish/subscribe** | `eventBus.publish(address, message)` delivers to all registered consumers | Could replace `PipelineEventBroadcaster`'s manual fan-out |
| **Point-to-point / request-reply** | `eventBus.request(address, message)` delivers to one consumer, with reply | Already used for MCP tool dispatch |
| **Message codecs** | Custom serialization for typed objects | Could serialize `PipelineEventMessage` directly |
| **Clustered EventBus** | Distributed event bus across Vert.x cluster nodes | Could enable multi-node pipeline event distribution |
| **Automatic verticle cleanup** | EventBus consumers registered in verticles are auto-unregistered | Simplifies lifecycle management |
| **Backpressure** | EventBus handles message queuing internally | Could replace manual `writeQueueFull()` checks |
| **SockJS Bridge** | Bridges EventBus to browser clients via SockJS | Could replace the raw WebSocket fan-out to UI clients |

### 6.3 Vert.x SockJS Event Bus Bridge

The [Vert.x Web SockJS Bridge](https://vertx.io/docs/vertx-web/java/#_handling_event_bus_bridge_events) provides a built-in bridge that exposes the EventBus to browser clients. Key features:
- `SockJSHandler.bridge(options)` mounts a bridge at a URL path (e.g., `/eventbus/*`).
- `PermittedOptions` controls which addresses are allowed inbound/outbound.
- `BridgeEvent` handler allows filtering/authorization of bridge messages.
- Client-side `vertx-eventbus.js` library provides an EventBus API in the browser.

This could **replace** the current `PipelineEventBroadcaster` + `PipelineEventEndpoint` with a simpler architecture:
- Cortex publishes tracking events to `vertx.eventBus().publish("pipeline.events", message)`.
- UI clients subscribe via SockJS bridge at `/eventbus/*` with `PermittedOptions` for `pipeline.events.*`.
- No custom WebSocket endpoint, no custom broadcaster, no manual JSON encoding.

### 6.4 Assessment: Is the Vert.x EventBus Useful for the Pipeline Event System?

**Yes, but with caveats.** The Vert.x EventBus could simplify the pipeline event dispatch in several ways, but the current custom implementation has specific properties that would need to be preserved:

**Advantages of using Vert.x EventBus:**
1. **Simpler fan-out** - `eventBus.publish()` handles delivery to all consumers automatically, replacing the manual `ConcurrentHashMap<ServerWebSocket, Subscriber>` in `PipelineEventBroadcaster`.
2. **SockJS Bridge** - Could replace the raw WebSocket endpoint with a standards-based bridge that handles reconnection, fallback transports, and authentication.
3. **Clustered distribution** - In a multi-node deployment, the clustered EventBus would automatically distribute events across nodes.
4. **Message codecs** - Could register a custom codec for `PipelineEventMessage` to avoid manual JSON serialization.
5. **Backpressure** - Vert.x EventBus handles message queuing internally, potentially replacing the manual `writeQueueFull()` / drop logic.
6. **Lifecycle management** - EventBus consumers registered in verticles are auto-cleaned on undeploy.

**Disadvantages / risks:**
1. **Cortex runs in a separate process** - The cortex processor is not a Vert.x verticle and does not share the Vert.x instance with loom. The EventBus would need to be clustered for cortex to publish to it, adding complexity.
2. **Current architecture works** - The existing `LoomControlChannel` -> `ProcessorEndpoint` -> `PipelineEventBroadcaster` chain is functional and tested.
3. **SockJS overhead** - SockJS adds overhead compared to raw WebSockets, and the current UI client already has a working raw WebSocket implementation.
4. **Per-pipeline filtering** - The current `?pipeline=<name>` query param filtering would need to be replicated using EventBus address patterns (e.g., `pipeline.events.<name>`).
5. **Authentication** - The current `?token=<jwt>` WebSocket auth would need to be replaced with SockJS bridge authentication, which is more complex.

---

## 7. TODOs

### 7.1 Populate the `loom-service-eventbus` Module

- [ ] **TODO-1**: Create source directory and implement an EventBus-based service in `loom/services/eventbus/src/main/java/`. The module currently has no source files. If the Vert.x EventBus approach is adopted for pipeline events, this module should house the Vert.x EventBus integration code (publishers, subscribers, codecs, bridge configuration).
- [ ] **TODO-2**: Add dependencies to `loom/services/eventbus/pom.xml` - currently has no dependencies beyond the parent. Should depend on `loom-common` (for Vert.x/Dagger), `cortex-pipeline-api` (for event types), and `loom-shared/rest-model` (for `PipelineEventMessage`).

### 7.2 Vert.x EventBus Integration for Pipeline Events

- [ ] **TODO-3**: Evaluate replacing `PipelineEventBroadcaster` with Vert.x EventBus `publish()`. Register a consumer at address `pipeline.events` (or `pipeline.events.<pipelineName>`) that forwards to WebSocket subscribers. This would simplify the broadcaster and leverage Vert.x's built-in message delivery.
- [ ] **TODO-4**: Evaluate replacing `PipelineEventEndpoint` (raw WebSocket) with a SockJS Event Bus Bridge. Configure `SockJSBridgeOptions` with `PermittedOptions` for outbound addresses matching `pipeline.events.*`. This would give UI clients a standards-based EventBus client (`vertx-eventbus.js`) instead of a custom WebSocket client.
- [ ] **TODO-5**: If SockJS bridge is adopted, register a `BridgeEvent` handler to intercept `REGISTER` / `UNREGISTER` / `RECEIVE` events for authentication and per-pipeline filtering. Use `setRequiredAuthority` on `PermittedOptions` for JWT-based access control.
- [ ] **TODO-6**: Register a custom `MessageCodec` for `PipelineEventMessage` so it can be sent over the EventBus without manual `JsonObject.mapFrom()` conversion. This would simplify `LoomControlChannel.forwardPipelineTrackingEvent()`.
- [ ] **TODO-7**: If clustered deployment is planned, evaluate using a clustered EventBus (`Vertx.clusteredVertx()`) so that cortex processors on different nodes can publish events to the loom EventBus without the WebSocket bridge. This would require a `ClusterManager` (e.g., Hazelcast) on the classpath.

### 7.3 Cortex-side EventBus Integration

- [ ] **TODO-8**: Currently `LoomControlChannel` subscribes to `PipelineEventBus.subscribeTracking()` and forwards events over a WebSocket client to `/api/v1/processors/ws`. If the Vert.x EventBus is adopted, evaluate having the cortex processor publish directly to the EventBus (requires clustered EventBus or an EventBus client). This would eliminate the `ProcessorMessage(PIPELINE_EVENT)` -> `ProcessorEndpoint.handlePipelineEvent()` -> `PipelineEventBroadcaster` chain entirely.
- [ ] **TODO-9**: The `DefaultPipelineEventBus` is an in-process pub/sub. Consider whether it should be retained for internal cortex coordination (full-fidelity `NodeCompletionEvent` events) even if tracking events are moved to the Vert.x EventBus. The `NodeCompletionEvent` carries `LoomMedia` + `NodeResult` which are heavy objects not suitable for EventBus transport.

### 7.4 MCP EventBus Integration

- [ ] **TODO-10**: The MCP tool dispatch already uses the Vert.x EventBus (`mcp.tool.<name>`). Evaluate whether MCP-related events (tool call lifecycle, progress notifications) should also be published on the EventBus for observability. Currently MCP tool calls are dispatched via `eventBus.request()` (request-reply) with no event stream.
- [ ] **TODO-11**: Consider whether MCP tool results should be forwardable to UI clients via the same event stream as pipeline events. This would require a new `PipelineEventMessage` type or a separate MCP event channel.

### 7.5 Testing

- [ ] **TODO-12**: Add integration tests for the full cortex -> loom event flow: `ReactivePipelineExecutor` -> `LoomControlChannel` -> `ProcessorEndpoint` -> `PipelineEventBroadcaster` -> UI WebSocket subscriber. Currently only piecewise tests exist.
- [ ] **TODO-13**: Add tests for `PipelineEventBroadcaster` backpressure behavior (event dropping when `writeQueueFull()`).
- [ ] **TODO-14**: Add tests for `LoomControlChannel` reconnection with exponential backoff.
- [ ] **TODO-15**: If Vert.x EventBus integration is implemented, add tests for EventBus-based event publishing/subscribing, including clustered scenarios.
- [ ] **TODO-16**: If SockJS bridge is adopted, add tests for bridge authentication, per-pipeline filtering via `PermittedOptions`, and `BridgeEvent` handler.

### 7.6 Documentation

- [ ] **TODO-17**: Document the event flow in the loom REST API docs (OpenAPI/Swagger) - the WebSocket endpoints `/api/v1/pipelines/events/ws` and `/api/v1/processors/ws` should be documented with their message schemas.
- [ ] **TODO-18**: Update the `loom/services/eventbus/README.md` (currently one line: "This service provides eventbus code to loom.") with actual content once the module is populated.

---

## 8. Key File Index

| Component | File Path |
|---|---|
| PipelineEventBus interface | `cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/event/PipelineEventBus.java` |
| DefaultPipelineEventBus impl | `cortex/pipeline-common/src/main/java/io/metaloom/cortex/pipeline/common/event/DefaultPipelineEventBus.java` |
| PipelineTrackingEvent | `cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/event/PipelineTrackingEvent.java` |
| NodeCompletionEvent | `cortex/pipeline-api/src/main/java/io/metaloom/cortex/pipeline/api/event/NodeCompletionEvent.java` |
| ReactivePipelineExecutor | `cortex/pipeline-core/src/main/java/io/metaloom/cortex/pipeline/core/executor/ReactivePipelineExecutor.java` |
| LoomControlChannel (bridge) | `cortex/core/src/main/java/io/metaloom/cortex/impl/loom/LoomControlChannel.java` |
| PipelineWorkOrderHandler | `cortex/core/src/main/java/io/metaloom/cortex/impl/loom/PipelineWorkOrderHandler.java` |
| PipelineEventEndpoint (UI WS) | `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEventEndpoint.java` |
| ProcessorEndpoint (processor WS) | `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ProcessorEndpoint.java` |
| PipelineEventBroadcaster | `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEventBroadcaster.java` |
| WebSocketAuthenticator | `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/WebSocketAuthenticator.java` |
| ProcessorRegistry | `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/ProcessorRegistry.java` |
| WorkOrderResultRegistry | `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/WorkOrderResultRegistry.java` |
| PipelineEndpointService (REST) | `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEndpointService.java` |
| MCPService (WebSocket + SSE) | `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPService.java` |
| MCPToolRegistry (EventBus dispatch) | `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/tool/MCPToolRegistry.java` |
| MCPConstants (EventBus addresses) | `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPConstants.java` |
| VertxModule (Dagger) | `loom/common/src/main/java/io/metaloom/loom/common/dagger/VertxModule.java` |
| CortexBindModule (pipeline bus wiring) | `cortex/core/src/main/java/io/metaloom/cortex/cli/dagger/CortexBindModule.java` |
| PipelineEventMessage (REST model) | `loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/event/PipelineEventMessage.java` |
| PipelineEventType (REST model) | `loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/event/PipelineEventType.java` |
| ProcessorMessage | `loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/processor/message/ProcessorMessage.java` |
| ProcessorMessageType | `loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/processor/message/ProcessorMessageType.java` |
| PipelineRunResponse | `loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/pipeline/PipelineRunResponse.java` |
| Eventbus service (empty module) | `loom/services/eventbus/` |
| Pipeline executor tests | `cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/core/PipelineExecutorTest.java` |
| Abstract pipeline node test | `cortex/pipeline-core/src/test/java/io/metaloom/cortex/pipeline/test/AbstractPipelineNodeTest.java` |
| Pipeline event endpoint tests | `loom/core/src/test/java/io/metaloom/core/endpoint/test/PipelineEventEndpointTest.java` |
| Processor endpoint tests | `loom/core/src/test/java/io/metaloom/core/endpoint/test/ProcessorEndpointTest.java` |
| UI pipeline events client | `loom-ui/src/api/pipelineEvents.ts` |
| UI pipeline area component | `loom-ui/src/Pipeline/PipelineArea.tsx` |

---

## 9. References

- [Vert.x Core - Event Bus](https://vertx.io/docs/vertx-core/java/#event_bus) - publish/subscribe, request-reply, message codecs, clustered event bus
- [Vert.x Web - SockJS Event Bus Bridge](https://vertx.io/docs/vertx-web/java/#_handling_event_bus_bridge_events) - bridging EventBus to browser clients, `PermittedOptions`, `BridgeEvent` handling
- [Vert.x Core - WebSockets](https://vertx.io/docs/vertx-core/java/#_websockets) - `ServerWebSocket`, `WebSocketClient`, `writeTextMessage`, `writeQueueFull`
