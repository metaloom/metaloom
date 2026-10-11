"""
Kokoro-82M (English) — ONNX on CPU, Apache-2.0.

The light half of the sidecar: ~340 MB of weights that `setup.sh` fetches, no GPU, no HF gate and
no token layout. It also has **no duration ceiling**, which is why `chunk_chars` is 0: splitting
a text it can synthesise in one pass would only add seams. A stream still splits it, because
nothing can be heard until the first piece exists — that is the one place English gets a chunk.

Unlike German, the voice list here is **authoritative**: it comes out of `voices-v1.0.bin`
itself, and an unknown name raises inside the library. A caller typo is therefore a 400 rather
than a 500 or a substituted voice.
"""

import logging
import os
from typing import List

import numpy as np
from fastapi import HTTPException

from .. import config
from .base import Engine

logger = logging.getLogger("tts.engine.kokoro")

DEFAULT_VOICE = "af_heart"
#: Fallback only, for when `voices-v1.0.bin` is absent. The real file holds far more.
SAMPLE_VOICES = ["af_heart", "am_michael", "bf_emma"]


class KokoroEngine(Engine):
    name = "kokoro"
    languages = ("en",)
    backend = "onnx"
    sample_rate = 24000
    licence = "Apache-2.0"
    commercial = True
    #: No token ceiling: one generation can say a whole page.
    chunk_chars = 0

    def __init__(self):
        super().__init__()
        self._model = None
        self._voices = None

    # -- catalogue --------------------------------------------------------------
    @property
    def repo(self) -> str:
        return config.KOKORO_MODEL

    @property
    def device(self) -> str:
        # ONNX on CPU; `DEVICE` governs the German engines only.
        return "cpu"

    @property
    def loaded(self) -> bool:
        return self._model is not None

    @property
    def available(self) -> bool:
        return os.path.exists(config.KOKORO_MODEL) and os.path.exists(config.KOKORO_VOICES)

    def voices(self) -> List[str]:
        """The voice names in `voices-v1.0.bin`, which is an npz archive of them.

        Reading the archive's directory is cheap and does not touch the 340 MB ONNX model, so this
        stays honest without paying for a load. An unreadable or absent file falls back to the
        documented sample rather than failing the route. Cached, unlike the German clip listing:
        this one reads a file, and the file does not change under a running process.
        """
        if self._voices is not None:
            return self._voices
        names: List[str] = []
        try:
            if os.path.exists(config.KOKORO_VOICES):
                with np.load(config.KOKORO_VOICES) as bundle:
                    names = sorted(bundle.files)
        except Exception as e:  # noqa: BLE001 — a listing failure must not fail the route
            logger.warning("cannot read Kokoro voices from %s: %s", config.KOKORO_VOICES, e)
        self._voices = names or SAMPLE_VOICES
        return self._voices

    # -- generation -------------------------------------------------------------
    def _load(self):
        if self._model is not None:
            return
        with self._load_lock:
            if self._model is not None:
                return
            # The weight check comes BEFORE the import on purpose: a deployment that has not run
            # setup.sh is missing both, and "run setup.sh" is the useful answer. An import error
            # would surface as a 500 about a module name.
            if not self.available:
                raise HTTPException(
                    status_code=503,
                    detail=(f"Kokoro weights missing ({config.KOKORO_MODEL} / "
                            f"{config.KOKORO_VOICES}); run setup.sh"))
            from kokoro_onnx import Kokoro

            logger.info("Loading Kokoro %s ...", config.KOKORO_MODEL)
            self._model = Kokoro(config.KOKORO_MODEL, config.KOKORO_VOICES)

    def _generate(self, text: str, voice: str):
        self._load()
        voice = voice or DEFAULT_VOICE
        known = self.voices()
        if known and voice not in known:
            raise HTTPException(status_code=400, detail=f"unknown English voice: {voice}")
        samples, rate = self._model.create(text, voice=voice, speed=1.0, lang="en-us")
        return samples, rate
