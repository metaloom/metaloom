"""
Every setting of the sidecar, read once from the environment at import time.

All variables are VOICEPRINT_-prefixed and all of them are read here, by Python - including host
and port, so `python server.py`, `run.sh` and the container agree (spec/sidecars/SIDECARS.md lists
the unprefixed DEVICE and the run.sh-only *_PORT as hazards of the older sidecars).
"""

import os


def _env(name: str, default: str) -> str:
    value = os.environ.get(name)
    return default if value is None or value == "" else value


def _flag(name: str, default: bool) -> bool:
    return _env(name, "1" if default else "0").lower() in ("1", "true", "yes", "on")


HOST = _env("VOICEPRINT_HOST", "0.0.0.0")
PORT = int(_env("VOICEPRINT_PORT", "9150"))

# Which backends this process offers, and the one a request without `model` gets.
BACKENDS = [b.strip() for b in _env("VOICEPRINT_BACKENDS", "redimnet2,wespeaker,speechbrain").split(",") if b.strip()]
DEFAULT_MODEL = _env("VOICEPRINT_DEFAULT_MODEL", "redimnet2")
# Comma-separated backends to load at startup rather than on first use.
PRELOAD = [b.strip() for b in _env("VOICEPRINT_PRELOAD", "").split(",") if b.strip()]
# "auto" picks cuda when torch sees a GPU, else cpu. Every backend here runs on either.
DEVICE = _env("VOICEPRINT_DEVICE", "auto")

# --- redimnet2: ReDimNet2 (PalabraAI, MIT), the default ---
# Size b0..b6, training set vox2 | vb2+vox2_v0 | vb2+vox2+cnc2_v0, training stage ptn | lm | dis.
# Only the vox2 checkpoints are trained on commercially usable data; see README.md.
REDIMNET2_SIZE = _env("VOICEPRINT_REDIMNET2_SIZE", "b6")
REDIMNET2_DATASET = _env("VOICEPRINT_REDIMNET2_DATASET", "vox2")
REDIMNET2_TRAIN = _env("VOICEPRINT_REDIMNET2_TRAIN", "lm")
REDIMNET2_WEIGHTS_URL = _env("VOICEPRINT_REDIMNET2_WEIGHTS_URL",
                             "https://github.com/PalabraAI/redimnet2/releases/download/v1.0.0/{weight_name}")

# --- wespeaker: WeSpeaker ONNX exports from the Hugging Face hub (CC-BY-4.0, VoxCeleb2) ---
# Any repo that ships a `*.onnx` export taking [batch, frames, 80] fbank features works:
# Wespeaker/wespeaker-voxceleb-resnet34-LM (the pyannote 3.1 embedding, 6.6M params),
# Wespeaker/wespeaker-voxceleb-resnet293-LM (28.6M, better), Wespeaker/wespeaker-voxceleb-campplus-LM.
WESPEAKER_REPO = _env("VOICEPRINT_WESPEAKER_REPO", "Wespeaker/wespeaker-voxceleb-resnet34-LM")
WESPEAKER_ONNX = _env("VOICEPRINT_WESPEAKER_ONNX", "")  # empty: the first *.onnx in the repo

# --- speechbrain: ECAPA-TDNN (Apache-2.0, VoxCeleb1+2) ---
SPEECHBRAIN_REPO = _env("VOICEPRINT_SPEECHBRAIN_REPO", "speechbrain/spkrec-ecapa-voxceleb")

# --- windowing and clustering defaults (a request may override each) ---
WINDOW_S = float(_env("VOICEPRINT_WINDOW_S", "3.0"))
HOP_S = float(_env("VOICEPRINT_HOP_S", "1.5"))
MIN_SPEECH_S = float(_env("VOICEPRINT_MIN_SPEECH_S", "1.0"))
# Hard cap on the windows embedded per request: a 10 h recording at 1.5 s hop is 24k windows.
MAX_WINDOWS = int(_env("VOICEPRINT_MAX_WINDOWS", "4000"))
# Cosine similarity at or above which two windows belong to the same speaker. Empty: the backend's own default.
CLUSTER_THRESHOLD = _env("VOICEPRINT_CLUSTER_THRESHOLD", "")
BATCH = int(_env("VOICEPRINT_BATCH", "32"))

# --- HTTP uploads ---
MAX_UPLOAD_MB = int(_env("VOICEPRINT_MAX_UPLOAD_MB", "4096"))
