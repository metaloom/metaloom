"""
The engine registry: name -> `Engine`, one instance per process, created on first use.

**Adding a model is three things**, and none of them is in `server.py`:

1. a module here with an `Engine` subclass (declare the languages, the chunk budget and the
   ceiling, implement `_generate`, and `finish` if it needs a per-generation audio stage),
2. its name in `_NAMES` and one branch in `_create` — imported inside that branch, so a process
   that does not use the engine never imports its dependencies,
3. optionally a `TTS_ENGINE_<LANG>` default in `config.ENGINE_BY_LANG`.

Everything else — the request contract, the chunk loop, the container, the post-production chain,
`/v1/models`, `/voices` — comes from the shared code and does not change.

**The engine is bound to the PROCESS, not to the request.** `TTS_ENGINE_DE` is read from the
environment; there is no `model` field on the request and there must not be one. Two reasons, both
load-bearing: the downstream `TtsClient` sends only `{text, lang, voice, format}`, so the wire
stays unchanged when a deployment switches engine; and only one engine per language may be
resident, because two multi-GB checkpoints on one GPU is a memory profile nobody chose and there
is no unload path. It follows that the catalogue lists the **bound** engine only — never both
German ones.
"""

import logging
from typing import Dict, List, Tuple

from .. import config
from .base import Engine

logger = logging.getLogger("tts.engines")

#: Every engine this package knows how to build.
_NAMES = ("orpheus", "kartoffelbox", "kokoro")

#: What a language falls back to when `TTS_ENGINE_<LANG>` names something unknown.
_DEFAULTS = {"de": "orpheus", "en": "kokoro"}

_instances: Dict[str, Engine] = {}


def _create(name: str) -> Engine:
    if name == "orpheus":
        from .orpheus import OrpheusEngine

        return OrpheusEngine()
    if name == "kartoffelbox":
        from .kartoffelbox import KartoffelboxEngine

        return KartoffelboxEngine()
    if name == "kokoro":
        from .kokoro import KokoroEngine

        return KokoroEngine()
    raise KeyError(name)


def register(name: str, engine: Engine):
    """Install an engine under `name` — the tests use this to swap in a fake."""
    _instances[name] = engine


def reset():
    """Drop every instance. Tests only; a process never unloads an engine (see `base`)."""
    _instances.clear()


def get(name: str) -> Engine:
    """The one instance of `name`, created (not loaded) on first use."""
    key = (name or "").strip().lower()
    if key not in _instances:
        _instances[key] = _create(key)
    return _instances[key]


def engine_name_for(lang: str) -> str:
    """The engine name bound to `lang`, falling back loudly when it is not one we know.

    A typo must not be silent here: unlike a wrong backend, which only moves the work to another
    machine, a wrong engine name would make the process speak in a different voice than the
    deployment asked for.
    """
    name = config.ENGINE_BY_LANG[lang]
    if name in _instances or name in _NAMES:
        return name
    fallback = _DEFAULTS.get(lang, "")
    logger.error("TTS_ENGINE_%s=%r is not one of %s — falling back to %r",
                 lang.upper(), name, ", ".join(_NAMES), fallback)
    config.ENGINE_BY_LANG[lang] = fallback
    return fallback


def languages() -> Tuple[str, ...]:
    """The language prefixes this process answers for, longest first.

    Longest first so a future `de-ch` engine wins over `de` for `de-CH-x-whatever`.
    """
    return tuple(sorted(config.ENGINE_BY_LANG, key=len, reverse=True))


def resolve_language(lang: str) -> str:
    """`de-AT` -> `de`. Raises KeyError when no bound engine claims the language.

    Prefix matching, case-insensitively, because callers send `de`, `de-DE`, `en-US` and the
    `TtsNode`'s `language` option is free text.
    """
    wanted = (lang or "").strip().lower()
    for key in languages():
        if wanted.startswith(key):
            return key
    raise KeyError(lang)


def for_language(lang: str) -> Engine:
    """The engine that speaks `lang` in this process. Raises KeyError for anything else."""
    return get(engine_name_for(resolve_language(lang)))


def bound() -> List[Engine]:
    """One engine per bound language, in the order `config.ENGINE_BY_LANG` declares them.

    Deduplicated, so an engine that serves two languages is listed once. Creating an instance
    loads nothing, which is what lets the catalogue routes answer without a 6 GB download.
    """
    engines: List[Engine] = []
    for lang in config.ENGINE_BY_LANG:
        engine = get(engine_name_for(lang))
        if engine not in engines:
            engines.append(engine)
    return engines


def log_startup():
    """Say what this process resolved to, once, at startup.

    Worth the lines: the two failures this prevents are both silent. A Kartoffelbox process with
    no clip directory answers every request in a voice nobody selected, and a relative path
    resolved against the wrong working directory is how that happens.
    """
    for lang in config.ENGINE_BY_LANG:
        engine = get(engine_name_for(lang))
        voices = engine.voices()
        logger.info("%s -> %s (%s, backend=%s, device=%s) with %d voice(s)%s",
                    lang, engine.name, engine.repo, engine.backend, engine.device, len(voices),
                    ": " + ", ".join(voices[:8]) if voices else "")
        note = engine.startup_note()
        if note:
            logger.info("   %s", note)
        if not engine.available:
            logger.warning("engine %s for %s is not ready to serve: %s", engine.name, lang,
                           "no reference clips installed — every request will use the "
                           "checkpoint's default conditioning and `voice` will select nothing"
                           if engine.name == "kartoffelbox" else
                           "its weights are missing; run setup.sh")
