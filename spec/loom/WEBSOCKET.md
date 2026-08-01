# MetaLoom // Loom WebSocket API Specification

> This document describes the WebSocket endpoints exposed by the Loom REST
> server. It is intended to be consumed by AI agents and developers who need
> to implement processor nodes, UI clients, or tooling that interacts with
> the real-time WebSocket channels.
>
> The progress checklist at the end tracks areas that still need improvement.

---

## 1. Overview

The Loom server exposes two WebSocket endpoints:

| Endpoint              | Path                          | Client population        | Direction             | Purpose                                  |
|-----------------------|-------------------------------|--------------------------|-----------------------|------------------------------------------|
| Processor WebSocket   | `/api/v1/processors/ws`       | Cortex **worker nodes** (JVM processes) | Bidirectional         | Workers register, receive source/node tasks, and report results/events |
| UI Events WebSocket   | `/api/v1/pipelines/events/ws` | **Browsers** (UI clients) | Server -> Client (read-only) | UI clients receive live pipeline **and** processor events (multiplexed — see §4) |

Both endpoints use the standard HTTP WebSocket upgrade. Authentication is
performed **after** the upgrade completes because browsers cannot attach
custom `Authorization` headers to WebSocket handshakes. Instead, a JWT bearer
token is passed as a `?token=<jwt>` query parameter.

### 1.1 Why two endpoints, and why they are not merged

The two endpoints serve **different client populations with different roles**,
and are deliberately kept separate:

- **Processor WebSocket** is a *worker* control channel. The peers are Cortex
  processor nodes — long-lived backend JVM processes, not browsers. It is
  **bidirectional** and trusted to a high degree: after registering, a peer can
  receive source/node tasks and report results (see §3). Its auth context is
  "a processor node joining the fleet".
- **UI Events WebSocket** is a *read-only broadcast* channel for browsers. The
  peer never sends application messages; it only receives events. Its auth
  context is "a signed-in user watching the system". To avoid a browser opening
  more than one socket, this channel is **multiplexed**: it carries both
  pipeline events and processor lifecycle events over a single connection,
  discriminated by a `channel` field (see §4). A browser therefore holds exactly
  **one** WebSocket to the backend regardless of how many views are open.

Merging the two into one endpoint is intentionally avoided: it would mix the
worker control protocol with the browser broadcast protocol, blur two distinct
auth roles onto one route, and widen the trust surface — while saving no
connections, since each browser already uses a single socket. The
`WebSocketAuthenticator.authenticate(ws, name)` call is passed a per-endpoint
name (`"processor"` vs `"pipeline-events"`) precisely so the two roles stay
distinguishable in logs and future policy.

---

## 2. Authentication

### 2.1 Token Query Parameter

- Both WebSocket endpoints accept a `?token=<jwt>` query parameter on the
  handshake URL.
- The token is validated by `WebSocketAuthenticator` using
  `LoomAuthenticationHandler.authenticateToken(token)`, which uses the same
  underlying JWT provider as the REST auth handler.
- The token is URL-decoded before validation.

### 2.2 Strict vs. Lenient Mode

- **Lenient mode (default):** If no token is supplied, the connection is
  accepted with a warning log. This exists for backward compatibility during
  the migration to token-based auth.
- **Strict mode:** When `LOOM_WS_STRICT_AUTH=true` (env var) or
  `loom.ws.strictAuth` JVM property is set, every connection **must** include
  a valid token. Missing tokens result in immediate rejection.
- Strict mode is resolved in `WebSocketAuthenticator.resolveStrict()` which
  checks the JVM property first, then the environment variable.

### 2.3 Rejection and Close Codes

- Invalid or missing tokens (in strict mode) cause the WebSocket to be closed
  with custom close code **4401** and a reason string (`"missing token"` or
  `"invalid token"`).
- The close is performed via `ServerWebSocket.close((short) 4401, reason)`.

### 2.4 Authentication Flow

```
Client                              Server
  |                                   |
  | -- WS Upgrade GET /api/v1/.../ws?token=<jwt> -->
  |                                   |
  |                       Server upgrades TCP to WebSocket
  |                                   |
  |                       WebSocketAuthenticator.authenticate(ws)
  |                       validates JWT via authHandler.authenticateToken()
  |                                   |
  | <-- (success: connection accepted) |
  |   OR                              |
  | <-- (failure: close code 4401)    |
  |                                   |
```

---

## 3. Processor WebSocket (`/api/v1/processors/ws`)

### 3.1 Purpose

The processor WebSocket is the primary communication channel between Loom and
cortex processor nodes. Processor nodes connect, register their capabilities,
and then exchange messages with the server.

### 3.2 Registration

