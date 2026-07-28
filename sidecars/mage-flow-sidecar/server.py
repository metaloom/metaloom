"""
Mage-Flow sidecar — FastAPI HTTP server for Microsoft's Mage-Flow image model
(4B NR-MMDiT + Mage-VAE, MIT-licensed, https://github.com/microsoft/Mage).

It speaks the same model-agnostic contract as `sidecars/ideogram-sidecar`, so the
existing Cortex `imagegen` node (`io.metaloom.cortex.node.imagegen`) can be pointed
at it by changing one option — `port` — and nothing else:

  POST /generate   {prompt, width?, height?, seed?, steps?, cfg?, neg_prompt?}  -> image/png
  POST /remix      {image_b64, prompt, seed?, steps?, cfg?, max_size?}          -> image/png
  POST /v1/generate  same as /generate plus `n`                                 -> JSON
  GET  /health                                                                  -> JSON

THE ONE THING TO UNDERSTAND ABOUT THIS SERVER
---------------------------------------------
Mage-Flow has a MANDATORY content filter with no opt-out, and a blocked prompt does
not raise — `generate()` returns a PLAIN WHITE IMAGE with an empty banner. Upstream's
`FilterVerdict.banner()` deliberately returns "" and `make_refusal_image()` is a blank
white canvas, so a refusal is indistinguishable from a broken model, a bad checkpoint,
or an fp16 collapse. Worse, the filter is FAIL-CLOSED: any error inside the classifier
(it is a Qwen3-VL generation call, so a bad attention backend or an OOM counts) also
returns "violates" and therefore also returns white.

Shipping that upstream would mean every one of those failures looks identical, and
looks like our bug. So this server turns it into an explicit HTTP error:

    a returned image that is uniformly white is re-screened via `txt_enc.screen_text()`
    /`screen_edit()`. If the filter confirms the block, the request fails with
    422 and the filter's own categories + reason. If it does not, the image really
    was white and is returned unchanged.

Re-screening only on a white result keeps the happy path free — the screening pass is
a VL text generation, and paying for it on every request would roughly double latency
for a check the pipeline has already done internally.

Two more responsibilities live here rather than in the Java node:

  * Per-variant step/cfg defaults (see mage_loader.variant_defaults) — Turbo at cfg 5
    is a scorched image, and no caller should have to know that.
  * Native-resolution validation — Mage-Flow generates 512..2048 on any aspect ratio
    with sides a multiple of 16, and unlike a bucketed SDXL there is no "supported
    resolution" list to snap to.

Only one generation runs at a time (`_gpu_lock`). FastAPI dispatches sync handlers to a
threadpool, so without it two concurrent requests would both allocate ~18 GB.

Run:
  uvicorn server:app --host 0.0.0.0 --port 9210

Environment variables:
  MAGEFLOW_MODEL       t2i checkpoint    (default: microsoft/Mage-Flow-Turbo)
  MAGEFLOW_EDIT_MODEL  edit checkpoint   (default: microsoft/Mage-Flow-Edit-Turbo)
  MAGEFLOW_ATTN        auto | flash2 | flash4 | sdpa   (default: auto)
  MAGEFLOW_DEVICE      torch device      (default: cuda if available else cpu)
  MAGEFLOW_STEPS       override the variant's default step count
  MAGEFLOW_CFG         override the variant's default guidance scale
  MAGEFLOW_MAX_BATCH   cap on `n` for /v1/generate (default: 4)
  CUDA_VISIBLE_DEVICES pin a GPU - bf16 weights alone are 17.5 GB, so this needs a
                       >=24 GB card; a 12 GB card cannot hold the model at all.

Licence: the model code and all Mage-Flow checkpoints are MIT, which (unlike
SDXL-Turbo's and Ideogram 4's non-commercial licences) covers commercial use. The
resolved model id travels back in every JSON response so a node can record it as the
result ledger's producerVersion.
"""

import base64
import io
import logging
import os
import threading
import time
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel

import mage_loader

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("mage-flow-server")

# Native-resolution envelope of the NR-MMDiT. Outside it the model is out of
# distribution; the multiple-of-16 rule is the VAE's 16x downsampling.
MIN_SIDE = 512
MAX_SIDE = 2048
SIDE_MULTIPLE = 16

