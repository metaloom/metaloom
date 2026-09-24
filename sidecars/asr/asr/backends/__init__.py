"""
The backend registry: model name -> Backend instance, one instance per process.

A request names its backend with `model`. Besides the short names, the full model ids and a few
obvious aliases are accepted, so an OpenAI client that sends `whisper-1` or a vLLM client that
sends the Voxtral repo id lands on the right backend.
"""

from __future__ import annotations

from typing import Dict, Optional

from .. import config
from .base import Backend

_ALIASES = {
    "whisper": "whisper",
    "whisper-1": "whisper",
    "large-v3-turbo": "whisper",
    "parakeet": "parakeet",
    "parakeet-tdt-v3": "parakeet",
    "nvidia/parakeet-tdt-0.6b-v3": "parakeet",
    "voxtral": "voxtral",
    "voxtral-realtime": "voxtral",
    "mistralai/voxtral-mini-4b-realtime-2602": "voxtral",
}

_instances: Dict[str, Backend] = {}


def _create(name: str) -> Backend:
    if name == "whisper":
        from .whisper import WhisperBackend

        return WhisperBackend()
    if name == "parakeet":
        from .parakeet import ParakeetBackend

        return ParakeetBackend()
    if name == "voxtral":
        from .voxtral import VoxtralBackend

        return VoxtralBackend()
    raise KeyError(name)


def register(name: str, backend: Backend):
    """Install a backend under `name` - the tests use this to swap in a fake."""
    _instances[name] = backend


def resolve(model: Optional[str]) -> str:
    """Map a request's `model` field to a backend name, or raise KeyError."""
    key = (model or config.DEFAULT_MODEL).strip().lower()
    key = _ALIASES.get(key, key)
    for name in ("whisper", "parakeet", "voxtral"):
        if key == getattr(config, f"{name.upper()}_MODEL").lower():
            key = name
    if key in _instances or key in config.BACKENDS:
        return key
    raise KeyError(model)


def get(model: Optional[str]) -> Backend:
    name = resolve(model)
    if name not in _instances:
        _instances[name] = _create(name)
    return _instances[name]


def available() -> Dict[str, Backend]:
    """Every enabled (or registered) backend, instantiated but not necessarily loaded."""
    for name in config.BACKENDS:
        if name not in _instances:
            _instances[name] = _create(name)
    return dict(_instances)
