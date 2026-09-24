#!/usr/bin/env python3
"""
Build tests/data/de_timeline.flac + de_timeline.json - the German fixture the live tests run on.

Three real German utterances are laid out on a timeline with known silence between them:

  |-- 1.5 s --|== clip 0 ==|-- 2.5 s --|== clip 1 ==|-- 2.5 s --|== clip 2 ==|-- 1.5 s --|

Because every clip's position is known to the sample, the time codes a backend returns can be
checked without a forced aligner: each word must land inside the window of the clip it belongs
to, and no word may land in a gap. An offset bug of even half a gap puts words in the wrong
window. The JSON records each clip's window, its reference transcript and its source.

Sources (both licences allow redistribution):
  FLEURS (google/fleurs, CC-BY-4.0) and Common Voice 17 (Mozilla, CC0-1.0), taken from the
  evaluation sets audio-eval builds with tools/fetch_asr_data.py.

Usage:
  python tests/make_fixture.py /path/to/audio-eval/assets/asr
"""

import json
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

RATE = 16000
LEAD_S, GAP_S, TAIL_S = 1.5, 2.5, 1.5
CLIPS = [
    ("fleurs_de", "1100650137365357330", "FLEURS (google/fleurs), CC-BY-4.0"),
    ("cv_de", "common_voice_de_17301738", "Common Voice 17 (Mozilla), CC0-1.0"),
    ("fleurs_de", "11833581125162793735", "FLEURS (google/fleurs), CC-BY-4.0"),
]


def main(assets: Path) -> int:
    out_dir = Path(__file__).parent / "data"
    out_dir.mkdir(exist_ok=True)
    rng = np.random.default_rng(1234)

    def silence(seconds):
        # A faint noise floor rather than digital zero, which no microphone produces.
        return (rng.standard_normal(int(seconds * RATE)) * 10 ** (-70 / 20)).astype(np.float32)

    pieces = [silence(LEAD_S)]
    pos = len(pieces[0])
    clips = []
    for i, (set_name, clip_id, source) in enumerate(CLIPS):
        manifest = {r["id"]: r for r in map(json.loads, open(assets / set_name / "manifest.jsonl"))}
        row = manifest[clip_id]
        audio, rate = sf.read(assets / set_name / row["wav"], dtype="float32", always_2d=True)
        audio = audio.mean(axis=1)
        assert rate == RATE, f"{clip_id}: {rate} Hz"
        # Normalise to -20 dBFS RMS so the three sources sit at one level.
        audio = audio * (10 ** (-20 / 20) / (np.sqrt(np.mean(audio**2)) + 1e-9))
        audio = np.clip(audio, -1.0, 1.0).astype(np.float32)
        clips.append(
            {
                "index": i,
                "start": round(pos / RATE, 3),
                "end": round((pos + len(audio)) / RATE, 3),
                "text": row["text"],
                "source": source,
                "source_id": clip_id,
            }
        )
        pieces.append(audio)
        pos += len(audio)
        pieces.append(silence(TAIL_S if i == len(CLIPS) - 1 else GAP_S))
        pos += len(pieces[-1])

    timeline = np.concatenate(pieces)
    sf.write(out_dir / "de_timeline.flac", timeline, RATE, subtype="PCM_16")
    meta = {"sample_rate": RATE, "duration": round(len(timeline) / RATE, 3), "language": "de", "clips": clips}
    (out_dir / "de_timeline.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(meta, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(Path(sys.argv[1])))
