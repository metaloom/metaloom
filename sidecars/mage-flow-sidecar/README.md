# mage-flow-sidecar

FastAPI sidecar serving **[Mage-Flow](https://github.com/microsoft/Mage)** — Microsoft
Research's 4B native-resolution image model (NR-MMDiT + Mage-VAE, rectified flow,
released 2026-07-21, **MIT**). It speaks the same model-agnostic HTTP contract as
[`../ideogram-sidecar`](../ideogram-sidecar), so the existing Cortex `imagegen` node
(`io.metaloom.cortex.node.imagegen`) can be pointed at it by changing **one option** —
`port` — and nothing else.

Why it exists next to the ideogram sidecar rather than replacing its config: that
sidecar's practical default is SDXL-Turbo, whose weights are **non-commercial**
(Ideogram 4's likewise, plus gated and ~24 GB). Mage-Flow's checkpoints are MIT, which
makes them the first image model in this repo that a commercial metaloom deployment can
actually ship. On benchmarks it is also not a compromise: GenEval **0.90**, the highest
of any open-weights model, above FLUX.2-dev 32B (0.87) and Qwen-Image 20B (0.87).

## HTTP contract

| Method | Path             | Body                                                             | Response    |
|--------|------------------|------------------------------------------------------------------|-------------|
| GET    | `/health`        | —                                                                | JSON status |
| POST   | `/generate`      | `{prompt, width?, height?, seed?, steps?, cfg?, neg_prompt?}`     | `image/png` |
| POST   | `/v1/generate`   | same, plus `n?`                                                  | JSON        |
| POST   | `/remix`         | `{image_b64, prompt, seed?, steps?, cfg?, max_size?}`             | `image/png` |

`/generate` and `/remix` return raw PNG bytes — that is the shape `ImageGenClient`
expects. `/v1/generate` returns JSON (`images` as base64 PNGs) and additionally carries
the resolved model id, the seed actually used, the step count and the server-side
timing, which is what a node needs to record provenance for a generated asset. Every
PNG also carries those values in its `tEXt` chunks, so a file found on disk later still
knows what produced it.

`n` generates several variants in **one packed transformer forward** (the model's
native-resolution packing, not a loop) — 3×768² takes ~2.8 s against ~1.0 s for one.
Each variant raises peak VRAM, hence the `MAGEFLOW_MAX_BATCH` cap.

## Two things that will otherwise cost you an afternoon

**1. A blocked prompt returns a plain white image, not an error.** Mage-Flow's content
filter is mandatory with no opt-out, `FilterVerdict.banner()` deliberately returns `""`,
and `make_refusal_image()` is a blank white canvas. The filter is also *fail-closed*, so
a crash inside the classifier looks identical. This server unmasks that: a uniformly
white result is re-screened and turned into a `422` carrying the filter's own verdict.

```console
$ curl -s -X POST localhost:9210/generate -d '{"prompt":"Mickey Mouse and Darth Vader shaking hands"}' ...
{"detail":{"error":"blocked by the Mage-Flow content filter","categories":["copyright"],
 "reason":"Mickey Mouse is a Disney character and Darth Vader is a Star Wars character; ..."}}
```

Re-screening runs only when the image is white, so the happy path pays nothing for it.

**2. The variants are not interchangeable at fixed settings.** Turbo is distilled for
4 steps at `cfg 1.0`; the RL-aligned model wants 20 steps at `cfg 5.0`, Base 30. Running
Turbo at `cfg 5.0` gives a scorched, oversaturated image and takes ~10× longer — which
reads as "the model is bad" rather than "the caller passed the wrong number". So `steps`
and `cfg` default to whatever the *configured checkpoint* was tuned for, derived from its
name (`mage_loader.variant_defaults`). Send them only to override deliberately.

## Attention backend (flash-attn is optional here)

Mage-Flow defaults to flash-attn and hardcodes `attn_type="flash2"` — the published
checkpoints carry no `attn_type` key, so the config always resolves to flash2 and the
model dies on its first attention call if flash-attn is missing. Upstream ships an SDPA
fallback but no plumbing to select it, so this sidecar selects it: `MAGEFLOW_ATTN=auto`
(the default) uses flash-attn when importable and torch SDPA otherwise. See the header of
[`mage_loader.py`](./mage_loader.py) for why the switch has to happen on two paths, one
before the load and one after.

Installing flash-attn is therefore optional. It is faster, but it compiles a CUDA
extension against your torch build and needs an `nvcc` whose **major** version matches
the wheel's CUDA (torch 2.13 default wheels are cu130 → nvcc 13.x). If you have that:

```bash
./.venv/bin/pip install setuptools wheel ninja
./.venv/bin/pip install --no-build-isolation flash-attn==2.8.3
```

Measured here without it — RTX 4090, SDPA, Turbo, 4 steps: **~1.2 s** per 1024²-class
image, 16 s to load, **18.1 GB** peak VRAM.

## Hardware

The bf16 weights are **17.5 GB** (8.2 GB DiT + 8.9 GB Qwen3-VL text encoder + 0.35 GB
Mage-VAE) and peak at ~18–20 GB, so this needs a **≥24 GB** card; a 12 GB GPU cannot hold
the model at all. Pin one with `CUDA_VISIBLE_DEVICES`.

Each checkpoint repo is self-contained — every variant ships its own copy of the text
encoder — so the t2i and edit models are ~35 GB together and do not co-reside on a 24 GB
card. Loading one **evicts** the other (`mage_loader._evict`), which means alternating
`/generate` and `/remix` traffic pays a reload each time. Give them separate processes on
separate GPUs if you need both hot.

## Environment variables

| Var                    | Default                          | Notes                                        |
|------------------------|----------------------------------|----------------------------------------------|
| `MAGEFLOW_MODEL`       | `microsoft/Mage-Flow-Turbo`      | t2i checkpoint (HF repo id or local path)    |
| `MAGEFLOW_EDIT_MODEL`  | `microsoft/Mage-Flow-Edit-Turbo` | checkpoint used by `/remix`                  |
| `MAGEFLOW_ATTN`        | `auto`                           | `auto` \| `flash2` \| `flash4` \| `sdpa`     |
| `MAGEFLOW_DEVICE`      | `cuda` if available              | torch device                                 |
| `MAGEFLOW_STEPS`       | per-variant                      | override the tuned step count                |
| `MAGEFLOW_CFG`         | per-variant                      | override the tuned guidance scale            |
| `MAGEFLOW_MAX_BATCH`   | `4`                              | cap on `n` for `/v1/generate`                |
| `MAGEFLOW_HOST/PORT`   | `0.0.0.0` / `9210`               | listener (`run.sh`)                          |
| `CUDA_VISIBLE_DEVICES` | —                                | pin a ≥24 GB GPU, e.g. `0`                   |

Checkpoint alternatives: `microsoft/Mage-Flow` (RL-aligned, 20 steps, GenEval 0.90 — the
quality ceiling) and `microsoft/Mage-Flow-Base` (30 steps). Turbo is the default because
4 steps is what makes this usable as a request-scoped sidecar, for ~0.02 GenEval.

## Run

```bash
./setup.sh                                # venv + pinned deps + the mage-flow package
CUDA_VISIBLE_DEVICES=0 ./run.sh           # serves on :9210
```

The checkpoint downloads from the Hub on the first request (~17.5 GB), so call one
`/generate` and wait before pointing a pipeline at it. `setup.sh` prints a one-liner to
prefetch it instead.

## Smoke test

```bash
curl -s localhost:9210/health | jq

# text-to-image
curl -s -X POST localhost:9210/generate \
  -H 'content-type: application/json' \
  -d '{"prompt":"a red panda astronaut, studio lighting","seed":42}' \
  -o out.png

# instruction edit (loads the edit checkpoint - evicts the t2i one, so ~24 s not ~1 s)
# The body goes in a file: a base64 image inlined into argv exceeds the shell's argument
# limit for anything much above a 100 KB source image ("Argument list too long").
./.venv/bin/python - > /tmp/remix-body.json <<'PY'
import base64, json
print(json.dumps({"image_b64": base64.b64encode(open("out.png", "rb").read()).decode(),
                  "prompt": "replace the background with a field of sunflowers"}))
PY
curl -s -X POST localhost:9210/remix \
  -H 'content-type: application/json' \
  --data @/tmp/remix-body.json \
  -o remix.png
```

Note on `/remix`: the contract's `strength` parameter is **accepted and ignored**.
Mage-Flow-Edit is instruction-conditioned, not noise-strength based — the prompt is an
instruction ("replace the background with …"), not a description of the wanted output,
and there is no knob for "how far from the source". Requests from the existing node keep
working; the parameter simply has no effect.

## Pointing the Cortex `imagegen` node at this sidecar

```yaml
imagegen:
  host: localhost
  port: 9210      # instead of 9200
  steps: 4        # IMPORTANT - see below
  prompt: "..."
```

`steps` matters because `ImageGenClient` always sends the option's value (default **30**),
so the per-variant default in this server never applies to node traffic. Against the
Turbo checkpoint, 30 steps is ~7× the work for no quality gain — the model is distilled
to 4. Set `steps: 4` for Turbo, `20` for `microsoft/Mage-Flow`, `30` for `-Base`. The
server deliberately does not clamp an explicitly requested value.

The node's `strength` option has no effect here (see the `/remix` note above).

## Example: the MetaLoom set

[`generate_examples.py`](./generate_examples.py) drives the **running server** (stdlib
only, no torch import) and writes the five reference images in this folder. Because it
goes over HTTP, a successful run exercises the same path the Java node takes — request
validation, per-variant defaults, filter unmasking, PNG provenance — rather than just
proving torch works.

```bash
./run.sh &
./.venv/bin/python generate_examples.py
```

| Image | Size | What it exercises |
|-------|------|-------------------|
| [`example-1-wordmark.png`](./example-1-wordmark.png) | 1536×640 | Legible text rendering — the family's headline capability — on an extreme aspect ratio no bucketed SDXL can emit directly |
| [`example-2-loom-constellation.png`](./example-2-loom-constellation.png) | 1024×1024 | The brand mark as a scene: five teal stars joined by one thread across a loom's warp |
| [`example-3-self-hosted-rack.png`](./example-3-self-hosted-rack.png) | 1216×832 | Photorealism and mixed lighting — "on hardware you control" |
| [`example-4-processing-pipeline.png`](./example-4-processing-pipeline.png) | 1024×1024 | Compositional prompt following: film, stills and audio woven into indexed tiles |
| [`example-5-cortex-analysis.png`](./example-5-cortex-analysis.png) | 1216×832 | Dense synthetic UI — face boxes, waveform, metadata column |

Total: **~7 s** for all five on a 4090 with the model already resident. Seeds are fixed,
so a re-run reproduces the files byte-for-similar.

Two prompt findings are recorded as comments in the script rather than lost: asking for
"shallow depth of field" on a *dashboard* defocuses the whole UI, and the token `8k` on a
UI prompt gets rendered as a literal label inside the interface.

## Docker

```bash
docker build -t mage-flow-sidecar .
docker run --gpus all -p 9210:9210 \
  -v $HOME/.cache/huggingface:/root/.cache/huggingface \
  mage-flow-sidecar
```

The 17.5 GB checkpoint is not baked into the image — mount the HF cache.

## Licence

Sidecar code follows the repo (Apache-2.0). **Mage-Flow's code and all its checkpoints
are MIT**, which covers commercial use — unlike SDXL-Turbo's and Ideogram 4's
non-commercial community licences. The content filter is part of the model and cannot be
disabled; treat a `422` as a product constraint, not a bug.

## References

- Paper: [Mage-Flow: An Efficient Native-Resolution Foundation Model for Image Generation
  and Editing](https://arxiv.org/abs/2607.19064) (arXiv 2607.19064)
- Code: [github.com/microsoft/Mage](https://github.com/microsoft/Mage) — pinned in
  `setup.sh` to a commit, not a branch
- Weights: [microsoft/Mage-Flow-Turbo](https://huggingface.co/microsoft/Mage-Flow-Turbo)
