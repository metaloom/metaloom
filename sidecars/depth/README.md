# Depth sidecar

Monocular depth estimation for the Cortex `depthmap` node
(`io.metaloom.cortex.node.depthmap`). One image in, one depth map out.

The Java node is a pure HTTP client; the model, its PyTorch runtime and the whole
normalisation problem live here.

## The NEARNESS convention

This is the part to read before changing anything.

Raw monocular-depth output is **not** consistent between model families:

| Family | Raw output | Direction |
|--------|-----------|-----------|
| Depth Anything, MiDaS/DPT | disparity-like | **bigger = closer** |
| ZoeDepth, Depth Pro | metres | **bigger = farther** |

Passing that ambiguity downstream would guarantee an inverted-relation bug in every
consumer — one that reports success and quietly claims the background is in front of
the subject. So this server normalises everything to one convention:

> **NEARNESS** — a float in `[0, 1]` where **`1.0` is nearest to the camera**, encoded
> as a **16-bit grayscale PNG** in which `65535` is nearest.

Metric models are min-max normalised and then **inverted**, and their real metre range
comes back in `metric.min_m` / `metric.max_m` so absolute distance stays recoverable.

**Sanity check after any model change**: open the returned PNG and confirm the
foreground is *bright*. A dark foreground means the normalisation inverted.

16 bits rather than 8: an 8-bit map quantises to 256 levels, coarse enough that two
objects at similar distance land in the same bucket and their ordering collapses.

## Models

| Mode | Default checkpoint | Licence | Notes |
|------|--------------------|---------|-------|
| `RELATIVE` (default) | `depth-anything/Depth-Anything-V2-Small-hf` | **Apache-2.0** | ~25M params (ViT-S). Fast, CPU-viable, strong for its size |
| `METRIC` (opt-in) | `Intel/zoedepth-nyu-kitti` | MIT | Real metres. Heavier, wants a GPU, and single-view metric depth is meaningfully less reliable than relative |

🔴 **Do not switch `DEPTH_MODEL` to Depth Anything V2 Base or Large.** Those are
**CC-BY-NC-4.0** — non-commercial. Small is the only Apache-2.0 member of the family.
For a permissive step up in quality use `Intel/dpt-large` (MiDaS 3.0) instead.

Also rejected: Apple **Depth Pro** (`apple-ascl`, research-only terms) and **Marigold**
(Apache-2.0 and excellent, but diffusion-based — far too slow for a batch DAM pipeline).

## Setup

```bash
./setup.sh    # creates .venv and installs requirements
./run.sh      # serves on 0.0.0.0:9120
```

Checkpoints download lazily on the first request for their mode. Both are ungated, so
no `HF_TOKEN` is needed.

## API

| Method | Path | Body | Response |
|--------|------|------|----------|
| `GET` | `/health` | — | `{status, device, convention, models, maxDim, loaded}` |
| `POST` | `/v1/depth` | `{image_b64, mode?, model?, max_dim?}` | JSON, below |

```jsonc
{
  "model": "depth-anything/Depth-Anything-V2-Small-hf",
  "convention": "NEARNESS",
  "source": "RELATIVE",
  "width": 1024, "height": 683,     // dimensions of the RETURNED MAP, not the source image
  "png_b64": "iVBORw0KGgo…",        // 16-bit grayscale PNG, 65535 = nearest
  "stats": { "p05": 0.11, "p50": 0.38, "p95": 0.82 },
  "metric": { "min_m": 1.2, "max_m": 14.7 }   // only when source == METRIC
}
```

JSON with an embedded base64 PNG rather than a raw `image/png` body (which is what the
imagegen sidecar returns) because the metadata is not optional: a bare PNG cannot say
which model produced it, which convention it follows, or what the metre range was — and
the consumer needs all three. One round trip carrying both beats a second `/meta` call
that can disagree with the pixels.

⚠️ `width` / `height` are the **map's** dimensions after `max_dim` downscaling, not the
source image's. A consumer mapping bounding boxes onto the map must rescale them.

## Smoke test

```bash
curl -s localhost:9120/health

python - <<'PY'
import base64, json, urllib.request
b64 = base64.b64encode(open("some.jpg","rb").read()).decode()
req = urllib.request.Request("http://localhost:9120/v1/depth",
    data=json.dumps({"image_b64": b64, "max_dim": 1024}).encode(),
    headers={"Content-Type": "application/json"})
r = json.load(urllib.request.urlopen(req))
print(r["model"], r["convention"], r["width"], r["height"], r["stats"])
open("depth.png","wb").write(base64.b64decode(r["png_b64"]))
PY
```

Then open `depth.png` and check the foreground is bright.

## Environment variables

| Var | Default | Meaning |
|-----|---------|---------|
| `DEPTH_HOST` | `0.0.0.0` | Listener bind address |
| `DEPTH_PORT` | `9120` | Listener port |
| `DEPTH_MODEL` | `depth-anything/Depth-Anything-V2-Small-hf` | Relative-mode checkpoint |
| `DEPTH_MODEL_METRIC` | `Intel/zoedepth-nyu-kitti` | Metric-mode checkpoint |
| `DEPTH_MAX_DIM` | `1024` | Server-side cap on the longest side, applied even when a client asks for more |
| `DEVICE` | `cuda` if available else `cpu` | torch device |
| `CUDA_VISIBLE_DEVICES` | — | Pin a GPU |
| `HF_HOME` | — | Model cache location |

## Node configuration

The `depthmap` node addresses this sidecar via `depthHost` / `depthPort`
(default `localhost:9120`) and selects the mode with its own `mode` option. See
[`spec/features/pipeline-nodes/NODE_DEPTHMAP_PLAN.md`](../../spec/features/pipeline-nodes/NODE_DEPTHMAP_PLAN.md).
