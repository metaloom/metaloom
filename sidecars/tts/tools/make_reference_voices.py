#!/usr/bin/env python3
"""Produce Kartoffelbox reference clips from a RUNNING Orpheus TTS sidecar.

Kartoffelbox clones a voice from a reference clip; it has no speakers of its own. This
script makes one clip per Orpheus speaker, so that the German voice vocabulary is the same
whichever engine `TTS_ENGINE_DE` binds:

    Jakob.wav, Anton.wav, Julian.wav, Sophie.wav, Marie.wav

That matters beyond tidiness — the downstream caller (`TtsNode`, through its `voice` option)
posts `voice="Jakob"` and nothing else, so a clip of that name is what keeps
`TTS_ENGINE_DE=kartoffelbox` a drop-in change rather than a coordinated one.

    # against a server already running the ORPHEUS engine
    python3 tools/make_reference_voices.py --server http://localhost:9100 \
        --out models/kartoffelbox-voices

Caveat worth listening for: this clones from SYNTHETIC audio. It is the cheapest source and
it needs no consent or licence, but cloning a generated voice may compound artefacts. If the
result sounds off, the fallback is a CC0 corpus (Thorsten-Voice is CC0 and already a name
this repo knows) or a consented recording — see spec/sidecars/TTS_SIDECAR.md §3.3.
"""
import argparse
import io
import json
import os
import sys
import urllib.error
import urllib.request

import numpy as np
import soundfile as sf

# Chatterbox conditions on 7-15 s of clean speech and clips the reference internally at
# ~10 s. A greeting is too short to characterise a voice, so this is a paragraph — and it
# is deliberately plain prose, because whatever prosody is in the clip is what gets cloned.
REFERENCE_TEXT = (
    "Guten Tag, mein Name begleitet Sie durch diese Nachrichten. "
    "Ich lese Ihnen die wichtigsten Meldungen des Tages vor, ruhig und deutlich. "
    "Die Themen reichen von Politik und Wirtschaft bis zu Kultur und Sport. "
    "Bleiben Sie dran, wir beginnen mit den Schlagzeilen."
)

DEFAULT_VOICES = ["Jakob", "Anton", "Julian", "Sophie", "Marie"]


def burst_db(wav_bytes: bytes) -> float:
    """How far the loudest 50 ms of a take sticks out above its median speech level.

    The clone copies a clip's dynamics: a take that shouts single syllables is cloned into a
    voice that shouts them too, with saturated harmonics („übersteuert"). Orpheus takes of
    the same speaker vary — eight `Sophie` takes measured 10.1-12.7 dB, the one first
    installed 14.4 dB, against ~8.8 dB for Jakob/Marie (2026-10-09). Lower is better.
    """
    audio, rate = sf.read(io.BytesIO(wav_bytes), dtype="float32", always_2d=True)
    audio = audio.mean(axis=1)
    n = max(1, rate // 20)
    level = 20 * np.log10(np.sqrt((audio[: audio.size - audio.size % n].reshape(-1, n) ** 2)
                                  .mean(axis=1)) + 1e-9)
    speech = level[level > level.max() - 30]
    return float(level.max() - np.median(speech))


def synthesise(server: str, voice: str, text: str, timeout: float) -> bytes:
    payload = {"text": text, "lang": "de", "voice": voice, "format": "wav"}
    req = urllib.request.Request(
        f"{server.rstrip('/')}/v1/tts",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server", default="http://localhost:9100",
                    help="a tts-server running TTS_ENGINE_DE=orpheus")
    ap.add_argument("--out", default="models/kartoffelbox-voices")
    ap.add_argument("--voices", nargs="*", default=DEFAULT_VOICES)
    ap.add_argument("--text", default=REFERENCE_TEXT)
    ap.add_argument("--timeout", type=float, default=300.0)
    ap.add_argument("--takes", type=int, default=4,
                    help="takes generated per voice; the most even one (lowest burst_db) is kept")
    ap.add_argument("--force", action="store_true",
                    help="overwrite clips that already exist")
    args = ap.parse_args()

    # Refuse to clone from the wrong engine: a Kartoffelbox server asked for reference clips
    # would clone its own default conditioning back onto itself, which is not a voice.
    try:
        with urllib.request.urlopen(f"{args.server.rstrip('/')}/v1/models", timeout=30) as r:
            engines = [m["engine"] for m in json.load(r)["models"] if "de" in m["languages"]]
        if engines and engines[0] != "orpheus":
            print(f"error: {args.server} runs the {engines[0]} German engine, not orpheus. "
                  f"Start it with TTS_ENGINE_DE=orpheus to generate reference clips.",
                  file=sys.stderr)
            return 2
    except (urllib.error.URLError, OSError, KeyError, ValueError) as e:
        print(f"error: cannot read {args.server}/v1/models: {e}", file=sys.stderr)
        return 2

    os.makedirs(args.out, exist_ok=True)
    failures = 0
    for voice in args.voices:
        path = os.path.join(args.out, f"{voice}.wav")
        if os.path.exists(path) and not args.force:
            print(f"  {path} exists, skipping (use --force to overwrite)")
            continue
        try:
            takes = [synthesise(args.server, voice, args.text, args.timeout)
                     for _ in range(max(1, args.takes))]
        except (urllib.error.URLError, OSError) as e:
            print(f"  {voice}: FAILED ({e})", file=sys.stderr)
            failures += 1
            continue
        bursts = [burst_db(t) for t in takes]
        best = int(np.argmin(bursts))
        with open(path, "wb") as fh:
            fh.write(takes[best])
        print(f"  {path}  ({len(takes[best]) / 1e6:.1f} MB, take {best + 1}/{len(takes)}, "
              f"burst {bursts[best]:.1f} dB of {' '.join(f'{b:.1f}' for b in bursts)})")

    if failures:
        print(f"\n{failures} voice(s) failed.", file=sys.stderr)
        return 1
    print(f"\nWrote {len(args.voices) - failures} clip(s) to {args.out}.\n"
          f"LISTEN to them before using them: a clip of the right size can still be silence, "
          f"and whatever is in it is what gets cloned.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
