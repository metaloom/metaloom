"""
Post-production — the optional "radio/podcast" chain, one instance per response.

    working loudness -> EQ -> RMS compressor -> output loudness -> look-ahead limiter

`TTS_POSTFX=off` (the default) skips all of it and the output is exactly what the engine made.

⚠️ Read this before changing a stage. The first version of this chain was audibly **overdriven**
and had to be rebuilt. Two causes, both of the same kind — a gain that moves faster than one
period of a voice is not a gain any more, it is a waveshaper:

* pedalboard's `Compressor` detects PEAKS with a 3-5 ms attack, faster than one period of a
  120 Hz voice (8 ms), so its gain rippled *inside* every period. Measured THD on a 120 Hz sine:
  -37 dB in `podcast`, -30 dB in `broadcast`. This chain measures -69 dB or better in every mode.
* pedalboard's `Limiter` is JUCE's loudness maximiser, with automatic make-up gain and a hard
  clip at 0 dBFS. `lookahead_limit` below replaces it with a slow, smooth gain.

So: every stage here is either a linear filter or a gain computed as a mean/hold over tens of
milliseconds. Never a per-sample curve. Verified by `tests/test_postfx.py`, which measures THD
per mode and the gain's own wobble.
"""

import logging

import numpy as np

from . import config

logger = logging.getLogger("tts.postfx")

#: The loudness the EQ and the compressor see. Fixing it lets the compressor thresholds below be
#: constants: voices arrive anywhere between -16 and -25 LUFS (the Kartoffelbox default voice vs.
#: a leveled clone), and a threshold that suits one would be idle or brutal on the other.
POSTFX_WORKING_LUFS = -20.0

#: mode -> (EQ stages, compressor). EQ: pedalboard's JUCE biquads, linear filters (measured
#: THD < -110 dB). Compressor: `RmsCompressor` as (threshold dBFS RMS, ratio, attack ms,
#: release dB/s). Measured on 7 real voices (2026-10-09): at most 5.8 / 5.4 / 7.2 / 9.9 dB of
#: gain reduction on the loudest syllables, with gain wobble above 30 Hz under 0.1 dB.
POSTFX_CHAINS = {
    "level": ("", (-20.0, 2.0, 10.0, 40.0)),
    "clean": ("clean", (-20.0, 2.0, 10.0, 40.0)),
    "podcast": ("podcast", (-20.0, 3.0, 10.0, 40.0)),
    "broadcast": ("broadcast", (-23.0, 3.5, 8.0, 50.0)),
}


def postfx_stages(mode: str) -> list:
    """The EQ stages of `mode`, in order (pedalboard filters, all linear)."""
    if mode not in POSTFX_CHAINS:
        raise ValueError(f"unknown post-production mode {mode!r}")
    from pedalboard import HighpassFilter, HighShelfFilter, LowShelfFilter, PeakFilter

    eq = POSTFX_CHAINS[mode][0]
    if not eq:
        return []
    stages = [
        HighpassFilter(cutoff_frequency_hz=80),                      # rumble, DC
        PeakFilter(cutoff_frequency_hz=250, gain_db=-2.0, q=1.0),    # boxiness
        HighShelfFilter(cutoff_frequency_hz=7000, gain_db=-3.0),     # vocoder fizz, sibilance
    ]
    if eq == "podcast":
        stages.append(PeakFilter(cutoff_frequency_hz=3500, gain_db=2.0, q=1.0))   # presence
    if eq == "broadcast":
        stages += [LowShelfFilter(cutoff_frequency_hz=150, gain_db=2.0),          # body
                   PeakFilter(cutoff_frequency_hz=3500, gain_db=3.0, q=1.0)]
    return stages


def moving_mean(history, values, n: int):
    """Causal mean over the last `n` samples of `history ++ values`, one per value.

    Returns `(means, new_history)`; the history carries the window across stream pieces.
    """
    joined = np.concatenate([history, values])
    csum = np.concatenate([[0.0], np.cumsum(joined)])
    means = (csum[n:] - csum[:-n]) / n
    return means[-values.size:], joined[-(n - 1):] if n > 1 else joined[:0]


