# ideogram-sidecar

FastAPI sidecar that serves a [Diffusers](https://github.com/huggingface/diffusers)
image model behind a small, **model-agnostic** HTTP contract. It is the Python
PoC from `metaloom/spec/plans/imagegen-node.md` (Part A) and the model server for
the Cortex `imagegen` node — the Java `ImageGenNode` is a pure HTTP client of it,
mirroring the `CaptioningNode → SmolVLM` / `TtsNode → tts server` pattern.

## HTTP contract

| Method | Path        | Body                                                   | Response    |
|--------|-------------|--------------------------------------------------------|-------------|
| GET    | `/health`   | —                                                      | JSON status |
| POST   | `/generate` | `{prompt, width?, height?, seed?, steps?, guidance?}`  | `image/png` |
| POST   | `/remix`    | `{image_b64, prompt, strength?, seed?, steps?, guidance?}` | `image/png` |

`/generate` is text-to-image (smoke tests). `/remix` is image-to-image and is the
endpoint the node calls.

## Model selection

The *intended* backing model is **Ideogram 4.0** (`ideogram-ai/ideogram-4-nf4`),
but its weights are **gated** on Hugging Face and require ~24 GB CUDA, an
`HF_TOKEN`, and accepting the *Ideogram 4 Non-Commercial Model Agreement*.

The sidecar is deliberately pluggable, so for an ungated PoC it **defaults to
SDXL-Turbo** — no login, ~8-12 GB VRAM, full image in 1-4 steps. Swap models
purely via env:

```bash
# default (ungated PoC)
IMAGEGEN_MODEL=stabilityai/sdxl-turbo

# Ideogram 4.0 (gated — needs the steps below)
IMAGEGEN_MODEL=ideogram-ai/ideogram-4-nf4
```

### Using the gated Ideogram weights
```bash
pip install -U "huggingface_hub[cli]"
huggingface-cli login              # paste an HF token
# accept the gate at https://huggingface.co/ideogram-ai/ideogram-4-nf4
export HF_TOKEN=hf_...
export IMAGEGEN_MODEL=ideogram-ai/ideogram-4-nf4
```

## Environment variables

| Var                    | Default                    | Notes                                  |
|------------------------|----------------------------|----------------------------------------|
| `IMAGEGEN_MODEL`       | `stabilityai/sdxl-turbo`   | HF repo or local path                  |
| `IMAGEGEN_DEVICE`      | `cuda` if available        | torch device                           |
| `IMAGEGEN_DTYPE`       | `float16` on cuda          | `float16` \| `bfloat16` \| `float32`   |
| `IMAGEGEN_STEPS`       | `4`                        | default inference steps (turbo-tuned)  |
| `IMAGEGEN_GUIDANCE`    | `0.0`                      | default guidance (turbo wants 0)       |
| `CUDA_VISIBLE_DEVICES` | —                          | pin a GPU, e.g. `1` for the 2nd card   |
| `HF_TOKEN`             | —                          | only for gated models                  |

## Run

```bash
python -m venv venv && ./venv/bin/pip install -r requirements.txt
CUDA_VISIBLE_DEVICES=1 ./venv/bin/uvicorn server:app --host 0.0.0.0 --port 9200
```

## Smoke test

```bash
curl -s localhost:9200/health | jq

# text-to-image
curl -s -X POST localhost:9200/generate \
  -H 'content-type: application/json' \
  -d '{"prompt":"a red panda astronaut, studio lighting","seed":42}' \
  -o out.png

# image-to-image / remix
IMG=$(base64 -w0 out.png)
curl -s -X POST localhost:9200/remix \
  -H 'content-type: application/json' \
  -d "{\"image_b64\":\"$IMG\",\"prompt\":\"same panda, cyberpunk neon\",\"strength\":0.6}" \
  -o remix.png
```

## Docker

```bash
docker build -t ideogram-sidecar .
docker run --gpus all -p 9200:9200 \
  -e IMAGEGEN_MODEL=stabilityai/sdxl-turbo \
  -v $HOME/.cache/huggingface:/root/.cache/huggingface \
  ideogram-sidecar
```

## License caveat

The sidecar code is MIT/Apache-style. **Model weights carry their own licence.**
Ideogram 4.0 weights are non-commercial; using them in a commercial metaloom
deployment would violate that licence. The default SDXL-Turbo weights are
likewise Stability's non-commercial community licence. Any Diffusers-compatible
model can back this same contract — pick one whose licence fits your use.
