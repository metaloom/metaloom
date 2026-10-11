"""
The chunk loop — one per delivery mode, shared by every engine.

An engine generates at most `engine.chunk_chars` of text at a time; this is what turns a request
of any length into a sequence of generations and welds them back together. It is deliberately
engine-agnostic: the two German engines and Kokoro differ only in the numbers they declare, so
the loop lives here once rather than three times, and a new engine inherits it.

The two modes split the text for **different reasons**, which is why they have different budgets:

* `synthesize` splits because one generation cannot exceed the engine's duration ceiling. Here
  the chunk is a CORRECTNESS device (without it the recording ends mid-word with no error
  anywhere) and the budget is as large as that ceiling allows.
* `stream_pieces` splits for LATENCY. `TTS_STREAM_CHUNK_CHARS` is deliberately smaller, because
  when an engine cannot stream inside a generation it is exactly the time-to-first-audio, and
  every later chunk is generated while the caller is still playing the one before it. It never
  exceeds the engine's own budget: a stream wants short chunks, but an engine that degrades on
  very short inputs must not be pushed below what it can say well.
"""

import logging
from typing import Iterator, List

import numpy as np

from . import config
from .audio import STREAM_ENCODERS
from .chunking import absorb_short_tail, split_for_synthesis
from .engines.base import Engine
from .postfx import PostFx

logger = logging.getLogger("tts.synth")


def chunks_for(engine: Engine, text: str, limit: int) -> List[str]:
    """`text` split for `engine` at `limit` characters: the shared splitter, then its tail rule."""
    return absorb_short_tail(split_for_synthesis(text, limit), engine.min_chunk_chars)


def stream_limit(engine: Engine) -> int:
    """The streaming chunk budget for `engine` — the smaller of the two that apply."""
    if engine.chunk_chars <= 0:
        return config.TTS_STREAM_CHUNK_CHARS
    return min(config.TTS_STREAM_CHUNK_CHARS, engine.chunk_chars)


def _gap(rate: int, dtype=np.float32):
    """The silence welded between two chunks.

    Without it the join is audible as a clipped word boundary; a real speaker pauses between
    sentences anyway, and chunks only ever end at one.
    """
    return np.zeros(int(rate * config.TTS_CHUNK_GAP_MS / 1000), dtype=dtype)


def _check_rate(rate: int, chunk_rate: int, i: int) -> int:
    if not rate:
        return chunk_rate
    if chunk_rate != rate:   # one engine, one checkpoint — cannot happen, must not pass
        raise RuntimeError(f"chunk {i} came back at {chunk_rate} Hz, not {rate} Hz")
    return rate


def synthesize(engine: Engine, text: str, voice: str):
    """Speech for `text` of any length as ONE `(waveform, rate)` pair.

    `audio.encode` still sees a single waveform, which keeps "one place where a waveform becomes
    bytes" true however many generations went into it.
    """
    chunks = chunks_for(engine, text, engine.chunk_chars)
    if len(chunks) <= 1:
        # The overwhelming majority of requests, and byte-for-byte the un-chunked path.
        return engine.generate(chunks[0] if chunks else text, voice)

    logger.info("chunked synthesis — %d chars in %d parts (engine=%s, chunk_chars=%d)",
                len(text), len(chunks), engine.name, engine.chunk_chars)
    pieces: List = []
    rate = 0
    for i, chunk in enumerate(chunks, 1):
        waveform, chunk_rate = engine.generate(chunk, voice)
        rate = _check_rate(rate, chunk_rate, i)
        if pieces:
            pieces.append(_gap(rate, waveform.dtype))
        pieces.append(waveform)
        logger.info("  chunk %d/%d — %d chars -> %.2f s", i, len(chunks), len(chunk),
                    len(waveform) / rate)
    return np.concatenate(pieces), rate


def stream_pieces(engine: Engine, text: str, voice: str) -> Iterator[tuple]:
    """Speech for `text` of any length as `(waveform, rate)` pieces, in order.

    The inter-chunk silence is the same `TTS_CHUNK_GAP_MS` as in the file — a seam that is audible
    as a clipped word in a recording is just as audible in a stream.
    """
    limit = stream_limit(engine)
    chunks = chunks_for(engine, text, limit) or [text]
    logger.info("streaming synthesis — %d chars in %d parts (engine=%s, chunk_chars=%d, "
                "incremental=%s)", len(text), len(chunks), engine.name, limit,
                engine.incremental and config.TTS_STREAM_INCREMENTAL)

    rate = 0
    for i, chunk in enumerate(chunks, 1):
        if i > 1 and rate:
            yield _gap(rate), rate
        for waveform, piece_rate in engine.stream(chunk, voice):
            rate = _check_rate(rate, piece_rate, i)
            yield waveform, rate


def stream_bytes(engine: Engine, text: str, voice: str, fmt: str) -> Iterator[bytes]:
    """`(text, voice, fmt)` as the byte chunks of a response, in order.

    The streaming counterpart of `audio.encode` and, like it, the only place the container is
    decided for this delivery mode. The encoder cannot be built before the first piece arrives,
    because the sample rate belongs to the engine that produced it and not to the request.

    A failure PART WAY THROUGH is re-raised, which drops the connection and leaves the caller with
    a truncated stream. That is deliberate: the alternative is ending the response cleanly, which
    is indistinguishable from a recording that simply finished — exactly the "looks like success"
    failure that a silent WAV would be.
    """
    encoder = None
    postfx = None
    total = 0
    try:
        for waveform, rate in stream_pieces(engine, text, voice):
            if encoder is None:
                encoder = STREAM_ENCODERS[fmt](rate)
                postfx = PostFx(rate) if config.TTS_POSTFX != "off" else None
                header = encoder.open()
                if header:
                    total += len(header)
                    yield header
            if postfx is not None:
                waveform = postfx.process(waveform)
            chunk = encoder.write(waveform)
            if chunk:
                total += len(chunk)
                yield chunk
    except Exception:
        logger.error("streaming synthesis failed after %d bytes — the caller gets a truncated "
                     "stream", total, exc_info=True)
        raise

    if encoder is not None:
        tail = encoder.close()
        if tail:
            total += len(tail)
            yield tail
    logger.info("TTS stream finished — %d bytes %s", total, fmt)