def release(reduction_db, step_db: float, state: float):
    """`reduction_db` held at its peaks and released at `step_db` per sample, never faster.

    `out[n] = max_k<=n (reduction[k] - (n - k) * step)` — a peak hold with a linear-in-dB
    release, computed exactly with one `maximum.accumulate`. `state` is the previous piece's last
    output, so a stream releases across its seams like a file. Returns `(out, state)`.
    """
    ramp = np.arange(reduction_db.size) * step_db
    # the previous sample sits at index -1 of this piece's ramp: its value, released one step
    held = np.maximum.accumulate(np.concatenate([[state - step_db], reduction_db + ramp]))[1:]
    out = held - ramp
    return out, float(out[-1])


class RmsCompressor:
    """A feed-forward compressor whose gain cannot ripple at the pitch of a voice.

    The level is the RMS over `window_ms` (30 ms: three periods of a 100 Hz voice), so the
    detector does not see single cycles. Gain reduction above threshold is `(level - threshold) *
    (1 - 1/ratio)`, held and released at `release_db_s`, then smoothed over `attack_ms`. Every
    stage is a mean or a hold over many milliseconds, so the result is a gain that moves slowly
    and never a curve applied to the wave. State (window history, release, attack history) carries
    across `process` calls, so a stream is compressed exactly like the file.
    """

    def __init__(self, rate: int, threshold_db: float, ratio: float, attack_ms: float,
                 release_db_s: float, window_ms: float = 30.0):
        self.threshold_db = threshold_db
        self.slope = 1.0 - 1.0 / ratio
        self.window = max(1, int(rate * window_ms / 1000))
        self.attack = max(1, int(rate * attack_ms / 1000))
        self.step = release_db_s / rate
        self._power = np.zeros(self.window - 1)
        self._reduction = np.zeros(self.attack - 1)
        self._held = 0.0

    def gain_db(self, audio):
        """The gain in dB (<= 0) this compressor applies to `audio`, advancing its state."""
        x = np.asarray(audio, dtype=np.float64)
        power, self._power = moving_mean(self._power, x * x, self.window)
        level = 10 * np.log10(power + 1e-12)
        reduction = np.maximum(level - self.threshold_db, 0.0) * self.slope
        held, self._held = release(reduction, self.step, self._held)
        smoothed, self._reduction = moving_mean(self._reduction, held, self.attack)
        return -smoothed

    def process(self, audio):
        audio = np.asarray(audio, dtype=np.float32)
        if audio.size == 0:
            return audio
        return (audio * 10 ** (self.gain_db(audio) / 20)).astype(np.float32)


