"""
The engine layer: the registry, the SNAC window, the vLLM token stream, Kartoffelbox, Kokoro.

Nothing here imports torch, transformers, snac, chatterbox or kokoro_onnx. Where an engine's own
plumbing is under test (the reference clip it hands to the model, the vocoder clamp, the audio
stages at the generation seam) the model is a fake and `torch` is a stub in `sys.modules` — the
suite deliberately has neither.
"""

import json as jsonlib
import sys
import types

import numpy as np
import pytest
import soundfile as sf
from fastapi import HTTPException

from tts import config, engines
from tts.engines import kartoffelbox as kb
from tts.engines.base import Engine
from tts.engines.orpheus import FRAME, OrpheusEngine


# --------------------------------------------------------------------------- #
# The registry
# --------------------------------------------------------------------------- #
def test_each_language_resolves_to_its_bound_engine():
    assert engines.for_language("de").name == "orpheus"
    assert engines.for_language("en").name == "kokoro"


def test_a_language_is_matched_by_prefix():
    assert engines.resolve_language("de-AT") == "de"
    assert engines.resolve_language("EN-US") == "en"
    with pytest.raises(KeyError):
        engines.resolve_language("fr")


def test_the_same_instance_is_reused():
    """One engine per process: a second instance would be a second copy of the weights."""
    assert engines.get("orpheus") is engines.get("orpheus")


def test_switching_the_bound_engine_switches_the_language(monkeypatch):
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "kartoffelbox")
    assert engines.for_language("de").name == "kartoffelbox"
    assert engines.for_language("en").name == "kokoro"


def test_an_unknown_engine_falls_back_loudly(monkeypatch, caplog):
    """A typo must not quietly serve a different voice than the deployment asked for."""
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "kartoffelbix")
    with caplog.at_level("ERROR"):
        assert engines.for_language("de").name == "orpheus"
    assert "kartoffelbix" in caplog.text


def test_the_catalogue_lists_the_bound_engine_only(client, monkeypatch):
    """Never both German engines: only one may ever be resident."""
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "kartoffelbox")
    ids = [m["id"] for m in client.get("/v1/models").json()["models"]]
    assert ids == ["kartoffelbox-de", "kokoro-en"]


def test_an_engine_serving_two_languages_is_listed_once(monkeypatch):
    class Both(Engine):
        name = "both"
        languages = ("de", "en")

        @property
        def repo(self):
            return "fake/both"

    engines.register("both", Both())
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "both")
    monkeypatch.setitem(config.ENGINE_BY_LANG, "en", "both")
    assert [e.name for e in engines.bound()] == ["both"]


# --------------------------------------------------------------------------- #
# The generation contract every engine inherits
# --------------------------------------------------------------------------- #
class _Fixed(Engine):
    """An engine that returns exactly what a test hands it."""

    name = "fixed"
    languages = ("de",)

    def __init__(self, waveform, rate=24000, **kw):
        super().__init__()
        self._waveform, self._rate = waveform, rate
        for key, value in kw.items():
            setattr(self, key, value)

    @property
    def repo(self):
        return "fake/fixed"

    def _generate(self, text, voice):
        return self._waveform, self._rate


def test_an_empty_generation_is_an_error_not_a_silent_success():
    """A 0-byte body would look like success to the TtsNode and be impossible to diagnose."""
    with pytest.raises(RuntimeError, match="too short"):
        _Fixed(np.zeros(0, dtype="float32")).generate("Guten Tag.", "Jakob")


def test_a_two_dimensional_waveform_is_refused():
    """A (1, N) array reaching the WAV writer is written as interleaved stereo."""
    with pytest.raises(RuntimeError, match="not 1-D"):
        _Fixed(np.zeros((1, 24000), dtype="float32")).generate("Guten Tag.", "Jakob")


def test_a_fragment_is_an_error_for_an_engine_that_declares_a_floor():
    engine = _Fixed(np.ones(2400, dtype="float32"), min_seconds=0.5)
    with pytest.raises(RuntimeError, match="too short to be speech"):
        engine.generate("Guten Tag.", "Jakob")


