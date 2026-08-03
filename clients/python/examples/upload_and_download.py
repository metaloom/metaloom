#!/usr/bin/env python3
"""Upload a file, tag it, then read the bytes back.

Run against a demo server::

    ../../../start-postgres.sh && ../../../start-demo.sh
    python3 examples/upload_and_download.py [path/to/file]

With no argument it generates a small file so the example works anywhere.
"""

from __future__ import annotations

import hashlib
import os
import sys
import tempfile
import uuid

from loom_client import LoomClient, LoomError
from loom_client.models import TagCreateRequest

HOST = os.environ.get("LOOM_HOST", "localhost")
PORT = int(os.environ.get("LOOM_PORT", "8092"))
USER = os.environ.get("LOOM_USER", "admin")
PASSWORD = os.environ.get("LOOM_PASSWORD", "finger")


def sample_file() -> str:
    handle, path = tempfile.mkstemp(suffix=".bin", prefix="loom-example-")
    with os.fdopen(handle, "wb") as f:
        f.write(os.urandom(4096))
    return path


def main(argv: list[str]) -> int:
    source = argv[1] if len(argv) > 1 else sample_file()
    original = open(source, "rb").read()
    print(f"uploading {source} ({len(original)} bytes)")

    with LoomClient(host=HOST, port=PORT, timeout=120) as client:
        client.authenticate(USER, PASSWORD)

        libraries = client.list_libraries().limit(1).body()
        if not libraries.data:
            print("this instance has no library to upload into", file=sys.stderr)
            return 1
        library = libraries.data[0]

        # Uploads are multipart. Raise the per-request timeout for large files.
        asset = client.upload_asset(source, library_uuid=library.uuid).body()
        print(f"created asset {asset.uuid} in library {library.name}")

        # A tag belongs to a collection, and the server rejects one without it.
        collections = client.list_collections().limit(1).body()
        collection = collections.data[0].name if collections.data else "default"

        # Tagging is nested under the asset, and is UUID-only: the server registers
        # no content-addressed routes below the asset itself.
        # Unique per run: re-creating a tag that already exists in the collection
        # currently fails rather than reusing it.
        tag_name = f"example-upload-{uuid.uuid4().hex[:8]}"
        tag = client.tag_asset(asset.uuid, TagCreateRequest(name=tag_name, collection=collection)).body()
        print(f"tagged with {tag.name} (collection {collection})")

        # Download streams. Close it, or use it as a context manager as here.
        with client.download_asset_binary(asset.uuid).body() as binary:
            print(f"content-type: {binary.content_type}  filename: {binary.filename}")
            target = os.path.join(tempfile.mkdtemp(), "downloaded.bin")
            written = binary.save(target)

        roundtripped = open(target, "rb").read()
        print(f"downloaded {written} bytes to {target}")
        print(
            "bytes match"
            if hashlib.sha512(roundtripped).digest() == hashlib.sha512(original).digest()
            else "MISMATCH"
        )

        # Best effort: the server currently answers 500 when deleting an asset that
        # still carries a tag, so a failure here is not the example going wrong.
        try:
            client.delete_asset(asset.uuid).execute()
            print("cleaned up")
        except LoomError as e:
            print(f"could not delete {asset.uuid}: {e.args[0] if e.args else e}")

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except LoomError as error:
        print(f"failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
