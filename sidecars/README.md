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
| [`sam2/`](./sam2) | `sam2` (`io.metaloom.cortex.node.sam2`) | Segmentation — SAM 2.1 Hiera, `POST /v1/segment` (segment-everything or box-prompted) + `/v1/track` (video propagation) | `9130` |
| [`asr/`](./asr) | none yet | Speech recognition with word + segment time codes — Whisper large-v3-turbo, Parakeet-TDT-v3 (CPU), Voxtral Realtime (streaming). OpenAI-style `POST /v1/audio/transcriptions` + `WS /v1/realtime`. Ships as a container (`container.sh`) | `9140` |
| [`ideogram-sidecar/`](./ideogram-sidecar) | `imagegen` (`io.metaloom.cortex.node.imagegen`) | Image generation — SDXL-Turbo by default, Ideogram 4 if you accept its gate, `POST /generate` + `/remix` | `9200` |
| [`mage-flow-sidecar/`](./mage-flow-sidecar) | `imagegen` (same node, `port` option) | Image generation + instruction editing — Mage-Flow 4B, **MIT weights**, `POST /generate` + `/remix` | `9210` |
| [`ltx2-sidecar/`](./ltx2-sidecar) | `videogen` (`io.metaloom.cortex.node.videogen`) | Text/image-to-video — LTX-2 19B, `POST /generate` + `/animate` → `video/mp4` | `9220` |
| [`qwen-image-sidecar/`](./qwen-image-sidecar) | `imagegen` (same node, `port` option) + MCP `generate_image` | Multi-image editing and prompted masks — Qwen-Image-2.1, **non-commercial weights**, `POST /generate` + `/remix` + `/edit` + `/mask` | `9230` |
| [`llamacpp/`](./llamacpp) | `llm` (`io.metaloom.cortex.node.llm`), `translate`, `guard` | LLM — llama.cpp's official server image, OpenAI chat-completions at `/v1` | `8080` |
| [`llamacpp-embeddings/`](./llamacpp-embeddings) | none — **Loom itself**, for semantic search | Text embeddings — nomic-embed-text-v1.5, OpenAI `POST /v1/embeddings` | `8090` |
| [`tei/`](./tei) | none — **Loom itself**, for semantic and transcript search | Text embeddings — BGE-M3, multilingual, 8192-token context, OpenAI `POST /v1/embeddings` | `8091` |

Three of these break the pattern the Python sidecars share — `llamacpp`, `llamacpp-embeddings` and
`tei` are shell scripts around an official upstream image rather than a FastAPI server of ours,
they run under **docker or podman**, and none of them has a `.venv` or a `server.py`. `llamacpp`
sits on `8080` because that is already `AbstractLlmNodeOptions.DEFAULT_OPENAI_URL`, so the `llm`
node finds it with no configuration.

The two embedding sidecars are also the only ones **no Cortex node talks to**: they serve *Loom*,
which embeds `search_document` rows for semantic search. They serve the same route with different
models, so running both at once is how you compare them — see [`tei/README.md`](./tei) for which
to pick and why transcript search wants the multilingual one.

