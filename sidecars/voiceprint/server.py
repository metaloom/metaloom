"""
Cortex voiceprint sidecar - speaker embeddings ("voiceprints") and who-spoke-when over HTTP.

Three backends behind one API, each turning 16 kHz audio windows into one L2-normalised vector:

  redimnet2    ReDimNet2-B6, VoxCeleb2, MIT (PalabraAI)          the default; best commercial model
  wespeaker    WeSpeaker ResNet34-LM ONNX, CC-BY-4.0             the pyannote 3.1 embedding, fastest
  speechbrain  SpeechBrain ECAPA-TDNN, Apache-2.0                the baseline, no vendored code

  GET  /health            -> backends, which are loaded, defaults
  GET  /v1/models         -> OpenAI-style model list
  POST /v1/voiceprints    -> multipart `file` (+ `model`, `window_s`, `hop_s`, `min_speech_s`,
                             `cluster_threshold`, `max_speakers`, `include_windows`,
                             `include_vectors`); the windows, the speakers and a pooled voiceprint
  POST /v1/compare        -> JSON {"a": [...], "b": [...]} -> {"score": cosine similarity}

The response of /v1/voiceprints, every time in integer milliseconds from the start of the file:

  {"model", "backend", "dim", "normalized": true, "threshold", "duration_ms", "speech_ms",
   "window_s", "hop_s",
   "pooled":   {"vector": [...]}                                   (absent when there is no speech)
   "speakers": [{"index", "total_ms", "cohesion", "windows": [i, ...],
                 "segments": [{"start_ms", "end_ms"}, ...], "vector": [...]}],
   "windows":  [{"index", "start_ms", "end_ms", "speaker", "vector": [...]}]}

Run:
  python server.py                 # binds VOICEPRINT_HOST:VOICEPRINT_PORT (default 0.0.0.0:9150)
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from contextlib import asynccontextmanager
from typing import List, Optional

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from voiceprint import audio as audio_io
from voiceprint import backends, cluster, config, vad

logging.basicConfig(level=os.environ.get("VOICEPRINT_LOG_LEVEL", "INFO"), format="%(asctime)s [%(name)s] %(message)s")
logger = logging.getLogger("voiceprint")

SAMPLE_RATE = 16000


@asynccontextmanager
async def lifespan(app: FastAPI):
    loop = asyncio.get_running_loop()
    for name in config.PRELOAD:
        await loop.run_in_executor(None, backends.get(name).ensure_loaded)
    yield


app = FastAPI(title="Metaloom voiceprint sidecar", lifespan=lifespan)


def _backend_or_400(model: Optional[str]):
    try:
        return backends.get(model)
    except KeyError:
        raise HTTPException(status_code=400, detail=f"unknown or disabled model {model!r}, enabled: {config.BACKENDS}")


def _flag(value: Optional[str], default: bool) -> bool:
    if value is None or value == "":
        return default
    return value.strip().lower() in ("1", "true", "yes", "on")


def _ms(samples: int) -> int:
    return int(round(samples * 1000 / SAMPLE_RATE))


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
@app.get("/health")
def health():
    return {
        "status": "ok",
        "default_model": config.DEFAULT_MODEL,
        "window_s": config.WINDOW_S,
        "hop_s": config.HOP_S,
        "min_speech_s": config.MIN_SPEECH_S,
        "backends": {name: b.info() for name, b in backends.available().items()},
    }


@app.get("/v1/models")
def models():
    return {
        "object": "list",
        "data": [{"id": name, "object": "model", "owned_by": "metaloom", "root": b.model_id}
                 for name, b in backends.available().items()],
    }


def analyse(backend, samples, window_s: float, hop_s: float, min_speech_s: float, threshold: Optional[float],
            max_speakers: int, include_windows: bool, include_vectors: bool) -> dict:
    """
    The whole job on decoded audio: speech spans -> fixed windows -> embeddings -> speakers.
    Separate from the route so the tests and a future batch caller can drive it without HTTP.
    """
    spans = vad.speech_spans(samples)
    wins = vad.windows(samples, spans, window_s, hop_s, min_speech_s, config.MAX_WINDOWS)
    vectors = backend.embed([w.audio for w in wins], config.BATCH)
    used = backend.default_threshold if threshold is None else threshold
    bounds = [(w.start, w.end) for w in wins]
    speakers, labels = cluster.speakers(vectors, bounds, used, max_speakers)
    pooled = cluster.pooled(vectors)

    result = {
        "model": backend.model_id,
        "backend": backend.name,
        "dim": backend.dim,
        "normalized": True,
        "threshold": used,
        "duration_ms": _ms(len(samples)),
        "speech_ms": _ms(sum(e - s for s, e in spans)),
        "window_s": window_s,
        "hop_s": hop_s,
        "speakers": [],
        "windows": [],
    }
    if pooled is not None:
        result["pooled"] = {"vector": pooled.tolist()} if include_vectors else {}
    for sp in speakers:
        item = {
            "index": sp.index,
            "total_ms": sp.total_ms,
            "cohesion": round(sp.cohesion, 4),
            "windows": sp.windows,
            "segments": [{"start_ms": _ms(s), "end_ms": _ms(e)} for s, e in sp.segments],
        }
        if include_vectors:
            item["vector"] = sp.vector.tolist()
        result["speakers"].append(item)
    if include_windows:
        for i, w in enumerate(wins):
            item = {"index": i, "start_ms": _ms(w.start), "end_ms": _ms(w.end), "speaker": int(labels[i])}
            if include_vectors:
                item["vector"] = vectors[i].tolist()
            result["windows"].append(item)
    return result


@app.post("/v1/voiceprints")
def voiceprints(
    file: UploadFile = File(...),
    model: Optional[str] = Form(None),
    window_s: Optional[float] = Form(None),
    hop_s: Optional[float] = Form(None),
    min_speech_s: Optional[float] = Form(None),
    cluster_threshold: Optional[float] = Form(None),
    max_speakers: Optional[int] = Form(None),
    include_windows: Optional[str] = Form(None),
    include_vectors: Optional[str] = Form(None),
):
    backend = _backend_or_400(model)
    data = file.file.read(config.MAX_UPLOAD_MB * 1024 * 1024 + 1)
    if len(data) > config.MAX_UPLOAD_MB * 1024 * 1024:
        raise HTTPException(status_code=413, detail=f"upload exceeds VOICEPRINT_MAX_UPLOAD_MB={config.MAX_UPLOAD_MB}")
    try:
        samples = audio_io.decode(data)
    except audio_io.AudioDecodeError as e:
        raise HTTPException(status_code=400, detail=str(e))

    # Explicit None checks: a caller sending 0 must get a 400, not the default.
    w = config.WINDOW_S if window_s is None else window_s
    h = config.HOP_S if hop_s is None else hop_s
    m = config.MIN_SPEECH_S if min_speech_s is None else min_speech_s
    if w <= 0 or h <= 0 or m <= 0 or h > w:
        raise HTTPException(status_code=400, detail="window_s, hop_s and min_speech_s must be positive and hop_s <= window_s")
    thr = cluster_threshold
    if thr is None and config.CLUSTER_THRESHOLD:
        thr = float(config.CLUSTER_THRESHOLD)
    if thr is not None and not (-1.0 <= thr <= 1.0):
        raise HTTPException(status_code=400, detail="cluster_threshold is a cosine similarity in [-1, 1]")

    started = time.time()
    result = analyse(backend, samples, w, h, m, thr, max(0, max_speakers or 0),
                     _flag(include_windows, True), _flag(include_vectors, True))
    result["compute_ms"] = int((time.time() - started) * 1000)
    logger.info("%s: %d ms audio, %d speakers, %d windows, %d ms", backend.name, result["duration_ms"],
                len(result["speakers"]), len(result.get("windows", [])), result["compute_ms"])
    return JSONResponse(result, headers={"X-Model-Id": backend.model_id, "X-Backend": backend.name})


class CompareRequest(BaseModel):
    a: List[float]
    b: List[float]


@app.post("/v1/compare")
def compare(body: CompareRequest):
    if len(body.a) != len(body.b) or not body.a:
        raise HTTPException(status_code=400, detail=f"vectors must have the same non-zero length, got {len(body.a)} and {len(body.b)}")
    return {"score": cluster.cosine(body.a, body.b), "dim": len(body.a)}


if __name__ == "__main__":
    import uvicorn

    # One worker on purpose: the models live in module globals, a second worker would load a second copy.
    uvicorn.run(app, host=config.HOST, port=config.PORT, workers=1, log_level="info")
