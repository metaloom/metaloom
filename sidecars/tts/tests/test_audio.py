"""
The audio chain: edge trimming, peak headroom, phase dispersion, reference leveling.

Each of these fixes a defect that **no other measure sees**. The byte count, the duration and the
word count all look right with and without the edge trim; the magnitude spectrum is normal with
and without the vocoder tick; a peak of 1.15 shows up only as crackle on the loud syllables. So
these get tests of their own, and the tests measure rather than smoke-check.
"""

import io

import numpy as np
import pytest
import soundfile as sf

from tts import config
from tts.audio import (disperse, encode, even_out_reference, fit_peak,
                       level_reference, trim_edges)


def _padded_tone(lead_ms: int, tail_ms: int, rate: int = 24000):
    """One second of tone with `lead_ms`/`tail_ms` of digital silence around it."""
    t = np.linspace(0, 1.0, rate, endpoint=False)
    speech = (0.3 * np.sin(2 * np.pi * 440 * t)).astype("float32")

    def pad(ms):
        return np.zeros(int(rate * ms / 1000), dtype="float32")

    return np.concatenate([pad(lead_ms), speech, pad(tail_ms)]), rate


def _hot_tone(peak: float, rate: int = 24000):
    t = np.linspace(0, 1.0, rate, endpoint=False)
    return (peak * np.sin(2 * np.pi * 440 * t)).astype("float32"), rate


def _active_rms_db(audio, rate):
    window = rate // 100
    usable = audio.size - audio.size % window
    power = (audio[:usable].reshape(-1, window) ** 2).mean(axis=1)
    return 10 * np.log10(power[power > 10 ** (config.TTS_EDGE_SILENCE_DB / 10)].mean())


# --------------------------------------------------------------------------- #
# Edge trimming
# --------------------------------------------------------------------------- #
# The silence removed here is what turned a chunk seam into a 0.5-0.6 s hole where the sentence
# pauses around it are 0.10-0.25 s — once every ~7 s of a streamed recording, which is what
# "choppy" meant in the field report that led to this.
def test_the_silence_around_a_generation_is_trimmed_off():
    waveform, rate = _padded_tone(lead_ms=100, tail_ms=150)
    trimmed = trim_edges(waveform, rate)

    keep = int(rate * config.TTS_EDGE_KEEP_MS / 1000)
    # the second of speech, plus the margin deliberately kept on either side
    assert abs(len(trimmed) - (rate + 2 * keep)) <= rate // 100
    # and it is the speech that survived, not a slice of the padding
    assert float(np.abs(trimmed).max()) == pytest.approx(0.3, abs=0.01)


def test_trimming_keeps_a_margin_so_a_soft_onset_survives():
    """The threshold is a level, and a word that fades in crosses it late."""
    waveform, rate = _padded_tone(lead_ms=500, tail_ms=500)
    trimmed = trim_edges(waveform, rate)

    keep = int(rate * config.TTS_EDGE_KEEP_MS / 1000)
    assert len(trimmed) > rate, "the trim must not start exactly at the first loud sample"
    assert len(trimmed) <= rate + 2 * keep + rate // 100


def test_a_pause_inside_the_speech_is_left_alone():
    """Only the edges. A pause the speaker made is part of the recording."""
    rate = 24000
    t = np.linspace(0, 1.0, rate, endpoint=False)
    speech = (0.3 * np.sin(2 * np.pi * 440 * t)).astype("float32")
    with_pause = np.concatenate([speech, np.zeros(rate, dtype="float32"), speech])

    assert len(trim_edges(with_pause, rate)) == len(with_pause)


def test_a_silent_waveform_is_returned_untouched():
    """Either the caller asked for silence or the generation failed; neither is ours to fix."""
    silence = np.zeros(24000, dtype="float32")
    assert len(trim_edges(silence, 24000)) == len(silence)


# --------------------------------------------------------------------------- #
# Peak headroom
# --------------------------------------------------------------------------- #
def test_audio_under_the_ceiling_passes_bit_for_bit():
    waveform, _ = _hot_tone(0.5)
    assert np.array_equal(fit_peak(waveform), waveform)


def test_a_hot_waveform_is_brought_to_the_ceiling():
    waveform, _ = _hot_tone(1.5)
    fitted = fit_peak(waveform)
    assert float(np.abs(fitted).max()) == pytest.approx(
        10 ** (config.TTS_PEAK_CEILING_DB / 20), rel=1e-5)


