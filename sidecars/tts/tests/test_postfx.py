"""
The post-production chain, `TTS_POSTFX`.

The listener's verdict on the FIRST version of this chain was "all übersteuert", and it was right:
pedalboard's peak-detecting compressor rippled inside every voice period (measured THD -37 dB in
`podcast`, -30 dB in `broadcast`) and the limiter released in 5 ms. These tests pin the rebuilt
one — they measure distortion, gain wobble and release rate rather than checking that something
happened, because "it ran" was never the question.
"""

import io

import numpy as np
import pytest
import soundfile as sf

from tts import config, postfx
from tts.audio import encode
from tts.postfx import PostFx, RmsCompressor, lookahead_gain, lookahead_limit, postfx_stages


def _speechlike(level: float, seconds: float = 4.0, rate: int = 24000, accented: bool = False):
    """A 150 Hz voice with harmonics, opening and closing at a 4 Hz syllable rate.

    `accented` makes every fourth syllable 8 dB louder: rare, strong peaks over an even body,
    which is how real speech reaches its 18-20 dB peak-to-loudness. That shape defeated the first
    post-production ceiling (a compressor without look-ahead).
    """
    t = np.arange(int(seconds * rate)) / rate
    voice = sum(np.sin(2 * np.pi * 150 * k * t) / k for k in range(1, 12))
    syllables = np.clip(np.sin(2 * np.pi * 4 * t), 0, None)
    if accented:
        syllables = syllables * np.where((np.floor(t * 4) % 4) == 0, 10 ** (8 / 20), 1.0)
    return (level * voice * syllables / np.abs(voice).max()).astype("float32"), rate


def _lufs(audio, rate):
    import pyloudnorm

    return pyloudnorm.Meter(rate).integrated_loudness(np.asarray(audio, dtype=np.float64))


def _thd_db(y, f0, rate):
    y = y[rate:3 * rate]
    spec = np.abs(np.fft.rfft(y * np.hanning(y.size)))
    f = np.fft.rfftfreq(y.size, 1 / rate)

    def band(fc):
        return (spec[(f > fc - 3) & (f < fc + 3)] ** 2).sum()

    harmonics = sum(band(k * f0) for k in range(2, 40) if k * f0 < rate / 2 - 100)
    return 10 * np.log10(harmonics / band(f0))


# --------------------------------------------------------------------------- #
# off, and the wiring
# --------------------------------------------------------------------------- #
def test_postfx_is_off_by_default_and_then_touches_nothing(monkeypatch):
    assert config.TTS_POSTFX == "off"

    def boom(*a, **kw):
        raise AssertionError("the post-production chain ran although TTS_POSTFX=off")

    monkeypatch.setattr("tts.audio.PostFx", boom)
    waveform, rate = _speechlike(0.3)
    audio, _ = encode(waveform, rate, "wav")
    back, _ = sf.read(io.BytesIO(audio), dtype="float32")
    assert np.allclose(back, waveform, atol=1 / 32767)


def test_an_unknown_mode_is_refused_by_the_chain():
    assert config.TTS_POSTFX in config.POSTFX_MODES
    with pytest.raises(ValueError):
        postfx_stages("radio")


def test_the_catalogue_reports_the_mode(client, monkeypatch):
    monkeypatch.setattr(config, "TTS_POSTFX", "broadcast")
    body = client.get("/v1/models").json()
    assert body["postfx"]["mode"] == "broadcast"
    assert body["postfx"]["modes"] == list(config.POSTFX_MODES)
    assert body["postfx"]["target_lufs"] == config.TTS_POSTFX_LUFS
    assert client.get("/health").json()["postfx"] == "broadcast"


# --------------------------------------------------------------------------- #
# the loudness target
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("mode", ["level", "clean", "podcast", "broadcast"])
@pytest.mark.parametrize("level", [0.02, 0.6])
@pytest.mark.parametrize("accented", [False, True])
def test_every_mode_lands_on_the_loudness_target_under_the_ceiling(monkeypatch, mode, level,
                                                                   accented):
    """Quiet and loud voices, even and accented alike — that consistency is the point."""
    monkeypatch.setattr(config, "TTS_POSTFX", mode)
    waveform, rate = _speechlike(level, accented=accented)
    audio, _ = encode(waveform, rate, "wav")
    back, _ = sf.read(io.BytesIO(audio), dtype="float32")
    assert _lufs(back, rate) == pytest.approx(config.TTS_POSTFX_LUFS, abs=1.0)
    assert float(np.abs(back).max()) <= 10 ** (config.TTS_PEAK_CEILING_DB / 20) + 1e-4


def test_clean_takes_the_rumble_out():
    rate = 24000
    t = np.arange(3 * rate) / rate
    mix = (0.2 * np.sin(2 * np.pi * 40 * t) + 0.2 * np.sin(2 * np.pi * 1000 * t)).astype("float32")
    out = PostFx(rate, mode="clean").process(mix)

    def ratio_db(x):
        spec = np.abs(np.fft.rfft(x[rate:]))
        f = np.fft.rfftfreq(x[rate:].size, 1 / rate)
        return 20 * np.log10(spec[np.argmin(abs(f - 40))] / spec[np.argmin(abs(f - 1000))])

    assert ratio_db(mix) - ratio_db(out) > 6.0


