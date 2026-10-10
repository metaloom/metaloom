"""
Where is speech, and how is it cut into the fixed-length windows the embedding models see?

Two steps. `speech_spans` is the energy-based endpointer the ASR sidecar uses (a frame is loud
when it is `margin_db` above a running noise floor and above `floor_db`; a span opens after
`min_speech_ms` of loud frames and closes after `silence_ms` without one). It finds pauses, not
phonemes, needs no model, and is deterministic. `windows` then slides a `window_s` window with
`hop_s` hop over every span.

Every window has exactly `window_s` of audio. That is a property the backends rely on: the models
are batched, and padding a short input changes its embedding (the eval harness this sidecar grew
out of asserts batch == single on equal lengths only). A span shorter than the window but at least
`min_speech_s` long is **tiled** - repeated until it fills the window - which is the usual way to
embed a short utterance with a fixed-input model, and far better than zero padding. Spans shorter
than `min_speech_s` are dropped: a cough is not a voice.

Positions are sample indices into the decoded audio; the server turns them into milliseconds.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np

SAMPLE_RATE = 16000
FRAME = 480  # 30 ms at 16 kHz


@dataclass
class VadConfig:
    silence_ms: int = 400
    min_speech_ms: int = 150
    preroll_ms: int = 100
    postroll_ms: int = 150
    floor_db: float = -50.0
    margin_db: float = 12.0


def speech_spans(audio: np.ndarray, config: Optional[VadConfig] = None, sample_rate: int = SAMPLE_RATE) -> List[Tuple[int, int]]:
    """(start, end) sample spans of speech. Audio with no detectable speech comes back empty."""
    c = config or VadConfig()
    ms = sample_rate // 1000
    silence_frames = max(1, c.silence_ms * ms // FRAME)
    min_speech_frames = max(1, c.min_speech_ms * ms // FRAME)
    preroll = c.preroll_ms * ms
    postroll = c.postroll_ms * ms

    audio = np.asarray(audio, dtype=np.float32)
    n = len(audio) // FRAME
    if n == 0:
        return []
    frames = audio[: n * FRAME].reshape(n, FRAME)
    db = 20.0 * np.log10(np.sqrt((frames * frames).mean(axis=1)) + 1e-10)

    spans: List[Tuple[int, int]] = []
    noise_db = -60.0
    loud_run = 0
    quiet_run = 0
    start: Optional[int] = None
    last_loud_end = 0
    prev_end = 0
    for i in range(n):
        pos = (i + 1) * FRAME
        loud = db[i] > max(c.floor_db, noise_db + c.margin_db)
        if not loud:
            # The floor follows quiet frames quickly downwards and slowly upwards, so one loud
            # breath does not raise it but a noisier room eventually does.
            rate = 0.2 if db[i] < noise_db else 0.02
            noise_db += rate * (db[i] - noise_db)
        if start is None:
            loud_run = loud_run + 1 if loud else 0
            if loud_run >= min_speech_frames:
                start = max(0, pos - loud_run * FRAME - preroll, prev_end)
                quiet_run = 0
                last_loud_end = pos
            continue
        if loud:
            quiet_run = 0
            last_loud_end = pos
        else:
            quiet_run += 1
        if quiet_run >= silence_frames:
            end = min(pos, last_loud_end + postroll)
            spans.append((start, end))
            prev_end = end
            start = None
            loud_run = 0
    if start is not None:
        spans.append((start, min(len(audio), last_loud_end + postroll)))
    return spans


@dataclass
class Window:
    start: int  # sample index of the first sample this window covers
    end: int  # sample index past the last sample it covers (end - start <= window length)
    audio: np.ndarray  # exactly `length` samples, tiled when the span was shorter


def windows(audio: np.ndarray, spans: List[Tuple[int, int]], window_s: float, hop_s: float, min_speech_s: float,
            max_windows: int = 0, sample_rate: int = SAMPLE_RATE) -> List[Window]:
    """Fixed-length windows over every span. `max_windows` > 0 caps the count by widening the hop."""
    length = max(1, int(round(window_s * sample_rate)))
    hop = max(1, int(round(hop_s * sample_rate)))
    min_len = max(1, int(round(min_speech_s * sample_rate)))

    cuts: List[Tuple[int, int]] = []
    for s, e in spans:
        if e - s < min_len:
            continue
        if e - s <= length:
            cuts.append((s, e))
            continue
        pos = s
        while pos + length <= e:
            cuts.append((pos, pos + length))
            pos += hop
        if cuts[-1][1] < e and e - cuts[-1][1] >= min_len // 2:
            # The tail nobody covered: a last window ending exactly at the span end.
            cuts.append((e - length, e))
    if max_windows > 0 and len(cuts) > max_windows:
        keep = np.linspace(0, len(cuts) - 1, max_windows).round().astype(int)
        cuts = [cuts[i] for i in sorted(set(keep.tolist()))]

    out: List[Window] = []
    for s, e in cuts:
        chunk = audio[s:e]
        if len(chunk) < length:
            reps = int(np.ceil(length / max(1, len(chunk))))
            chunk = np.tile(chunk, reps)[:length]
        out.append(Window(s, e, np.ascontiguousarray(chunk, dtype=np.float32)))
    return out
