# Sidecars — Index

HTTP model servers that Cortex nodes call over localhost. Every sidecar is a **separate process with
its own model weights**; no sidecar shares a runtime with the JVM, and none of them is part of the
Maven reactor.

One file per sidecar in this directory carries the full contract. This page is the router, plus the
facts that are only visible when you look at all seven at once.

**Related:** [../features/nodes/NODES.md](../features/nodes/NODES.md) (the nodes that call them) ·
[../cortex/CORTEX.md](../cortex/CORTEX.md) · [../METALOOM_CONTEXT.md](../METALOOM_CONTEXT.md)

## The seven sidecars

| Sidecar | Port | Spec | Calling node (kind) | Purpose |
|---|---|---|---|---|
| `sidecars/tts` | 9100 | [TTS_SIDECAR.md](TTS_SIDECAR.md) | `TtsNode` (`tts`) | Orpheus (DE) / Kokoro (EN) speech synthesis |
| `sidecars/sentiment` | 9110 | [SENTIMENT_SIDECAR.md](SENTIMENT_SIDECAR.md) | `SentimentNode` (`sentiment`) | DE/EN/multilingual 3-class sentiment |
| `sidecars/depth` | 9120 | [DEPTH_SIDECAR.md](DEPTH_SIDECAR.md) | `DepthmapNode` (`depthmap`) | Monocular depth → 16-bit NEARNESS map |
| `sidecars/ideogram-sidecar` | 9200 | [IDEOGRAM_SIDECAR.md](IDEOGRAM_SIDECAR.md) | `ImageGenNode` (`imagegen`) | SDXL-Turbo / Ideogram-4 nf4 image generation |
| `sidecars/mage-flow-sidecar` | 9210 | [MAGE_FLOW_SIDECAR.md](MAGE_FLOW_SIDECAR.md) | `ImageGenNode` (`imagegen`) | Mage-Flow 4B — **MIT weights**, the commercially usable backend |
| `sidecars/ltx2-sidecar` | 9220 | [LTX2_SIDECAR.md](LTX2_SIDECAR.md) | `VideoGenNode` (`videogen`) | LTX-2 video **with synchronised audio** |
| `sidecars/llamacpp` | **8080** | [LLAMACPP_SIDECAR.md](LLAMACPP_SIDECAR.md) | `LLMNode` (`llm`), `TranslateNode` (`translate`) | llama.cpp — OpenAI-compatible chat completions |

`imagegen` has two interchangeable backends. You pick one by setting the node's `port` option —
`9200` for Ideogram, `9210` for Mage-Flow. There is no backend enum; the port *is* the selector.

### `llamacpp` is the odd one out — read this before generalising

Six of the seven are **our** FastAPI servers: a `.venv`, a `requirements.txt`, a `server.py` we
wrote, a bespoke `POST /v1/<thing>` contract, and a port from the 9100–9220 block. `llamacpp` is
none of that — it is three shell scripts around llama.cpp's **official container image**, speaking a
protocol we do not own, on **8080** because that is already
`AbstractLlmNodeOptions.DEFAULT_OPENAI_URL`. Every statement below about venvs, `server.py`,
`--workers 1`, `<NAME>_PORT` or lazy model loading applies to the six, **not** to it.

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