def test_headroom_is_a_gain_and_never_bends_the_wave():
    """The regression this replaced: a sample-wise curve is an overdrive, not a level."""
    waveform, _ = _hot_tone(1.5)
    fitted = fit_peak(waveform)
    loud = np.abs(waveform) > 1e-3
    ratios = fitted[loud] / waveform[loud]
    assert float(ratios.max() - ratios.min()) < 1e-5


def test_the_file_path_fits_the_peak():
    waveform, rate = _hot_tone(1.2)
    audio, _ = encode(waveform, rate, "wav")
    back, _ = sf.read(io.BytesIO(audio), dtype="int16")
    ceiling = round(32768 * 10 ** (config.TTS_PEAK_CEILING_DB / 20))
    assert int(np.abs(back.astype(np.int32)).max()) <= ceiling


# --------------------------------------------------------------------------- #
# Phase dispersion
# --------------------------------------------------------------------------- #
# The HiFT vocoder puts a sharp tick into every pitch period. A tick per period at pitch rate is
# heard as buzz, "pops", an overdriven voice — and no spectrum-based measure sees it, because the
# magnitude spectrum is normal; only the phase is.
def test_dispersion_is_an_all_pass():
    """|H| = 1 by construction: the spectrum must survive to a fraction of a dB."""
    rate = 24000
    noise = np.random.default_rng(7).normal(0, 0.1, rate).astype("float32")
    spread = disperse(noise, rate)

    before = np.abs(np.fft.rfft(noise))
    after = np.abs(np.fft.rfft(spread))
    band = slice(20, len(before) - 20)
    ratio = 20 * np.log10((after[band] + 1e-9) / (before[band] + 1e-9))
    assert abs(float(np.mean(ratio))) < 0.2
    assert float(np.std(ratio)) < 1.0


def test_dispersion_spreads_a_click_and_keeps_its_energy():
    rate = 24000
    click = np.zeros(4096, dtype="float32")
    click[2048] = 1.0
    spread = disperse(click, rate)

    assert float(np.abs(spread).max()) < 0.9, "the tick must be lower than it was"
    energy_before = float((click ** 2).sum())
    energy_after = float((spread ** 2).sum())
    assert energy_after == pytest.approx(energy_before, rel=0.1)


def test_dispersion_can_be_switched_off(monkeypatch):
    monkeypatch.setattr(config, "KARTOFFELBOX_DISPERSION_MS", 0.0)
    waveform, rate = _hot_tone(0.5)
    assert np.array_equal(disperse(waveform, rate), waveform)


