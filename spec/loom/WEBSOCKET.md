# MetaLoom // Loom WebSocket API Specification

> The WebSocket channels exposed by the Loom server: the **processor control
> channel** that carries work to Cortex workers, and the **UI events socket**
> that streams pipeline and processor lifecycle frames to browsers and the CLI.
>
> Scope boundaries: the run engine's own semantics live in
> [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md), the
> worker-side execution model in
> [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md), the
> in-JVM event bus in [EVENTBUS.md](EVENTBUS.md), and the MCP socket in
> [MCP.md](MCP.md). This file specifies only the wire protocol.
>
> **The agent chat stream is Server-Sent Events, not a WebSocket**
> (`SseAgentEventSink`, `text/event-stream`). Do not document it here.

---

## 1. Overview

| Endpoint | Path | Peers | Direction | Spec |
|---|---|---|---|---|
| Processor control channel | `/api/v1/processors/ws` | Cortex worker JVMs | Bidirectional | this file, §3 |
| UI events socket | `/api/v1/pipelines/events/ws` | Browsers, CLI | Server → client only | this file, §4 |
| MCP socket | `/mcp/ws` | MCP clients | Bidirectional (JSON-RPC) | [MCP.md](MCP.md) |

All three upgrade over plain HTTP and authenticate **after** the upgrade via a
`?token=<jwt>` query parameter (§2) — browsers cannot attach an
`Authorization` header to a handshake. All three share one
`WebSocketAuthenticator`; the MCP server obtains it through `MCPModule`.

### 1.1 Why the two Loom endpoints are not merged

- **Processor socket** is a *worker control channel*: long-lived backend JVMs,
  bidirectional, trusted enough to be handed work. Auth context: "a worker
  joining the fleet".
- **UI events socket** is a *read-only broadcast*: the client never sends an
  application frame. To keep a browser at exactly **one** socket regardless of
  how many views are open, it is **multiplexed** — pipeline frames and processor
  lifecycle frames share it, discriminated by a `channel` field (§4.1).

Merging them would blur two auth roles onto one route and widen the trust
surface while saving no connections. `authenticate(ws, name)` takes a
per-endpoint label (`"processor"` / `"pipeline-events"` / `"mcp"`) so the roles
stay distinguishable in logs and future policy.

---

## 2. Authentication

`WebSocketAuthenticator.authenticate(ws, endpoint)` parses `?token=` off
`ServerWebSocket.query()`, URL-decodes it, and validates it with
`LoomAuthenticationHandler.authenticateToken(token)` — the same JWT provider the
REST auth handler uses.

| Situation | Lenient (default) | Strict |
|---|---|---|
| No token | accepted, `WARN` logged | closed `4401 "missing token"` |
| Invalid token | closed `4401 "invalid token"` | closed `4401 "invalid token"` |
| Valid token | accepted, `User` resolved | accepted, `User` resolved |

- Strict is resolved once in the constructor by `resolveStrict()`: JVM property
  `loom.ws.strictAuth` first, then env `LOOM_WS_STRICT_AUTH`, else `false`.
- Every rejection calls `metrics.recordAuthFailure("ws")`.
- `4401` is `WebSocketAuthenticator.UNAUTHORIZED_CLOSE_CODE`. `4409` is a
  separate, endpoint-level code meaning duplicate `nodeId` (§3.3).
- ⚠️ The token is checked **once, at handshake**. A socket that stays open past
  its token's expiry is never re-checked (§7 gaps).

### 2.1 Environment / property reference

| Setting | Default | Side | Effect |
|---|---|---|---|
| `LOOM_WS_STRICT_AUTH` / `-Dloom.ws.strictAuth` | `false` | Loom | Require a token on every WS handshake |
| `LOOM_TOKEN` / `--loom-token` | none | Cortex | JWT the worker appends as `?token=` |
| `LOOM_HOST` | none | Cortex | Loom host; unset ⇒ control channel disabled |
| `LOOM_PORT` / `--port` | none | Cortex | Loom port; `<= 0` ⇒ control channel disabled |
| `CORTEX_NODE_ID` | none | Cortex | Registration identity; missing ⇒ `CortexMain` exits 2, blank ⇒ `start()` throws |
| `CORTEX_DRAIN_TIMEOUT_MS` | `30000` | Cortex | Grace period before outstanding tasks are handed back |

---

## 3. Processor control channel (`/api/v1/processors/ws`)

### 3.1 Envelope

Every frame both ways is a `ProcessorMessage`:

```json
{ "type": "REGISTER", "body": { } }
```

`type` is a `ProcessorMessageType`; `body` is a `JsonObject` whose shape depends
on the type, and is absent for `HEARTBEAT` / `HEARTBEAT_ACK`.

### 3.2 Message vocabulary

**Worker → Loom**

