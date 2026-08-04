# llama.cpp sidecar

The OpenAI-compatible LLM backend the Cortex `llm` node talks to.

```bash
./setup.sh   # optional - pre-pulls the image
./run.sh     # starts it, waits until /health answers
./stop.sh
```

`run.sh` blocks until the server is healthy, so it is safe to chain:

```bash
./sidecars/llamacpp/run.sh && mvn -pl cortex/nodes/llm/core test
```

## How this differs from the other sidecars

The rest of `sidecars/` are Python FastAPI servers with a `.venv` and a `server.py`.
This one is not: llama.cpp publishes an official server image, so the sidecar is three
shell scripts around `ghcr.io/ggml-org/llama.cpp:server-cuda`. There is nothing to
`pip install` and no code of ours in the request path.

The scripts run under **docker or podman**. `run.sh` picks whichever it finds first
(docker, then podman); force one with `LLAMACPP_RUNTIME=podman`.

## What it runs

| | |
|---|---|
| Image | `ghcr.io/ggml-org/llama.cpp:server-cuda` (official) |
| Model | `ggml-org/Qwen3-4B-GGUF:Q4_K_M` — ~2.5 GB |
| Port | `8080` |
| API | OpenAI chat-completions at `/v1`, health at `/health` |
| Caches | `<cache>/llamacpp` (models), `<cache>/huggingface`, where `<cache>` is `/extra/cache` when it exists, else `$HOME/.cache` |

### Why port 8080

`AbstractLlmNodeOptions.DEFAULT_OPENAI_URL` is `http://127.0.0.1:8080/v1`, so the `llm`
node reaches this sidecar with no option set. It sits outside the 9100–9220 block the
Python sidecars use on purpose — that block is ours, 8080 is llama.cpp's own default.

### Why this model

The node's tool-calling path needs a model whose chat template declares tools natively.
Qwen3 does, and llama.cpp parses real `tool_calls` out of it when started with `--jinja`
(which `run.sh` passes). Gemma 3 has no native tool support and falls back to a generic
JSON-in-the-prompt scheme, which is markedly less reliable.

`Qwen/Qwen3-4B-Instruct-2507-GGUF` — the obvious first choice — is not publicly readable
and returns `401` from `-hf`. The `ggml-org` conversion is.

For a much stronger model on a big card:

```bash
LLAMACPP_MODEL=ibm-granite/granite-4.1-30b-GGUF LLAMACPP_CTX_SIZE=32768 ./run.sh
```

## Environment

Every variable is prefixed `LLAMACPP_` — unlike the shared, unprefixed `DEVICE` the
Python sidecars read.

| Variable | Default | Meaning |
|---|---|---|
| `LLAMACPP_RUNTIME` | docker, else podman | Container runtime |
| `LLAMACPP_NAME` | `loom-llamacpp` | Container name |
| `LLAMACPP_IMAGE` | `ghcr.io/ggml-org/llama.cpp` | Image repository |
| `LLAMACPP_VERSION` | `server-cuda` | Tag. Use `server` for CPU-only |
| `LLAMACPP_HOST` | `0.0.0.0` | Host interface to publish on |
| `LLAMACPP_PORT` | `8080` | Host port; container always listens on 8080 |
| `LLAMACPP_MODEL` | `ggml-org/Qwen3-4B-GGUF:Q4_K_M` | `-hf` model reference |
| `LLAMACPP_CTX_SIZE` | `8192` | `--ctx-size`. Must be ≥ the node's `contextWindow` |
| `LLAMACPP_GPU` | `all` | `all`, a bare index (`0`), or `none` for CPU |
| `LLAMACPP_GPU_ARGS` | — | Replaces the derived GPU flags wholesale, e.g. `--device nvidia.com/gpu=all` |
| `LLAMACPP_CACHE` | `/extra/cache` if it exists, else `$HOME/.cache` | Parent of the `llamacpp/` and `huggingface/` cache dirs. The `/extra/cache` preference is what makes this container and `loom-test-env/llamacpp` share one model download |
| `LLAMACPP_STARTUP_TIMEOUT` | `900` | Seconds to wait for `/health` |
| `LLAMACPP_EXTRA_ARGS` | — | Extra flags appended to the `llama-server` command line |

GPU flags are derived per runtime: podman gets CDI (`--device nvidia.com/gpu=…`), docker
gets `--gpus`. Docker also understands CDI from 25.0 on, but only when a CDI spec is
installed — hence `LLAMACPP_GPU_ARGS` for that case.

## Pointing the node at it

```yaml
nodes:
  llm:
    openaiUrl: http://127.0.0.1:8080/v1
    contextWindow: 8192
```

`translate` reads the same option and the same default, so it is served too.

The vision nodes are **not**: `vlm` and `captioning` default to `:8000` and need a vision
model. Watch out for `facedescription` though — it hardcodes
`http://127.0.0.1:8080/v1` (`FacedescriptionNode.URL`, not an option) and expects a
multimodal backend there. With a text-only model running here it will get answers from a
model that never saw the image. Serve a multimodal GGUF (`--mmproj`) if you need both on
one host.

## Smoke test

```bash
curl -s http://127.0.0.1:8080/health
curl -s http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Say hello in one word."}]}' | jq -r '.choices[0].message.content'
```

## No auth

Like every other sidecar here, this binds without a token or TLS. llama.cpp does support
`--api-key`, but the Java side has no way to send an `Authorization` header
(`LlmEndpoint` carries a URL and nothing else), so enabling it would just break the node.
Keep the listener on a private interface — `LLAMACPP_HOST=127.0.0.1` if the worker is on
the same host.

## Related

* `loom-test-env/llamacpp/` — the same image on port `8899` for the `llm` node's tests.
  It is deliberately a separate container so a test run does not fight this one for 8080.
* [`spec/sidecars/LLAMACPP_SIDECAR.md`](../../spec/sidecars/LLAMACPP_SIDECAR.md) — the spec.
