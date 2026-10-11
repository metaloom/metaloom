"""
Every setting of the TTS sidecar, read once from the environment at import time.

Changing a variable requires a process restart — the house rule for all Cortex sidecars
(spec/sidecars/SIDECARS.md). Two hazards that spec lists for the older sidecars are fixed here:

* `TTS_HOST` / `TTS_PORT` are read by **Python**, so `run.sh`, `python server.py` and the
  container cannot disagree about the port.
* the device is `TTS_DEVICE`; the unprefixed, shared `DEVICE` is still honoured as a fallback
  so an existing deployment keeps working.

Relative model/voice paths resolve against the **sidecar directory**, not the working
directory. A uvicorn started from elsewhere used to find no Kokoro weights (503) and no
Kartoffelbox clips — the latter silently, with every request falling back to the checkpoint's
default voice and only the sound saying so.
"""

import logging
import os

logger = logging.getLogger("tts.config")

#: `sidecars/tts` — the directory `server.py` lives in, and what a relative path means.
SIDECAR_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _env(name: str, default: str) -> str:
    value = os.environ.get(name)
    return default if value is None or value == "" else value


def _flag(name: str, default: bool) -> bool:
    return _env(name, "1" if default else "0").strip().lower() not in ("0", "false", "no", "off")


def _path(value: str) -> str:
    """An absolute path for `value`, resolved against `SIDECAR_DIR` when it is relative."""
    if not value:
        return value
    return value if os.path.isabs(value) else os.path.join(SIDECAR_DIR, value)


HOST = _env("TTS_HOST", "0.0.0.0")
PORT = int(_env("TTS_PORT", "9100"))


def resolve_device() -> str:
    """The torch device for the engines that have one. `auto` asks torch."""
    configured = _env("TTS_DEVICE", _env("DEVICE", "auto"))
    if configured != "auto":
        return configured
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:  # noqa: BLE001 — no torch, no cuda
        return "cpu"


DEVICE = resolve_device()

# --------------------------------------------------------------------------- #
# Which engine speaks which language
# --------------------------------------------------------------------------- #
# The engine is bound to the PROCESS, not to the request: there is deliberately no `model`
# field on `TTSRequest` and there must not be one. Two reasons, both load-bearing. The
# downstream `TtsClient` sends only `{text, lang, voice, format}`, so the wire stays unchanged
# when a deployment switches engine; and only one engine per language may be resident, because
# two multi-GB checkpoints on one GPU is a memory profile nobody chose — there is no unload path.
#
# Adding a language is this dict plus an engine module; see `tts/engines/__init__.py`.
ENGINE_BY_LANG = {
    # German: `orpheus` (Orpheus-3B/Kartoffel, SNAC tokens decoded here) or `kartoffelbox`
    # (a Chatterbox fine-tune that clones a reference clip). Kartoffelbox is the better voice
    # and needs `requirements-kartoffelbox.txt`; the default stays Orpheus because that is
    # what `setup.sh` installs. See spec/sidecars/TTS_SIDECAR.md §4.
    "de": _env("TTS_ENGINE_DE", "orpheus").strip().lower(),
    "en": _env("TTS_ENGINE_EN", "kokoro").strip().lower(),
}

# --------------------------------------------------------------------------- #
# Orpheus / Kartoffel (German engine A)
# --------------------------------------------------------------------------- #
ORPHEUS_BACKEND = _env("ORPHEUS_BACKEND", "transformers").strip().lower()
ORPHEUS_BACKENDS = ("transformers", "vllm")
ORPHEUS_LLM_ENDPOINT = _env("ORPHEUS_LLM_ENDPOINT", "http://localhost:8000")
ORPHEUS_REPO_DE = _env("ORPHEUS_REPO_DE", "SebastianBodza/Kartoffel_Orpheus-3B_german_natural-v0.1")
#: The id vLLM announces, when it differs from the HF repo (e.g. a local path).
ORPHEUS_REPO_DE_MODEL = _env("ORPHEUS_REPO_DE_MODEL", ORPHEUS_REPO_DE)
SNAC_REPO = _env("SNAC_REPO", "hubertsiuzdak/snac_24khz")