- **REST routes:** The `ProcessorEndpoint` also exposes REST routes for
  listing (`GET /api/v1/processors`) and loading
  (`GET /api/v1/processors/:uuid`) registered processors. These REST routes
  are secured via the standard auth handler.
- **WebSocket route:** The WebSocket upgrade route at
  `/api/v1/processors/ws` is **not** secured via the standard auth handler
  (because the WS upgrade happens before the handler chain can authenticate).
  Instead, authentication is done post-upgrade via `WebSocketAuthenticator`.

### 3.3 Message Envelope

All messages use the `ProcessorMessage` JSON envelope:

```json
{
  "type": "REGISTER",
  "body": { ... }
}
```

- `type` (required): One of the `ProcessorMessageType` enum values.
- `body` (optional): A JSON object whose structure depends on the `type`.

### 3.4 Message Types

#### Messages FROM processor TO loom

| Type             | Body Model                  | Description                                      |
|------------------|-----------------------------|--------------------------------------------------|
| `REGISTER`       | `ProcessorRegistration`     | Initial registration of a processor node         |
| `HEARTBEAT`      | (none)                      | Keepalive ping; server replies with `HEARTBEAT_ACK` |
| `STATUS_UPDATE`  | `SystemStatusInfo`          | System metrics (CPU, memory, GPU, I/O, disk)     |
| `STATE_CHANGE`   | `{ "state": "ONLINE" }`     | Processor state change notification              |
| `SOURCE_ITEMS`   | `SourceItemsMessage`        | A batch of media items discovered by a source node |
| `SOURCE_COMPLETE`| `SourceCompleteMessage`     | The source node finished enumerating             |
| `NODE_TASK_RESULT` | `NodeTaskResult`          | Outcome of a single `NODE_TASK`                   |
| `NODE_TASK_RESULT_BATCH` | `NodeTaskResultBatch` | Outcomes of several node tasks in one frame       |
| `SEGMENT_TASK_RESULT` | `SegmentTaskResult`    | Per-node outcomes of a `SEGMENT_TASK`            |
| `TASK_RETURNED`  | `TaskReturnedMessage`       | Hands a dispatched task back unexecuted, so it is placed elsewhere at once instead of after the lease lapses |
| `PIPELINE_RUN_COMPLETED` | `{ runUuid, counters }` | Run finished; closes the run via `PipelineRunTracker` |
| `PIPELINE_EVENT` | `PipelineEventMessage`      | **Accepted but dropped.** Loom owns the graph, so run events are emitted by `RunStatsAggregator`, not relayed from a worker (see §4.6b) |

#### Messages FROM loom TO processor

| Type             | Body Model                  | Description                                      |
|------------------|-----------------------------|--------------------------------------------------|
| `REGISTERED`     | `ProcessorResponse` (as body) | Registration acknowledgement with processor info |
| `HEARTBEAT_ACK`  | (none)                      | Heartbeat pong                                   |
| `SOURCE_TASK`    | `SourceTaskMessage`         | Run a source node and stream back what it finds  |
| `SOURCE_ITEMS_ACK` | (ack)                     | Acknowledges a `SOURCE_ITEMS` batch, releasing the next |
| `NODE_TASK`      | `NodeTask`                  | Apply one node to one media item                 |
| `SEGMENT_TASK`   | `SegmentTask`               | Apply an affinity group of nodes to one media item |
| `ERROR`          | `{ "message": "..." }`      | Error message                                    |

### 3.5 Connection Lifecycle

```
Processor                           Loom
   |                                  |
   | -- WS Upgrade with ?token= -->   |
   |                                  | -- authenticate(ws, "processor")
   | <-- (connection accepted) ------|
   |                                  |
   | -- REGISTER -->                  |
   |   { nodeId, name, capabilities } |
   |                                  | -- registry.register()
   | <-- REGISTERED --                |
   |   { uuid, name, state, ... }     |
   |                                  |
   | -- HEARTBEAT -->                 |
   | <-- HEARTBEAT_ACK --             |
   |                                  |
   | -- STATUS_UPDATE -->             |
   |   { cpuLoad, memoryUsed, ... }   |
   |                                  |
   | <-- SOURCE_TASK --               |
   |   { runUuid, nodeId, nodeKind }  |
   |                                  |
   | -- SOURCE_ITEMS -->              |
   |   { runUuid, items[] }           |
   | <-- SOURCE_ITEMS_ACK --          |
   | -- SOURCE_COMPLETE -->           |
   |                                  |
   | <-- NODE_TASK --                 |
   |   { runUuid, nodeId, mediaRef }  |
   |                                  |
   | -- NODE_TASK_RESULT -->          |
   |   { runUuid, nodeId, state, ... }|
   |                                  |
   | -- STATE_CHANGE -->              |
   |   { state: "PAUSED" }            |
   |                                  |
   |   ... shutdown (see 3.8.1) ...   |
   | -- STATE_CHANGE -->              |
   |   { state: "TERMINATING" }       | -- placement stops offering work
   | -- TASK_RETURNED -->             |
   |   { runUuid, itemId, nodeIds[] } | -- engine re-places each node now
   |                                  |
   | <-- (close) --                   |
   |                                  | -- registry.updateState(OFFLINE)
   |                                  | -- registry.unregister()
```

