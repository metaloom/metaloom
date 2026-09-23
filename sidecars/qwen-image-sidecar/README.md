# qwen-image-sidecar

FastAPI server for **Qwen-Image-2.1** — Qwen's unified text-to-image / editing model
(7B single-stream DiT + a Qwen3-VL text encoder). Port **9230**.

It is the third backend for the Cortex `imagegen` node, and the first one that can take
**several images at once** or edit **a region** rather than a whole picture.

> ## ⚠️ Licence: non-commercial
>
> Qwen-Image-2.1 is released under the **Qwen RESEARCH LICENSE** (2026-09-20), which
> grants use *"FOR NON-COMMERCIAL PURPOSES ONLY"* — research or evaluation. This is
> **not** Apache-2.0; Qwen-Image **1.0** was, and 2.1 is not.
>
> That puts this backend alongside SDXL-Turbo and Ideogram-4 rather than alongside
> MIT-licensed **Mage-Flow** (`sidecars/mage-flow-sidecar`, port 9210), which remains
> the documented default for shipping deployments. Commercial use needs a separate
> licence from Qwen (`model-business@notice.qwencloud.com`).

## The one thing to understand

**Qwen-Image-2.1 has no `mask_image` parameter, and masked editing still works.**

`QwenImage21Pipeline.__call__` takes `image=` as a PIL image or a **flat list** of them,
and that list is the only channel into the model. A mask is simply one more entry in it
— what the model card calls *"the original image and a separate mask as two inputs"*,
alongside two looser variants (coloured circles, painted annotations) that work the same
way. The model was trained to read the list that way.

So `/edit` does not translate a mask into a pipeline argument. It **appends the mask to
the condition list** and tells the model, in the prompt, that the last image is a mask.
If you are looking for where `mask_image` went: it never existed for this model.

The second consequence is `/mask`. Because a mask is just an image, **the model can draw
one** — which is how the phrase *"the boy's hair"* becomes a region with no segmentation
model anywhere in the loop. What comes back is a *picture of* a mask (anti-aliased edges,
"white" around 250, the occasional helpful text label), so `/mask` normalises it to
exactly `{0, 255}` before anything relies on it. That normalisation is the entire reason
`/mask` is a route rather than the caller's problem.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | status, model, device, capabilities |
| `POST` | `/generate` | text-to-image — **the existing sidecar contract** |
| `POST` | `/remix` | instruction edit of one image — **the existing contract** |
| `POST` | `/edit` | 1–10 images, optional mask, optional composite — **new** |
| `POST` | `/mask` | text → binary mask, white over the named region — **new** |

`/generate` and `/remix` are byte-compatible with `ideogram-sidecar` and
`mage-flow-sidecar`, so an existing pipeline is repointed here by changing the node's
`port` option to `9230` and nothing else. There is no backend enum; the port is the
selector.

### `POST /edit`

```jsonc
{
  "prompt": "the person from image 1 holding the product from image 2",
  "images_b64": ["…", "…"],   // 1..10. [0] is the image being EDITED; the rest are references
  "mask_b64": null,           // a binary mask, white = the region that may change
  "mask_prompt": null,        // …or name the region and let this server derive the mask
  "width": null, "height": null,      // omitted -> derived from images_b64[0]'s aspect ratio
  "output_resolution": 1024,          // 2048 for native 2K, ~4x the latency
  "steps": 40, "seed": null,
  "negative_prompt": null, "true_cfg_scale": 1.0,
  "composite": false
}
```

`mask_prompt` runs **both passes in one request** — one lock acquisition, one resident
model, no round trip carrying a 4 MB PNG back and forth. `mask_b64` and `mask_prompt`
are mutually exclusive: they answer the same question, and silently honouring one would
leave you believing the other took effect.

**`composite`** additionally blends the result back over the original through the mask,
so nothing outside the region can move. It defaults to `false` because passing the mask
as a condition image is the model's *own* preservation mechanism and is usually enough —
and because the pipeline renders at `output_resolution`, so compositing means upscaling
the generated pixels back to the source size, which costs quality on the edited region
too. Turn it on when you need the hard guarantee.

`true_cfg_scale` must be **> 1.0** for `negative_prompt` to do anything; at the default
1.0 the negative branch is not evaluated at all.

## Running it

```bash
./setup.sh          # venv; torch first, then diffusers FROM GIT (see below)
./run.sh            # serves on :9230
./qwen_smoke.py health --endpoint http://localhost:9230
```

Or as a container:

```bash
docker build -t qwen-image-sidecar .
docker run --gpus all -p 9230:9230 \
  -v $HOME/.cache/huggingface:/root/.cache/huggingface \
  qwen-image-sidecar
```

The 33 GB checkpoint is **not** baked into the image — mount the HF cache.

### diffusers has to come from git

