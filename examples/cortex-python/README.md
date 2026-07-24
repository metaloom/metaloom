# MetaLoom // Cortex — Python Node Example

A **minimal, self-contained Python daemon** that plugs into a Loom backend as a
processing worker — the Python counterpart to the Java
[`cortex-custom`](../cortex-custom) example.

The Cortex node SPI (Dagger, `AbstractMediaNode`) is JVM-only and cannot be
called from Python. So instead of reusing it, this example talks Loom's **wire
protocol** directly. That is all a worker actually needs: register over a
WebSocket, receive work, run a node, and report results. Use it as a starting
point if you want to implement your own nodes in Python.

Everything lives in a **single file** — [`daemon.py`](./daemon.py) — with one
runtime dependency (`websockets`); the REST calls use the Python standard
library.

## How a worker talks to Loom

A Cortex worker uses two planes, and this daemon uses both:

```
                          ┌─────────────────────────── Loom ───────────────────────────┐
  control plane           │                                                             │
  (WebSocket)   REGISTER  │  /api/v1/processors/ws   ── engine dispatches ──▶ NODE_TASK │
  daemon.py  ────────────▶│                          ◀── worker answers ──── NODE_TASK_RESULT
                          │                                                             │
  data plane              │  POST /api/v1/assets/:uuid/json-comps     (the payload)     │
  (REST)     ────────────▶│  POST /api/v1/assets/:uuid/node-results   (the ledger row)  │
                          └─────────────────────────────────────────────────────────────┘
```

- **Control plane** — a persistent WebSocket at `/api/v1/processors/ws`. Loom
  *pushes* work; the worker does not poll. This is what makes the worker part of
  the fleet.
- **Data plane** — the REST API. This is where the actual computed **payload**
  is written into Loom, keyed by asset UUID. The `NODE_TASK_RESULT` on the
  control plane only tells the engine the node finished and carries its outputs
  for downstream nodes; the durable data is written over REST.

## Prerequisites

- **Python 3.11+**
- `pip install -r requirements.txt`
- **A running Loom backend.** The quickest local option from the repo root:

  ```bash
  ./start-postgres.sh     # Postgres for Loom
  ./start-demo.sh         # Loom on http://localhost:8092 (initial password: finger)
  ```

- **Shared storage.** Loom sends the worker a *reference* to media (a `path`),
  never the bytes. The worker must be able to read that `path` itself, so the
  media mount has to be visible to this process — exactly as it is for a JVM
  Cortex worker.

## Configuration

All configuration is via environment variables:

| Variable            | Purpose                                                        | Default          |
|---------------------|---------------------------------------------------------------|------------------|
| `LOOM_HOST`         | Loom hostname                                                 | `localhost`      |
| `LOOM_PORT`         | Loom REST + WebSocket port                                    | `8092`           |
| `LOOM_TOKEN`        | JWT used for both the WebSocket (`?token=`) and REST (`Bearer`) | *(none)*       |
| `LOOM_USER`         | Username — used to log in for a token if `LOOM_TOKEN` is unset | *(none)*         |
| `LOOM_PASSWORD`     | Password for the above                                        | *(none)*         |
| `CORTEX_NODE_ID`    | Stable worker id (survives restarts; Loom keys leases on it)  | generated        |
| `CORTEX_NODE_KINDS` | Comma-separated node kinds this worker advertises and runs    | `py-hello`       |
| `LOG_LEVEL`         | Python log level                                              | `INFO`           |

> **Port note:** `8092` is Loom's REST **and** WebSocket port. Some Cortex docs
> mention `7733` as a historical default, but the running Loom server (and
> `start-cortex.sh`) use `8092`.

## Run it

```bash
pip install -r requirements.txt

export LOOM_HOST=localhost LOOM_PORT=8092
export LOOM_TOKEN=<your-jwt>          # or set LOOM_USER / LOOM_PASSWORD instead
python daemon.py
```