def test_a_short_answer_is_fine_for_an_engine_without_a_floor():
    """"Ja." really is 0.4 s; only an engine that fails by truncation needs the guard."""
    waveform, rate = _Fixed(np.ones(2400, dtype="float32")).generate("Ja.", "Jakob")
    assert len(waveform) == 2400


def test_running_into_the_ceiling_is_warned_about(caplog):
    """The audio will not say so: it is well-formed and simply stops mid-word."""
    engine = _Fixed(np.ones(24000 * 40, dtype="float32"), max_seconds=40.0)
    with caplog.at_level("WARNING"):
        engine.generate("Ein sehr langer Text", "Jakob")
    assert "ceiling" in caplog.text


# --------------------------------------------------------------------------- #
# Orpheus — the sliding SNAC window
# --------------------------------------------------------------------------- #
SAMPLES_PER_FRAME = 4


@pytest.fixture
def orpheus(monkeypatch):
    """An Orpheus engine whose SNAC decoder names its own frames.

    Every code of frame *k* is the number *k*, and the fake returns SAMPLES_PER_FRAME samples of
    *k* for it. So the decoded audio IS the frame sequence, and "every frame exactly once, in
    order" stops being a claim about a waveform and becomes a list comparison — which is the only
    way to check it without a GPU.
    """
    engine = OrpheusEngine()

    def fake_decode(codes):
        usable = (len(codes) // FRAME) * FRAME
        if usable == 0:
            raise RuntimeError(
                f"Orpheus emitted {len(codes)} audio tokens, fewer than one frame")
        frames = [codes[i] for i in range(0, usable, FRAME)]
        return np.repeat(np.array(frames, dtype="float32"), SAMPLES_PER_FRAME), 24000

    monkeypatch.setattr(engine, "_snac_decode", fake_decode)
    engines.register("orpheus", engine)
    return engine


def _frames(n):
    """`n` frames of codes, every code of frame k being k."""
    return [k for k in range(n) for _ in range(FRAME)]


def _expected(n):
    return [float(k) for k in range(n) for _ in range(SAMPLES_PER_FRAME)]


def test_the_window_emits_every_frame_exactly_once(orpheus):
    pieces = list(orpheus._snac_decode_stream(iter(_frames(10))))
    assert [rate for _, rate in pieces] == [24000] * len(pieces)
    assert np.concatenate([p for p, _ in pieces]).tolist() == _expected(10)


@pytest.mark.parametrize("window", [2, 3, 4, 8])
def test_the_window_size_does_not_change_what_comes_out(monkeypatch, orpheus, window):
    monkeypatch.setattr(config, "TTS_STREAM_WINDOW_FRAMES", window)
    pieces = list(orpheus._snac_decode_stream(iter(_frames(12))))
    assert np.concatenate([p for p, _ in pieces]).tolist() == _expected(12)


def test_a_window_of_one_is_raised_to_two(monkeypatch, orpheus):
    """Below two the emission arithmetic has no middle to emit."""
    monkeypatch.setattr(config, "TTS_STREAM_WINDOW_FRAMES", 1)
    pieces = list(orpheus._snac_decode_stream(iter(_frames(6))))
    assert np.concatenate([p for p, _ in pieces]).tolist() == _expected(6)


def test_a_generation_shorter_than_the_window_is_decoded_in_one_go(orpheus):
    pieces = list(orpheus._snac_decode_stream(iter(_frames(2))))
    assert len(pieces) == 1
    assert pieces[0][0].tolist() == _expected(2)


def test_a_partial_trailing_frame_is_dropped_never_padded(orpheus):
    """Padding a partial frame injects codes the model never emitted."""
    codes = _frames(5) + [99, 99, 99]
    pieces = list(orpheus._snac_decode_stream(iter(codes)))
    assert np.concatenate([p for p, _ in pieces]).tolist() == _expected(5)


def test_fewer_than_one_frame_is_an_error_not_empty_audio(orpheus):
    with pytest.raises(RuntimeError):
        list(orpheus._snac_decode_stream(iter([1, 2, 3])))


# --------------------------------------------------------------------------- #
# Orpheus — the vLLM token stream
# --------------------------------------------------------------------------- #
class _FakeSSEResponse:
    def __init__(self, lines, status_code=200, text=""):
        self._lines = lines
        self.status_code = status_code
        self.text = text

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def iter_lines(self):
        return iter(self._lines)

    def read(self):
        return b""


class _FakeHttpxClient:
    def __init__(self, response, seen):
        self._response = response
        self._seen = seen

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def stream(self, method, url, json=None):
        self._seen.append((method, url, json))
        return self._response


def _fake_vllm(monkeypatch, lines, status_code=200, text=""):
    import tts.engines.orpheus as module

    seen = []
    response = _FakeSSEResponse(lines, status_code=status_code, text=text)
    monkeypatch.setattr(module.httpx, "Client", lambda *a, **kw: _FakeHttpxClient(response, seen))
    return seen


def _sse(deltas, finish_reason=None):
    lines = []
    for i, delta in enumerate(deltas):
        choice = {"text": delta}
        if finish_reason and i == len(deltas) - 1:
            choice["finish_reason"] = finish_reason
        lines.append("data: " + jsonlib.dumps({"choices": [choice]}))
    return lines + ["data: [DONE]"]


def test_vllm_streaming_survives_a_token_split_across_two_deltas(monkeypatch, orpheus):
    """The one hazard streaming adds to the vLLM path: a delta can end mid-token.

    Reading `<custom_to` as a token would drop it; reading `ken_11>` as one would invent a code out
    of a fragment. Neither shows up as an error — both show up as noise.
    """
    seen = _fake_vllm(monkeypatch, _sse(
        ["<custom_token_10><custom_to", "ken_11><custom_token_", "12>", "<custom_token_9>"]))
    assert list(orpheus._codes_vllm("Guten Tag.", "Jakob")) == [0, 1, 2]
    # …and custom_token_9 maps to -1, which is dropped rather than decoded.
    assert seen[0][1].endswith("/v1/completions")
    assert seen[0][2]["stream"] is True


def test_vllm_streaming_ignores_an_unparsable_line_instead_of_failing(monkeypatch, orpheus):
    _fake_vllm(monkeypatch, ["data: not json", "", "event: ping"]
               + _sse(["<custom_token_10><custom_token_11>"]))
    assert list(orpheus._codes_vllm("Guten Tag.", "Jakob")) == [0, 1]


def test_a_vllm_error_is_still_a_502(monkeypatch, orpheus):
    _fake_vllm(monkeypatch, [], status_code=500, text="model not found")
    with pytest.raises(HTTPException) as e:
        list(orpheus._codes_vllm("Guten Tag.", "Jakob"))
    assert e.value.status_code == 502
    assert "model not found" in e.value.detail


def test_both_backends_build_the_same_prompt(orpheus):
    """Invariant: a prompt change must be made in both, so they are derived from one function."""
    assert orpheus.prompt_text("Guten Tag.", "Jakob") == "Jakob: Guten Tag."
    payload = orpheus._vllm_payload("Guten Tag.", "Jakob")
    assert payload["prompt"] == "<|begin_of_human|>Jakob: Guten Tag.<|eot_id|><|end_of_human|>"


def test_the_incremental_switch_falls_back_to_whole_generations(monkeypatch, orpheus, tone):
    """Turning the never-GPU-tested path off must not cost the stream."""
    monkeypatch.setattr(config, "TTS_STREAM_INCREMENTAL", False)
    monkeypatch.setattr(orpheus, "_generate", lambda text, voice: tone)
    pieces = list(orpheus.stream("Guten Tag.", "Jakob"))
    assert len(pieces) == 1
    assert len(pieces[0][0]) == len(tone[0])


def test_the_catalogue_does_not_claim_incremental_streaming_it_cannot_do(client, monkeypatch):
    """Kartoffelbox returns a whole waveform per call; reporting `true` would be a lie."""
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "kartoffelbox")
    body = client.get("/v1/models").json()
    german = body["models"][0]
    assert german["incremental"] is False
    assert body["streaming"]["incremental"] is False