def test_a_stream_is_processed_like_the_file():
    """Filter/compressor state carries across pieces; only the held loudness gains may differ."""
    waveform, rate = _speechlike(0.1, seconds=6.0)
    whole = PostFx(rate, mode="podcast").process(waveform)
    streamed = PostFx(rate, mode="podcast")
    pieces = [streamed.process(waveform[i:i + 2 * rate])
              for i in range(0, waveform.size, 2 * rate)]
    joined = np.concatenate(pieces)
    assert joined.size == whole.size
    assert _lufs(joined, rate) == pytest.approx(_lufs(whole, rate), abs=0.5)


def test_the_stream_route_applies_postfx(client, monkeypatch, engine_factory):
    from tts import engines

    monkeypatch.setattr(config, "TTS_POSTFX", "podcast")
    engines.register("orpheus", engine_factory("orpheus", _speechlike(0.02), languages=("de",)))
    r = client.post("/v1/tts/stream", json={"text": "Guten Tag.", "format": "wav"})
    back, rate = sf.read(io.BytesIO(r.content), dtype="float32")
    assert _lufs(back, rate) == pytest.approx(config.TTS_POSTFX_LUFS, abs=1.0)


# --------------------------------------------------------------------------- #
# no stage may be an overdrive
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("mode", ["level", "clean", "podcast", "broadcast"])
@pytest.mark.parametrize("f0", [100, 120, 220])
def test_no_mode_adds_audible_distortion(mode, f0):
    """A pure tone at voice pitch, compressed: any harmonic that comes out was made by the chain."""
    rate = 24000
    t = np.arange(4 * rate) / rate
    tone = (0.3 * np.sin(2 * np.pi * f0 * t)).astype("float32")
    assert _thd_db(PostFx(rate, mode=mode).process(tone), f0, rate) < -60.0


def test_the_compressor_gain_does_not_wobble_at_voice_rates():
    waveform, rate = _speechlike(0.3, seconds=6.0, accented=True)
    gain = RmsCompressor(rate, -20.0, 4.0, 8.0, 50.0).gain_db(waveform)
    assert float(gain.min()) < -3.0, "the test signal must actually be compressed"
    spec = np.fft.rfft(gain - gain.mean())
    spec[np.fft.rfftfreq(gain.size, 1 / rate) < 30] = 0
    assert float(np.sqrt(np.mean(np.fft.irfft(spec, gain.size) ** 2))) < 0.2


def test_the_compressor_streams_exactly_like_the_file():
    waveform, rate = _speechlike(0.3, seconds=6.0, accented=True)
    whole = RmsCompressor(rate, -20.0, 3.0, 10.0, 40.0).process(waveform)
    streamed = RmsCompressor(rate, -20.0, 3.0, 10.0, 40.0)
    pieces = [streamed.process(waveform[i:i + 7001]) for i in range(0, waveform.size, 7001)]
    assert np.allclose(np.concatenate(pieces), whole, atol=1e-6)


def test_the_lookahead_limiter_holds_the_ceiling_exactly():
    waveform, rate = _speechlike(1.5, accented=True)
    limited = lookahead_limit(waveform, rate)
    assert float(np.abs(limited).max()) <= 10 ** (config.TTS_POSTFX_CEILING_DB / 20) + 1e-6


def test_the_lookahead_limiter_is_a_smooth_gain():
    """Ramps of at least the look-ahead, never a per-sample curve."""
    waveform, rate = _speechlike(1.5, accented=True)
    gain = lookahead_gain(waveform, rate)
    assert float(gain.min()) < 0.7, "the test signal must actually be limited"
    ramp = int(rate * 0.005)
    # a full swing takes at least the look-ahead: no step larger than 1/ramp of the range
    assert float(np.abs(np.diff(gain)).max()) <= (1 - float(gain.min())) / ramp * 1.01


def test_the_limiter_releases_slowly():
    """5 ms releases made the gain flutter on loud syllables; it must recover at its rate."""
    waveform, rate = _speechlike(1.5, accented=True)
    gain_db = 20 * np.log10(lookahead_gain(waveform, rate))
    assert float(gain_db.min()) < -3.0
    assert float(np.diff(gain_db).max()) <= 60.0 / rate * 1.05


def test_the_working_level_is_fixed_so_the_thresholds_can_be_constants():
    """A voice arrives anywhere between -16 and -25 LUFS; the compressor must see one level."""
    rate = 24000
    quiet, _ = _speechlike(0.02, rate=rate)
    loud, _ = _speechlike(0.6, rate=rate)
    gains = [PostFx(rate, mode="podcast")._gain_to(a, postfx.POSTFX_WORKING_LUFS)
             for a in (quiet, loud)]
    assert _lufs(quiet * gains[0], rate) == pytest.approx(postfx.POSTFX_WORKING_LUFS, abs=0.1)
    assert _lufs(loud * gains[1], rate) == pytest.approx(postfx.POSTFX_WORKING_LUFS, abs=0.1)
