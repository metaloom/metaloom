"""
Waveform stages and containers — everything between "the engine made audio" and "bytes".

Each function below is the ONE place its job is done, which is the rule that keeps the two
delivery modes (a file and a stream) and the engines from drifting apart:

| Function | The one place where … |
|---|---|
| `encode` | a whole waveform becomes bytes. Adding a container is one entry in `FORMATS`. |
| `WavStream` / `Mp3Stream` | a SEQUENCE of waveforms becomes bytes, with no total length known |
| `trim_edges` | a generation loses the near-silence the engine left around the speech |
| `fit_peak` | a waveform is kept inside full scale — with a LINEAR gain, never a curve |
| `disperse` | a vocoder's per-period tick is spread out in time (all-pass, spectrum untouched) |
| `level_reference` / `even_out_reference` | a voice-cloning reference clip is evened and leveled |

`fit_peak` has history worth keeping: it started as a sample-wise soft knee (`tanh` above
-3 dBFS) so a stream could apply it piece by piece — but bending individual samples is exactly
what an overdrive pedal does, and it added harmonics to the loudest syllables of the very voices
it was meant to protect. A linear gain cannot distort; the price is that it needs a whole
waveform, so it is applied where one exists.
"""

import io
import logging
import os
import struct

import numpy as np
import soundfile as sf
from fastapi import HTTPException

from . import config
from .postfx import PostFx

logger = logging.getLogger("tts.audio")

# --------------------------------------------------------------------------- #
# Output containers
# --------------------------------------------------------------------------- #
# `format` used to be accepted and ignored, which made the field a lie: a caller asking for mp3
# got wav and had no way to tell. Both entries below are written by libsndfile through
# `soundfile` — MP3 encoding needs libsndfile >= 1.1, which the soundfile >= 0.13 wheels bundle,
# so this costs no new dependency.
FORMATS = {
    "wav": ("WAV", "PCM_16", "audio/wav"),
    "mp3": ("MP3", None, "audio/mpeg"),
}
DEFAULT_FORMAT = "wav"


def encode(waveform, rate: int, fmt: str):
    """Write `waveform` into the container `fmt`; returns `(bytes, media_type)`.

    Every engine hands back a float waveform and lets this decide the container, so adding a
    format is a single entry in `FORMATS` rather than an edit in every synthesis path — which is
    how `format` came to be ignored in the first place.
    """
    try:
        sf_format, subtype, media_type = FORMATS[fmt]
    except KeyError:  # pragma: no cover — the route validates before calling
        raise HTTPException(status_code=400, detail=f"unsupported format: {fmt}")

    if config.TTS_POSTFX != "off":
        waveform = PostFx(rate).process(waveform)
    buf = io.BytesIO()
    kwargs = {"subtype": subtype} if subtype else {}
    try:
        sf.write(buf, fit_peak(waveform), rate, format=sf_format, **kwargs)
    except Exception as e:  # noqa: BLE001 — a libsndfile without MPEG support lands here
        raise HTTPException(
            status_code=500,
            detail=f"cannot encode {fmt}: {e}. libsndfile >= 1.1 is required for mp3.",
        )
    return buf.getvalue(), media_type


# --------------------------------------------------------------------------- #
# Streaming encoders — the same job for a sequence of waveforms
# --------------------------------------------------------------------------- #
# `encode` writes a recording whose length is known before the first byte goes out. A stream has
# neither: the bytes leave while the rest is still being generated, so the container has to be one
# that survives being read from the front with no total length.
#: The size a streaming RIFF header declares when it cannot know the real one. It is what every
#: streaming WAV producer writes, and libsndfile reads it back happily; a player reads until the
#: connection ends.
WAV_UNKNOWN_SIZE = 0xFFFFFFFF


