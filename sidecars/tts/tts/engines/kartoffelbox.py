"""
Kartoffelbox-v0.1 (German engine B) — a German fine-tune of ResembleAI's Chatterbox.

`SebastianBodza/Kartoffelbox-v0.1` shares NOTHING with Orpheus except the `(waveform, rate)`
signature: no SNAC, no token layout, no frame interleave, no vLLM backend. It returns a waveform
from `generate()` directly, and it is the **better German voice** — measured on identical text
(SQUIM PESQ 3.59 against 3.07, every voice improving over the Kartoffel speaker it clones) and
chosen by ear, 2026-10-09. The code default is still Orpheus because that is what `setup.sh`
installs; see spec/sidecars/TTS_SIDECAR.md §4.

Three consequences worth knowing before editing anything below:

* **It has no speakers.** Timbre comes from a reference clip, so the German voice list is "which
  clips are installed", not "which names the checkpoint knows". With no clip it falls back to the
  conditioning bundled in the checkpoint's `conds.pt`, and only the sound says so.
* **It copies the clip's level AND its dynamics.** A loud clip is cloned clipping; a bursty clip
  is cloned shouting single syllables, with saturated harmonics — "übersteuert" — and no peak
  stage can undo that, because the waveform never clips. Hence `audio.level_reference` /
  `audio.even_out_reference` on the way in, and `audio.disperse` on the way out.
* **It has a duration ceiling, and it is TIGHTER than Orpheus's.** `chatterbox-tts` hard-codes
  `max_new_tokens=1000` and S3 speech tokens run at 25/s, so one generation caps at ~40 s. The
  loop simply stops there if the sentence has not ended, which is the same silent mid-word
  truncation chunking exists to prevent. Pulling the other way: the model is known to produce
  unclear speech on very SHORT inputs, so the budget has a floor as well as a ceiling.

Install: `pip install --no-deps chatterbox-tts==0.1.7` then `requirements-kartoffelbox.txt`.
Upstream's own pins (`torch==2.6.0`, `transformers==5.2.0`, plus `gradio`) would downgrade torch
underneath Orpheus and put a web UI in a server image.
"""

import copy
import logging
import os
import tempfile
from typing import List

from .. import config
from ..audio import disperse, fit_peak, level_reference, trim_edges
from .base import Engine

logger = logging.getLogger("tts.engine.kartoffelbox")

#: Extensions accepted for a reference clip. libsndfile reads all of these; mp3 needs
#: libsndfile >= 1.1, the same floor MP3 *output* already documents.
VOICE_SUFFIXES = (".wav", ".flac", ".ogg", ".mp3")

#: `chatterbox-tts` hard-codes `max_new_tokens=1000` in its sampling loop and S3 speech tokens run
#: at 25/s, so ONE generation cannot exceed ~40 s however much text goes in.
MAX_SECONDS = 1000 / 25
#: Chatterbox's S3Gen vocoder runs at 24 kHz. A constant because the catalogue must answer
#: without loading the model and `model.sr` needs one; the waveform that leaves still carries the
#: rate the model reported.
SAMPLE_RATE = 24000


def clips_in(directory: str) -> dict:
    """`{lowercased stem: path}` for the reference clips in ONE directory.

    `{}` when it is absent — silently, because both clip directories are optional and the
    catalogue is asked on every request; `startup_note` says once which ones were used. A
    directory that EXISTS but cannot be read is a deployment error and is logged every time.
    """
    found: dict = {}
    if not directory or not os.path.isdir(directory):
        return found
    try:
        entries = sorted(os.listdir(directory))
    except OSError as e:  # noqa: BLE001
        logger.warning("cannot read Kartoffelbox voices from %s: %s", directory, e)
        return found
    for name in entries:
        stem, ext = os.path.splitext(name)
        if ext.lower() in VOICE_SUFFIXES and stem:
            found.setdefault(stem.lower(), os.path.join(directory, name))
    return found


