"""
The speech finder and the windowing: spans land on the loud parts, windows have one fixed length,
short spans are tiled rather than padded, and silence yields nothing.
"""

import numpy as np

from voiceprint import vad

RATE = 16000


def _timeline(spec, seed=0):
    """spec: [(seconds, loud), ...] -> float32 audio of band-limited noise bursts and silence."""
    rng = np.random.default_rng(seed)
    parts = []
    for seconds, loud in spec:
        n = int(seconds * RATE)
        if loud:
            parts.append((rng.standard_normal(n) * 0.2).astype(np.float32))
        else:
            parts.append((rng.standard_normal(n) * 0.0005).astype(np.float32))
    return np.concatenate(parts)


SPEC = [(1.0, False), (2.0, True), (1.0, False), (4.0, True), (1.0, False)]
SPEECH = [(1.0, 3.0), (4.0, 8.0)]


def test_spans_cover_the_loud_parts():
    spans = vad.speech_spans(_timeline(SPEC))
    assert len(spans) == 2
    for (s, e), (ts, te) in zip(spans, SPEECH):
        assert abs(s / RATE - ts) < 0.2
        assert abs(e / RATE - te) < 0.3


def test_silence_has_no_spans():
    assert vad.speech_spans(_timeline([(3.0, False)])) == []
    assert vad.speech_spans(np.zeros(0, dtype=np.float32)) == []


def test_windows_have_one_length_and_sit_inside_their_span():
    audio = _timeline(SPEC)
    spans = vad.speech_spans(audio)
    wins = vad.windows(audio, spans, 3.0, 1.5, 1.0)
    assert wins, "the 4 s span alone must yield windows"
    for w in wins:
        assert len(w.audio) == 3 * RATE
        assert w.end - w.start <= 3 * RATE
        assert any(s <= w.start and w.end <= e for s, e in spans)


def test_short_span_is_tiled_not_padded():
    audio = _timeline([(0.5, False), (1.5, True), (0.5, False)])
    spans = vad.speech_spans(audio)
    wins = vad.windows(audio, spans, 3.0, 1.5, 1.0)
    assert len(wins) == 1
    w = wins[0]
    assert len(w.audio) == 3 * RATE
    assert w.end - w.start < 3 * RATE
    span_len = w.end - w.start
    # The window repeats the span: sample k and sample k + span_len are identical.
    np.testing.assert_array_equal(w.audio[:span_len // 2], w.audio[span_len : span_len + span_len // 2])
    assert np.abs(w.audio).mean() > 0.05, "no zero padding"


def test_span_below_min_speech_is_dropped():
    audio = _timeline([(0.5, False), (0.4, True), (0.5, False)])
    spans = vad.speech_spans(audio)
    assert spans, "the endpointer still sees the burst"
    assert vad.windows(audio, spans, 3.0, 1.5, 1.0) == []


def test_max_windows_caps_the_count():
    audio = _timeline([(0.5, False), (30.0, True), (0.5, False)])
    spans = vad.speech_spans(audio)
    assert len(vad.windows(audio, spans, 3.0, 1.5, 1.0)) > 10
    capped = vad.windows(audio, spans, 3.0, 1.5, 1.0, max_windows=5)
    assert len(capped) == 5
    assert capped[0].start < capped[-1].start


def test_hop_spacing():
    audio = _timeline([(0.5, False), (10.0, True), (0.5, False)])
    wins = vad.windows(audio, vad.speech_spans(audio), 2.0, 1.0, 1.0)
    starts = [w.start for w in wins]
    diffs = np.diff(starts[:-1])
    assert all(abs(d - RATE) <= 1 for d in diffs), diffs
