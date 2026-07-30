#!/usr/bin/env python3
"""
Generate the MetaLoom-themed reference video clip next to this script.

This is the end-to-end example for the sidecar: it drives the running HTTP server rather
than importing the pipeline, so a successful run proves the same path a future Cortex
video node would take — request validation, the shape-constraint snapping, MP4 encoding
and provenance — not just that torch and diffusers import.

Usage:
  ./run.sh &                       # the server; first call loads the checkpoint (GB)
  ./.venv/bin/python generate_examples.py

Only the stdlib is used, so it also runs under a system python outside the venv.

The prompt is built from the actual brand (shared with the image sidecars' examples):
the MetaLoom mark is five teal stars joined by a single thread — a loom warp drawn as a
constellation — the palette is petrol teal #44889d / deep teal #196477 / pale blue-grey
#bdd1d7 on near-black navy, and the product story is "point it at a folder and it
transcribes, detects, describes and indexes what is inside — on hardware you control".

The clip is kept deliberately small (512x320, 33 frames, fewer steps) so it renders in a
couple of minutes on the 24 GB nf4 default (the model offloads a 27B text encoder + 19B
transformer per step), and so the committed reference file stays tiny. Bump width/height/
num_frames/steps for quality once you have the VRAM/time budget.
"""

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

HOST = os.environ.get("LTX2_HOST", "localhost")
PORT = os.environ.get("LTX2_PORT", "9220")
BASE = f"http://{HOST}:{PORT}"
OUT_DIR = os.path.dirname(os.path.abspath(__file__))

PALETTE = (
    "colour palette restricted to petrol teal, deep teal, pale blue-grey and "
    "near-black navy"
)

EXAMPLES = [
    {
        "slug": "loom-constellation",
        "prompt": (
            "A dark, elegant cinemagraph of a weaving loom whose warp threads are strands "
            "of glowing petrol-teal light drawn taut across the frame. Five bright "
            "star-like nodes sit on the threads and are linked by a single continuous "
            "luminous line, forming an angular constellation that reads as a logo. The "
            "nodes slowly pulse and the thread shimmers as if data is flowing along it, "
            "fine dust motes drift through the light, deep near-black navy background, "
            "slow cinematic push-in, long-exposure glow, " + PALETTE + ", ultra detailed, "
            "museum-grade composition."
        ),
        "width": 512,
        "height": 320,
        "num_frames": 33,
        "fps": 24,
        "steps": 20,
        "seed": 22,
    },
]


def _get(path: str, timeout: int = 10) -> dict:
    with urllib.request.urlopen(BASE + path, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def post(path: str, payload: dict, timeout: int = 1800) -> dict:
    request = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")
        raise SystemExit(f"{path} failed with HTTP {error.code}: {body}") from error
    except urllib.error.URLError as error:
        raise SystemExit(
            f"Could not reach the sidecar at {BASE} ({error.reason}). Start it with ./run.sh"
        ) from error


def main() -> int:
    try:
        health = _get("/health")
    except Exception as error:
        raise SystemExit(f"Could not reach the sidecar at {BASE} ({error}). Start it with ./run.sh")

    print(f"model {health['model']}  device {health['device']}  "
          f"offload {health['cpuOffload']}  vae_tiling {health['vaeTiling']}  "
          f"defaults {health['defaults']}")

    total = 0.0
    for index, example in enumerate(EXAMPLES, start=1):
        name = f"example-{index}-{example['slug']}.mp4"
        started = time.monotonic()
        # /v1/generate rather than /generate: the JSON response carries the model id,
        # the seed and the server-side timing, which is what makes this file reproducible.
        payload = {
            "prompt": example["prompt"],
            "width": example["width"],
            "height": example["height"],
            "num_frames": example["num_frames"],
            "fps": example["fps"],
            "seed": example["seed"],
        }
        if example.get("steps"):
            payload["steps"] = example["steps"]
        result = post("/v1/generate", payload)
        wall = time.monotonic() - started
        total += wall
        path = os.path.join(OUT_DIR, name)
        with open(path, "wb") as handle:
            handle.write(base64.b64decode(result["video_b64"]))
        print(f"  {name}  {result['width']}x{result['height']}  "
              f"frames={result['numFrames']}  fps={result['fps']}  seed={result['seed']}  "
              f"steps={result['steps']}  guidance={result['guidance']}  "
              f"server={result['elapsedMs']} ms  wall={wall:.1f}s")

    print(f"Wrote {len(EXAMPLES)} clip(s) to {OUT_DIR} in {total:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