# --------------------------------------------------------------------------- #
# Kartoffelbox — voices are reference clips
# --------------------------------------------------------------------------- #
def test_german_voices_are_the_installed_reference_clips(voices_dir):
    assert engines.for_language("de").voices() == ["Jakob", "Sophie"]


def test_no_reference_clips_is_an_empty_list_not_a_crash(kartoffelbox, tmp_path):
    kartoffelbox.setattr(config, "KARTOFFELBOX_VOICES_DIR", str(tmp_path / "nope"))
    engine = engines.for_language("de")
    assert engine.voices() == []
    # No clips is not a broken engine — it still speaks in the checkpoint's own voice.
    assert engine.available is False


def test_the_catalogue_loads_nothing_under_kartoffelbox(client, voices_dir):
    body = client.get("/v1/models").json()["models"][0]
    assert body["loaded"] is False
    assert body["available"] is True
    assert body["sample_rate"] == kb.SAMPLE_RATE
    assert body["backend"] == "chatterbox"


def test_a_reference_clip_resolves_case_insensitively(voices_dir):
    engine = engines.for_language("de")
    assert engine.reference("jakob") == str(voices_dir / "Jakob.wav")
    assert engine.reference("JAKOB") == str(voices_dir / "Jakob.wav")
    assert engine.reference("Sophie") == str(voices_dir / "Sophie.flac")