def voice_files() -> dict:
    """`{lowercased voice name: path}` for every reference clip installed.

    The built-in clips, overlaid by `KARTOFFELBOX_VOICES_DIR` (same name -> the mounted clip
    wins). A directory listing, deliberately: it is what the catalogue answers with, and answering
    "what can you speak with" must not load a 3.2 GB model.
    """
    found = clips_in(config.KARTOFFELBOX_BUILTIN_VOICES_DIR)
    found.update(clips_in(config.KARTOFFELBOX_VOICES_DIR))
    return found


class KartoffelboxEngine(Engine):
    name = "kartoffelbox"
    languages = ("de",)
    backend = "chatterbox"
    sample_rate = SAMPLE_RATE
    licence = "gated (HF licence + HF_TOKEN); outputs carry a resemble-perth watermark by default"
    commercial = False
    max_seconds = MAX_SECONDS
    #: Well below anything a real chunk yields and well above a rounding artefact. This engine
    #: fails by returning a fragment rather than by raising, so the floor is the only thing that
    #: would notice.
    min_seconds = 0.5

    def __init__(self):
        super().__init__()
        self._model = None
        #: The conditioning bundled with the checkpoint, kept aside at load time.
        #: `prepare_conditionals` overwrites `model.conds` in place, so without this copy the
        #: first cloned voice would permanently destroy the fallback for every later request.
        self._default_conds = None
        #: Which voice `model.conds` currently holds, so a run of chunks in one voice encodes the
        #: reference clip ONCE. Passing `audio_prompt_path` to every `generate()` call re-runs the
        #: whole reference-encoding pipeline per chunk — wasted work, and a needless risk of the
        #: timbre drifting between chunks of one recording. A sentinel no voice name can equal.
        self._conds_voice = object()

    # -- catalogue --------------------------------------------------------------
    @property
    def repo(self) -> str:
        return config.KARTOFFELBOX_REPO

    @property
    def chunk_chars(self) -> int:
        return config.KARTOFFELBOX_CHUNK_CHARS

    @property
    def min_chunk_chars(self) -> int:
        return config.KARTOFFELBOX_MIN_CHUNK_CHARS

    @property
    def loaded(self) -> bool:
        return self._model is not None

    @property
    def available(self) -> bool:
        # No clips is not a broken engine — it still speaks in the checkpoint's own default voice —
        # but it does mean `voice` cannot select anything, so the catalogue says so.
        return bool(self.voices())

    def startup_note(self) -> str:
        """Which clip directories this process actually found — said once, at startup.

        Worth a line: a relative path resolved against the wrong working directory finds no
        clips, every request then falls back to the checkpoint's default conditioning, and the
        only symptom is that the voices sound wrong.
        """
        def state(path):
            return path if path and os.path.isdir(path) else "—"

        return (f"built-in clips from {state(config.KARTOFFELBOX_BUILTIN_VOICES_DIR)}, "
                f"mounted clips from {state(config.KARTOFFELBOX_VOICES_DIR)}")

    def voices(self) -> List[str]:
        """The installed reference clips, by filename stem.

        NOT cached, deliberately: a `listdir` costs nothing, and caching would let the catalogue
        drift from what `reference` actually resolves — so a clip dropped into a mounted volume
        shows up without a restart instead of being half-visible.
        """
        return sorted(os.path.splitext(os.path.basename(p))[0] for p in voice_files().values())

    # -- voices -----------------------------------------------------------------
    def reference(self, voice: str):
        """The reference clip for `voice`, or `None` to use the checkpoint's own conditioning.

        Matching is on the filename stem, case-insensitively, so `Jakob.wav` answers to `jakob`.
        A miss WARNS and returns None rather than raising: an unlisted name must not become "your
        speaker does not exist", and here there is always a usable default to fall back to.

        **A voice is never a path.** The name is matched against a *listing*, so `"../../etc/
        passwd"` simply misses. Never build the path by concatenating the field: this sidecar has
        no auth and sits next to its caller.
        """
        path = voice_files().get((voice or "").strip().lower())
        if path is None:
            logger.warning("no reference clip for German voice %r in %s — using the default "
                           "conditioning bundled with %s; the voice will not match the name",
                           voice,
                           f"{config.KARTOFFELBOX_BUILTIN_VOICES_DIR} + "
                           f"{config.KARTOFFELBOX_VOICES_DIR}",
                           config.KARTOFFELBOX_REPO)
        return path

    # -- loading ----------------------------------------------------------------
    def _load(self):
        """Load the English base Chatterbox, then swap in the German T3 stage.

        The checkpoint ships only `t3_cfg.safetensors` — the retrained text-to-speech-token stage.
        S3Gen (the vocoder), the voice encoder and the tokenizer come from the base model, so a
        single `from_pretrained` on the German repo is NOT how this loads.
        """
        if self._model is not None:
            return
        with self._load_lock:
            if self._model is not None:
                return
            from chatterbox.tts import ChatterboxTTS
            from huggingface_hub import hf_hub_download
            from safetensors.torch import load_file

            # ~3.2 GB from ResembleAI/chatterbox: the vocoder, voice encoder, tokenizer and the
            # default conditioning. Only T3 is replaced below, so this is not optional.
            logger.info("Loading base Chatterbox on %s ...", config.DEVICE)
            model = ChatterboxTTS.from_pretrained(device=config.DEVICE)
            logger.info("Applying German T3 weights from %s (%s) ...",
                        config.KARTOFFELBOX_REPO, config.KARTOFFELBOX_T3_FILE)
            # `device="cpu"` then load_state_dict: the tensors are moved by the module they land
            # in, and staging them on the GPU first would hold a second copy for no reason.
            path = hf_hub_download(repo_id=config.KARTOFFELBOX_REPO,
                                   filename=config.KARTOFFELBOX_T3_FILE)
            model.t3.load_state_dict(load_file(path, device="cpu"))

            if not config.KARTOFFELBOX_WATERMARK:
                # Chatterbox watermarks every output through `resemble-perth`, unconditionally and
                # with no parameter to turn it off — an STFT round-trip plus two resamples per
                # request. Swapping the watermarker is upstream's own supported shape for this.
                import perth

                model.watermarker = perth.DummyWatermarker()
                logger.info("Kartoffelbox watermarking disabled (KARTOFFELBOX_WATERMARK=0)")

            self._load_default_conds(model)
            self._lift_vocoder_clamp(model)
            self._default_conds = copy.deepcopy(getattr(model, "conds", None))
            # Assigned last, so a failed download leaves `None` and the next request retries.
            self._model = model
            logger.info("Kartoffelbox ready at %d Hz", getattr(model, "sr", SAMPLE_RATE))

    @staticmethod
    def _load_default_conds(model):
        """Prefer the German repo's own `conds.pt` as the default voice.

        The conditioning `from_pretrained` loaded belongs to the ENGLISH base checkpoint — only T3
        was replaced — so "no reference clip" would otherwise mean "a German T3 driven by an
        English speaker's conditioning". Tolerant of the exact `Conditionals.load` signature, which
        nothing we control pins: this is a quality improvement over the English default, not a
        requirement, so a version whose API differs must degrade to a WARNING rather than fail the
        whole load.
        """
        try:
            from chatterbox.tts import Conditionals
            from huggingface_hub import hf_hub_download

            conds_path = hf_hub_download(repo_id=config.KARTOFFELBOX_REPO, filename="conds.pt")
            try:
                conds = Conditionals.load(conds_path, map_location=config.DEVICE)
            except TypeError:
                conds = Conditionals.load(conds_path)
            model.conds = conds.to(config.DEVICE) if hasattr(conds, "to") else conds
            logger.info("Default conditioning taken from %s", config.KARTOFFELBOX_REPO)
        except Exception as e:  # noqa: BLE001 — an absent conds.pt must not fail the load
            logger.warning("%s has no usable conds.pt (%s) — the default voice falls back to the "
                           "ENGLISH base conditioning, which is a different speaker; install a "
                           "reference clip in %s to avoid relying on it",
                           config.KARTOFFELBOX_REPO, e, config.KARTOFFELBOX_VOICES_DIR)

    @staticmethod
    def _lift_vocoder_clamp(model) -> None:
        """Replace S3Gen's hard ±0.99 output clamp with the server's linear peak gain.

        The HiFT vocoder ends in `torch.clamp(x, -audio_limit, audio_limit)` with
        `audio_limit=0.99` — a hard clip INSIDE the engine, before the server sees the waveform.
        The default conditioning hits it on every take (13 samples flat at 0.99 in one sentence,
        2026-10-09), and leveling cannot help there because `conds.pt` has no clip to level.
        Lifting it lets the peak through intact so `fit_peak` turns the whole generation down
        instead of the vocoder squaring the peak off. Tolerant of a different chatterbox layout:
        absent attributes leave the clamp in place, which is only the old behaviour.
        """
        vocoder = getattr(getattr(model, "s3gen", None), "mel2wav", None)
        if vocoder is not None and hasattr(vocoder, "audio_limit"):
            vocoder.audio_limit = config.KARTOFFELBOX_VOCODER_LIMIT
            logger.info("Kartoffelbox vocoder clamp lifted to ±%.1f — peaks go to fit_peak",
                        config.KARTOFFELBOX_VOCODER_LIMIT)
        else:
            logger.warning("Kartoffelbox vocoder has no `s3gen.mel2wav.audio_limit` — its hard "
                           "±0.99 clamp stays in place")

    def _prepare(self, voice: str):
        """Point `model.conds` at `voice`, encoding its reference clip only when it changes."""
        key = (voice or "").strip().lower()
        if key == self._conds_voice:
            return
        path = self.reference(voice)
        if path is None:
            if self._default_conds is None:
                raise RuntimeError(
                    f"no reference clip for voice {voice!r} in "
                    f"{config.KARTOFFELBOX_VOICES_DIR} and {config.KARTOFFELBOX_REPO} carries no "
                    f"default conditioning")
            self._model.conds = copy.deepcopy(self._default_conds)
        else:
            logger.info("encoding reference clip %s for voice %r", path, voice)
            # The clip directory is usually mounted read-only, so the leveled copy is a temp file.
            with tempfile.TemporaryDirectory() as tmp:
                self._model.prepare_conditionals(level_reference(path, tmp),
                                                 exaggeration=config.KARTOFFELBOX_EXAGGERATION)
        self._conds_voice = key

    # -- generation -------------------------------------------------------------
    def _generate(self, text: str, voice: str):
        import torch

        self._load()
        self._prepare(voice)
        with torch.inference_mode():
            wav = self._model.generate(
                text,
                exaggeration=config.KARTOFFELBOX_EXAGGERATION,
                temperature=config.KARTOFFELBOX_TEMPERATURE,
                cfg_weight=config.KARTOFFELBOX_CFG_WEIGHT,
            )
        # `.float()` before `.numpy()` is not decoration: a half/bfloat16 tensor has no numpy
        # dtype and would fail deep inside the WAV writer.
        waveform = wav.squeeze().float().cpu().numpy()
        return waveform, int(getattr(self._model, "sr", SAMPLE_RATE))

    def finish(self, waveform, rate: int):
        """Dispersion, then the edge trim, then the headroom gain.

        After the duration checks in `Engine.generate`, on purpose: those are about what the
        ENGINE produced, and a trimmed waveform would make the too-short guard and the ceiling
        warning read the wrong duration. Both the file and the streaming path come through here,
        so a chunk seam is the same in both.
        """
        return fit_peak(trim_edges(disperse(waveform, rate), rate))