| Type | Body model | Handler | Notes |
|---|---|---|---|
| `REGISTER` | `ProcessorRegistration` | `handleRegister` | Must carry a non-blank `nodeId` |
| `HEARTBEAT` | — | `handleHeartbeat` | Answered with `HEARTBEAT_ACK` |
| `STATUS_UPDATE` | `SystemStatusInfo` | `handleStatusUpdate` | Feeds placement's load score (§3.7) |
| `STATE_CHANGE` | `{ "state": "TERMINATING" }` | `handleStateChange` | Value is a `ProcessorState` name |
| `SOURCE_ITEMS` | `SourceItemsMessage` | `handleSourceItems` | Acked; the ack is the run's only source-side backpressure |
| `SOURCE_COMPLETE` | `SourceCompleteMessage` | `handleSourceComplete` | A run cannot settle without it |
| `NODE_TASK_RESULT` | `NodeTaskResultMessage` | `handleNodeTaskResult` | Wraps `{ runUuid, itemId, result }` |
| `NODE_TASK_RESULT_BATCH` | `NodeTaskResultBatch` | `handleNodeTaskResultBatch` | Transport saving only; entries assimilated individually |
| `SEGMENT_TASK_RESULT` | `SegmentTaskResult` | `handleSegmentTaskResult` | One result per node, never one segment verdict |
| `TASK_RETURNED` | `TaskReturnedMessage` | `handleTaskReturned` | Immediate re-placement (§3.6) |
| `PIPELINE_RUN_COMPLETED` | free-form JSON | `handlePipelineRunCompleted` | **Cortex no longer sends this.** Retained so an older worker can still close a run |
| `PIPELINE_EVENT` | `PipelineEventMessage` | `handlePipelineEvent` | **Accepted and dropped** — Loom owns run events (§4.5) |

**Loom → worker**

| Type | Body model | Sent by |
|---|---|---|
| `REGISTERED` | `ProcessorResponse` | `ProcessorEndpoint.handleRegister` |
| `HEARTBEAT_ACK` | — | `ProcessorEndpoint.handleHeartbeat` |
| `SOURCE_TASK` | `SourceTaskMessage` | `PipelineEndpointService` at run start |
| `SOURCE_ITEMS_ACK` | `SourceItemsAckMessage` | `handleSourceItems`, gated on `engine.whenCapacityAvailable` |
| `NODE_TASK` | `NodeTask` | `WebSocketNodeDispatcher.dispatch(NodeTask)` |
| `SEGMENT_TASK` | `SegmentTask` | `WebSocketNodeDispatcher.dispatch(SegmentTask)` |
| `ERROR` | `{ "message": "…" }` | `ProcessorEndpoint.sendError` |

`ProcessorMessageType` groups `SEGMENT_TASK_RESULT` and
`NODE_TASK_RESULT_BATCH` under its "FROM loom TO processor" comment banner. That
banner is wrong — both travel worker → Loom. The table above is authoritative.

### 3.3 Registration

`ProcessorRegistration`:

| Field | Type | Required | Notes |
|---|---|---|---|
| `nodeId` | String | Yes | Unique **and stable across restarts**; leases and attribution key on it |
| `name` | String | No | Cortex sends `"cortex"` |
| `priority` | int | No | Higher wins; Cortex sends `100` |
| `host` | String | No | `<hostname>:<monitoringPort>` |
| `capabilities` | Set\<`ProcessorCapability`\> | No | `IO`, `CPU`, `GPU`; Cortex announces `CPU` + `IO` |
| `nodeWhitelist` | Set\<String\> | No | Node kinds this worker will run; **null/empty means "anything"** |
| `nodeBlacklist` | Set\<String\> | No | Refused kinds, applied even when the whitelist would admit them |

- The whitelist defaults to `NodeFactory.registeredTypes()` so a worker cannot
  advertise work it has no implementation for.
- A blank `nodeId` is answered with `ERROR` and no registration.
- A `nodeId` already held by a **live** socket is answered with `ERROR` and
  `close(4409, "Duplicate nodeId")`. A stale entry whose socket has already
  closed is *not* live, so an ordinary reconnect takes over cleanly
  (`ProcessorRegistry.isLive`).
- Restrictions can afterwards be overridden by an operator via
  `PUT /api/v1/processors/:uuid/restrictions` (§3.8), which takes effect on the
  next placement decision without a reconnect.

### 3.4 Lifecycle

```mermaid
sequenceDiagram
    autonumber
    participant W as Cortex worker<br/>(LoomControlChannel)
    participant P as ProcessorEndpoint
    participant R as ProcessorRegistry
    participant E as PipelineRunEngine
    participant B as PipelineEventBroadcaster
    participant U as UI / CLI

    W->>P: GET /api/v1/processors/ws?token=<jwt>
    P->>P: WebSocketAuthenticator.authenticate(ws, "processor")
    Note over P: failure ⇒ close(4401)

    W->>P: REGISTER {nodeId, capabilities, nodeWhitelist}
    P->>R: register(nodeId, reg, ws)
    R->>B: ProcessorEvent REGISTERED
    B-->>U: {channel:"PROCESSOR", type:"REGISTERED"}
    P-->>W: REGISTERED {ProcessorResponse}

    loop every 10s / 20s
        W->>P: HEARTBEAT
        P-->>W: HEARTBEAT_ACK
        W->>P: STATUS_UPDATE {cpuLoad, ioLoad, mem, disk}
        P->>R: updateStatus → ProcessorEvent STATUS_UPDATED
    end

    Note over E,W: run start — one source task
    E-->>W: SOURCE_TASK {runUuid, nodeId, nodeKind, options, batchSize}
    loop until enumeration ends
        W->>P: SOURCE_ITEMS {runUuid, seq, items[]}
        P->>E: onItemDiscovered(...) per item
        E-->>W: SOURCE_ITEMS_ACK {runUuid, seq}
    end
    W->>P: SOURCE_COMPLETE {runUuid, totalCount, error?}

    loop per item, per node
        E-->>W: NODE_TASK / SEGMENT_TASK
        W->>P: NODE_TASK_RESULT(_BATCH) / SEGMENT_TASK_RESULT
        P->>E: onNodeTaskResult / onSegmentTaskResult
        E->>B: RunStatsAggregator → NODE_FAILED now, NODE_STATS on the 1s tick
        B-->>U: PipelineEventMessage
    end

    Note over W: shutdown (drain, §3.6)
    W->>P: STATE_CHANGE {state:"TERMINATING"}
    P->>R: updateState → no longer placeable
    W->>P: TASK_RETURNED {runUuid, itemId, nodeIds[]}
    P->>E: onNodeTaskReturned → re-placed immediately
    W->>P: (close)
    P->>R: disconnect(nodeId, ws) → OFFLINE, then unregister
```

