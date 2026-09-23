"""
Qwen-Image-2.1 sidecar — FastAPI HTTP server for Qwen's unified generation/editing
model (7B single-stream DiT + Qwen3-VL text encoder, https://huggingface.co/Qwen/Qwen-Image-2.1).

It speaks the same model-agnostic contract as `sidecars/ideogram-sidecar` and
`sidecars/mage-flow-sidecar`, so the existing Cortex `imagegen` node can be pointed at
it by changing one option — `port` — and nothing else. On top of that it adds the two
endpoints the other two backends cannot serve:

  GET  /health                                                               -> JSON
  POST /generate   {prompt, width?, height?, seed?, steps?, ...}             -> image/png
  POST /remix      {image_b64, prompt, strength?, seed?, steps?}             -> image/png
  POST /edit       {prompt, images_b64[], mask_b64?, mask_prompt?, ...}      -> image/png
  POST /mask       {image_b64, prompt, seed?, steps?}                        -> image/png

THE ONE THING TO UNDERSTAND ABOUT THIS SERVER
---------------------------------------------
Qwen-Image-2.1 has NO `mask_image` parameter, and masked editing is nevertheless a
first-class, trained capability. The two facts are reconciled by how the model was
taught to read its inputs: `__call__` takes `image=` as a PIL image or a FLAT LIST of
them, and a mask is simply one more entry in that list. The model card calls this
"the original image and a separate mask as two inputs", alongside two looser variants
(coloured circles, painted annotations) that work the same way.

So `/edit` does not translate a mask into some pipeline argument. It appends it to the
condition list and says so in the prompt. Anyone reading this file looking for where
`mask_image` went should stop looking: it never existed for this model, and passing a
mask through the image list is the supported path rather than a workaround.

The second consequence is `/mask`. Because the mask is just an image, the model can
DRAW one — and that is how a text phrase ("the boy's hair") becomes a region without
any segmentation model in the loop. What comes back is a picture rather than a
guaranteed bitmask, so `/mask` normalises it (`_binarize`) before anyone relies on
{0,255}. That normalisation is the entire reason `/mask` is a route rather than a
caller's problem.

`/edit` with `mask_prompt` and no `mask_b64` does both passes in ONE request: one lock
acquisition, one resident model, no round trip carrying a 4 MB PNG back and forth.

Only one generation runs at a time (`_gpu_lock`). FastAPI dispatches sync handlers to a
threadpool, so without it two concurrent requests would both allocate against a card
already holding 33 GB of weights.

Run:
  uvicorn server:app --host 0.0.0.0 --port 9230 --workers 1

Environment variables:
  QWENIMAGE_MODEL              HF repo id or local path  (default: Qwen/Qwen-Image-2.1)
  QWENIMAGE_DEVICE             torch device              (default: cuda if available)
  QWENIMAGE_DTYPE              bfloat16 | float16 | float32   (default: bfloat16)
  QWENIMAGE_OFFLOAD            1 to enable model CPU offload  (default: 0)
  QWENIMAGE_STEPS              default inference steps   (default: 40, the card's value)
  QWENIMAGE_OUTPUT_RESOLUTION  target side length        (default: 1024; 2048 for native 2K)
  QWENIMAGE_MAX_IMAGES         cap on images_b64         (default: 10, the model's own cap)
  QWENIMAGE_HOST/_PORT         bind address              (default: 0.0.0.0 / 9230)
  CUDA_VISIBLE_DEVICES         pin a card. MEASURED need is ~46 GB at output_resolution
                               1024 and ~66 GB at 2048 - well above the 33 GB of weights,
                               because activations and the KV cache are never returned.
                               A100 80 GB or H200; set QWENIMAGE_OFFLOAD=1 below ~48 GB.

LICENCE: Qwen-Image-2.1 is released under the **Qwen RESEARCH LICENSE** (2026-09-20),
which grants use "FOR NON-COMMERCIAL PURPOSES ONLY" — research or evaluation. This is
NOT Apache-2.0, unlike Qwen-Image 1.0. It puts this backend alongside SDXL-Turbo and
Ideogram-4 rather than alongside MIT-licensed Mage-Flow, which remains the documented
default for shipping deployments. See spec/sidecars/QWEN_IMAGE_SIDECAR.md and
website/content/english/docs/legal/model-licenses/.

The resolved model id travels back on every image response as `X-Model-Id` and in the
PNG's tEXt chunks, so a node can record it as the result ledger's producerVersion.
"""