def test_a_file_that_is_not_a_clip_is_not_a_voice(voices_dir):
    assert "notes" not in [v.lower() for v in engines.for_language("de").voices()]


def test_an_unknown_voice_falls_back_to_the_default_conditioning(voices_dir, caplog):
    with caplog.at_level("WARNING"):
        assert engines.for_language("de").reference("Rumpelstilzchen") is None
    assert "Rumpelstilzchen" in caplog.text


def test_a_voice_cannot_escape_the_voices_directory(voices_dir):
    """A voice is never a path: the name is matched against a listing, so a traversal misses."""
    engine = engines.for_language("de")
    assert engine.reference("../../etc/passwd") is None
    assert engine.reference("/etc/passwd") is None


def test_a_clip_added_later_shows_up_without_a_restart(voices_dir):
    engine = engines.for_language("de")
    assert "Marie" not in engine.voices()
    (voices_dir / "Marie.wav").write_bytes(b"")
    assert "Marie" in engine.voices()


# ---- the built-in voices (voices/kartoffelbox/, shipped with the sidecar) ----------------
BUILTIN = ["Anton", "Jakob", "Julian", "Marie", "Sophie"]


def test_the_five_built_in_voices_ship_with_the_sidecar(monkeypatch, tmp_path):
    """Synthetic clips, so `voice` works on a fresh checkout and no real person is committed."""
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "kartoffelbox")
    monkeypatch.setattr(config, "KARTOFFELBOX_VOICES_DIR", str(tmp_path / "none"))
    assert engines.for_language("de").voices() == BUILTIN


def test_a_mounted_clip_adds_a_voice_and_replaces_one_of_the_same_name(monkeypatch, tmp_path):
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "kartoffelbox")
    monkeypatch.setattr(config, "KARTOFFELBOX_VOICES_DIR", str(tmp_path))
    (tmp_path / "Jakob.wav").write_bytes(b"")
    (tmp_path / "Nina.wav").write_bytes(b"")

    engine = engines.for_language("de")
    assert engine.voices() == sorted(BUILTIN + ["Nina"])
    assert engine.reference("Jakob") == str(tmp_path / "Jakob.wav")


def test_the_built_in_voices_can_be_switched_off(monkeypatch, tmp_path):
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "kartoffelbox")
    monkeypatch.setattr(config, "KARTOFFELBOX_BUILTIN_VOICES_DIR", "")
    monkeypatch.setattr(config, "KARTOFFELBOX_VOICES_DIR", str(tmp_path))
    assert engines.for_language("de").voices() == []


# ---- the generation seam ---------------------------------------------------------------
def _fake_torch(monkeypatch):
    class FakeInferenceMode:
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    monkeypatch.setitem(sys.modules, "torch",
                        types.SimpleNamespace(inference_mode=FakeInferenceMode,
                                              Tensor=type("Tensor", (), {})))


