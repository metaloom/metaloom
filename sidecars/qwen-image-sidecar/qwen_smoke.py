#!/usr/bin/env python3
"""
Bring-up / smoke-test client for the Qwen-Image-2.1 sidecar.

This is the role `generate_examples.py` plays for mage-flow and ltx2: the thing you run
against a freshly deployed sidecar to find out whether it actually works, before any
Java is pointed at it.

It exists as a script rather than a README full of curl because a base64-encoded image
inlined into curl's argv blows the shell argument limit at around a 100 KB source
image - the exact trap mage-flow's README documents. Bodies go into a temp file here.

Only the standard library is used, so it runs anywhere python3 does - no venv needed,
and in particular it does NOT need torch or diffusers. Point it at a remote endpoint
and it is a pure HTTP client.

Usage:
  ./qwen_smoke.py health                          --endpoint http://HOST:9230
  ./qwen_smoke.py generate "a neon shop sign reading QWEN, rainy night"
  ./qwen_smoke.py remix photo.jpg "as an oil painting"
  ./qwen_smoke.py compose a.png b.png c.png "the person from image 1 holding the
                                             product from image 2, in the setting of image 3"
  ./qwen_smoke.py mask boy.jpg "the boy's hair"
  ./qwen_smoke.py edit boy.jpg "dark brown hair" --mask-prompt "the boy's hair" --dump-mask
  ./qwen_smoke.py all sample.jpg                  # every route in sequence

Exit code is non-zero if any request failed, so it is usable as a deployment gate.
"""

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

DEFAULT_ENDPOINT = os.environ.get("QWENIMAGE_ENDPOINT", "http://localhost:9230")
# The first request after a cold start downloads and loads 33 GB. Minutes, not seconds.
DEFAULT_TIMEOUT = int(os.environ.get("QWENIMAGE_SMOKE_TIMEOUT", "1800"))


def _b64(path: str) -> str:
    with open(path, "rb") as handle:
        return base64.b64encode(handle.read()).decode("ascii")


def _post(endpoint: str, route: str, body: dict, timeout: int):
    """POST JSON, return (bytes, headers). Raises RuntimeError with the server's detail."""
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        endpoint.rstrip("/") + route,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read()
            elapsed = time.time() - started
            return payload, dict(response.headers), elapsed
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"{route} returned HTTP {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"{route} could not be reached at {endpoint}: {exc.reason}") from exc


def _header(headers: dict, name: str, default: str = "?") -> str:
    """Case-insensitive header lookup.

    uvicorn emits header names lowercased, so `headers["X-Model-Id"]` misses. urllib's
    response.headers is itself case-insensitive, but dict() of it is not - and dict() is
    what crosses the function boundary here. (The Java client needs no equivalent:
    java.net.http.HttpHeaders.firstValue is case-insensitive by contract.)
    """
    lowered = {k.lower(): v for k, v in headers.items()}
    return lowered.get(name.lower(), default)


def _save(payload: bytes, headers: dict, out: str, elapsed: float) -> None:
    if not payload.startswith(b"\x89PNG"):
        raise RuntimeError(
            f"expected PNG bytes, got {len(payload)} bytes starting {payload[:16]!r}"
        )
    with open(out, "wb") as handle:
        handle.write(payload)
    print(f"  -> {out}  ({len(payload) // 1024} KB, {elapsed:.1f}s, "
          f"model={_header(headers, 'X-Model-Id')})")


def cmd_health(args) -> None:
    with urllib.request.urlopen(args.endpoint.rstrip("/") + "/health", timeout=30) as response:
        report = json.loads(response.read())
    print(json.dumps(report, indent=2))
    missing = {"generate", "remix", "edit", "mask"} - set(report.get("capabilities", []))
    if missing:
        raise RuntimeError(f"the sidecar does not advertise {sorted(missing)}")
    if not report.get("loaded"):
        print("\nNote: nothing is loaded yet - the model loads on the first request, "
              "which will take minutes on a cold cache.")


def cmd_generate(args) -> None:
    print(f"generate: {args.prompt!r}")
    payload, headers, elapsed = _post(args.endpoint, "/generate", {
        "prompt": args.prompt, "width": args.width, "height": args.height,
        "steps": args.steps, "seed": args.seed,
    }, args.timeout)
    _save(payload, headers, args.out or "smoke-generate.png", elapsed)


def cmd_remix(args) -> None:
    print(f"remix: {args.image} + {args.prompt!r}")
    payload, headers, elapsed = _post(args.endpoint, "/remix", {
        "image_b64": _b64(args.image), "prompt": args.prompt,
        "steps": args.steps, "seed": args.seed,
    }, args.timeout)
    _save(payload, headers, args.out or "smoke-remix.png", elapsed)


def cmd_compose(args) -> None:
    print(f"compose: {len(args.images)} image(s) + {args.prompt!r}")
    payload, headers, elapsed = _post(args.endpoint, "/edit", {
        "prompt": args.prompt,
        "images_b64": [_b64(path) for path in args.images],
        "steps": args.steps, "seed": args.seed,
    }, args.timeout)
    _save(payload, headers, args.out or "smoke-compose.png", elapsed)


def cmd_mask(args) -> None:
    print(f"mask: {args.image} -> {args.prompt!r}")
    payload, headers, elapsed = _post(args.endpoint, "/mask", {
        "image_b64": _b64(args.image), "prompt": args.prompt,
        "steps": args.steps, "seed": args.seed,
    }, args.timeout)
    _save(payload, headers, args.out or "smoke-mask.png", elapsed)