import base64
import io
import logging
import os
import threading
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from PIL import Image, PngImagePlugin
from pydantic import BaseModel

import mask_ops
import qwen_loader

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("qwen-image-server")

# `calculate_dimensions` in the pipeline rounds both sides to a multiple of 32, and the
# VAE compresses 16x with the transformer consuming latents unpatched. 2752 is the
# longest side in the model card's aspect-ratio table (16:9 at 2K).
MIN_SIDE = 256
MAX_SIDE = 2752
SIDE_MULTIPLE = 32

DEFAULT_STEPS = int(os.environ.get("QWENIMAGE_STEPS", "40"))
DEFAULT_OUTPUT_RESOLUTION = int(os.environ.get("QWENIMAGE_OUTPUT_RESOLUTION", "1024"))
MAX_IMAGES = int(os.environ.get("QWENIMAGE_MAX_IMAGES", "10"))

# One generation at a time - see module docstring.
_gpu_lock = threading.Lock()

# Appended to the caller's prompt when a mask rides along in the condition list. The
# model needs to be told that the last image is a mask rather than a further subject
# reference; without this it composes the black-and-white shape INTO the picture.
MASK_INSTRUCTION = (
    " The final image is a mask: apply the change only inside its white region and "
    "leave every pixel outside it exactly as it is."
)

# Asking for a mask is asking for a picture, so the prompt has to over-specify what a
# mask looks like. "No outlines, no text" is not padding - without it the model
# helpfully labels the region.
MASK_PROMPT = (
    "Output a binary segmentation mask of the input image. Fill the region containing "
    "{subject} with pure white (255, 255, 255). Fill absolutely everything else with "
    "pure black (0, 0, 0). Produce only these two colours: no grey, no shading, no "
    "gradients, no outlines, no text, no labels."
)


# --------------------------------------------------------------------------- #
# Pure helpers - no model, no HTTP. These are the parts worth unit-testing.
# --------------------------------------------------------------------------- #
def _side(value: Optional[int], name: str, default: Optional[int] = None) -> Optional[int]:
    """Validate one output dimension and snap it to the pipeline's 32-pixel grid.

    Out-of-range is an error rather than a clamp: a caller asking for 4096 wants
    something this checkpoint cannot do, and silently returning 2752 would hide that.
    A non-multiple of 32 is snapped, because 1000 -> 1024 is not a decision anyone
    needs to make and the PNG carries its own true size.

    Returns None when the caller passed None and there is no default - the pipeline
    then derives the size from the condition image's aspect ratio, which is what you
    want for an edit and cannot be expressed by picking a number here.
    """
    if value is None:
        return default
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


# _binarize / _composite live in mask_ops.py, which imports nothing but PIL so the mask
# arithmetic can be unit-tested without fastapi, torch or diffusers (test_mask_ops.py).
_binarize = mask_ops.binarize
_composite = mask_ops.composite


def _decode_image(image_b64: str, what: str = "image_b64") -> Image.Image:
    try:
        raw = base64.b64decode(image_b64, validate=True)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"{what} is not valid base64: {exc}") from exc
    try:
        return Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"could not decode {what}: {exc}") from exc


def _to_png(image: Image.Image, meta: dict) -> bytes:
    """Encode to PNG, stamping the parameters into tEXt chunks.

    The contract returns raw bytes, so this is the only channel a caller has for
    provenance other than the X-Model-Id header - and it is the one that survives the
    file being written to disk and picked up later.
    """
    info = PngImagePlugin.PngInfo()
    for key, value in meta.items():
        if value is not None:
            info.add_text(f"qwenimage:{key}", str(value))
    buf = io.BytesIO()
    image.save(buf, format="PNG", pnginfo=info)
    return buf.getvalue()


def _png_response(image: Image.Image, meta: dict) -> Response:
    return Response(
        content=_to_png(image, meta),
        media_type="image/png",
        headers={"X-Model-Id": str(meta.get("model", qwen_loader.DEFAULT_MODEL))},
    )


