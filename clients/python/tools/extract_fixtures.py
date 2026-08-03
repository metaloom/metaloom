#!/usr/bin/env python3
# Copyright 2024 Johannes Schüth
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""Extract the example request and response bodies from the generated OpenAPI document.

The document carries no schemas -- which is why the models cannot be generated from
it -- but it does carry a couple of hundred real example bodies, produced by the
server's own ``*Examples`` classes and serialised by the server's own mapper. That
makes them the best available oracle for the model layer: if a generated dataclass
round-trips every one of them unchanged, its field names and types agree with the
server, and no running server was needed to prove it.

Run after regenerating the OpenAPI document::

    python3 tools/extract_fixtures.py

Writes ``tests/fixtures/openapi_bodies.json``, which ``tests/test_models.py`` reads.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CLIENT_ROOT = HERE.parent
REPO_ROOT = CLIENT_ROOT.parent.parent

OPENAPI = REPO_ROOT / "loom/doc/src/main/generated/openapi.json"
OUTPUT = CLIENT_ROOT / "tests" / "fixtures" / "openapi_bodies.json"

VERBS = ("get", "post", "put", "patch", "delete")
API_PREFIX = "/api/v1"


def extract(document: dict) -> list[dict]:
    """Pull every inlined example body out, keyed by where it appeared."""
    bodies: list[dict] = []

    for path, operations in document.get("paths", {}).items():
        # Store paths the way the client builds them: relative to /api/v1, no
        # leading slash, so the test can match them against the client's own table.
        relative = path[len(API_PREFIX) :].strip("/") if path.startswith(API_PREFIX) else path

        for verb in VERBS:
            operation = operations.get(verb)
            if not isinstance(operation, dict):
                continue

            request = operation.get("requestBody", {}).get("content", {})
            for media_type, media in request.items():
                if "example" in media:
                    bodies.append(
                        {
                            "path": relative,
                            "verb": verb.upper(),
                            "direction": "request",
                            "status": None,
                            "media_type": media_type,
                            "operation_id": operation.get("operationId"),
                            "body": media["example"],
                        }
                    )

            for status, response in operation.get("responses", {}).items():
                for media_type, media in response.get("content", {}).items():
                    if "example" in media:
                        bodies.append(
                            {
                                "path": relative,
                                "verb": verb.upper(),
                                "direction": "response",
                                "status": status,
                                "media_type": media_type,
                                "operation_id": operation.get("operationId"),
                                "body": media["example"],
                            }
                        )

    bodies.sort(key=lambda b: (b["path"], b["verb"], b["direction"], b["status"] or ""))
    return bodies


def main() -> int:
    if not OPENAPI.is_file():
        print(
            f"OpenAPI document not found at {OPENAPI}.\n"
            f"Regenerate it with:\n"
            f"  cd loom/doc && mvn -q exec:java "
            f"-Dexec.mainClass=io.metaloom.loom.doc.ExampleGenerator",
            file=sys.stderr,
        )
        return 2

    document = json.loads(OPENAPI.read_text(encoding="utf-8"))
    bodies = extract(document)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(bodies, indent=1, sort_keys=True) + "\n", encoding="utf-8")

    requests = sum(1 for b in bodies if b["direction"] == "request")
    responses = len(bodies) - requests
    print(
        f"wrote {len(bodies)} example bodies ({requests} request, {responses} response) "
        f"to {OUTPUT.relative_to(CLIENT_ROOT)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
