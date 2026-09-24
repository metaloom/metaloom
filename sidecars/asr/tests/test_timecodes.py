"""The transcript model and its renderings - no model, no server."""

import json

from asr import timecodes
from asr.timecodes import Segment, SegmentBuilder, Transcript, Word, WordBuilder


def test_word_builder_merges_subword_tokens():
    # Parakeet's actual token stream for "Für Stromausfälle hat" (sherpa-onnx, leading-space words)
    tokens = [" F", "ür", " S", "tr", "oma", "us", "f", "äl", "le", " hat"]
    starts = [0.0, 0.08, 0.32, 0.4, 0.56, 0.88, 1.04, 1.12, 1.2, 1.36]
    ends = [s + 0.08 for s in starts]
    words = timecodes.words_from_tokens(tokens, starts, ends)
    assert [w.text for w in words] == ["Für", "Stromausfälle", "hat"]
    assert (words[0].start, words[0].end) == (0.0, 0.16)
    assert words[1].start == 0.32 and abs(words[1].end - 1.28) < 1e-9


def test_word_builder_sentencepiece_marker_and_blank_tokens():
    words = timecodes.words_from_tokens(["▁Guten", " ", "▁Tag", "."], [0, 0.3, 0.5, 0.7], [0.3, 0.3, 0.7, 0.8])
    assert [w.text for w in words] == ["Guten", "Tag."]
    # the "." joins the word but, having no sound, does not stretch it into the silence after it
    assert words[1].end == 0.7


def test_word_builder_completes_on_pause_only_after_settle():
    b = WordBuilder(settle=0.24)
    assert b.push(" Haus", 1.0, 1.0) == []
    assert b.tick(1.16) == []  # a continuation may still come
    assert [w.text for w in b.tick(1.24)] == ["Haus"]


def test_segments_split_on_sentence_end_pause_and_length():
    w = [
        Word("Hallo", 0.0, 0.4), Word("Welt.", 0.45, 0.9),       # sentence end
        Word("Wie", 1.0, 1.2), Word("geht's", 1.25, 1.6),        # 1.0 s pause follows
        Word("dir?", 2.6, 2.9),
    ]
    segs = timecodes.segments_from_words(w, max_gap=0.8)
    assert [s.text for s in segs] == ["Hallo Welt.", "Wie geht's", "dir?"]
    assert (segs[1].start, segs[1].end) == (1.0, 1.6)

    long = [Word(f"w{i}", i * 1.0, i * 1.0 + 0.9) for i in range(20)]
    for s in timecodes.segments_from_words(long, max_gap=5, max_duration=5.0):
        assert s.end - s.start <= 5.0


def test_segment_builder_tick_closes_during_a_pause():
    b = SegmentBuilder(max_gap=0.8)
    assert b.push(Word("Guten", 0.0, 0.3)) == []
    assert b.push(Word("Morgen", 0.35, 0.8)) == []
    assert b.tick(1.2) == []
    [seg] = b.tick(1.6)
    assert seg.text == "Guten Morgen" and (seg.start, seg.end) == (0.0, 0.8)
    assert b.finish() == []


def test_offset_shifts_segments_and_words_without_mutating():
    t = Transcript([Segment(0.5, 1.5, "Hallo Welt", [Word("Hallo", 0.5, 0.9), Word("Welt", 1.0, 1.5)])], "de", 2.0, "m")
    shifted = timecodes.offset(t, 10.0)
    assert (shifted.segments[0].start, shifted.segments[0].end) == (10.5, 11.5)
    assert [(w.start, w.end) for w in shifted.words] == [(10.5, 10.9), (11.0, 11.5)]
    assert t.segments[0].start == 0.5


def test_clamp_keeps_times_inside_the_audio_and_ordered():
    t = Transcript([Segment(-0.2, 3.4, "a b", [Word("a", -0.2, 0.5), Word("b", 2.9, 3.4)])])
    timecodes.clamp(t, 0.0, 3.0)
    s = t.segments[0]
    assert (s.start, s.end) == (0.0, 3.0)
    assert [(w.start, w.end) for w in s.words] == [(0.0, 0.5), (2.9, 3.0)]


def _sample():
    return Transcript(
        [
            Segment(1.234, 2.5, "Guten Morgen.", [Word("Guten", 1.234, 1.6, 0.98), Word("Morgen.", 1.65, 2.5)]),
            Segment(3661.0, 3662.0, "Tschüss", [Word("Tschüss", 3661.0, 3662.0)]),
        ],
        "de", 3663.0, "whisper-test",
    )


def test_verbose_json_shape():
    body, media = timecodes.render(_sample(), "verbose_json")
    assert media == "application/json"
    assert body["text"] == "Guten Morgen. Tschüss"
    assert body["language"] == "de" and body["duration"] == 3663.0
    seg = body["segments"][0]
    assert seg == {
        "id": 0, "start": 1.234, "end": 2.5, "start_ms": 1234, "end_ms": 2500, "text": "Guten Morgen.",
        "words": [{"word": "Guten", "start": 1.234, "end": 1.6, "probability": 0.98},
                  {"word": "Morgen.", "start": 1.65, "end": 2.5}],
    }
    assert len(body["words"]) == 3
    json.dumps(body)


def test_loom_format_matches_whisper_result_json():
    """io.metaloom.cortex.media.whisper.WhisperResult#fromJson reads segments[].text/from/to (long ms)."""
    body, _ = timecodes.render(_sample(), "loom")
    assert body == {"segments": [{"text": "Guten Morgen.", "from": 1234, "to": 2500},
                                 {"text": "Tschüss", "from": 3661000, "to": 3662000}]}
    assert all(isinstance(s["from"], int) and isinstance(s["to"], int) for s in body["segments"])


def test_subtitles():
    srt, _ = timecodes.render(_sample(), "srt")
    assert srt.startswith("1\n00:00:01,234 --> 00:00:02,500\nGuten Morgen.\n")
    assert "2\n01:01:01,000 --> 01:01:02,000\nTschüss" in srt
    vtt, _ = timecodes.render(_sample(), "vtt")
    assert vtt.startswith("WEBVTT\n") and "00:00:01.234 --> 00:00:02.500" in vtt


def test_text_and_json_formats():
    assert timecodes.render(_sample(), "text")[0] == "Guten Morgen. Tschüss\n"
    assert timecodes.render(_sample(), "json")[0] == {"text": "Guten Morgen. Tschüss"}
