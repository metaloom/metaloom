"""
The HTTP and WebSocket contract, with the models replaced by fakes.

The fakes answer in *utterance-relative* time exactly like the real backends do, and report
their word where the loud part of the audio they were handed is. So every assertion of the form
"the word is at 1.0 - 3.0 s" checks that the server turned utterance-relative time into
session/file-absolute time correctly - the one transformation every time code depends on.
"""

import base64
import io
import shutil
import subprocess

import numpy as np
import pytest
import soundfile as sf
from fastapi.testclient import TestClient

import server
from asr import backends, config
from asr.backends.base import Backend
from asr.timecodes import Segment, Transcript, Word

from .test_endpointing import RATE, _timeline

# speech at 1.0-3.0 s and 5.0-6.5 s
SPEC = [(1.0, False), (2.0, True), (2.0, False), (1.5, True), (1.0, False)]
SPEECH = [(1.0, 3.0), (5.0, 6.5)]
TOL = 0.1


def _loud_range(audio):
    """(first, last) loud second of `audio`, relative to its start."""
    frames = audio[: len(audio) // 160 * 160].reshape(-1, 160)
    loud = np.flatnonzero(np.sqrt((frames**2).mean(axis=1)) > 0.02)
    if not len(loud):
        return None
    return loud[0] * 160 / RATE, (loud[-1] + 1) * 160 / RATE


class FakeBatch(Backend):
    name = "fake"
    takes_language = True

    def __init__(self):
        super().__init__()
        self.calls = []

    @property
    def model_id(self):
        return "fake-batch-1"

    def _load(self):
        pass

    def _transcribe(self, audio, language):
        self.calls.append((len(audio), language))
        r = _loud_range(audio)
        if r is None:
            return Transcript([], language, len(audio) / RATE, self.model_id)
        a, b = r
        mid = (a + b) / 2
        words = [Word("hallo", a, mid), Word("welt.", mid, b)]
        return Transcript([Segment(a, b, "hallo welt.", words)], language, len(audio) / RATE, self.model_id)


class FakeStreaming(Backend):
    """Reads the utterance frame by frame through take(); one word per loud run, reported as it ends."""

    name = "fakestream"
    streaming = True
    pad_samples = int(0.3 * RATE)

    @property
    def model_id(self):
        return "fake-stream-1"

    def _load(self):
        pass

    def stream(self, take, on_token, on_words):
        frame = 1280
        k = 0
        run_start = None
        n = 0
        while True:
            chunk = take(k * frame, frame)
            if chunk is None:
                break
            t = k * frame / RATE
            loud = np.sqrt((chunk**2).mean()) > 0.02
            if loud and run_start is None:
                run_start = t
            if not loud and run_start is not None:
                n += 1
                on_token(f" wort{n}", t)
                on_words([Word(f"wort{n}.", run_start, t)], t)
                run_start = None
            else:
                on_words([], t)
            k += 1


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(config, "PARTIAL_INTERVAL_MS", 0)
    backends.register("fake", FakeBatch())
    backends.register("fakestream", FakeStreaming())
    with TestClient(server.app) as c:
        yield c


def _flac(audio):
    buf = io.BytesIO()
    sf.write(buf, audio, RATE, format="FLAC", subtype="PCM_16")
    return buf.getvalue()


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
def test_health_lists_backends(client):
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert {"whisper", "parakeet", "voxtral", "fake"} <= set(body["backends"])
    assert body["backends"]["whisper"]["loaded"] is False  # nothing loads until it is used


def test_transcription_times_are_absolute_in_the_file(client):
    r = client.post("/v1/audio/transcriptions", files={"file": ("a.flac", _flac(_timeline(SPEC)))},
                    data={"model": "fake"})
    assert r.status_code == 200, r.text
    assert r.headers["x-model-id"] == "fake-batch-1"
    body = r.json()
    assert [s["text"] for s in body["segments"]] == ["hallo welt.", "hallo welt."]
    for seg, (a, b) in zip(body["segments"], SPEECH):
        assert abs(seg["start"] - a) < TOL and abs(seg["end"] - b) < TOL
        assert seg["start_ms"] == round(seg["start"] * 1000)
        assert seg["words"][0]["start"] == seg["start"] and seg["words"][-1]["end"] == seg["end"]
    assert body["language"] == "de" and abs(body["duration"] - 7.5) < 0.01
    assert len(body["words"]) == 4


def test_long_file_is_decoded_span_by_span(client):
    fake = backends.get("fake")
    fake.calls.clear()
    client.post("/v1/audio/transcriptions", files={"file": ("a.flac", _flac(_timeline(SPEC)))}, data={"model": "fake"})
    assert len(fake.calls) == 2
    assert all(n < 3.0 * RATE for n, _ in fake.calls)  # neither span includes the 2 s gap


def test_loom_format(client):
    r = client.post("/v1/audio/transcriptions", files={"file": ("a.flac", _flac(_timeline(SPEC)))},
                    data={"model": "fake", "response_format": "loom"})
    segs = r.json()["segments"]
    assert set(segs[0]) == {"text", "from", "to"}
    assert abs(segs[1]["from"] - 5000) < 100 and abs(segs[1]["to"] - 6500) < 100


def test_srt_format_and_openai_fields_are_accepted(client):
    r = client.post(
        "/v1/audio/transcriptions",
        files={"file": ("a.flac", _flac(_timeline(SPEC)))},
        data={"model": "fake", "response_format": "srt", "timestamp_granularities[]": ["word", "segment"],
              "temperature": "0", "prompt": "x"},
    )
    assert r.status_code == 200
    assert r.text.startswith("1\n00:00:0") and " --> " in r.text


def test_language_is_passed_through(client):
    fake = backends.get("fake")
    fake.calls.clear()
    client.post("/v1/audio/transcriptions", files={"file": ("a.flac", _flac(_timeline(SPEC)))},
                data={"model": "fake", "language": "en"})
    assert {lang for _, lang in fake.calls} == {"en"}


@pytest.mark.parametrize(
    "data,status",
    [({"model": "nope"}, 400), ({"model": "fake", "response_format": "xml"}, 400)],
)
def test_bad_requests(client, data, status):
    r = client.post("/v1/audio/transcriptions", files={"file": ("a.flac", _flac(_timeline(SPEC)))}, data=data)
    assert r.status_code == status


@pytest.mark.skipif(not shutil.which("ffmpeg"), reason="needs ffmpeg (the container image has it)")
def test_video_upload_goes_through_ffmpeg(client, tmp_path):
    """An mp4 keeps its index at the end - ffmpeg must read it from a seekable file, not a pipe."""
    wav = tmp_path / "a.wav"
    sf.write(wav, _timeline(SPEC), RATE)
    mp4 = tmp_path / "a.mp4"
    subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=black:s=64x64:d=8", "-i", str(wav),
                    "-shortest", "-c:v", "libx264", "-c:a", "aac", str(mp4)], check=True)
    r = client.post("/v1/audio/transcriptions", files={"file": ("a.mp4", mp4.read_bytes())}, data={"model": "fake"})
    assert r.status_code == 200, r.text
    for seg, (a, b) in zip(r.json()["segments"], SPEECH):
        assert abs(seg["start"] - a) < 0.15 and abs(seg["end"] - b) < 0.15  # aac adds priming delay