# --------------------------------------------------------------------------- #
# Kartoffelbox (German engine B)
# --------------------------------------------------------------------------- #
# SebastianBodza/Kartoffelbox-v0.1, a German fine-tune of ResembleAI's Chatterbox. The
# checkpoint ships ONLY the retrained T3 stage, so the load is "English base Chatterbox, then
# swap T3" rather than a single from_pretrained.
KARTOFFELBOX_REPO = _env("KARTOFFELBOX_REPO", "SebastianBodza/Kartoffelbox-v0.1")
KARTOFFELBOX_T3_FILE = _env("KARTOFFELBOX_T3_FILE", "t3_cfg.safetensors")
#: A deployment's own reference clips, one per voice, named after it: `Jakob.wav` -> `Jakob`.
KARTOFFELBOX_VOICES_DIR = _path(_env("KARTOFFELBOX_VOICES_DIR", "models/kartoffelbox-voices"))
#: The clips that ship WITH the sidecar (`voices/kartoffelbox/`): Jakob, Anton, Julian, Sophie,
#: Marie — synthetic takes rendered from the Kartoffel speakers of the same name by
#: `tools/make_reference_voices.py`, so no recording of a real person is committed. A clip of
#: the same name in `KARTOFFELBOX_VOICES_DIR` replaces one, new names there add to them, and an
#: empty mount therefore no longer means "no voices". Set to "" to switch the built-ins off.
KARTOFFELBOX_BUILTIN_VOICES_DIR = _path(_env("KARTOFFELBOX_BUILTIN_VOICES_DIR", "voices/kartoffelbox"))
#: Upstream's own generation defaults. Exposed, unlike Orpheus's, because `exaggeration` and
#: `cfg_weight` are the two knobs this model is actually steered with.
KARTOFFELBOX_EXAGGERATION = float(_env("KARTOFFELBOX_EXAGGERATION", "0.5"))
KARTOFFELBOX_CFG_WEIGHT = float(_env("KARTOFFELBOX_CFG_WEIGHT", "0.3"))
KARTOFFELBOX_TEMPERATURE = float(_env("KARTOFFELBOX_TEMPERATURE", "0.6"))
#: The speech level every reference clip is brought to before it is cloned. Chatterbox 0.1.7
#: reproduces the clip's level one-for-one (measured 2026-10-09: one clip at 0/-6/-12 dB gave
#: output at -15.6/-21.7/-27.4 dB RMS) and the clips the reference tool writes are Orpheus
#: output at ~-17 dB RMS — loud enough that the clone peaked past full scale on every loud
#: syllable. Leveling the clip fixes the cause; the peak stage is only the guard behind it.
KARTOFFELBOX_REF_RMS_DB = float(_env("KARTOFFELBOX_REF_RMS_DB", "-23"))
#: The clone copies the clip's DYNAMICS as well as its level: a clip whose loudest syllables
#: jump far above its normal speech is cloned into a voice that shouts single syllables, with
#: saturated harmonics — "übersteuert", and no peak stage can undo it because the waveform never
#: clips. Measured 2026-10-09: the Orpheus `Sophie` clip jumped 14.4 dB above its median speech
#: level (Jakob/Marie 8.8) and its clone 13.2 dB over four seeds; the same voice from an evened
#: clip 8.8 dB. Everything more than ..._EVEN_DB above the clip's median speech level is
#: therefore compressed by ..._EVEN_RATIO with a smooth gain envelope. RATIO=1 turns it off.
KARTOFFELBOX_REF_EVEN_DB = float(_env("KARTOFFELBOX_REF_EVEN_DB", "4"))
KARTOFFELBOX_REF_EVEN_RATIO = float(_env("KARTOFFELBOX_REF_EVEN_RATIO", "4"))
#: Phase dispersion of every Kartoffelbox generation. The HiFT vocoder puts a sharp
#: high-frequency tick into every pitch period: in the 2-8 kHz band its output has a waveform
#: kurtosis of 7-9 against 5 for human speech and for the clips it clones, in EVERY voice
#: (2026-10-09). A tick per period at pitch rate is heard as buzz, "pops", an overdriven voice —
#: and no spectrum-based measure sees it, because the magnitude spectrum is normal; only the
#: phase is. An all-pass whose group delay rises from 0 at ..._FROM_HZ to this many ms at
#: Nyquist spreads each tick out in time. 8 ms from 2 kHz: Jakob 9.0 -> 6.0, Sophie 7.5 -> 4.8,
#: Marie 6.6 -> 4.7, magnitude spectrum unchanged to 0.1 dB, DNSMOS unchanged (±0.03). 0 = off.
KARTOFFELBOX_DISPERSION_MS = float(_env("KARTOFFELBOX_DISPERSION_MS", "8"))
#: Where the group delay starts to rise; below it the phase is bit-identical. The tick lives in
#: 2-8 kHz, so starting at 2 kHz reaches it and spares the pitch and formant band (4 ms from DC
#: only got Sophie to 5.8 and Jakob to 7.3). 0 = from DC.
KARTOFFELBOX_DISPERSION_FROM_HZ = float(_env("KARTOFFELBOX_DISPERSION_FROM_HZ", "2000"))
#: What S3Gen's vocoder clamp is raised to. Upstream hard-clips at 0.99 — inside the engine,
#: before the server sees the waveform; anything comfortably above the loudest real peak hands
#: the job to the linear peak stage instead of squaring the peak off.
KARTOFFELBOX_VOCODER_LIMIT = float(_env("KARTOFFELBOX_VOCODER_LIMIT", "4.0"))
#: Its own chunk budget, NOT the Orpheus one: 500 there is derived from a token ceiling that
#: does not exist for Chatterbox, and 300 is what upstream's own demo truncates at. Note the
#: opposite failure mode — very SHORT inputs degrade — so this is a ceiling worth keeping high
#: enough that sentences are not split into fragments.
KARTOFFELBOX_CHUNK_CHARS = int(_env("KARTOFFELBOX_CHUNK_CHARS", "300"))
#: The floor that goes with it. A greedy split leaves the remainder in the last chunk, which can
#: be two words — precisely what this engine says badly. Below this many characters a trailing
#: chunk is welded onto its predecessor instead of being generated alone.
KARTOFFELBOX_MIN_CHUNK_CHARS = int(_env("KARTOFFELBOX_MIN_CHUNK_CHARS", "60"))
#: Chatterbox applies a `resemble-perth` watermark to every output and exposes no way to turn it
#: off; upstream's own escape hatch is to replace the watermarker object. Left ON by default — it
#: is the model's documented behaviour, and removing provenance marking from synthetic speech
#: should be a deliberate act.
KARTOFFELBOX_WATERMARK = _flag("KARTOFFELBOX_WATERMARK", True)

