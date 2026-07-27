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

When one of these grows an in-repo model server (e.g. a future `asr`, `vlm`, `llm` or `imagegen`
sidecar), add it here as `sidecars/<name>/` and list it in the table above. See
[`spec/plans/imagegen-node.md`](../spec/plans/imagegen-node.md) for a sidecar that is planned.

## Deployment

In production a sidecar runs alongside the Cortex worker (same pod / host). The node points at it via
its own host/port options (for `tts`: `ttsHost` / `ttsPort`, default `localhost:9100`; for
`sentiment`: `sentimentHost` / `sentimentPort`, default `localhost:9110`), while the sidecar binds
its listener via its own env vars (`TTS_HOST` / `TTS_PORT`, `SENTIMENT_HOST` / `SENTIMENT_PORT`). See the
[Cortex Helm chart](../helm/cortex) for deploying workers.