def test_undecodable_upload_is_400(client):
    r = client.post("/v1/audio/transcriptions", files={"file": ("a.wav", b"not audio at all")}, data={"model": "fake"})
    assert r.status_code == 400


# ---------------------------------------------------------------------------
# Realtime
# ---------------------------------------------------------------------------
def _send_audio(ws, audio, chunk_s=0.1):
    step = int(chunk_s * RATE)
    for i in range(0, len(audio), step):
        pcm = (np.clip(audio[i : i + step], -1, 1) * 32767).astype("<i2").tobytes()
        ws.send_json({"type": "input_audio_buffer.append", "audio": base64.b64encode(pcm).decode()})


def _events_until_done(ws, item_id, limit=500):
    events = []
    for _ in range(limit):
        e = ws.receive_json()
        events.append(e)
        if e["type"] == "error":
            raise AssertionError(e)
        if e["type"] == "transcription.done" and e["item_id"] == item_id:
            return events
    raise AssertionError(f"no transcription.done for {item_id}: {[e['type'] for e in events]}")


@pytest.mark.parametrize("model", ["fake", "fakestream"])
def test_realtime_server_vad_segments_are_session_absolute(client, model):
    with client.websocket_connect(f"/v1/realtime?model={model}") as ws:
        created = ws.receive_json()
        assert created["type"] == "session.created" and created["session"]["model"] == model
        _send_audio(ws, _timeline(SPEC))
        ws.send_json({"type": "input_audio_buffer.commit"})
        # The commit's own item: the endpointer already closed both utterances on their pauses,
        # so the commit gets an empty one - still exactly one done, and it comes last.
        committed = None
        events = []
        while committed is None:
            e = ws.receive_json()
            events.append(e)
            if e["type"] == "input_audio_buffer.committed":
                committed = e["item_id"]
        events += _events_until_done(ws, committed)

    started = [e for e in events if e["type"] == "input_audio_buffer.speech_started"]
    stopped = [e for e in events if e["type"] == "input_audio_buffer.speech_stopped"]
    segments = [e["segment"] for e in events if e["type"] == "transcription.segment"]
    dones = [e for e in events if e["type"] == "transcription.done"]
    assert len(started) == 2 and len(stopped) == 2
    assert len(segments) == 2
    for seg, (a, b) in zip(segments, SPEECH):
        assert abs(seg["start"] - a) < 0.15, seg
        assert abs(seg["end"] - b) < 0.15, seg
    assert [s["id"] for s in segments] == [0, 1]
    assert [d["item_id"] for d in dones] == [started[0]["item_id"], started[1]["item_id"], committed]
    assert dones[-1]["text"] == "" and dones[-1]["segments"] == []
    assert dones[0]["segments"][0] == segments[0]  # done repeats the segments, same ids and times
    if model == "fakestream":
        deltas = [e for e in events if e["type"] == "transcription.delta"]
        assert [d["delta"] for d in deltas] == [" wort1", " wort1"]  # one word per utterance
        assert abs(deltas[0]["end"] - 3.0) < 0.15


