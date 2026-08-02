# Sidecars — Index

Python HTTP model servers that Cortex nodes call over localhost. Every sidecar is a **separate
process with its own virtualenv and its own model weights**; no sidecar shares a runtime with the
JVM, and none of them is part of the Maven reactor.

One file per sidecar in this directory carries the full contract. This page is the router, plus the
facts that are only visible when you look at all six at once.

**Related:** [../features/pipeline-nodes/NODES.md](../features/pipeline-nodes/NODES.md) (the nodes
that call them) · [../cortex/CORTEX.md](../cortex/CORTEX.md) · [../CONTEXT.md](../CONTEXT.md)

## The six sidecars

| Sidecar | Port | Spec | Calling node (kind) | Purpose |
|---|---|---|---|---|
| `sidecars/depth` | 9120 | [DEPTH_SIDECAR.md](DEPTH_SIDECAR.md) | `DepthmapNode` (`depthmap`) | Monocular depth → 16-bit NEARNESS map |
| `sidecars/sentiment` | 9110 | [SENTIMENT_SIDECAR.md](SENTIMENT_SIDECAR.md) | `SentimentNode` (`sentiment`) | DE/EN/multilingual 3-class sentiment |
| `sidecars/tts` | 9100 | [TTS_SIDECAR.md](TTS_SIDECAR.md) | `TtsNode` (`tts`) | Orpheus (DE) / Kokoro (EN) speech synthesis |
| `sidecars/ideogram-sidecar` | 9200 | [IDEOGRAM_SIDECAR.md](IDEOGRAM_SIDECAR.md) | `ImageGenNode` (`imagegen`) | SDXL-Turbo / Ideogram-4 nf4 image generation |
| `sidecars/mage-flow-sidecar` | 9210 | [MAGE_FLOW_SIDECAR.md](MAGE_FLOW_SIDECAR.md) | `ImageGenNode` (`imagegen`) | Mage-Flow 4B — **MIT weights**, the commercially usable backend |
| `sidecars/ltx2-sidecar` | 9220 | [LTX2_SIDECAR.md](LTX2_SIDECAR.md) | `VideoGenNode` (`videogen`) | LTX-2 video **with synchronised audio** |

`imagegen` has two interchangeable backends. You pick one by setting the node's `port` option —
`9200` for Ideogram, `9210` for Mage-Flow. There is no backend enum; the port *is* the selector.

## Architecture

```
Cortex worker (JVM)                          sidecar process (Python)
┌────────────────────────┐                   ┌──────────────────────────────┐
│ DepthmapNode           │──HTTP/1.1 JSON───▶│ FastAPI + uvicorn --workers 1│
│   └─ DepthmapClient    │  localhost:9120   │   └─ lazily-loaded model     │
│ SentimentNode          │──────────────────▶│      (module-global, no      │
│ TtsNode                │──────────────────▶│       eviction)              │
│ ImageGenNode           │──────────────────▶│                              │
│ VideoGenNode           │──────────────────▶│  GPU work serialised         │
└────────────────────────┘                   └──────────────────────────────┘
         │                                                 │
         └── node result → Loom                            └── weights from HF cache
```

Nodes are pure HTTP clients: they hold no model state and fail the node when the sidecar is
unreachable. The sidecars hold no Loom state and never call back.

## Cross-cutting status — read this before planning any deployment

| Property | Reality at this revision |
|---|---|
| **Helm** | 🔴 **No sidecar appears in any chart.** `helm/` contains zero references to ports 9100–9220 |
| **Compose** | 🔴 No `docker-compose` file references any sidecar |
| **Container image** | 🟡 Only `ideogram-sidecar`, `mage-flow-sidecar`, `ltx2-sidecar` ship a `Dockerfile`. `depth`, `sentiment`, `tts` have **no** container build at all |
| **Start path** | Manual `setup.sh` then `run.sh` (except `ideogram-sidecar`, which has neither — see its spec) |
| **Python tests** | 🔴 **Zero.** No sidecar has a single Python test |
| **Java-side tests** | All stub the client. Nothing in the repo has ever exercised a live sidecar over the wire |
| **Auth** | 🔴 None. Every sidecar binds `0.0.0.0` with no token, no TLS, no allow-list |

The practical consequence: **the wire formats in these specs are derived from reading both sides of
the code, not from a passing end-to-end test.** Treat a first live bring-up as unproven.

## Environment variables

Each sidecar's own spec has the authoritative table. Two cross-cutting hazards:

