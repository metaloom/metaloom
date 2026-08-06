# SAM 2 sidecar

FastAPI model server for the Cortex `sam2` node. Runs [SAM 2](https://github.com/facebookresearch/sam2)
(Segment Anything 2) and answers with per-object segmentation masks.

```bash
./setup.sh          # once
./run.sh            # serves on :9130
curl -s localhost:9130/health | python3 -m json.tool
```

## What it serves

| Endpoint | Does |
|---|---|
| `POST /v1/segment` `mode=AUTOMATIC` | Segment everything in one still image |
| `POST /v1/segment` `mode=PROMPTED` | One mask per box an upstream detector found |
| `POST /v1/track` | Prompt on one frame, propagate the masks through a clip |
| `GET /health` | Device, default model, caps, and which checkpoints are loaded |

## The coordinate contract

Every coordinate crossing this boundary is in the space of the image that was actually
POSTed, **never the source file**. The node downscales to `max_dim` first, sends boxes
already rescaled into that space, and gets masks back at exactly the `width`/`height`
the response reports. The server never learns the source resolution.

Boxes are **XYXY** (`x1,y1,x2,y2`), not XYWH. The node converts — `objectdetect` and
`facedetect` both emit XYWH.

Masks come back as base64 8-bit grayscale PNGs whose pixels are 0 or 255. 8 bits rather
than 1 because a 1-bit PNG is a decoding hazard in Java's ImageIO, and deflate makes
the size difference negligible for a two-value image.

## Models

Parameter counts and FPS are from the SAM 2 repository's own table. **The VRAM column is
estimated from parameter counts, not measured — measure on your card and correct it.**

| Model id | Params | FPS (A100, video) | Rough inference VRAM |
|---|---|---|---|
| `facebook/sam2.1-hiera-tiny` | 38.9 M | 91.2 | ~2.5 GB |
| `facebook/sam2.1-hiera-small` | 46.0 M | 84.8 | ~3 GB (**default**) |
| `facebook/sam2.1-hiera-base-plus` | 80.8 M | 64.1 | ~4.5 GB |
| `facebook/sam2.1-hiera-large` | 224.4 M | 39.5 | ~9 GB |

For `/v1/track` the weights are not the dominant term: the memory bank grows with
tracked objects × frames, so `max_frames` and `max_masks` are the real VRAM knobs.

SAM 2 code and the 2.1 checkpoints are Apache-2.0 — every member of this family is
usable commercially, unlike the Depth Anything V2 family where only Small is.

## Why `transformers` and not the `sam2` package

`pip install sam2` on PyPI is a **third-party upload**, not Meta's. Meta's own
instruction is `git clone github.com/facebookresearch/sam2 && pip install -e .`, which
builds a CUDA extension and pins hydra. `transformers` ships `Sam2Model`,
`Sam2VideoModel`, `Sam2Processor`, `Sam2VideoProcessor` and the `"mask-generation"`
pipeline natively, needs no build step, and is the same stack `sidecars/depth` runs.

`setup.sh` asserts those four imports resolve rather than pinning a `transformers`
minor, because which release first shipped the video classes moves.

## Concurrency

One GPU lock in `server.py` plus `--workers 1` in `run.sh`. `points_per_side=32` is 1024
forward passes for a single image, and the video predictor holds a per-request memory
bank. Two concurrent requests do not go faster; they run the card out of memory. The
Java node declares `defaultConcurrency = 1` to say so; the lock is what guarantees it
when something else calls the server.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `SAM2_HOST` / `SAM2_PORT` | `0.0.0.0` / `9130` | Listener |
| `SAM2_MODEL` | `facebook/sam2.1-hiera-small` | Default checkpoint; per-request `model` overrides it |
| `SAM2_MAX_DIM` | `1024` | Server-side cap on the longest side; a request may ask for less, never more |
| `SAM2_MAX_FRAMES` | `128` | Server-side cap on frames per `/v1/track` request |
| `DEVICE` | `cuda` if available | torch device |

## Known limitation

The `transformers` `"mask-generation"` pipeline does not expose SAM 2's own
`pred_iou_thresh` / `stability_score_thresh`. AUTOMATIC applies `pred_iou_thresh` as a
post-filter on the score the pipeline does return, and **`stability_score_thresh` has no
analogue in that path and is ignored**. The node's contract is unaffected — masks are
still filtered by `min_mask_area` and capped by `max_masks`.
