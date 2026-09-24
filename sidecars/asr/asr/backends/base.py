"""
What a backend is: something that turns one utterance of 16 kHz mono float32 audio into a
time-coded Transcript.

Times in the returned Transcript are **relative to the audio passed in**. The caller (the HTTP
route or the realtime session) knows where that audio sits in the stream and applies the offset
with `timecodes.offset`; a backend never needs to know.

Loading is lazy and happens once (`ensure_loaded`), on first use or at startup via ASR_PRELOAD.
`lock` serialises decodes on one model: FastAPI runs sync work on a threadpool, and two decodes
racing on one GPU model only make both slower.
"""

from __future__ import annotations

import threading
import time
import logging
from typing import Optional

import numpy as np

from ..timecodes import Transcript

logger = logging.getLogger("asr.backend")

SAMPLE_RATE = 16000


class Backend:
    name: str = ""
    # True when the backend can decode audio as it arrives (Voxtral). The others get whole
    # utterances cut by the endpointer.
    streaming: bool = False
    # Whether `transcribe` honours the language argument. Backends that detect it ignore it.
    takes_language: bool = False

    def __init__(self):
        self.lock = threading.Lock()
        self._load_lock = threading.Lock()
        self._loaded = False
        self.load_seconds: Optional[float] = None

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
            self.load_seconds = time.time() - started
            self._loaded = True
            logger.info("Loaded %s in %.1fs", self.name, self.load_seconds)

    def _load(self):
        raise NotImplementedError

    def info(self) -> dict:
        return {
            "name": self.name,
            "model": self.model_id,
            "loaded": self._loaded,
            "streaming": self.streaming,
            "takes_language": self.takes_language,
        }

    # -- work -------------------------------------------------------------------
    def transcribe(self, audio: np.ndarray, language: Optional[str]) -> Transcript:
        """Decode one utterance. Loads the model if needed and holds `lock` for the decode."""
        self.ensure_loaded()
        with self.lock:
            return self._transcribe(np.asarray(audio, dtype=np.float32), language)

    def _transcribe(self, audio: np.ndarray, language: Optional[str]) -> Transcript:
        raise NotImplementedError
