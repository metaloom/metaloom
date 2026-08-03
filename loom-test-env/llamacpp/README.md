# llama.cpp test server

A small local LLM for the tests that need one — today the Cortex `llm` node tests
(`cortex/nodes/llm`).

```bash
./start.sh     # pulls the image + model on first run, waits until healthy
./stop.sh
```

`start.sh` blocks until `/health` answers, so it is safe to chain:

```bash
./loom-test-env/llamacpp/start.sh && mvn -pl cortex/nodes/llm/core test
```

## What it runs

| | |
|---|---|
| Image | `ghcr.io/ggml-org/llama.cpp:server-cuda` (the official one) |
| Model | `ggml-org/Qwen3-4B-GGUF:Q4_K_M` — ~2.5 GB |
| Port | `8899` |
| Caches | `/extra/cache/llamacpp` (models), `/extra/cache/huggingface` |

Everything is overridable from the environment:

```bash
PORT=9000 GPU=1 MODEL=ggml-org/gemma-3-4b-it-GGUF:Q4_K_M ./start.sh
```

## Why this model

The node tests exercise tool calling, so the model has to support it natively.
Qwen3 ships a chat template that declares tools, and llama.cpp parses real
`tool_calls` out of it when started with `--jinja` (which `start.sh` passes).
Gemma 3 has no native tool support — llama.cpp falls back to a generic
JSON-in-the-prompt scheme for it, which is markedly less reliable. Qwen3-4B is
the smallest model that behaves properly here.

Note that `Qwen/Qwen3-4B-Instruct-2507-GGUF` — the obvious first choice — is not
publicly readable and returns `401` from `-hf`. The `ggml-org` conversion is.

## Why port 8899 and not 8888

8888 is where a hand-started llama.cpp usually sits. The test server picks a
different port so it can run alongside one rather than fighting it for the bind.

## How the tests reach it

llama.cpp speaks the OpenAI protocol against `/v1`, which is the only protocol
the nodes support. The endpoint and the skip-guard live in `TestEnv` in the llm
node's test sources; override the target with `-Dloom.test.llm.host` /
`-Dloom.test.llm.port`.

Tests **skip** rather than fail when nothing is listening, following the same
pattern as `LlmBackendAvailability` in `loom/core`. A green build therefore does not
by itself prove the llm tests ran — check for skips if that matters.
