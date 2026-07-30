"""
LTX-2 sidecar — FastAPI HTTP server for Lightricks' LTX-2 text/image-to-video model
(19B DiT audio-video, https://huggingface.co/Lightricks/LTX-2), served via diffusers.

It is the first *video*-generation sidecar in this folder. The contract is shaped after
the image sidecars (`sidecars/mage-flow-sidecar`) so a future Cortex `videogen` node can
be a thin HTTP client, but it produces MP4 rather than PNG:

  POST /generate     {prompt, negative_prompt?, width?, height?, num_frames?, fps?,
                      seed?, steps?, guidance?}                                        -> video/mp4
  POST /animate      {image_b64, prompt, ...same...}                                   -> video/mp4  (image-to-video)
  POST /v1/generate  same body as /generate                                           -> JSON (base64 mp4 + provenance)
  GET  /health                                                                        -> JSON

THINGS TO UNDERSTAND ABOUT THIS SERVER
--------------------------------------
* LTX-2 PRODUCES SYNCHRONISED AUDIO. The pipeline returns (video, audio) and we mux both
  into the MP4 via diffusers' `encode_video` — the clip has sound, not just frames. This
  is the model's headline feature and comes for free from the same forward.

* IT ONLY FITS 24 GB QUANTIZED. LTX-2 is a ~46B system (19B transformer + 27B text
  encoder); this sidecar quantizes both to bitsandbytes nf4 (see ltx_loader.py), peaking
  ~11 GB VRAM. All the VRAM handling lives there; this file is the HTTP layer.

* ONE GENERATION AT A TIME (`_gpu_lock`). FastAPI dispatches sync handlers to a
  threadpool; a single LTX-2 generation can consume the whole card, so two concurrent
  requests would OOM. The lock serialises them.

* LTX-2 HAS HARD SHAPE CONSTRAINTS (from the model card): width & height divisible by 32,
  and num_frames of the form `k*8 + 1` (9, 17, … 121). Cheap-to-correct values are
  snapped up and logged; num_frames past LTX2_MAX_FRAMES is a 400.

Run:
  uvicorn server:app --host 0.0.0.0 --port 9220 --workers 1

Environment variables (loading ones are documented in ltx_loader.py):
  LTX2_MODEL             checkpoint repo         (default: Lightricks/LTX-2)
  LTX2_QUANTIZE          nf4 | none              (default: nf4 on CUDA)
  LTX2_CPU_OFFLOAD       none | model | sequential   (default: model)
  LTX2_VAE_TILING        1 to enable VAE tiling      (default: 1)
  LTX2_STEPS             inference steps             (default: 40)
  LTX2_GUIDANCE          guidance scale              (default: 4.0)
  LTX2_MAX_FRAMES        hard cap on num_frames      (default: 121)
  CUDA_VISIBLE_DEVICES   pin a GPU.

Licence: LTX-2 Community License (see the model card). The resolved model id + quantize
mode travel back in every JSON response so a node can record them as producerVersion.
"""

import base64
import io
import logging
import os
import tempfile
import threading
import time
from typing import Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel

import ltx_loader

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("ltx2-server")

# --- Shape constraints (from the LTX-2 model card) --------------------------- #
SIDE_MULTIPLE = 32           # width & height must be divisible by 32
FRAME_MODULO = 8             # num_frames must be (k * 8) + 1
FRAME_REMAINDER = 1
MIN_SIDE = 32
MAX_SIDE = 1280

DEFAULT_WIDTH = 768
DEFAULT_HEIGHT = 512
DEFAULT_NUM_FRAMES = 49      # ~2s at 24fps — a "small clip"
DEFAULT_FPS = 24
# The dev transformer subfolder (what we quantize) is tuned for 40 steps at guidance 4.0.
DEFAULT_STEPS = int(os.environ.get("LTX2_STEPS", "40"))
DEFAULT_GUIDANCE = float(os.environ.get("LTX2_GUIDANCE", "4.0"))
MAX_FRAMES = int(os.environ.get("LTX2_MAX_FRAMES", "121"))
DEFAULT_NEG_PROMPT = "worst quality, inconsistent motion, blurry, jittery, distorted"

# One generation at a time — see module docstring.
_gpu_lock = threading.Lock()