def _generator(seed: Optional[int]):
    if seed is None:
        return None
    import torch

    return torch.Generator(device=qwen_loader.DEVICE).manual_seed(int(seed))


# --------------------------------------------------------------------------- #
# The one call that touches the GPU
# --------------------------------------------------------------------------- #
def _load_or_explain():
    """Load the pipeline, turning a failure into a response that says what went wrong.

    Without this a load failure is a bare `500 Internal Server Error` with an empty body:
    FastAPI logs the traceback inside the container and tells the caller nothing. Loading
    is where the interesting failures live - a diffusers too old to carry the pipeline, a
    transformers too old for Qwen3-VL, a card too small for 33 GB - and every one of them
    is actionable if you can read it. 503 rather than 500 because the server is fine and
    the model is not.
    """
    try:
        return qwen_loader.load()
    except Exception as exc:
        logger.exception("Loading %s failed", qwen_loader.DEFAULT_MODEL)
        raise HTTPException(
            status_code=503,
            detail={
                "error": "the model could not be loaded",
                "model": qwen_loader.DEFAULT_MODEL,
                "device": qwen_loader.DEVICE,
                "dtype": qwen_loader.DTYPE_NAME,
                "offload": qwen_loader.OFFLOAD,
                "type": type(exc).__name__,
                "message": str(exc),
            },
        ) from exc


def _run(prompt: str, condition: Optional[List[Image.Image]], width: Optional[int],
         height: Optional[int], steps: int, seed: Optional[int], negative_prompt: Optional[str],
         true_cfg_scale: float, output_resolution: int) -> Image.Image:
    """One pipeline call, serialised on the GPU lock.

    `condition` is None for text-to-image and a list otherwise. The pipeline treats a
    flat list as one set of condition images applying to the whole batch, which is
    exactly the multi-reference semantics we want and the reason nothing here batches
    prompts.
    """
    pipe = _load_or_explain()
    kwargs = dict(
        prompt=prompt,
        negative_prompt=negative_prompt or None,
        true_cfg_scale=true_cfg_scale,
        num_inference_steps=steps,
        generator=_generator(seed),
        output_resolution=output_resolution,
    )
    if condition:
        kwargs["image"] = condition
    # width/height omitted entirely when None: the pipeline then derives them from the
    # last condition image's aspect ratio, which is what preserves an edit's framing.
    if width is not None:
        kwargs["width"] = width
    if height is not None:
        kwargs["height"] = height

    try:
        with _gpu_lock:
            return pipe(**kwargs).images[0]
    except HTTPException:
        raise
    except Exception as exc:
        # Most often CUDA OOM at a high output_resolution. Saying which knob to turn down
        # is the difference between an actionable error and "Internal Server Error".
        logger.exception("Generation failed")
        raise HTTPException(
            status_code=500,
            detail={
                "error": "generation failed",
                "type": type(exc).__name__,
                "message": str(exc),
                "output_resolution": output_resolution,
                "condition_images": len(condition) if condition else 0,
                "hint": "if this is out of memory, lower output_resolution or set QWENIMAGE_OFFLOAD=1",
            },
        ) from exc


def _derive_mask(source: Image.Image, mask_prompt: str, seed: Optional[int], steps: int,
                 output_resolution: int) -> Image.Image:
    """Draw a mask for `mask_prompt` over `source`, then normalise it to {0, 255}."""
    raw = _run(
        prompt=MASK_PROMPT.format(subject=mask_prompt),
        condition=[source],
        width=None, height=None,
        steps=steps, seed=seed,
        negative_prompt=None, true_cfg_scale=1.0,
        output_resolution=output_resolution,
    )
    return _binarize(raw, size=source.size)


# --------------------------------------------------------------------------- #
# Request models
# --------------------------------------------------------------------------- #
class GenerateRequest(BaseModel):
    prompt: str
    width: Optional[int] = None
    height: Optional[int] = None
    seed: Optional[int] = None
    steps: Optional[int] = None
    negative_prompt: Optional[str] = None
    true_cfg_scale: float = 1.0
    output_resolution: Optional[int] = None