# --------------------------------------------------------------------------- #
# Kokoro (English)
# --------------------------------------------------------------------------- #
KOKORO_MODEL = _path(_env("KOKORO_MODEL", "models/kokoro-v1.0.onnx"))
KOKORO_VOICES = _path(_env("KOKORO_VOICES", "models/voices-v1.0.bin"))

# --------------------------------------------------------------------------- #
# Chunking, edges, headroom
# --------------------------------------------------------------------------- #
#: Orpheus generates ONE pass of at most `max_new_tokens` SNAC tokens, which caps the DURATION
#: of a generation (~49.9 s) and not its input length. Text that needs longer came back as a
#: file that ended mid-word, with no error anywhere. 500 characters is ~33 s at the 15 chars/s
#: German narration measures here, so it holds even when the model over-generates, which it
#: demonstrably does on short prompts.
TTS_CHUNK_CHARS = int(_env("TTS_CHUNK_CHARS", "500"))
#: Silence welded between two chunks. Without it the join is audible as a clipped word boundary;
#: a real speaker pauses between sentences anyway, and chunks only ever end at one.
TTS_CHUNK_GAP_MS = int(_env("TTS_CHUNK_GAP_MS", "180"))
#: Silence the ENGINE leaves at the edges of a generation — a different thing from the pause
#: above, and it has to go before anything is welded to it. Measured on Kartoffelbox: ~0.10 s of
#: near-silence before the first word and ~0.15 s after the last, per generation. Left in place
#: they land on either side of the weld, so a chunk seam became a 0.50-0.60 s hole where the
#: sentence pauses around it are 0.10-0.25 s. Trimmed, the seam is TTS_CHUNK_GAP_MS and nothing more.
TTS_EDGE_SILENCE_DB = float(_env("TTS_EDGE_SILENCE_DB", "-40"))
#: Kept on either side of the speech so the trim cannot bite into a soft onset or a fading final
#: consonant. Costs 60 ms of the seam back and buys the one failure worth avoiding here.
TTS_EDGE_KEEP_MS = int(_env("TTS_EDGE_KEEP_MS", "30"))
#: Output headroom. Nothing in an engine keeps its waveform inside full scale: Kartoffelbox
#: measured peaks of 1.15 (+1.2 dBFS) with 140-240 samples per 25 s recording past 1.0, which the
#: WAV writer hard-clips and an MP3 decoder clips on playback — audible as crackle on every loud
#: syllable (2026-10-09). MP3 itself overshoots its input by ~5 %, so the ceiling sits 1 dB down.
TTS_PEAK_CEILING_DB = float(_env("TTS_PEAK_CEILING_DB", "-1"))

