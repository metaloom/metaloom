"""The endpointer on synthetic audio with known speech positions."""

import numpy as np

from asr.endpointing import Endpointer, EndpointerConfig, split

RATE = 16000


def _timeline(spec, seed=7):
    """spec: [(seconds, loud)] -> audio. Loud parts are a modulated tone, quiet parts faint noise."""
    rng = np.random.default_rng(seed)
    parts = []
    for seconds, loud in spec:
        n = int(seconds * RATE)
        if loud:
            t = np.arange(n) / RATE
            parts.append((0.2 * np.sin(2 * np.pi * 220 * t) * (0.6 + 0.4 * np.sin(2 * np.pi * 3 * t))).astype(np.float32))
        else:
            parts.append((rng.standard_normal(n) * 1e-3).astype(np.float32))
    return np.concatenate(parts)


def test_split_finds_the_speech_and_drops_the_gaps():
    audio = _timeline([(1.0, False), (2.0, True), (2.0, False), (1.5, True), (1.0, False)])
    spans = [(s / RATE, e / RATE) for s, e in split(audio)]
    assert len(spans) == 2
    (a0, a1), (b0, b1) = spans
    # speech 1.0-3.0 and 5.0-6.5, pre-roll 0.3, post-roll 0.3
    assert abs(a0 - 0.7) < 0.05 and abs(a1 - 3.3) < 0.05
    assert abs(b0 - 4.7) < 0.05 and abs(b1 - 6.8) < 0.05


def test_streaming_feed_equals_offline_split():
    audio = _timeline([(0.5, False), (1.2, True), (1.0, False), (3.0, True), (0.9, False), (0.7, True), (0.3, False)])
    ep = Endpointer()
    closed = []
    for i in range(0, len(audio), 1234):  # an awkward chunk size on purpose
        closed += ep.feed(audio[i : i + 1234])[1]
    last = ep.flush()
    streamed = [(s.start, s.end) for s in closed] + ([(last.start, last.end)] if last else [])
    assert streamed == split(audio)


def test_short_pause_does_not_cut():
    audio = _timeline([(0.5, False), (1.0, True), (0.3, False), (1.0, True), (1.0, False)])
    assert len(split(audio)) == 1


def test_continuous_speech_is_cut_at_max_length_without_gaps():
    audio = _timeline([(0.2, False), (13.0, True), (0.8, False)])
    spans = split(audio, EndpointerConfig(max_utterance_s=5.0))
    assert len(spans) >= 3
    assert all(e - s <= 5.0 * RATE + 480 for s, e in spans)
    for (_, e), (s, _) in zip(spans, spans[1:]):
        assert s == e  # forced cuts leave no hole in the audio


def test_silence_only_is_one_span():
    audio = _timeline([(2.0, False)])
    assert split(audio) == [(0, len(audio))]


def test_spans_never_overlap_the_previous_one():
    audio = _timeline([(0.5, False), (1.0, True), (0.65, False), (1.0, True), (0.5, False)])
    spans = split(audio, EndpointerConfig(silence_ms=600, preroll_ms=500))
    for (_, e), (s, _) in zip(spans, spans[1:]):
        assert s >= e
