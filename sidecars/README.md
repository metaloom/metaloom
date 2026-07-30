# Cortex Node Sidecars

Model-server **sidecars** that Cortex nodes call over HTTP. A sidecar keeps a model (and its heavy
Python/GPU runtime) out of the JVM: the Java node stays a thin HTTP client, and the model runs in its
own process next to the worker — locally during development, or as a co-located container/pod in
production.

```
   Cortex worker (JVM)                         sidecar (this folder)
   ┌───────────────────┐   HTTP (localhost)   ┌────────────────────────┐
   │ TtsNode / …        │ ───────────────────▶ │ FastAPI model server    │
   │ pure HTTP client   │ ◀─────────────────── │ loads + runs the model  │
   └───────────────────┘                       └────────────────────────┘
```

## Sidecars in this folder

| Folder | Node | Serves | Default port |
|--------|------|--------|--------------|
| [`tts/`](./tts) | `tts` (`io.metaloom.cortex.node.tts`) | Text-to-speech — Orpheus/Kartoffel (DE), Kokoro (EN), `POST /v1/tts` | `9100` |
| [`sentiment/`](./sentiment) | `sentiment` (`io.metaloom.cortex.node.sentiment`) | Sentiment analysis — german-sentiment-bert (DE), twitter-roberta (EN), `POST /v1/sentiment` | `9110` |
| [`depth/`](./depth) | `depthmap` (`io.metaloom.cortex.node.depthmap`) | Monocular depth — Depth-Anything-V2-Small (relative), ZoeDepth (metric), `POST /v1/depth` | `9120` |
| [`ideogram-sidecar/`](./ideogram-sidecar) | `imagegen` (`io.metaloom.cortex.node.imagegen`) | Image generation — SDXL-Turbo by default, Ideogram 4 if you accept its gate, `POST /generate` + `/remix` | `9200` |
| [`mage-flow-sidecar/`](./mage-flow-sidecar) | `imagegen` (same node, `port` option) | Image generation + instruction editing — Mage-Flow 4B, **MIT weights**, `POST /generate` + `/remix` | `9210` |
| [`ltx2-sidecar/`](./ltx2-sidecar) | `videogen` (`io.metaloom.cortex.node.videogen`) | Text/image-to-video — LTX-2 19B, `POST /generate` + `/animate` → `video/mp4` | `9220` |

Two sidecars serve the same `imagegen` node on purpose. The ideogram one's practical
default is SDXL-Turbo, whose weights are **non-commercial** (as are Ideogram 4's, which
are additionally gated); `mage-flow-sidecar` is the first image model here whose weights
are MIT and can therefore ship in a commercial deployment. It is also the stronger model
(GenEval 0.90 vs SDXL-Turbo's ~0.55). Point the node at one or the other by changing its
`port` option — the HTTP contract is identical.

Each sidecar directory is self-contained (`setup.sh`, `run.sh`, `server.py`, `requirements.txt`,
`README.md`) and location-independent — the scripts `cd` to their own directory, so moving them here
required no edits.

## Nodes that do NOT have an in-repo sidecar

Not every model-backed node ships a sidecar here — several reuse an external server or run in-process:

| Node | Where the model runs |
|------|----------------------|
| `whisper` (ASR) | whisper.cpp, **in-process** in the worker — no sidecar |
| `llm` | An external **Ollama** endpoint |
| `captioning` / `vlm` | An external **vLLM** / Ollama endpoint |
| `facedescription` | An external Ollama endpoint |

When one of these grows an in-repo model server (e.g. a future `asr`, `vlm` or `llm` sidecar), add it
here as `sidecars/<name>/` and list it in the table above. See
[`spec/plans/imagegen-node.md`](../spec/plans/imagegen-node.md) for the plan the two image sidecars
came out of.

## Deployment

In production a sidecar runs alongside the Cortex worker (same pod / host). The node points at it via
its own host/port options (for `tts`: `ttsHost` / `ttsPort`, default `localhost:9100`; for
`sentiment`: `sentimentHost` / `sentimentPort`, default `localhost:9110`; for `imagegen`: `host` /
`port`, default `localhost:9200` — set `9210` for `mage-flow-sidecar`), while the sidecar binds
its listener via its own env vars (`TTS_HOST` / `TTS_PORT`, `SENTIMENT_HOST` / `SENTIMENT_PORT`,
`DEPTH_HOST` / `DEPTH_PORT`, `MAGEFLOW_HOST` / `MAGEFLOW_PORT`, `LTX2_HOST` / `LTX2_PORT`). See the
[Cortex Helm chart](../helm/cortex) for deploying workers. `ltx2-sidecar` is the model server for the
`videogen` Cortex node (`cortex/nodes/video-generation`).

One caveat specific to the generative-media sidecars: they are far heavier than the rest. Mage-Flow
holds 17.5 GB of bf16 weights and peaks near 20 GB, and LTX-2 is a 19B model that only fits a
commodity card when quantized (fp8 for 24 GB, fp4 + offload for 12 GB) — both need a dedicated GPU
rather than a share of one that also runs `whisper` or `depthmap`.
