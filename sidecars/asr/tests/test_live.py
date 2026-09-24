"""
Live test: the real models, over the real HTTP and WebSocket API, on German speech with known
positions (tests/data/de_timeline.flac, built by make_fixture.py).

It checks the three things this sidecar exists for:

  quality     every clip is transcribed with a low word error rate against its reference
  time codes  every word lands inside the window of the clip it belongs to, no word lands in a
              gap, and the word timings agree with Whisper's (an independent method: DTW on
              cross-attention) to within a fraction of a second
  real time   streamed at the speed of speech, final segments arrive shortly after each
              utterance ends - and Voxtral shows text while the clip is still being spoken

Skipped unless ASR_TEST_URL points at a running sidecar:

  ASR_TEST_URL=http://localhost:9140 python -m pytest tests/test_live.py -v -s

  ASR_TEST_MODELS  backends to test          (default: whisper,parakeet,voxtral)
  ASR_TEST_PACE    streaming speed factor    (default: 1.0 = real time; 2.0 = twice as fast)
"""

import asyncio
import base64
import difflib
import json
import os
import re
import statistics
import time
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf

URL = os.environ.get("ASR_TEST_URL", "")
MODELS = [m.strip() for m in os.environ.get("ASR_TEST_MODELS", "whisper,parakeet,voxtral").split(",") if m.strip()]
PACE = float(os.environ.get("ASR_TEST_PACE", "1.0"))
DATA = Path(__file__).parent / "data"

pytestmark = pytest.mark.skipif(not URL, reason="set ASR_TEST_URL to a running sidecar to run the live tests")

# A word may start or end this far outside its clip's window. The windows are the clips' exact
# sample positions, and a model's boundary is only ever a frame or two off; 0.35 s is far below
# the 2.5 s gaps, so a word in the wrong place still fails.
WINDOW_TOL = 0.35
MAX_CLIP_WER = 0.20
MAX_TOTAL_WER = 0.10
# Agreement with Whisper's DTW word timings: median, 90th percentile and worst |difference|. The
# worst case matters: a sentence's last word ending in the pause after it is exactly the error a
# percentile hides.
END_AGREEMENT = (0.20, 0.45, 0.50)
START_AGREEMENT = {"parakeet": (0.20, 0.45), "voxtral": (0.35, 0.70)}  # voxtral's starts are estimated
# Wall-clock from the end of a clip's speech having been sent to its last final segment arriving.
FINAL_LATENCY_S = 3.0


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def norm_words(text):
    text = text.lower().replace("ß", "ss").replace("-", " ")
    return re.sub(r"[^\w\s]", " ", text).split()


def wer(ref, hyp):
    r, h = norm_words(ref), norm_words(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)] / max(1, len(r))


def clip_of(meta, t):
    for c in meta["clips"]:
        if c["start"] - WINDOW_TOL <= t <= c["end"] + WINDOW_TOL:
            return c["index"]
    return None


def assert_timecodes(meta, segments, label):
    """Every word and segment inside a clip window; words in order; every clip covered."""
    words = [w for s in segments for w in s["words"]]
    assert words, f"{label}: no words"
    covered = set()
    for s in segments:
        assert s["start"] <= s["end"], s
        c0, c1 = clip_of(meta, s["start"]), clip_of(meta, s["end"])
        assert c0 is not None and c0 == c1, f"{label}: segment outside/straddling clip windows: {s}"
        covered.add(c0)
    for w in words:
        assert w["start"] <= w["end"], w
        c0, c1 = clip_of(meta, w["start"]), clip_of(meta, w["end"])
        assert c0 is not None and c0 == c1, f"{label}: word in a gap: {w}"
    starts = [w["start"] for w in words]
    assert starts == sorted(starts), f"{label}: words out of order"
    assert covered == {c["index"] for c in meta["clips"]}, f"{label}: clips without a segment: {covered}"


def text_per_clip(meta, segments):
    out = {c["index"]: [] for c in meta["clips"]}
    for s in segments:
        out[clip_of(meta, (s["start"] + s["end"]) / 2)].append(s["text"])
    return {k: " ".join(v) for k, v in out.items()}


def assert_quality(meta, segments, label):
    per_clip = text_per_clip(meta, segments)
    report = []
    for c in meta["clips"]:
        e = wer(c["text"], per_clip[c["index"]])
        report.append(f"clip {c['index']}: WER {e:.1%}  {per_clip[c['index']]!r}")
        assert e <= MAX_CLIP_WER, f"{label}: clip {c['index']} WER {e:.1%}: {per_clip[c['index']]!r}"
    total = wer(" ".join(c["text"] for c in meta["clips"]), " ".join(per_clip.values()))
    print(f"\n[{label}] total WER {total:.1%}\n  " + "\n  ".join(report))
    assert total <= MAX_TOTAL_WER


