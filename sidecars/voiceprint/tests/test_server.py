"""
The HTTP contract, with the models replaced by a fake.

The fake embeds a window as a unit vector that depends only on the window's dominant frequency,
and the synthetic "speakers" are tones at two different pitches. So every assertion about
speakers, segments and windows checks the real pipeline - VAD, windowing, batching, clustering,
timeline arithmetic, JSON shape - with only the neural network taken out.
"""

import io
import json

import numpy as np
import pytest
import soundfile as sf
from fastapi.testclient import TestClient

import server
from voiceprint import backends, config
from voiceprint.backends.base import Backend

RATE = 16000
TOL_S = 0.35


class FakeBackend(Backend):
    name = "fake"
    default_threshold = 0.9
    licence = "none"
    commercial = True

    def __init__(self):
        super().__init__()
        self.calls = []

    @property
    def model_id(self):
        return "fake-voice-1"

    def _load(self):
        self.device = "cpu"

    def _embed(self, wavs):
        self.calls.append(len(wavs))
        out = []
        for w in wavs:
            spec = np.abs(np.fft.rfft(w))
            f = np.argmax(spec) * RATE / len(w)
            theta = f / 100.0  # 200 Hz -> 2 rad, 600 Hz -> 6 rad: cosine -0.65 between the two voices
            out.append([np.cos(theta), np.sin(theta), 0.0, 0.0])
        return np.array(out, dtype=np.float32)


def _tone(seconds, freq, seed=0):
    t = np.arange(int(seconds * RATE)) / RATE
    rng = np.random.default_rng(seed)
    env = 0.6 + 0.4 * np.sin(2 * np.pi * 3 * t)
    return (0.3 * env * np.sin(2 * np.pi * freq * t) + rng.standard_normal(len(t)) * 0.002).astype(np.float32)


def _silence(seconds, seed=0):
    rng = np.random.default_rng(seed)
    return (rng.standard_normal(int(seconds * RATE)) * 0.0005).astype(np.float32)


# A at 200 Hz, B at 600 Hz. A speaks 1-5 and 11-14 s, B 6-10 s.
TURNS = [("A", 1.0, 5.0), ("B", 6.0, 10.0), ("A", 11.0, 14.0)]
FREQ = {"A": 200.0, "B": 600.0}


def _conversation():
    parts = [_silence(1.0)]
    pos = 1.0
    for who, s, e in TURNS:
        if s > pos:
            parts.append(_silence(s - pos))
        parts.append(_tone(e - s, FREQ[who], seed=int(s)))
        pos = e
    parts.append(_silence(1.0))
    return np.concatenate(parts)


def _wav(audio):
    buf = io.BytesIO()
    sf.write(buf, audio, RATE, format="WAV", subtype="PCM_16")
    return buf.getvalue()


@pytest.fixture
def fake(monkeypatch):
    backend = FakeBackend()
    backends.register("fake", backend)
    monkeypatch.setattr(config, "BACKENDS", ["fake"])
    monkeypatch.setattr(config, "DEFAULT_MODEL", "fake")
    return backend


@pytest.fixture
def client(fake):
    with TestClient(server.app) as c:
        yield c


def _post(client, audio, **fields):
    return client.post("/v1/voiceprints", files={"file": ("x.wav", _wav(audio), "audio/wav")}, data=fields)


def _truth(start_s, end_s):
    best, overlap = None, 0.0
    for who, s, e in TURNS:
        o = max(0.0, min(end_s, e) - max(start_s, s))
        if o > overlap:
            best, overlap = who, o
    return best


def test_health_and_models(client, fake):
    h = client.get("/health").json()
    assert h["status"] == "ok"
    assert h["default_model"] == "fake"
    assert h["backends"]["fake"]["model"] == "fake-voice-1"
    assert h["backends"]["fake"]["loaded"] is False
    assert h["window_s"] == config.WINDOW_S
    m = client.get("/v1/models").json()
    assert [d["id"] for d in m["data"]] == ["fake"]