def test_realtime_manual_commit_mode(client):
    with client.websocket_connect("/v1/realtime?model=fake") as ws:
        ws.receive_json()
        ws.send_json({"type": "session.update", "session": {"turn_detection": None, "language": "en"}})
        updated = ws.receive_json()
        assert updated["type"] == "session.updated" and updated["session"]["turn_detection"] is None
        audio = _timeline(SPEC)
        _send_audio(ws, audio[: int(4.0 * RATE)])
        ws.send_json({"type": "input_audio_buffer.commit"})
        _send_audio(ws, audio[int(4.0 * RATE):])
        ws.send_json({"type": "input_audio_buffer.commit"})
        events = []
        commits = []
        while len(commits) < 2 or not any(e["type"] == "transcription.done" and e["item_id"] == commits[1] for e in events):
            e = ws.receive_json()
            events.append(e)
            if e["type"] == "input_audio_buffer.committed":
                commits.append(e["item_id"])
    dones = [e for e in events if e["type"] == "transcription.done"]
    assert [d["item_id"] for d in dones] == commits
    # first utterance 0-4 s holds speech 1-3 s; second 4-7.5 s holds 5-6.5 s
    assert (dones[0]["start"], dones[0]["end"]) == (0.0, 4.0)
    assert abs(dones[1]["segments"][0]["start"] - 5.0) < TOL
    assert abs(dones[1]["segments"][0]["end"] - 6.5) < TOL
    assert not any(e["type"] == "input_audio_buffer.speech_started" for e in events)


def test_realtime_partials(client, monkeypatch):
    monkeypatch.setattr(config, "PARTIAL_INTERVAL_MS", 50)
    with client.websocket_connect("/v1/realtime?model=fake") as ws:
        ws.receive_json()
        audio = _timeline([(0.5, False), (3.0, True), (1.0, False)])
        step = int(0.1 * RATE)
        import time as _t

        for i in range(0, len(audio), step):
            pcm = (audio[i : i + step] * 32767).astype("<i2").tobytes()
            ws.send_json({"type": "input_audio_buffer.append", "audio": base64.b64encode(pcm).decode()})
            _t.sleep(0.01)
        ws.send_json({"type": "input_audio_buffer.commit"})
        events = []
        committed = None
        while True:
            e = ws.receive_json()
            events.append(e)
            if e["type"] == "input_audio_buffer.committed":
                committed = e["item_id"]
            if committed and e["type"] == "transcription.done" and e["item_id"] == committed:
                break
    partials = [e for e in events if e["type"] == "transcription.partial"]
    assert partials, [e["type"] for e in events]
    assert all(p["text"] == "hallo welt." for p in partials)
    assert all(p["start"] <= p["end"] for p in partials)


def test_realtime_unknown_model_is_an_error(client):
    with client.websocket_connect("/v1/realtime?model=nope") as ws:
        e = ws.receive_json()
        assert e["type"] == "error" and "nope" in e["error"]["message"]


def test_realtime_model_switch_only_before_audio(client):
    with client.websocket_connect("/v1/realtime?model=fake") as ws:
        ws.receive_json()
        ws.send_json({"type": "session.update", "model": "fakestream"})  # vLLM's flat shape
        assert ws.receive_json()["session"]["model"] == "fakestream"
        _send_audio(ws, _timeline([(0.2, False)]))
        ws.send_json({"type": "session.update", "session": {"model": "fake"}})
        assert ws.receive_json()["type"] == "error"
