#!/usr/bin/env python3
"""
Generate the five MetaLoom-themed reference images next to this script.

This is the end-to-end example for the sidecar: it drives the running HTTP server
rather than importing the pipeline, so a successful run proves the same path the
Cortex `imagegen` node takes - request validation, per-variant step/cfg defaults,
the content-filter unmasking, PNG provenance chunks - and not just that torch works.

Usage:
  ./run.sh &                       # the server; first call loads ~17.5 GB
  ./.venv/bin/python generate_examples.py

Only the stdlib is used, so it also runs under a system python outside the venv.

The prompts are built from the actual brand: the MetaLoom mark is five teal stars
joined by a single thread (a loom warp drawn as a constellation), the palette is
petrol teal #44889d / deep teal #196477 / pale blue-grey #bdd1d7 on near-black navy,
and the product story is "point it at a folder and it transcribes, detects, describes
and indexes what is inside - on hardware you control". Sizes deliberately vary: the
NR-MMDiT is native-resolution, so a 1536x640 banner is a first-class output rather
than a square that has been cropped.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request

HOST = os.environ.get("MAGEFLOW_HOST", "localhost")
PORT = os.environ.get("MAGEFLOW_PORT", "9210")
BASE = f"http://{HOST}:{PORT}"
OUT_DIR = os.path.dirname(os.path.abspath(__file__))

PALETTE = (
    "colour palette restricted to petrol teal, deep teal, pale blue-grey and "
    "near-black navy"
)

EXAMPLES = [
    {
        "slug": "wordmark",
        # Text rendering is the headline capability of this model family
        # (LongText-EN 0.911 for Turbo), so the first example is a legibility test.
        "prompt": (
            "A wide cinematic product banner for an open source media platform. Centred, "
            "crisp, correctly spelled sans-serif wordmark reading \"MetaLoom\" in pale "
            "blue-grey, set on a dark brushed-metal wall of near-black navy. To the left "
            "of the wordmark, five small glowing petrol-teal stars are joined by one thin "
            "luminous thread into an angular constellation. Soft rim light grazing the "
            "metal, subtle volumetric haze, shallow depth of field, "
            + PALETTE + ", studio product photography, 8k, sharp typography."
        ),
        "width": 1536,
        "height": 640,
        "seed": 11,
    },
    {
        "slug": "loom-constellation",
        "prompt": (
            "A dark, elegant macro photograph of a weaving loom whose warp threads are "
            "strands of glowing petrol-teal light stretched taut across the frame. Five "
            "bright star-like nodes sit on the threads and are linked by a single "
            "continuous luminous line, forming an angular constellation that reads as a "
            "logo. Fine dust motes drift through the light, deep near-black navy "
            "background, long exposure glow, " + PALETTE + ", cinematic key light from "
            "the upper left, ultra detailed, museum-grade composition."
        ),
        "width": 1024,
        "height": 1024,
        "seed": 22,
    },
    {
        "slug": "self-hosted-rack",
        "prompt": (
            "A quiet home-office corner at night, photographed with a wide-angle lens: a "
            "compact self-hosted server rack of four black units, each with a row of tiny "
            "petrol-teal status LEDs, humming beside a wooden desk. On the desk a single "
            "monitor shows a dark media-library grid of thumbnails outlined in teal. A "
            "warm desk lamp is the only other light source, cables neatly routed, plants "
            "on a shelf above. Realistic, lived-in, " + PALETTE + " with one warm amber "
            "accent from the lamp, Fujifilm colour rendition, 35mm, f/2, ultra realistic."
        ),
        "width": 1216,
        "height": 832,
        "seed": 33,
    },
    {
        "slug": "processing-pipeline",
        "prompt": (
            "A conceptual illustration of a media processing pipeline as a loom: film "
            "strips, photographic prints and glowing audio waveforms enter from the left, "
            "are woven by petrol-teal threads of light through a mechanical shuttle in the "
            "centre, and leave to the right as a single smooth fabric of indexed, "
            "labelled tiles. Dark near-black navy studio void, precise technical "
            "geometry, thin luminous linework, volumetric light, " + PALETTE + ", "
            "high-end editorial infographic art direction, ultra detailed, 8k."
        ),
        "width": 1024,
        "height": 1024,
        "seed": 44,
    },
    {
        "slug": "cortex-analysis",
        # Kept perfectly flat and fully in focus on purpose: an earlier version asked for
        # "photographed off-axis, shallow depth of field" and the model obliged by
        # defocusing the entire UI, which is exactly wrong for a dashboard.
        "prompt": (
            "A dark media-analysis dashboard UI, rendered flat and straight-on as a crisp "
            "screenshot filling the frame, every element perfectly in focus: a grid of "
            "video thumbnails, several overlaid with thin petrol-teal face-detection "
            "rectangles and small confidence percentages, a bright teal audio waveform "
            "across the centre with a transcript strip beneath it, and a right-hand column "
            "of extracted metadata rows in a pale blue-grey monospace face. Near-black "
            # No "8k" in this one: on a UI prompt the model rendered the token itself as
            # a label in the corner of the interface.
            "navy panels, hairline separators, " + PALETTE + ", razor-sharp vector-clean "
            "interface design, high contrast, no blur, no bokeh."
        ),
        "width": 1216,
        "height": 832,
        "seed": 55,
    },
]


def post(path: str, payload: dict, timeout: int = 900) -> dict:
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
        with urllib.request.urlopen(BASE + "/health", timeout=10) as response:
            health = json.loads(response.read().decode("utf-8"))
    except Exception as error:
        raise SystemExit(f"Could not reach the sidecar at {BASE} ({error}). Start it with ./run.sh")

    print(f"model {health['models']['generate']}  device {health['device']}  "
          f"attn {health['attnBackend']}  defaults {health['defaults']}")

    import base64

    total = 0.0
    for index, example in enumerate(EXAMPLES, start=1):
        name = f"example-{index}-{example['slug']}.png"
        started = time.monotonic()
        # /v1/generate rather than /generate: the JSON response carries the model id,
        # the seed actually used and the server-side timing, which is what makes these
        # files reproducible.
        result = post("/v1/generate", {
            "prompt": example["prompt"],
            "width": example["width"],
            "height": example["height"],
            "seed": example["seed"],
        })
        wall = time.monotonic() - started
        total += wall
        path = os.path.join(OUT_DIR, name)
        with open(path, "wb") as handle:
            handle.write(base64.b64decode(result["images"][0]))
        print(f"  {name}  {result['width']}x{result['height']}  "
              f"seed={result['seeds'][0]}  steps={result['steps']}  cfg={result['cfg']}  "
              f"server={result['elapsedMs']} ms  wall={wall:.1f}s")

    print(f"Wrote {len(EXAMPLES)} images to {OUT_DIR} in {total:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