### 3.6 REGISTER Message

The `REGISTER` message must include a `ProcessorRegistration` body:

| Field          | Type                      | Required | Description                              |
|----------------|---------------------------|----------|------------------------------------------|
| `nodeId`       | String                    | Yes      | Unique identifier of the processor node  |
| `name`         | String                    | Yes      | Human-readable name                      |
| `priority`     | Integer                   | No       | Priority (higher = preferred for dispatch) |
| `host`         | String                    | No       | Host address (e.g. `10.0.1.10:9090`)     |
| `capabilities` | Set\<ProcessorCapability\> | No      | Capabilities: `IO`, `CPU`, `GPU`         |

If `nodeId` is null or blank, the server sends an `ERROR` message and does
not register the processor.

### 3.7 Processor Capabilities

```java
public enum ProcessorCapability {
    IO,   // I/O intensive operations (filesystem scans, network transfers)
    CPU,  // CPU intensive operations (fingerprinting, hashing)
    GPU   // GPU accelerated operations (face detection, embedding generation)
}
```

### 3.8 Processor States

```java
public enum ProcessorState {
    STARTING,
    ONLINE,
    OFFLINE,
    PAUSED,
    TERMINATING
}
```

#### 3.8.1 Graceful shutdown (drain)

A worker that simply exits leaves every task it held `RUNNING` until the lease
lapses. The lease is deliberately generous — its other job is to avoid duplicating
the work of a merely *slow* worker — so a scale-down or a rolling deploy costs the
affected runs a full lease interval per outstanding task before any of that work is
placed again. A drain closes that gap:

1. **Announce.** The worker sends `STATE_CHANGE` with `state: "TERMINATING"`.
   `ProcessorRegistry#isPlaceable` admits `ONLINE` and nothing else, so from this
   point Loom offers it no further work.
2. **Refuse.** A dispatch already on the wire when the announcement went out still
   arrives. The worker answers it with `TASK_RETURNED` instead of starting it —
   without this the announcement would still leave work abandoned seconds later.
