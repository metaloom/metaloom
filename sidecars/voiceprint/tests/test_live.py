"""
Live test: the real models, over the real HTTP API, on the two-voice fixture
(tests/data/two_speakers.wav, built by make_fixture.py from two TTS voices taking turns).

It checks the three things this sidecar exists for, per backend:

  separation   the two voices come back as exactly two speakers, and every window is attributed
               to the voice that was actually speaking in it
  voiceprints  windows of the same voice are far more similar than windows of different voices,
               and the per-speaker vectors tell the two apart through /v1/compare
  speed        a 37 s conversation is analysed in well under its own duration

Skipped unless VOICEPRINT_TEST_URL points at a running sidecar:

  VOICEPRINT_TEST_URL=http://localhost:9150 python -m pytest tests/test_live.py -v -s

  VOICEPRINT_TEST_MODELS  backends to test  (default: redimnet2,wespeaker,speechbrain)
"""

import json
import os
import time
from pathlib import Path

import numpy as np
import pytest

URL = os.environ.get("VOICEPRINT_TEST_URL", "")
MODELS = [m.strip() for m in os.environ.get("VOICEPRINT_TEST_MODELS", "redimnet2,wespeaker,speechbrain").split(",") if m.strip()]
DATA = Path(__file__).parent / "data"

pytestmark = pytest.mark.skipif(not URL, reason="set VOICEPRINT_TEST_URL to a running sidecar to run the live tests")

MIN_WINDOW_AGREEMENT = 0.9
MIN_SEPARATION = 0.3  # mean same-voice cosine minus mean cross-voice cosine
MAX_CROSS_SCORE = 0.3  # /v1/compare of the two speaker voiceprints
MAX_RTF = 0.5


def _fixture():
    fx = json.load(open(DATA / "two_speakers.json"))
    return (DATA / "two_speakers.wav").read_bytes(), fx


def _truth(fx, start_ms, end_ms):
    best, overlap = None, 0
    for t in fx["turns"]:
        s, e = t["start"] * 1000 / fx["rate"], t["end"] * 1000 / fx["rate"]
        o = max(0.0, min(end_ms, e) - max(start_ms, s))
        if o > overlap:
            best, overlap = t["speaker"], o
    return best


@pytest.fixture(scope="module")
def http():
    import httpx

    with httpx.Client(base_url=URL, timeout=600) as c:
        yield c


def test_health(http):
    h = http.get("/health").json()
    assert h["status"] == "ok"
    for m in MODELS:
        assert m in h["backends"], f"{m} is not enabled on the sidecar: {list(h['backends'])}"


@pytest.mark.parametrize("model", MODELS)
def test_two_voices(http, model):
    wav, fx = _fixture()
    started = time.time()
    r = http.post("/v1/voiceprints", files={"file": ("two_speakers.wav", wav, "audio/wav")}, data={"model": model})
    wall = time.time() - started
    assert r.status_code == 200, r.text
    body = r.json()
    assert r.headers["X-Backend"] == model
    duration_s = body["duration_ms"] / 1000
    print(f"\n{model}: {body['model']} dim {body['dim']} - {len(body['windows'])} windows, {len(body['speakers'])} speakers, "
          f"compute {body['compute_ms']} ms, wall {wall:.2f} s for {duration_s:.1f} s audio (RTF {wall / duration_s:.3f})")

    assert len(body["speakers"]) == 2, [s["total_ms"] for s in body["speakers"]]
    windows = body["windows"]
    assert len(windows) >= 8

    # Which cluster is which voice: majority vote, then every window must follow it.
    votes = {}
    for w in windows:
        who = _truth(fx, w["start_ms"], w["end_ms"])
        votes.setdefault(w["speaker"], {}).setdefault(who, 0)
        votes[w["speaker"]][who] += 1
    mapping = {idx: max(v, key=v.get) for idx, v in votes.items()}
    assert len(set(mapping.values())) == 2, f"both clusters map to the same voice: {votes}"
    agree = sum(1 for w in windows if mapping[w["speaker"]] == _truth(fx, w["start_ms"], w["end_ms"]))
    print(f"{model}: {agree}/{len(windows)} windows attributed to the right voice")
    assert agree / len(windows) >= MIN_WINDOW_AGREEMENT

    vectors = np.array([w["vector"] for w in windows])
    truth = [_truth(fx, w["start_ms"], w["end_ms"]) for w in windows]
    sims = vectors @ vectors.T
    same = [sims[i, j] for i in range(len(windows)) for j in range(i + 1, len(windows)) if truth[i] == truth[j]]
    cross = [sims[i, j] for i in range(len(windows)) for j in range(i + 1, len(windows)) if truth[i] != truth[j]]
    print(f"{model}: same-voice cosine {np.mean(same):.2f} (min {np.min(same):.2f}), cross-voice {np.mean(cross):.2f} (max {np.max(cross):.2f})")
    assert np.mean(same) - np.mean(cross) >= MIN_SEPARATION

    a, b = body["speakers"][0]["vector"], body["speakers"][1]["vector"]
    score = http.post("/v1/compare", json={"a": a, "b": b}).json()["score"]
    print(f"{model}: compare(speaker 0, speaker 1) = {score:.3f}")
    assert score < MAX_CROSS_SCORE
    assert abs(http.post("/v1/compare", json={"a": a, "b": a}).json()["score"] - 1.0) < 1e-4
    assert wall / duration_s < MAX_RTF


@pytest.mark.parametrize("model", MODELS)
def test_full_model_id_is_accepted(http, model):
    wav, _ = _fixture()
    full = http.get("/health").json()["backends"][model]["model"]
    r = http.post("/v1/voiceprints", files={"file": ("two_speakers.wav", wav, "audio/wav")},
                  data={"model": full, "include_windows": "false", "include_vectors": "false"})
    assert r.status_code == 200 and r.headers["X-Backend"] == model
    assert r.json()["windows"] == [] and "vector" not in r.json()["speakers"][0]
