"""
SAM 2 sidecar — FastAPI HTTP server for the Cortex `sam2` node.

Two endpoints cover the three things SAM 2 does:

  * POST /v1/segment  mode=AUTOMATIC  → segment everything in one still image
  * POST /v1/segment  mode=PROMPTED   → one mask per box an upstream detector found
  * POST /v1/track                    → prompt on one frame, propagate through a clip

THE ONE THING TO UNDERSTAND ABOUT THIS SERVER
---------------------------------------------
Every coordinate that crosses this boundary is in the space of the image that was
actually POSTed, never the source file. The node downscales to `max_dim` before
encoding, sends boxes already rescaled into that space, and gets masks back at
exactly the `width`/`height` this server reports. The server never learns the source
resolution and must never try to infer it.

That is the same trap the depth sidecar documents: a map returned at 1024px against
boxes measured at 4000px projects to nonsense while every status code says 200. The
node carries both dimension pairs in its manifest so a consumer can project back.

WHY transformers AND NOT THE `sam2` PyPI PACKAGE
------------------------------------------------
`pip install sam2` is a THIRD-PARTY upload, not Meta's. Meta's own instruction is
`git clone github.com/facebookresearch/sam2 && pip install -e .`, which builds a CUDA
extension (it prints "Failed to build the SAM 2 CUDA extension" on plenty of machines)
and pins hydra. transformers ships Sam2Model / Sam2VideoModel / Sam2Processor /
Sam2VideoProcessor and the "mask-generation" pipeline natively, needs no build step,
and is the stack sidecars/depth already runs.

Masks come back as 8-bit grayscale PNGs whose pixels are 0 or 255. 8 bits, not 1: a
1-bit PNG is a decoding hazard in Java's ImageIO, and the size difference after
deflate is negligible for a two-value image.

Concurrency: one GPU lock and `--workers 1`. `points_per_side=32` is 1024 forward
passes for a single image, and the video predictor holds a per-request memory bank
that grows with objects x frames. Two concurrent requests do not go faster; they OOM.
The Java node declares `defaultConcurrency = 1` to say so; this lock is what
guarantees it when something else calls the server.

The Java `Sam2Node` is a pure HTTP client of this server (io.metaloom.cortex.node.sam2).

Run:
  uvicorn server:app --host 0.0.0.0 --port 9130 --workers 1

Environment variables:
  SAM2_MODEL       checkpoint             (default: facebook/sam2.1-hiera-small)
  SAM2_MAX_DIM     cap on the longest side (default: 1024)
  SAM2_MAX_FRAMES  cap on frames per /v1/track request (default: 128)
  DEVICE           torch device           (default: cuda if available else cpu)

Licence note: SAM 2 code and the 2.1 checkpoints are Apache-2.0 — unlike the depth
family, every member is usable commercially. The resolved model id travels back in
every response so the node can record it as the ledger's producerVersion.
"""

import base64
import io
import logging
import os
import threading
from typing import Dict, List, Optional

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("sam2-server")

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #
MODEL_DEFAULT = os.environ.get("SAM2_MODEL", "facebook/sam2.1-hiera-small")
MAX_DIM = int(os.environ.get("SAM2_MAX_DIM", "1024"))
MAX_FRAMES = int(os.environ.get("SAM2_MAX_FRAMES", "128"))

MODE_AUTOMATIC = "AUTOMATIC"
MODE_PROMPTED = "PROMPTED"
MODE_TRACK = "TRACK"


def _default_device() -> str:
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


DEVICE = os.environ.get("DEVICE", _default_device())

# Lazily-loaded, keyed by model id. Three separate caches because the three entry
# points want three different objects out of the same checkpoint, and loading one
# should not pay for the others.
_pipelines: Dict[str, object] = {}          # mask-generation (AUTOMATIC)
_image_models: Dict[str, object] = {}       # (Sam2Model, Sam2Processor) (PROMPTED)
_video_models: Dict[str, object] = {}       # (Sam2VideoModel, Sam2VideoProcessor) (TRACK)

# Serialises every forward pass. See the module docstring.
_gpu_lock = threading.Lock()


def _model_id(override: Optional[str]) -> str:
    if override and override.strip():
        return override.strip()
    return MODEL_DEFAULT


def _pipeline(model_id: str):
    pipe = _pipelines.get(model_id)
    if pipe is None:
        from transformers import pipeline as hf_pipeline

        logger.info("Loading SAM 2 mask-generation pipeline %s on %s", model_id, DEVICE)
        pipe = hf_pipeline("mask-generation", model=model_id, device=DEVICE)
        _pipelines[model_id] = pipe
    return pipe