MAX_BATCH = int(os.environ.get("MAGEFLOW_MAX_BATCH", "4"))

_STEPS_OVERRIDE = os.environ.get("MAGEFLOW_STEPS")
_CFG_OVERRIDE = os.environ.get("MAGEFLOW_CFG")

# One generation at a time - see module docstring.
_gpu_lock = threading.Lock()


# --------------------------------------------------------------------------- #
# Request parameter resolution
# --------------------------------------------------------------------------- #
def _defaults(model_id: str) -> tuple[int, float]:
    steps, cfg = mage_loader.variant_defaults(model_id)
    if _STEPS_OVERRIDE:
        steps = int(_STEPS_OVERRIDE)
    if _CFG_OVERRIDE:
        cfg = float(_CFG_OVERRIDE)
    return steps, cfg


def _side(value: Optional[int], name: str) -> int:
    """Validate one output dimension and snap it up to the VAE's 16-pixel grid.

    Out-of-range is an error rather than a clamp: a caller asking for 4096 wants
    something this checkpoint cannot do, and silently returning 2048 would hide that.
    A non-multiple of 16 is snapped, because 1000 -> 1008 is not a decision anyone
    needs to make and the PNG carries its own true size.
    """
    if value is None:
        return 1024
    value = int(value)
    if value < MIN_SIDE or value > MAX_SIDE:
        raise HTTPException(
            status_code=400,
            detail=f"{name} must be between {MIN_SIDE} and {MAX_SIDE}, got {value}",
        )
    if value % SIDE_MULTIPLE:
        snapped = ((value + SIDE_MULTIPLE - 1) // SIDE_MULTIPLE) * SIDE_MULTIPLE
        snapped = min(snapped, MAX_SIDE)
        logger.info("Snapped %s %d -> %d (multiple of %d)", name, value, snapped, SIDE_MULTIPLE)
        return snapped
    return value


def _decode_image(image_b64: str):
    from PIL import Image

    try:
        raw = base64.b64decode(image_b64, validate=True)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"image_b64 is not valid base64: {exc}") from exc
    try:
        return Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"could not decode the image: {exc}") from exc


# --------------------------------------------------------------------------- #
# The content-filter unmasking - see module docstring
# --------------------------------------------------------------------------- #
def _is_blank_white(image) -> bool:
    """True for a uniformly white image, i.e. what a refusal looks like."""
    extrema = image.convert("RGB").getextrema()
    return all(lo == 255 and hi == 255 for lo, hi in extrema)


def _reject_if_filtered(pipe, image, prompt: str, refs=None) -> None:
    """Raise 422 if `image` is a content-filter refusal placeholder.

    Called only for a uniformly white result, so a legitimately white image costs one
    screening pass and is then returned as-is.
    """
    if not _is_blank_white(image):
        return
    logger.info("Blank white result - re-screening the prompt to identify a refusal")
    try:
        if refs:
            verdict = pipe.model.txt_enc.screen_edit(prompt, refs)
        else:
            verdict = pipe.model.txt_enc.screen_text(prompt)
    except Exception as exc:
        # The screening call itself failed. Do not claim a policy block we cannot
        # confirm - report the ambiguity, since a fail-closed filter and a broken
        # classifier produce the identical white image.
        raise HTTPException(
            status_code=500,
            detail=(
                "Generation returned a blank white image, which means either a "
                f"content-filter refusal or a classifier failure; re-screening also "
                f"failed: {exc}"
            ),
        ) from exc

    if not verdict.violates:
        logger.info("Filter says the prompt is fine - the image is genuinely white")
        return

    raise HTTPException(
        status_code=422,
        detail={
            "error": "blocked by the Mage-Flow content filter",
            "categories": list(getattr(verdict, "categories", []) or []),
            "reason": getattr(verdict, "reason", "") or "",
            "note": (
                "The filter is mandatory and fail-closed: a classifier error is also "
                "reported as a violation."
            ),
        },
    )


# --------------------------------------------------------------------------- #
# PNG encoding
# --------------------------------------------------------------------------- #
def _to_png(image, meta: dict) -> bytes:
    """Encode as PNG, recording the generation parameters in tEXt chunks.

    Provenance travels with the file: an image found on disk months later still says
    which checkpoint, prompt and seed produced it, which a raw PNG cannot.
    """
    from PIL import PngImagePlugin

    info = PngImagePlugin.PngInfo()
    for key, value in meta.items():
        if value is not None:
            info.add_text(f"mageflow:{key}", str(value))
    buf = io.BytesIO()
    image.save(buf, format="PNG", pnginfo=info)
    return buf.getvalue()


