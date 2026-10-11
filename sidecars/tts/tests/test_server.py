"""The HTTP surface: the request contract, the containers, the catalogue."""

import io

import numpy as np
import pytest
import soundfile as sf

import server
from tts import config, engines
from tts.audio import FORMATS, STREAM_ENCODERS, encode
from tts.engines.orpheus import KARTOFFEL_VOICES, THORSTEN_REPO, THORSTEN_VOICES


# ---------------------------------------------------------------- request contract
def test_blank_text_is_a_400(client):
    assert client.post("/v1/tts", json={"text": "   "}).status_code == 400
    assert client.post("/v1/tts", json={"text": ""}).status_code == 400


def test_unsupported_language_is_a_400(client, spoken):
    r = client.post("/v1/tts", json={"text": "Bonjour", "lang": "fr"})
    assert r.status_code == 400
    assert "fr" in r.json()["detail"]


@pytest.mark.parametrize("lang", ["de", "DE", "de-AT", "en", "EN", "en-US"])
def test_language_prefixes_route(client, spoken, lang):
    """Routing is by case-insensitive prefix, so regional tags must not 400."""
    assert client.post("/v1/tts", json={"text": "Test", "lang": lang}).status_code == 200


def test_the_text_reaches_the_engine_with_its_voice(client, spoken):
    client.post("/v1/tts", json={"text": "Guten Tag.", "voice": "Sophie"})
    assert spoken == [("Guten Tag.", "Sophie")]


# --------------------------------------------------------------------- the format
def test_unknown_format_is_a_400_not_a_silent_wav(client, spoken):
    """`format` must stop lying: it used to be accepted, ignored, and answered with WAV."""
    r = client.post("/v1/tts", json={"text": "Test", "format": "ogg"})
    assert r.status_code == 400
    assert "ogg" in r.json()["detail"]


def test_wav_is_still_the_default(client, spoken):
    """The Java TtsClient reads the body as a waveform — mp3 is opt-in, never implicit."""
    r = client.post("/v1/tts", json={"text": "Test"})
    assert r.status_code == 200
    assert r.headers["content-type"] == "audio/wav"
    assert r.content[:4] == b"RIFF"


def test_mp3_is_produced_and_is_actually_mp3(client, spoken):
    r = client.post("/v1/tts", json={"text": "Test", "format": "mp3"})
    assert r.status_code == 200
    assert r.headers["content-type"] == "audio/mpeg"
    # An MPEG audio frame begins with an 11-bit sync word.
    assert r.content[0] == 0xFF and (r.content[1] & 0xE0) == 0xE0
    # And it round-trips back to the same number of frames.
    back, rate = sf.read(io.BytesIO(r.content))
    assert rate == 24000 and len(back) == 24000


def test_format_is_case_insensitive(client, spoken):
    assert client.post("/v1/tts", json={"text": "T", "format": "MP3"}).status_code == 200


def test_mp3_is_much_smaller_than_wav(client, spoken):
    wav = client.post("/v1/tts", json={"text": "Test"}).content
    mp3 = client.post("/v1/tts", json={"text": "Test", "format": "mp3"}).content
    assert len(mp3) < len(wav) / 5


def test_encode_rejects_an_unknown_container(tone):
    """`encode` is the one place a waveform becomes bytes; it must not guess."""
    waveform, rate = tone
    with pytest.raises(Exception):
        encode(waveform, rate, "flac")


def test_every_container_the_file_supports_can_also_be_streamed():
    assert set(STREAM_ENCODERS) == set(FORMATS)


# ------------------------------------------------------------------- the catalogue
def test_models_lists_one_engine_per_language(client):
    body = client.get("/v1/models").json()
    assert [m["id"] for m in body["models"]] == ["orpheus-de", "kokoro-en"]
    assert body["default_format"] == "wav"
    assert body["languages"] == ["de", "en"]


def test_models_does_not_load_anything(client):
    """Answering "what can you speak with" must never trigger a multi-GB download."""
    body = client.get("/v1/models").json()
    german, english = body["models"]
    # Orpheus reports itself ready under the vLLM backend, where there is nothing local to load.
    assert german["loaded"] is (config.ORPHEUS_BACKEND == "vllm")
    assert english["loaded"] is False