3. **Finish.** Tasks already running are given `--drain-timeout-ms` to complete
   normally (default 30 000, matching Kubernetes' termination grace period).
4. **Return.** Whatever is still running at the deadline is named in a
   `TASK_RETURNED`. The task keeps running — a node cannot be interrupted — but Loom
   is free to place it elsewhere immediately.

The connection stays open throughout; it is closed only after the last frame has
been written, since a queued frame is lost when the socket closes.

`TASK_RETURNED` body (`TaskReturnedMessage`):

| Field        | Type          | Required | Description                                              |
|--------------|---------------|----------|----------------------------------------------------------|
| `runUuid`    | UUID          | Yes      | The run the task belongs to                              |
| `itemId`     | String        | Yes      | The media item it was dispatched for                     |
| `taskUuid`   | UUID          | No       | The dispatch, for correlation with the task row          |
| `nodeIds`    | List\<String\> | Yes     | One id for a node task; every member for a segment task  |
| `elementSeq` | Integer       | No       | Element of a fanned-out sequence; 0 for a once-per-item node |
| `reason`     | String        | No       | Recorded on the re-dispatch                              |

**A return is not a failure.** Nothing ran, so `PipelineRunEngine#onNodeTaskReturned`
releases the in-flight slot and refunds the attempt the dispatch consumed. This
matters because nodes are *not* retryable by default: charging a return would make a
routine deployment dead-letter every item that happened to be in flight. The refund
is capped at three per execution — returning costs the worker nothing, so an
unbounded refund would let a misbehaving worker circulate an item around the fleet
forever without ever accumulating an attempt. Past the cap a return is accounted
exactly like a lapsed lease.

A late result for a returned task is still assimilated: the node settles and the
re-dispatched copy is recognised as a duplicate, which is the trade the lease model
already makes.

**Gap.** A `SOURCE_TASK` has no reclaim path. A draining worker refuses a source task
that arrives after the announcement, but one already enumerating is abandoned when
the deadline passes — the run then waits for a `SOURCE_COMPLETE` that never comes.
Fabricating one would mark a truncated scan as a whole one, which is worse; the run
has to be dispatched again. See task 9 in
[../cortex/METALOOM_ARCHITECTURE_TASK.md](../cortex/METALOOM_ARCHITECTURE_TASK.md).

### 3.9 System Status Info

The `STATUS_UPDATE` message body contains `SystemStatusInfo`:

| Field          | Type    | Description                          |
|----------------|---------|--------------------------------------|
| `cpuLoad`      | Double  | CPU load percentage (0-100), null when unknown |
| `memoryUsed`   | Long    | Used memory in bytes (JVM heap)      |
| `memoryTotal`  | Long    | Total memory in bytes (JVM heap)     |
| `gpuLoad`      | Double  | GPU load percentage (0-100) — never populated |
| `ioLoad`       | Double  | Busiest disk's utilisation percentage (0-100); null on the first update after connecting, and on non-Linux workers |
| `diskUsed`     | Long    | Used disk space in bytes             |
| `diskTotal`    | Long    | Total disk space in bytes            |

### 3.10 Source Task

To start a run the server dispatches one `SOURCE_TASK` (`SourceTaskMessage`) to a
selected processor. The worker runs the source node and streams what it finds
back as `SOURCE_ITEMS` batches (each acked with `SOURCE_ITEMS_ACK`), ending with
`SOURCE_COMPLETE`:

| Field      | Type                    | Description                              |
|------------|-------------------------|------------------------------------------|
| `runUuid`  | UUID                    | The `pipeline_run` this source task belongs to |
| `nodeId`   | String                  | Id of the source node in the graph       |
| `nodeKind` | String                  | Descriptor kind of the source node       |
| `options`  | Map\<String, Object\>   | Source selection (`pathGlobs`, `path`, `assetUuid`, …) |

### 3.11 Node Task and Node Task Result

For each discovered item the `PipelineRunEngine` dispatches individual
`NODE_TASK` (`NodeTask`) messages via `WebSocketNodeDispatcher`. The worker runs
the node and replies with `NODE_TASK_RESULT` (`NodeTaskResult`), batched as
`NODE_TASK_RESULT_BATCH` when several settle together. Affinity groups go out as
`SEGMENT_TASK` and return as `SEGMENT_TASK_RESULT` (one entry per node).

`NodeTask`:

| Field             | Type                    | Description                              |
|-------------------|-------------------------|------------------------------------------|
| `taskUuid`        | UUID                    | Unique identifier for this node task     |
| `runUuid`         | UUID                    | The `pipeline_run` this task belongs to  |
| `itemId`          | String                  | Media item this task applies to          |
| `nodeId`          | String                  | Graph node to apply                      |
| `nodeKind`        | String                  | Descriptor kind of the node              |
| `media`           | `MediaRef`              | `{ path, sha512, size }` of the item     |
| `options`         | Map\<String, Object\>   | Node options from the definition         |
| `upstreamOutputs` | Map\<String, Map\>      | Outputs of upstream nodes this one depends on |

`NodeTaskResult`:

| Field        | Type                  | Description                              |
|--------------|-----------------------|------------------------------------------|
| `taskUuid`   | UUID                  | UUID of the node task this result answers |
| `nodeId`     | String                | Graph node that produced the result      |
| `state`      | `NodeState`           | `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, or `SKIPPED` |
| `durationMs` | long                  | Node execution time                      |
| `message`    | String                | Optional status / error message          |
| `outputs`    | Map\<String, Object\> | Node outputs (synced to the asset when `syncToLoom`) |

### 3.12 Run Engine and Completion

- The `PipelineRunEngine` (loom-side, `loom/pipeline`) owns the DAG for a run,
  decides what runs next, and persists run state through a `RunStateStore` so a
  run survives the process that started it.
- When the DAG drains, the engine closes the run via `PipelineRunTracker`. The
  worker also sends `PIPELINE_RUN_COMPLETED`, which
  `ProcessorEndpoint.handlePipelineRunCompleted` routes to the same tracker.
- `PipelineRunTracker` derives the final status via `PipelineRunStatusResolver`
  and writes `status`, `finished`, `duration_ms`, and the counters. The first
  terminal verdict wins.

### 3.13 Processor Selection for a Run

- `ProcessorRegistry.selectProcessorForKinds(capability, kinds)` selects the
  highest-priority `ONLINE` processor whose node-kind restriction accepts the
  pipeline's source-node kind.
- Selection filters by `ProcessorState.ONLINE`, the required capability
  (currently hardcoded to `CPU`), and the source kind. Only `ONLINE` qualifies —
  a worker that has announced `TERMINATING` is draining and is never given more
  work, likewise `PAUSED` and `STARTING`. That announcement is the first step of a
  drain — see [3.8.1](#381-graceful-shutdown-drain).
- Ordering is **priority first, then live load**: declared priority is an
  operator's explicit placement decision and is never overruled, but among
  workers of equal priority the least loaded one wins. Load is
  `max(cpuLoad, ioLoad)` from the last `STATUS_UPDATE` — the busiest resource,
  since a worker pinned on either cannot take more. A status older than 60
  seconds, or one carrying neither figure, scores a neutral 50 so that silence
  neither attracts nor repels work. Equal priority and equal load tie-break on
  `nodeId`, so repeated dispatches of the same work are deterministic rather
  than dependent on map iteration order.
- If no processor is available, the pipeline run endpoint returns 503 and no
  `pipeline_run` row is created. There is no ack watchdog.

### 3.14 Error Handling

- Invalid JSON or missing `type` field results in an `ERROR` message being
  sent back to the processor.
- Messages received before `REGISTER` (except `REGISTER` itself) result in an
  `ERROR` message: "Not registered. Send REGISTER first."
- The `ERROR` message body is `{ "message": "..." }`.

### 3.15 REST Monitoring Routes

The `ProcessorEndpoint` also provides REST routes for monitoring:

| Method | Path                      | Description                              | Secured |
|--------|---------------------------|------------------------------------------|---------|
| GET    | `/api/v1/processors`      | List all registered processor nodes      | Yes     |
| GET    | `/api/v1/processors/:uuid`| Load a single processor by UUID          | Yes     |

The list response uses `ProcessorListResponse` (extends `AbstractListResponse`)
containing `ProcessorResponse` items with: `uuid`, `name`, `host`, `priority`,
`state`, `capabilities`, `systemStatus`, `lastSeen`.

---

## 4. Pipeline Events WebSocket (`/api/v1/pipelines/events/ws`)

### 4.1 Purpose

The pipeline events WebSocket is the **single UI-facing event socket**. It is
**read-only** from the client side - clients do not send messages. To avoid
opening additional sockets from a browser, it is **multiplexed** and carries two
kinds of frame, discriminated by an optional top-level `channel` field:

| `channel` value | Frame model              | Source                                            |
|-----------------|--------------------------|---------------------------------------------------|
| *(absent)*      | `PipelineEventMessage`   | Processor nodes → `ProcessorEndpoint` → `PipelineEventBroadcaster.broadcast()` |
| `"PROCESSOR"`   | `ProcessorEventMessage`  | `ProcessorRegistry` state changes → `PipelineEventBroadcaster.broadcastProcessorEvent()` |

Pipeline frames keep their original wire format (no `channel` field, treated as
the default pipeline channel) so existing clients are unaffected. A client routes
each frame by inspecting `channel`. Processor frames are **not** pipeline-scoped:
the `?pipeline=` filter (§4.3) is bypassed for them, so a filtered subscriber
still receives fleet-wide processor updates.

### 4.2 Authentication

Same as the processor WebSocket: `?token=<jwt>` query parameter, validated
by `WebSocketAuthenticator`. See [Section 2](#2-authentication).

### 4.3 Filtering

Two optional query parameters narrow the stream. They are ANDed; a subscriber
with neither receives everything.

| Parameter | Matches against | Used by |
|-----------|-----------------|---------|
| `?pipeline=<name>` | `PipelineEventMessage.pipelineName` | the UI, which watches one pipeline |
| `?run=<uuid>` | `PipelineEventMessage.pipelineRunUuid` | the CLI (`metaloom run follow`), which watches one run |

- Both are extracted from the WebSocket handshake URL query string
  (`PipelineEventEndpoint.extractQueryParam`) and applied in
  `PipelineEventBroadcaster.Subscriber.matches`.
- The `run` filter exists because a pipeline-name filter still delivers every
  concurrent run of that pipeline. The CLI also filters client-side, so it works
  against a server that predates this parameter.
- ⚠️ The stream carries **no history** — it delivers only what happens after the
  socket opens. A client that wants a run's opening events must connect *before*
  it posts `/run`, which is what `metaloom pipeline run --follow` does.

### 4.4 Event Message

Events are JSON-encoded `PipelineEventMessage` objects:

| Field           | Type                | Description                              |
|-----------------|---------------------|------------------------------------------|
| `type`          | `PipelineEventType` | Event type (see below)                   |
| `pipelineName`  | String              | Name of the pipeline that emitted the event |
| `nodeId`        | String              | ID of the pipeline node (null for pipeline-level events) |
| `mediaPath`     | String              | Filesystem path of the media item        |
| `timestamp`     | Long                | Epoch millis when the event occurred     |
| `durationMs`    | Long                | Processing duration in ms (completion events only) |
| `message`       | String              | Human-readable detail (failures, skip reasons) |
| `activeCount`   | Integer             | Items currently being processed at this node |
| `pendingCount`  | Integer             | Items queued/waiting at this node        |
| `processedCount`| Long                | Total items processed by this node since pipeline start |
| `failedCount`   | Long                | Total items that failed in this node since pipeline start |

### 4.5 Event Types

```java
public enum PipelineEventType {
    // Pipeline lifecycle
    PIPELINE_STARTED,      // A pipeline has started processing a media batch
    PIPELINE_COMPLETED,    // A pipeline has finished processing all media items

    // Per-node / per-media events
    NODE_STARTED,          // A media item has entered a node and processing has begun
    NODE_COMPLETED,        // A node has successfully finished processing a media item
    NODE_FAILED,           // A node failed while processing a media item
    NODE_SKIPPED,          // A node was skipped (filter branch mismatch, dependency failure, dry-run)
    NODE_BUFFERED,         // A media item is buffered/queued at a node because concurrency limit is reached

    // Periodic aggregate stats
    NODE_STATS             // Periodic per-node throughput snapshot (active, pending, processed, failed counts)
}
```

### 4.5b Processor Event Message (`channel: "PROCESSOR"`)

Processor lifecycle frames are JSON-encoded `ProcessorEventMessage` objects
emitted by `ProcessorRegistry` whenever a processor registers, changes state,
reports metrics, heartbeats, or disconnects:

| Field       | Type                  | Description                                                     |
|-------------|-----------------------|-----------------------------------------------------------------|
| `channel`   | String                | Always `"PROCESSOR"` — the multiplexing discriminator            |
| `type`      | `ProcessorEventType`  | `REGISTERED`, `STATE_CHANGED`, `STATUS_UPDATED`, `HEARTBEAT`, `DISCONNECTED` |
| `nodeId`    | String                | Node id of the processor (stable UI key)                        |
| `processor` | `ProcessorResponse`   | Full snapshot on `REGISTERED` / `STATE_CHANGED` / `STATUS_UPDATED`; null otherwise |
| `lastSeen`  | Instant               | Carried on `HEARTBEAT` (lightweight; no full snapshot)          |

```java
public enum ProcessorEventType {
    REGISTERED,      // Processor registered / re-registered (full snapshot)
    STATE_CHANGED,   // State transition incl. ONLINE→OFFLINE on disconnect (full snapshot)
    STATUS_UPDATED,  // Fresh system metrics: CPU/GPU/IO/memory (full snapshot)
    HEARTBEAT,       // Keepalive; carries only nodeId + lastSeen
    DISCONNECTED     // Processor unregistered; carries only nodeId
}
```

A processor disconnect naturally produces `STATE_CHANGED`→`OFFLINE` (the close
handler calls `updateState(nodeId, OFFLINE)`) followed by `DISCONNECTED`
(`unregister`), letting the UI show the card as "offline (persisted)" rather than
dropping it.

### 4.6 Backpressure

- Each subscriber has a bounded queue (default 1024 entries).
- When the queue is full, the oldest event is dropped and a per-subscriber
  `droppedCount` is incremented.
- This prevents a slow subscriber from blocking the broadcaster thread or
  accumulating unbounded memory.
- A log message is emitted every 100 dropped events.

### 4.6b Who may emit a pipeline event

Loom is the only producer. Under Variant C a worker holds no pipeline graph — it
answers `NODE_TASK`, `SEGMENT_TASK` and `SOURCE_TASK` — so it has nothing to say at
pipeline-event granularity, and `RunStatsAggregator` is the single source of a run's
events:

- **Successes and skips** are counted per node and pushed as `NODE_STATS` on a timer.
  A 100 000 item run over a 10 node graph settles a million nodes; one frame per settle
  would be millions of renders to move a bar by a percent.
- **Failures** go out immediately as `NODE_FAILED`. They are rare, individually
  actionable, and summing them into a number would leave the UI able to say
  "300 failed" without naming a single file.

`ProcessorEndpoint` therefore **drops** a `PIPELINE_EVENT` arriving from a processor
rather than broadcasting it — relaying one would put exactly the per-item flood the
aggregator exists to prevent onto the UI socket. The envelope checks still apply (an
unregistered sender or a bodyless frame is answered with `ERROR`), and the first drop
per processor is logged so an outdated worker is visible.

Cortex no longer sends them: the tracking-bus subscription in `LoomControlChannel` was
removed, and nothing outside the node-chain tests publishes to that bus. The message
type is retained so an older worker's frames are recognised and discarded rather than
answered with "Unexpected message type" once per item.

`PIPELINE_RUN_COMPLETED` was derived from the same tracking bus and went with it, so
Cortex no longer sends that either. Nothing is lost: a run is closed out by the engine's
`onCompletion` callback (`PipelineEndpointService`), which is the path that has actually
been running. Loom still handles the message so an older worker can close a run.

### 4.7 Route Ordering

The WebSocket upgrade route uses `order(-1000)` to ensure it is matched
before wildcard auth routes such as `/api/v1/pipelines*`:

```java
apiRouter().getDelegate().get(basePath() + "/ws").order(-1000).handler(...)
```

### 4.8 Connection Lifecycle

```
UI Client                            Loom
   |                                    |
   | -- WS Upgrade with ?token=...&pipeline=... -->
   |                                    | -- authenticate(ws, "pipeline-events")
   | <-- (connection accepted) --------|
   |                                    | -- broadcaster.addSubscriber(ws, pipelineFilter)
   |                                    |
   | <-- PipelineEventMessage (NODE_STARTED) -- (from processor via broadcaster)
   | <-- PipelineEventMessage (NODE_COMPLETED) --
   | <-- PipelineEventMessage (NODE_STATS) --
   | <-- PipelineEventMessage (PIPELINE_COMPLETED) --
   |                                    |
   | -- (close) --                      | -- broadcaster.removeSubscriber(ws)
```

---

## 5. REST Integration

### 5.1 Pipeline Run Dispatch

The `POST /api/v1/pipelines/:uuid/run` REST endpoint triggers pipeline
execution by selecting a processor with
`ProcessorRegistry.selectProcessorForKinds(CPU, [sourceKind])`, creating a
`pipeline_run` row (status `RUNNING`), building a `PipelineRunEngine`, and
handing the pipeline's **source node** to the worker as a `SOURCE_TASK`. The
engine then drives the run by dispatching individual `NODE_TASK`s via
`WebSocketNodeDispatcher` (see §3.10–3.12).

The `SourceTaskMessage` is sent as a `SOURCE_TASK` message over the processor's
WebSocket connection:

```json
{
  "type": "SOURCE_TASK",
  "body": {
    "runUuid": "uuid",
    "nodeId": "filesystem-source",
    "nodeKind": "filesystem-source",
    "options": { "pathGlobs": ["/data/**"] }
  }
}
```

### 5.2 Pipeline Event Flow

Events originate inside Loom, not on the worker — the worker reports task outcomes and
the engine turns those into run events (see §4.6b).

```
Processor Node                         Loom Server                           UI Client
   |                                       |                                      |
   | -- NODE_TASK_RESULT (WS) -->          |                                      |
   |   { runUuid, nodeId, state, ... }     |                                      |
   |                                       | -- engine.onNodeSettled(...)         |
   |                                       |    -> RunStatsAggregator             |
   |                                       |       counts it; NODE_FAILED now,    |
   |                                       |       NODE_STATS on the next tick    |
   |                                       | -- broadcaster.broadcast(event) -->  |
   |                                       |                                      | -- (receives event via WS)
   |                                       |                                      |
   | -- PIPELINE_EVENT (WS) -->            |                                      |
   |   (legacy worker only)                | -- dropped; see §4.6b                |
```

---

## 6. Progress Assessment

The following checkboxes track aspects of the WebSocket API that need
improvement, fixes, or are incomplete. AI agents can use this list to
identify work items.

### 6.1 Core Functionality

- [x] Processor WebSocket with bidirectional message protocol
- [x] Pipeline events WebSocket with read-only event streaming
- [x] Processor registration with capabilities and priority
- [x] Heartbeat/keepalive mechanism
- [x] System status reporting (CPU, memory, GPU, I/O, disk)
- [x] State change notifications
- [x] `SOURCE_TASK` dispatch and `SOURCE_ITEMS`/`SOURCE_COMPLETE` streaming
- [x] `NODE_TASK` dispatch from server to processor and `NODE_TASK_RESULT` reporting back
- [x] Run completion tracking via `PipelineRunEngine` + `PipelineRunTracker`
- [x] Pipeline event broadcasting with per-pipeline filtering
- [x] Backpressure handling with bounded per-subscriber queue
- [x] REST routes for listing and loading registered processors
- [x] Processor selection by capability and priority
- [x] Graceful shutdown: `TERMINATING` announcement, `TASK_RETURNED` hand-back, immediate re-placement
- [ ] A source task in progress is abandoned by a drain; the run waits for a `SOURCE_COMPLETE` that never arrives

### 6.2 Authentication

- [x] JWT token authentication via `?token=` query parameter
- [x] Shared `WebSocketAuthenticator` for both WebSocket endpoints
- [x] Strict mode configuration via `LOOM_WS_STRICT_AUTH` env var or `loom.ws.strictAuth` JVM property
- [x] Custom close code 4401 for unauthorized connections
- [ ] Strict mode is opt-in (default is lenient - accepts missing token)
- [ ] No rate limiting on WebSocket connection attempts
- [ ] No token expiry check on long-lived WebSocket connections (token may expire while WS is open)
- [ ] No re-authentication or token refresh mechanism for established WebSocket connections

### 6.3 Protocol and Message Handling

- [x] `ProcessorMessage` envelope with `type` and `body` fields
- [x] All message types defined in `ProcessorMessageType` enum
- [x] Error messages sent back to processor on invalid input
- [x] Pre-registration guard (messages before REGISTER are rejected)
- [ ] No heartbeat timeout / idle detection on the server side (processor could stop sending heartbeats and stay registered)
- [ ] No maximum message size limit on WebSocket text messages
- [ ] No binary message support (text only)
- [ ] No message versioning or protocol version negotiation
- [ ] `ERROR` message type is sent from server to processor but not documented in the message type enum comment
- [ ] No reconnection/re-registration protocol for processors after server restart

### 6.4 REST Monitoring

- [x] `GET /api/v1/processors` - list registered processors
- [x] `GET /api/v1/processors/:uuid` - load a single processor
- [ ] Processor list endpoint does not support pagination (returns all processors)
- [ ] Processor list endpoint does not support filtering by state, capability, or online status
- [ ] No REST endpoint to force-disconnect a processor
- [ ] No REST endpoint to send a `STATE_CHANGE` or `NODE_TASK` to a specific processor
- [ ] No REST endpoint for pipeline event subscriber count or status

### 6.5 Error Handling and Resilience

- [x] WebSocket exception handler logs errors and removes subscribers
- [x] Close handler unregisters processors and sets state to OFFLINE
- [x] Broadcaster removes closed WebSocket connections lazily during broadcast
- [ ] No automatic reconnection logic on the server side (processors must reconnect manually)
- [ ] No dead-letter mechanism for dropped pipeline events (events are silently dropped when queue is full)
- [ ] No error reporting to UI clients when a processor disconnects mid-pipeline
- [ ] `ProcessorRegistry.send` returns `false` if the processor WebSocket is closed; the run endpoint fails the run immediately rather than retrying or queueing the task

### 6.6 Documentation and OpenAPI

- [x] `ProcessorEndpoint` has Javadoc describing the WebSocket protocol
- [x] `PipelineEventEndpoint` has Javadoc describing the event stream and filtering
- [x] `WebSocketAuthenticator` has Javadoc describing strict vs. lenient mode
- [x] `PipelineEventBroadcaster` has Javadoc describing backpressure and filtering
- [x] `ProcessorMessage` has Javadoc describing the envelope format
- [x] `ProcessorMessageType` has Javadoc describing each message type
- [ ] WebSocket endpoints are not documented in the OpenAPI spec
- [ ] No OpenAPI schema definitions for WebSocket message models
- [ ] No client-side WebSocket documentation (how to connect, message examples)
- [ ] No sequence diagrams or protocol flow diagrams in the codebase

### 6.7 Testing

- [ ] No WebSocket endpoint integration tests
- [ ] No processor registration/heartbeat/source-task/node-task flow tests
- [ ] No pipeline event broadcasting tests
- [ ] No WebSocket authentication tests (valid token, invalid token, missing token, strict mode)
- [ ] No backpressure / queue overflow tests
- [ ] No reconnection scenario tests

### 6.8 Client Support

- [ ] HTTP client (`LoomHttpClient`) does not have methods for processor WebSocket
- [ ] HTTP client does not have methods for pipeline events WebSocket
- [ ] No dedicated WebSocket client class for processor or pipeline events
- [ ] No client-side message serialization/deserialization helpers for `ProcessorMessage`
- [ ] No client-side helper for `PipelineEventMessage` parsing
- [ ] gRPC client does not support WebSocket-style communication

### 6.9 Security

- [x] Token-based authentication on WebSocket upgrade
- [x] Tokens are not logged
- [ ] No origin validation on WebSocket upgrade (any origin can connect)
- [ ] No message-level authentication or signing (after upgrade, any message is trusted)
- [ ] No rate limiting on message frequency (processor can flood the server with messages)
- [ ] No validation of `nodeId` uniqueness (two processors could register with the same nodeId)
- [ ] No validation of `ProcessorRegistration` fields beyond null/blank checks on `nodeId`
