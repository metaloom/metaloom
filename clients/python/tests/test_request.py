"""Request terminals: body/execute, seek paging, binary downloads, asset identity."""

from __future__ import annotations

import os
import tempfile
import unittest

from loom_client import AssetId
from loom_client.response import _parse_filename

from .stubserver import StubServerTestCase


class TerminalTest(StubServerTestCase):
    def test_body_returns_the_model_and_execute_returns_the_envelope(self):
        self.stub.enqueue(json_body={"version": "9.9.9"})
        self.assertEqual(self.client.rest_info().body().version, "9.9.9")

        self.stub.enqueue(201, json_body={"version": "9.9.9"})
        response = self.client.rest_info().execute()
        self.assertEqual(response.status, 201)
        self.assertEqual(response.body.version, "9.9.9")

    def test_query_parameter_methods_chain(self):
        request = self.client.list_users()
        self.assertIs(request.limit(5), request)
        self.assertIs(request.sort("username"), request)


class PagingTest(StubServerTestCase):
    def _page(self, names, last_uuid):
        return {
            "data": [{"username": n} for n in names],
            "_metainfo": {"lastUuid": last_uuid, "perPage": 2, "totalCount": 5},
        }

    def test_iter_follows_the_cursor_across_pages(self):
        self.stub.enqueue(json_body=self._page(["a", "b"], "uuid-b"))
        self.stub.enqueue(json_body=self._page(["c", "d"], "uuid-d"))
        self.stub.enqueue(json_body=self._page([], None))

        names = [u.username for u in self.client.list_users().iter(page_size=2)]
        self.assertEqual(names, ["a", "b", "c", "d"])

        self.assertEqual(self.stub.requests[0].query_pairs, [("limit", "2")])
        self.assertEqual(self.stub.requests[1].query_pairs, [("limit", "2"), ("from", "uuid-b")])
        self.assertEqual(self.stub.requests[2].query_pairs, [("limit", "2"), ("from", "uuid-d")])

    def test_iter_does_not_accumulate_from_parameters(self):
        self.stub.enqueue(json_body=self._page(["a"], "u1"))
        self.stub.enqueue(json_body=self._page([], None))
        list(self.client.list_users().iter())
        froms = [k for k, _ in self.stub.requests[1].query_pairs if k == "from"]
        self.assertEqual(froms, ["from"])

    def test_iter_stops_when_the_cursor_repeats(self):
        # A server that keeps handing back the same cursor would otherwise loop
        # forever. The guard trips the second time the cursor is seen, so one extra
        # page is consumed before iteration ends -- what matters is that it ends.
        for _ in range(5):
            self.stub.enqueue(json_body=self._page(["a"], "same"))
        names = list(self.client.list_users().iter())
        self.assertEqual(len(names), 2)
        self.assertEqual(len(self.stub.requests), 2)

    def test_iter_on_a_non_list_response_raises(self):
        self.stub.enqueue(json_body={"version": "1"})
        with self.assertRaises(TypeError):
            list(self.client.rest_info().iter())

    def test_list_response_is_iterable_and_sized(self):
        self.stub.enqueue(json_body=self._page(["a", "b"], "u"))
        page = self.client.list_users().body()
        self.assertEqual(len(page), 2)
        self.assertEqual([u.username for u in page], ["a", "b"])


class BinaryDownloadTest(StubServerTestCase):
    def test_stream_is_read_and_saved(self):
        payload = bytes(range(256)) * 8
        self.stub.enqueue(
            raw=payload,
            headers={
                "Content-Type": "image/jpeg",
                "Content-Disposition": "attachment; filename*=utf-8''h%C3%A4llo.jpg",
            },
        )
        with self.client._download("assets/a1/binary/data").body() as binary:
            self.assertEqual(binary.content_type, "image/jpeg")
            self.assertEqual(binary.filename, "hällo.jpg")
            self.assertEqual(binary.content_length, len(payload))
            self.assertEqual(binary.read(), payload)

    def test_save_writes_the_file_and_closes_the_stream(self):
        payload = b"binary-bytes"
        self.stub.enqueue(raw=payload)
        target = os.path.join(tempfile.mkdtemp(), "out.bin")
        binary = self.client._download("assets/a1/binary/data").body()
        written = binary.save(target)
        self.assertEqual(written, len(payload))
        with open(target, "rb") as f:
            self.assertEqual(f.read(), payload)
        self.assertTrue(binary._closed)

    def test_chunks_yields_the_whole_body(self):
        payload = b"x" * 5000
        self.stub.enqueue(raw=payload)
        with self.client._download("assets/a1/binary/data").body() as binary:
            self.assertEqual(b"".join(binary.chunks(1024)), payload)

    def test_close_is_idempotent(self):
        self.stub.enqueue(raw=b"x")
        binary = self.client._download("assets/a1/binary/data").body()
        binary.close()
        binary.close()


class FilenameParsingTest(unittest.TestCase):
    def test_rfc5987_form(self):
        self.assertEqual(_parse_filename("attachment; filename*=utf-8''h%C3%A4llo.jpg"), "hällo.jpg")

    def test_plain_form_is_also_accepted(self):
        # The Java client returns None here; tolerating it costs nothing and a proxy
        # rewriting the header is a real possibility.
        self.assertEqual(_parse_filename('attachment; filename="plain.jpg"'), "plain.jpg")

    def test_missing_header(self):
        self.assertIsNone(_parse_filename(None))
        self.assertIsNone(_parse_filename("attachment"))


class AssetIdTest(unittest.TestCase):
    UUID = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    SHA512 = "cf" * 64

    def test_uuid_is_detected(self):
        asset = AssetId.of(self.UUID)
        self.assertTrue(asset.is_uuid)
        self.assertEqual(asset.path, f"assets/{self.UUID}")

    def test_sha512_is_detected(self):
        asset = AssetId.of(self.SHA512)
        self.assertFalse(asset.is_uuid)
        self.assertEqual(asset.path, f"assets/sha512/{self.SHA512}")

    def test_uuid_object_is_accepted(self):
        import uuid as uuid_mod

        value = uuid_mod.uuid4()
        self.assertEqual(AssetId.of(value).path, f"assets/{value}")

    def test_asset_id_passes_through(self):
        asset = AssetId.of(self.UUID)
        self.assertIs(AssetId.of(asset), asset)

    def test_case_is_normalised(self):
        self.assertEqual(AssetId.of(self.SHA512.upper()).value, self.SHA512)

    def test_nonsense_is_rejected_with_an_explanation(self):
        with self.assertRaises(ValueError) as caught:
            AssetId.of("not-an-id")
        self.assertIn("SHA-512", str(caught.exception))

    def test_explicit_constructors_skip_sniffing(self):
        self.assertTrue(AssetId.of_uuid("anything").is_uuid)
        self.assertFalse(AssetId.of_sha512("anything").is_uuid)


if __name__ == "__main__":
    unittest.main()