Without a token or credentials the worker still **registers and answers tasks**,
it simply skips result persistence (the same graceful degradation as an offline
JVM Cortex worker). You can confirm it joined the fleet with:

```bash
curl -H "Authorization: Bearer $LOOM_TOKEN" http://localhost:8092/api/v1/processors
```

To verify your node logic **without any Loom server**:

```bash
python daemon.py --selftest ./some-file.txt
# prints: {"state": "COMPLETED", "outputs": {"file_size": ..., "word_count": ...}, ...}
```

## How work flows

| Message            | Direction        | Meaning                                                          |
|--------------------|------------------|------------------------------------------------------------------|
| `REGISTER`         | worker → Loom    | Announce `nodeId`, capabilities, and the `nodeWhitelist` of kinds we run |
| `REGISTERED`       | Loom → worker    | Registration acknowledged                                        |
| `HEARTBEAT` / `HEARTBEAT_ACK` | both  | Keepalive every 10s                                             |
| `STATUS_UPDATE`    | worker → Loom    | CPU / disk metrics every 20s                                    |
| `NODE_TASK`        | Loom → worker    | Run one node against one media item                             |
| `NODE_TASK_RESULT` | worker → Loom    | The outcome (`COMPLETED` / `FAILED` / `SKIPPED`) + outputs      |

Loom, not the worker, decides what runs next. The worker only turns a
`NODE_TASK` into a node invocation and an answer. Every dispatched task gets
exactly one result — a node that throws is reported as `FAILED`, never dropped,
because the engine blocks on one answer per task.

## Writing your own node

Replace `run_node()` in [`daemon.py`](./daemon.py). It receives the media path,
the per-task `options`, and `upstreamOutputs` (the outputs of upstream nodes
this node depends on), and returns a `NodeResult`:

```python
def run_node(node_kind, media_path, options, upstream):
    # ... compute whatever you like from media_path ...
    return NodeResult.completed({"my_output": value})
```

Then advertise the kind via `CORTEX_NODE_KINDS` so Loom dispatches it to you.
The bundled `py-hello` node mirrors the Java
[`HelloWorldNode`](../cortex-custom-node): it emits `file_size` and
`word_count`.

## Persisting results

When online, a `COMPLETED` result is written to Loom in two steps — the same
pattern the built-in `WhisperNode` uses:

```
POST /api/v1/assets/:uuid/json-comps      # the payload, into the generic asset_json_comp sink
POST /api/v1/assets/:uuid/node-results    # a ledger row recording what ran, keyed by (asset, nodeKind, nodeId)
```

The asset UUID is resolved from the media hash the task carries
(`GET /api/v1/assets/sha512/:sha512`). That hash is only present after a hash
node has run upstream, so persistence is **best-effort**: if the asset is not
known yet, the worker still reports the result to the engine and skips the
write. `json-comps` is the lightweight, schema-agnostic sink — no dedicated
database table is required.

## Out of scope

To stay minimal, this example handles only the `NODE_TASK` path. It does not
implement `SOURCE_TASK` / `SEGMENT_TASK` (source enumeration and affinity
segments). Those are advanced Cortex paths — see the references below.

## Protocol reference

| Topic                              | Where                                                                 |
|------------------------------------|-----------------------------------------------------------------------|
| Processor WebSocket protocol       | [`spec/loom/WEBSOCKET.md`](../../spec/loom/WEBSOCKET.md)               |
| Cortex architecture & lifecycle    | [`spec/cortex/CORTEX.md`](../../spec/cortex/CORTEX.md)                 |
| JVM control channel (reference)    | `cortex/core/.../impl/loom/LoomControlChannel.java`                    |
| JVM task execution (reference)     | `cortex/core/.../impl/loom/PipelineTaskHandler.java`                   |
| Loom-side WebSocket endpoint       | `loom/services/rest/.../endpoint/impl/ProcessorEndpoint.java`         |
| Wire models (`NodeTask`, results)  | `loom-shared/pipeline-model/`, `loom-shared/rest-model/.../processor/` |

## License

Apache License, Version 2.0.