| Variable | Read by | Hazard |
|---|---|---|
| `DEVICE` | `depth`, `sentiment`, `tts` | 🔴 **Unprefixed and shared.** One export changes three sidecars. The three also differ subtly on empty-string handling (`or`-default vs. argument default) |
| `<NAME>_PORT` | `depth`, `sentiment`, `tts`, `ltx2`, `mage-flow` | Read by `run.sh` **only** — the Python never reads it. Launching `server.py` directly ignores it and binds the hardcoded default |

`ideogram-sidecar` has no host/port variable at all; its port exists only in the uvicorn command
line.

## Conventions and Gotchas

* **Config is captured at import time.** Every sidecar reads its environment into module-level
  constants when `server.py` is imported. Changing a variable requires a **process restart** — there
  is no reload endpoint.
* **`--workers 1` is load-bearing.** Model weights live in module globals. A second worker would
  double VRAM and, on the image/video sidecars, OOM.
* **`GET /health` exists on all six and is called by none of them.** No node performs a readiness
  probe, so a cold sidecar surfaces as a slow first request, not as a clear "not ready".
* **The node always sends its option defaults.** Options like `steps` are non-null on the Java side,
  so a server-side `*_STEPS` default is **only** in effect for non-node traffic (curl, tests). This
  has bitten `imagegen` specifically — see [IDEOGRAM_SIDECAR.md](IDEOGRAM_SIDECAR.md).
* **Force HTTP/1.1.** The JDK client's HTTP/2 upgrade is rejected by these FastAPI servers; the
  node clients set this explicitly. A new client that forgets it fails at connect.
* **First call downloads weights.** Models load lazily on first matching request, from the Hugging
  Face cache. The first request after a cold start can take minutes and is easily mistaken for a
  hang. `ltx2` pulls ~130 GB.
* **`sidecars/README.md` is stale in places** — notably its LTX-2 VRAM guidance and its claim that
  every sidecar ships `setup.sh`/`run.sh`. The per-sidecar specs here were verified against code;
  prefer them.
* **`ltx2-sidecar/.venv/` is committed** (~34k files). Exclude it from every `rg`/`find`, or
  searches over `sidecars/` become unusable.
* **Plain `grep` silently returns nothing** on some files in this repo. Use `rg` or `/usr/bin/grep`.

## Test Setup

There is no test harness for the sidecars. What exists:

* **Java side** — each node has unit/options/persistence tests that stub its client
  (`DepthmapClient`, `SentimentClient`, `TtsClient`, `ImageGenClient`, `VideoGenClient`). These
  verify the node's behaviour given a response; they do not verify the response shape is real.
* **Integration tests** — `integration-test/` has per-node E2E tests, but none starts a sidecar.
* **Manual bring-up** — `cd sidecars/<name> && ./setup.sh && ./run.sh`, then `curl` the endpoint
  documented in that sidecar's spec.

To add real coverage, the smallest useful step is a Python test per sidecar asserting the response
schema its Java client parses — that is the seam where a silent drift would show up.

## Where do I find ...?

| I want ... | Look at |
|---|---|
| A sidecar's HTTP contract | The per-sidecar spec in this directory |
| The Java client that calls it | `cortex/nodes/<node>/core/src/main/java/…/<Name>Client.java` |
| The node's options (host, port, timeout) | `…/<Name>NodeOptions.java` |
| What the pipeline editor shows for the node | `loom-shared/node-model/…/<Name>DescriptorProvider.java` |
| The node's place in the node catalogue | [../features/pipeline-nodes/NODES.md](../features/pipeline-nodes/NODES.md) |
| Why no sidecar is deployed | This page, "Cross-cutting status" |
| The original per-node build plan | `../features/pipeline-nodes/NODE_*_PLAN.md` |

## Progress Assessment

- [x] All six sidecars have a dedicated spec verified against both sides of the code
- [x] Ports, endpoints, consumers and env vars recorded per sidecar
- [ ] 🔴 No sidecar is deployable: no Helm chart, no compose file, and three have no `Dockerfile`
- [ ] 🔴 No Python tests anywhere; no test exercises a live sidecar over the wire
- [ ] 🔴 No authentication on any sidecar despite binding `0.0.0.0`
- [ ] 🟡 `DEVICE` is unprefixed and shared across three sidecars — namespace it
- [ ] 🟡 `<NAME>_PORT` is honoured by `run.sh` but ignored by `server.py` — read it in Python
- [ ] 🟡 `ideogram-sidecar` has no `setup.sh`/`run.sh` and no host/port variable
- [ ] 🟡 `sidecars/README.md` contradicts the verified per-sidecar findings — reconcile it
- [ ] 🟢 Nothing calls `GET /health`; wire a readiness probe into the node clients

---

_Git HEAD revision: `d930e222`_
_Last updated: 2026-08-02 (new file — index for the six per-sidecar specs, with the cross-cutting deployment and test status)_
