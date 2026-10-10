"""
Build tests/data/two_speakers.wav + .json: two synthetic voices taking turns, with the exact
sample positions of every turn, so the live tests know who speaks when.

The voices come from the TTS sidecar (sidecars/tts, :9100): two Kokoro voices, one female and one
male, reading different sentences. Synthetic speech is not a real speaker, but two distinct TTS
voices are two distinct "speakers" to every embedding model, and the fixture is reproducible
without shipping anyone's actual voice. Run it once with the TTS sidecar up; the output is
committed.

  python tests/make_fixture.py [--tts http://localhost:9100]
"""

from __future__ import annotations

import argparse
import io
import json
from pathlib import Path

import numpy as np
import soundfile as sf
import soxr
import urllib.request

RATE = 16000
GAP_S = 1.5

TURNS = [
    ("A", "Good evening. Tonight we look at how the river shaped the old town, and why the bridge was moved twice."),
    ("B", "Thank you. The first bridge stood further downstream, close to where the market square is today."),
    ("A", "And the second one was built after the flood of the following decade, is that right?"),
    ("B", "That is right. The flood took the mill with it, and the council decided to rebuild on higher ground, where the road already crossed."),
    ("A", "Which brings us to the stone bridge we still walk across today. Thank you for joining us this evening."),
]
VOICES = {"A": "af_heart", "B": "am_michael"}


def tts(base: str, text: str, voice: str) -> np.ndarray:
    body = json.dumps({"text": text, "lang": "en", "voice": voice, "format": "wav"}).encode()
    req = urllib.request.Request(base + "/v1/tts", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        data = resp.read()
    audio, rate = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
    audio = audio.mean(axis=1)
    if rate != RATE:
        audio = soxr.resample(audio, rate, RATE)
    # Trim leading/trailing silence so the turn positions are tight.
    frames = audio[: len(audio) // 160 * 160].reshape(-1, 160)
    loud = np.flatnonzero(np.sqrt((frames**2).mean(axis=1)) > 0.01)
    audio = audio[loud[0] * 160 : (loud[-1] + 1) * 160]
    return (audio / max(1e-6, np.abs(audio).max()) * 0.8).astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tts", default="http://localhost:9100")
    args = ap.parse_args()
    out = Path(__file__).parent / "data"
    out.mkdir(exist_ok=True)

    gap = np.zeros(int(GAP_S * RATE), dtype=np.float32)
    parts = [gap]
    turns = []
    pos = len(gap)
    for speaker, text in TURNS:
        clip = tts(args.tts, text, VOICES[speaker])
        turns.append({"speaker": speaker, "start": pos, "end": pos + len(clip), "text": text})
        parts += [clip, gap]
        pos += len(clip) + len(gap)
    audio = np.concatenate(parts)
    sf.write(out / "two_speakers.wav", audio, RATE, subtype="PCM_16")
    json.dump({"rate": RATE, "voices": VOICES, "turns": turns}, open(out / "two_speakers.json", "w"), indent=1)
    print(f"wrote {len(audio) / RATE:.1f} s, {len(turns)} turns")


if __name__ == "__main__":
    main()