class WavStream:
    """Raw PCM-16 behind a RIFF header whose two size fields are left unknown.

    A WAV header states the total length up front and a stream does not have one, so the fields
    are filled with `WAV_UNKNOWN_SIZE` instead of being patched later — there is no going back to
    patch them once the bytes have been sent.
    """

    def __init__(self, rate: int, channels: int = 1, bits: int = 16):
        self.rate, self.channels, self.bits = rate, channels, bits

    def open(self) -> bytes:
        block_align = self.channels * self.bits // 8
        return (
            b"RIFF" + struct.pack("<I", WAV_UNKNOWN_SIZE) + b"WAVEfmt "
            + struct.pack("<IHHIIHH", 16, 1, self.channels, self.rate,
                          self.rate * block_align, block_align, self.bits)
            + b"data" + struct.pack("<I", WAV_UNKNOWN_SIZE)
        )

    def write(self, waveform) -> bytes:
        clipped = np.clip(np.asarray(waveform, dtype=np.float32), -1.0, 1.0)
        return (clipped * 32767.0).astype("<i2").tobytes()

    def close(self) -> bytes:
        return b""


class Mp3Stream:
    """A libsndfile MP3 writer drained after every flush.

    MP3 is the container to ask for here: its frames are self-delimiting, so a decoder that joins
    the stream at an arbitrary byte still finds the next frame, and it is roughly twenty times
    smaller than the PCM above.

    libsndfile only ever APPENDS on `write`/`flush`; the one place it goes back is `close`, where
    it patches the LAME info frame with the final duration. Those bytes are long gone by then, so
    the stream keeps the duration the info frame had at the start and decoders treat the length as
    unknown — the same position every internet-radio MP3 is in.

    Two visible prices, both measured against ffmpeg. The encoder delay the patched info frame
    would have let a decoder trim stays at the very front (1728 samples, 72 ms of silence). And a
    player shows an ESTIMATED duration from the bitrate until it reaches the end — 2.90 s for a
    3.43 s stream — which is what an unknown length looks like, not a sign that anything was lost.
    """

    def __init__(self, rate: int, channels: int = 1):
        self._buf = io.BytesIO()
        self._emitted = 0
        try:
            self._handle = sf.SoundFile(self._buf, mode="w", format="MP3",
                                        samplerate=rate, channels=channels)
        except Exception as e:  # noqa: BLE001 — a libsndfile without MPEG support lands here
            raise HTTPException(
                status_code=500,
                detail=f"cannot encode mp3: {e}. libsndfile >= 1.1 is required for mp3.",
            )

    def _drain(self) -> bytes:
        """Everything libsndfile has appended since the last drain."""
        data = self._buf.getvalue()
        fresh, self._emitted = data[self._emitted:], len(data)
        return fresh

    def open(self) -> bytes:
        return self._drain()

    def write(self, waveform) -> bytes:
        self._handle.write(np.asarray(waveform, dtype=np.float32))
        self._handle.flush()
        return self._drain()

    def close(self) -> bytes:
        self._handle.close()
        return self._drain()


#: Same keys as `FORMATS`, which is what makes "every container the file endpoint accepts can
#: also be streamed" checkable rather than a claim.
STREAM_ENCODERS = {"wav": WavStream, "mp3": Mp3Stream}


