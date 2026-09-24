"""
The time-coded transcript every backend produces, and the formats it is rendered into.

This module is the contract of the sidecar. Whisper, Parakeet and Voxtral each find their timings
in a different place (cross-attention DTW, transducer frame indices, the frame a streaming token
was emitted in) but all of them are reduced to the same three types before anything leaves the
process:

  Word        one recognised word with start/end in seconds
  Segment     a run of words - a sentence or a phrase between pauses
  Transcript  the segments of one request or one realtime utterance

All times are **seconds from the start of the audio** the caller sent - for a realtime session
that is the start of the session, not of the utterance. `offset()` is the one place a backend's
utterance-relative times become absolute; nothing else adds offsets.

Rendered formats (`render`):

  verbose_json  OpenAI's transcription shape plus `words` per segment and integer *_ms fields
  json          {"text": ...} - OpenAI's default, no time codes
  loom          {"segments": [{"text", "from", "to"}]} in ms - byte-compatible with
                io.metaloom.cortex.media.whisper.WhisperResult#fromJson
  srt / vtt     subtitle files
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Iterable, List, Optional

# Sentence-final punctuation closes a segment even without a pause. The German quotes are there
# because both Parakeet and Voxtral emit them after the full stop.
_SENTENCE_END = re.compile(r"[.!?…]['\"»«“”]*$")
_PUNCTUATION = re.compile(r"^[^\w\s]+$")


@dataclass
class Word:
    text: str
    start: float
    end: float
    probability: Optional[float] = None

    def to_dict(self) -> dict:
        d = {"word": self.text, "start": _r(self.start), "end": _r(self.end)}
        if self.probability is not None:
            d["probability"] = round(float(self.probability), 4)
        return d


@dataclass
class Segment:
    start: float
    end: float
    text: str
    words: List[Word] = field(default_factory=list)

    def to_dict(self, index: int) -> dict:
        return {
            "id": index,
            "start": _r(self.start),
            "end": _r(self.end),
            "start_ms": to_ms(self.start),
            "end_ms": to_ms(self.end),
            "text": self.text,
            "words": [w.to_dict() for w in self.words],
        }


@dataclass
class Transcript:
    segments: List[Segment]
    language: Optional[str] = None
    duration: float = 0.0
    model: str = ""

    @property
    def text(self) -> str:
        return join_text(s.text for s in self.segments)

    @property
    def words(self) -> List[Word]:
        return [w for s in self.segments for w in s.words]


def _r(seconds: float) -> float:
    return round(float(seconds), 3)


def to_ms(seconds: float) -> int:
    return int(round(float(seconds) * 1000))


def join_text(parts: Iterable[str]) -> str:
    return " ".join(p.strip() for p in parts if p and p.strip())


# ---------------------------------------------------------------------------
# Building transcripts
# ---------------------------------------------------------------------------
class WordBuilder:
    """
    Merge a stream of timed sub-word tokens into words.

    A token that begins with a space (or SentencePiece's U+2581) starts a new word; any other
    token continues the current one. A word is only *complete* once the next word starts, the
    stream pauses for `settle` seconds, or `finish()` is called - until then another piece of it
    may still arrive. Tokens that are only whitespace are dropped.
    """

    def __init__(self, settle: float = 0.4):
        self.settle = settle
        self.current: Optional[Word] = None

    def push(self, token: str, start: float, end: float) -> List[Word]:
        token = token.replace("\u2581", " ")
        if not token.strip():
            return []
        done: List[Word] = []
        if self.current is None or token[0].isspace():
            if self.current is not None:
                done.append(self.current)
            self.current = Word(token.strip(), start, end)
        else:
            self.current.text += token.rstrip()
            # Punctuation has no sound of its own. Transducers emit the final "." after the
            # speaker has stopped, and letting it stretch the word would push the end of every
            # sentence into the following silence.
            if not _PUNCTUATION.match(token.strip()):
                self.current.end = max(self.current.end, end)
        return done

    def tick(self, now: float) -> List[Word]:
        if self.current is not None and now - self.current.end >= self.settle:
            return self.finish()
        return []

    def finish(self) -> List[Word]:
        done, self.current = ([self.current] if self.current else []), None
        return done


def words_from_tokens(tokens: List[str], starts: List[float], ends: List[float]) -> List[Word]:
    """Batch form of WordBuilder: the words of a finished token sequence."""
    builder = WordBuilder()
    words: List[Word] = []
    for tok, start, end in zip(tokens, starts, ends):
        words += builder.push(tok, start, end)
    return words + builder.finish()


class SegmentBuilder:
    """
    Group a stream of words into segments.

    A segment closes after sentence-final punctuation, after a pause of at least `max_gap`
    seconds, or when the next word would take it past `max_duration`. `tick(now)` closes the
    open segment once `max_gap` has passed with no new word - that is how a realtime stream gets a
    final segment during a pause instead of only when the speaker starts again.
    """

    def __init__(self, max_gap: float = 0.8, max_duration: float = 15.0):
        self.max_gap = max_gap
        self.max_duration = max_duration
        self.words: List[Word] = []

    def push(self, word: Word) -> List[Segment]:
        done: List[Segment] = []
        if self.words:
            prev = self.words[-1]
            if (
                _SENTENCE_END.search(prev.text)
                or word.start - prev.end >= self.max_gap
                or word.end - self.words[0].start > self.max_duration
            ):
                done = self.finish()
        self.words.append(word)
        return done

    def tick(self, now: float) -> List[Segment]:
        if self.words and now - self.words[-1].end >= self.max_gap:
            return self.finish()
        return []

    def finish(self) -> List[Segment]:
        if not self.words:
            return []
        words, self.words = self.words, []
        return [Segment(words[0].start, words[-1].end, join_text(w.text for w in words), words)]


def segments_from_words(words: List[Word], max_gap: float = 0.8, max_duration: float = 15.0) -> List[Segment]:
    """
    Batch form of SegmentBuilder, for the backends that only have word timings (Parakeet,
    Voxtral). Whisper brings its own segments and does not go through here.
    """
    builder = SegmentBuilder(max_gap, max_duration)
    segments: List[Segment] = []
    for w in words:
        segments += builder.push(w)
    return segments + builder.finish()


def offset(transcript: Transcript, seconds: float) -> Transcript:
    """Shift every time in the transcript by `seconds`. Returns a new transcript."""
    if not seconds:
        return transcript
    segments = [
        Segment(
            s.start + seconds,
            s.end + seconds,
            s.text,
            [Word(w.text, w.start + seconds, w.end + seconds, w.probability) for w in s.words],
        )
        for s in transcript.segments
    ]
    return Transcript(segments, transcript.language, transcript.duration, transcript.model)


def merge(parts: List[Transcript], duration: float, model: str, language: Optional[str]) -> Transcript:
    """Concatenate already-offset transcripts of consecutive chunks of one audio."""
    segments = [s for p in parts for s in p.segments]
    detected = next((p.language for p in parts if p.language), None)
    return Transcript(segments, language or detected, duration, model)


def clamp(transcript: Transcript, lo: float, hi: float) -> Transcript:
    """
    Keep every time inside [lo, hi] and non-decreasing.

    Models occasionally place a word a frame before the audio they were given or past its end
    (Whisper's last word, a Voxtral token decoded out of the commit padding). A time code outside
    the audio is worse than a slightly squashed one: the caller seeks to it.
    """
    for s in transcript.segments:
        for w in s.words:
            w.start = min(max(w.start, lo), hi)
            w.end = min(max(w.end, w.start), hi)
        if s.words:
            s.start = min(max(min(s.start, s.words[0].start), lo), hi)
            s.end = min(max(max(s.end, s.words[-1].end), s.start), hi)
        else:
            s.start = min(max(s.start, lo), hi)
            s.end = min(max(s.end, s.start), hi)
    return transcript


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------
RESPONSE_FORMATS = ("verbose_json", "json", "text", "loom", "srt", "vtt")


def to_verbose_json(t: Transcript) -> dict:
    return {
        "task": "transcribe",
        "language": t.language,
        "duration": _r(t.duration),
        "model": t.model,
        "text": t.text,
        "segments": [s.to_dict(i) for i, s in enumerate(t.segments)],
        "words": [w.to_dict() for w in t.words],
    }


def to_loom(t: Transcript) -> dict:
    """The exact JSON `WhisperResult.fromJson` parses: segments with text and from/to in ms."""
    return {"segments": [{"text": s.text, "from": to_ms(s.start), "to": to_ms(s.end)} for s in t.segments]}


def _clock(seconds: float, sep: str) -> str:
    ms = to_ms(seconds)
    h, rem = divmod(ms, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, ms = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d}{sep}{ms:03d}"


def to_srt(t: Transcript) -> str:
    blocks = []
    for i, s in enumerate(t.segments, 1):
        blocks.append(f"{i}\n{_clock(s.start, ',')} --> {_clock(s.end, ',')}\n{s.text}\n")
    return "\n".join(blocks)


def to_vtt(t: Transcript) -> str:
    blocks = ["WEBVTT\n"]
    for s in t.segments:
        blocks.append(f"{_clock(s.start, '.')} --> {_clock(s.end, '.')}\n{s.text}\n")
    return "\n".join(blocks)


def render(t: Transcript, response_format: str):
    """Returns (body, media_type). `body` is a dict for the JSON formats, else a str."""
    if response_format == "verbose_json":
        return to_verbose_json(t), "application/json"
    if response_format == "json":
        return {"text": t.text}, "application/json"
    if response_format == "loom":
        return to_loom(t), "application/json"
    if response_format == "text":
        return t.text + "\n", "text/plain; charset=utf-8"
    if response_format == "srt":
        return to_srt(t), "application/x-subrip; charset=utf-8"
    if response_format == "vtt":
        return to_vtt(t), "text/vtt; charset=utf-8"
    raise ValueError(f"unknown response_format {response_format!r}, expected one of {RESPONSE_FORMATS}")
