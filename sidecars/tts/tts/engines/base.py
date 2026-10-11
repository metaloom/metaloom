"""
What an engine is: something that turns text and a voice name into one waveform.

    engine.generate("Guten Tag.", "Jakob")  ->  (float32 1-D waveform, sample rate)

Everything else about an engine is declared rather than discovered, because the catalogue routes
(`/v1/models`, `/voices`) must answer "what can you speak with" **without loading a model** — a
6 GB download is not an acceptable cost for a readiness probe.

Adding an engine is therefore a subclass with four or five lines of declaration, `_generate`, and
one entry in the registry (`tts/engines/__init__.py`). The chunk loop, the gap welding, the
container, the post-production chain and both routes come from the shared code and do not learn
that a new engine exists.

Three rules a subclass must not break:

1. **Models load lazily into instance state and are never unloaded.** First request pays the
   load; every later one is warm. A loader assigns its handle **last**, so a failed download
   leaves it unset and the next request retries, rather than a half-built model answering for the
   life of the process.
2. **A recording that is too short, silent or truncated is an error, not a success.** Returning a
   0-byte or silent response to the `TtsNode` would look like success and be impossible to
   diagnose. `generate` enforces the duration floor; the ceiling is warned about here and more
   precisely by the engines that can detect it exactly.
3. **`finish` is where a per-generation audio stage belongs** (dispersion, edge trim, headroom) —
   never inside `_generate`, so the duration checks above see what the engine actually produced.
"""

import logging
import threading
from typing import Iterator, List, Tuple

import numpy as np

from .. import config

logger = logging.getLogger("tts.engine")


class Engine:
    """One speech model, per process, loaded on first use."""

    #: Registry key, and what `TTS_ENGINE_<LANG>` is set to.
    name: str = ""
    #: The languages this engine answers for. The first one names it in the catalogue.
    languages: Tuple[str, ...] = ()
    #: How it runs — `transformers`, `vllm`, `chatterbox`, `onnx`. Reported, never branched on here.
    backend: str = ""
    #: The rate this engine's waveforms come at. DECLARED, not read from a loaded model: the
    #: catalogue needs it without a load. The waveform that actually leaves still carries the
    #: rate the model reported, and the container uses that one.
    sample_rate: int = 24000
    #: Licence of the weights the default configuration loads, for the catalogue and the README.
    licence: str = ""
    commercial: bool = False
    #: Text budget for ONE generation; 0 means "no ceiling, do not split a file".
    chunk_chars: int = 0
    #: A trailing chunk shorter than this is welded onto its predecessor; 0 turns that off.
    min_chunk_chars: int = 0
    #: Longest audio one generation can produce, in seconds; 0 means no known ceiling. Used to
    #: recognise a generation that ran into it — the audio itself will not say so.
    max_seconds: float = 0.0
    #: Shortest audio that can plausibly be speech for a whole chunk, in seconds. 0 disables the
    #: floor, which is right for an engine that legitimately answers "Ja." with 0.4 s; a cloning
    #: engine that fails by returning a fragment needs one.
    min_seconds: float = 0.0
    #: True only for an engine that can stream audio from INSIDE one generation.
    incremental: bool = False

    def __init__(self):
        #: Serialises inference. One sidecar holds one model on one device, so two requests
        #: racing only make both slower — and the model's own state (a cloning engine's
        #: conditioning) is not re-entrant. Held for one generation, never for a whole stream.
        self.lock = threading.Lock()
        self._load_lock = threading.Lock()

    # -- what the catalogue says ------------------------------------------------
    @property
    def id(self) -> str:
        return f"{self.name}-{self.languages[0]}" if self.languages else self.name

    @property
    def repo(self) -> str:
        """The checkpoint or model file this engine would use."""
        raise NotImplementedError

    @property
    def loaded(self) -> bool:
        """True once the weights are in memory. The readiness `/health` cannot give."""
        return False

    @property
    def available(self) -> bool:
        """True when this engine could serve a request — weights present, voices installed."""
        return True

    def voices(self) -> List[str]:
        """The voices this engine can speak with, derived from configuration. Loads nothing."""
        return []

    def startup_note(self) -> str:
        """One line of engine-specific diagnostics for the startup log, or "" for none."""
        return ""

    def info(self) -> dict:
        return {
            "id": self.id,
            "engine": self.name,
            "repo": self.repo,
            "backend": self.backend,
            "languages": list(self.languages),
            "voices": self.voices(),
            "sample_rate": self.sample_rate,
            "device": self.device,
            "licence": self.licence,
            "commercial": self.commercial,
            "loaded": self.loaded,
            "available": self.available,
            "chunk_chars": self.chunk_chars,
            "max_seconds": round(self.max_seconds, 2) if self.max_seconds else None,
            # Whether THIS engine streams from inside one generation. Reporting a flat `true`
            # because the switch is on would be the same kind of lie as a `format` field that is
            # accepted and ignored: an engine that returns a whole waveform per call cannot.
            "incremental": self.incremental and config.TTS_STREAM_INCREMENTAL,
        }

    @property
    def device(self) -> str:
        return config.DEVICE

    # -- the work ---------------------------------------------------------------
    def generate(self, text: str, voice: str):
        """ONE generation of at most `chunk_chars` of text. Returns `(waveform, rate)`.

        The seam the chunk loop calls and the one a test replaces. Subclasses implement
        `_generate`; this adds the lock, the shape and duration checks, and `finish`.
        """
        with self.lock:
            waveform, rate = self._generate(text, voice)

        waveform = np.asarray(waveform, dtype=np.float32)
        # A `(1, N)` array reaching the WAV writer is written as interleaved stereo, which sounds
        # wrong rather than failing — so the shape is checked, not squeezed and hoped for.
        if waveform.ndim != 1:
            raise RuntimeError(f"{self.name} returned a {waveform.shape} array, not 1-D audio")
        rate = int(rate)
        if rate <= 0:
            raise RuntimeError(f"{self.name} returned a sample rate of {rate}")
        # Rule 2: a silent success is the worst outcome available here. No audio at all is always
        # an error; how short counts as "not speech" is per engine, because "Ja." really is 0.4 s.
        if waveform.size == 0 or waveform.size < rate * self.min_seconds:
            raise RuntimeError(
                f"{self.name} returned {waveform.size} samples ({waveform.size / rate:.3f} s) "
                f"for {text[:80]!r} — too short to be speech")
        if self.max_seconds and waveform.size / rate >= self.max_seconds - 0.5:
            logger.warning("%s hit its ~%.1f s generation ceiling — this chunk is probably cut "
                           "off mid-word: %r", self.name, self.max_seconds, text[:80])
        return self.finish(waveform, rate), rate

    def _generate(self, text: str, voice: str):
        """The engine's own synthesis. Returns `(waveform, rate)`."""
        raise NotImplementedError

    def finish(self, waveform, rate: int):
        """Per-generation audio stages. Identity unless an engine needs one."""
        return waveform

    def stream(self, text: str, voice: str) -> Iterator[Tuple[np.ndarray, int]]:
        """One generation, as `(waveform, rate)` pieces.

        The default is a single piece — the whole generation — which still streams at chunk
        granularity, because the caller hears chunk 1 while chunk 2 is being made. An engine that
        can do better (Orpheus, through the SNAC decode) overrides this and sets `incremental`.
        """
        yield self.generate(text, voice)