def _fake_model(waveform, rate):
    class FakeTensor:
        def squeeze(self):
            return self

        def float(self):
            return self

        def cpu(self):
            return self

        def numpy(self):
            return waveform

    class FakeModel:
        sr = rate

        def generate(self, text, **kwargs):
            return FakeTensor()

    return FakeModel()


def _padded_tone(lead_ms, tail_ms, peak=0.3, rate=24000):
    t = np.linspace(0, 1.0, rate, endpoint=False)
    speech = (peak * np.sin(2 * np.pi * 440 * t)).astype("float32")

    def pad(ms):
        return np.zeros(int(rate * ms / 1000), dtype="float32")

    return np.concatenate([pad(lead_ms), speech, pad(tail_ms)]), rate


@pytest.fixture
def boxed(monkeypatch):
    """A Kartoffelbox engine with a fake model, for the stages at its generation seam.

    `finish` being correct is worth nothing if the seam forgets to call it, and a fixture that
    replaces the whole generation cannot see that — so this one goes in underneath.
    """
    _fake_torch(monkeypatch)

    def build(waveform, rate=24000):
        engine = kb.KartoffelboxEngine()
        engine._model = _fake_model(waveform, rate)
        monkeypatch.setattr(engine, "_load", lambda: None)
        monkeypatch.setattr(engine, "_prepare", lambda voice: None)
        return engine

    return build


def test_the_seam_trims_the_silence_around_a_generation(boxed):
    padded, rate = _padded_tone(lead_ms=200, tail_ms=200)
    waveform, got_rate = boxed(padded, rate).generate("Guten Tag.", "Jakob")

    assert got_rate == rate
    assert len(waveform) < len(padded), "the seam returned the untrimmed generation"
    keep = int(rate * config.TTS_EDGE_KEEP_MS / 1000)
    assert abs(len(waveform) - (rate + 2 * keep)) <= rate // 100


def test_the_seam_fits_each_generation_under_the_ceiling(boxed):
    """This engine streams whole generations, so the seam is where its stream gets headroom."""
    hot, rate = _padded_tone(0, 0, peak=1.2)
    waveform, _ = boxed(hot, rate).generate("Guten Tag.", "Jakob")
    assert float(np.abs(waveform).max()) <= 10 ** (config.TTS_PEAK_CEILING_DB / 20) + 1e-6


def test_the_seam_disperses_the_vocoder_tick(boxed, monkeypatch):
    """A click per pitch period must come out spread, with its energy intact."""
    rate = 24000
    clicky = np.zeros(2 * rate, dtype="float32")
    clicky[::200] = 0.5                      # 120 Hz tick train
    waveform, _ = boxed(clicky, rate).generate("Guten Tag.", "Jakob")
    assert float(np.abs(waveform).max()) < 0.5


def test_the_seam_can_be_left_alone_by_switching_dispersion_off(boxed, monkeypatch):
    monkeypatch.setattr(config, "KARTOFFELBOX_DISPERSION_MS", 0.0)
    rate = 24000
    clicky = np.zeros(2 * rate, dtype="float32")
    clicky[::200] = 0.5
    waveform, _ = boxed(clicky, rate).generate("Guten Tag.", "Jakob")
    assert float(np.abs(waveform).max()) == pytest.approx(0.5, abs=1e-6)


def test_kartoffelbox_clones_the_leveled_clip(monkeypatch, tmp_path, no_builtin_voices):
    """The wiring: `_prepare` must hand the engine the leveled copy, not the mounted clip."""
    rate = 24000
    t = np.linspace(0, 1.0, rate, endpoint=False)
    loud = (0.9 * np.sin(2 * np.pi * 440 * t)).astype("float32")
    sf.write(tmp_path / "Jakob.wav", loud, rate)
    monkeypatch.setattr(config, "KARTOFFELBOX_VOICES_DIR", str(tmp_path))
    seen = []

    class FakeModel:
        def prepare_conditionals(self, path, exaggeration):
            audio, r = sf.read(path, dtype="float32")
            window = r // 100
            usable = audio.size - audio.size % window
            power = (audio[:usable].reshape(-1, window) ** 2).mean(axis=1)
            active = power[power > 10 ** (config.TTS_EDGE_SILENCE_DB / 10)]
            seen.append(10 * np.log10(active.mean()))

    engine = kb.KartoffelboxEngine()
    engine._model = FakeModel()
    engine._prepare("Jakob")

    assert seen == [pytest.approx(config.KARTOFFELBOX_REF_RMS_DB, abs=0.2)]