def test_two_tones_are_two_speakers_with_a_timeline(client, fake):
    r = _post(client, _conversation())
    assert r.status_code == 200, r.text
    assert r.headers["X-Model-Id"] == "fake-voice-1"
    assert r.headers["X-Backend"] == "fake"
    body = r.json()
    assert body["model"] == "fake-voice-1" and body["backend"] == "fake"
    assert body["dim"] == 4 and body["normalized"] is True
    assert body["threshold"] == 0.9
    assert abs(body["duration_ms"] - 15000) <= 1
    assert 10_000 <= body["speech_ms"] <= 12_000

    speakers = body["speakers"]
    assert len(speakers) == 2
    # Longest first: A speaks 7 s, B 4 s.
    assert speakers[0]["total_ms"] > speakers[1]["total_ms"]
    assert speakers[0]["index"] == 0 and speakers[1]["index"] == 1
    for sp in speakers:
        assert abs(np.linalg.norm(sp["vector"]) - 1.0) < 1e-4
        assert 0.0 < sp["cohesion"] <= 1.0
        assert sp["windows"]
    a_segments = [(s["start_ms"] / 1000, s["end_ms"] / 1000) for s in speakers[0]["segments"]]
    assert len(a_segments) == 2
    assert abs(a_segments[0][0] - 1.0) < TOL_S and abs(a_segments[0][1] - 5.0) < TOL_S
    assert abs(a_segments[1][0] - 11.0) < TOL_S and abs(a_segments[1][1] - 14.0) < TOL_S
    b_segments = [(s["start_ms"] / 1000, s["end_ms"] / 1000) for s in speakers[1]["segments"]]
    assert len(b_segments) == 1
    assert abs(b_segments[0][0] - 6.0) < TOL_S and abs(b_segments[0][1] - 10.0) < TOL_S

    windows = body["windows"]
    assert windows and all(w["end_ms"] - w["start_ms"] <= 3000 for w in windows)
    assert [w["index"] for w in windows] == list(range(len(windows)))
    for w in windows:
        who = _truth(w["start_ms"] / 1000, w["end_ms"] / 1000)
        assert w["speaker"] == (0 if who == "A" else 1), w
        assert len(w["vector"]) == 4
    # Every window of a speaker is listed on that speaker, and only there.
    for sp in speakers:
        assert all(windows[i]["speaker"] == sp["index"] for i in sp["windows"])
    assert "pooled" in body and len(body["pooled"]["vector"]) == 4
    # Batched: after the one-window load probe, all windows went through in one call (equal length).
    assert fake.calls == [1, len(windows)]


def test_vectors_and_windows_can_be_left_out(client, fake):
    body = _post(client, _conversation(), include_vectors="false", include_windows="false").json()
    assert body["windows"] == []
    assert len(body["speakers"]) == 2
    assert all("vector" not in sp for sp in body["speakers"])
    assert body["pooled"] == {}


def test_max_speakers_and_threshold_override(client, fake):
    body = _post(client, _conversation(), max_speakers="1").json()
    assert len(body["speakers"]) == 1
    assert len(body["speakers"][0]["segments"]) == 3
    # A threshold below every cosine in the fake space merges everything too.
    body = _post(client, _conversation(), cluster_threshold="-1").json()
    assert len(body["speakers"]) == 1 and body["threshold"] == -1.0


def test_window_parameters_are_honoured(client, fake):
    body = _post(client, _conversation(), window_s="2", hop_s="2").json()
    assert body["window_s"] == 2.0 and body["hop_s"] == 2.0
    assert all(w["end_ms"] - w["start_ms"] <= 2000 for w in body["windows"])
    assert _post(client, _conversation(), window_s="1", hop_s="2").status_code == 400
    assert _post(client, _conversation(), min_speech_s="0").status_code == 400
    assert _post(client, _conversation(), cluster_threshold="2").status_code == 400


def test_silence_yields_no_speakers(client, fake):
    body = _post(client, _silence(5.0)).json()
    assert body["speakers"] == [] and body["windows"] == []
    assert body["speech_ms"] == 0 and "pooled" not in body


def test_model_resolution(client, fake):
    assert _post(client, _conversation(), model="fake").status_code == 200
    assert _post(client, _conversation(), model="fake-voice-1").status_code == 200, "the full model id is an alias"
    r = _post(client, _conversation(), model="nope")
    assert r.status_code == 400 and "nope" in r.text


def test_bad_uploads(client, fake):
    r = client.post("/v1/voiceprints", files={"file": ("x.bin", b"not audio at all", "application/octet-stream")})
    assert r.status_code == 400
    r = client.post("/v1/voiceprints", files={"file": ("x.wav", b"", "audio/wav")})
    assert r.status_code == 400


def test_upload_limit(client, fake, monkeypatch):
    monkeypatch.setattr(config, "MAX_UPLOAD_MB", 0)
    assert _post(client, _conversation()).status_code == 413


def test_compare(client, fake):
    r = client.post("/v1/compare", json={"a": [1, 0, 0], "b": [1, 0, 0]})
    assert r.status_code == 200 and abs(r.json()["score"] - 1.0) < 1e-9 and r.json()["dim"] == 3
    r = client.post("/v1/compare", json={"a": [1, 0], "b": [0, 1]})
    assert abs(r.json()["score"]) < 1e-9
    assert client.post("/v1/compare", json={"a": [1, 0], "b": [1]}).status_code == 400
    assert client.post("/v1/compare", json={"a": [], "b": []}).status_code == 400


def test_loom_shaped_round_trip(client, fake):
    """The fields the Cortex node reads, and that they survive a JSON round trip unchanged."""
    body = json.loads(_post(client, _conversation()).content)
    for key in ("model", "backend", "dim", "normalized", "threshold", "duration_ms", "speech_ms", "speakers", "windows", "pooled", "compute_ms"):
        assert key in body, key
    for sp in body["speakers"]:
        for key in ("index", "total_ms", "cohesion", "windows", "segments", "vector"):
            assert key in sp, key
    for w in body["windows"]:
        for key in ("index", "start_ms", "end_ms", "speaker", "vector"):
            assert key in w, key