class RemixRequest(BaseModel):
    image_b64: str
    prompt: str
    seed: Optional[int] = None
    steps: Optional[int] = None
    negative_prompt: Optional[str] = None
    true_cfg_scale: float = 1.0
    output_resolution: Optional[int] = None
    # Part of the sidecar contract, not of this model. Qwen-Image-2.1 is an
    # instruction-edit model with no denoise-strength knob, so this is accepted and
    # ignored - exactly as mage-flow does, and for the same reason.
    strength: Optional[float] = None


class EditRequest(BaseModel):
    prompt: str
    # 1..MAX_IMAGES. images_b64[0] is the image being edited; the rest are references.
    images_b64: List[str]
    mask_b64: Optional[str] = None
    mask_prompt: Optional[str] = None
    width: Optional[int] = None
    height: Optional[int] = None
    seed: Optional[int] = None
    steps: Optional[int] = None
    negative_prompt: Optional[str] = None
    true_cfg_scale: float = 1.0
    output_resolution: Optional[int] = None
    composite: bool = False


class MaskRequest(BaseModel):
    image_b64: str
    prompt: str
    seed: Optional[int] = None
    steps: Optional[int] = None
    output_resolution: Optional[int] = None


# --------------------------------------------------------------------------- #
# App
# --------------------------------------------------------------------------- #
app = FastAPI(title="Cortex Qwen-Image-2.1 sidecar")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "model": qwen_loader.DEFAULT_MODEL,
        "device": qwen_loader.DEVICE,
        "dtype": qwen_loader.DTYPE_NAME,
        "offload": qwen_loader.OFFLOAD,
        "capabilities": ["generate", "remix", "edit", "mask"],
        "defaults": {
            "steps": DEFAULT_STEPS,
            "outputResolution": DEFAULT_OUTPUT_RESOLUTION,
        },
        "resolution": {"min": MIN_SIDE, "max": MAX_SIDE, "multipleOf": SIDE_MULTIPLE},
        "maxImages": MAX_IMAGES,
        "licence": "Qwen RESEARCH LICENSE - non-commercial use only",
        # Empty until the first request - the checkpoint loads lazily.
        "loaded": qwen_loader.loaded_models(),
    }


def _require_prompt(prompt: str) -> str:
    if not prompt or not prompt.strip():
        raise HTTPException(status_code=400, detail="prompt must not be empty")
    return prompt


@app.post("/generate")
def generate(request: GenerateRequest):
    """Text-to-image. Raw PNG bytes - the sidecar contract the node already speaks."""
    prompt = _require_prompt(request.prompt)
    steps = request.steps or DEFAULT_STEPS
    resolution = request.output_resolution or DEFAULT_OUTPUT_RESOLUTION
    # A t2i call with neither side given still needs a size, hence the defaults here
    # (unlike /edit and /remix, where None means "follow the source").
    width = _side(request.width, "width", resolution)
    height = _side(request.height, "height", resolution)

    logger.info("generate: %dx%d steps=%d prompt=%r", width, height, steps, prompt[:80])
    image = _run(prompt, None, width, height, steps, request.seed,
                 request.negative_prompt, request.true_cfg_scale, resolution)
    return _png_response(image, {
        "model": qwen_loader.DEFAULT_MODEL, "mode": "generate", "prompt": prompt,
        "seed": request.seed, "steps": steps, "size": f"{width}x{height}",
    })


@app.post("/remix")
def remix(request: RemixRequest):
    """Instruction edit of one source image. Raw PNG bytes.

    Kept distinct from /edit so an existing pipeline pointed at this port keeps working
    unchanged; it is /edit with a single image and no mask.
    """
    prompt = _require_prompt(request.prompt)
    source = _decode_image(request.image_b64)
    steps = request.steps or DEFAULT_STEPS
    resolution = request.output_resolution or DEFAULT_OUTPUT_RESOLUTION

    if request.strength is not None:
        logger.info("remix: ignoring strength=%s - this model has no denoise strength",
                    request.strength)

    logger.info("remix: steps=%d prompt=%r", steps, prompt[:80])
    # width/height deliberately None: an edit keeps the source's aspect ratio, and the
    # pipeline derives the size from the condition image when neither is given.
    image = _run(prompt, [source], None, None, steps, request.seed,
                 request.negative_prompt, request.true_cfg_scale, resolution)
    return _png_response(image, {
        "model": qwen_loader.DEFAULT_MODEL, "mode": "remix", "prompt": prompt,
        "seed": request.seed, "steps": steps,
    })