def test_german_voices_follow_the_configured_checkpoint(client, monkeypatch):
    """The old static list advertised six voices even for the single-speaker checkpoint."""
    assert engines.for_language("de").voices() == KARTOFFEL_VOICES
    monkeypatch.setattr(config, "ORPHEUS_REPO_DE", THORSTEN_REPO)
    assert engines.for_language("de").voices() == THORSTEN_VOICES


def test_english_voices_come_from_the_voices_file(monkeypatch, tmp_path):
    path = tmp_path / "voices.bin"
    np.savez(path, bf_alice=np.zeros(1), am_bob=np.zeros(1))
    monkeypatch.setattr(config, "KOKORO_VOICES", str(path) + ".npz")
    assert engines.for_language("en").voices() == ["am_bob", "bf_alice"]


def test_english_voices_fall_back_when_the_file_is_missing(monkeypatch):
    monkeypatch.setattr(config, "KOKORO_VOICES", "/nonexistent/voices.bin")
    assert engines.for_language("en").voices() == ["af_heart", "am_michael", "bf_emma"]


def test_voices_route_projects_the_same_data_as_models(client):
    models = {m["languages"][0]: m for m in client.get("/v1/models").json()["models"]}
    projected = client.get("/voices").json()
    assert set(projected) == set(models)
    for lang, entry in projected.items():
        assert entry["voices"] == models[lang]["voices"]
        assert entry["engine"] == models[lang]["engine"]
        assert entry["repo"] == models[lang]["repo"]


def test_health_stays_liveness_only(client):
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["engines"] == {"de": "orpheus", "en": "kokoro"}
    assert body["formats"] == ["mp3", "wav"]
    assert body["postfx"] == config.TTS_POSTFX
    assert "loaded" not in body


# ------------------------------------------------------------------------- voices
def test_an_unknown_german_voice_is_accepted(client, spoken):
    """German speaker names are a convention of the training data, not a closed set."""
    r = client.post("/v1/tts", json={"text": "Test", "lang": "de", "voice": "Rumpelstilzchen"})
    assert r.status_code == 200


def test_an_unknown_english_voice_is_a_400(client, monkeypatch, tone):
    """English is different: the list comes out of the voices file and is authoritative."""
    engine = engines.for_language("en")
    monkeypatch.setattr(engine, "_load", lambda: None)
    monkeypatch.setattr(engine, "_voices", ["af_heart"])
    r = client.post("/v1/tts", json={"text": "Test", "lang": "en", "voice": "nope"})
    assert r.status_code == 400
    assert "nope" in r.json()["detail"]


# ------------------------------------------------------------------------ the chunk loop
def test_the_route_returns_the_whole_recording(client, chunked, tone):
    """Several generations must arrive as ONE file, gaps included."""
    text = ". ".join(f"Satz nummer {i} mit genug Text fuer einen eigenen Teil" for i in range(4))
    audio, rate = sf.read(io.BytesIO(client.post("/v1/tts", json={"text": text}).content))

    chunks = len(chunked.calls)
    assert chunks > 1
    gap = int(rate * config.TTS_CHUNK_GAP_MS / 1000)
    assert len(audio) == chunks * len(tone[0]) + (chunks - 1) * gap


def test_a_short_request_is_still_exactly_one_generation(client, chunked):
    client.post("/v1/tts", json={"text": "Kurz."})
    assert chunked.calls == [("Kurz.", "Jakob")]


def test_english_is_not_chunked(client, spoken):
    """Kokoro has no token ceiling, so splitting a file it can say in one pass only adds seams."""
    text = "This is a sentence. " * 60
    client.post("/v1/tts", json={"text": text, "lang": "en"})
    assert len(spoken) == 1


# ------------------------------------------------------------------------ streaming
@pytest.fixture
def streamed(spoken):
    return spoken


def test_the_streamed_wav_round_trips(client, streamed, tone):
    r = client.get("/v1/tts/stream", params={"text": "Guten Tag"})
    assert r.status_code == 200
    assert r.headers["content-type"] == "audio/wav"
    audio, rate = sf.read(io.BytesIO(r.content), dtype="float32")
    assert rate == 24000
    assert len(audio) == len(tone[0])
    # PCM-16 of the same waveform, so it is sample-exact to a quantisation step.
    assert np.abs(audio - tone[0]).max() < 2e-4