# --------------------------------------------------------------------------- #
# Generation
# --------------------------------------------------------------------------- #
def _generate(prompt: str, width: int, height: int, seed: Optional[int], steps: Optional[int],
              cfg: Optional[float], neg_prompt: Optional[str], n: int):
    """Text-to-image. Returns (images, meta). `n>1` is one packed transformer forward."""
    model_id = mage_loader.DEFAULT_T2I_MODEL
    default_steps, default_cfg = _defaults(model_id)
    steps = int(steps) if steps else default_steps
    cfg = float(cfg) if cfg is not None else default_cfg
    # -1 asks the model for a random seed; it reports back the one it drew.
    base_seed = -1 if seed is None else int(seed)
    seeds = [base_seed] * n if base_seed == -1 else [base_seed + i for i in range(n)]

    pipe = mage_loader.load(model_id)
    started = time.monotonic()
    with _gpu_lock:
        images = pipe.generate(
            [prompt] * n,
            steps=steps,
            cfg=cfg,
            heights=[height] * n,
            widths=[width] * n,
            seeds=seeds,
            neg_prompts=[neg_prompt or " "] * n,
        )
    elapsed_ms = int((time.monotonic() - started) * 1000)

    for image in images:
        _reject_if_filtered(pipe, image, prompt)

    meta = {
        "model": model_id,
        "steps": steps,
        "cfg": cfg,
        "width": width,
        "height": height,
        "seeds": seeds,
        "attn": mage_loader.ATTN_BACKEND,
        "elapsedMs": elapsed_ms,
    }
    logger.info(
        "Generated %d image(s) %dx%d in %d ms (steps=%d cfg=%s)",
        len(images), width, height, elapsed_ms, steps, cfg,
    )
    return images, meta


def _remix(image_b64: str, prompt: str, seed: Optional[int], steps: Optional[int],
           cfg: Optional[float], max_size: Optional[int], width: Optional[int],
           height: Optional[int]):
    """Instruction edit of a source image, via the Mage-Flow-Edit checkpoint.

    This is the `/remix` slot of the sidecar contract, but Mage-Flow-Edit is
    instruction-conditioned, not noise-strength based: it has no `strength` knob to
    trade off "how far from the source". The prompt is an instruction ("replace the
    background with ..."), not a description of the desired output, and the request's
    `strength` is accepted and ignored so the existing node keeps working.
    """
    source = _decode_image(image_b64)
    model_id = mage_loader.DEFAULT_EDIT_MODEL
    default_steps, default_cfg = _defaults(model_id)
    steps = int(steps) if steps else default_steps
    cfg = float(cfg) if cfg is not None else default_cfg
    seed_value = -1 if seed is None else int(seed)

    kwargs = {}
    if width and height:
        kwargs["heights"] = [_side(height, "height")]
        kwargs["widths"] = [_side(width, "width")]
    else:
        # Upstream default: keep the source aspect ratio, longest side `max_size`.
        kwargs["max_size"] = int(max_size) if max_size else 1024

    pipe = mage_loader.load(model_id)
    started = time.monotonic()
    with _gpu_lock:
        images = pipe.edit([prompt], [source], steps=steps, cfg=cfg, seeds=[seed_value], **kwargs)
    elapsed_ms = int((time.monotonic() - started) * 1000)

    _reject_if_filtered(pipe, images[0], prompt, refs=[source])

    meta = {
        "model": model_id,
        "steps": steps,
        "cfg": cfg,
        "width": images[0].width,
        "height": images[0].height,
        "seeds": [seed_value],
        "attn": mage_loader.ATTN_BACKEND,
        "elapsedMs": elapsed_ms,
    }
    logger.info("Edited image -> %dx%d in %d ms", images[0].width, images[0].height, elapsed_ms)
    return images[0], meta


