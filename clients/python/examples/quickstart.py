#!/usr/bin/env python3
"""Connect, authenticate, and walk through the basics.

Run against a demo server::

    ../../../start-postgres.sh && ../../../start-demo.sh
    python3 examples/quickstart.py
"""

from __future__ import annotations

import os

from loom_client import LoomClient, LoomError, LoomNotFoundError, eq

HOST = os.environ.get("LOOM_HOST", "localhost")
PORT = int(os.environ.get("LOOM_PORT", "8092"))
USER = os.environ.get("LOOM_USER", "admin")
PASSWORD = os.environ.get("LOOM_PASSWORD", "finger")


def main() -> int:
    with LoomClient(host=HOST, port=PORT) as client:
        # The server is reachable before you have a token.
        health = client.health().body()
        print(f"health: {health.status}  database: {health.database}")

        client.authenticate(USER, PASSWORD)

        info = client.rest_info().body()
        print(f"connected to Loom {info.version} (db revision {info.db_revision})")
        print(f"authenticated as {client.me().body().username}")

        # Paged listing. iter() follows the seek cursor across pages.
        print("\nusers:")
        for user in client.list_users().iter(page_size=10):
            print(f"  {user.username:<24} enabled={user.enabled}")

        # Filtering uses left-hand-side bracket syntax; the helpers build it.
        matches = client.list_users().filter(eq("username", USER)).body()
        print(f"\nfilter matched {len(matches)} user(s)")

        # Libraries are the containers assets live in.
        libraries = client.list_libraries().limit(5).body()
        print(f"\n{libraries.metainfo.total_count} librar(ies):")
        for library in libraries:
            print(f"  {library.uuid}  {library.name}")

        # Assets, and the two ways to address one.
        assets = client.list_assets().limit(3).body()
        print(f"\n{assets.metainfo.total_count} asset(s), showing {len(assets)}:")
        for asset in assets:
            filename = asset.file.filename if asset.file else "?"
            print(f"  {asset.uuid}  {filename}")
            if asset.hashes and asset.hashes.sha512:
                # The same asset, fetched by the hash of its content.
                same = client.load_asset(asset.hashes.sha512).body()
                print(f"      also reachable by sha512 -> {same.uuid}")

        # Errors carry the server's own explanation.
        try:
            client.load_user("00000000-0000-0000-0000-000000000000").body()
        except LoomNotFoundError as e:
            print(f"\nexpected 404: {e.message}")

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except LoomError as error:
        print(f"failed: {error}")
        raise SystemExit(1) from error