### 3.5 Wire models

`SystemStatusInfo` (`STATUS_UPDATE`, every 20 s):

| Field | Type | Notes |
|---|---|---|
| `cpuLoad` | Double | 0–100; null when unknown |
| `ioLoad` | Double | Busiest disk's utilisation, 0–100; null on the first sample and on non-Linux |
| `memoryUsed` / `memoryTotal` | Long | JVM heap: `total-free` and `max` |
| `diskUsed` / `diskTotal` | Long | `FileStore` of the worker's working directory |
| `gpuLoad` | Double | **Never populated** — `collectSystemStatus()` does not set it |

`SourceTaskMessage`: `runUuid`, `nodeId`, `nodeKind`,
`options` (`pathGlobs`, `path`, `assetUuid`, …), `batchSize` (default `250`).
`SourceItemsMessage`: `runUuid`, `seq`, `items[]` of `MediaRef`.
`SourceCompleteMessage`: `runUuid`, `totalCount`, `error`.

`MediaRef` is `{ path, sha512, size, mediaType }`. `path` is an **absolute path
or a URI** (`s3://…`) — a plain String precisely because `Paths.get` mangles a
URI. `mediaType` is best-effort (`image`/`video`/`audio`/`document`/`unknown`).

`NodeTask`:

| Field | Type | Notes |
|---|---|---|
| `taskUuid` | UUID | Required |
| `runUuid` | UUID | Null for an untracked execution |
| `itemId` | String | Also the origin id of every element the task produces |
| `nodeId` / `nodeKind` | String | Graph node and the kind the worker resolves |
| `elementSeq` | int | Which element of a fanned-out sequence; `0` when the node runs once per item |
| `media` | `MediaRef` | |
| `options` | Map\<String, Object\> | From the pipeline definition |
| `inputs` | Map\<String, `PortPayload`\> | **Keyed by the receiving node's own input port ids**, not by upstream node id |
| `demandedOutputs` | Set\<String\> | Output ports something is wired to; a hint, not a restriction |
| `resultBatchSize` | int | How many results the worker may accumulate before sending; `1` ⇒ send each |
| `capturePreviews` | boolean | Ask the worker to attach small renderings of what each output port carried. **False on every ordinary run** — set only for a run started with `debug`. Absent from an older Loom, which reads as false |

`PortPayload` is `{ contentType, cardinality, elements[] }` — the typed
replacement for the old untyped `Map<String,Object>`. For the port lattice and
fan-out rules see [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md)
and [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md).

