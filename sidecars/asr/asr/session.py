"""
One realtime client: PCM16 in, time-coded transcript events out.

Audio is kept in a session-absolute buffer (sample 0 = the first sample the client sent), and
utterances are spans of it. A span opens and closes by server-side endpointing (`server_vad`,
the default) or only on `input_audio_buffer.commit` (`turn_detection: none`). A single worker
thread walks the utterances in order, so their events never interleave:

  batch backends (whisper, parakeet)
      while the utterance is open: every ASR_PARTIAL_INTERVAL_MS, re-decode it and send
      `transcription.partial` - interim text that the next partial replaces
      once it closes: decode it once, send one `transcription.segment` per segment, then
      `transcription.done`

  streaming backend (voxtral)
      decode while the audio arrives: `transcription.delta` per text token, a
      `transcription.segment` as soon as a segment is complete (sentence end or pause), then
      `transcription.done` when the utterance closes

Every time in every event is seconds since the start of the session. That is the whole point of
this class: the backends only ever see an utterance and answer in utterance-relative time;
`timecodes.offset(..., utterance.start / SAMPLE_RATE)` is applied here and nowhere else.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import deque
from typing import Callable, Deque, List, Optional

import numpy as np

from . import timecodes
from .backends.base import Backend
from .endpointing import Endpointer, EndpointerConfig
from .timecodes import Segment, SegmentBuilder, Transcript, Word

logger = logging.getLogger("asr.session")

SAMPLE_RATE = 16000
# How much audio before the oldest pending utterance is kept - covers the endpointer's pre-roll.
KEEP_S = 2.0


class Utterance:
    def __init__(self, item_id: str, start: int):
        self.item_id = item_id
        self.start = start
        self.end: Optional[int] = None
        self.committed = False  # ended by input_audio_buffer.commit rather than by a pause

    @property
    def closed(self) -> bool:
        return self.end is not None


class RealtimeSession:
    def __init__(
        self,
        backend: Backend,
        emit: Callable[[dict], None],
        language: Optional[str] = None,
        turn_detection: bool = True,
        endpointer: Optional[EndpointerConfig] = None,
        partial_interval_ms: int = 1000,
    ):
        self.backend = backend
        self.emit = emit
        self.language = language
        self.turn_detection = turn_detection
        self.ep_config = endpointer or EndpointerConfig()
        self.partial_interval = partial_interval_ms / 1000.0

        self.cond = threading.Condition()
        self.buf = np.zeros(0, dtype=np.float32)
        self.base = 0  # session sample index of buf[0]
        self.total = 0  # samples received
        self.ep = Endpointer(self.ep_config)
        self.queue: Deque[Utterance] = deque()
        self.closed = False
        self._items = 0
        self._segments = 0
        self.worker: Optional[threading.Thread] = None

    # ------------------------------------------------------------------ config
    def update(self, language: Optional[str] = None, turn_detection: Optional[bool] = None,
               silence_ms: Optional[int] = None):
        """session.update. Endpointer settings only apply to utterances that have not started."""
        with self.cond:
            if language is not None:
                self.language = language
            if turn_detection is not None:
                self.turn_detection = turn_detection
            if silence_ms is not None:
                self.ep_config.silence_ms = int(silence_ms)
                if not self.ep.is_open:
                    pos, noise = self.ep.pos + len(self.ep._rest), self.ep.noise_db
                    self.ep = Endpointer(self.ep_config)
                    self.ep.pos, self.ep.noise_db, self.ep.prev_end = pos, noise, pos

    # ------------------------------------------------------- called by the socket
    def append(self, pcm: np.ndarray):
        pcm = np.asarray(pcm, dtype=np.float32)
        if not len(pcm):
            return
        with self.cond:
            if self.closed:
                return
            self.buf = np.concatenate([self.buf, pcm])
            first = self.total
            self.total += len(pcm)
            if self.turn_detection:
                opened, closed = self.ep.feed(pcm)
                # A span can open and close within one append; handle them in stream order.
                events = sorted(
                    [(s.start, "open", s) for s in opened] + [(s.end, "close", s) for s in closed],
                    key=lambda e: (e[0], e[1] == "open"),
                )
                for _, kind, span in events:
                    if kind == "open":
                        self._open(span.start)
                    else:
                        self._close(span.end, committed=False)
            else:
                # Keep the endpointer's clock in step, so switching turn detection on later works.
                self.ep.pos = self.total
                if self._open_utterance() is None:
                    self._open(first, announce=False)
            self._ensure_worker()
            self.cond.notify_all()

    def commit(self) -> Optional[str]:
        """
        End the open utterance now. Returns the item id this commit answers for.

        Every commit gets exactly one `transcription.done`: if nothing is open (silence since the
        last cut), an empty, already-closed utterance is queued so the answer still comes, in
        order behind whatever is being decoded.
        """
        with self.cond:
            if self.closed:
                return None
            u = self._open_utterance()
            if u is not None:
                span = self.ep.flush() if self.turn_detection else None
                self._close(span.end if span is not None else self.total, committed=True)
            else:
                u = self._open(self.total, announce=False)
                u.end = self.total
                u.committed = True
            self._ensure_worker()
            self.cond.notify_all()
            return u.item_id

    def close(self):
        with self.cond:
            self.closed = True
            self.cond.notify_all()
            worker = self.worker
        if worker is not None:
            worker.join(timeout=30)

    # --------------------------------------------------------------- internals
    def _open_utterance(self) -> Optional[Utterance]:
        if self.queue and not self.queue[-1].closed:
            return self.queue[-1]
        return None

    def _open(self, start: int, announce: bool = True) -> Utterance:
        self._items += 1
        u = Utterance(f"item_{self._items:04d}", start)
        self.queue.append(u)
        if announce:
            self.emit({"type": "input_audio_buffer.speech_started", "item_id": u.item_id,
                       "audio_start_ms": timecodes.to_ms(start / SAMPLE_RATE)})
        return u

    def _close(self, end: int, committed: bool):
        u = self._open_utterance()
        if u is None:
            return
        u.end = max(end, u.start)
        u.committed = committed
        if self.turn_detection:
            self.emit({"type": "input_audio_buffer.speech_stopped", "item_id": u.item_id,
                       "audio_end_ms": timecodes.to_ms(u.end / SAMPLE_RATE)})

    def _slice(self, start: int, end: int) -> np.ndarray:
        return self.buf[max(0, start - self.base): max(0, end - self.base)].copy()

    def _trim(self):
        """Drop audio no utterance can need any more. Caller holds the condition."""
        keep = self.total - int(KEEP_S * SAMPLE_RATE)
        if self.queue:
            keep = min(keep, self.queue[0].start)
        drop = keep - self.base
        if drop > SAMPLE_RATE:  # not worth a copy for less than a second
            self.buf = self.buf[drop:]
            self.base += drop

    def _ensure_worker(self):
        if self.worker is None and not self.closed:
            self.worker = threading.Thread(target=self._run, name=f"asr-{self.backend.name}", daemon=True)
            self.worker.start()

    def _run(self):
        while True:
            with self.cond:
                while not self.queue and not self.closed:
                    self.cond.wait(timeout=0.5)
                if self.closed:
                    return
                u = self.queue[0]
            try:
                if u.closed and u.end == u.start:
                    segments = []
                elif self.backend.streaming:
                    segments = self._stream(u)
                else:
                    segments = self._batch(u)
            except Exception as e:  # noqa: BLE001 - a failed utterance must not end the session
                logger.exception("utterance %s failed", u.item_id)
                self.emit({"type": "error", "error": {"message": str(e), "item_id": u.item_id}})
                segments = []
                with self.cond:
                    if not u.closed:
                        u.end = self.total
            if segments is None:  # session closed mid-utterance: nobody is listening
                return
            with self.cond:
                if self.queue and self.queue[0] is u:
                    self.queue.popleft()
                self._trim()
            self._done(u, segments)

    def _done(self, u: Utterance, segments: List[dict]):
        self.emit({
            "type": "transcription.done",
            "item_id": u.item_id,
            "text": timecodes.join_text(s["text"] for s in segments),
            "start": round(u.start / SAMPLE_RATE, 3),
            "end": round((u.end or u.start) / SAMPLE_RATE, 3),
            "segments": segments,
        })

    def _emit_segment(self, u: Utterance, s: Segment) -> dict:
        """Number a final segment (ids are session-wide), send it, and return what was sent."""
        d = s.to_dict(self._segments)
        self._segments += 1
        self.emit({"type": "transcription.segment", "item_id": u.item_id, "segment": d})
        return d

    # -- whisper / parakeet -------------------------------------------------------
    def _batch(self, u: Utterance) -> Optional[List[dict]]:
        last_partial_at = time.monotonic()
        last_partial_len = 0
        while True:
            with self.cond:
                while not u.closed and not self.closed:
                    if self.partial_interval > 0 and time.monotonic() - last_partial_at >= self.partial_interval:
                        break
                    self.cond.wait(timeout=0.1)
                if self.closed:
                    return None
                if u.closed:
                    audio = self._slice(u.start, u.end)
                    break
                audio = self._slice(u.start, self.total)
            last_partial_at = time.monotonic()
            if len(audio) - last_partial_len >= SAMPLE_RATE // 2:
                last_partial_len = len(audio)
                partial = self._decode(u, audio)
                self.emit({"type": "transcription.partial", "item_id": u.item_id, "text": partial.text,
                           "start": round(u.start / SAMPLE_RATE, 3),
                           "end": round((u.start + len(audio)) / SAMPLE_RATE, 3)})
        if len(audio) == 0:
            return []
        transcript = self._decode(u, audio)
        return [self._emit_segment(u, s) for s in transcript.segments]

    def _decode(self, u: Utterance, audio: np.ndarray) -> Transcript:
        t = self.backend.transcribe(audio, self.language)
        t = timecodes.offset(t, u.start / SAMPLE_RATE)
        return timecodes.clamp(t, u.start / SAMPLE_RATE, (u.start + len(audio)) / SAMPLE_RATE)

    # -- voxtral --------------------------------------------------------------------
    def _stream(self, u: Utterance) -> Optional[List[dict]]:
        pad = self.backend.pad_samples
        origin = u.start / SAMPLE_RATE
        builder = SegmentBuilder()
        out: List[dict] = []

        def take(start: int, length: int) -> Optional[np.ndarray]:
            a = u.start + start
            b = a + length
            with self.cond:
                while True:
                    if self.closed:
                        return None
                    if not u.closed:
                        if self.total >= b:
                            return self._slice(a, b)
                    else:
                        if a >= u.end + pad:
                            return None
                        chunk = np.zeros(length, dtype=np.float32)
                        real = self._slice(a, min(b, u.end))
                        chunk[: len(real)] = real
                        return chunk
                    self.cond.wait(timeout=0.5)

        def bound(t: float) -> float:
            hi = (u.end if u.closed else self.total) / SAMPLE_RATE
            return min(max(origin + t, origin), max(hi, origin))

        def on_token(piece: str, t: float):
            self.emit({"type": "transcription.delta", "item_id": u.item_id, "delta": piece, "end": round(bound(t), 3)})

        def on_words(words: List[Word], now: Optional[float]):
            for w in words:
                w.start, w.end = bound(w.start), bound(w.end)
                for s in builder.push(w):
                    out.append(self._emit_segment(u, s))
            if now is not None:  # None: a word is still arriving, so no pause has started
                for s in builder.tick(origin + now):
                    out.append(self._emit_segment(u, s))

        self.backend.stream(take, on_token, on_words)
        if self.closed:
            return None
        for s in builder.finish():
            out.append(self._emit_segment(u, s))
        return out