`QwenImage21Pipeline` was merged into diffusers on **2026-09-18** (PR #14804). The newest
release, **0.40.0**, shipped **2026-08-20** and cannot contain it, so `pip install
diffusers` produces an `ImportError` that reads like a typo. `setup.sh` and the
`Dockerfile` install it from git **pinned to the merge commit** rather than tracking
`main`, so a rebuild months from now still yields the pipeline this server was written
against. The pin lives in three places that must agree: `qwen_loader.DIFFUSERS_GIT_REF`,
`setup.sh`, and the `Dockerfile`'s `ARG DIFFUSERS_GIT_REF`.

`qwen_loader._require_pipeline()` turns the `ImportError` into a sentence naming the
commit to install, because the bare one reads as "diffusers is broken" and it is not.

### torchvision is required even though nothing here imports it

Loading the pipeline constructs `Qwen3VLProcessor` → `Qwen3VLVideoProcessor`, which hard-requires
**torchvision**. Leave it out and the container builds, starts, serves `/health`, and then fails the
first generation with `Qwen3VLVideoProcessor requires the Torchvision library but it was not found`.
It is installed on the same line as torch, from the same index, so their compiled ABIs match.

### VRAM

The weights are 33 GB in bf16 (text encoder 17.5, transformer 14.2, VAE 1.35) — but the
weights are not the number that matters. **Measured on an H200, idle between requests:**

| `output_resolution` | Resident VRAM | Generation |
|---|---|---|
| 1024 (default) | **46.3 GB** | 4 s at 8 steps, 8–14 s at 40 |
| 2048 (native 2K) | **65.5 GB** | 20 s at 20 steps |

The extra is activations and the KV cache, and torch's caching allocator does not hand it back
between requests — resident and peak are the same number. **A card sized for the weights alone
will OOM on the first generation.**

So 48 GB is the practical floor at 1K and 80 GB at 2K. An A100 80 GB or an H200 handles both; a
40 GB A100 does not. Below that, `QWENIMAGE_OFFLOAD=1` swaps components CPU↔GPU per stage at a
real latency cost.

The first request after a cold start downloads and loads all of it — **minutes, not
seconds**, and easily mistaken for a hang. `./setup.sh` prints the `snapshot_download`
command to pull it ahead of time.

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `QWENIMAGE_MODEL` | `Qwen/Qwen-Image-2.1` | HF repo id or local path |
| `QWENIMAGE_DEVICE` | `cuda` if available | torch device |
| `QWENIMAGE_DTYPE` | `bfloat16` | the published dtype. `float16` gives NaN latents on long prompts |
| `QWENIMAGE_OFFLOAD` | `0` | `1` → `enable_model_cpu_offload()` |
| `QWENIMAGE_STEPS` | `40` | the model card's default |
| `QWENIMAGE_OUTPUT_RESOLUTION` | `1024` | target side length; `2048` for native 2K |
| `QWENIMAGE_MAX_IMAGES` | `10` | cap on `images_b64` — the model's own limit |
| `QWENIMAGE_HOST` / `QWENIMAGE_PORT` | `0.0.0.0` / `9230` | read by **`server.py` as well as `run.sh`**, unlike the three older sidecars where launching `server.py` directly silently ignores them |
| `CUDA_VISIBLE_DEVICES` | — | pin a card |

All config is captured at **import time** into module constants, so changing any of it
needs a process restart. There is no reload endpoint.

## Provenance

Every image response carries `X-Model-Id`, and every PNG carries `qwenimage:*` tEXt
chunks (model, mode, prompt, seed, steps). The raw-bytes contract leaves no JSON to put
this in, and tEXt survives the file being written to disk and picked up later — which is
how the `imagegen` node records a non-null `producerVersion` on its ledger row.

## Smoke testing

`qwen_smoke.py` is the bring-up client. Standard library only — no venv, no torch — so
it runs anywhere python3 does and works fine against a remote endpoint.

```bash
./qwen_smoke.py health   --endpoint http://HOST:9230
./qwen_smoke.py generate "a neon shop sign reading QWEN, rainy night"
./qwen_smoke.py compose a.png b.png c.png "the person from image 1 holding the product from image 2"
./qwen_smoke.py edit boy.jpg "dark brown hair" --mask-prompt "the boy's hair" --dump-mask
./qwen_smoke.py all sample.jpg          # every route; non-zero exit if any fails
```

**`--dump-mask` is the flag that matters.** It derives the mask in a separate call and
saves it, so you can look at the intermediate. A wrong mask produces a completely
plausible edit in entirely the wrong place, and that is the one failure mode the output
image will not reveal.

Bodies go into a JSON payload rather than curl's argv on purpose: a base64-encoded image
inlined into argv blows the shell argument limit at around a 100 KB source image.

## Tests

The first Python tests in `sidecars/`. No GPU, no weights, no torch.

```bash
python -m pytest test_mask_ops.py test_routes.py test_packaging.py -q
# or, with no pytest installed:
python test_mask_ops.py && python test_routes.py && python test_packaging.py
```

- **`test_mask_ops.py`** — the mask arithmetic (`mask_ops.py` imports nothing but PIL).
  Polarity, binarization, the Otsu threshold against a bright background, and that
  `composite` leaves the outside byte-identical.
- **`test_routes.py`** — the request layer with the pipeline stubbed. Validation, and
  above all **which images end up in the condition list, in what order**. That assembly
  is the entire design and it is invisible from the outside: a wrong condition list
  yields a perfectly plausible picture of the wrong thing.
- **`test_packaging.py`** — the deploy-time checks. Does the `Dockerfile` ship every module
  `server.py` imports? Are torch and torchvision installed together? Do the three copies of
  the diffusers pin agree? Written after the first bring-up failed twice, both times on
  packaging rather than code: `mask_ops.py` was left out of the `COPY` line, and
  `torchvision` was never installed at all. Nothing else catches either — the other suites
  import from the working directory, where every file is present by definition, so they pass
  happily on a tree that cannot be containerised.

Neither says anything about image quality. That is a live-GPU question and `qwen_smoke.py`
is how you answer it.

## Related

- `spec/sidecars/QWEN_IMAGE_SIDECAR.md` — the full contract
- `spec/features/nodes/image-generation/NODE_IMAGEGEN.md` — the node that calls it
- `spec/sidecars/SIDECARS.md` — the sidecar fleet and its shared rules