@app.post("/edit")
def edit(request: EditRequest):
    """Multi-reference composition and masked regional editing.

    `images_b64[0]` is the image being edited; the rest are references the model may
    draw subjects, style or setting from. A mask - supplied directly as `mask_b64` or
    derived here from `mask_prompt` - is APPENDED to that list, because that is how
    this model consumes one (see the module docstring).
    """
    prompt = _require_prompt(request.prompt)
    if not request.images_b64:
        raise HTTPException(status_code=400, detail="images_b64 must hold at least one image")
    if len(request.images_b64) > MAX_IMAGES:
        raise HTTPException(
            status_code=400,
            detail=f"images_b64 must hold at most {MAX_IMAGES} images "
                   f"(QWENIMAGE_MAX_IMAGES), got {len(request.images_b64)}",
        )
    if request.mask_b64 and request.mask_prompt:
        raise HTTPException(
            status_code=400,
            detail="pass either mask_b64 or mask_prompt, not both - mask_prompt is how "
                   "you ask this server to derive the mask it would otherwise be given",
        )

    condition = [
        _decode_image(b, f"images_b64[{i}]") for i, b in enumerate(request.images_b64)
    ]
    steps = request.steps or DEFAULT_STEPS
    resolution = request.output_resolution or DEFAULT_OUTPUT_RESOLUTION
    target = condition[0]

    mask = None
    if request.mask_b64:
        mask = _binarize(_decode_image(request.mask_b64, "mask_b64"), size=target.size)
    elif request.mask_prompt:
        logger.info("edit: deriving a mask for %r first", request.mask_prompt[:60])
        mask = _derive_mask(target, request.mask_prompt, request.seed, steps, resolution)

    if mask is not None:
        # RGB because the condition list is encoded by a vision encoder that expects
        # three channels; the mask's information is in the luminance either way.
        condition.append(mask.convert("RGB"))
        prompt = prompt + MASK_INSTRUCTION

    logger.info("edit: %d condition image(s)%s steps=%d prompt=%r",
                len(condition), " (incl. mask)" if mask is not None else "", steps, prompt[:80])
    image = _run(prompt, condition, _side(request.width, "width"),
                 _side(request.height, "height"), steps, request.seed,
                 request.negative_prompt, request.true_cfg_scale, resolution)

    if request.composite:
        if mask is None:
            raise HTTPException(
                status_code=400,
                detail="composite=true needs a mask - pass mask_b64 or mask_prompt",
            )
        image = _composite(target, image, mask)

    return _png_response(image, {
        "model": qwen_loader.DEFAULT_MODEL, "mode": "edit", "prompt": prompt,
        "seed": request.seed, "steps": steps, "references": len(request.images_b64),
        "masked": mask is not None, "composited": request.composite,
    })


@app.post("/mask")
def mask(request: MaskRequest):
    """Text -> binary mask, using the same resident model.

    Returns an 8-bit greyscale PNG whose pixels are exactly 0 or 255, white over the
    named region. That polarity is a contract: `/edit` and `_composite` both read it.
    """
    prompt = _require_prompt(request.prompt)
    source = _decode_image(request.image_b64)
    steps = request.steps or DEFAULT_STEPS
    resolution = request.output_resolution or DEFAULT_OUTPUT_RESOLUTION

    logger.info("mask: steps=%d subject=%r", steps, prompt[:80])
    result = _derive_mask(source, prompt, request.seed, steps, resolution)
    return _png_response(result, {
        "model": qwen_loader.DEFAULT_MODEL, "mode": "mask", "prompt": prompt,
        "seed": request.seed, "steps": steps,
    })


if __name__ == "__main__":
    # Unlike the three older sidecars, HOST/PORT are read HERE as well as in run.sh, so
    # launching server.py directly honours them instead of silently binding a default.
    import uvicorn

    uvicorn.run(
        app,
        host=os.environ.get("QWENIMAGE_HOST", "0.0.0.0"),
        port=int(os.environ.get("QWENIMAGE_PORT", "9230")),
        workers=1,
    )