# --------------------------------------------------------------------------- #
# Parameter resolution / validation
# --------------------------------------------------------------------------- #
def _side(value: Optional[int], name: str, default: int) -> int:
    """Validate a spatial dimension and snap it up to the 32-pixel grid."""
    if value is None:
        return default
    value = int(value)
    if value < MIN_SIDE or value > MAX_SIDE:
        raise HTTPException(
            status_code=400,
            detail=f"{name} must be between {MIN_SIDE} and {MAX_SIDE}, got {value}",
        )
    if value % SIDE_MULTIPLE:
        snapped = min(((value + SIDE_MULTIPLE - 1) // SIDE_MULTIPLE) * SIDE_MULTIPLE, MAX_SIDE)
        logger.info("Snapped %s %d -> %d (multiple of %d)", name, value, snapped, SIDE_MULTIPLE)
        return snapped
    return value


def _frames(value: Optional[int]) -> int:
    """Validate num_frames and snap it up to the nearest valid `k*8 + 1`."""
    if value is None:
        return DEFAULT_NUM_FRAMES
    value = int(value)
    if value < FRAME_REMAINDER:
        raise HTTPException(status_code=400, detail=f"num_frames must be >= {FRAME_REMAINDER}, got {value}")
    if value > MAX_FRAMES:
        raise HTTPException(
            status_code=400,
            detail=f"num_frames must be <= {MAX_FRAMES} (LTX2_MAX_FRAMES); got {value}",
        )
    if value % FRAME_MODULO != FRAME_REMAINDER:
        snapped = ((value - FRAME_REMAINDER + FRAME_MODULO) // FRAME_MODULO) * FRAME_MODULO + FRAME_REMAINDER
        snapped = min(snapped, MAX_FRAMES)
        logger.info("Snapped num_frames %d -> %d (k*%d + %d)", value, snapped, FRAME_MODULO, FRAME_REMAINDER)
        return snapped
    return value


def _generator(seed: Optional[int]):
    if seed is None:
        return None
    import torch

    return torch.Generator(device=ltx_loader.DEVICE).manual_seed(int(seed))


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
# MP4 encoding (video + LTX-2's synchronised audio)
# --------------------------------------------------------------------------- #
def _to_mp4(pipe, video, audio, fps: int) -> bytes:
    """Mux the generated frames and audio into MP4 bytes via diffusers' encode_video.

    encode_video only writes to a path, so we round-trip through a temp file and read the
    bytes back — the sidecar returns bytes, never a server-side path. Audio is optional:
    if the model returned none we still write a silent clip.
    """
    from diffusers.utils import encode_video

    with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as handle:
        path = handle.name
    try:
        kwargs = {}
        if audio is not None:
            kwargs["audio"] = audio[0].float().cpu()
            kwargs["audio_sample_rate"] = pipe.vocoder.config.output_sampling_rate
        encode_video(video[0], fps=float(fps), output_path=path, **kwargs)
        with open(path, "rb") as fh:
            return fh.read()
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


# --------------------------------------------------------------------------- #
# Generation
# --------------------------------------------------------------------------- #
def _run(prompt: str, neg_prompt: str, width: int, height: int, num_frames: int, fps: int,
         seed: Optional[int], steps: Optional[int], guidance: Optional[float], image=None):
    """Text-to-video (image=None) or image-to-video. Returns (mp4_bytes, meta)."""
    steps = int(steps) if steps else DEFAULT_STEPS
    guidance = float(guidance) if guidance is not None else DEFAULT_GUIDANCE

    pipe = ltx_loader.load_i2v() if image is not None else ltx_loader.load()
    kwargs = dict(
        prompt=prompt,
        negative_prompt=neg_prompt,
        width=width,
        height=height,
        num_frames=num_frames,
        frame_rate=float(fps),
        num_inference_steps=steps,
        guidance_scale=guidance,
        generator=_generator(seed),
        output_type="np",
        return_dict=False,
    )
    if image is not None:
        kwargs["image"] = image

    started = time.monotonic()
    with _gpu_lock:
        video, audio = pipe(**kwargs)
    mp4 = _to_mp4(pipe, video, audio, fps)
    elapsed_ms = int((time.monotonic() - started) * 1000)

    meta = {
        "model": ltx_loader.MODEL_REPO,
        "quantize": ltx_loader.QUANTIZE,
        "width": width,
        "height": height,
        "numFrames": num_frames,
        "fps": fps,
        "steps": steps,
        "guidance": guidance,
        "seed": seed,
        "hasAudio": audio is not None,
        "elapsedMs": elapsed_ms,
    }
    logger.info(
        "Generated %d-frame %dx%d clip in %d ms (steps=%d guidance=%s audio=%s%s)",
        num_frames, width, height, elapsed_ms, steps, guidance, audio is not None,
        ", image-to-video" if image is not None else "",
    )
    return mp4, meta


# --------------------------------------------------------------------------- #
# HTTP API
# --------------------------------------------------------------------------- #
class GenerateRequest(BaseModel):
    prompt: str
    negative_prompt: Optional[str] = None
    width: Optional[int] = None
    height: Optional[int] = None
    num_frames: Optional[int] = None
    fps: Optional[int] = None
    seed: Optional[int] = None
    steps: Optional[int] = None
    # Guidance scale. Absent means the checkpoint's tuned default.
    guidance: Optional[float] = None


class AnimateRequest(GenerateRequest):
    # Start image for image-to-video conditioning.
    image_b64: str


app = FastAPI(title="Cortex LTX-2 video sidecar")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "device": ltx_loader.DEVICE,
        "dtype": ltx_loader.DTYPE_SETTING,
        "cpuOffload": ltx_loader.CPU_OFFLOAD,
        "vaeTiling": ltx_loader.VAE_TILING,
        "model": ltx_loader.MODEL_REPO,
        "quantize": ltx_loader.QUANTIZE,
        "defaults": {
            "width": DEFAULT_WIDTH, "height": DEFAULT_HEIGHT,
            "numFrames": DEFAULT_NUM_FRAMES, "fps": DEFAULT_FPS,
            "steps": DEFAULT_STEPS, "guidance": DEFAULT_GUIDANCE,
        },
        "constraints": {
            "sideMultipleOf": SIDE_MULTIPLE, "minSide": MIN_SIDE, "maxSide": MAX_SIDE,
            "numFramesModulo": f"k*{FRAME_MODULO}+{FRAME_REMAINDER}", "maxFrames": MAX_FRAMES,
        },
        # Empty until the first request — the checkpoint loads lazily.
        "loaded": ltx_loader.loaded_models(),
    }


def _resolve(req: GenerateRequest):
    if not req.prompt or not req.prompt.strip():
        raise HTTPException(status_code=400, detail="prompt must not be empty")
    width = _side(req.width, "width", DEFAULT_WIDTH)
    height = _side(req.height, "height", DEFAULT_HEIGHT)
    num_frames = _frames(req.num_frames)
    fps = int(req.fps) if req.fps else DEFAULT_FPS
    neg = req.negative_prompt if req.negative_prompt is not None else DEFAULT_NEG_PROMPT
    return neg, width, height, num_frames, fps


@app.post("/generate")
def generate(request: GenerateRequest):
    """Text-to-video. Returns raw MP4 bytes (with audio) — the sidecar contract a node speaks."""
    neg, width, height, num_frames, fps = _resolve(request)
    mp4, _ = _run(request.prompt, neg, width, height, num_frames, fps,
                  request.seed, request.steps, request.guidance)
    return Response(content=mp4, media_type="video/mp4")


@app.post("/animate")
def animate(request: AnimateRequest):
    """Image-to-video: condition the clip on a start image. Returns raw MP4 bytes."""
    if not request.image_b64 or not request.image_b64.strip():
        raise HTTPException(status_code=400, detail="image_b64 must not be empty")
    neg, width, height, num_frames, fps = _resolve(request)
    image = _decode_image(request.image_b64)
    mp4, _ = _run(request.prompt, neg, width, height, num_frames, fps,
                  request.seed, request.steps, request.guidance, image=image)
    return Response(content=mp4, media_type="video/mp4")


@app.post("/v1/generate")
def generate_json(request: GenerateRequest):
    """Text-to-video returning JSON: base64 MP4 plus the parameters actually used.

    Exists alongside /generate because a raw-MP4 response cannot carry the resolved model
    id / transformer file / seed / frame count a caller needs to record provenance.
    """
    neg, width, height, num_frames, fps = _resolve(request)
    mp4, meta = _run(request.prompt, neg, width, height, num_frames, fps,
                     request.seed, request.steps, request.guidance)
    return {**meta, "prompt": request.prompt, "video_b64": base64.b64encode(mp4).decode("ascii")}
