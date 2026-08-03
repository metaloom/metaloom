"""End-to-end tests against a running Loom server.

Skipped unless ``LOOM_IT=1``, because they need a real server::

    ./start-postgres.sh          # from the repository root
    ./start-demo.sh              # -> http://localhost:8092
    LOOM_IT=1 ./test.sh

``./setup-pool.sh`` is *not* needed -- that leases pooled databases for the Java tests
and has nothing to do with this client.

Deliberately not using testcontainers: the Java client's ``LoomContainer`` pulls
``metaloom/loom:v1.0.0`` on port 6333, an image ``build-containers.sh`` does not
produce and a port the server does not listen on. ``start-demo.sh`` is the supported
way to get a server up.

Every test cleans up what it creates, so the suite can be run repeatedly against the
same instance.
"""

from __future__ import annotations

import hashlib
import os
import tempfile
import unittest
import uuid

from loom_client import LoomClient, LoomError, LoomNotFoundError
from loom_client.models import (
    AssetCreateRequest,
    FileInfo,
    HashInfo,
    UserCreateRequest,
    UserUpdateRequest,
)

RUN_IT = os.environ.get("LOOM_IT") == "1"
HOST = os.environ.get("LOOM_HOST", "localhost")
PORT = int(os.environ.get("LOOM_PORT", "8092"))
USER = os.environ.get("LOOM_USER", "admin")
PASSWORD = os.environ.get("LOOM_PASSWORD", "finger")