# --------------------------------------------------------------------------- #
# Waveform edges
# --------------------------------------------------------------------------- #
def trim_edges(waveform, rate: int):
    """`waveform` without the near-silence the engine left before and after the speech.

    Only the EDGES. Everything between the first and the last audible window is returned
    untouched, so pauses the speaker made stay exactly as they were — this removes dead air that
    was never spoken, it does not compress speech.

    Deliberately conservative in both directions it can be wrong:

    * A waveform that is quiet all the way through is returned unchanged. It is either silence the
      caller asked for or a failed generation, and neither is this function's call to make.
    * `TTS_EDGE_KEEP_MS` of the silence is kept on each side, because the threshold is a level and
      a soft onset crosses it late.
    """
    audio = np.asarray(waveform, dtype=np.float32)
    if audio.ndim != 1 or rate <= 0:
        return waveform

    window = max(1, rate // 100)                      # 10 ms, short enough to sit inside a stop
    usable = audio.size - audio.size % window
    if usable < window:
        return waveform

    frames = audio[:usable].reshape(-1, window)
    threshold = 10 ** (config.TTS_EDGE_SILENCE_DB / 20)
    loud = np.nonzero(np.sqrt((frames ** 2).mean(axis=1)) > threshold)[0]
    if loud.size == 0:
        return waveform

    keep = int(rate * config.TTS_EDGE_KEEP_MS / 1000)
    start = max(0, int(loud[0]) * window - keep)
    end = min(audio.size, (int(loud[-1]) + 1) * window + keep)
    return audio[start:end]


# --------------------------------------------------------------------------- #
# Peak headroom
# --------------------------------------------------------------------------- #
def fit_peak(waveform):
    """`waveform` turned down as a whole, if needed, so its peak sits at `TTS_PEAK_CEILING_DB`.

    A plain GAIN, never a curve — see this module's docstring for why that distinction cost a
    rewrite. It needs a whole waveform, so it is applied where one exists: the whole recording in
    `encode`, and each whole generation at the Kartoffelbox seam (which also covers that engine's
    chunk-granular stream). Orpheus's incremental stream gets no headroom stage: it measured peaks
    of at most 0.94 and the pieces are too short for a gain that does not pump.
    """
    audio = np.asarray(waveform, dtype=np.float32)
    if audio.size == 0:
        return audio
    peak = float(np.abs(audio).max())
    ceiling = 10 ** (config.TTS_PEAK_CEILING_DB / 20)
    if peak <= ceiling:
        return audio
    return audio * np.float32(ceiling / peak)


# --------------------------------------------------------------------------- #
# Phase dispersion
# --------------------------------------------------------------------------- #
_DISPERSION_TAPS = 4096
_dispersion_cache: dict = {}


def dispersion_fir(rate: int, spread_ms: float, from_hz: float = 0.0):
    """An all-pass FIR whose group delay grows linearly with frequency, 0 -> `spread_ms`.

    The rise starts at `from_hz` (0 below it) and reaches `spread_ms` at Nyquist.

    Built in the frequency domain as `exp(j·phase)` with the phase integrated from that group
    delay, so |H| = 1 by construction; the impulse response sits under the flat top of a Tukey
    window so truncation costs under 0.1 dB. Anchored at zero delay: whatever lies below `from_hz`
    passes bit-for-bit, and higher frequencies arrive up to `spread_ms` later.
    """
    from scipy.signal.windows import tukey

    n = _DISPERSION_TAPS
    f = np.fft.rfftfreq(n, 1 / rate)
    nyquist = rate / 2
    from_hz = min(max(from_hz, 0.0), nyquist * 0.9)
    tau = (spread_ms / 1000) * np.clip((f - from_hz) / (nyquist - from_hz), 0.0, 1.0)
    phase = -2 * np.pi * np.concatenate([[0.0], np.cumsum((tau[1:] + tau[:-1]) / 2 * np.diff(f))])
    h = np.fft.irfft(np.exp(1j * phase), n)
    # (n-1)//2 is the zero-delay tap of a "same" convolution
    h = np.roll(h, (n - 1) // 2) * tukey(n, 0.25)
    return h.astype(np.float32)


def disperse(waveform, rate: int):
    """`waveform` through the dispersion all-pass of `KARTOFFELBOX_DISPERSION_MS`, or as it was."""
    from scipy.signal import fftconvolve

    audio = np.asarray(waveform, dtype=np.float32)
    if config.KARTOFFELBOX_DISPERSION_MS <= 0 or audio.size == 0:
        return audio
    key = (rate, config.KARTOFFELBOX_DISPERSION_MS, config.KARTOFFELBOX_DISPERSION_FROM_HZ)
    if key not in _dispersion_cache:
        _dispersion_cache[key] = dispersion_fir(rate, config.KARTOFFELBOX_DISPERSION_MS,
                                                config.KARTOFFELBOX_DISPERSION_FROM_HZ)
    return fftconvolve(audio, _dispersion_cache[key], mode="same").astype(np.float32)


# --------------------------------------------------------------------------- #
# Reference clips for a cloning engine
# --------------------------------------------------------------------------- #
def level_reference(path: str, out_dir: str) -> str:
    """A copy of the clip at `path`, evened out and at `KARTOFFELBOX_REF_RMS_DB`, in `out_dir`.

    Returns the path of the copy, or `path` itself when the clip has no speech to measure — an
    unreadable or silent clip is the engine's to reject, not this function's. The clip's loud
    bursts are compressed first (`even_out_reference`), then its level is set. The gain is capped
    so the copy itself never clips: a clip with a very high crest factor ends up a little under
    the target rather than distorted.

    Why a cloning engine needs this at all: Chatterbox reproduces the reference clip's level and
    dynamics one-for-one, so a loud or bursty clip is cloned shouting. See `config`.
    """
    try:
        audio, rate = sf.read(path, dtype="float32", always_2d=True)
    except Exception as e:  # noqa: BLE001 — fall through to the engine's own loader and error
        logger.warning("cannot read reference clip %s to level it (%s) — using it as is", path, e)
        return path
    audio = even_out_reference(audio.mean(axis=1), rate)

    window = max(1, rate // 100)
    usable = audio.size - audio.size % window
    if usable < window:
        return path
    power = (audio[:usable].reshape(-1, window) ** 2).mean(axis=1)
    active = power[power > 10 ** (config.TTS_EDGE_SILENCE_DB / 10)]
    if active.size == 0:
        return path

    level_db = 10 * np.log10(float(active.mean()))
    gain_db = config.KARTOFFELBOX_REF_RMS_DB - level_db
    peak = float(np.abs(audio).max())
    gain_db = min(gain_db, 20 * np.log10(0.99 / peak))
    logger.info("reference clip %s: speech at %.1f dB RMS, %+.1f dB to reach %.1f dB",
                os.path.basename(path), level_db, gain_db, config.KARTOFFELBOX_REF_RMS_DB)
    leveled = os.path.join(out_dir, os.path.splitext(os.path.basename(path))[0] + ".wav")
    sf.write(leveled, audio * np.float32(10 ** (gain_db / 20)), rate, subtype="FLOAT")
    return leveled


def even_out_reference(audio, rate: int):
    """`audio` with its loud bursts compressed by a smooth gain envelope.

    Levels are measured in 20 ms windows; whatever lies more than `KARTOFFELBOX_REF_EVEN_DB` above
    the median level of the speech windows is reduced by `KARTOFFELBOX_REF_EVEN_RATIO`. The
    reduction is smoothed with a 10 ms attack and 150 ms release, run in both directions so the
    envelope is never late for an onset, and interpolated per sample — a gain that moves over tens
    of milliseconds, so the waveform is scaled, never reshaped. Speech under the threshold, which
    is nearly all of it, passes at unity gain.
    """
    audio = np.asarray(audio, dtype=np.float32)
    if config.KARTOFFELBOX_REF_EVEN_RATIO <= 1.0:
        return audio
    window = max(1, rate // 50)
    usable = audio.size - audio.size % window
    if usable < window:
        return audio
    level = 10 * np.log10((audio[:usable].reshape(-1, window) ** 2).mean(axis=1) + 1e-12)
    speech = level[level > config.TTS_EDGE_SILENCE_DB]
    if speech.size == 0:
        return audio
    threshold = float(np.median(speech)) + config.KARTOFFELBOX_REF_EVEN_DB
    reduction = np.maximum(level - threshold, 0.0) * (1 - 1 / config.KARTOFFELBOX_REF_EVEN_RATIO)
    if not reduction.any():
        return audio

    step_ms = 1000 * window / rate
    attack, release = np.exp(-step_ms / 10.0), np.exp(-step_ms / 150.0)

    def follow(x):
        out, state = np.empty_like(x), 0.0
        for i, target in enumerate(x):
            coeff = attack if target > state else release
            state = coeff * state + (1 - coeff) * target
            out[i] = state
        return out

    envelope = np.maximum(follow(reduction), follow(reduction[::-1])[::-1])
    centres = (np.arange(envelope.size) + 0.5) * window
    gain_db = np.interp(np.arange(audio.size), centres, envelope)
    return (audio * 10 ** (-gain_db / 20)).astype(np.float32)
