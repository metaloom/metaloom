"""
Voxtral's token-to-time rule, on the token stream the model actually produced.

TOKENS is clip 0 of tests/data/de_timeline.flac exactly as Voxtral-Mini-4B-Realtime-2602 decoded
it (generated-token index k -> piece; every other frame was [STREAMING_PAD] / [STREAMING_WORD]).
Whisper's DTW word ends for the same audio are in WHISPER_END. The rule under test: a word ends
at (k - 1) * 80 ms of its first token.
"""

from asr.backends.voxtral import TokenTimer
from asr.timecodes import SegmentBuilder

TOKENS = {41: " Casablanca", 42: " ist", 46: " einer", 47: " der", 58: " uninter", 59: "ess", 60: "ant",
          61: "esten", 62: " Orte", 65: " zum", 69: " Shop", 70: "pen", 71: " in", 72: " ganz",
          79: " Marokko", 80: "."}
WHISPER_END = {"Casablanca": 3.14, "ist": 3.32, "einer": 3.50, "der": 3.64, "uninteressantesten": 4.52,
               "Orte": 4.82, "zum": 5.04, "Shoppen": 5.36, "in": 5.48, "ganz": 5.70, "Marokko.": 6.24}


def _run():
    """Replay the stream the way the realtime session consumes it."""
    timer, builder = TokenTimer(0.08, shift_s=0.0), SegmentBuilder()
    words, segments = [], []
    for k in range(0, 110):
        done = timer.step(k, TOKENS.get(k))
        words += done
        for w in done:
            segments += builder.push(w)
        now = timer.clock(k)
        if now is not None:
            segments += builder.tick(now)
    tail = timer.finish()
    words += tail
    for w in tail:
        segments += builder.push(w)
    return words, segments + builder.finish()


def test_words_and_their_measured_ends():
    words, _ = _run()
    assert [w.text for w in words] == list(WHISPER_END)
    for w in words:
        assert abs(w.end - WHISPER_END[w.text]) <= 0.121, (w.text, w.end, WHISPER_END[w.text])  # 1.5 frames
    assert all(a.end <= b.start + 1e-9 for a, b in zip(words, words[1:]))  # no overlap
    assert all(w.start <= w.end for w in words)


def test_a_long_word_arriving_is_not_a_pause():
    """"uninteressantesten" takes four frames; the segment must not close while it arrives."""
    _, segments = _run()
    assert [s.text for s in segments] == ["Casablanca ist einer der uninteressantesten Orte zum Shoppen in ganz Marokko."]


def test_clock_is_none_while_a_word_is_open():
    timer = TokenTimer(0.08, shift_s=0.0)
    timer.step(10, " Haus")
    assert timer.clock(10) is None
    timer.step(11, None)
    timer.step(12, None)
    assert timer.step(13, None)[0].text == "Haus"  # settled after three quiet frames
    assert abs(timer.clock(13) - (12 * 0.08 - 1.2)) < 1e-9