┌────────────────────────┐                   ┌──────────────────────────────┐
│ LLMNode / TranslateNode│──OpenAI /v1──────▶│ llama.cpp official image      │
│   └─ OpenAILLMProvider │  localhost:8080   │   └─ ONE GGUF, loaded at start│
└────────────────────────┘                   └──────────────────────────────┘
```

Nodes are pure HTTP clients: they hold no model state and fail the node when the sidecar is
unreachable. The sidecars hold no Loom state and never call back.

## Cross-cutting status — read this before planning any deployment

| Property | Reality at this revision |
|---|---|
| **Helm** | 🔴 **No sidecar appears in any chart.** `helm/` contains zero references to ports 9100–9220 or 8080 |
| **Compose** | 🔴 No `docker-compose` file references any sidecar |
| **Container image** | 🟡 `ideogram-sidecar`, `mage-flow-sidecar`, `ltx2-sidecar` ship a `Dockerfile`; `llamacpp` uses **upstream's official image**. `depth`, `sentiment`, `tts` have **no** container build at all |
| **Container runtime** | 🟡 Only `llamacpp` runs under both **docker and podman**; the three with a Dockerfile assume docker |
| **Start path** | Manual `setup.sh` then `run.sh` (except `ideogram-sidecar`, which has neither — see its spec). Only `llamacpp`'s `run.sh` **blocks until healthy**; the rest return immediately |
| **Python tests** | 🔴 **Zero.** No sidecar has a single Python test (`llamacpp` has no Python at all) |
| **Java-side tests** | All stub the client. No test in the repo exercises a live sidecar over the wire |
| **Live bring-up observed** | 🟡 `llamacpp` only — chat completion + tool call verified on this checkout. The other six have never been started here |
| **Auth** | 🔴 None. Every sidecar binds `0.0.0.0` with no token, no TLS, no allow-list |

The practical consequence: **the wire formats in the six Python specs are derived from reading both
sides of the code, not from a passing end-to-end test.** Treat a first live bring-up as unproven.
`llamacpp` is the exception, and it is also the one whose protocol we do not own.

## Environment variables

Each sidecar's own spec has the authoritative table. Two cross-cutting hazards:

| Variable | Read by | Hazard |
|---|---|---|
| `DEVICE` | `depth`, `sentiment`, `tts` | 🔴 **Unprefixed and shared.** One export changes three sidecars. The three also differ subtly on empty-string handling (`or`-default vs. argument default) |
| `<NAME>_PORT` | `depth`, `sentiment`, `tts`, `ltx2`, `mage-flow` | Read by `run.sh` **only** — the Python never reads it. Launching `server.py` directly ignores it and binds the hardcoded default |

`ideogram-sidecar` has no host/port variable at all; its port exists only in the uvicorn command
line.

`llamacpp` avoids both hazards: every variable is `LLAMACPP_`-prefixed, and there is no `server.py`
to disagree with `run.sh` about the port. Copy that pattern when namespacing the other six.

## Conventions and Gotchas

Unless a bullet names `llamacpp`, it is about the six Python sidecars.

* **Config is captured at import time.** Every Python sidecar reads its environment into module-level
  constants when `server.py` is imported. Changing a variable requires a **process restart** — there
  is no reload endpoint.
* **`--workers 1` is load-bearing.** Model weights live in module globals. A second worker would
  double VRAM and, on the image/video sidecars, OOM.
* **`GET /health` exists on all seven and is called by no node.** Only `llamacpp`'s own `run.sh`
  polls it. Everywhere else a cold sidecar surfaces as a slow first request, not as a clear
  "not ready".
* **`llamacpp` loads its model at *start*, not on first request** — the inverse of the six. `/health`
  answers 503 for the first minutes and `run.sh` waits up to 900 s for it.
* **`llamacpp` silently ignores the per-call `model` field.** One container serves one GGUF; a
  request naming a different model is answered by the loaded one, with no error. See
  [LLAMACPP_SIDECAR.md](LLAMACPP_SIDECAR.md) §4.2.
* **`FacedescriptionNode.URL` hardcodes `http://127.0.0.1:8080/v1`** — `llamacpp`'s default port —
  but needs a *vision* model there. Running the sidecar with a text-only GGUF gives that node a
  healthy server that cannot see its images.
* **The node always sends its option defaults.** Options like `steps` are non-null on the Java side,
  so a server-side `*_STEPS` default is **only** in effect for non-node traffic (curl, tests). This
  has bitten `imagegen` specifically — see [IDEOGRAM_SIDECAR.md](IDEOGRAM_SIDECAR.md).
* **Force HTTP/1.1.** The JDK client's HTTP/2 upgrade is rejected by these FastAPI servers; the
  node clients set this explicitly. A new client that forgets it fails at connect.