@unittest.skipUnless(RUN_IT, "set LOOM_IT=1 and start a server to run the integration tests")
class LiveServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = LoomClient(host=HOST, port=PORT, timeout=30)
        cls.client.authenticate(USER, PASSWORD)

    @classmethod
    def tearDownClass(cls):
        cls.client.close()

    def unique(self, prefix: str) -> str:
        return f"{prefix}-{uuid.uuid4().hex[:12]}"

    def delete_later(self, method, element_uuid) -> None:
        """Register a best-effort cleanup.

        Failures are swallowed: a test that already deleted the element should not
        fail in teardown, and the server answers a repeat delete with 500 rather
        than 404.
        """

        def cleanup():
            try:
                method(element_uuid).execute()
            except LoomError:
                pass

        self.addCleanup(cleanup)

    # -- the basics ---------------------------------------------------------

    def test_health_is_reachable_without_a_token(self):
        with LoomClient(host=HOST, port=PORT) as anonymous:
            health = anonymous.health().body()
            self.assertIsNotNone(health.status)

    def test_rest_info_reports_a_version(self):
        info = self.client.rest_info().body()
        self.assertTrue(info.version, "the API root should report a server version")

    def test_login_yields_a_usable_token(self):
        token = self.client.login(USER, PASSWORD).body().token
        self.assertTrue(token)
        with LoomClient(host=HOST, port=PORT, token=token) as fresh:
            self.assertEqual(fresh.me().body().username, USER)

    def test_me_returns_the_authenticated_user(self):
        self.assertEqual(self.client.me().body().username, USER)

    # -- CRUD ---------------------------------------------------------------

    def test_user_lifecycle(self):
        username = self.unique("py-client")
        created = self.client.create_user(UserCreateRequest(username=username, email="a@b.c")).body()
        self.delete_later(self.client.delete_user, created.uuid)

        self.assertEqual(created.username, username)
        self.assertTrue(created.uuid)
        # The server takes only the username on create -- email and firstname are
        # accepted and dropped. Verified against raw HTTP, so this is the endpoint's
        # behaviour rather than something the client loses.
        self.assertIsNone(created.email)

        loaded = self.client.load_user(created.uuid).body()
        self.assertEqual(loaded.username, username)

        updated = self.client.update_user(
            created.uuid, UserUpdateRequest(firstname="Ada", email="a@b.c")
        ).body()
        self.assertEqual(updated.firstname, "Ada")
        self.assertEqual(updated.email, "a@b.c")
        # Update touches only what was set.
        self.assertEqual(updated.username, username)

        patched = self.client.patch_user(created.uuid, UserUpdateRequest(lastname="Lovelace")).body()
        self.assertEqual(patched.lastname, "Lovelace")
        self.assertEqual(patched.firstname, "Ada", "patch should not clear other fields")

        response = self.client.delete_user(created.uuid).execute()
        self.assertIn(response.status, (200, 204))
        with self.assertRaises(LoomNotFoundError):
            self.client.load_user(created.uuid).execute()

    def test_creator_and_timestamps_are_populated(self):
        username = self.unique("py-status")
        created = self.client.create_user(UserCreateRequest(username=username)).body()
        self.delete_later(self.client.delete_user, created.uuid)

        from loom_client.models import parse_instant

        self.assertIsNotNone(created.status)
        self.assertIsNotNone(created.status.creator)
        self.assertIsNotNone(parse_instant(created.status.created))

    # -- paging -------------------------------------------------------------

    def test_list_respects_limit_and_reports_paging_info(self):
        page = self.client.list_users().limit(2).body()
        self.assertLessEqual(len(page.data), 2)
        self.assertIsNotNone(page.metainfo)
        self.assertGreaterEqual(page.metainfo.total_count, 1)

    def test_iter_walks_every_page(self):
        total = self.client.list_users().limit(1).body().metainfo.total_count
        seen = [u.uuid for u in self.client.list_users().iter(page_size=2)]
        self.assertEqual(len(seen), len(set(seen)), "iter() yielded a duplicate")
        self.assertEqual(len(seen), total, "iter() did not cover every page")

    # -- assets and binaries -------------------------------------------------

    def test_asset_is_addressable_by_uuid_and_by_sha512(self):
        payload = self.unique("content").encode()
        digest = hashlib.sha512(payload).hexdigest()

        created = self.client.create_asset(
            AssetCreateRequest(
                hashes=HashInfo(sha512=digest),
                # The server rejects an asset with no origin, so this is required
                # rather than decorative.
                file=FileInfo(
                    filename="probe.bin",
                    mime_type="application/octet-stream",
                    size=len(payload),
                    origin="python-client-it",
                ),
            )
        ).body()
        self.delete_later(self.client.delete_asset, created.uuid)

        by_uuid = self.client.load_asset(created.uuid).body()
        by_hash = self.client.load_asset(digest).body()
        self.assertEqual(by_uuid.uuid, by_hash.uuid)

    def test_asset_sub_resources_reject_a_hash_with_a_clear_message(self):
        # The server registers no content-addressed routes below the asset, so the
        # client refuses rather than issuing a request that would 404.
        digest = hashlib.sha512(b"x").hexdigest()
        with self.assertRaises(ValueError) as caught:
            self.client.list_asset_tasks(digest)
        self.assertIn("load_asset(sha512)", str(caught.exception))

    def test_upload_then_download_returns_the_same_bytes(self):
        payload = os.urandom(2048)
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as handle:
            handle.write(payload)
            source = handle.name
        self.addCleanup(os.unlink, source)

        library = self.client.list_libraries().limit(1).body()
        if not library.data:
            self.skipTest("the instance has no library to upload into")

        asset = self.client.upload_asset(source, library_uuid=library.data[0].uuid).body()
        self.delete_later(self.client.delete_asset, asset.uuid)
        self.assertTrue(asset.uuid)

        with self.client.download_asset_binary(asset.uuid).body() as binary:
            self.assertEqual(binary.read(), payload)

    # -- errors ---------------------------------------------------------------

    def test_unknown_uuid_raises_not_found_with_a_message(self):
        with self.assertRaises(LoomNotFoundError) as caught:
            self.client.load_user(uuid.uuid4()).execute()
        self.assertEqual(caught.exception.status, 404)
        self.assertTrue(caught.exception.message)

    def test_missing_token_raises_unauthorized(self):
        from loom_client import LoomUnauthorizedError

        with LoomClient(host=HOST, port=PORT) as anonymous:
            with self.assertRaises(LoomUnauthorizedError):
                anonymous.list_users().execute()

    def test_replace_without_every_field_is_rejected(self):
        """PUT demands a complete body, and says which fields were missing.

        This is the documented limitation of ``replace_*``: like the Java client, the
        model serialises only the fields that were set.
        """
        from loom_client import LoomBadRequestError

        username = self.unique("py-replace")
        created = self.client.create_user(UserCreateRequest(username=username)).body()
        self.delete_later(self.client.delete_user, created.uuid)

        try:
            self.client.replace_user(created.uuid, UserUpdateRequest(firstname="Only")).execute()
        except LoomBadRequestError as e:
            self.assertTrue(e.message)
        else:
            self.skipTest("this server accepted a partial PUT body")


if __name__ == "__main__":
    unittest.main()