`NodeTaskResult`: `taskUuid`, `nodeId`, `elementSeq`, `state` (`NodeState`:
`PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `SKIPPED`), `durationMs`,
`message`, `outputs` (Map\<String, `PortPayload`\> keyed by **output** port id).
Outputs are **kept on `SKIPPED` and `FAILED`** results — discarding them threw
away the diagnostics that explain the non-completion.

`previews` (Map\<String, `NodePreview`\>, keyed by **output** port id) rides alongside
`outputs` when the task asked for it. `NodePreview` is
`{mimeType, width, height, data}`, `{markdown}` or `{skippedReason}` — bytes as base64,
capped at 96 KiB with the longest edge at 512 px, and **dropped rather than truncated**
past the cap. A port can carry both bytes and Markdown: the image comes from
`NodePreviews`, the Markdown from the node's own `ctx.preview(port, …)`, and neither
displaces the other. It exists because an `artifact/image` port carries a worker-local *path*, which
Loom cannot resolve into anything anyone can look at. Generating one is never allowed
to fail the task that produced the real output.

`SegmentTask` carries `taskUuid`, `runUuid`, `itemId`, `segmentId`, `affinity`,
`media`, `nodes[]` (`SegmentNode`) and `inputs` — only what comes from *outside*
the segment; intra-segment dependencies never cross the network, which is the
whole saving. `SegmentTaskResult` answers with `results[]` (one per node, skips
reported explicitly) plus an optional segment-level `error`.

### 3.6 Graceful shutdown (drain)

A worker that simply exits leaves its tasks `RUNNING` until the lease lapses —
and the lease is deliberately generous, because its other job is to tolerate a
merely *slow* worker. A drain closes that gap (`LoomControlChannel.drain`):

1. **Announce** `STATE_CHANGE {state:"TERMINATING"}`. `isPlaceable()` admits
   `ONLINE` and nothing else, so no further work is offered from here.
2. **Refuse.** A dispatch already on the wire still arrives;
   `PipelineTaskHandler.beginDrain()` makes the worker answer it with
   `TASK_RETURNED` instead of starting it.
3. **Finish.** Running tasks get `CORTEX_DRAIN_TIMEOUT_MS` (default 30 000, matching
   Kubernetes' termination grace period).
4. **Return.** Whatever is still running at the deadline is named in a
   `TASK_RETURNED`. It keeps running locally — a node cannot be interrupted —
   but Loom is free to place it elsewhere at once.
5. **Flush.** `awaitFlush(5000)` writes one more frame and waits for it; frames
   on a WebSocket are ordered, so that waits for all of them. Only then does
   `stop()` close the socket — a queued frame is lost on close.

`TaskReturnedMessage`: `runUuid`, `itemId`, `taskUuid`, `nodeIds[]` (one for a
node task, every member for a segment), `elementSeq`, `reason`.

**A return is not a failure.** `PipelineRunEngine#onNodeTaskReturned` releases
the in-flight slot and refunds the attempt, because nodes are not retryable by
default and charging a return would dead-letter every in-flight item on a
routine deployment. The refund is capped at three per execution — returning
costs the worker nothing, so an unbounded refund would let a misbehaving worker
circulate an item around the fleet forever. Past the cap a return is accounted
exactly like a lapsed lease. A late result for a returned task is still
assimilated; the re-dispatched copy is recognised as a duplicate.

**Gap.** A `SOURCE_TASK` has no reclaim path — a source already enumerating is
abandoned at the deadline and the run waits for a `SOURCE_COMPLETE` that never
comes. Fabricating one would mark a truncated scan as whole. See task 9 in
[../cortex/METALOOM_ARCHITECTURE_TASK.md](../cortex/METALOOM_ARCHITECTURE_TASK.md).

### 3.7 Placement

`ProcessorRegistry.selectProcessorForKinds(capability, kinds)` filters, then
orders:

1. **Filter** — `isPlaceable` (state is `ONLINE`, and nothing else: `PAUSED`,
   `STARTING` and `TERMINATING` are all excluded), has the required capability
   (`PipelineEndpointService` currently asks for `CPU`), and `accepts()` **every**
   requested node kind.
2. **Order** — `priority` descending first: an operator's explicit placement
   decision is never overruled by live load. Then `loadScore` ascending. Then
   `nodeId`, so repeated dispatches of the same work land deterministically
   rather than by map iteration order.
3. `loadScore` = `max(cpuLoad, ioLoad)` from the last `STATUS_UPDATE` — the
   *busiest* resource, since a worker pinned on either cannot take more.
   A status older than `STATUS_MAX_AGE` (60 s), or one carrying neither figure,
   scores `UNKNOWN_LOAD` (50) so silence neither attracts nor repels work.
4. No processor for the required kinds ⇒ `POST /run` returns **503** and no
   `pipeline_run` row is created. There is no ack watchdog.

### 3.8 REST routes on the same endpoint

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `/api/v1/processors` | List live processors merged with persisted-but-offline instances | authenticated |
| GET | `/api/v1/processors/:uuid` | Load one, by nodeId or derived UUID, falling back to the persisted row | authenticated |
| PUT | `/api/v1/processors/:uuid/restrictions` | Set `nodeWhitelist` / `nodeBlacklist` | `MANAGE_CORTEX_INSTANCE` |
| DELETE | `/api/v1/processors/:uuid` | Forget a persisted instance; **409 while it is online** | `MANAGE_CORTEX_INSTANCE` |

`ProcessorListResponse` holds `ProcessorResponse` items:
`uuid`, `name`, `host`, `priority`, `state`, `capabilities`, `systemStatus`,
`lastSeen`, and the restriction sets.

### 3.9 Error handling

- Undecodable JSON, or a missing `type` ⇒ `ERROR`.
- Any typed frame other than `REGISTER` arriving before registration ⇒
  `ERROR "Not registered. Send REGISTER first."`
- A missing `body` on a type that requires one ⇒ `ERROR`.
- A frame naming an unknown or already-finished `runUuid` is **logged at debug
  and ignored**, not answered with `ERROR` — a late message for a settled run is
  normal and a worker must not be disconnected for it (`resolveEngine`).
- One malformed entry in a `NODE_TASK_RESULT_BATCH`, or one node of a
  `TASK_RETURNED` that fails to re-place, is logged and skipped; its siblings
  are still processed.

---

## 4. UI events socket (`/api/v1/pipelines/events/ws`)

### 4.1 Multiplexing

Read-only from the client. Two frame kinds share the socket, discriminated by an
optional top-level `channel`:

| `channel` | Model | Produced by |
|---|---|---|
| *(absent)* | `PipelineEventMessage` | `RunStatsAggregator` → `PipelineEventBroadcaster.broadcast()` |
| `"PROCESSOR"` | `ProcessorEventMessage` | `ProcessorRegistry` → `broadcastProcessorEvent()` |

Pipeline frames keep their original shape (no `channel`), so older clients are
unaffected. Processor frames are **not** pipeline-scoped: the `?pipeline=` and
`?run=` filters are deliberately bypassed for them, so a filtered subscriber
still sees fleet-wide processor updates.

### 4.2 Filtering

| Parameter | Matches | Used by |
|---|---|---|
| `?pipeline=<name>` | `PipelineEventMessage.pipelineName` | the UI, watching one pipeline |
| `?run=<uuid>` | `PipelineEventMessage.pipelineRunUuid` | the CLI (`PipelineEventStream`), following one run |

- Extracted in `PipelineEventEndpoint.extractQueryParam`, applied in
  `PipelineEventBroadcaster.Subscriber.matches`. **ANDed**; neither ⇒ everything.
- `?run=` exists because a pipeline-name filter still delivers every concurrent
  run of that pipeline. The CLI also filters client-side, so it works against a
  server predating the parameter.
- ⚠️ **No history.** The stream delivers only what happens after the socket
  opens. A client wanting a run's opening frames must connect *before* posting
  `/run` — which is what `metaloom pipeline run --follow` does.

### 4.3 `PipelineEventMessage`

`type`, `pipelineName`, `pipelineRunUuid`, `nodeId`, `mediaPath`, `timestamp`
(epoch millis), `durationMs`, `message`, and the aggregate counters
`activeCount`, `pendingCount`, `processedCount`, `failedCount`, `skippedCount`,
plus `itemUuid` and `elementSeq` on the breakpoint frames.

`PipelineEventType`: `PIPELINE_STARTED`, `PIPELINE_COMPLETED`, `RUN_PAUSED`,
`RUN_RESUMED`, `NODE_STARTED`, `NODE_COMPLETED`, `NODE_FAILED`, `NODE_SKIPPED`,
`NODE_BUFFERED`, `NODE_BREAKPOINT_HELD`, `NODE_BREAKPOINT_RELEASED`, `NODE_STATS`.

**Run-level frames come from `PipelineEndpointService`, not `RunStatsAggregator`**
(§4.5). The aggregator only ever sees *node settles*, so it cannot know that a run
started, was paused, or was cancelled. The four run-level types carry no counters —
a client wanting numbers reads the next `NODE_STATS` tick or refetches the run.

| Type | Emitted from | Why it cannot come from anywhere else |
|---|---|---|
| `PIPELINE_STARTED` | `dispatchRun`, right after `engine.start()` | Loom owns the graph; a worker holds none |
| `PIPELINE_COMPLETED` | `engine.onCompletion`, **plus** `cancelRun` and the undispatchable-run path | `engine.cancel()` sets `runComplete` without invoking the completion callbacks, and an unreachable processor unregisters the engine before it ever completes. Without those two extra emissions a cancelled run would be the one terminal outcome the socket never reports |
| `RUN_PAUSED` | `pauseRun`, **after** the engine gate is applied | A pause is an operator decision, never something the run discovers about itself |
| `RUN_RESUMED` | `resumeRun`, after `engine.unpause()` | as above |
| `NODE_BREAKPOINT_HELD` | the engine's `BreakpointListener`, from the settle path | Only the engine knows an execution finished and was withheld; nothing else in the system can observe the moment |
| `NODE_BREAKPOINT_RELEASED` | the same listener, from `releaseNode` / `stepOne` / disarming | Without it a node released from another tab stays ringed forever |

**The two breakpoint frames are the third exception to aggregation**, after `NODE_FAILED`
and the run-level types. A hold happens because a person asked for it, is individually
actionable, and is worthless a second late — folding it into the 1 s `NODE_STATS` tick
would leave a stopped node looking busy for up to a second. They carry `itemUuid` and
`elementSeq` on top of `nodeId`, because a node downstream of a fan-out is held once per
element and the debug view has to open the right result.

Ordering is load-bearing on both suspension frames: the frame goes out only once the
gate is really applied, so a client acting on it can never observe a run that calls
itself paused while dispatch is still running. A **refused** pause or resume (409)
broadcasts nothing.

### 4.4 `ProcessorEventMessage` (`channel: "PROCESSOR"`)

`channel` (always `"PROCESSOR"`), `type`, `nodeId`, `processor`
(`ProcessorResponse` snapshot), `lastSeen`.

`ProcessorEventType`: `REGISTERED`, `STATE_CHANGED`, `STATUS_UPDATED` (each with
a full snapshot), `HEARTBEAT` (nodeId + lastSeen only), `DISCONNECTED` (nodeId
only). A disconnect produces `STATE_CHANGED`→`OFFLINE` then `DISCONNECTED`, so
the UI can show the card as "offline (persisted)" rather than dropping it.

### 4.5 Loom is the only producer of pipeline events

A worker holds no pipeline graph — it answers `NODE_TASK`, `SEGMENT_TASK` and
`SOURCE_TASK` — so it has nothing to say at pipeline-event granularity.
`RunStatsAggregator` is the single source **of node-level frames**; run-level
lifecycle comes from `PipelineEndpointService` (§4.3):

- **Successes and skips** are counted per node and flushed as `NODE_STATS` on a
  timer (`PipelineEndpointService.STATS_INTERVAL_MS` = **1000 ms**, plus a final
  flush at run end). A 100 000-item run over a 10-node graph settles a million
  nodes; one frame per settle is millions of renders to move a bar by a percent.
- **Failures** go out immediately as `NODE_FAILED` — rare, individually
  actionable, and a bare count would leave the UI able to say "300 failed"
  without naming a file.

`ProcessorEndpoint` therefore **drops** an inbound `PIPELINE_EVENT`. The
envelope checks still apply (unregistered sender or missing body ⇒ `ERROR`), and
the first drop per processor is logged so an outdated worker stays visible.
Cortex no longer sends them: the tracking-bus subscription in
`LoomControlChannel` was removed, and nothing outside the node-chain tests
publishes to that bus. `PIPELINE_RUN_COMPLETED` came from the same bus and went
with it; runs are closed by the engine's `onCompletion` callback in
`PipelineEndpointService`, and Loom still handles the message only so an older
worker can close a run.

### 4.6 Backpressure — what it actually does

⚠️ Frequently mis-stated. There is **no per-subscriber queue**. Each broadcast:

1. Skips subscribers whose filters do not match (pipeline frames only).
2. Prunes any subscriber whose socket `isClosed()` — removal is lazy, done
   during broadcast rather than by a sweeper.
3. Encodes the JSON **once, lazily**, only after the first matching live
   subscriber is found.
4. Calls `Subscriber.send`, which checks `ws.writeQueueFull()`. If the Vert.x
   write queue is full the **new** event is dropped (not the oldest) and
   `droppedCount` is incremented; a `WARN` is logged when
   `droppedCount % 100 == 1`, and `metrics.recordPipelineEventDropped()` fires.

`PipelineEventBroadcaster.DEFAULT_QUEUE_CAPACITY` (1024) is threaded into the
`Subscriber` constructor and **never used** — dead code. Do not document it as a
capacity. `subscribers.size()` is exported as the gauge
`loom_pipeline_event_subscribers`.

### 4.7 Route ordering

Only `PipelineEventEndpoint` needs it, because `/api/v1/pipelines*` has a
wildcard auth route that would otherwise swallow the upgrade:

```java
apiRouter().getDelegate().get(basePath() + "/ws").order(-1000).handler(...)
```

`ProcessorEndpoint` registers its `/ws` route **without** an order — there is no
competing wildcard under `/api/v1/processors`. Do not "fix" this by symmetry;
`secure()` is called on the sibling paths afterwards and the plain route already
wins.

---

## 5. Reconnect and backoff

The two clients use **different** strategies. Other spec files have disagreed on
this; the code below is authoritative.

| | Cortex worker | UI client |
|---|---|---|
| Source | `LoomControlChannel.scheduleReconnect()` | `loom-ui/src/api/pipelineEvents.ts` `computeReconnectDelay()` |
| Shape | **Linear** — `min(2000 × attempt, 30000)` ms | **Exponential** — `min(maxDelayMs, baseDelayMs × 2^attempt)` ms |
| Defaults | base 2000 ms, cap 30 000 ms | base 1000 ms, cap 30 000 ms |
| Jitter | **None** | `× (0.5 + random())` ⇒ delay lands in `[0.5×, 1.5×)` |
| Attempt cap | **Unbounded** | `maxAttempts` = 10, then it gives up and logs |
| Reset | `reconnectAttempts.set(0)` on `REGISTERED` | on `onopen`, and when the token changes |
| On 4401 | retries anyway | **does not reconnect** |
| Re-identify | resends `REGISTER` on every `onConnected` | re-subscribes; no application handshake |

Worker timers, all `vertx.setPeriodic`: heartbeat 10 s, status 20 s, health log
30 s. Only one reconnect timer is ever armed (`reconnectTimerId != -1` guard).
Gauges: `cortex_loom_connected`, `cortex_loom_registered`,
`cortex_loom_reconnect_attempts`.

---

## 6. Test setup

| Area | Test | Location |
|---|---|---|
| Registration, duplicate `nodeId`, heartbeat, pre-register guard, status, state, invalid frames, restrictions, persisted-offline listing, forget | `ProcessorEndpointTest` (14) | `loom/core/src/test/java/io/metaloom/loom/core/endpoint/test/` |
| Connect, `PIPELINE_EVENT` drop paths, envelope errors | `PipelineEventEndpointTest` (6) | same |
| Pause/resume guards **and** the `RUN_PAUSED` / `PIPELINE_COMPLETED` broadcasts (incl. "a refused pause broadcasts nothing") | `PipelineRunPauseEndpointTest` (15) | same |
| Breakpoint routes, guards, and the `NODE_BREAKPOINT_HELD` / `_RELEASED` broadcasts | `PipelineRunBreakpointEndpointTest` (20) | same |
| Fan-out, `?run=` filter, closed-subscriber pruning, full-write-queue drop | `PipelineEventBroadcasterTest` (4) | `loom/services/rest/src/test/java/io/metaloom/loom/rest/service/` |
| Run completion over the socket | `PipelineRunCompletionEndpointTest` | `loom/core/src/test/.../endpoint/test/` |
| UI socket, mocked | `cortex-mocked.spec.ts`, `pipeline-events-mocked.spec.ts` | `loom-ui/e2e/` |

Run the pooled-DB setup before the Java suites — see
[../../.claude/CLAUDE.md](../../.claude/CLAUDE.md) (`./setup-pool.sh`).
`PipelineEventBroadcaster` has a no-arg constructor that installs
`NoopLoomMetrics`, so it can be unit-tested without a metrics backend.

---

## 7. Progress Assessment

### 7.1 Protocol and transport

- [x] Bidirectional processor channel with the `ProcessorMessage` envelope
- [x] Read-only, multiplexed UI events socket (`channel` discriminator)
- [x] Registration with capabilities, priority and node-kind restrictions
- [x] Heartbeat / `HEARTBEAT_ACK`; system status reporting
- [x] `SOURCE_TASK` → acked `SOURCE_ITEMS` → `SOURCE_COMPLETE`
- [x] `NODE_TASK` / `SEGMENT_TASK` dispatch and their result frames, incl. batching
- [x] Typed `PortPayload` inputs/outputs keyed by port id
- [x] Graceful drain: `TERMINATING`, `TASK_RETURNED`, attempt refund, flush before close
- [x] Duplicate-`nodeId` rejection with close code `4409`
- [x] Socket-scoped `disconnect(nodeId, ws)` so a superseded close cannot evict a reconnect
- [ ] `gpuLoad` is on the wire but never populated by the worker
- [ ] A `SOURCE_TASK` in progress is abandoned by a drain; the run waits for a `SOURCE_COMPLETE` that never arrives
- [ ] No server-side heartbeat timeout: a silent worker stays registered until its socket closes
- [ ] No max message size, no binary frames, no protocol version negotiation
- [ ] `ProcessorMessageType` files `SEGMENT_TASK_RESULT` / `NODE_TASK_RESULT_BATCH` under the wrong direction banner

### 7.2 Authentication and security

- [x] `?token=<jwt>` validated by a shared `WebSocketAuthenticator` (processor, events, MCP)
- [x] Strict mode via `LOOM_WS_STRICT_AUTH` / `-Dloom.ws.strictAuth`; close code `4401`
- [x] Auth failures counted (`recordAuthFailure("ws")`); tokens never logged
- [x] `nodeId` uniqueness enforced at REGISTER
- [ ] Strict mode is opt-in — the default still accepts a tokenless connection
- [ ] Token expiry is never re-checked on an open socket; no refresh / re-auth
- [ ] No origin validation on the upgrade
- [ ] No rate limiting on connection attempts or on message frequency
- [ ] No per-message authorization after the handshake

### 7.3 Broadcasting

- [x] Run-level lifecycle frames actually emitted: `PIPELINE_STARTED` / `PIPELINE_COMPLETED` / `RUN_PAUSED` / `RUN_RESUMED`
- [x] Breakpoint frames emitted immediately rather than aggregated: `NODE_BREAKPOINT_HELD` / `NODE_BREAKPOINT_RELEASED`
- [x] Pipeline-name and run-uuid filters, ANDed
- [x] Lazy JSON encoding; lazy pruning of closed sockets
- [x] Write-queue-full drop with counter, throttled log and metric
- [x] Processor lifecycle events multiplexed onto the same socket
- [ ] `DEFAULT_QUEUE_CAPACITY` is dead code — remove it or implement a real queue
- [ ] Newest-frame-dropped means a burst loses the *latest* state, which is the one that matters
- [ ] No history/replay: a client connecting after a run starts misses its opening events
- [ ] No dead-letter or client-visible signal for dropped events

### 7.4 Reconnect

- [x] Worker reconnects and re-registers automatically; attempts reset on `REGISTERED`
- [x] UI backs off exponentially with jitter and stops on `4401`
- [ ] Worker backoff is **linear and unjittered** — a fleet restarting together reconnects in lock-step
- [ ] Worker retries forever, including after a `4401` that will never succeed
- [ ] Work in flight at the moment of an unplanned disconnect still waits out its lease

### 7.5 Documentation and tooling

- [x] Javadoc on `ProcessorEndpoint`, `PipelineEventEndpoint`, `WebSocketAuthenticator`, `PipelineEventBroadcaster`, `ProcessorMessage(Type)`
- [x] Endpoint, broadcaster and UI-socket tests exist (§6)
- [ ] No authentication tests (valid / invalid / missing token, strict mode)
- [ ] No reconnection-scenario test on the Java side
- [ ] WebSocket endpoints and their message models are absent from the OpenAPI spec
- [ ] `LoomHttpClient` has no WebSocket helpers; there is no reusable client for either socket
- [ ] Processor list route supports neither pagination nor filtering by state/capability
- [ ] No route to force-disconnect a processor or to push it a message

---

## 8. Conventions and Gotchas

1. **The chat stream is SSE, not a WebSocket.** `SseAgentEventSink` /
   `text/event-stream`. Anything describing "the chat WebSocket" is wrong.
2. **Backpressure drops the newest frame, not the oldest**, and there is no
   1024-entry queue (§4.6). `DEFAULT_QUEUE_CAPACITY` is dead.
3. **Worker backoff is linear; UI backoff is exponential** (§5). Neither is a
   typo, and specs that claim otherwise are stale.
4. **`NodeTask.inputs` is keyed by the receiving node's port ids**, never by the
   upstream node id — renaming a node in the editor must not break downstream
   lookups. The old `upstreamOutputs` name is gone.
5. **Outputs survive `SKIPPED` and `FAILED`.** Do not reintroduce the clearing.
5a. **A preview is never truncated to fit and never fails a task.** Half a JPEG is not a
    smaller JPEG, and a node that did its real work correctly must not be reported as
    failed because a thumbnail could not be encoded.
6. **An empty `nodeWhitelist` means "accepts anything."** A worker that fails to
   determine its kinds registers unrestricted rather than dropping out.
7. **A message for an unknown run is ignored, not an error.** Answering with
   `ERROR` would punish a worker for the normal race of a run finishing first.
8. **`SOURCE_ITEMS_ACK` is the only source-side throttle.** Withholding it stops
   the scan itself; capping dispatch alone still lets item state pile up.
9. **Only `ONLINE` is placeable.** `TERMINATING`, `PAUSED` and `STARTING` are
   all excluded — see `isPlaceable`.
10. **`4401` is auth, `4409` is duplicate `nodeId`.** Different layers; do not
    merge them.
11. **Close the socket only after flushing.** A queued frame is lost on close,
    which is exactly the hand-back the drain exists to deliver.
11a. **A cancel and an undispatchable run must broadcast `PIPELINE_COMPLETED`
    explicitly.** Neither path runs the engine's completion callbacks —
    `engine.cancel()` sets `runComplete` directly, and an unreachable processor
    unregisters the engine first — so both emit the closing frame themselves. Drop
    either and a run silently never closes for every connected client.
12. **`order(-1000)` is needed on the pipelines socket only**, because of the
    `/api/v1/pipelines*` wildcard auth route.

---

## 9. Key Classes Reference

| Class | Package / module | Purpose |
|---|---|---|
| `ProcessorEndpoint` | `io.metaloom.loom.rest.endpoint.impl` (loom/services/rest) | Worker socket + processor REST routes |
| `PipelineEventEndpoint` | `io.metaloom.loom.rest.endpoint.impl` | UI events socket, filter extraction, `order(-1000)` |
| `WebSocketAuthenticator` | `io.metaloom.loom.rest.service.impl` | `?token=` validation, strict mode, `4401` |
| `PipelineEventBroadcaster` | `io.metaloom.loom.rest.service.impl` | Subscriber set, filters, write-queue drop |
| `ProcessorRegistry` | `io.metaloom.loom.rest.service.impl` | Connected workers, placement, `send`, processor events |
| `WebSocketNodeDispatcher` | `io.metaloom.loom.rest.service.impl` | Turns a `NodeTask`/`SegmentTask` into a dispatched frame |
| `RunStatsAggregator` | `io.metaloom.loom.rest.service.impl` | Counts settles, emits `NODE_STATS` / `NODE_FAILED` |
| `PipelineRunRegistry` / `PipelineRunTracker` | `io.metaloom.loom.rest.service.impl` | Live engines by run uuid; terminal status and counters |
| `PipelineEndpointService` | `io.metaloom.loom.rest.service.impl` | Run start, `SOURCE_TASK`, stats timer, 503 on no worker |
| `ProcessorMessage` / `ProcessorMessageType` | `io.metaloom.loom.rest.model.processor.message` (loom-shared/rest-model) | Envelope and vocabulary |
| `ProcessorRegistration`, `Source*Message`, `TaskReturnedMessage`, `NodeTaskResultMessage` | same package | Control-plane bodies |
| `NodeTask`, `NodeTaskResult`, `SegmentTask`, `SegmentTaskResult`, `NodeTaskResultBatch`, `PortPayload`, `MediaRef`, `NodeState` | `io.metaloom.loom.pipeline.model` (loom-shared/pipeline-model) | Work-plane wire model |
| `PipelineEventMessage` / `PipelineEventType` | `io.metaloom.loom.rest.model.pipeline.event` | UI pipeline frames |
| `ProcessorEventMessage` / `ProcessorEventType` | `io.metaloom.loom.rest.model.processor.event` | UI processor frames (`channel:"PROCESSOR"`) |
| `LoomControlChannel` | `io.metaloom.cortex.impl.loom` (cortex/core) | Worker side: connect, register, heartbeat, drain, reconnect |
| `PipelineTaskHandler` | `io.metaloom.cortex.impl.loom` | Executes tasks off the socket thread; emits result frames |
| `PipelineEventStream` | `io.metaloom.cli.client` (cli) | CLI consumer of the events socket |

---

## 10. Where do I find …?

| Concept | Path |
|---|---|
| Worker socket handlers | `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/ProcessorEndpoint.java` |
| UI socket route and filters | `loom/services/rest/src/main/java/io/metaloom/loom/rest/endpoint/impl/PipelineEventEndpoint.java` |
| Token check and strict mode | `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/WebSocketAuthenticator.java` |
| Broadcast, filtering, drop rule | `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEventBroadcaster.java` |
| Placement, load score, `isPlaceable` | `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/ProcessorRegistry.java` |
| `NODE_STATS` cadence (`STATS_INTERVAL_MS`) | `loom/services/rest/src/main/java/io/metaloom/loom/rest/service/impl/PipelineEndpointService.java` |
| Message vocabulary | `loom-shared/rest-model/src/main/java/io/metaloom/loom/rest/model/processor/message/` |
| Work-plane wire model | `loom-shared/pipeline-model/src/main/java/io/metaloom/loom/pipeline/model/` |
| Worker connect / drain / reconnect | `cortex/core/src/main/java/io/metaloom/cortex/impl/loom/LoomControlChannel.java` |
| Worker task execution and result frames | `cortex/core/src/main/java/io/metaloom/cortex/impl/loom/PipelineTaskHandler.java` |
| UI reconnect policy and channel routing | `loom-ui/src/api/pipelineEvents.ts` |
| CLI run-follow client | `cli/src/main/java/io/metaloom/cli/client/PipelineEventStream.java` |
| MCP socket (`/mcp/ws`) | `loom/services/mcp/src/main/java/io/metaloom/loom/mcp/MCPService.java`, [MCP.md](MCP.md) |
| Run engine semantics | [../features/pipeline/PIPELINE.md](../features/pipeline/PIPELINE.md) |
| Worker architecture, typed ports | [../cortex/METALOOM_ARCHITECTURE.md](../cortex/METALOOM_ARCHITECTURE.md) |
| In-JVM event bus (not this socket) | [EVENTBUS.md](EVENTBUS.md) |
| REST conventions and auth | [RESTAPI.md](RESTAPI.md) |

---

_Git HEAD revision: `827cd2cb`_
_Last updated: 2026-08-04 (Added `NODE_BREAKPOINT_HELD`/`NODE_BREAKPOINT_RELEASED` and the `itemUuid`/`elementSeq` fields they carry. Earlier the same day: the run-level lifecycle frames `RUN_PAUSED`/`RUN_RESUMED` and the previously-declared-but-never-emitted `PIPELINE_STARTED`/`PIPELINE_COMPLETED`.)_