* **First call downloads weights.** Models load lazily on first matching request, from the Hugging
  Face cache. The first request after a cold start can take minutes and is easily mistaken for a
  hang. `ltx2` pulls ~130 GB. (`llamacpp` front-loads this into startup instead.)
* **`sidecars/README.md` is stale in places** — notably its LTX-2 VRAM guidance and its claim that
  every sidecar ships `setup.sh`/`run.sh`. The per-sidecar specs here were verified against code;
  prefer them.
* **Links to `../features/pipeline-nodes/…` in the six older specs are broken.** That directory was
  renamed: node docs now live in `../features/nodes/`, and the `NODE_*_PLAN.md` files in
  `../concept/`. This page's links are fixed; the six per-sidecar specs still carry the old paths.
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
* **`llm` node tests** — the closest thing to a live-backend test in the repo. They target
  `loom-test-env/llamacpp` on **:8899** (a second llama.cpp container, deliberately not the
  sidecar's 8080) and **skip** when nothing is listening. A green build does not prove they ran.

To add real coverage, the smallest useful step is a Python test per sidecar asserting the response
schema its Java client parses — that is the seam where a silent drift would show up. `llamacpp`
needs no such test (the schema is upstream's), but nothing starts it either.

## Where do I find ...?

| I want ... | Look at |
|---|---|
| A sidecar's HTTP contract | The per-sidecar spec in this directory |
| The Java client that calls it | `cortex/nodes/<node>/core/src/main/java/…/<Name>Client.java` |
| The node's options (host, port, timeout) | `…/<Name>NodeOptions.java` |
| What the pipeline editor shows for the node | `loom-shared/node-model/…/<Name>DescriptorProvider.java` |
| The node's place in the node catalogue | [../features/nodes/NODES.md](../features/nodes/NODES.md) |
| Why no sidecar is deployed | This page, "Cross-cutting status" |
| The original per-node build plan | `../concept/NODE_*_PLAN.md` |
| The LLM backend the `llm`/`translate` nodes call | [LLAMACPP_SIDECAR.md](LLAMACPP_SIDECAR.md); `cortex/llm-common/…/AbstractLlmNodeOptions.java` |
| A llama.cpp for the **tests** rather than for deployment | `loom-test-env/llamacpp/` (port 8899) |

## Progress Assessment

- [x] All seven sidecars have a dedicated spec verified against both sides of the code
- [x] Ports, endpoints, consumers and env vars recorded per sidecar
- [x] `llm`/`translate` have an in-repo backend (`sidecars/llamacpp`), runnable under docker or
      podman and verified live
- [ ] 🔴 No sidecar is deployable: no Helm chart, no compose file, and three have no `Dockerfile`
- [ ] 🔴 No Python tests anywhere; no test starts any sidecar, including `llamacpp`
- [ ] 🔴 No authentication on any sidecar despite binding `0.0.0.0` — worst on `llamacpp`, which
      exposes a general-purpose LLM with tool calling and whose Java client cannot send a token
- [ ] 🔴 `FacedescriptionNode.URL` hardcodes `llamacpp`'s port but needs a vision model — make it an
      option
- [ ] 🟡 `DEVICE` is unprefixed and shared across three sidecars — namespace it, as `llamacpp` does
- [ ] 🟡 `<NAME>_PORT` is honoured by `run.sh` but ignored by `server.py` — read it in Python
- [ ] 🟡 `ideogram-sidecar` has no `setup.sh`/`run.sh` and no host/port variable
- [ ] 🟡 `sidecars/README.md` contradicts the verified per-sidecar findings — reconcile it
- [ ] 🟡 The six older per-sidecar specs still link to the renamed `../features/pipeline-nodes/`
- [ ] 🟡 No in-repo **vision** sidecar: `vlm`, `captioning` and `facedescription` still need an
      external OpenAI-compatible endpoint
- [ ] 🟢 No node calls `GET /health`; only `llamacpp`'s `run.sh` does. Wire a readiness probe into
      the node clients

---

_Git HEAD revision: `827cd2cb`_
_Last updated: 2026-08-04 (added `sidecars/llamacpp` — seventh sidecar, first container-based one and the first verified live)_