def _image_model(model_id: str):
    entry = _image_models.get(model_id)
    if entry is None:
        from transformers import Sam2Model, Sam2Processor

        logger.info("Loading SAM 2 image model %s on %s", model_id, DEVICE)
        model = Sam2Model.from_pretrained(model_id).to(DEVICE)
        model.eval()
        entry = (model, Sam2Processor.from_pretrained(model_id))
        _image_models[model_id] = entry
    return entry


def _video_model(model_id: str):
    entry = _video_models.get(model_id)
    if entry is None:
        from transformers import Sam2VideoModel, Sam2VideoProcessor

        logger.info("Loading SAM 2 video model %s on %s", model_id, DEVICE)
        model = Sam2VideoModel.from_pretrained(model_id).to(DEVICE)
        model.eval()
        entry = (model, Sam2VideoProcessor.from_pretrained(model_id))
        _video_models[model_id] = entry
    return entry


# --------------------------------------------------------------------------- #
# Image helpers
# --------------------------------------------------------------------------- #
def _decode(image_b64: str, what: str):
    from PIL import Image

    try:
        raw = base64.b64decode(image_b64, validate=True)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"{what} is not valid base64: {exc}") from exc
    try:
        return Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"could not decode {what}: {exc}") from exc


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


def _encode_mask_png(mask: np.ndarray) -> str:
    """Encode a boolean mask as a base64 8-bit grayscale PNG (255 inside, 0 outside)."""
    import cv2

    ok, buf = cv2.imencode(".png", (np.asarray(mask).astype(np.uint8) * 255))
    if not ok:
        raise HTTPException(status_code=500, detail="failed to encode a mask as PNG")
    return base64.b64encode(buf.tobytes()).decode("ascii")


def _mask_bbox(mask: np.ndarray):
    """Tight box around the set pixels, as {x, y, w, h}. None for an empty mask."""
    ys, xs = np.nonzero(np.asarray(mask))
    if xs.size == 0:
        return None
    x0, x1 = int(xs.min()), int(xs.max())
    y0, y1 = int(ys.min()), int(ys.max())
    return {"x": x0, "y": y0, "w": x1 - x0 + 1, "h": y1 - y0 + 1}


def _as_bool_2d(mask) -> np.ndarray:
    """Squeeze whatever transformers handed back down to one 2-D boolean plane."""
    if hasattr(mask, "detach"):
        mask = mask.detach().cpu().numpy()
    arr = np.squeeze(np.asarray(mask))
    if arr.ndim == 3:
        # A multimask head that was not asked for, or a leading channel. Take the first.
        arr = arr[0]
    if arr.ndim != 2:
        raise HTTPException(status_code=500, detail=f"unexpected mask shape {arr.shape}")
    return arr.astype(bool)


def _mask_record(mask, index: int, score: Optional[float], label: Optional[str],
                 prompt_index: Optional[int], obj_id: Optional[int], min_area: int):
    """One entry of the `masks` array, or None when it is empty or below min_area."""
    arr = _as_bool_2d(mask)
    area = int(arr.sum())
    if area <= 0 or area < min_area:
        return None
    record = {
        "index": index,
        "png_b64": _encode_mask_png(arr),
        "area": area,
        "bbox": _mask_bbox(arr),
    }
    if score is not None:
        record["score"] = round(float(score), 6)
    if label is not None:
        record["label"] = label
    if prompt_index is not None:
        record["promptIndex"] = prompt_index
    if obj_id is not None:
        record["objId"] = obj_id
    return record


# --------------------------------------------------------------------------- #
# AUTOMATIC — segment everything
# --------------------------------------------------------------------------- #
def _segment_automatic(image, model_id: str, request: "SegmentRequest"):
    pipe = _pipeline(model_id)
    with _gpu_lock:
        outputs = pipe(image, points_per_side=request.points_per_side,
                       points_per_batch=request.points_per_batch)

    masks = outputs.get("masks") or []
    scores = outputs.get("scores")
    scores = [] if scores is None else [float(s) for s in np.asarray(
        scores.detach().cpu() if hasattr(scores, "detach") else scores).reshape(-1)]

    # The transformers "mask-generation" pipeline does not expose SAM 2's own
    # pred_iou / stability thresholds, so they are applied here as a post-filter on the
    # score it does return. The node's contract is the same either way; what differs is
    # that stability_score_thresh has no analogue in this path and is ignored.
    kept = []
    for i, mask in enumerate(masks):
        score = scores[i] if i < len(scores) else None
        if score is not None and score < request.pred_iou_thresh:
            continue
        kept.append((mask, score))

    # Biggest first: a segment-everything result is unordered, and a consumer that
    # truncates to max_masks should keep the ones a human would call the subject.
    records = []
    for mask, score in kept:
        record = _mask_record(mask, len(records), score, None, None, None, request.min_mask_area)
        if record is not None:
            records.append(record)
    records.sort(key=lambda r: r["area"], reverse=True)
    return records