def test_the_reference_clip_is_encoded_once_per_run_of_chunks(monkeypatch, tmp_path,
                                                              no_builtin_voices):
    """Re-encoding per chunk is wasted work and risks the timbre drifting mid-recording."""
    rate = 24000
    t = np.linspace(0, 1.0, rate, endpoint=False)
    sf.write(tmp_path / "Jakob.wav", (0.3 * np.sin(2 * np.pi * 440 * t)).astype("float32"), rate)
    monkeypatch.setattr(config, "KARTOFFELBOX_VOICES_DIR", str(tmp_path))
    calls = []

    class FakeModel:
        def prepare_conditionals(self, path, exaggeration):
            calls.append(path)

    engine = kb.KartoffelboxEngine()
    engine._model = FakeModel()
    engine._prepare("Jakob")
    engine._prepare("Jakob")
    assert len(calls) == 1


def test_the_vocoder_hard_clamp_is_lifted():
    """S3Gen clamps at ±0.99 inside the engine; the linear peak gain must be the only ceiling."""
    vocoder = types.SimpleNamespace(audio_limit=0.99)
    model = types.SimpleNamespace(s3gen=types.SimpleNamespace(mel2wav=vocoder))
    kb.KartoffelboxEngine._lift_vocoder_clamp(model)
    assert vocoder.audio_limit == config.KARTOFFELBOX_VOCODER_LIMIT


def test_a_different_chatterbox_layout_is_left_alone():
    """An absent attribute leaves the clamp in place, which is only the old behaviour."""
    kb.KartoffelboxEngine._lift_vocoder_clamp(types.SimpleNamespace())


def test_the_kartoffelbox_chunk_budget_is_its_own(monkeypatch):
    """500 characters is derived from the Orpheus token ceiling, which does not exist here."""
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "kartoffelbox")
    engine = engines.for_language("de")
    assert engine.chunk_chars == config.KARTOFFELBOX_CHUNK_CHARS
    assert engine.min_chunk_chars == config.KARTOFFELBOX_MIN_CHUNK_CHARS
    assert engine.max_seconds == pytest.approx(40.0)


def test_the_stream_chunk_budget_never_exceeds_the_engine_budget(monkeypatch):
    """A stream wants short chunks; an engine that degrades on short inputs sets the floor."""
    from tts.synth import stream_limit

    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "kartoffelbox")
    engine = engines.for_language("de")
    monkeypatch.setattr(config, "TTS_STREAM_CHUNK_CHARS", 10_000)
    assert stream_limit(engine) == config.KARTOFFELBOX_CHUNK_CHARS
    monkeypatch.setattr(config, "TTS_STREAM_CHUNK_CHARS", 200)
    assert stream_limit(engine) == 200


# --------------------------------------------------------------------------- #
# Kokoro
# --------------------------------------------------------------------------- #
def test_missing_kokoro_weights_are_a_503(monkeypatch):
    monkeypatch.setattr(config, "KOKORO_MODEL", "/nonexistent/kokoro.onnx")
    monkeypatch.setattr(config, "KOKORO_VOICES", "/nonexistent/voices.bin")
    engine = engines.for_language("en")
    assert engine.available is False
    with pytest.raises(HTTPException) as e:
        engine.generate("Hello.", "af_heart")
    assert e.value.status_code == 503


def test_kokoro_is_not_chunked_and_says_so():
    """No token ceiling: `chunk_chars = 0` is how an engine declares that."""
    engine = engines.for_language("en")
    assert engine.chunk_chars == 0
    assert engine.max_seconds == 0.0
    assert engine.device == "cpu"
