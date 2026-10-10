"""
What a backend is: something that turns a batch of equal-length 16 kHz mono float32 windows into
one L2-normalised embedding each.

Loading is lazy and happens once (`ensure_loaded`), on first use or at startup via
VOICEPRINT_PRELOAD. `lock` serialises inference on one model: FastAPI runs sync work on a
threadpool, and two batches racing on one GPU model only make both slower.

A backend also names `default_threshold` - the cosine similarity at which two windows count as
the same voice under *its* scoring - because that number is a property of the model, not of the
clustering code. The thresholds shipped here were set on the two-voice fixture in tests/ and the
eval harness's German sets; a deployment with its own audio should re-check them.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import List, Optional

import numpy as np

from .. import config

logger = logging.getLogger("voiceprint.backend")

SAMPLE_RATE = 16000


def resolve_device() -> str:
    if config.DEVICE != "auto":
        return config.DEVICE
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:  # noqa: BLE001 - no torch, no cuda
        return "cpu"


class Backend:
    name: str = ""
    # Cosine similarity at or above which two windows are the same speaker for this model.
    default_threshold: float = 0.5
    # Licence of the weights the default configuration loads, for /health and the README.
    licence: str = ""
    commercial: bool = False

    def __init__(self):
        self.lock = threading.Lock()
        self._load_lock = threading.Lock()
        self._loaded = False
        self.load_seconds: Optional[float] = None
        self.dim: Optional[int] = None
        self.device: str = "cpu"

    # -- lifecycle ------------------------------------------------------------
    @property
    def model_id(self) -> str:
        raise NotImplementedError

    @property
    def loaded(self) -> bool:
        return self._loaded

    def ensure_loaded(self):
        if self._loaded:
            return
        with self._load_lock:
            if self._loaded:
                return
            started = time.time()
            logger.info("Loading %s (%s)", self.name, self.model_id)
            self._load()
            # One probe tells us the dimension, and warms the kernels while nobody waits for it.
            probe = self._embed([np.zeros(SAMPLE_RATE, dtype=np.float32)])
            self.dim = int(np.asarray(probe).shape[-1])
            self.load_seconds = time.time() - started
            self._loaded = True
            logger.info("Loaded %s in %.1fs on %s, dim %d", self.name, self.load_seconds, self.device, self.dim)

    def _load(self):
        raise NotImplementedError

    def info(self) -> dict:
        return {
            "name": self.name,
            "model": self.model_id,
            "loaded": self._loaded,
            "device": self.device if self._loaded else None,
            "dim": self.dim,
            "default_threshold": self.default_threshold,
            "licence": self.licence,
            "commercial": self.commercial,
        }

    # -- work -------------------------------------------------------------------
    def embed(self, wavs: List[np.ndarray], batch: int = 32) -> np.ndarray:
        """[N, dim] L2-normalised embeddings for N equal-length windows. Loads the model if needed."""
        if not wavs:
            return np.zeros((0, self.dim or 0), dtype=np.float32)
        lengths = {len(w) for w in wavs}
        if len(lengths) != 1:
            raise ValueError(f"windows must have equal length, got {sorted(lengths)}")
        self.ensure_loaded()
        out = []
        with self.lock:
            for i in range(0, len(wavs), max(1, batch)):
                out.append(np.asarray(self._embed(wavs[i : i + batch]), dtype=np.float32))
        vectors = np.concatenate(out, axis=0)
        if not np.isfinite(vectors).all():
            raise RuntimeError(f"{self.name} produced non-finite embeddings")
        norms = np.linalg.norm(vectors, axis=1, keepdims=True)
        return vectors / np.maximum(norms, 1e-12)

    def _embed(self, wavs: List[np.ndarray]) -> np.ndarray:
        raise NotImplementedError