def align(words_a, words_b):
    """Pairs of (a, b) words whose normalised text matches, in order."""
    na = [" ".join(norm_words(w["word"])) for w in words_a]
    nb = [" ".join(norm_words(w["word"])) for w in words_b]
    pairs = []
    for block in difflib.SequenceMatcher(a=na, b=nb, autojunk=False).get_matching_blocks():
        for k in range(block.size):
            pairs.append((words_a[block.a + k], words_b[block.b + k]))
    return pairs


def agreement(pairs, key):
    diffs = sorted(abs(a[key] - b[key]) for a, b in pairs)
    return statistics.median(diffs), diffs[int(0.9 * (len(diffs) - 1))], diffs[-1]


# ---------------------------------------------------------------------------
# fixtures
# ---------------------------------------------------------------------------
@pytest.fixture(scope="module")
def meta():
    return json.loads((DATA / "de_timeline.json").read_text())


@pytest.fixture(scope="module")
def flac_bytes():
    return (DATA / "de_timeline.flac").read_bytes()


@pytest.fixture(scope="module")
def pcm():
    audio, rate = sf.read(DATA / "de_timeline.flac", dtype="float32")
    assert rate == 16000
    return (np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes()


_http_cache = {}


def http_result(model, flac_bytes, response_format="verbose_json"):
    key = (model, response_format)
    if key not in _http_cache:
        import httpx

        started = time.time()
        r = httpx.post(
            f"{URL}/v1/audio/transcriptions",
            files={"file": ("de_timeline.flac", flac_bytes, "audio/flac")},
            data={"model": model, "language": "de", "response_format": response_format},
            timeout=900,
        )
        assert r.status_code == 200, r.text
        _http_cache[key] = r.json()
        print(f"\n[{model}] HTTP {response_format} in {time.time() - started:.1f}s (includes a cold load)")
    return _http_cache[key]


_rt_cache = {}


async def _stream(model, pcm):
    import websockets

    ws_url = URL.replace("http", "ws", 1) + f"/v1/realtime?model={model}"
    events = []
    async with websockets.connect(ws_url, max_size=None, open_timeout=900) as ws:
        created = json.loads(await asyncio.wait_for(ws.recv(), 900))
        assert created["type"] == "session.created", created
        await ws.send(json.dumps({"type": "session.update", "session": {"language": "de"}}))
        chunk = 3200  # 100 ms of PCM16
        t0 = time.monotonic()
        done = asyncio.Event()
        commit_item = {}

        async def receive():
            async for raw in ws:
                e = json.loads(raw)
                e["_t"] = time.monotonic() - t0
                events.append(e)
                if e["type"] == "error":
                    raise AssertionError(e)
                if e["type"] == "input_audio_buffer.committed":
                    commit_item["id"] = e["item_id"]
                if e["type"] == "transcription.done" and e.get("item_id") == commit_item.get("id"):
                    done.set()
                    return

        receiver = asyncio.create_task(receive())
        for i in range(0, len(pcm), chunk):
            await ws.send(json.dumps({"type": "input_audio_buffer.append",
                                      "audio": base64.b64encode(pcm[i : i + chunk]).decode()}))
            # Pace against the wall clock, not per chunk, so send overhead does not accumulate.
            target = (i + chunk) / 2 / 16000 / PACE
            delay = target - (time.monotonic() - t0)
            if delay > 0:
                await asyncio.sleep(delay)
        await ws.send(json.dumps({"type": "input_audio_buffer.commit"}))
        await asyncio.wait_for(done.wait(), 300)
        await receiver
    return events


def realtime_events(model, pcm):
    if model not in _rt_cache:
        _rt_cache[model] = asyncio.run(_stream(model, pcm))
    return _rt_cache[model]


def rt_segments(events):
    return [e["segment"] for e in events if e["type"] == "transcription.segment"]


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("model", MODELS)
def test_http_german_quality(model, meta, flac_bytes):
    body = http_result(model, flac_bytes)
    assert_quality(meta, body["segments"], f"{model} http")


@pytest.mark.parametrize("model", MODELS)
def test_http_timecodes_in_clip_windows(model, meta, flac_bytes):
    body = http_result(model, flac_bytes)
    assert abs(body["duration"] - meta["duration"]) < 0.05
    assert_timecodes(meta, body["segments"], f"{model} http")
    for s in body["segments"]:
        assert s["start_ms"] == round(s["start"] * 1000) and s["end_ms"] == round(s["end"] * 1000)


@pytest.mark.parametrize("model", [m for m in MODELS if m != "whisper"])
def test_http_word_timing_agrees_with_whisper(model, meta, flac_bytes):
    if "whisper" not in MODELS:
        pytest.skip("needs the whisper backend as the reference")
    ref = http_result("whisper", flac_bytes)["words"]
    hyp = http_result(model, flac_bytes)["words"]
    pairs = align(hyp, ref)
    assert len(pairs) >= 0.8 * len(ref), f"only {len(pairs)} of {len(ref)} words align"
    end_med, end_p90, end_max = agreement(pairs, "end")
    start_med, start_p90, _ = agreement(pairs, "start")
    print(f"\n[{model}] vs whisper over {len(pairs)} words: |d end| median {end_med:.3f}s p90 {end_p90:.3f}s "
          f"max {end_max:.3f}s, |d start| median {start_med:.3f}s p90 {start_p90:.3f}s")
    assert end_med <= END_AGREEMENT[0] and end_p90 <= END_AGREEMENT[1] and end_max <= END_AGREEMENT[2]
    lim = START_AGREEMENT[model]
    assert start_med <= lim[0] and start_p90 <= lim[1]


@pytest.mark.parametrize("model", MODELS)
def test_http_loom_format(model, meta, flac_bytes):
    body = http_result(model, flac_bytes, "loom")
    assert set(body) == {"segments"}
    for s in body["segments"]:
        assert set(s) == {"text", "from", "to"}
        assert isinstance(s["from"], int) and isinstance(s["to"], int) and s["from"] <= s["to"]
        assert clip_of(meta, s["from"] / 1000) is not None


# ---------------------------------------------------------------------------
# Realtime
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("model", MODELS)
def test_realtime_quality_and_timecodes(model, meta, pcm):
    events = realtime_events(model, pcm)
    segments = rt_segments(events)
    assert_quality(meta, segments, f"{model} realtime")
    assert_timecodes(meta, segments, f"{model} realtime")
    dones = [e for e in events if e["type"] == "transcription.done"]
    assert [s for d in dones for s in d["segments"]] == segments  # done repeats exactly the segments


@pytest.mark.parametrize("model", MODELS)
def test_realtime_matches_http(model, meta, pcm, flac_bytes):
    """A stream and a file of the same audio give the same words at the same times."""
    rt = [w for s in rt_segments(realtime_events(model, pcm)) for w in s["words"]]
    http = http_result(model, flac_bytes)["words"]
    pairs = align(rt, http)
    assert len(pairs) >= 0.85 * len(http)
    med, p90, _ = agreement(pairs, "end")
    print(f"\n[{model}] realtime vs http: {len(pairs)}/{len(http)} words, |d end| median {med:.3f}s p90 {p90:.3f}s")
    assert med <= 0.10 and p90 <= 0.30


@pytest.mark.parametrize("model", MODELS)
def test_realtime_latency(model, meta, pcm):
    """Final segments arrive soon after each clip is spoken; Voxtral shows text before it ends."""
    events = realtime_events(model, pcm)
    lines = []
    for c in meta["clips"]:
        segs = [e for e in events if e["type"] == "transcription.segment" and clip_of(meta, e["segment"]["end"]) == c["index"]]
        assert segs, f"clip {c['index']}: no segment"
        spoken_end = max(s["segment"]["end"] for s in segs)  # session time the last word ended
        sent_at = spoken_end / PACE  # wall time that audio had been sent
        latency = segs[-1]["_t"] - sent_at
        lines.append(f"clip {c['index']}: last final segment {latency:+.2f}s after its speech was sent")
        assert latency <= FINAL_LATENCY_S, lines[-1]
        if model == "voxtral":
            deltas = [e for e in events if e["type"] == "transcription.delta" and clip_of(meta, e["end"]) == c["index"]]
            assert deltas, f"clip {c['index']}: no deltas"
            first_wall = deltas[0]["_t"]
            lines.append(f"clip {c['index']}: first text {first_wall * PACE - c['start']:.2f}s into the clip, "
                         f"clip ends at {c['end'] - c['start']:.2f}s")
            assert first_wall < c["end"] / PACE, "voxtral produced no text while the clip was being spoken"
            lag = statistics.median(e["_t"] - e["end"] / PACE for e in deltas)
            lines.append(f"clip {c['index']}: median delta lag {lag:.2f}s behind the word's end")
            assert lag <= 1.5
    partials = [e for e in events if e["type"] == "transcription.partial"]
    if model != "voxtral":
        lines.append(f"{len(partials)} interim partials")
    print(f"\n[{model}] " + "\n  ".join(lines))
