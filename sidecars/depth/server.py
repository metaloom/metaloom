"""
Depth sidecar — FastAPI HTTP server for the Cortex `depthmap` node.

One endpoint, `POST /v1/depth`, runs monocular depth estimation on a single image:

  * relative (default) → depth-anything/Depth-Anything-V2-Small-hf   (Apache-2.0)
  * metric  (opt-in)   → Intel/zoedepth-nyu-kitti                    (MIT)

THE ONE THING TO UNDERSTAND ABOUT THIS SERVER
---------------------------------------------
Raw monocular-depth output is not consistent between model families. Depth Anything
and MiDaS emit a *disparity*-like value where BIGGER = CLOSER; ZoeDepth emits metres,
where BIGGER = FARTHER. Shipping that ambiguity down the pipeline would guarantee an
inverted-relation bug in every consumer — one that reports SUCCESS and silently says
the background is in front of the subject.

So this server normalises everything to exactly one convention:

    NEARNESS — a float in [0, 1] where 1.0 is NEAREST to the camera and 0.0 is
    farthest, encoded as a 16-bit grayscale PNG in which 65535 is nearest.

Metric models are min-max normalised and then INVERTED, and their metre range travels
back in the response so absolute distance stays recoverable. Doing the conversion here
keeps it in one place, out of Java, and out of every future consumer.

16 bits, not 8: an 8-bit map quantises to 256 levels, which is coarse enough that two
objects at similar distance land in the same bucket and their ordering collapses.

Three responsibilities live here rather than in the Java node, so that swapping a
checkpoint never touches Java:

  1. Model routing — RELATIVE vs METRIC picks the checkpoint, with a per-request
     override.
  2. Normalisation — see _normalize() below. The single source of truth for the
     NEARNESS convention.
  3. Encoding — 16-bit PNG, written with cv2 rather than PIL's version-dependent
     "I;16" mode.

The Java `DepthmapNode` is a pure HTTP client of this server
(io.metaloom.cortex.node.depthmap).

Run:
  uvicorn server:app --host 0.0.0.0 --port 9120

Environment variables:
  DEPTH_MODEL         relative checkpoint  (default: depth-anything/Depth-Anything-V2-Small-hf)
  DEPTH_MODEL_METRIC  metric checkpoint    (default: Intel/zoedepth-nyu-kitti)
  DEPTH_MAX_DIM       server-side cap on the longest side (default: 1024)
  DEVICE              torch device         (default: cuda if available else cpu)

Licence note: the default is Apache-2.0 and is the only permissively licensed member
of the Depth Anything V2 family — the Base and Large variants are CC-BY-NC-4.0 and are
NOT usable commercially. The model id travels back in every response so the node can
record it as the ledger's producerVersion.
"""

import base64
import io
import logging
import os
from typing import Dict, Optional

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("depth-server")

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #
MODEL_RELATIVE = os.environ.get("DEPTH_MODEL", "depth-anything/Depth-Anything-V2-Small-hf")
MODEL_METRIC = os.environ.get("DEPTH_MODEL_METRIC", "Intel/zoedepth-nyu-kitti")
MAX_DIM = int(os.environ.get("DEPTH_MAX_DIM", "1024"))

# The convention every response carries. Constant on purpose: the node rejects
# anything else rather than guessing which way "closer" runs.
CONVENTION = "NEARNESS"

SOURCE_RELATIVE = "RELATIVE"
SOURCE_METRIC = "METRIC"


def _default_device() -> str:
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


DEVICE = os.environ.get("DEVICE", _default_device())

# Lazily-loaded pipelines, keyed by model id. The first request for a checkpoint pays
# the download and load; an unused checkpoint is never loaded at all.
_pipelines: Dict[str, object] = {}


def _model_id(mode: str, override: Optional[str]) -> str:
    if override and override.strip():
        return override.strip()
    return MODEL_METRIC if mode == SOURCE_METRIC else MODEL_RELATIVE


def _pipeline(model_id: str):
    pipe = _pipelines.get(model_id)
    if pipe is None:
        from transformers import pipeline as hf_pipeline

        logger.info("Loading depth model %s on %s", model_id, DEVICE)
        pipe = hf_pipeline("depth-estimation", model=model_id, device=DEVICE)
        _pipelines[model_id] = pipe
    return pipe


