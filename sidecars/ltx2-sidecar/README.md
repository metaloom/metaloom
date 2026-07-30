# LTX-2 video sidecar

The first **video-generation** sidecar in this folder. It serves
[Lightricks/LTX-2](https://huggingface.co/Lightricks/LTX-2) through HuggingFace
`diffusers` (`LTX2Pipeline`) and produces short **MP4** clips **with synchronised audio**
from a text prompt or a start image.

It follows the heavy-GPU sidecar template: the HTTP skeleton mirrors
[`mage-flow-sidecar`](../mage-flow-sidecar) (single-flight GPU lock, lazy load, JSON
provenance twin). Unlike the image sidecars it emits `video/mp4` (with an audio track),
not `image/png`.

There is **no Cortex node for it yet** — a future `videogen` node + `VideoGenClient`
would be a thin HTTP client of the contract below (see *Follow-ups*).

## Quick start

```bash
./setup.sh                       # venv; installs torch (cu128) then requirements.txt
./run.sh                         # serves on :9220 (first call loads the model — see below)
./.venv/bin/python generate_examples.py   # renders the MetaLoom demo clip (with audio)
```

Health check:

```bash
curl -s localhost:9220/health | jq
```

## HTTP contract

```
GET  /health                                                              -> JSON
POST /generate  {prompt, negative_prompt?, width?, height?, num_frames?,
                 fps?, seed?, steps?, guidance?}                          -> video/mp4
POST /animate   {image_b64, prompt, ...same...}                          -> video/mp4  (image-to-video)
POST /v1/generate  same body as /generate                                -> JSON
```

`/v1/generate` returns `{prompt, video_b64, model, quantize, width, height, numFrames,
fps, steps, guidance, seed, hasAudio, elapsedMs}` — the base64 MP4 plus the parameters
actually used, so a caller can record provenance.

Text-to-video uses `LTX2Pipeline`; image-to-video uses the separate
`LTX2ImageToVideoPipeline`, built from the already-quantized components so nothing loads
twice.

### Shape constraints (enforced, from the model card)

- `width` & `height` must be divisible by **32** — a non-multiple is snapped up and
  logged; out of `[32, 1280]` is a `400`.
- `num_frames` must be **`k*8 + 1`** (9, 17, 25, 33, … 121) — snapped up if not; past
  `LTX2_MAX_FRAMES` is a `400`.
- Empty `prompt` (or empty `image_b64` on `/animate`) is a `400`.

## The VRAM reality (read this before deploying)

**LTX-2 is not a 19B model — it is a ~46B system:** a 19B DiT transformer **plus a 27B
Gemma3 text encoder** (94 GB bf16 on disk) plus video/audio VAEs and a vocoder. The
model card's documented `from_pretrained(torch_dtype=bf16)` path is a **multi-GPU /
≥48 GB** path.

To fit a **single 24 GB card** this sidecar quantizes **both** heavy components to
bitsandbytes **nf4 4-bit**, streamed straight onto the GPU so the 94 GB text encoder never
materialises in CPU RAM, then `enable_model_cpu_offload()`s the pipeline:

- text encoder 27B → **~8 GB** nf4
- transformer 19B → **~10 GB** nf4
- **verified peak ~11 GB VRAM** on an RTX 4090 (sm_89), so it even has headroom.

This is the default (`LTX2_QUANTIZE=nf4` on CUDA). Set `LTX2_QUANTIZE=none` only on a
≥48 GB / multi-GPU host.

**Two dead ends** ruled out during bring-up (documented in `ltx_loader.py` so nobody
re-treads them):

1. The **fp8 single-file** transformers Lightricks ships (`ltx-2-19b-*-fp8.safetensors`)
   **upcast to bf16** in diffusers 0.39 — `LTX2VideoTransformer3DModel` is not
   fp8-quant-aware (it drops the `*_scale` tensors) — so they are just the 38 GB bf16
   path and OOM.
2. Quantizing **only** the transformer still OOMs: the 94 GB bf16 text encoder is loaded
   to CPU RAM and blows the RAM budget. It must be quantized too.

VAE tiling stays on (`LTX2_VAE_TILING=1`) — a whole-video VAE decode OOMs without it.

### First-load cost

The repo is large (~130 GB of components; the Gemma3 text encoder alone is ~94 GB), so
the first request downloads for a while, then quantizes both models (a couple of minutes),
then generates. Everything after is warm.

## Environment variables

| Var | Default | Meaning |
|-----|---------|---------|
| `LTX2_MODEL` | `Lightricks/LTX-2` | Checkpoint repo. |
| `LTX2_QUANTIZE` | `nf4` (CUDA) | `nf4` (fits 24 GB) or `none` (bf16, ≥48 GB). |
| `LTX2_DEVICE` | `cuda` if available | torch device. |
| `LTX2_DTYPE` | `bfloat16` (cuda) | Compute dtype / non-quantized components. |
| `LTX2_CPU_OFFLOAD` | `model` | `none` \| `model` \| `sequential`. |
| `LTX2_VAE_TILING` | `1` | VAE tiling on decode. |
| `LTX2_STEPS` | `40` | Inference steps. |
| `LTX2_GUIDANCE` | `4.0` | Guidance scale. |
| `LTX2_MAX_FRAMES` | `121` | Hard cap on `num_frames`. |
| `LTX2_HOST` / `LTX2_PORT` | `0.0.0.0` / `9220` | Listener bind. |
| `CUDA_VISIBLE_DEVICES` | — | Pin a GPU. |

## Demo clip

`generate_examples.py` drives the running server to write
`example-1-loom-constellation.mp4` next to it — a small (512×320, 33 frames @ 24fps, 20
steps) MetaLoom-themed cinemagraph of the loom-constellation logo (glowing petrol-teal
warp threads, five linked star nodes) **with a generated audio track**. It is kept small
because the nf4 model offloads a 27B text encoder + 19B transformer every step; bump the
resolution / frame count / steps once you have the budget. One reference clip is
committed; the rest are gitignored.

## Follow-ups (out of scope here)

- A Cortex `videogen` Java node + `VideoGenClient` + Dagger wiring + tests + website docs
  (the [CODING.md](../../spec/guidelines/CODING.md) definition of done for a real node).
- Two-stage / upscaler pipeline (the repo ships spatial & temporal upscalers) for higher
  resolution than a single-stage pass.
- Byte-ingest of produced video into Loom's binary subsystem (the same gap the image/TTS
  nodes have — they write to a local `*_bin` cache today).

## Licence

Model weights are under the **LTX-2 Community License** (see the model card / GitHub
repo). Review it before any commercial use — it is not one of the permissive licences.