# --------------------------------------------------------------------------- #
# PROMPTED — one mask per box
# --------------------------------------------------------------------------- #
def _segment_prompted(image, model_id: str, request: "SegmentRequest"):
    import torch

    model, processor = _image_model(model_id)
    boxes = [[b.x1, b.y1, b.x2, b.y2] for b in request.boxes]

    inputs = processor(images=image, input_boxes=[boxes], return_tensors="pt").to(DEVICE)
    with _gpu_lock, torch.no_grad():
        outputs = model(**inputs, multimask_output=request.multimask)

    # (num_boxes, candidates_per_box, H, W) — candidates_per_box is 3 with
    # multimask_output=True and 1 without, so iterating it needs no special case.
    masks = processor.post_process_masks(outputs.pred_masks.cpu(), inputs["original_sizes"])[0]
    iou = None
    if outputs.iou_scores is not None:
        iou = np.asarray(outputs.iou_scores.detach().cpu()).reshape(len(boxes), -1)

    records = []
    for prompt_index, prompt in enumerate(request.boxes):
        for candidate_index, candidate in enumerate(masks[prompt_index]):
            score = None
            if iou is not None and candidate_index < iou.shape[1]:
                score = float(iou[prompt_index][candidate_index])
            record = _mask_record(candidate, len(records), score, prompt.label,
                                  prompt_index, prompt.obj_id, request.min_mask_area)
            if record is not None:
                records.append(record)
    return records


# --------------------------------------------------------------------------- #
# TRACK — propagate through a clip
# --------------------------------------------------------------------------- #
def _track(request: "TrackRequest", model_id: str):
    import torch

    model, processor = _video_model(model_id)

    effective_dim = min(request.max_dim or MAX_DIM, MAX_DIM)
    frames = [_downscale(_decode(b64, f"frames_b64[{i}]"), effective_dim)
              for i, b64 in enumerate(request.frames_b64)]
    width, height = frames[0].width, frames[0].height

    labels = {p.obj_id: p.box.label for p in request.prompts if p.box.label is not None}
    session = processor.init_video_session(video=frames, inference_device=DEVICE)
    try:
        for prompt in request.prompts:
            box = prompt.box
            processor.add_inputs_to_inference_session(
                inference_session=session,
                frame_idx=prompt.frame_index,
                obj_ids=[prompt.obj_id],
                input_boxes=[[[box.x1, box.y1, box.x2, box.y2]]])

        frames_out = []
        dropped_masks = 0
        with _gpu_lock, torch.no_grad():
            for output in model.propagate_in_video_iterator(inference_session=session):
                frame_index = int(output.frame_idx)
                video_masks = processor.post_process_masks(
                    [output.pred_masks], original_sizes=[[height, width]], binarize=True)[0]

                records = []
                for slot, obj_id in enumerate(session.obj_ids):
                    if len(records) >= request.max_masks:
                        dropped_masks += 1
                        continue
                    record = _mask_record(video_masks[slot], len(records), None,
                                          labels.get(int(obj_id)), None, int(obj_id), 0)
                    if record is not None:
                        records.append(record)

                frames_out.append({
                    "index": frame_index,
                    "frameNumber": (request.frame_numbers[frame_index]
                                    if request.frame_numbers and frame_index < len(request.frame_numbers)
                                    else frame_index),
                    "masks": records,
                })
    finally:
        # A leaked session is both a VRAM leak and a correctness bug: the next request's
        # propagation would still be tracking this request's obj_ids.
        try:
            session.reset_inference_session()
        except Exception:  # pragma: no cover - best effort teardown
            logger.warning("Failed to reset the SAM 2 video session", exc_info=True)

    return {
        "model": model_id,
        "mode": MODE_TRACK,
        "width": int(width),
        "height": int(height),
        "frameCount": len(frames),
        "objects": [{"objId": p.obj_id, "label": p.box.label} for p in request.prompts],
        "frames": frames_out,
        "truncated": {"frames": 0, "masks": dropped_masks},
    }


# --------------------------------------------------------------------------- #
# HTTP API
# --------------------------------------------------------------------------- #
class Box(BaseModel):
    # XYXY, absolute pixels OF THE POSTED IMAGE. The node rescales before sending; this
    # server never sees the source resolution and must not have to guess it.
    x1: float
    y1: float
    x2: float
    y2: float
    label: Optional[str] = None
    obj_id: Optional[int] = None


