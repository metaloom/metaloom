"""
The backend registry: model name -> Backend instance, one instance per process.

A request names its backend with `model`. Besides the short names a few aliases are accepted
(the HF repo id of the WeSpeaker and SpeechBrain checkpoints, the ReDimNet2 weight name), so a
caller that copies a model id from /v1/models lands on the right backend.
"""

from __future__ import annotations

from typing import Dict, Optional

from .. import config
from .base import Backend

_NAMES = ("redimnet2", "wespeaker", "speechbrain")

_instances: Dict[str, Backend] = {}


def _create(name: str) -> Backend:
    if name == "redimnet2":
        from .redimnet2 import ReDimNet2Backend

        return ReDimNet2Backend()
    if name == "wespeaker":
        from .wespeaker import WeSpeakerBackend

        return WeSpeakerBackend()
    if name == "speechbrain":
        from .speechbrain import SpeechBrainBackend

        return SpeechBrainBackend()
    raise KeyError(name)


def register(name: str, backend: Backend):
    """Install a backend under `name` - the tests use this to swap in a fake."""
    _instances[name] = backend


def resolve(model: Optional[str]) -> str:
    """Map a request's `model` field to a backend name, or raise KeyError."""
    key = (model or config.DEFAULT_MODEL).strip()
    low = key.lower()
    if low in _instances or low in config.BACKENDS:
        return low
    # Full model ids, as /v1/models prints them.
    for name, backend in available().items():
        if key == backend.model_id or low == backend.model_id.lower():
            return name
    if low.startswith("wespeaker/") or low.startswith("pyannote/wespeaker"):
        return "wespeaker"
    if low.startswith("speechbrain/"):
        return "speechbrain"
    if low.startswith("redimnet"):
        return "redimnet2"
    raise KeyError(model)


def get(model: Optional[str]) -> Backend:
    name = resolve(model)
    if name not in _instances:
        _instances[name] = _create(name)
    return _instances[name]


def available() -> Dict[str, Backend]:
    """Every enabled (or registered) backend, instantiated but not necessarily loaded."""
    for name in config.BACKENDS:
        if name not in _instances and name in _NAMES:
            _instances[name] = _create(name)
    return dict(_instances)
