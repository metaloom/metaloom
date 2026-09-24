"""
Every setting of the sidecar, read once from the environment at import time.

All variables are ASR_-prefixed and all of them are read here, by Python - including host and
port, so `python server.py`, `run.sh` and the container agree (spec/sidecars/SIDECARS.md lists
the unprefixed DEVICE and the run.sh-only *_PORT as hazards of the older sidecars).
"""

import os


def _env(name: str, default: str) -> str:
    value = os.environ.get(name)
    return default if value is None or value == "" else value


def _flag(name: str, default: bool) -> bool:
    return _env(name, "1" if default else "0").lower() in ("1", "true", "yes", "on")


HOST = _env("ASR_HOST", "0.0.0.0")
PORT = int(_env("ASR_PORT", "9140"))

# Which backends this process offers, and the one a request without `model` gets.
BACKENDS = [b.strip() for b in _env("ASR_BACKENDS", "whisper,parakeet,voxtral").split(",") if b.strip()]
DEFAULT_MODEL = _env("ASR_DEFAULT_MODEL", "whisper")
# Decode language for the backends that take one (Whisper). "auto" lets Whisper detect it.
# Parakeet and Voxtral always detect the language themselves.
LANGUAGE = _env("ASR_LANGUAGE", "de")
# Comma-separated backends to load at startup rather than on first use.
PRELOAD = [b.strip() for b in _env("ASR_PRELOAD", "").split(",") if b.strip()]

# --- whisper ---
WHISPER_MODEL = _env("ASR_WHISPER_MODEL", "large-v3-turbo")
WHISPER_DEVICE = _env("ASR_WHISPER_DEVICE", "auto")
WHISPER_COMPUTE_TYPE = _env("ASR_WHISPER_COMPUTE_TYPE", "")
WHISPER_BEAM_SIZE = int(_env("ASR_WHISPER_BEAM_SIZE", "5"))

# --- parakeet ---
PARAKEET_MODEL = _env("ASR_PARAKEET_MODEL", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3")
PARAKEET_THREADS = int(_env("ASR_PARAKEET_THREADS", "4"))
PARAKEET_PROVIDER = _env("ASR_PARAKEET_PROVIDER", "cpu")

# --- voxtral ---
VOXTRAL_MODEL = _env("ASR_VOXTRAL_MODEL", "mistralai/Voxtral-Mini-4B-Realtime-2602")
VOXTRAL_DEVICE = _env("ASR_VOXTRAL_DEVICE", "cuda")
VOXTRAL_DTYPE = _env("ASR_VOXTRAL_DTYPE", "bfloat16")
# Concurrent Voxtral realtime streams. Each one holds the model for as long as its speaker talks.
VOXTRAL_MAX_STREAMS = int(_env("ASR_VOXTRAL_MAX_STREAMS", "1"))

# --- realtime sessions ---
MAX_SESSIONS = int(_env("ASR_MAX_SESSIONS", "8"))
# "server_vad" cuts utterances on pauses; "none" leaves it to input_audio_buffer.commit.
TURN_DETECTION = _env("ASR_TURN_DETECTION", "server_vad")
SILENCE_MS = int(_env("ASR_SILENCE_MS", "600"))
MAX_UTTERANCE_S = float(_env("ASR_MAX_UTTERANCE_S", "20"))
# How often Whisper/Parakeet re-decode the open utterance for interim text. 0 turns it off.
PARTIAL_INTERVAL_MS = int(_env("ASR_PARTIAL_INTERVAL_MS", "1000"))

# --- HTTP uploads ---
MAX_UPLOAD_MB = int(_env("ASR_MAX_UPLOAD_MB", "512"))
