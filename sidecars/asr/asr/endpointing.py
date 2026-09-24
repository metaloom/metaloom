"""
Energy-based endpointing: where does an utterance start and end in a stream of audio?

The realtime session needs this because Whisper and Parakeet are offline models - they want a
finished utterance, and a caller streaming a microphone never says when one is finished. The HTTP
route uses the same code to cut a long file into pieces the models can digest, so a file and a
stream of the same audio are cut in the same places.

Every position here is a **sample index into the whole stream** (session-absolute), which is what
lets the backends' utterance-relative time codes be turned into absolute ones with one addition.

It is deliberately not a neural VAD. It runs per 30 ms frame, in constant time, with no model and
no state beyond a noise-floor estimate, and its only job is to find *pauses*, not to classify
speech. A frame is "loud" when it is `margin_db` above the running noise floor and above
`floor_db`. A span starts on the first loud run of `min_speech_ms` (with `preroll_ms` of audio
before it) and ends after `silence_ms` without a loud frame (keeping `postroll_ms` after the
last one). A span that reaches `max_utterance_s` is cut at its quietest frame in the last
`search_s` seconds and the next span starts there - so continuous speech in a noisy room still
produces bounded utterances, just not at word-perfect boundaries.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np

SAMPLE_RATE = 16000
FRAME = 480  # 30 ms at 16 kHz


@dataclass
class EndpointerConfig:
    silence_ms: int = 600
    min_speech_ms: int = 150
    preroll_ms: int = 300
    postroll_ms: int = 300
    max_utterance_s: float = 20.0
    search_s: float = 3.0
    floor_db: float = -50.0
    margin_db: float = 12.0


@dataclass
class Span:
    start: int
    end: Optional[int] = None  # None while the span is open


class Endpointer:
    """
    Feed audio in any chunk size; get back the spans that opened and closed.

    `feed()` returns (opened, closed): the Span objects that started, and those that ended, during
    that call. An opened span is the same object that later appears in `closed`, with `end` set.
    """

    def __init__(self, config: Optional[EndpointerConfig] = None, sample_rate: int = SAMPLE_RATE):
        self.c = config or EndpointerConfig()
        self.rate = sample_rate
        ms = sample_rate // 1000
        self.silence_frames = max(1, self.c.silence_ms * ms // FRAME)
        self.min_speech_frames = max(1, self.c.min_speech_ms * ms // FRAME)
        self.preroll = self.c.preroll_ms * ms
        self.postroll = self.c.postroll_ms * ms
        self.max_len = int(self.c.max_utterance_s * sample_rate)
        self.search_frames = max(1, int(self.c.search_s * sample_rate) // FRAME)

        self.pos = 0  # samples consumed so far, frame-aligned
        self._rest = np.zeros(0, dtype=np.float32)
        self.noise_db = -60.0
        self.span: Optional[Span] = None
        self.loud_run = 0  # consecutive loud frames
        self.quiet_run = 0  # consecutive quiet frames inside an open span
        self.last_loud_end = 0
        self.prev_end = 0  # end of the last closed span: the next preroll must not reach into it
        # (frame_end_sample, energy_db) of the recent frames of the open span, for the quietest cut
        self._recent: List[Tuple[int, float]] = []

    # -- public -------------------------------------------------------------
    def feed(self, samples: np.ndarray) -> Tuple[List[Span], List[Span]]:
        opened: List[Span] = []
        closed: List[Span] = []
        data = np.concatenate([self._rest, np.asarray(samples, dtype=np.float32)])
        n = len(data) // FRAME
        for i in range(n):
            self._frame(data[i * FRAME : (i + 1) * FRAME], opened, closed)
        self._rest = data[n * FRAME :]
        return opened, closed

    def flush(self) -> Optional[Span]:
        """End the open span at the current position (a client commit, or the end of a file)."""
        end = self.pos + len(self._rest)
        self.loud_run = 0
        self.quiet_run = 0
        self._recent.clear()
        span, self.span = self.span, None
        if span is not None:
            span.end = max(end, span.start)
            self.prev_end = span.end
        return span

    @property
    def is_open(self) -> bool:
        return self.span is not None

    # -- internals ----------------------------------------------------------
    def _frame(self, frame: np.ndarray, opened: List[Span], closed: List[Span]):
        self.pos += FRAME
        rms = float(np.sqrt(np.mean(frame * frame)) + 1e-10)
        db = 20.0 * np.log10(rms)
        loud = db > max(self.c.floor_db, self.noise_db + self.c.margin_db)

        if not loud:
            # The floor follows quiet frames quickly downwards and slowly upwards, so one loud
            # breath does not raise it but a noisier room eventually does.
            rate = 0.2 if db < self.noise_db else 0.02
            self.noise_db += rate * (db - self.noise_db)

        if self.span is None:
            self.loud_run = self.loud_run + 1 if loud else 0
            if self.loud_run >= self.min_speech_frames:
                speech_start = self.pos - self.loud_run * FRAME
                self.span = Span(max(0, speech_start - self.preroll, self.prev_end))
                opened.append(self.span)
                self.quiet_run = 0
                self.last_loud_end = self.pos
                self._recent = []
            return

        self._recent.append((self.pos, db))
        if len(self._recent) > self.search_frames:
            self._recent.pop(0)

        if loud:
            self.quiet_run = 0
            self.last_loud_end = self.pos
        else:
            self.quiet_run += 1

        if self.quiet_run >= self.silence_frames:
            self.span.end = min(self.pos, self.last_loud_end + self.postroll)
            self.prev_end = self.span.end
            closed.append(self.span)
            self.span = None
            self.loud_run = 0
            return

        if self.pos - self.span.start >= self.max_len:
            # Continuous speech: cut at the quietest recent frame, not mid-word at the hard limit.
            cut, _ = min(self._recent, key=lambda fe: fe[1])
            cut = max(cut, self.span.start + FRAME)
            self.span.end = cut
            self.prev_end = cut
            closed.append(self.span)
            self.span = Span(cut)
            opened.append(self.span)
            self._recent = [(p, e) for p, e in self._recent if p > cut]
            self.quiet_run = 0


def split(audio: np.ndarray, config: Optional[EndpointerConfig] = None, sample_rate: int = SAMPLE_RATE) -> List[Tuple[int, int]]:
    """
    Cut a whole recording into (start, end) sample spans of speech.

    Silence between spans is dropped. Audio with no detectable speech at all comes back as one span
    covering everything, so a very quiet recording is still handed to the model rather than being
    reported as empty by the endpointer.
    """
    ep = Endpointer(config, sample_rate)
    _, closed = ep.feed(audio)
    last = ep.flush()
    spans = [(s.start, s.end) for s in closed]
    if last is not None:
        spans.append((last.start, last.end))
    if not spans and len(audio):
        spans = [(0, len(audio))]
    return spans