class SegmentRequest(BaseModel):
    image_b64: str
    mode: Optional[str] = MODE_AUTOMATIC
    model: Optional[str] = None
    max_dim: Optional[int] = None
    # PROMPTED
    boxes: Optional[List[Box]] = None
    multimask: bool = False
    # AUTOMATIC
    points_per_side: int = 32
    points_per_batch: int = 64
    pred_iou_thresh: float = 0.8
    stability_score_thresh: float = 0.95
    # both
    min_mask_area: int = 0
    max_masks: int = 64


class TrackPrompt(BaseModel):
    obj_id: int
    # An index INTO frames_b64, not a source frame number. The node owns the mapping.
    frame_index: int
    box: Box


class TrackRequest(BaseModel):
    frames_b64: List[str]
    # Source frame number per entry, echoed back verbatim so the node never has to
    # reconstruct it from a chop rate this server does not know.
    frame_numbers: Optional[List[int]] = None
    model: Optional[str] = None
    max_dim: Optional[int] = None
    prompts: List[TrackPrompt]
    max_masks: int = 16


app = FastAPI(title="Cortex SAM 2 sidecar")


@app.get("/health")
def health():
    loaded = sorted(set(_pipelines) | set(_image_models) | set(_video_models))
    return {
        "status": "ok",
        "device": DEVICE,
        "models": {"default": MODEL_DEFAULT},
        "maxDim": MAX_DIM,
        "maxFrames": MAX_FRAMES,
        "loaded": loaded,
    }


def _validate_box(box: Box, where: str):
    if box.x2 <= box.x1 or box.y2 <= box.y1:
        raise HTTPException(
            status_code=400,
            detail=f"{where} must be XYXY with x2>x1 and y2>y1, got "
                   f"({box.x1},{box.y1},{box.x2},{box.y2})")


@app.post("/v1/segment")
def segment(request: SegmentRequest):
    if not request.image_b64 or not request.image_b64.strip():
        raise HTTPException(status_code=400, detail="image_b64 must not be empty")

    mode = (request.mode or MODE_AUTOMATIC).strip().upper()
    if mode not in (MODE_AUTOMATIC, MODE_PROMPTED):
        raise HTTPException(status_code=400, detail=f"mode must be AUTOMATIC or PROMPTED, got '{mode}'")
    if mode == MODE_PROMPTED and not request.boxes:
        raise HTTPException(status_code=400, detail="PROMPTED needs at least one box")
    for i, box in enumerate(request.boxes or []):
        _validate_box(box, f"boxes[{i}]")

    model_id = _model_id(request.model)
    effective_dim = min(request.max_dim or MAX_DIM, MAX_DIM)
    image = _downscale(_decode(request.image_b64, "image_b64"), effective_dim)

    if mode == MODE_AUTOMATIC:
        records = _segment_automatic(image, model_id, request)
    else:
        records = _segment_prompted(image, model_id, request)

    dropped = max(0, len(records) - request.max_masks)
    records = records[:request.max_masks]
    # Re-index after the cap so `index` is dense and matches the array position; the
    # node writes mask-<index>.png from it.
    for position, record in enumerate(records):
        record["index"] = position

    return {
        "model": model_id,
        "mode": mode,
        "width": image.width,
        "height": image.height,
        "masks": records,
        "truncated": {"masks": dropped},
    }


@app.post("/v1/track")
def track(request: TrackRequest):
    if not request.frames_b64:
        raise HTTPException(status_code=400, detail="frames_b64 must not be empty")
    if len(request.frames_b64) > MAX_FRAMES:
        raise HTTPException(
            status_code=400,
            detail=f"frames_b64 holds {len(request.frames_b64)} frames, the server cap is {MAX_FRAMES}")
    if request.frame_numbers is not None and len(request.frame_numbers) != len(request.frames_b64):
        raise HTTPException(
            status_code=400,
            detail=f"frame_numbers has {len(request.frame_numbers)} entries but frames_b64 has "
                   f"{len(request.frames_b64)}")
    if not request.prompts:
        raise HTTPException(status_code=400, detail="track needs at least one prompt")
    for i, prompt in enumerate(request.prompts):
        if not 0 <= prompt.frame_index < len(request.frames_b64):
            raise HTTPException(
                status_code=400,
                detail=f"prompts[{i}].frame_index {prompt.frame_index} is outside "
                       f"0..{len(request.frames_b64) - 1}")
        _validate_box(prompt.box, f"prompts[{i}].box")

    return _track(request, _model_id(request.model))