def test_the_streamed_wav_header_declares_an_unknown_length(client, streamed):
    """A stream has no total length; the two RIFF size fields say so."""
    content = client.get("/v1/tts/stream", params={"text": "Hallo"}).content
    assert content[4:8] == b"\xff\xff\xff\xff"
    assert content[40:44] == b"\xff\xff\xff\xff"


def test_the_streamed_mp3_is_mpeg_frames(client, streamed):
    content = client.get("/v1/tts/stream", params={"text": "Hallo", "format": "mp3"}).content
    assert content[0] == 0xFF and (content[1] & 0xE0) == 0xE0
    assert len(content) < 24000 * 2 / 5


def test_the_body_leaves_in_pieces_rather_than_in_one_block(tone, engine_factory, monkeypatch):
    """The point of the endpoint: bytes exist before the synthesis has finished.

    Asserted on the generator rather than through the client, which buffers the whole body.
    """
    fake = engine_factory("orpheus", tone, languages=("de",), chunk_chars=50, pieces=4)
    engines.register("orpheus", fake)
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "orpheus")
    text = ". ".join(f"Satz nummer {i} mit genug Text fuer einen Teil" for i in range(4))

    chunks = list(server.synth.stream_bytes(fake, text, "Jakob", "wav"))
    assert len(chunks) > 3
    assert all(chunks)


@pytest.mark.parametrize("body", [
    {"text": "   "},
    {"text": "Bonjour", "lang": "fr"},
    {"text": "Test", "format": "ogg"},
])
def test_the_stream_refuses_exactly_what_the_file_refuses(client, spoken, body):
    assert client.post("/v1/tts", json=body).status_code == 400
    assert client.post("/v1/tts/stream", json=body).status_code == 400


def test_the_get_route_is_the_same_stream(client, streamed):
    post = client.post("/v1/tts/stream", json={"text": "Hallo"}).content
    get = client.get("/v1/tts/stream", params={"text": "Hallo"}).content
    assert post == get


def test_the_response_tells_a_proxy_not_to_buffer_it(client, streamed):
    headers = client.get("/v1/tts/stream", params={"text": "Hallo"}).headers
    assert headers["x-accel-buffering"] == "no"
    assert headers["cache-control"] == "no-store"


def test_a_failure_before_the_first_byte_is_a_status_code_not_an_empty_200(client, monkeypatch):
    """A stream that ends politely is indistinguishable from a finished recording."""
    def boom(engine, text, voice):
        raise RuntimeError("the GPU said no")

    monkeypatch.setattr(server.synth, "stream_pieces", boom)
    r = client.get("/v1/tts/stream", params={"text": "Hallo"})
    assert r.status_code == 500
    assert "the GPU said no" in r.json()["detail"]


def test_an_engine_refusal_keeps_its_own_status_code(client, monkeypatch):
    """The 503 for missing weights must not become a 500 on the way through the stream."""
    from fastapi import HTTPException

    def missing(engine, text, voice):
        raise HTTPException(status_code=503, detail="Kokoro weights missing")

    monkeypatch.setattr(server.synth, "stream_pieces", missing)
    r = client.get("/v1/tts/stream", params={"text": "Hello", "lang": "en"})
    assert r.status_code == 503


def test_streaming_uses_its_own_smaller_chunk_budget(client, monkeypatch, tone, engine_factory):
    """The file splits for correctness, the stream splits for latency."""
    fake = engine_factory("orpheus", tone, languages=("de",), chunk_chars=500)
    engines.register("orpheus", fake)
    monkeypatch.setattr(config, "TTS_STREAM_CHUNK_CHARS", 40)
    text = ". ".join(f"Dies ist Satz nummer {i} mit reichlich Text" for i in range(6))

    client.get("/v1/tts/stream", params={"text": text})
    assert len(fake.calls) > 1
    assert all(len(call[0]) <= 40 for call in fake.calls)


def test_the_catalogue_says_whether_this_process_streams(client):
    body = client.get("/v1/models").json()["streaming"]
    assert body["endpoint"] == "/v1/tts/stream"
    assert body["formats"] == ["mp3", "wav"]
    assert body["chunk_chars"] == config.TTS_STREAM_CHUNK_CHARS
    # Orpheus can stream inside one generation; the switch is on by default.
    assert body["incremental"] is True
