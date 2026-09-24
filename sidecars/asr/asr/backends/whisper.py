"""
OpenAI Whisper large-v3-turbo through faster-whisper (CTranslate2).

The default backend and the best German WER of the three: FLEURS DE 3.41 %, Common Voice DE
4.72 % (audio-eval's sets, measured through this backend). large-v3 (ASR_WHISPER_MODEL) scored
3.4 / 4.3 % in audio-eval at about twice the decode time.

Time codes are Whisper's own: segment boundaries from its timestamp tokens, word boundaries from
DTW over the alignment heads' cross-attention (`word_timestamps=True`) - the same method as the
reference openai-whisper package.

Not a streaming model: it decodes whole utterances, which the endpointer cuts.
"""

from __future__ import annotations

from typing import Optional

import numpy as np

from .. import config
from ..timecodes import Segment, Transcript, Word
from .base import SAMPLE_RATE, Backend

# Below a quarter of a second Whisper hallucinates rather than transcribes.
MIN_SAMPLES = SAMPLE_RATE // 4


class WhisperBackend(Backend):
    name = "whisper"
    takes_language = True

    def __init__(self):
        super().__init__()
        self._model = None
        self.device = None
        self.compute_type = None

    @property
    def model_id(self) -> str:
        return config.WHISPER_MODEL

    def _load(self):
        from faster_whisper import WhisperModel

        device = config.WHISPER_DEVICE
        if device == "auto":
            import ctranslate2

            device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
        self.device = device
        self.compute_type = config.WHISPER_COMPUTE_TYPE or ("float16" if device == "cuda" else "int8")
        self._model = WhisperModel(config.WHISPER_MODEL, device=device, compute_type=self.compute_type)

    def info(self) -> dict:
        d = super().info()
        d.update(device=self.device, compute_type=self.compute_type)
        return d

    def _transcribe(self, audio: np.ndarray, language: Optional[str]) -> Transcript:
        duration = len(audio) / SAMPLE_RATE
        if len(audio) < MIN_SAMPLES:
            return Transcript([], language, duration, self.model_id)
        segments, info = self._model.transcribe(
            audio,
            language=None if language in (None, "", "auto") else language,
            beam_size=config.WHISPER_BEAM_SIZE,
            word_timestamps=True,
            # The endpointer already cut on silence, and each utterance is decoded on its own:
            # carrying the previous text over is how Whisper loops on a phrase.
            vad_filter=False,
            condition_on_previous_text=False,
        )
        out = []
        for s in segments:
            words = [Word(w.word.strip(), w.start, w.end, w.probability) for w in (s.words or []) if w.word.strip()]
            text = s.text.strip()
            if not text:
                continue
            out.append(Segment(s.start, s.end, text, words))
        return Transcript(out, info.language, duration, self.model_id)
