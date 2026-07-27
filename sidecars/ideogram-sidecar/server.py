"""
ideogram-sidecar — FastAPI HTTP server for the Cortex `imagegen` node.

This is the Python PoC from spec/plans/imagegen-node.md (Part A). It wraps a
Diffusers image model behind a small, **model-agnostic** HTTP contract so the
Java `ImageGenNode` is a pure HTTP client and is not coupled to any one model.

HTTP contract (matches the plan):
  * GET  /health   → {"status":"ok","model": "<repo>", "device": "..."}
  * POST /generate — {prompt, width?, height?, seed?, steps?, guidance?}
                     → image/png   (text-to-image; smoke-test endpoint)
  * POST /remix    — {image_b64, prompt, strength?, seed?, steps?, guidance?}
                     → image/png   (image-to-image; the endpoint the node uses)

Model selection is entirely env-driven. Ideogram 4.0 is the *intended* backing
model, but its weights are gated on Hugging Face and need ~24 GB CUDA + an
HF_TOKEN + accepting the non-commercial model gate. For an ungated PoC that runs
on modest hardware (~8-12 GB VRAM) the server defaults to SDXL-Turbo, which
produces a full image in 1-4 steps with no login. Point IMAGEGEN_MODEL at
`ideogram-ai/ideogram-4-nf4` (with HF_TOKEN set) to use Ideogram instead.

Run:
  uvicorn server:app --host 0.0.0.0 --port 9200

Environment variables:
  IMAGEGEN_MODEL     HF repo / local path of the diffusers pipeline
                     (default: stabilityai/sdxl-turbo)
  IMAGEGEN_DEVICE    torch device            (default: cuda if available else cpu)
  IMAGEGEN_DTYPE     float16 | bfloat16 | float32   (default: float16 on cuda)
  IMAGEGEN_STEPS     default inference steps (default: 4 — tuned for turbo)
  IMAGEGEN_GUIDANCE  default guidance scale  (default: 0.0 — turbo wants 0)
  IMAGEGEN_CPU_OFFLOAD  1 to enable model CPU offload (fits big models on
                     small VRAM, e.g. Ideogram-4 on a 12 GB card)  (default: 0)
  CUDA_VISIBLE_DEVICES  pin a GPU, e.g. "1" for the second card
  HF_TOKEN           Hugging Face token (only needed for gated models)

Note on `/remix`: image-to-image needs an img2img-capable pipeline. SDXL has one;
Ideogram-4 (this diffusers build) is text-to-image only, so `/remix` transparently
falls back to prompt-only generation there. `/health` reports the active mode.
"""

import base64
import io
import logging
import os
from typing import Optional

import torch
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from PIL import Image
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("ideogram-sidecar")

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #
MODEL_ID = os.environ.get("IMAGEGEN_MODEL", "stabilityai/sdxl-turbo")
DEVICE = os.environ.get(
    "IMAGEGEN_DEVICE", "cuda" if torch.cuda.is_available() else "cpu"
)
_DTYPE_MAP = {"float16": torch.float16, "bfloat16": torch.bfloat16, "float32": torch.float32}
_default_dtype = "float16" if DEVICE.startswith("cuda") else "float32"
DTYPE = _DTYPE_MAP[os.environ.get("IMAGEGEN_DTYPE", _default_dtype)]
DEFAULT_STEPS = int(os.environ.get("IMAGEGEN_STEPS", "4"))
_g = os.environ.get("IMAGEGEN_GUIDANCE", "0.0")
# "none"/"default" → let the pipeline use its own guidance default (Ideogram wants this).
DEFAULT_GUIDANCE = None if _g.lower() in ("none", "default", "") else float(_g)


def _resolve_guidance(req_guidance):
    """Return kwargs for guidance_scale, omitting it entirely when None so the
    pipeline uses its built-in default (Ideogram-4's guidance_scale default is None)."""
    g = req_guidance if req_guidance is not None else DEFAULT_GUIDANCE
    return {} if g is None else {"guidance_scale": g}
CPU_OFFLOAD = os.environ.get("IMAGEGEN_CPU_OFFLOAD", "0") not in ("0", "", "false", "False")

# Lazily-populated pipelines (text2img + img2img share the same weights).
_txt2img = None
_img2img = None  # None when the backend has no image-to-image pipeline.


