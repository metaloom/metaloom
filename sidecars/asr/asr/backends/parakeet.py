"""
NVIDIA Parakeet-TDT-0.6B-v3 through its sherpa-onnx export, on the CPU.

The third backend, chosen for what the other two lack: it is a transducer, so its time codes are
not reconstructed after the fact - every token is emitted at an encoder frame (80 ms) and the TDT
head also predicts how many frames it covers. That makes it the cheapest backend to get word
timings from, and at ~40x real time on four CPU threads it serves realtime sessions without a
GPU at all. German quality is close to Whisper's (audio-eval: FLEURS DE 4.4 %, CV DE 6.1 % WER
against 3.4 / 4.3 %); it detects the language itself among 25 European ones, and it punctuates
and capitalises.

Model files come from the Hub like the other backends', but through `snapshot_download(...,
local_dir=...)` rather than the plain cache: the encoder keeps its 2.4 GB of weights in an ONNX
external-data file, and onnxruntime refuses to follow the cache's symlinked blobs out of the
model's directory ("External data path escapes model directory").
"""

from __future__ import annotations

import math
import os
from typing import Optional

import numpy as np

from .. import config
from ..timecodes import Transcript, WordBuilder, segments_from_words
from .base import SAMPLE_RATE, Backend


class ParakeetBackend(Backend):
    name = "parakeet"

    def __init__(self):
        super().__init__()
        self._recognizer = None
        self.model_dir = None

    @property
    def model_id(self) -> str:
        return config.PARAKEET_MODEL

    def _resolve_dir(self) -> str:
        if os.path.isdir(config.PARAKEET_MODEL):
            return config.PARAKEET_MODEL
        from huggingface_hub import snapshot_download

        hf_home = os.environ.get("HF_HOME", os.path.expanduser("~/.cache/huggingface"))
        target = os.path.join(hf_home, "asr-sidecar", config.PARAKEET_MODEL.replace("/", "--"))
        return snapshot_download(
            config.PARAKEET_MODEL,
            local_dir=target,
            allow_patterns=["*.onnx", "*.weights", "tokens.txt"],
        )

    def _load(self):
        import sherpa_onnx

        d = self._resolve_dir()
        self.model_dir = d
        # The int8 export names its files encoder.int8.onnx etc.; accept either.
        def pick(stem):
            for name in (f"{stem}.onnx", f"{stem}.int8.onnx"):
                if os.path.exists(os.path.join(d, name)):
                    return os.path.join(d, name)
            raise FileNotFoundError(f"{stem}.onnx not found in {d}")

        self._recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=pick("encoder"),
            decoder=pick("decoder"),
            joiner=pick("joiner"),
            tokens=os.path.join(d, "tokens.txt"),
            num_threads=config.PARAKEET_THREADS,
            sample_rate=SAMPLE_RATE,
            feature_dim=128,
            decoding_method="greedy_search",
            model_type="nemo_transducer",
            provider=config.PARAKEET_PROVIDER,
        )

    def info(self) -> dict:
        d = super().info()
        d.update(provider=config.PARAKEET_PROVIDER, threads=config.PARAKEET_THREADS)
        return d

    def _transcribe(self, audio: np.ndarray, language: Optional[str]) -> Transcript:
        duration = len(audio) / SAMPLE_RATE
        stream = self._recognizer.create_stream()
        stream.accept_waveform(SAMPLE_RATE, audio)
        self._recognizer.decode_stream(stream)
        r = stream.result
        tokens = list(r.tokens)
        starts = [float(t) for t in r.timestamps]
        durations = [float(d) for d in getattr(r, "durations", [])] or [0.08] * len(tokens)
        logprobs = [float(p) for p in getattr(r, "ys_log_probs", [])] or [0.0] * len(tokens)

        builder = WordBuilder()
        words = []
        word_logprobs = []
        current_lp = []
        for tok, start, dur, lp in zip(tokens, starts, durations, logprobs):
            # A TDT duration counts every frame the decoder skipped after the token, blanks
            # included - so it covers the token's sound, and before a pause the silence too.
            # snap_to_speech below takes the silence back off.
            done = builder.push(tok, start, start + max(dur, 0.04))
            if done:
                words += done
                word_logprobs.append(current_lp)
                current_lp = []
            if tok.strip():
                current_lp.append(lp)
        tail = builder.finish()
        if tail:
            words += tail
            word_logprobs.append(current_lp)
        for w, lps in zip(words, word_logprobs):
            w.probability = math.exp(sum(lps) / len(lps)) if lps else None
        for a, b in zip(words, words[1:]):
            a.end = min(a.end, b.start) if b.start > a.start else a.end
        snap_to_speech(words, audio)
        return Transcript(segments_from_words(words), getattr(r, "lang", None) or None, duration, self.model_id)


FRAME_S = 0.02


def snap_to_speech(words, audio: np.ndarray, floor_margin_db: float = 10.0, peak_range_db: float = 20.0,
                   min_len: float = 0.08):
    """
    Pull each word's boundaries in to where the audio actually carries it.

    A transducer's token times are frame indices of *emission*, and at the edges of an utterance
    they are loose. Measured on the German fixture against Whisper, Voxtral and the waveform:
    the last word of a sentence ends 0.4 - 0.6 s into the room noise after it ("gepackt." at
    14.77 s, the "-kt" burst ends at 14.16 s), because the TDT duration of its last token counts
    the blank frames that follow; and a word after a pause can be emitted before it is spoken
    (" Der" at 17.71 s, spoken 18.10 - 18.40 s).

    A frame belongs to the word when it is within `peak_range_db` of the word's own loudest frame
    AND `floor_margin_db` above the utterance's noise floor (10th-percentile frame energy). Both
    are needed: the floor alone fails on a recording whose room noise sits well above its quietest
    frames (the fixture's Common Voice clip: noise tail -47 dB, floor -70 dB), the peak alone on a
    quiet word. -20 dB is 1 % of the peak's power; the quietest real word ending in the fixture, a
    "-kt" burst 19 dB below its vowel, still clears it.

    It only ever shrinks a word, never below `min_len`, and a word with no qualifying frame is
    left untouched.
    """
    n = int(FRAME_S * SAMPLE_RATE)
    frames = len(audio) // n
    if frames < 5 or not words:
        return words
    energy = audio[: frames * n].reshape(frames, n)
    db = 20 * np.log10(np.sqrt((energy**2).mean(axis=1)) + 1e-10)
    floor = np.percentile(db, 10) + floor_margin_db
    for w in words:
        i0, i1 = int(w.start / FRAME_S), min(frames, int(math.ceil(w.end / FRAME_S)))
        if i1 <= i0:
            continue
        span = db[i0:i1]
        idx = np.flatnonzero((span > floor) & (span > span.max() - peak_range_db))
        if not len(idx):
            continue
        start = (i0 + idx[0]) * FRAME_S
        end = (i0 + idx[-1] + 1) * FRAME_S
        if end - start >= min_len:
            w.start, w.end = max(w.start, start), min(w.end, end)
    return words
