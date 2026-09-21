# TEI sidecar — text embeddings for semantic and transcript search

[Hugging Face text-embeddings-inference](https://github.com/huggingface/text-embeddings-inference)
serving **BGE-M3**, behind the same OpenAI `POST /v1/embeddings` shape Loom already speaks.

```bash
./run.sh     # starts it on :8091, waits until it answers a real embedding
./stop.sh
```

## Why this exists next to `llamacpp-embeddings`

Both serve the same route and Loom cannot tell them apart — it only knows the protocol. They
differ in what they are good at:

| | [`llamacpp-embeddings`](../llamacpp-embeddings) | this one |
|---|---|---|
| Model | nomic-embed-text-v1.5, 768 dims | BGE-M3, 1024 dims |
| Languages | English | ~100, one shared vector space |
| Context | 2048 tokens | 8192 tokens |
| Runtime | llama.cpp, GGUF | TEI, safetensors, batched |

Transcript search is what tipped it. A transcript window is a minute of speech in whatever
language the audio happened to be in, and it is searched with a phrase typed in whatever language
the person at the keyboard thinks in. BGE-M3 puts both in one space; an English-only embedder
answers the same question with noise. The long context also means a window is never truncated,
which a 2048-token limit cannot promise for a minute of dense dialogue.

Keep whichever suits the corpus. Running both at once is cheap and is how you compare them: the
model name is stored on every vector and is part of its identity, so the two sets of vectors sit
side by side and the old ones are dropped once you are satisfied.

## Turning search on

```bash
LOOM_SEARCH_SEMANTIC_ENABLED=true
LOOM_SEARCH_EMBED_URL=http://127.0.0.1:8091/v1
LOOM_SEARCH_EMBED_MODEL=BAAI/bge-m3
LOOM_SEARCH_EMBED_DIMENSIONS=1024
LOOM_VECTOR_INDEX_PROVIDER=lucene
```

`LOOM_VECTOR_INDEX_PROVIDER` is load-bearing: the embeddings are stored in Postgres either way,
but nearest-neighbour queries run against the vector index, and with no index bound the server
advertises no `SEMANTIC` capability and the UI's mode toggle never appears.

Whether it took is visible at `GET /api/v1/search/status`: `capabilities` lists `SEMANTIC` and
`HYBRID`, and `dirtyCount` is how many documents are still waiting to be embedded. Loom works
through that backlog on its own — there is nothing to trigger.

## What gets embedded

Every `search_document` row, which since `V2.110` includes **one row per minute of every
transcript** rather than one row per transcript. That is the change that makes semantic transcript
search mean anything: a single embedding of a 43-minute episode is an average of forty minutes of
unrelated conversation and matches nothing in particular. A minute of dialogue is a coherent
thing to compare a query against, and it carries the timecode to jump to.

For 22 episodes of television that is roughly a thousand vectors — a few minutes of CPU, once.

🔴 **Raise the drain rate, or that few minutes becomes half an hour.** Loom's default pass is
`LOOM_SEARCH_EMBED_BATCH_SIZE=16` every `LOOM_SEARCH_EMBED_SYNC_INTERVAL_MS=15000` — 64 documents
a minute, whatever the host can do. On metaloom.sky the model was never the bottleneck: TEI
reported `inference_time` of 65–200ms throughout while `queue_time` ran to 67 seconds. Use
`64` / `5000` for an initial index.

🔴 **Keep the batch size at or under TEI's `--max-client-batch-size`,** which defaults to **32**.
Above it TEI answers `413 {"message":"batch size 64 > maximum allowed batch size 32"}` and the
*whole pass* fails, so the backlog does not shrink at all — the log says so plainly, but only if
you read it rather than watching the count. Either keep `LOOM_SEARCH_EMBED_BATCH_SIZE` at 32 or
raise both together (`--max-client-batch-size 64`).

🔴 **And raise `LOOM_SEARCH_EMBED_TIMEOUT_MS` above its 10s default.** A queued request abandoned
at 10s does not cancel the work TEI is already committed to, so short timeouts lengthen the queue
they are reacting to — and the capability probe (one real embedding, see
`OpenAiTextEmbedder.isAvailable`) never succeeds, so `SEMANTIC` is never advertised and the UI's
mode toggle never appears. 120000 is comfortable.

## Changing the model

🔴 **Set `LOOM_SEARCH_EMBED_DIMENSIONS` to the new model's output size in the same change.** A
reply of the wrong length is rejected rather than stored, because mixing vector lengths in one
index segment produces distances that are numbers with no meaning. `run.sh` counts the numbers in
the probe response and warns when they disagree with `TEI_DIMENSIONS`, which is the cheapest place
to catch it — the alternative is noticing months later that a subset of the catalogue never
matches anything.

```bash
TEI_MODEL=BAAI/bge-small-en-v1.5 TEI_DIMENSIONS=384 ./run.sh
```

## Knobs

| Variable | Default | Meaning |
|---|---|---|
| `TEI_PORT` | `8091` | Port. 8090 belongs to the llama.cpp embeddings sidecar, 8080 to the chat one |
| `TEI_MODEL` | `BAAI/bge-m3` | Any model TEI supports |
| `TEI_DIMENSIONS` | `1024` | Only used to check the probe reply; the model decides the real number |
| `TEI_GPU` | `none` | CPU by default — the GPU on this box belongs to whisper and facedetect |
| `TEI_IMAGE_TAG` | `cpu-latest` | Use `latest` (CUDA) together with `TEI_GPU=all` |
| `TEI_CACHE` | `/extra/cache`, else `~/.cache` | Weights live here, shared with the other sidecars |
| `TEI_NAME` | `loom-tei` | Container name |
| `TEI_RUNTIME` | docker, else podman | Container runtime |
| `TEI_EXTRA_ARGS` | — | Passed to the TEI server verbatim |

## Deploying it beside Loom

The sidecar is reached over the host network, so on a single-box deployment it goes in the same
compose file as Loom and Cortex:

```yaml
  tei:
    image: ghcr.io/huggingface/text-embeddings-inference:cpu-latest
    command: ["--model-id", "BAAI/bge-m3", "--auto-truncate"]
    volumes:
      - /opt/metaloom/hf-cache:/data
    environment:
      - HF_HOME=/data
    ports:
      - "8091:80"
    restart: unless-stopped
```

and Loom gets `LOOM_SEARCH_EMBED_URL=http://tei:80/v1` (the service name, not `127.0.0.1` — the
loopback address inside Loom's container is Loom).

🔴 **Gate Loom on the sidecar being *healthy*, not on it having started.** Loom probes the
embedding host **once, at boot**, and if it does not answer it disables semantic search for the
life of the process — the log line is `the document embedding pass was not started`, and nothing
retries. BGE-M3 takes around five minutes to warm on CPU, so restarting the two together loses
semantic search silently until somebody restarts Loom again. A plain `depends_on: [tei]` does not
help: it waits for the container to start, not for the model to load.

```yaml
  tei:
    # A real embedding, not /health — a server that is up with no usable model answers /health
    # perfectly well, which is the same reason Loom's own probe embeds a word.
    healthcheck:
      test: ["CMD", "curl", "-sf", "-X", "POST", "http://127.0.0.1:80/v1/embeddings",
             "-H", "Content-Type: application/json",
             "-d", "{\"input\":\"ping\",\"model\":\"BAAI/bge-m3\"}"]
      interval: 15s
      timeout: 30s
      retries: 60
      start_period: 60s

  loom:
    depends_on:
      tei:
        condition: service_healthy
```