Three sidecars serve the same `imagegen` node on purpose. The ideogram one's practical
default is SDXL-Turbo, whose weights are **non-commercial** (as are Ideogram 4's, which
are additionally gated); `mage-flow-sidecar` is the first image model here whose weights
are MIT and can therefore ship in a commercial deployment. It is also the stronger model
(GenEval 0.90 vs SDXL-Turbo's ~0.55). Point the node at one or the other by changing its
`port` option — the HTTP contract is identical.

`qwen-image-sidecar` is the third, and it is not interchangeable in the same way. It
answers `/generate` and `/remix` like the other two, so it is a drop-in for those, but it
**also** answers `/edit` and `/mask` — combining up to ten pictures into one, and confining
a change to a region you name in words. Nothing else here does either. The catch is its
licence: Qwen-Image-2.1 is **non-commercial** (Qwen Research License), so the only backend
that can do those two things is the one a commercial deployment may not use. Measured VRAM is
~46 GB at the default resolution and ~66 GB at 2K — well above its 33 GB of weights — so it
wants an 80 GB card, or `QWENIMAGE_OFFLOAD=1` below ~48 GB.

Each sidecar directory is self-contained (`setup.sh`, `run.sh`, `server.py`, `requirements.txt`,
`README.md` — `llamacpp/` has no `server.py`/`requirements.txt`, and adds a `stop.sh`) and
location-independent: the scripts `cd` to their own directory, so moving them here required no edits.

## Nodes that do NOT have an in-repo sidecar

Not every model-backed node ships a sidecar here — several reuse an external server or run in-process:

| Node | Where the model runs |
|------|----------------------|
| `whisper` (ASR) | whisper.cpp, **in-process** in the worker. [`asr/`](./asr) serves the same job over HTTP (plus Parakeet and Voxtral, realtime, and a `loom` response format that is `WhisperResult` JSON), but the node does not call it yet |
| `captioning` / `vlm` | An external **OpenAI-compatible** vision endpoint (default `:8000`) |
| `guard` | This same sidecar, with a **guardrail** model loaded instead of a chat model — every text guard family (Llama Guard 3/4, ShieldGemma, Granite Guardian) has published GGUF quantizations. It calls `POST /v1/completions` with `logprobs`, not `/v1/chat/completions`, because a guard model's answer is the probability of its decision token. ⚠️ **Image screening cannot use this sidecar**: llama.cpp serves neither multimodal guard model — no `mmproj` projector is published for Llama Guard 4 and `shieldgemma-2-4b` has no GGUF conversion — so `guard` on pictures needs vLLM |
| `facedescription` | An external **OpenAI-compatible** vision endpoint. ⚠️ Its URL is **hardcoded** to `http://127.0.0.1:8080/v1` — the same port `llamacpp/` uses. A text-only model there answers without ever seeing the image |

When one of these grows an in-repo model server (as `asr` now has, or a future `vlm` sidecar), add it
here as `sidecars/<name>/` and list it in the table above. See
[`spec/plans/imagegen-node.md`](../spec/plans/imagegen-node.md) for the plan the two image sidecars
came out of.

## Deployment

In production a sidecar runs alongside the Cortex worker (same pod / host). The node points at it via
its own host/port options (for `tts`: `ttsHost` / `ttsPort`, default `localhost:9100`; for
`sentiment`: `sentimentHost` / `sentimentPort`, default `localhost:9110`; for `imagegen`: `host` /
`port`, default `localhost:9200` — set `9210` for `mage-flow-sidecar`, `9230` for
`qwen-image-sidecar`), while the sidecar binds
its listener via its own env vars (`TTS_HOST` / `TTS_PORT`, `SENTIMENT_HOST` / `SENTIMENT_PORT`,
`DEPTH_HOST` / `DEPTH_PORT`, `SAM2_HOST` / `SAM2_PORT`, `MAGEFLOW_HOST` / `MAGEFLOW_PORT`,
`QWENIMAGE_HOST` / `QWENIMAGE_PORT`,
`LTX2_HOST` / `LTX2_PORT`,
`LLAMACPP_HOST` / `LLAMACPP_PORT`). See the [Cortex Helm chart](../helm/cortex) for deploying
workers. `ltx2-sidecar` is the model server for the `videogen` Cortex node
(`cortex/nodes/video-generation`).

`llamacpp` is the exception on the node side too: it needs no host/port option because its default
port *is* the node's default (`nodes.llm.openaiUrl`, `http://127.0.0.1:8080/v1`). Move it with
`LLAMACPP_PORT` and set `openaiUrl` to match.

One caveat specific to the generative-media sidecars: they are far heavier than the rest. Mage-Flow
holds 17.5 GB of bf16 weights and peaks near 20 GB, and LTX-2 is a 19B model that only fits a
commodity card when quantized (fp8 for 24 GB, fp4 + offload for 12 GB) — both need a dedicated GPU
rather than a share of one that also runs `whisper` or `depthmap`.