# --------------------------------------------------------------------------- #
# Normalisation — the NEARNESS convention
# --------------------------------------------------------------------------- #
def _normalize(raw: np.ndarray, source: str):
    """
    Map raw model output onto NEARNESS in [0, 1], 1 = nearest.

    RELATIVE models already run "bigger = nearer" (they predict disparity), so a plain
    min-max is enough. METRIC models run "bigger = farther" (metres), so the same
    min-max is inverted and the real metre range is reported alongside.

    Returns (nearness, metric) where metric is None for relative models.
    """
    raw = raw.astype(np.float32)
    lo = float(np.nanmin(raw))
    hi = float(np.nanmax(raw))

    # A constant map (a blank wall, or a model failure) has no gradient to normalise.
    # Return a flat mid-grey rather than dividing by zero — the consumer's spread-based
    # confidence will correctly refuse to assert any ordering from it.
    if not np.isfinite(lo) or not np.isfinite(hi) or hi - lo < 1e-9:
        return np.full(raw.shape, 0.5, dtype=np.float32), None

    unit = (raw - lo) / (hi - lo)
    if source == SOURCE_METRIC:
        return (1.0 - unit).astype(np.float32), {"min_m": round(lo, 4), "max_m": round(hi, 4)}
    return unit.astype(np.float32), None


def _encode_png16(nearness: np.ndarray) -> str:
    """Encode nearness [0,1] as a base64 16-bit grayscale PNG (65535 = nearest)."""
    import cv2

    scaled = np.clip(nearness, 0.0, 1.0) * 65535.0
    ok, buf = cv2.imencode(".png", scaled.astype(np.uint16))
    if not ok:
        raise HTTPException(status_code=500, detail="failed to encode the depth map as PNG")
    return base64.b64encode(buf.tobytes()).decode("ascii")


def _downscale(image, max_dim: int):
    """Cap the longest side. Never upscales — that costs inference time for no detail."""
    if max_dim <= 0:
        return image
    longest = max(image.width, image.height)
    if longest <= max_dim:
        return image
    factor = max_dim / longest
    size = (max(1, round(image.width * factor)), max(1, round(image.height * factor)))
    from PIL import Image

    return image.resize(size, Image.BILINEAR)


def _estimate(image_b64: str, mode: str, model_override: Optional[str], max_dim: Optional[int]):
    from PIL import Image

    try:
        raw_bytes = base64.b64decode(image_b64, validate=True)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"image_b64 is not valid base64: {exc}") from exc

    try:
        image = Image.open(io.BytesIO(raw_bytes)).convert("RGB")
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"could not decode the image: {exc}") from exc

    # The client may ask for less than the server cap, never more.
    effective_dim = min(max_dim or MAX_DIM, MAX_DIM)
    image = _downscale(image, effective_dim)

    model_id = _model_id(mode, model_override)
    result = _pipeline(model_id)(image)

    predicted = result["predicted_depth"] if "predicted_depth" in result else result["depth"]
    # transformers returns either a torch tensor or a PIL image depending on the model.
    if hasattr(predicted, "detach"):
        raw = predicted.detach().cpu().numpy()
    else:
        raw = np.array(predicted)
    raw = np.squeeze(raw)
    if raw.ndim != 2:
        raise HTTPException(status_code=500, detail=f"unexpected depth shape {raw.shape}")

    nearness, metric = _normalize(raw, mode)

    height, width = nearness.shape
    payload = {
        "model": model_id,
        "convention": CONVENTION,
        "source": mode,
        "width": int(width),
        "height": int(height),
        "png_b64": _encode_png16(nearness),
        "stats": {
            "p05": round(float(np.percentile(nearness, 5)), 6),
            "p50": round(float(np.percentile(nearness, 50)), 6),
            "p95": round(float(np.percentile(nearness, 95)), 6),
        },
    }
    if metric is not None:
        payload["metric"] = metric
    return payload


# --------------------------------------------------------------------------- #
# HTTP API
# --------------------------------------------------------------------------- #
class DepthRequest(BaseModel):
    image_b64: str
    # RELATIVE (default) or METRIC. Selects which checkpoint runs and therefore
    # whether a metre range comes back.
    mode: Optional[str] = SOURCE_RELATIVE
    # Per-request checkpoint override; absent means the DEPTH_MODEL* env default.
    model: Optional[str] = None
    # Longest side sent through the model. Clamped to DEPTH_MAX_DIM.
    max_dim: Optional[int] = None


app = FastAPI(title="Cortex depth sidecar")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "device": DEVICE,
        "convention": CONVENTION,
        "models": {"relative": MODEL_RELATIVE, "metric": MODEL_METRIC},
        "maxDim": MAX_DIM,
        "loaded": sorted(_pipelines.keys()),
    }


@app.post("/v1/depth")
def depth(request: DepthRequest):
    if not request.image_b64 or not request.image_b64.strip():
        raise HTTPException(status_code=400, detail="image_b64 must not be empty")

    mode = (request.mode or SOURCE_RELATIVE).strip().upper()
    if mode not in (SOURCE_RELATIVE, SOURCE_METRIC):
        raise HTTPException(status_code=400, detail=f"mode must be RELATIVE or METRIC, got '{mode}'")

    return _estimate(request.image_b64, mode, request.model, request.max_dim)