def cmd_edit(args) -> None:
    """The two-step flow. --dump-mask runs /mask separately first so you can SEE the
    intermediate, which is the step most likely to be the weak link."""
    body = {
        "prompt": args.prompt,
        "images_b64": [_b64(args.image)] + [_b64(p) for p in (args.reference or [])],
        "steps": args.steps, "seed": args.seed, "composite": args.composite,
    }

    if args.dump_mask and args.mask_prompt:
        print(f"edit: deriving the mask separately first ({args.mask_prompt!r})")
        payload, headers, elapsed = _post(args.endpoint, "/mask", {
            "image_b64": body["images_b64"][0], "prompt": args.mask_prompt,
            "steps": args.steps, "seed": args.seed,
        }, args.timeout)
        _save(payload, headers, "smoke-edit-mask.png", elapsed)
        # Reuse it, so the edit below is driven by the mask you just looked at rather
        # than by a second, differently-sampled one.
        body["mask_b64"] = base64.b64encode(payload).decode("ascii")
    elif args.mask_prompt:
        body["mask_prompt"] = args.mask_prompt
    elif args.mask:
        body["mask_b64"] = _b64(args.mask)

    print(f"edit: {args.prompt!r}"
          + (f" masked by {args.mask_prompt!r}" if args.mask_prompt else ""))
    payload, headers, elapsed = _post(args.endpoint, "/edit", body, args.timeout)
    _save(payload, headers, args.out or "smoke-edit.png", elapsed)


def cmd_all(args) -> None:
    """Every route in sequence against one sample image. The deployment gate."""
    cmd_health(args)
    print()

    steps = args.steps
    for label, fn, overrides in [
        ("generate", cmd_generate, {"prompt": "a neon shop sign that reads \"QWEN IMAGE 2.1\", "
                                              "rainy night, reflections on wet pavement"}),
        ("remix", cmd_remix, {"prompt": "the same scene as an oil painting"}),
        ("mask", cmd_mask, {"prompt": args.subject}),
        ("edit", cmd_edit, {"prompt": args.change, "mask_prompt": args.subject,
                            "dump_mask": True, "reference": None, "mask": None,
                            "composite": False}),
    ]:
        print(f"--- {label} ---")
        # width/height are defined on the `generate` subparser only, so they are absent
        # here and cmd_generate would fail on the attribute rather than on the request.
        scoped = argparse.Namespace(**{
            "width": 1024, "height": 1024,
            **vars(args), "steps": steps, "out": f"smoke-{label}.png", **overrides,
        })
        fn(scoped)
        print()

    print("All four routes answered with a PNG. Look at smoke-edit-mask.png before "
          "believing smoke-edit.png: a wrong mask produces a plausible-looking edit "
          "in the wrong place.")


def main() -> int:
    # The shared flags are attached BOTH to the top-level parser and to every subcommand, so
    # `--endpoint` works on either side of the verb. argparse does not do this by itself: flags
    # defined only on the parent must precede the subcommand, and typing
    # `qwen_smoke.py health --endpoint ...` - the obvious order, and the one the README uses -
    # fails with "unrecognized arguments". The subcommand copies default to SUPPRESS so that
    # leaving one off does not overwrite the value given before the verb.
    def add_shared(target, suppress):
        default = (lambda v: argparse.SUPPRESS) if suppress else (lambda v: v)
        target.add_argument("--endpoint", default=default(DEFAULT_ENDPOINT))
        target.add_argument("--timeout", type=int, default=default(DEFAULT_TIMEOUT))
        target.add_argument("--steps", type=int, default=default(None))
        target.add_argument("--seed", type=int, default=default(7),
                            help="fixed by default so two runs are comparable")
        target.add_argument("--out", default=default(None))

    shared = argparse.ArgumentParser(add_help=False)
    add_shared(shared, suppress=True)

    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    add_shared(parser, suppress=False)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("health", parents=[shared]).set_defaults(func=cmd_health)

    p = sub.add_parser("generate", parents=[shared])
    p.add_argument("prompt")
    p.add_argument("--width", type=int, default=1024)
    p.add_argument("--height", type=int, default=1024)
    p.set_defaults(func=cmd_generate)

    p = sub.add_parser("remix", parents=[shared])
    p.add_argument("image")
    p.add_argument("prompt")
    p.set_defaults(func=cmd_remix)

    p = sub.add_parser("compose", parents=[shared])
    p.add_argument("images", nargs="+", help="2..10 images; the LAST argument is the prompt")
    p.set_defaults(func=cmd_compose)

    p = sub.add_parser("mask", parents=[shared])
    p.add_argument("image")
    p.add_argument("prompt", help="the region to find, e.g. \"the boy's hair\"")
    p.set_defaults(func=cmd_mask)

    p = sub.add_parser("edit", parents=[shared])
    p.add_argument("image")
    p.add_argument("prompt", help="the change to make")
    p.add_argument("--mask-prompt", dest="mask_prompt", default=None)
    p.add_argument("--mask", default=None, help="a mask PNG, instead of --mask-prompt")
    p.add_argument("--reference", action="append", help="further reference images")
    p.add_argument("--composite", action="store_true")
    p.add_argument("--dump-mask", dest="dump_mask", action="store_true",
                   help="derive the mask in a separate call and save it")
    p.set_defaults(func=cmd_edit)

    p = sub.add_parser("all", parents=[shared])
    p.add_argument("image")
    p.add_argument("--subject", default="the largest object in the foreground")
    p.add_argument("--change", default="make it bright red")
    p.set_defaults(func=cmd_all)

    args = parser.parse_args()

    # `compose` takes the prompt as its final positional, so the list has to be split.
    if args.command == "compose":
        if len(args.images) < 2:
            parser.error("compose needs at least one image and a prompt")
        args.prompt = args.images[-1]
        args.images = args.images[:-1]

    try:
        args.func(args)
    except RuntimeError as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