def _load_pipelines():
    """Load the diffusers pipelines once. Called at startup."""
    global _txt2img, _img2img
    if _txt2img is not None:
        return
    from diffusers import AutoPipelineForImage2Image, DiffusionPipeline

    logger.info(
        "Loading model '%s' (device=%s, dtype=%s, cpu_offload=%s)",
        MODEL_ID, DEVICE, DTYPE, CPU_OFFLOAD,
    )
    # DiffusionPipeline reads model_index.json's _class_name, so it resolves the
    # right pipeline for SDXL *and* custom ones like Ideogram4Pipeline.
    kwargs = {"torch_dtype": DTYPE}
    if DTYPE == torch.float16:
        kwargs["variant"] = "fp16"
    try:
        _txt2img = DiffusionPipeline.from_pretrained(MODEL_ID, **kwargs)
    except Exception:  # noqa: BLE001 — some repos ship no fp16 variant
        kwargs.pop("variant", None)
        _txt2img = DiffusionPipeline.from_pretrained(MODEL_ID, **kwargs)

    # Big models (e.g. Ideogram-4, ~16 GB) don't fit small cards fully resident;
    # model CPU offload keeps one component on GPU at a time.
    if CPU_OFFLOAD and DEVICE.startswith("cuda"):
        _txt2img.enable_model_cpu_offload()
    else:
        _txt2img = _txt2img.to(DEVICE)

    # Try to derive an img2img pipeline sharing the same weights (no re-download).
    # Not all models have one (Ideogram-4 is text-to-image only) — degrade cleanly.
    try:
        _img2img = AutoPipelineForImage2Image.from_pipe(_txt2img)
        if CPU_OFFLOAD and DEVICE.startswith("cuda"):
            _img2img.enable_model_cpu_offload()
        else:
            _img2img = _img2img.to(DEVICE)
        logger.info("img2img pipeline available — /remix does true image-to-image.")
    except (ValueError, KeyError, NotImplementedError) as exc:
        _img2img = None
        logger.warning(
            "No img2img pipeline for this model (%s) — /remix falls back to "
            "prompt-only text-to-image.", type(exc).__name__,
        )
    logger.info("Model loaded and ready.")


def _generator(seed: Optional[int]):
    if seed is None:
        return None
    return torch.Generator(device=DEVICE).manual_seed(int(seed))


def _to_png_response(image: Image.Image) -> Response:
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return Response(content=buf.getvalue(), media_type="image/png")


# --------------------------------------------------------------------------- #
# Request models
# --------------------------------------------------------------------------- #
class GenerateRequest(BaseModel):
    prompt: str
    width: int = 1024
    height: int = 1024
    seed: Optional[int] = None
    steps: Optional[int] = None
    guidance: Optional[float] = None


class RemixRequest(BaseModel):
    image_b64: str
    prompt: str
    strength: float = 0.6
    seed: Optional[int] = None
    steps: Optional[int] = None
    guidance: Optional[float] = None


# --------------------------------------------------------------------------- #
# App
# --------------------------------------------------------------------------- #
app = FastAPI(title="ideogram-sidecar", version="0.1.0")


@app.on_event("startup")
def _startup():
    _load_pipelines()


@app.get("/health")
def health():
    return {
        "status": "ok" if _txt2img is not None else "loading",
        "model": MODEL_ID,
        "device": DEVICE,
        "dtype": str(DTYPE),
        "cpu_offload": CPU_OFFLOAD,
        "remix_mode": "img2img" if _img2img is not None else "txt2img-fallback",
    }


@app.post("/generate")
def generate(req: GenerateRequest):
    """Text-to-image. Used for smoke tests."""
    if _txt2img is None:
        raise HTTPException(status_code=503, detail="model still loading")
    steps = req.steps if req.steps is not None else DEFAULT_STEPS
    gkw = _resolve_guidance(req.guidance)
    logger.info("generate: steps=%d guidance=%s size=%dx%d prompt=%r",
                steps, gkw.get("guidance_scale", "default"), req.width, req.height, req.prompt[:80])
    image = _txt2img(
        prompt=req.prompt,
        width=req.width,
        height=req.height,
        num_inference_steps=steps,
        generator=_generator(req.seed),
        **gkw,
    ).images[0]
    return _to_png_response(image)


@app.post("/remix")
def remix(req: RemixRequest):
    """Image-to-image / remix. The endpoint the ImageGenNode calls.

    When the backing model has no img2img pipeline (e.g. Ideogram-4), this
    transparently degrades to prompt-only text-to-image so the contract holds.
    """
    if _txt2img is None:
        raise HTTPException(status_code=503, detail="model still loading")
    try:
        raw = base64.b64decode(req.image_b64)
        source = Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=f"bad image_b64: {exc}") from exc

    steps = req.steps if req.steps is not None else DEFAULT_STEPS
    gkw = _resolve_guidance(req.guidance)

    if _img2img is None:
        # Text-to-image-only backend: honour the prompt, ignore the source pixels.
        w, h = source.size
        logger.info("remix(txt2img-fallback): steps=%d guidance=%s prompt=%r",
                    steps, gkw.get("guidance_scale", "default"), req.prompt[:80])
        image = _txt2img(
            prompt=req.prompt,
            width=w - (w % 16),
            height=h - (h % 16),
            num_inference_steps=steps,
            generator=_generator(req.seed),
            **gkw,
        ).images[0]
        return _to_png_response(image)

    # SDXL-Turbo etc. need steps * strength >= 1, so bump steps for low strength.
    eff_steps = max(steps, int(round(1.0 / max(req.strength, 1e-3))) + 1)
    logger.info("remix(img2img): steps=%d(eff) strength=%.2f guidance=%s prompt=%r",
                eff_steps, req.strength, gkw.get("guidance_scale", "default"), req.prompt[:80])
    image = _img2img(
        prompt=req.prompt,
        image=source,
        strength=req.strength,
        num_inference_steps=eff_steps,
        generator=_generator(req.seed),
        **gkw,
    ).images[0]
    return _to_png_response(image)