class PostFx:
    """The post-production chain of ONE response, stateful across the pieces of a stream.

    `audio.encode` hands it the whole recording in one call; `synth.stream_bytes` hands it piece
    by piece. EQ and compressor carry their state between calls, so a stream is processed
    sample-for-sample like the file. The two loudness gains are the one difference: they are
    measured on the first piece long enough to measure (>= 0.5 s) and then held, because a stream
    cannot know its future and a gain re-measured per piece would pump.

    That is also this chain's one weak spot on a stream of very short pieces. Orpheus's incremental
    pieces are one SNAC window each (~85 ms), so neither gain is ever measured and the delivery
    loudness is not reached — the filters and the limiter still run. Kartoffelbox streams whole
    chunks, so its first piece is long enough. `TTS_STREAM_INCREMENTAL=0` is the way to have both
    for now; see spec/sidecars/TTS_SIDECAR.md section 5.1.
    """

    def __init__(self, rate: int, mode: str = None):
        import pyloudnorm
        from pedalboard import Pedalboard

        mode = config.TTS_POSTFX if mode is None else mode
        self.rate = rate
        self.mode = mode
        self._meter = pyloudnorm.Meter(rate)
        self._board = Pedalboard(postfx_stages(mode))
        self._compressor = RmsCompressor(rate, *POSTFX_CHAINS[mode][1])
        self._working_gain = None
        self._output_gain = None

    def _gain_to(self, audio, target_lufs: float):
        """The gain that brings `audio` to `target_lufs`, or None if it cannot be measured."""
        if audio.size < self.rate // 2:
            return None
        try:
            lufs = self._meter.integrated_loudness(audio.astype(np.float64))
        except ValueError:
            return None
        if not np.isfinite(lufs):
            return None
        return np.float32(10 ** ((target_lufs - lufs) / 20))

    def process(self, waveform):
        audio = np.asarray(waveform, dtype=np.float32).reshape(-1)
        if audio.size == 0:
            return audio
        if self._working_gain is None:
            self._working_gain = self._gain_to(audio, POSTFX_WORKING_LUFS)
        if self._working_gain is not None:
            audio = audio * self._working_gain
        if len(self._board):
            audio = np.asarray(self._board(audio, self.rate, reset=False),
                               dtype=np.float32).reshape(-1)
        audio = self._compressor.process(audio)
        if self._output_gain is None:
            self._output_gain = self._output_gain_for(audio)
        if self._output_gain is not None:
            audio = audio * self._output_gain
        return lookahead_limit(audio, self.rate)

    def _output_gain_for(self, audio):
        """The gain that puts `audio` at TTS_POSTFX_LUFS AFTER the limiter, or None.

        Limiting costs a little loudness, so a gain measured before the limiter can land short.
        Up to three passes of measure -> limit -> re-measure, the correction capped at +3 dB: the
        compressor has already done the levelling, and the limiter must stay a safety net.
        """
        gain = self._gain_to(audio, config.TTS_POSTFX_LUFS)
        if gain is None:
            return None
        first = gain
        for _ in range(3):
            correction = self._gain_to(lookahead_limit(audio * gain, self.rate),
                                       config.TTS_POSTFX_LUFS)
            if correction is None or abs(20 * np.log10(correction)) < 0.1:
                break
            gain = np.float32(min(gain * correction, first * 10 ** (3 / 20)))
        return gain


def lookahead_limit(audio, rate: int):
    """`audio` with every peak above `TTS_POSTFX_CEILING_DB` taken down by a slow, smooth gain.

    The safety net at the end of the chain, behind the compressor. The first version of this
    released in 5 ms, which made the gain flutter on loud syllables: another overdrive.
    """
    audio = np.asarray(audio, dtype=np.float32)
    if audio.size == 0:
        return audio
    return (audio * lookahead_gain(audio, rate)).astype(np.float32)


def lookahead_gain(audio, rate: int, lookahead_ms: float = 5.0, hold_ms: float = 30.0,
                   release_db_s: float = 60.0):
    """The per-sample gain curve `lookahead_limit` applies (1.0 where nothing is limited).

    In dB of reduction: what each sample needs to sit at the ceiling; a sliding MAXIMUM over
    look-ahead + hold so the reduction is in place before the peak; a peak hold with a
    linear-in-dB release; and a sliding mean over the look-ahead for the attack ramp. Every value
    the mean averages is at least the reduction the centre sample needs (its window contains that
    sample, and the release only ever adds), so the ceiling holds exactly. The curve moves in
    ramps of at least the look-ahead and releases at `release_db_s`.
    """
    from scipy.ndimage import maximum_filter1d, uniform_filter1d

    ceiling = 10 ** (config.TTS_POSTFX_CEILING_DB / 20)
    peak = np.abs(np.asarray(audio, dtype=np.float64))
    if peak.size == 0 or float(peak.max()) <= ceiling:
        return np.ones(peak.size, dtype=np.float32)
    need = np.maximum(20 * np.log10(np.maximum(peak, 1e-12) / ceiling), 0.0)
    ramp = max(1, int(rate * lookahead_ms / 1000))
    hold = max(0, int(rate * hold_ms / 1000))
    plateau = maximum_filter1d(need, size=2 * ramp + 1 + hold, mode="nearest")
    released, _ = release(plateau, release_db_s / rate, 0.0)
    smoothed = uniform_filter1d(released, size=ramp, mode="nearest")
    reduction = np.maximum(smoothed, need)   # rounding in the mean must not cost the guarantee
    return (10 ** (-reduction / 20)).astype(np.float32)