# --------------------------------------------------------------------------- #
# HTTP API
# --------------------------------------------------------------------------- #
class GenerateRequest(BaseModel):
    prompt: str
    width: Optional[int] = None
    height: Optional[int] = None
    seed: Optional[int] = None
    steps: Optional[int] = None
    # Guidance scale. Absent means the checkpoint's own tuned value (Turbo: 1.0).
    cfg: Optional[float] = None
    neg_prompt: Optional[str] = None
    # Number of variants, generated in ONE packed forward. Each raises peak VRAM.
    n: Optional[int] = 1


class RemixRequest(BaseModel):
    image_b64: str
    prompt: str
    seed: Optional[int] = None
    steps: Optional[int] = None
    cfg: Optional[float] = None
    # Longest side of the output; the short side follows the source aspect ratio.
    max_size: Optional[int] = None
    width: Optional[int] = None
    height: Optional[int] = None
    # Part of the sidecar contract, not of this model. Accepted and ignored - see _remix.
    strength: Optional[float] = None


app = FastAPI(title="Cortex Mage-Flow sidecar")


@app.get("/health")
def health():
    steps, cfg = _defaults(mage_loader.DEFAULT_T2I_MODEL)
    return {
        "status": "ok",
        "device": mage_loader.DEVICE,
        "attnBackend": mage_loader.ATTN_BACKEND,
        "models": {
            "generate": mage_loader.DEFAULT_T2I_MODEL,
            "remix": mage_loader.DEFAULT_EDIT_MODEL,
        },
        "defaults": {"steps": steps, "cfg": cfg, "width": 1024, "height": 1024},
        "resolution": {"min": MIN_SIDE, "max": MAX_SIDE, "multipleOf": SIDE_MULTIPLE},
        "maxBatch": MAX_BATCH,
        # Empty until the first request - the checkpoint loads lazily.
        "loaded": mage_loader.loaded_models(),
    }


@app.post("/generate")
def generate(request: GenerateRequest):
    """Text-to-image. Returns raw PNG bytes (the sidecar contract the node speaks)."""
    if not request.prompt or not request.prompt.strip():
        raise HTTPException(status_code=400, detail="prompt must not be empty")
    width = _side(request.width, "width")
    height = _side(request.height, "height")
    images, meta = _generate(
        request.prompt, width, height, request.seed, request.steps, request.cfg,
        request.neg_prompt, 1,
    )
    png = _to_png(images[0], {**meta, "prompt": request.prompt, "seed": meta["seeds"][0]})
    return Response(content=png, media_type="image/png")


@app.post("/v1/generate")
def generate_json(request: GenerateRequest):
    """Text-to-image returning JSON: base64 PNGs plus the parameters actually used.

    Exists alongside /generate for two reasons a raw-PNG response cannot serve: `n`
    variants in one response, and the resolved model id / seeds / step count, which a
    caller needs to record provenance for a generated asset.
    """
    if not request.prompt or not request.prompt.strip():
        raise HTTPException(status_code=400, detail="prompt must not be empty")
    n = max(1, int(request.n or 1))
    if n > MAX_BATCH:
        raise HTTPException(
            status_code=400,
            detail=f"n must be <= {MAX_BATCH} (MAGEFLOW_MAX_BATCH); got {n}",
        )
    width = _side(request.width, "width")
    height = _side(request.height, "height")
    images, meta = _generate(
        request.prompt, width, height, request.seed, request.steps, request.cfg,
        request.neg_prompt, n,
    )
    encoded: List[str] = [
        base64.b64encode(
            _to_png(image, {**meta, "prompt": request.prompt, "seed": meta["seeds"][i]})
        ).decode("ascii")
        for i, image in enumerate(images)
    ]
    return {**meta, "prompt": request.prompt, "images": encoded}


@app.post("/remix")
def remix(request: RemixRequest):
    """Instruction edit of a source image. Returns raw PNG bytes."""
    if not request.prompt or not request.prompt.strip():
        raise HTTPException(status_code=400, detail="prompt must not be empty")
    if not request.image_b64 or not request.image_b64.strip():
        raise HTTPException(status_code=400, detail="image_b64 must not be empty")
    image, meta = _remix(
        request.image_b64, request.prompt, request.seed, request.steps, request.cfg,
        request.max_size, request.width, request.height,
    )
    png = _to_png(image, {**meta, "prompt": request.prompt, "seed": meta["seeds"][0]})
    return Response(content=png, media_type="image/png")
