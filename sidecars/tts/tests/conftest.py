"""
Shared fixtures — no model, no GPU, no network.

Every test replaces an engine at the ONE seam the chunk loop calls (`Engine.generate`, or
`_generate` when the loop itself is under test), so nothing here imports torch, transformers,
snac, chatterbox or kokoro_onnx. That is the point: the request contract, the chunking, the
containers and the whole audio chain stay checkable on a laptop with no GPU.

Run:  .venv/bin/python -m pytest tests -q
"""

import sys
from pathlib import Path

# The sidecar is not an installed package: make `server` and `tts` importable from the tests.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import numpy as np  # noqa: E402
import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import server  # noqa: E402
from tts import config, engines  # noqa: E402
from tts.engines.base import Engine  # noqa: E402


@pytest.fixture(autouse=True)
def fresh_registry():
    """A registry of its own for every test.

    An engine instance caches what it has loaded and (for Kokoro) its voice list, and the tests
    monkeypatch `config` underneath it — so instances must not leak from one test into the next.
    A process never does this; see `tts/engines/base.py` rule 1.
    """
    engines.reset()
    yield
    engines.reset()


@pytest.fixture
def client():
    return TestClient(server.app)


@pytest.fixture
def tone():
    """One second of 440 Hz at 24 kHz — a stand-in for a synthesised waveform."""
    t = np.linspace(0, 1.0, 24000, endpoint=False)
    return (0.3 * np.sin(2 * np.pi * 440 * t)).astype("float32"), 24000


class FakeEngine(Engine):
    """An engine that answers with a fixed waveform and records what it was asked for.

    Registered under a real engine's NAME, so `TTS_ENGINE_<LANG>` still selects it and the
    routes, the chunk loop, the container and the post-production chain under test are the real
    ones.
    """

    def __init__(self, name, tone, languages=("de", "en"), chunk_chars=0, min_chunk_chars=0,
                 pieces=1):
        super().__init__()
        self.name = name
        self.languages = languages
        self.backend = "fake"
        self._tone = tone
        self._chunk_chars = chunk_chars
        self._min_chunk_chars = min_chunk_chars
        self._pieces = pieces
        self.incremental = pieces > 1
        #: `(text, voice)` per generation, in order.
        self.calls = []

    @property
    def repo(self):
        return f"fake/{self.name}"

    @property
    def chunk_chars(self):
        return self._chunk_chars

    @property
    def min_chunk_chars(self):
        return self._min_chunk_chars

    @property
    def loaded(self):
        return True

    def voices(self):
        return ["Jakob", "Sophie", "af_heart"]

    def _generate(self, text, voice):
        self.calls.append((text, voice))
        return self._tone

    def stream(self, text, voice):
        """`pieces` equal slices of one generation, so the incremental path has a stand-in."""
        if self._pieces <= 1:
            yield self.generate(text, voice)
            return
        waveform, rate = self.generate(text, voice)
        step = len(waveform) // self._pieces
        for i in range(self._pieces):
            end = len(waveform) if i == self._pieces - 1 else (i + 1) * step
            yield waveform[i * step:end], rate


@pytest.fixture
def engine_factory():
    """The `FakeEngine` class itself, for a test that needs its own shape."""
    return FakeEngine


@pytest.fixture
def spoken(tone):
    """Every bound engine replaced by `FakeEngine`; returns the list of recorded calls.

    One list for both languages, because a route test only wants to know that the text reached an
    engine at all.
    """
    calls = []
    for name in dict.fromkeys(config.ENGINE_BY_LANG.values()):
        fake = FakeEngine(name, tone)
        fake.calls = calls
        engines.register(name, fake)
    return calls


@pytest.fixture
def chunked(tone, monkeypatch):
    """A German engine with a 50-character budget, so the chunk loop runs for real."""
    fake = FakeEngine("orpheus", tone, languages=("de",), chunk_chars=50)
    engines.register("orpheus", fake)
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "orpheus")
    return fake


@pytest.fixture
def no_builtin_voices(monkeypatch):
    """Switch the shipped Kartoffelbox clips off, so a test's voice list is exactly its own."""
    monkeypatch.setattr(config, "KARTOFFELBOX_BUILTIN_VOICES_DIR", "")


@pytest.fixture
def kartoffelbox(monkeypatch, no_builtin_voices):
    """Bind German to Kartoffelbox with an empty voices directory."""
    monkeypatch.setitem(config.ENGINE_BY_LANG, "de", "kartoffelbox")
    return monkeypatch


@pytest.fixture
def voices_dir(kartoffelbox, tmp_path):
    """A voices directory holding two reference clips, plus a file that is not one."""
    for name in ("Jakob.wav", "Sophie.flac", "notes.txt"):
        (tmp_path / name).write_bytes(b"")
    kartoffelbox.setattr(config, "KARTOFFELBOX_VOICES_DIR", str(tmp_path))
    return tmp_path