# --------------------------------------------------------------------------- #
# Post-production
# --------------------------------------------------------------------------- #
#: The "radio/podcast" chain, one mode per process. `off` keeps the output exactly as the engine
#: made it. The other modes share one frame: loudness to a fixed working level, the mode's EQ and
#: compressor, loudness to TTS_POSTFX_LUFS, a look-ahead limiter, the peak stage.
TTS_POSTFX = _env("TTS_POSTFX", "off").strip().lower()
POSTFX_MODES = ("off", "level", "clean", "podcast", "broadcast")
#: -19 LUFS because the output is MONO: the podcast norm of -16 LUFS is for stereo, and a mono
#: file played on both speakers sounds ~3 dB louder. At -16 the limiter had to cut speech peaks by
#: up to 10.7 dB for 14-28 % of the time (2026-10-09); at -19 it is a safety net again.
TTS_POSTFX_LUFS = float(_env("TTS_POSTFX_LUFS", "-19"))
#: Ceiling of the look-ahead limiter. Under TTS_PEAK_CEILING_DB on purpose, so the peak stage
#: behind it has nothing left to do and never turns a whole file down.
TTS_POSTFX_CEILING_DB = float(_env("TTS_POSTFX_CEILING_DB", "-1.5"))

# --------------------------------------------------------------------------- #
# Streaming
# --------------------------------------------------------------------------- #
#: Streaming trades throughput for latency, so it gets its OWN chunk budget. The blocking
#: endpoint wants few, long generations because nothing is heard until the last one finishes; a
#: stream wants the FIRST one to finish soon, and every later chunk is generated while the caller
#: is still playing the one before it. 200 characters is ~13 s of audio and ~23 s of work at the
#: measured 1.7x real time, which is the time-to-first-audio when the incremental path is off.
TTS_STREAM_CHUNK_CHARS = int(_env("TTS_STREAM_CHUNK_CHARS", "200"))
#: Stream the audio of ONE generation as the model emits it instead of waiting for the generation
#: to end. This is what takes time-to-first-audio from "a chunk" to "a second", and it is the part
#: of this sidecar that has never run against a live GPU — so it is a switch, and turning it off
#: falls back to whole-generation pieces without losing the stream.
TTS_STREAM_INCREMENTAL = _flag("TTS_STREAM_INCREMENTAL", True)
#: How many SNAC frames the incremental decoder hands to SNAC at once. SNAC is a convolutional
#: decoder: a frame decoded alone does not sound like the same frame decoded among its neighbours,
#: so the window carries context and only its middle is ever emitted. Four is what the Orpheus
#: streaming implementations use; two is the floor the emission arithmetic is defined for.
TTS_STREAM_WINDOW_FRAMES = int(_env("TTS_STREAM_WINDOW_FRAMES", "4"))


# --------------------------------------------------------------------------- #
# Validation — a typo must never be silent
# --------------------------------------------------------------------------- #
def _validate():
    """Fall back loudly where a value is not one we know. Called once, at import."""
    global TTS_POSTFX, ORPHEUS_BACKEND
    if TTS_POSTFX not in POSTFX_MODES:
        logger.error("TTS_POSTFX=%r is not one of %s — falling back to 'off'",
                     TTS_POSTFX, ", ".join(POSTFX_MODES))
        TTS_POSTFX = "off"
    if ORPHEUS_BACKEND not in ORPHEUS_BACKENDS:
        # The old code's check was `== "vllm"`, so a typo fell through to the in-process
        # backend without a word — and then loaded 6 GB onto a machine that was meant to be
        # proxying. The engine name gets the same treatment in `tts/engines/__init__.py`.
        logger.error("ORPHEUS_BACKEND=%r is not one of %s — falling back to 'transformers'",
                     ORPHEUS_BACKEND, ", ".join(ORPHEUS_BACKENDS))
        ORPHEUS_BACKEND = "transformers"


_validate()
