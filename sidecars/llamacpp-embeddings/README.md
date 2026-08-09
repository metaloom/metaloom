# llama.cpp embeddings sidecar

The text-embedding host behind Loom's **semantic and hybrid search**.

```bash
./run.sh     # starts it on :8090, waits until healthy
./stop.sh
```

## What it is

The same `ghcr.io/ggml-org/llama.cpp` server image as [`../llamacpp`](../llamacpp), started a
second time with an embedding model and `--embeddings`. `run.sh` is a wrapper around that
sidecar's script rather than a copy of it, so runtime detection, GPU flags, the shared weights
cache and the readiness probe stay in one place.

Two containers rather than one because the two jobs want different models: a chat model cannot
produce sentence embeddings, and an embedding model cannot answer a chat completion. They share
the image and the weights cache, so the second one costs a container and a few hundred MB of
model, not a second stack.

Anything else that speaks the OpenAI `POST /v1/embeddings` shape works just as well — Ollama, TEI,
or OpenAI itself. Loom only knows the protocol.

## Turning search on

```bash
LOOM_SEARCH_SEMANTIC_ENABLED=true
LOOM_SEARCH_EMBED_URL=http://127.0.0.1:8090/v1
LOOM_SEARCH_EMBED_MODEL=nomic-ai/nomic-embed-text-v1.5-GGUF:Q8_0
LOOM_SEARCH_EMBED_DIMENSIONS=768
LOOM_VECTOR_INDEX_PROVIDER=lucene
```

`LOOM_VECTOR_INDEX_PROVIDER` is load-bearing: the embeddings are stored in Postgres either way,
but nearest-neighbour queries run against the vector index, and with no index bound the server
advertises no `SEMANTIC` capability.

Loom probes the host at boot with a real embedding call — a reachable server with no embedding
model loaded answers `/health` perfectly well and then fails every actual request, so nothing less
would tell the truth. Whether it worked is visible at `GET /api/v1/search/status`: `capabilities`
lists `SEMANTIC` and `HYBRID`, and `dirtyCount` is the number of assets still waiting to be
embedded.

## Changing the model

🔴 **Set `LOOM_SEARCH_EMBED_DIMENSIONS` to the new model's output size in the same change.** A reply
of the wrong length is rejected rather than stored, because mixing vector lengths in one index
segment produces distances that are numbers with no meaning.

The model name is stored on every vector and is part of its identity, so switching models is safe
and reversible: the new vectors land beside the old ones, both sets exist while you compare them,
and the old ones are dropped when you are satisfied. Re-embedding the catalog happens on its own —
Loom notices the vectors are missing for the new model and works through the backlog.

```bash
LLAMACPP_EMBED_MODEL=<hf-repo>:<quant> ./run.sh
```

## Knobs

| Variable | Default | Meaning |
|---|---|---|
| `LLAMACPP_EMBED_PORT` | `8090` | Port. 8080 belongs to the chat sidecar |
| `LLAMACPP_EMBED_MODEL` | `nomic-ai/nomic-embed-text-v1.5-GGUF:Q8_0` | GGUF embedding model, 768 dimensions |
| `LLAMACPP_EMBED_GPU` | `none` | CPU is enough for a small embedder; set `all` to offload |
| `LLAMACPP_EMBED_VERSION` | `server` | Image tag; use `server-cuda` with a GPU |
| `LLAMACPP_EMBED_POOLING` | `mean` | llama.cpp pooling type |
| `LLAMACPP_EMBED_NAME` | `loom-llamacpp-embeddings` | Container name |

Every variable the parent sidecar understands still applies.