def test_dispersion_from_a_start_frequency_leaves_the_low_band_alone(monkeypatch):
    """The tick lives in 2-8 kHz; the pitch and formant band below it must come through as it was.

    A 300 Hz tone is below the start frequency, so its phase is bit-identical and it arrives
    where it went in — ignoring the FIR's own symmetric settling at either end.
    """
    monkeypatch.setattr(config, "KARTOFFELBOX_DISPERSION_FROM_HZ", 2000.0)
    rate = 24000
    t = np.arange(2 * rate) / rate
    low = (0.3 * np.sin(2 * np.pi * 300 * t)).astype("float32")

    out = disperse(low, rate)

    assert np.allclose(out[rate // 2: -rate // 2], low[rate // 2: -rate // 2], atol=2e-3)


def test_dispersion_delays_the_high_band_and_only_it(monkeypatch):
    """The other half of the same claim: above the start frequency the energy arrives later.

    Measured as the centre of energy of a short burst, which is what "spread the tick out in
    time" means — a 9 kHz burst must drift later, a 300 Hz burst must not move.
    """
    monkeypatch.setattr(config, "KARTOFFELBOX_DISPERSION_FROM_HZ", 2000.0)
    rate = 24000

    def centroid_ms(freq):
        t = np.arange(rate) / rate
        burst = np.zeros(rate, dtype="float32")
        span = slice(rate // 2, rate // 2 + rate // 500)      # 2 ms
        burst[span] = np.sin(2 * np.pi * freq * t[span]).astype("float32")
        out = disperse(burst, rate)

        def centre(audio):
            energy = audio.astype(np.float64) ** 2
            return float((np.arange(audio.size) * energy).sum() / energy.sum()) * 1000 / rate

        return centre(out) - centre(burst)

    assert centroid_ms(9000) > 1.0, "the high band must arrive later than it went in"
    assert abs(centroid_ms(300)) < 0.2, "below the start frequency nothing may move"


# --------------------------------------------------------------------------- #
# Reference clips for a cloning engine
# --------------------------------------------------------------------------- #
# Chatterbox reproduces a clip's level AND its dynamics one-for-one, so a loud clip is cloned
# clipping and a bursty clip is cloned shouting. Both failures are inaudible in the clip itself.
def test_a_loud_reference_clip_is_leveled_to_the_target(tmp_path):
    loud, rate = _hot_tone(0.8)
    clip = tmp_path / "Jakob.wav"
    sf.write(clip, np.concatenate([loud, np.zeros(rate, "float32"), loud]), rate)
    out = tmp_path / "out"
    out.mkdir()

    leveled = level_reference(str(clip), str(out))

    assert leveled != str(clip), "the mounted clip must not be the one that is cloned"
    audio, got_rate = sf.read(leveled, dtype="float32")
    assert got_rate == rate
    # the pause in the middle does not count towards the level
    assert _active_rms_db(audio, rate) == pytest.approx(config.KARTOFFELBOX_REF_RMS_DB, abs=0.2)


def test_leveling_never_makes_the_reference_clip_itself_clip(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "KARTOFFELBOX_REF_RMS_DB", 0.0)
    quiet, rate = _hot_tone(0.1)
    clip = tmp_path / "Sophie.wav"
    sf.write(clip, quiet, rate)
    out = tmp_path / "out"
    out.mkdir()

    audio, _ = sf.read(level_reference(str(clip), str(out)), dtype="float32")
    assert float(np.abs(audio).max()) <= 0.99 + 1e-4


def test_a_silent_reference_clip_is_left_to_the_engine(tmp_path):
    clip = tmp_path / "Marie.wav"
    sf.write(clip, np.zeros(24000, "float32"), 24000)
    assert level_reference(str(clip), str(tmp_path)) == str(clip)


def test_an_unreadable_reference_clip_is_left_to_the_engine(tmp_path):
    """Rejecting a clip is the engine's job; this stage must not swallow the error."""
    clip = tmp_path / "Anton.wav"
    clip.write_bytes(b"not audio")
    assert level_reference(str(clip), str(tmp_path)) == str(clip)


def test_a_burst_in_the_reference_is_evened_out():
    """One syllable 14 dB above the rest is what made a clone sound "übersteuert"."""
    rate = 24000
    t = np.linspace(0, 1.0, rate, endpoint=False)
    steady = (0.1 * np.sin(2 * np.pi * 200 * t)).astype("float32")
    burst = (0.5 * np.sin(2 * np.pi * 200 * t[:rate // 4])).astype("float32")
    clip = np.concatenate([steady, burst, steady])

    evened = even_out_reference(clip, rate)

    def crest_db(audio):
        window = rate // 50
        usable = audio.size - audio.size % window
        level = 10 * np.log10((audio[:usable].reshape(-1, window) ** 2).mean(axis=1) + 1e-12)
        speech = level[level > config.TTS_EDGE_SILENCE_DB]
        return float(speech.max() - np.median(speech))

    assert crest_db(evened) < crest_db(clip) - 3


def test_steady_speech_passes_at_unity_gain():
    """Nearly all of a clip is under the threshold and must come through untouched."""
    steady, rate = _hot_tone(0.2)
    assert np.allclose(even_out_reference(steady, rate), steady, atol=1e-4)


def test_evening_out_is_a_slow_gain_and_never_reshapes_the_wave(monkeypatch):
    """Per-sample gain must move over tens of milliseconds, not within a period."""
    rate = 24000
    t = np.linspace(0, 1.0, rate, endpoint=False)
    clip = (0.5 * np.sin(2 * np.pi * 200 * t)).astype("float32")
    clip[rate // 2:] *= 0.1

    gain = even_out_reference(clip, rate) / np.where(np.abs(clip) > 1e-4, clip, np.nan)
    gain = gain[np.isfinite(gain)]
    # the envelope changes by well under 1 % from one sample to the next
    assert float(np.abs(np.diff(gain)).max()) < 0.01


def test_evening_out_can_be_switched_off(monkeypatch):
    monkeypatch.setattr(config, "KARTOFFELBOX_REF_EVEN_RATIO", 1.0)
    rate = 24000
    t = np.linspace(0, 1.0, rate, endpoint=False)
    clip = (0.5 * np.sin(2 * np.pi * 200 * t)).astype("float32")
    assert np.array_equal(even_out_reference(clip, rate), clip)
